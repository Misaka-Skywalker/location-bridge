import copy
import importlib
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'android/repo'))
external = importlib.import_module('bridge_external')

def observation():
    return {'protocol':1,'kind':'external_gnss','state':'subscribed','connected':True,
        'selection':{'eligible':True,'reason':'eligible','manual_excluded':False,'waiting_after_txt':False},
        'receiver_id':'selected-device','elapsed_realtime_nanos':120_000_000_000,
        'sample':{'source_type':'ble_lns','sequence':3,'latitude':30.57,'longitude':104.06,
            'coordinate_system':'WGS84','position_status':1,'accuracy_m':None,'is_mock':None,
            'timestamp_ms':999000,'received_timestamp_ms':1000000,
            'received_elapsed_realtime_nanos':118_000_000_000,
            'altitude_m':12.0,'altitude_datum':'unknown','speed_m_s':4.5,'course_deg':45.0},
        'quality':{'horizontal_error_m':2.5,'association':'unverified'}}

class ExternalSnapshotTests(unittest.TestCase):
    def test_explicit_exclusion_blocks_even_a_retained_fresh_sample(self):
        for reason,manual,txt in [('manual_excluded',True,False),('static_txt',False,True),
                                 ('waiting_position',False,False),('stale_position',False,False)]:
            raw=observation()
            raw['selection']={'eligible':False,'reason':reason,'manual_excluded':manual,'waiting_after_txt':txt}
            with self.subTest(reason=reason):
                self.assertEqual(external.parse_external_snapshot(raw,now_utc_s=1003,now_boottime_s=121),(None,None))
                self.assertTrue(raw['connected'])

    def test_selection_metadata_must_be_boolean_and_consistent(self):
        valid={'eligible':True,'reason':'eligible','manual_excluded':False,'waiting_after_txt':False}
        for change in ({'eligible':1},{'eligible':True,'manual_excluded':True},
                       {'eligible':True,'waiting_after_txt':True},{'reason':'unknown'},
                       {'eligible':False},{'manual_excluded':'false'}):
            raw=observation();raw['selection']={**valid,**change}
            with self.subTest(change=change),self.assertRaisesRegex(ValueError,'invalid_external_selection'):
                external.parse_external_snapshot(raw,now_utc_s=1003,now_boottime_s=121)

    def test_eligible_selection_keeps_existing_position_validation(self):
        raw=observation()
        raw['selection']={'eligible':True,'reason':'eligible','manual_excluded':False,'waiting_after_txt':False}
        self.assertIsNotNone(external.parse_external_snapshot(raw,now_utc_s=1003,now_boottime_s=121)[0])
        self.assertEqual(external.parse_external_snapshot(raw,now_utc_s=1020,now_boottime_s=138),(None,None))

    def test_legacy_snapshot_without_selection_is_diagnostic_only(self):
        raw=observation();del raw['selection']
        self.assertIs(external.validate_external_snapshot(raw),raw)
        self.assertEqual(external.parse_external_snapshot(raw,now_utc_s=1003,now_boottime_s=121),(None,None))

    def test_normalizes_without_inventing_accuracy_or_sample_time(self):
        raw=observation()
        fix,token=external.parse_external_snapshot(raw,now_utc_s=1003,now_boottime_s=121)
        self.assertEqual(fix.accuracy_m,None)
        self.assertEqual(fix.altitude_datum,'unknown')
        self.assertEqual(fix.received_monotonic_s,118)
        self.assertEqual(fix.sample_time_utc_s,999)
        self.assertEqual(token,('selected-device',3))
        raw['sample']['timestamp_ms']=None
        self.assertIsNone(external.parse_external_snapshot(raw,now_utc_s=1003,now_boottime_s=121)[0].sample_time_utc_s)

    def test_repeat_keeps_original_sample_and_reception_time(self):
        first=external.parse_external_snapshot(observation(),now_utc_s=1003,now_boottime_s=121)
        again=external.parse_external_snapshot(observation(),now_utc_s=1004,now_boottime_s=122)
        self.assertEqual(first,again)

    def test_disconnected_or_stale_is_not_a_position(self):
        raw=observation(); raw['connected']=False;raw['sample']=None
        raw['selection'].update(eligible=False,reason='disconnected')
        self.assertEqual(external.parse_external_snapshot(raw,now_utc_s=1003,now_boottime_s=121),(None,None))
        self.assertEqual(external.parse_external_snapshot(observation(),now_utc_s=1020,now_boottime_s=138),(None,None))

    def test_rejects_invalid_status_coordinates_or_clocks(self):
        for change in ({'latitude':float('nan')},{'longitude':181},{'position_status':3},
                       {'received_elapsed_realtime_nanos':130_000_000_000},
                       {'timestamp_ms':1001000},{'accuracy_m':-1},{'is_mock':True},
                       {'coordinate_system':'GCJ02'},{'sequence':True}):
            raw=observation();raw['sample'].update(change)
            with self.subTest(change=change),self.assertRaises(ValueError):
                external.parse_external_snapshot(raw,now_utc_s=1003,now_boottime_s=121)

    def test_idle_envelope_has_no_fix(self):
        raw=observation();raw.update(connected=False,state='idle',receiver_id=None,sample=None)
        raw['selection'].update(eligible=False,reason='disconnected')
        self.assertEqual(external.parse_external_snapshot(raw,now_utc_s=1003,now_boottime_s=121),(None,None))

if __name__=='__main__':unittest.main()
