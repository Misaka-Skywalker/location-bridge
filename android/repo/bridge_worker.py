#!/usr/bin/env python3
"""Run a bounded-I/O DVT location worker for a single native-supervisor session."""
import argparse
import asyncio
import json
import logging
import math
import os
import signal
import tempfile
import time
from importlib.metadata import version
from pathlib import Path

MAX_AGE = 10.0


def elapsed_time():
    # Same kernel boot clock in native Termux and PRoot; includes device sleep.
    if hasattr(time, 'CLOCK_BOOTTIME'):
        return time.clock_gettime(time.CLOCK_BOOTTIME)
    return time.monotonic()  # Desktop tests; these clocks coincide without suspend.


def validate_fix(data, run_id, mode, now):
    sources = {'gps': ('gps',), 'test': ('test',), 'hybrid': ('gps', 'network'), 'fusion': ('fused','gps','network','inertial')}
    if not isinstance(data, dict) or data.get('run_id') != run_id or data.get('source') not in sources.get(mode, ()):
        return None
    sequence = data.get('sequence')
    if type(sequence) is not int or sequence < 0:
        return None
    fields = ('latitude', 'longitude', 'accuracy_m', 'received_monotonic', 'age_at_receive_ms')
    values = [data.get(key) for key in fields]
    if any(type(x) not in (int, float) or not math.isfinite(x) for x in values):
        return None
    lat, lon, accuracy, received, age_ms = values
    if not (-90 <= lat <= 90 and -180 <= lon <= 180 and accuracy >= 0 and age_ms >= 0 and received <= now):
        return None
    if data['source'] == 'inertial':
        anchor = data.get('anchor_elapsed_realtime_nanos')
        sample = data.get('elapsed_realtime_nanos')
        predicted = data.get('prediction_age_ms')
        if (data.get('accuracy_kind') != 'model_estimate' or data.get('anchor_source') != 'gps'
                or type(anchor) is not int or type(sample) is not int or anchor <= 0 or sample < anchor
                or type(predicted) not in (int, float) or not math.isfinite(predicted)
                or not 1500 < predicted <= 8000 or accuracy > 150
                or not math.isclose(predicted, (sample-anchor)/1e6, rel_tol=0, abs_tol=1e-6)):
            return None
        if (sample / 1e9 > received or now - sample / 1e9 > .5 or now - anchor / 1e9 > 8
                or now - received + age_ms / 1000 > .500000001):
            return None
    elif now - received + age_ms / 1000 > MAX_AGE:
        return None
    return sequence, lat, lon


def read_json(path):
    try:
        with path.open(encoding='utf-8') as f:
            return json.load(f)
    except (OSError, ValueError, UnicodeError):
        return None


def read_fix(path):
    try:
        with path.open(encoding='utf-8') as f:
            return True, json.load(f)
    except FileNotFoundError:
        return False, None
    except (OSError, ValueError, UnicodeError):
        return True, None


def atomic_json(path, data):
    fd, temp = tempfile.mkstemp(prefix='.worker-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as f:
            json.dump(data, f, separators=(',', ':'))
            f.flush()
            os.fsync(f.fileno())
        os.replace(temp, path)
    finally:
        try:
            os.unlink(temp)
        except FileNotFoundError:
            pass


class Status:
    def __init__(self, directory, run_id):
        self.path = directory / 'worker.state.json'
        self.data = dict(run_id=run_id, state='waiting', updated_monotonic=0, connected=False,
                         last_sequence=None, last_source=None, last_accuracy_m=None, last_accuracy_kind=None, last_prediction_age_ms=None, clear_pending=True, last_error=None)
        self._last_write = float('-inf')
        self.write()

    def write(self, **changes):
        changed = any(self.data.get(key) != value for key, value in changes.items())
        self.data.update(changes)
        now = elapsed_time()
        if changed or now - self._last_write >= 1.0:
            self.data['updated_monotonic'] = now
            atomic_json(self.path, self.data)
            self._last_write = now


async def bounded(coro, timeout=10):
    return await asyncio.wait_for(coro, timeout)


async def run_worker(args):
    from pymobiledevice3.lockdown import create_using_usbmux
    from pymobiledevice3.remote import userspace_tunnel
    from pymobiledevice3.remote.tunnel_service import CoreDeviceTunnelProxy, get_remote_pairing_tunnel_services
    from pymobiledevice3.services.dvt.instruments.dvt_provider import DvtProvider
    from pymobiledevice3.services.dvt.instruments.location_simulation import LocationSimulation
    from pymobiledevice3.usbmux import select_device

    directory = args.session_dir
    status = Status(directory, args.run_id)
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)
    initial_provider = userspace_tunnel._create_no_root_tunnel_provider
    backoff = 2

    def stopping():
        request = read_json(directory / 'stop.json')
        return stop.is_set() or isinstance(request, dict) and request.get('run_id') == args.run_id

    class StopRequested(Exception):
        pass

    async def interruptible(coro, timeout):
        task = asyncio.create_task(coro)
        deadline = loop.time() + timeout
        try:
            while True:
                done, _ = await asyncio.wait({task}, timeout=min(0.25, max(0, deadline - loop.time())))
                if task in done:
                    return await task
                if stopping():
                    raise StopRequested()
                if loop.time() >= deadline:
                    raise TimeoutError()
        finally:
            if not task.done():
                task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    async def provider(serial, autopair, remotepairing_fallback=True):
        if serial != args.identifier or autopair:
            raise RuntimeError('unexpected provider parameters')
        if args.transport == 'usb':
            if remotepairing_fallback:
                raise RuntimeError('USB fallback forbidden')
            device = await select_device(udid=serial, connection_type='USB', usbmux_address='/mnt/usbmuxd')
            if device is None or device.connection_type != 'USB' or not device.matches_udid(serial):
                raise RuntimeError('USB target not found')
            client = await create_using_usbmux(serial=serial, connection_type='USB', autopair=False, usbmux_address='/mnt/usbmuxd')
            try:
                if not client.paired:
                    raise RuntimeError('USB lockdown pairing required')
                return await CoreDeviceTunnelProxy.create(client), client
            except BaseException:
                await client.close()
                raise
        services = await get_remote_pairing_tunnel_services(bonjour_timeout=5, udid=serial)
        if not services:
            raise RuntimeError('paired wireless device not found')
        try:
            if any(service.remote_identifier != serial for service in services):
                raise RuntimeError('wireless identifier mismatch')
            for extra in services[1:]:
                await bounded(extra.close(), 5)
        except BaseException:
            for service in services:
                try:
                    await bounded(service.close(), 5)
                except Exception:
                    logging.warning("wireless provider close failed")
            raise
        return services[0], None

    async def cleanup_step(name, coro):
        try:
            await bounded(coro, 5)
            return True
        except Exception as exc:
            logging.warning('%s failed: %s', name, type(exc).__name__)
            return False

    async def clear(location):
        status.write(clear_pending=True)
        await bounded(location.clear(), 5)
        await asyncio.sleep(0.5)
        status.write(clear_pending=False)

    try:
        while not stopping():
            tunnel = userspace_tunnel.UserspaceRsdTunnel(serial=args.identifier, autopair=False, remotepairing_fallback=False)
            dvt = location = None
            connected = False
            first_error = None
            try:
                status.write(state='reconnecting' if backoff > 2 else 'waiting', connected=False)
                userspace_tunnel._create_no_root_tunnel_provider = provider
                try:
                    rsd = await interruptible(tunnel.aopen(), 20)
                finally:
                    userspace_tunnel._create_no_root_tunnel_provider = initial_provider
                dvt = DvtProvider(rsd)
                await interruptible(dvt.__aenter__(), 15)
                location = LocationSimulation(dvt)
                await interruptible(location.__aenter__(), 15)
                connected = True
                status.write(connected=True, state='waiting')
                # Clear first even if the previous process/transport died unexpectedly.
                await clear(location)
                while not stopping():
                    present, raw_fix = read_fix(directory / 'fix.json')
                    fix = validate_fix(raw_fix, args.run_id, args.mode, elapsed_time())
                    if fix is None:
                        if status.data['clear_pending']:
                            await clear(location)
                        status.write(state='stale' if present else 'waiting', last_error=None)
                    else:
                        seq, lat, lon = fix
                        last = status.data['last_sequence']
                        if last is None or seq > last:
                            # Durable write precedes any call which might inject a position.
                            status.write(clear_pending=True, state='running', last_error=None)
                            if validate_fix(raw_fix, args.run_id, args.mode, elapsed_time()) is None:
                                await clear(location)
                                status.write(state='stale')
                            else:
                                set_timeout = 10.0
                                if raw_fix['source'] == 'inertial':
                                    now = elapsed_time()
                                    set_timeout = min(.5 - (now - raw_fix['elapsed_realtime_nanos']/1e9),
                                                      8 - (now - raw_fix['anchor_elapsed_realtime_nanos']/1e9),
                                                      .5 - (now - raw_fix['received_monotonic'] + raw_fix['age_at_receive_ms']/1000))
                                if set_timeout <= 0:
                                    await clear(location)
                                    status.write(state='stale')
                                    continue
                                await interruptible(location.set(lat, lon), set_timeout)
                                status.write(last_sequence=seq, last_source=raw_fix['source'],
                                             last_accuracy_m=raw_fix['accuracy_m'],
                                             last_accuracy_kind=raw_fix.get('accuracy_kind'),
                                             last_prediction_age_ms=raw_fix.get('prediction_age_ms'), state='running')
                                if raw_fix['source'] == 'inertial' and validate_fix(raw_fix, args.run_id, args.mode, elapsed_time()) is None:
                                    await clear(location)
                                    status.write(state='stale')
                                backoff = 2
                        elif status.data['clear_pending']:
                            status.write(state='running')
                        else:
                            status.write(state='waiting')
                    try:
                        await asyncio.wait_for(stop.wait(), 0.1 if isinstance(raw_fix, dict) and raw_fix.get('source') == 'inertial' else 0.25)
                    except TimeoutError:
                        pass
            except asyncio.CancelledError:
                raise
            except StopRequested:
                pass
            except Exception as exc:
                first_error = type(exc).__name__
                logging.warning('connection cycle failed: %s', first_error)
            finally:
                userspace_tunnel._create_no_root_tunnel_provider = initial_provider
                if location is not None:
                    try:
                        await clear(location)
                    except Exception as exc:
                        first_error = first_error or type(exc).__name__
                        logging.warning('clear failed: %s', type(exc).__name__)
                        status.write(clear_pending=True)
                    if not await cleanup_step('location close', location.__aexit__(None, None, None)):
                        first_error = first_error or 'LocationCloseError'
                if dvt is not None and not await cleanup_step('DVT close', dvt.__aexit__(None, None, None)):
                    first_error = first_error or 'DvtCloseError'
                if not await cleanup_step('tunnel close', tunnel.aclose()):
                    first_error = first_error or 'TunnelCloseError'
                connected = False
                status.write(connected=False, last_error=first_error)
            if stopping():
                break
            status.write(state='reconnecting', connected=False, last_error=first_error)
            deadline = loop.time() + backoff
            while not stopping() and loop.time() < deadline:
                try:
                    await asyncio.wait_for(stop.wait(), min(0.25, max(0.001, deadline - loop.time())))
                except TimeoutError:
                    pass
            backoff = min(10, backoff * 2)
        pending = status.data['clear_pending']
        status.write(state='error' if pending else 'stopped', connected=False,
                     last_error='ClearUnconfirmed' if pending else status.data['last_error'])
    finally:
        userspace_tunnel._create_no_root_tunnel_provider = initial_provider
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.remove_signal_handler(sig)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--identifier', required=True)
    parser.add_argument('--transport', required=True, choices=('usb', 'wifi'))
    parser.add_argument('--session-dir', type=Path, required=True)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--mode', choices=('gps', 'test', 'hybrid', 'fusion'), required=True)
    args = parser.parse_args()
    if not args.identifier or not args.run_id or len(args.run_id) > 128:
        parser.error('identifier and run-id must be nonempty; run-id must be at most 128 characters')
    if not args.session_dir.is_dir():
        parser.error('session-dir must already exist')
    if args.transport == 'usb' and os.environ.get('USBMUXD_SOCKET_ADDRESS') != '/mnt/usbmuxd':
        parser.error('USBMUXD_SOCKET_ADDRESS must be /mnt/usbmuxd')
    if version('pymobiledevice3') != '11.52.1':
        parser.error('requires pymobiledevice3 11.52.1')
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    asyncio.run(run_worker(args))


if __name__ == '__main__':
    main()
