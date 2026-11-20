import asyncio
import hashlib
import hmac
import json
from pathlib import Path
import sys
import unittest
from unittest import mock
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'android/repo'))
import bridge_usb

KEY = bytes.fromhex('11' * 32)
NONCE = '22' * 32


def response(nonce=NONCE, key=KEY, **changes):
    value = {'protocol': 1, 'result': 'host', 'changed': True, 'data_role': 'host', 'power_role': 'sink'} | changes
    payload = json.dumps(value, separators=(',', ':'))
    mac = hmac.new(key, ('usb_result\n' + nonce + '\n' + payload).encode(), hashlib.sha256).hexdigest()
    return (json.dumps({'nonce': nonce, 'payload': payload, 'mac': mac}) + '\n').encode()


class ResponseTests(unittest.TestCase):
    def test_valid(self):
        self.assertEqual(bridge_usb.decode_response(response(), KEY, NONCE)['result'], 'host')

    def test_wrong_nonce_and_forged_mac(self):
        for raw in (response(nonce='33'*32), response(key=b'x'*32)):
            with self.assertRaises(ValueError): bridge_usb.decode_response(raw, KEY, NONCE)

    def test_bounds_and_payload_validation(self):
        for raw in (b'x'*4097 + b'\n', response()[:-1], response(protocol=True), response(changed=1), response(data_role='other')):
            with self.assertRaises(ValueError): bridge_usb.decode_response(raw, KEY, NONCE)

    def test_auto_requires_valid_authenticated_device_paths(self):
        path = '/dev/bus/usb/001/002'
        self.assertEqual(bridge_usb.decode_response(response(devices=[path]), KEY, NONCE, 'auto')['devices'], [path])
        for raw in (response(), response(devices=['/dev/random']), response(devices=[path, path]),
                    response(result='disconnected', devices=[path])):
            with self.assertRaises(ValueError):
                bridge_usb.decode_response(raw, KEY, NONCE, 'auto')


class RequestTests(unittest.IsolatedAsyncioTestCase):
    async def test_timeout_closes_listener_and_process(self):
        class FakeServer:
            sockets = [mock.Mock(getsockname=lambda: ('127.0.0.1', 1234))]
            def close(self): pass
            async def wait_closed(self): pass
        process = mock.Mock(returncode=0, wait=mock.AsyncMock())
        with mock.patch.object(asyncio, 'start_server', return_value=FakeServer()), \
             mock.patch.object(asyncio, 'create_subprocess_exec', return_value=process):
            with self.assertRaises(asyncio.TimeoutError): await bridge_usb.request('11'*32, timeout=.01)
        process.wait.assert_awaited_once()

    async def test_stop_cancels_request(self):
        class FakeServer:
            sockets = [mock.Mock(getsockname=lambda: ('127.0.0.1', 1234))]
            def close(self): pass
            async def wait_closed(self): pass
        process = mock.Mock(returncode=0, wait=mock.AsyncMock())
        with mock.patch.object(asyncio, 'start_server', return_value=FakeServer()), \
             mock.patch.object(asyncio, 'create_subprocess_exec', return_value=process):
            with self.assertRaises(InterruptedError): await bridge_usb.request('11'*32, stop_requested=lambda: True)

class SupervisorUsbTests(unittest.IsolatedAsyncioTestCase):
    async def run_case(self, lists, helper_result, mode='hybrid', transport='usb'):
        import tempfile
        import types
        import bridge_service as service
        calls = []
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); session = root/'run'; session.mkdir()
            (root/'source-config.json').write_text(json.dumps({'package':'org.locationbridge.source','helper_key':'11'*32}))
            info = {'run_id':'run-'+'a'*32,'mode':mode,'transport':transport,'session_dir':str(session)}
            async def bounded(*args, timeout=5):
                calls.append(args)
                if args[:2] == ('termux-usb','-l'):
                    return 0, json.dumps(lists.pop(0) if len(lists) > 1 else lists[0]).encode(), b''
                return 0, b'', b''
            async def helper(*args, operation='prepare', **kwargs):
                calls.append(('helper', operation))
                return helper_result
            # Stop immediately before launching an actual daemon/worker.
            async def launch(*args, **kwargs):
                calls.append(('launch', *args))
                calls.append(('launch',))
                raise RuntimeError('test launch reached')
            with mock.patch.object(service,'TASK',root), mock.patch.object(service,'APP',root), \
                 mock.patch.object(service,'bounded',bounded), mock.patch.object(bridge_usb,'request',helper), \
                 mock.patch.object(asyncio,'create_subprocess_exec',launch):
                await service.supervise(types.SimpleNamespace(mode=mode,transport=transport,duration=90,udid='test-target'),info)
            marker = json.loads((session/'final.status.json').read_text())
        return calls, marker

    async def test_empty_then_host_enumerates_once_before_daemon(self):
        calls, _ = await self.run_case([[], ['/dev/bus/usb/001/002']], {'result':'host','data_role':'host'})
        self.assertEqual(sum(c[:2] == ('termux-usb','-l') for c in calls), 2)
        self.assertIn(('helper','prepare'), calls)
        self.assertIn(('launch',), calls)

    async def test_permission_failure_never_launches_worker(self):
        calls, marker = await self.run_case([[]], {'result':'permission_required','data_role':'device'})
        self.assertNotIn(('launch',), calls)
        self.assertEqual(marker['issue'], 'usb_permission')

    async def test_wifi_does_not_request_usb_helper(self):
        calls, _ = await self.run_case([[]], {}, transport='wifi')
        self.assertFalse(any(c[0] == 'helper' for c in calls))
        self.assertFalse(any(c[:2] == ('termux-usb','-l') for c in calls))

    async def test_host_without_device_never_launches(self):
        calls, marker = await self.run_case([[]], {'result':'host','data_role':'host'})
        self.assertNotIn(('launch',), calls)
        self.assertEqual(marker['issue'], 'usb_missing')

    async def test_auto_prefers_a_usb_candidate_already_in_host_role_or_after_role_repair(self):
        path = '/dev/bus/usb/001/002'
        for repaired in (False, True):
            with self.subTest(repaired=repaired):
                calls, _ = await self.run_case([[]], {'result':'host','data_role':'host','devices':[path],
                                                     'changed':repaired}, transport='auto')
                self.assertIn(('helper','auto'), calls)
                self.assertTrue(any(c[:3] == ('launch','termux-usb','-r') and c[-1] == path for c in calls))

    async def test_auto_uses_wifi_when_no_target_usb_candidate_can_be_confirmed(self):
        for role in ('disconnected','host','permission_required','switch_failed','adb_active'):
            with self.subTest(role=role):
                calls, _ = await self.run_case([[]], {'result':role,'data_role':'unknown','devices':[]}, transport='auto')
                self.assertIn(('helper','auto'), calls)
                self.assertTrue(any(c[:2] == ('launch','proot-distro') and c[c.index('--transport') + 1] == 'wifi' for c in calls))

    async def test_auto_keeps_ambiguity_error_for_multiple_apple_usb_candidates(self):
        calls, marker = await self.run_case([[]], {'result':'host','data_role':'host',
            'devices':['/dev/bus/usb/001/002','/dev/bus/usb/001/003']}, transport='auto')
        self.assertNotIn(('launch',), calls)
        self.assertEqual(marker['issue'], 'usb_missing')

class SlowCallbackTests(unittest.IsolatedAsyncioTestCase):
    async def test_callback_remains_open_during_slow_role_switch(self):
        class Server:
            sockets = [mock.Mock(getsockname=lambda: ('127.0.0.1', 1234))]
            def close(self): pass
            async def wait_closed(self): pass
        class Writer:
            def close(self): pass
            async def wait_closed(self): pass
        async def start_server(callback, *args, **kwargs):
            async def reply():
                reader = asyncio.StreamReader()
                task = asyncio.create_task(callback(reader, Writer()))
                await asyncio.sleep(1.1)
                reader.feed_data(response())
                await task
            asyncio.create_task(reply())
            return Server()
        process = mock.Mock(returncode=0, wait=mock.AsyncMock())
        with mock.patch.object(asyncio, 'start_server', side_effect=start_server), \
             mock.patch.object(asyncio, 'create_subprocess_exec', return_value=process), \
             mock.patch.object(bridge_usb.secrets, 'token_hex', return_value=NONCE):
            value = await bridge_usb.request('11'*32, timeout=2)
        self.assertEqual(value['result'], 'host')

    async def test_forged_callback_does_not_prevent_valid_callback(self):
        class Server:
            sockets = [mock.Mock(getsockname=lambda: ('127.0.0.1', 1234))]
            def close(self): pass
            async def wait_closed(self): pass
        class Writer:
            def close(self): pass
            async def wait_closed(self): pass
        async def start_server(callback, *args, **kwargs):
            async def replies():
                for raw in (response(key=b'x'*32), response(nonce='33'*32), response()):
                    reader = asyncio.StreamReader(); reader.feed_data(raw)
                    await callback(reader, Writer())
            asyncio.create_task(replies())
            return Server()
        process = mock.Mock(returncode=0, wait=mock.AsyncMock())
        with mock.patch.object(asyncio, 'start_server', side_effect=start_server), \
             mock.patch.object(asyncio, 'create_subprocess_exec', return_value=process), \
             mock.patch.object(bridge_usb.secrets, 'token_hex', return_value=NONCE):
            value = await bridge_usb.request('11'*32, timeout=2)
        self.assertEqual(value['result'], 'host')
