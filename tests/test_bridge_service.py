import importlib.util
from pathlib import Path
import unittest
import tempfile
import subprocess
import os
import sys
import time
import json
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'android/repo'))

spec = importlib.util.spec_from_file_location('bridge_service', Path(__file__).resolve().parents[1] / 'android/repo/bridge_service.py')
service = importlib.util.module_from_spec(spec)
spec.loader.exec_module(service)

class TargetTests(unittest.TestCase):
    def test_explicit_target_and_local_configuration(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp) / 'bridge-config.json'
            config.write_text(json.dumps({'udid': 'device-123'}))
            self.assertEqual(service.target_udid(config_path=config), 'device-123')
            self.assertEqual(service.target_udid('other-456', config), 'other-456')

    def test_missing_and_invalid_targets_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp) / 'bridge-config.json'
            with self.assertRaises(ValueError): service.target_udid(config_path=config)
            for value in ('', 'bad target', '../device', 123, None):
                config.write_text(json.dumps({'udid': value}))
                with self.subTest(value=value), self.assertRaises(ValueError):
                    service.target_udid(config_path=config)

class FixTests(unittest.TestCase):
    def test_valid_fix_only_whitelisted_fields_and_age(self):
        raw = {'provider': 'gps', 'latitude': 30.7, 'longitude': 104.1, 'accuracy': 3.5, 'elapsedMs': 45, 'secret': 'ignored'}
        fix = service.parse_gps(raw, 'run', 2, 100.0)
        self.assertEqual(fix, {'run_id':'run', 'sequence':2, 'source':'gps', 'latitude':30.7, 'longitude':104.1, 'accuracy_m':3.5, 'received_monotonic':100.0, 'age_at_receive_ms':45})
        self.assertEqual(service.fix_age_seconds(fix, 102), 2.045)

    def test_rejects_invalid_or_unfresh_inputs(self):
        base = {'provider':'gps', 'latitude':30.7, 'longitude':104.1, 'accuracy':4, 'elapsedMs':45}
        for changes in ({'provider':'network'}, {'elapsedMs':-1}, {'elapsedMs':float('nan')}, {'latitude':float('nan')}, {'longitude':181}, {'accuracy':-1}, {'elapsedMs':None}, {'latitude':True}):
            with self.subTest(changes=changes):
                with self.assertRaises(ValueError): service.parse_gps(base | changes, 'run', 1, 1)

    def test_schedule_skips_old_ticks_and_holds_last_point(self):
        self.assertEqual(service.track_index(0,90), 0)
        self.assertEqual(service.track_index(5.9,90), 5)
        self.assertEqual(service.track_index(91,90), 90)
        self.assertTrue(service.track_done(95,90))
        self.assertFalse(service.track_done(94.9,90))
        first = service.track_point(0,90)
        last = service.track_point(90,90)
        self.assertAlmostEqual(first[0], last[0])
        self.assertAlmostEqual(first[1], last[1])
        self.assertNotEqual(first, service.track_point(45,90))

    def test_command_latency_is_counted_conservatively(self):
        raw = {'provider':'gps', 'latitude':30.7, 'longitude':104.1, 'accuracy':4, 'elapsedMs':45}
        fix = service.parse_gps(raw, 'run', 1, 100, 4)
        self.assertAlmostEqual(service.fix_age_seconds(fix, 101), 5.045)

class HybridTests(unittest.TestCase):
    def test_provider_selection_and_no_reissue(self):
        raw = {'latitude':30.7,'longitude':104.1,'accuracy':4,'elapsedMs':0}
        gps = service.parse_location(raw | {'provider':'gps'}, 'r', 1, 100, 'gps')
        net = service.parse_location(raw | {'provider':'network'}, 'r', 1, 105, 'network')
        arbiter = service.FixArbiter('r')
        arbiter.update('network', net); arbiter.update('gps', gps)
        first = arbiter.select(106)
        self.assertEqual(first['source'], 'gps'); self.assertEqual(first['sequence'], 1)
        self.assertIsNone(arbiter.select(106))
        second = arbiter.select(111)
        self.assertEqual(second['source'], 'network'); self.assertEqual(second['sequence'], 2)
        self.assertEqual(second['received_monotonic'], 105)
        self.assertIsNone(arbiter.select(111))
        self.assertIsNone(arbiter.select(116))
        self.assertEqual(arbiter.sequence, 2)

    def test_real_provider_validation(self):
        raw = {'provider':'network','latitude':30.7,'longitude':104.1,'accuracy':4,'elapsedMs':40}
        self.assertEqual(service.parse_location(raw,'r',1,1,'network')['source'], 'network')
        with self.assertRaises(ValueError): service.parse_location(raw,'r',1,1,'gps')

    def test_timeout_kills_inherited_pipe_child(self):
        import asyncio
        # A child keeps stdout open after the parent shell exits. Timeout must kill the group.
        async def run():
            start=time.monotonic()
            with self.assertRaises(asyncio.TimeoutError):
                await service.bounded('/bin/sh','-c','sleep 20 & wait',timeout=.2)
            self.assertLess(time.monotonic()-start, 3)
        asyncio.run(run())

class StatusTests(unittest.TestCase):
    def test_fusion_key_requires_exact_installed_package(self):
        self.assertEqual(service.fusion_key({'package':'org.locationbridge.source','helper_key':'11'*32}),'11'*32)
        for raw in (None, {}, {'package':'org.fake','helper_key':'11'*32},
                    {'package':'org.locationbridge.source','helper_key':True},
                    {'package':'org.locationbridge.source','helper_key':0}):
            with self.subTest(raw=raw), self.assertRaises(ValueError): service.fusion_key(raw)

    def test_fused_source_has_explicit_status_label(self):
        text = service.describe_worker({'last_source':'fused','last_accuracy_m':12}, None, 1,
                                       {'helper_error':'unavailable'})
        self.assertIn('来源：fused',text)
        self.assertIn('定位助手：unavailable',text)

    def test_inertial_status_labels_estimate(self):
        state={'last_source':'inertial','last_accuracy_m':25,'last_accuracy_kind':'model_estimate',
               'last_prediction_age_ms':2400}
        text=service.describe_worker(state,None,100)
        self.assertIn('来源：惯性推算',text)
        self.assertIn('模型误差估计 25.0米',text)
        self.assertIn('锚点预测时长：2.4秒',text)

    def test_missing_clear_state_is_unknown_and_no_coordinates(self):
        text = service.describe_worker({}, None, 12)
        self.assertIn('清除：未知', text)
        self.assertNotIn('经纬', text)
        text = service.describe_worker({'connected':True,'state':'running','clear_pending':False,'last_sequence':4}, {'received_monotonic':10,'age_at_receive_ms':45}, 12)
        self.assertIn('已连接', text)
        self.assertIn('2.0秒', text)
        self.assertIn('序号：4', text)

    def test_status_prefers_applied_provider_and_sanitized_errors(self):
        state = {'last_source':'network','last_accuracy_m':24.0}
        fix = {'source':'gps','accuracy_m':3, 'received_monotonic':10, 'age_at_receive_ms':0, 'latitude':30.7}
        status = {'providers': {'gps': {'error':'timeout'}}}
        text = service.describe_worker(state, fix, 11, status)
        self.assertIn('来源：network', text)
        self.assertIn('精度：24.0米', text)
        self.assertIn('gps:timeout', text)
        self.assertNotIn('30.7', text)

    def test_usb_successful_callback_waits_for_socket(self):
        self.assertFalse(service.usb_exited_with_error(None))
        self.assertFalse(service.usb_exited_with_error(0))
        self.assertTrue(service.usb_exited_with_error(1))

class SupervisorTests(unittest.TestCase):
    def test_start_duplicate_stop_with_fake_worker(self):
        script = Path(__file__).resolve().parents[1] / 'android/repo/bridge_service.py'
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp); bindir = home / 'bin'; bindir.mkdir()
            worker = bindir / 'proot-distro'
            worker.write_text("""#!%s
import json, os, sys, time
from pathlib import Path
args=sys.argv
rid=args[args.index('--run-id')+1]
p=Path(os.environ['LOCATION_BRIDGE_DIR'])/'service'/rid
(p/'worker.state.json').write_text(json.dumps({'run_id':rid,'connected':True,'state':'waiting','clear_pending':False}))
while not (p/'stop.json').exists(): time.sleep(.05)
(p/'worker.state.json').write_text(json.dumps({'run_id':rid,'connected':False,'state':'stopped','clear_pending':False}))
""" % sys.executable)
            worker.chmod(0o755)
            for name in ('termux-wake-lock','termux-wake-unlock'):
                f=bindir/name; f.write_text('#!/bin/sh\nexit 0\n'); f.chmod(0o755)
            env=os.environ | {'LOCATION_BRIDGE_DIR':str(home/'location-bridge'),'PATH':str(bindir)+os.pathsep+os.environ['PATH']}
            proc=subprocess.Popen([sys.executable,str(script),'start','--mode','test','--transport','wifi','--duration','30','--udid','test-target'], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                deadline=time.monotonic()+5
                while time.monotonic()<deadline:
                    pointer=service.read_json(home/'location-bridge/service/current.json')
                    if pointer and (Path(pointer['session_dir'])/'fix.json').exists(): break
                    time.sleep(.05)
                else: self.fail('simulated track did not start')
                duplicate=subprocess.run([sys.executable,str(script),'start','--mode','gps','--transport','wifi','--udid','test-target'],env=env,capture_output=True,text=True,timeout=5)
                self.assertIn('先停止',duplicate.stdout)
                stopped=subprocess.run([sys.executable,str(script),'stop'],env=env,capture_output=True,text=True,timeout=10)
                self.assertEqual(stopped.returncode,0)
                self.assertIn('停止请求已完成',stopped.stdout)
                self.assertEqual(proc.wait(timeout=5),0)
                state=json.loads((Path(pointer['session_dir'])/'worker.state.json').read_text())
                self.assertEqual(state['state'],'stopped')
                self.assertFalse(state['clear_pending'])
            finally:
                if proc.poll() is None: proc.kill(); proc.wait()
                proc.stdout.close(); proc.stderr.close()

if __name__ == '__main__': unittest.main()

class FusionOrderingTests(unittest.TestCase):
    def test_cross_provider_old_sample_is_not_forwarded(self):
        gate = service.FusionOrder()
        self.assertTrue(gate.accept('a', 100))
        self.assertFalse(gate.accept('a', 99))
        self.assertFalse(gate.accept('a', 100))
        self.assertTrue(gate.accept('a', 101))
        self.assertFalse(gate.accept('b', 99))
        self.assertTrue(gate.accept('b', 102))

    def test_lease_deadline_uses_boottime(self):
        import unittest.mock
        with unittest.mock.patch.object(service, 'boottime_ms', return_value=1000):
            self.assertEqual(service.helper_start_deadline(), 9000)

class FusionCollectorIntegration(unittest.IsolatedAsyncioTestCase):
    async def test_older_unseen_provider_sample_is_not_written(self):
        import asyncio
        class Client:
            count = 0
            def request_snapshot(self, uid):
                self.count += 1
                samples = [(100,'fused'), (99,'gps'), (101,'gps')]
                stamp, source = samples[min(self.count - 1, 2)]
                return {'running':True, 'session_id':'same', 'sample':{'elapsed_realtime_nanos':stamp}, 'providers':{}, 'source':source}
            def parse_snapshot(self, raw, run_id, sequence, now, rtt):
                return ({'run_id':run_id,'sequence':sequence,'source':raw['source'], 'received_monotonic':now, 'age_at_receive_ms':0}, ('same',raw['sample']['elapsed_realtime_nanos']))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp); done = asyncio.Event(); client = Client()
            task = asyncio.create_task(service.collect_fusion(path,'run',done,12345,client))
            try:
                await asyncio.sleep(.35)
                old = json.loads((path/'fix.json').read_text())
                self.assertEqual(old['sequence'],1)
                self.assertEqual(old['source'],'fused')
                await asyncio.sleep(.3)
                new = json.loads((path/'fix.json').read_text())
                self.assertEqual(new['sequence'],2)
                self.assertEqual(new['source'],'gps')
            finally:
                done.set()
                await task

class FusionRevocationTests(unittest.IsolatedAsyncioTestCase):
    async def test_invalid_response_revokes_and_old_token_does_not_restore(self):
        import asyncio
        class Client:
            count = 0
            def request_snapshot(self, key):
                self.count += 1
                if self.count == 2: return {'running':True, 'sample':None, 'providers':{}}
                stamp = 101 if self.count >= 4 else 100
                return {'running':True, 'session_id':'s', 'sample':{'elapsed_realtime_nanos':stamp}, 'providers':{}, 'selection':{'reason':'gps_better','rejected_count':2,'switch_count':1}}
            def parse_snapshot(self, raw, run_id, sequence, now, rtt):
                if raw['sample'] is None: return None, None
                stamp = raw['sample']['elapsed_realtime_nanos']
                return ({'run_id':run_id,'sequence':sequence,'source':'gps','latitude':1,'longitude':2,
                         'accuracy_m':3,'received_monotonic':now,'age_at_receive_ms':0}, ('s',stamp))
        async def wait_count(client,n):
            async with asyncio.timeout(4):
                while client.count < n: await asyncio.sleep(.02)
            await asyncio.sleep(.03)
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp); done=asyncio.Event(); client=Client()
            task=asyncio.create_task(service.collect_fusion(path,'run',done,'key',client))
            try:
                await wait_count(client,1)
                self.assertEqual(json.loads((path/'fix.json').read_text())['source'],'gps')
                selection=json.loads((path/'collector.status.json').read_text())['selection']
                self.assertEqual(selection,{'reason':'gps_better','rejected_count':2,'switch_count':1})
                self.assertIn('选源：gps_better 拒绝:2 切换:1', service.describe_worker({},None,1,{'selection':selection}))
                await wait_count(client,2)
                revoked=json.loads((path/'fix.json').read_text())
                self.assertEqual(revoked['run_id'],'run')
                self.assertNotIn('source',revoked)
                self.assertNotIn('latitude',revoked)
                self.assertEqual(revoked['reason'],'no_fresh_sample')
                worker_spec=importlib.util.spec_from_file_location('bridge_worker_for_revocation', Path(__file__).resolve().parents[1] / 'android/repo/bridge_worker.py')
                worker=importlib.util.module_from_spec(worker_spec); worker_spec.loader.exec_module(worker)
                self.assertIsNone(worker.validate_fix(revoked,'run','fusion',service.elapsed_time()))
                await wait_count(client,3)
                self.assertEqual(json.loads((path/'fix.json').read_text()),revoked)
                await wait_count(client,4)
                self.assertEqual(json.loads((path/'fix.json').read_text())['sequence'],2)
            finally:
                done.set(); await task

    async def test_rpc_failure_revokes_existing_fix(self):
        import asyncio
        class Client:
            count=0
            def request_snapshot(self, key):
                self.count+=1
                if self.count > 1: raise ValueError('timeout')
                return {'running':True,'session_id':'s','sample':{'elapsed_realtime_nanos':100},'providers':{}}
            def parse_snapshot(self, raw, run_id, sequence, now, rtt):
                return ({'run_id':run_id,'sequence':sequence,'source':'gps'},('s',100))
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp); done=asyncio.Event(); client=Client()
            task=asyncio.create_task(service.collect_fusion(path,'run',done,'key',client))
            try:
                async with asyncio.timeout(3):
                    while client.count<2: await asyncio.sleep(.02)
                await asyncio.sleep(.03)
                self.assertNotIn('source',json.loads((path/'fix.json').read_text()))
                self.assertEqual(json.loads((path/'collector.status.json').read_text())['helper_error'],'timeout')
            finally: done.set(); await task

class FusionStartupTests(unittest.IsolatedAsyncioTestCase):
    async def test_stop_during_launch_waits_out_deadline_and_sends_stop(self):
        import asyncio
        import types
        from unittest import mock
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); session = root/'run'; session.mkdir()
            (root/'source-config.json').write_text(json.dumps({'package':'org.locationbridge.source','helper_key':'11'*32}))
            times = []
            fake_client = types.SimpleNamespace(request_snapshot=lambda uid, op='snapshot': (times.append((op,time.monotonic())), {'running':False})[1])
            info = {'run_id':'run-test', 'session_dir':str(session)}
            async def fake_bounded(*args, timeout=5):
                if args[:2] == ('am','start'):
                    (session/'stop.json').write_text(json.dumps({'run_id':'run-test'}))
                return 0, b'', b''
            deadline = time.monotonic()+.2
            with mock.patch.object(service,'TASK',root), mock.patch.object(service,'bounded',fake_bounded), \
                 mock.patch.object(service,'boottime_ms',side_effect=lambda:int(time.monotonic()*1000)), \
                 mock.patch.object(service,'helper_start_deadline',return_value=int(deadline*1000)), \
                 mock.patch.dict(sys.modules, {'bridge_fusion':fake_client}), \
                 mock.patch.object(asyncio,'create_subprocess_exec',side_effect=AssertionError('worker must not start')):
                args = types.SimpleNamespace(mode='fusion',transport='wifi',duration=90)
                await service.supervise(args,info)
            self.assertEqual([call[0] for call in times], ['stop'])
            self.assertGreaterEqual(times[0][1], deadline-.005)
