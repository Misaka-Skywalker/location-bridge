import asyncio
import base64
import hashlib
import hmac
import json
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'android/repo'))
import bridge_status as bs

class BridgeStatusTests(unittest.TestCase):
    def test_fresh_connection_and_stale_worker(self):
        info={'run_id':'run-'+'a'*32,'mode':'fusion','transport':'usb'}
        worker={'run_id':info['run_id'],'state':'running','connected':True,'updated_monotonic':100,
                'last_source':'gps','last_accuracy_m':5,'last_sequence':1,'clear_pending':True}
        status=bs.make_status(info,worker,None,phase='connecting',issue='none',now=102,utc_ms=1000)
        self.assertEqual((status['phase'],status['connected']),('connected',True))
        status=bs.make_status(info,worker,None,phase='connecting',issue='none',now=106,utc_ms=1000)
        self.assertEqual((status['phase'],status['connected'],status['issue']),('unknown',None,'worker_stale'))
        self.assertEqual(bs.make_status(info,worker,None,phase='stopping',issue='none',now=102,utc_ms=1000)['phase'],'stopping')

    def test_signed_payload_and_inertial_labels(self):
        info={'run_id':'run-'+'b'*32,'mode':'fusion','transport':'wifi'}
        worker={'run_id':info['run_id'],'state':'running','connected':True,'updated_monotonic':100,
                'last_source':'inertial','last_accuracy_m':20,'last_accuracy_kind':'model_estimate'}
        value=bs.make_status(info,worker,None,phase='connecting',issue='none',now=100,utc_ms=1000)
        payload,signature=bs.encode(value,'11'*32)
        self.assertEqual(json.loads(base64.b64decode(payload)),value)
        self.assertEqual(signature,hmac.new(bytes.fromhex('11'*32),('bridge_status\n'+payload).encode(),hashlib.sha256).hexdigest())
        self.assertEqual(value['accuracy_kind'],'model_estimate')
        self.assertIn('惯性推算',bs.brief(value))

    def test_foreign_run_and_invalid_values_are_not_connected(self):
        value=bs.make_status({'run_id':'run-'+'a'*32,'mode':'gps','transport':'usb'},
            {'run_id':'other','state':'running','connected':True,'updated_monotonic':100},None,
            phase='connecting',issue='waiting_usb',now=100,utc_ms=1000)
        self.assertEqual(value['phase'],'connecting')
        self.assertIsNone(value['connected'])

class BroadcastLifecycleTests(unittest.IsolatedAsyncioTestCase):
    async def test_usb_setup_error_broadcasts_without_starting_helper(self):
        import tempfile
        import types
        from unittest.mock import patch
        import bridge_service as service
        import bridge_usb
        with tempfile.TemporaryDirectory() as root:
            directory=Path(root); session=directory/'run'; session.mkdir()
            (directory/'source-config.json').write_text(json.dumps({'package':'org.locationbridge.source','helper_key':'11'*32}))
            info={'run_id':'run-'+'a'*32,'mode':'hybrid','transport':'usb','session_dir':str(session)}
            seen=[]
            async def bounded(*args,timeout=5):
                await asyncio.sleep(.01)
                if args[:2]==('am','broadcast'):
                    seen.append(json.loads(base64.b64decode(args[args.index('payload')+1])))
                if args[:2]==('termux-usb','-l'): return 0,b'[]',b''
                return 0,b'',b''
            with patch.object(service,'TASK',directory),patch.object(service,'APP',directory),patch.object(service,'bounded',bounded), \
                 patch.object(bridge_usb,'request',side_effect=asyncio.TimeoutError):
                await service.supervise(types.SimpleNamespace(mode='hybrid',transport='usb',duration=90),info)
            self.assertGreaterEqual(len(seen),2)
            self.assertEqual(seen[0]['phase'],'connecting')
            self.assertEqual(seen[-1]['phase'],'error')
            self.assertEqual(seen[-1]['issue'],'usb_role_failed')
            self.assertTrue(all(x['connected'] is not True for x in seen))

class StatusCommandTests(unittest.TestCase):
    def test_no_lock_without_final_record_is_not_reported_stopped(self):
        import contextlib
        import io
        import tempfile
        from unittest.mock import patch
        import bridge_service as service
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); run='run-'+'c'*32
            info={'run_id':run,'session_dir':str(root),'mode':'fusion','transport':'usb'}
            output=io.StringIO()
            with patch.object(service,'current',return_value=info),patch.object(service,'running',return_value=False), \
                 patch.object(service,'TASK',root),contextlib.redirect_stdout(output):
                service.status()
            self.assertIn('状态未知',output.getvalue())
            self.assertNotIn('桥接：已停止',output.getvalue())
            (root/'final.status.json').write_text(json.dumps({'run_id':run,'phase':'stopped','issue':'none','clear_pending':False}))
            output=io.StringIO()
            with patch.object(service,'current',return_value=info),patch.object(service,'running',return_value=False), \
                 patch.object(service,'TASK',root),contextlib.redirect_stdout(output):
                service.status()
            self.assertIn('已停止',output.getvalue())

    def test_brief_is_chinese_and_does_not_claim_sending_while_stale(self):
        info={'run_id':'run-'+'d'*32,'mode':'fusion','transport':'usb'}
        state={'run_id':info['run_id'],'updated_monotonic':100,'state':'running','connected':True,'last_source':'fused'}
        view=bs.make_status(info,state,None,phase='connecting',issue='none',now=101,utc_ms=1000)
        text=bs.brief(view)
        self.assertIn('iPad 已连接（USB）',text)
        self.assertIn('系统融合',text)
        self.assertNotIn('none',text)
        state['state']='stale'
        self.assertNotIn('正在发送',bs.brief(bs.make_status(info,state,None,phase='connecting',issue='none',now=101,utc_ms=1000)))

class StatusPublishTests(unittest.TestCase):
    def test_status_publishes_not_started_without_launching_helper(self):
        import contextlib
        import io
        import tempfile
        from unittest.mock import AsyncMock, patch
        import bridge_service as service
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            (root/'source-config.json').write_text(json.dumps({'package':'org.locationbridge.source','helper_key':'11'*32}))
            sender=AsyncMock()
            with patch.object(service,'current',return_value=None),patch.object(service,'TASK',root), \
                 patch.object(service,'publish_status',sender),contextlib.redirect_stdout(io.StringIO()):
                service.status()
            self.assertEqual(sender.await_count,1)
            view,key=sender.await_args.args
            self.assertEqual((view['phase'],view['run_id'],view['connected']),('not_started','none',False))
            self.assertEqual(key,'11'*32)

    def test_foreign_final_marker_does_not_prove_stopped(self):
        import contextlib
        import io
        import tempfile
        from unittest.mock import patch
        import bridge_service as service
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); run='run-'+'c'*32
            info={'run_id':run,'session_dir':str(root),'mode':'test','transport':'usb'}
            (root/'final.status.json').write_text(json.dumps({'run_id':'run-'+'a'*32,'phase':'stopped','issue':'none','clear_pending':False}))
            output=io.StringIO()
            with patch.object(service,'current',return_value=info),patch.object(service,'running',return_value=False), \
                 patch.object(service,'TASK',root),contextlib.redirect_stdout(output): service.status()
            self.assertIn('状态未知',output.getvalue())
            self.assertIn('清除状态未确认',output.getvalue())

class FinalWorkerStateTests(unittest.TestCase):
    def test_non_object_and_foreign_state_are_not_trusted(self):
        import bridge_service as service
        self.assertEqual(service.normalize_final_worker_state('run-a',[]),{})
        self.assertEqual(service.normalize_final_worker_state('run-a',{'run_id':'run-b','clear_pending':False}),{})
        self.assertEqual(service.normalize_final_worker_state('run-a',{'run_id':'run-a','clear_pending':False}),{'run_id':'run-a','clear_pending':False})

class FinalMarkerIntegrationTests(unittest.IsolatedAsyncioTestCase):
    async def test_non_object_worker_state_still_records_clear_unconfirmed(self):
        import tempfile
        import types
        from unittest.mock import patch
        import bridge_service as service
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); session=root/'session'; session.mkdir()
            run='run-'+'c'*32
            info={'run_id':run,'session_dir':str(session),'mode':'hybrid','transport':'wifi'}
            async def bounded(*args,timeout=5): return 0,b'',b''
            async def spawn(*args,**kwargs):
                (session/'worker.state.json').write_text('[1,2]')
                return types.SimpleNamespace(returncode=1)
            with patch.object(service,'TASK',root),patch.object(service,'APP',root), \
                 patch.object(service,'bounded',bounded),patch.object(asyncio,'create_subprocess_exec',spawn):
                await service.supervise(types.SimpleNamespace(mode='hybrid',transport='wifi',duration=90,udid='test-target'),info)
            marker=json.loads((session/'final.status.json').read_text())
            self.assertEqual((marker['run_id'],marker['phase'],marker['issue'],marker['clear_pending']),
                             (run,'error','clear_unconfirmed',True))
