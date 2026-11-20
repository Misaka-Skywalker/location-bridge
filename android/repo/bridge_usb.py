"""Authenticated, bounded callback for the local Android USB role helper."""
import asyncio
import contextlib
import hashlib
import hmac
import json
import re
import secrets
import time

MAX_RESPONSE_BYTES = 4096
RESULTS = {'host', 'device', 'permission_required', 'disconnected', 'ambiguous',
           'unsupported', 'switch_failed', 'unavailable'}
ROLES = {'host', 'device', 'none', 'unknown'}
POWER_ROLES = {'sink', 'source', 'none', 'unknown'}


def decode_response(data, key, nonce, operation='prepare'):
    def unique(pairs):
        result = {}
        for name, value in pairs:
            if name in result: raise ValueError('duplicate field')
            result[name] = value
        return result
    if len(data) > MAX_RESPONSE_BYTES or not data.endswith(b'\n'):
        raise ValueError('invalid response size')
    try:
        envelope = json.loads(data, object_pairs_hook=unique)
        if not isinstance(envelope, dict) or set(envelope) != {'nonce', 'payload', 'mac'}:
            raise ValueError('invalid envelope')
        payload = envelope['payload']
        if envelope['nonce'] != nonce or not isinstance(payload, str) or not isinstance(envelope['mac'], str):
            raise ValueError('invalid envelope')
        mac = hmac.new(key, ('usb_result\n' + nonce + '\n' + payload).encode(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(mac, envelope['mac']): raise ValueError('invalid mac')
        value = json.loads(payload, object_pairs_hook=unique)
        fields = {'protocol', 'result', 'changed', 'data_role', 'power_role'}
        results = RESULTS
        if operation == 'auto':
            fields.add('devices')
            results = RESULTS | {'adb_active'}
        if (not isinstance(value, dict) or set(value) != fields
                or type(value['protocol']) is not int or value['protocol'] != 1
                or value['result'] not in results or type(value['changed']) is not bool
                or value['data_role'] not in ROLES or value['power_role'] not in POWER_ROLES):
            raise ValueError('invalid payload')
        if operation == 'auto':
            devices = value['devices']
            if (not isinstance(devices, list) or len(devices) > 32
                    or any(not isinstance(path, str) or not re.fullmatch(r'/dev/bus/usb/[0-9]{1,5}/[0-9]{1,5}', path) for path in devices)
                    or len(devices) != len(set(devices))
                    or devices and (value['result'] != 'host' or value['data_role'] != 'host')):
                raise ValueError('invalid paths')
        return value
    except (ValueError, TypeError, KeyError, UnicodeError, RecursionError):
        raise ValueError('invalid USB helper response') from None


async def request(key_hex, operation='prepare', stop_requested=lambda: False, timeout=22):
    if not isinstance(key_hex, str) or not re.fullmatch(r'[0-9a-f]{64}', key_hex): raise ValueError('invalid key')
    if operation not in ('prepare', 'inspect', 'auto'): raise ValueError('invalid operation')
    key = bytes.fromhex(key_hex)
    nonce = secrets.token_hex(32)
    loop = asyncio.get_running_loop()
    result = loop.create_future()
    clients = set()
    end = loop.time() + timeout
    async def receive(reader, writer):
        task = asyncio.current_task()
        if len(clients) >= 4:
            writer.close()
            with contextlib.suppress(OSError): await writer.wait_closed()
            return
        clients.add(task)
        try:
            raw = await asyncio.wait_for(reader.readline(), max(0, end - loop.time()))
            value = decode_response(raw, key, nonce, operation)
            if not result.done(): result.set_result(value)
        except (ValueError, asyncio.TimeoutError, OSError):
            pass
        finally:
            writer.close()
            with contextlib.suppress(OSError): await writer.wait_closed()
            clients.discard(task)
    server = await asyncio.start_server(receive, '127.0.0.1', 0, limit=MAX_RESPONSE_BYTES)
    process = None
    try:
        port = server.sockets[0].getsockname()[1]
        clock = getattr(time, 'CLOCK_BOOTTIME', time.CLOCK_MONOTONIC)
        deadline = int(time.clock_gettime(clock) * 1000) + 20000
        message = f'usb\n{operation}\n{deadline}\n{port}\n{nonce}'
        auth = hmac.new(key, message.encode(), hashlib.sha256).hexdigest()
        process = await asyncio.create_subprocess_exec('am', 'start', '-n', 'org.locationbridge.source/.UsbHostActivity',
            '--es', 'usb_operation', operation, '--el', 'usb_deadline', str(deadline), '--ei', 'usb_port', str(port),
            '--es', 'usb_nonce', nonce, '--es', 'usb_auth', auth,
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
        while loop.time() < end:
            if stop_requested(): raise InterruptedError('已停止')
            if result.done(): return result.result()
            await asyncio.sleep(min(.1, max(0, end - loop.time())))
        raise asyncio.TimeoutError('USB 角色检查超时。')
    finally:
        server.close(); await server.wait_closed()
        for task in list(clients): task.cancel()
        if clients: await asyncio.gather(*clients, return_exceptions=True)
        if process is not None:
            if process.returncode is None:
                with contextlib.suppress(ProcessLookupError): process.terminate()
            try: await asyncio.wait_for(process.wait(), 2)
            except asyncio.TimeoutError:
                with contextlib.suppress(ProcessLookupError): process.kill()
                await process.wait()
