import importlib.util
import math
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'android/repo'))

spec = importlib.util.spec_from_file_location('bridge_worker', Path(__file__).resolve().parents[1] / 'android/repo/bridge_worker.py')
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


class FixValidationTests(unittest.TestCase):
    def test_fusion_mode_accepts_fused_but_old_modes_do_not(self):
        data = {'run_id':'r','sequence':1,'source':'fused','latitude':30.,'longitude':104.,
                'accuracy_m':3.,'received_monotonic':100.,'age_at_receive_ms':0.}
        self.assertEqual(worker.validate_fix(data,'r','fusion',101.),(1,30.,104.))
        self.assertIsNone(worker.validate_fix(data,'r','hybrid',101.))
        self.assertIsNone(worker.validate_fix(data,'r','gps',101.))

    def setUp(self):
        self.fix = dict(run_id='run', sequence=4, source='gps', latitude=31.2, longitude=121.4, accuracy_m=5.0, received_monotonic=100.0, age_at_receive_ms=500.0)

    def check(self, value=None, now=105):
        return worker.validate_fix(self.fix if value is None else value, 'run', 'gps', now)

    def test_inertial_independent_deadlines_and_metadata(self):
        fix=dict(self.fix,source='inertial',accuracy_m=20,accuracy_kind='model_estimate',
                 anchor_source='gps',anchor_elapsed_realtime_nanos=98_000_000_000,
                 elapsed_realtime_nanos=100_000_000_000,prediction_age_ms=2000,
                 received_monotonic=100.1,age_at_receive_ms=100)
        self.assertIsNotNone(worker.validate_fix(fix,'run','fusion',100.5))
        self.assertIsNone(worker.validate_fix(fix,'run','fusion',100.501))
        self.assertIsNone(worker.validate_fix(fix,'run','gps',100.2))
        for change in ({'anchor_source':'fused'},{'accuracy_kind':'measured'},{'accuracy_m':float('inf')},
                       {'prediction_age_ms':1999},{'elapsed_realtime_nanos':101_000_000_000}):
            with self.subTest(change=change):
                self.assertIsNone(worker.validate_fix(dict(fix,**change),'run','fusion',100.2))
        late=dict(fix,anchor_elapsed_realtime_nanos=92_000_000_000,prediction_age_ms=8000)
        self.assertIsNotNone(worker.validate_fix(late,'run','fusion',100.1))
        continuous=dict(fix,anchor_elapsed_realtime_nanos=1_000_000_000,
            elapsed_realtime_nanos=3601_000_000_000,prediction_age_ms=3600_000,
            accuracy_m=1e12,received_monotonic=3601.1)
        self.assertIsNotNone(worker.validate_fix(continuous,'run','fusion',3601.2))
        self.assertIsNone(worker.validate_fix(continuous,'run','fusion',3601.6))

    def test_hybrid_source_restrictions(self):
        gps = dict(self.fix, source='gps')
        network = dict(self.fix, source='network')
        test = dict(self.fix, source='test')
        self.assertIsNotNone(worker.validate_fix(gps, 'run', 'hybrid', 105))
        self.assertIsNotNone(worker.validate_fix(network, 'run', 'hybrid', 105))
        self.assertIsNone(worker.validate_fix(test, 'run', 'hybrid', 105))
        self.assertIsNone(worker.validate_fix(network, 'run', 'gps', 105))
        self.assertIsNotNone(worker.validate_fix(test, 'run', 'test', 105))

    def test_valid_and_expiry_boundary(self):
        self.assertIsNotNone(self.check(now=109.5))
        self.assertIsNone(self.check(now=109.501))

    def test_wrong_run_source_and_future(self):
        for key, value in [('run_id', 'old'), ('source', 'test'), ('received_monotonic', 106.0)]:
            obj = dict(self.fix, **{key: value})
            self.assertIsNone(self.check(obj))

    def test_rejects_bad_values(self):
        for key, value in [('latitude',91), ('longitude',181), ('accuracy_m',-1), ('age_at_receive_ms',-1), ('sequence',True), ('sequence',-1), ('longitude',math.nan), ('received_monotonic', math.inf)]:
            self.assertIsNone(self.check(dict(self.fix, **{key:value})))

    def test_coordinate_and_age_bounds(self):
        self.assertIsNotNone(self.check(dict(self.fix, latitude=-90, longitude=180, age_at_receive_ms=10000), now=100))
        self.assertIsNone(self.check(dict(self.fix, age_at_receive_ms=10001), now=100))

    def test_status_persists_clear_pending(self):
        with tempfile.TemporaryDirectory() as d:
            status = worker.Status(Path(d), 'run')
            status.write(state='running', clear_pending=True)
            state = json.loads((Path(d) / 'worker.state.json').read_text())
            self.assertEqual(state['run_id'], 'run')
            self.assertTrue(state['clear_pending'])
            self.assertNotIn('latitude', state)

    def test_status_heartbeat_and_changes(self):
        from unittest.mock import patch
        # Mock the worker clock boundary on both BOOTTIME and monotonic platforms.
        with tempfile.TemporaryDirectory() as d, patch.object(worker, 'elapsed_time', side_effect=[0.0, 0.25, 0.5, 1.1, 1.2]), patch.object(worker, 'atomic_json') as write:
            status = worker.Status(Path(d), 'run')
            status.write(state='waiting')
            status.write(connected=False)
            self.assertEqual(write.call_count, 1)
            status.write()
            self.assertEqual(write.call_count, 2)
            status.write(clear_pending=False)
            self.assertEqual(write.call_count, 3)

    def test_read_fix_distinguishes_missing_and_malformed(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'fix.json'
            self.assertEqual(worker.read_fix(path), (False, None))
            path.write_text('{')
            self.assertEqual(worker.read_fix(path), (True, None))

    def test_rejects_missing_and_non_object(self):
        self.assertIsNone(self.check({}))
        self.assertIsNone(self.check([]))


if __name__ == '__main__': unittest.main()

class WorkerLifecycleTests(unittest.IsolatedAsyncioTestCase):
    async def test_stop_clear_and_reconnect(self):
        import asyncio
        import sys
        import time
        import types
        from unittest.mock import patch

        events = []
        modules = {}
        for name in ('pymobiledevice3', 'pymobiledevice3.lockdown', 'pymobiledevice3.remote',
                     'pymobiledevice3.remote.userspace_tunnel', 'pymobiledevice3.remote.tunnel_service',
                     'pymobiledevice3.services', 'pymobiledevice3.services.dvt',
                     'pymobiledevice3.services.dvt.instruments',
                     'pymobiledevice3.services.dvt.instruments.dvt_provider',
                     'pymobiledevice3.services.dvt.instruments.location_simulation', 'pymobiledevice3.usbmux'):
            modules[name] = types.ModuleType(name)
        userspace = modules['pymobiledevice3.remote.userspace_tunnel']
        modules['pymobiledevice3.remote'].userspace_tunnel = userspace
        userspace._create_no_root_tunnel_provider = None
        attempts = 0

        class Service:
            remote_identifier = 'target'
            async def close(self): events.append('provider_close')

        async def wifi_services(**kwargs):
            self.assertEqual(kwargs['udid'], 'target')
            return [Service()]

        class Tunnel:
            def __init__(self, serial, autopair, remotepairing_fallback):
                self.serial = serial
            async def aopen(self):
                nonlocal attempts
                attempts += 1
                if attempts == 1:
                    raise OSError('connection lost')
                await userspace._create_no_root_tunnel_provider(self.serial, False, False)
                return object()
            async def aclose(self): events.append('tunnel_close')

        class Dvt:
            def __init__(self, rsd): pass
            async def __aenter__(self): events.append('dvt_open'); return self
            async def __aexit__(self, *args): events.append('dvt_close')

        class Location:
            def __init__(self, dvt): pass
            async def __aenter__(self): return self
            async def __aexit__(self, *args): events.append('location_close')
            async def clear(self): events.append('clear')
            async def set(self, lat, lon):
                current = json.loads((directory / 'worker.state.json').read_text())
                self_test.assertTrue(current['clear_pending'])
                events.append('set')
                (directory / 'stop.json').write_text(json.dumps({'run_id': 'run'}))

        self_test = self
        userspace.UserspaceRsdTunnel = Tunnel
        tunnel_service = modules['pymobiledevice3.remote.tunnel_service']
        tunnel_service.CoreDeviceTunnelProxy = type('CoreDeviceTunnelProxy', (), {})
        tunnel_service.get_remote_pairing_tunnel_services = wifi_services
        modules['pymobiledevice3.services.dvt.instruments.dvt_provider'].DvtProvider = Dvt
        modules['pymobiledevice3.services.dvt.instruments.location_simulation'].LocationSimulation = Location
        modules['pymobiledevice3.lockdown'].create_using_usbmux = None
        modules['pymobiledevice3.usbmux'].select_device = None

        with tempfile.TemporaryDirectory() as d:
            directory = Path(d)
            args = types.SimpleNamespace(session_dir=directory, run_id='run', identifier='target', transport='wifi', mode='hybrid')
            (directory / 'fix.json').write_text(json.dumps(dict(run_id='run', sequence=1, source='network', latitude=1, longitude=2, accuracy_m=5, received_monotonic=time.monotonic(), age_at_receive_ms=0)))
            with patch.dict(sys.modules, modules):
                await asyncio.wait_for(worker.run_worker(args), 8)
            state = json.loads((directory / 'worker.state.json').read_text())
            self.assertEqual(attempts, 2)
            self.assertEqual(events.count('set'), 1)
            self.assertEqual(events.count('clear'), 2)
            self.assertLess(events.index('clear'), events.index('set'))
            self.assertEqual(state['state'], 'stopped')
            self.assertFalse(state['clear_pending'])
            self.assertEqual(state['last_sequence'], 1)
            self.assertEqual(state['last_source'], 'network')
            self.assertEqual(state['last_accuracy_m'], 5)

class InertialLifecycleTests(unittest.IsolatedAsyncioTestCase):
    async def test_collected_continuous_ins_expires_during_set(self):
        import asyncio
        import sys
        import types
        from unittest.mock import patch

        import bridge_fusion
        import bridge_service
        names=('pymobiledevice3', 'pymobiledevice3.lockdown', 'pymobiledevice3.remote',
               'pymobiledevice3.remote.userspace_tunnel', 'pymobiledevice3.remote.tunnel_service',
               'pymobiledevice3.services', 'pymobiledevice3.services.dvt',
               'pymobiledevice3.services.dvt.instruments',
               'pymobiledevice3.services.dvt.instruments.dvt_provider',
               'pymobiledevice3.services.dvt.instruments.location_simulation', 'pymobiledevice3.usbmux')
        modules={name:types.ModuleType(name) for name in names}
        userspace=modules['pymobiledevice3.remote.userspace_tunnel']
        modules['pymobiledevice3.remote'].userspace_tunnel=userspace
        userspace._create_no_root_tunnel_provider=None
        events=[]
        class Tunnel:
            def __init__(self,*args,**kwargs): pass
            async def aopen(self): return object()
            async def aclose(self): pass
        class Dvt:
            def __init__(self,rsd): pass
            async def __aenter__(self): return self
            async def __aexit__(self,*args): pass
        class Location:
            def __init__(self,dvt): pass
            async def __aenter__(self): return self
            async def __aexit__(self,*args): pass
            async def clear(self):
                events.append('clear')
                if 'set' in events:
                    (directory/'stop.json').write_text(json.dumps({'run_id':'run'}))
            async def set(self,lat,lon):
                events.append('set')
                try: await asyncio.sleep(2)
                finally: events.append('set_finished')
        userspace.UserspaceRsdTunnel=Tunnel
        tunnel=modules['pymobiledevice3.remote.tunnel_service']
        tunnel.CoreDeviceTunnelProxy=type('CoreDeviceTunnelProxy',(),{})
        tunnel.get_remote_pairing_tunnel_services=None
        modules['pymobiledevice3.services.dvt.instruments.dvt_provider'].DvtProvider=Dvt
        modules['pymobiledevice3.services.dvt.instruments.location_simulation'].LocationSimulation=Location
        modules['pymobiledevice3.lockdown'].create_using_usbmux=None
        modules['pymobiledevice3.usbmux'].select_device=None
        with tempfile.TemporaryDirectory() as d:
            directory=Path(d)
            args=types.SimpleNamespace(session_dir=directory,run_id='run',identifier='target',transport='wifi',mode='fusion')
            async def provide():
                while not (directory/'worker.state.json').exists() or 'clear' not in events:
                    await asyncio.sleep(.01)
                # Initial clear waits 0.5 seconds before entering the loop.
                await asyncio.sleep(.52)
                now=worker.elapsed_time()
                ns=int(now*1e9)
                sample=dict(sequence=1,source='inertial',latitude=1,longitude=2,
                    accuracy_m=20,accuracy_kind='model_estimate',anchor_source='gps',coordinate_system='WGS84',
                    anchor_elapsed_realtime_nanos=ns-9_000_000_000,elapsed_realtime_nanos=ns,
                    prediction_age_ms=9000,timestamp_ms=1000,is_mock=False)
                raw=dict(protocol=1,session_id='1c68b7a8-506c-4dc9-a137-810a20b8e5c2',
                    running=True,elapsed_realtime_nanos=ns,sample=sample,
                    providers={name:dict(available=True,enabled=True,error=None)
                        for name in ('gps','fused','network')})
                done=asyncio.Event()
                with patch.object(bridge_fusion,'request_snapshot',return_value=raw):
                    collector=asyncio.create_task(bridge_service.collect_fusion(directory,'run',done,'key'))
                    try:
                        while not (directory/'fix.json').exists():
                            await asyncio.sleep(.01)
                    finally:
                        done.set()
                        await collector
            with patch.dict(sys.modules,modules):
                await asyncio.wait_for(asyncio.gather(worker.run_worker(args),provide()),5)
            self.assertIn('set',events)
            self.assertLess(events.index('set_finished'),len(events)-1)
            self.assertEqual(events[-1],'clear')
