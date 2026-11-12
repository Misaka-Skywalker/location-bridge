import importlib.util
import json
import hashlib
import hmac
from pathlib import Path
import socket
import threading
import tempfile
import time
import unittest
from unittest import mock

import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'android/repo'))

spec = importlib.util.spec_from_file_location('fusion', Path(__file__).resolve().parents[1] / 'android/repo/bridge_fusion.py')
fusion = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fusion)

RUN = '1c68b7a8-506c-4dc9-a137-810a20b8e5c2'
def snapshot():
    return {'protocol':1,'session_id':RUN,'running':True,'elapsed_realtime_nanos':120_000_000_000,
            'providers':{p:{'available':True,'enabled':True,'error':None} for p in ('fused','gps','network')},
            'sample':{'sequence':2,'source':'fused','latitude':30.1,'longitude':104.2,'accuracy_m':5.5,
                      'elapsed_realtime_nanos':118_000_000_000,'timestamp_ms':123456,'is_mock':False,'coordinate_system':'WGS84'}}

class ValidationTests(unittest.TestCase):
    def test_age_and_repeat_token(self):
        fix, token = fusion.parse_snapshot(snapshot(),'run-id',9,500.0,0.25)
        self.assertEqual(token,(RUN,2))
        self.assertEqual(fix['source'],'fused')
        self.assertEqual(fix['age_at_receive_ms'],2250)
        self.assertEqual(fix['received_monotonic'],500)
        self.assertEqual(fix['sequence'],9)
        self.assertEqual(fusion.parse_snapshot(snapshot(),'run-id',10,501,0.25)[1],token)

    def test_inertial_schema_and_limits(self):
        value=snapshot(); value['sample'].update(source='inertial', elapsed_realtime_nanos=120_000_000_000,
            accuracy_m=25, accuracy_kind='model_estimate', anchor_source='gps',
            anchor_elapsed_realtime_nanos=118_000_000_000, prediction_age_ms=2000)
        fix,token=fusion.parse_snapshot(value,'r',1,120.2,.1)
        self.assertEqual(fix['source'],'inertial')
        self.assertEqual(fix['anchor_elapsed_realtime_nanos'],118_000_000_000)
        self.assertEqual(fix['prediction_age_ms'],2000)
        self.assertEqual(fix['elapsed_realtime_nanos'],120_000_000_000)
        self.assertEqual(fix['accuracy_kind'],'model_estimate')
        self.assertEqual(fusion.parse_snapshot(value,'r',1,120.6,.6),(None,None))
        for change in ({'anchor_source':'fused'},{'accuracy_kind':'measurement'},
                       {'accuracy_m':151},{'prediction_age_ms':1500},{'prediction_age_ms':2500},
                       {'anchor_elapsed_realtime_nanos':121_000_000_000}):
            bad=snapshot(); bad['sample']=dict(value['sample'],**change)
            with self.subTest(change=change), self.assertRaises(ValueError): fusion.validate_snapshot(bad)

    def test_bad_sample(self):
        for patch in ({'latitude':float('nan')},{'longitude':181},{'elapsed_realtime_nanos':121_000_000_000},{'elapsed_realtime_nanos':0},{'is_mock':True},{'coordinate_system':'GCJ02'},{'source':'cell'},{'accuracy_m':-1},{'sequence':True}):
            with self.subTest(patch=patch):
                value=snapshot(); value['sample'].update(patch)
                with self.assertRaises(ValueError): fusion.parse_snapshot(value,'run',1,100,0)

    def test_stale_and_null(self):
        value=snapshot(); value['sample']['elapsed_realtime_nanos']=100_000_000_000
        self.assertEqual(fusion.parse_snapshot(value,'run',1,100,0), (None,None))
        value['sample']=None
        self.assertEqual(fusion.parse_snapshot(value,'run',1,100,0), (None,None))
        value['running']=False
        self.assertEqual(fusion.parse_snapshot(value,'run',1,100,0), (None,None))

    def test_invalid_envelope(self):
        for patch in ({'protocol':True},{'session_id':'oops'},{'elapsed_realtime_nanos':0},{'running':1},{'providers':{}}):
            with self.subTest(patch=patch):
                value=snapshot(); value.update(patch)
                with self.assertRaises(ValueError): fusion.parse_snapshot(value,'run',1,100,0)

class SocketTests(unittest.TestCase):
    KEY = '11' * 32

    def serve(self, transform=None, timeout=2, operation='snapshot', value=None):
        server=socket.socket(socket.AF_INET,socket.SOCK_STREAM)
        server.bind(('127.0.0.1',0)); server.listen(1)
        address=server.getsockname()
        captured=[]
        def run():
            try:
                conn,_=server.accept()
                with conn:
                    request=json.loads(conn.makefile('rb').readline(16384))
                    expected=hmac.new(bytes.fromhex(self.KEY),
                        ('request\n'+request['nonce']+'\n'+operation).encode(),hashlib.sha256).hexdigest()
                    captured.append(hmac.compare_digest(request['mac'],expected))
                    payload=json.dumps(snapshot() if value is None else value,separators=(',',':'))
                    envelope={'nonce':request['nonce'],'payload':payload,
                              'mac':hmac.new(bytes.fromhex(self.KEY),('response\n'+request['nonce']+'\n'+payload).encode(),hashlib.sha256).hexdigest()}
                    data=json.dumps(envelope).encode()+b'\n'
                    if transform: data=transform(envelope,data)
                    conn.sendall(data)
            except (BrokenPipeError,ConnectionResetError): pass
            finally: server.close()
        thread=threading.Thread(target=run,daemon=True); thread.start()
        try:
            with mock.patch.object(fusion,'SOCKET_ADDRESS',address), mock.patch.object(fusion,'TIMEOUT_SECONDS',timeout):
                return fusion.request_snapshot(self.KEY,operation)
        finally:
            thread.join(timeout=3)
            self.assertEqual(captured,[True])

    def test_authenticated_snapshot_and_activate(self):
        self.assertEqual(self.serve()['protocol'],1)
        self.assertTrue(self.serve(operation='activate')['running'])

    def test_external_channel_is_authenticated_and_has_its_own_schema(self):
        value={'protocol':1,'kind':'external_gnss','state':'stopped','connected':False,
               'elapsed_realtime_nanos':1000000000,'receiver_id':None,'sample':None}
        self.assertEqual(self.serve(operation='external_snapshot',value=value),value)
        with self.assertRaisesRegex(ValueError,'invalid_external_envelope'):
            self.serve(operation='external_snapshot')

    def test_tampered_payload_or_mac_or_nonce_is_rejected(self):
        for field,value in (('payload','{}'),('mac','00'*32),('nonce','22'*32)):
            with self.subTest(field=field), self.assertRaisesRegex(ValueError,'authentication_failed'):
                self.serve(lambda envelope,data: json.dumps(envelope | {field:value}).encode()+b'\n')

    def test_bad_and_oversized_response(self):
        for content in (b'{bad}\n',b'{"x":NaN}\n',b'x'*16385+b'\n'):
            with self.subTest(size=len(content)), self.assertRaises(ValueError):
                self.serve(lambda envelope,data:content)

    def test_timeout(self):
        def delay(envelope,data): time.sleep(.3); return data
        with self.assertRaisesRegex(ValueError,'timeout'):
            self.serve(delay,timeout=.1)

    def test_key_and_operation_validated_before_connect(self):
        for key in (None,10228,'x'*64,'00'):
            with self.subTest(key=key), self.assertRaisesRegex(ValueError,'invalid_key'):
                fusion.request_snapshot(key)
        with self.assertRaisesRegex(ValueError,'invalid_operation'):
            fusion.request_snapshot(self.KEY,'arbitrary')

if __name__ == '__main__': unittest.main()
