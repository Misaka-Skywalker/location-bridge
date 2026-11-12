import dataclasses
import importlib.util
import pathlib
import sys
import unittest

path = pathlib.Path(__file__).resolve().parents[1] / 'android/repo/external_location.py'
spec = importlib.util.spec_from_file_location('external_location', path)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)


class ExternalLocationTests(unittest.TestCase):
    def setUp(self):
        self.fix = module.ExternalFix('usb_nmea', 'receiver-1', 30.0, 104.0, 999.0, 1000.0,
                                      100.0, None, None, None, None, None, False, True)

    def validate(self, fix):
        return module.validate_fix(fix, now_utc_s=1001, now_monotonic_s=101)

    def test_valid_missing_accuracy_is_explicitly_blocked(self):
        self.assertEqual(self.validate(self.fix), self.fix)
        result = module.worker_compatibility(self.fix, now_utc_s=1001, now_monotonic_s=101)
        self.assertFalse(result.compatible)
        self.assertIn('missing_accuracy', result.reasons)
        self.assertIn('unsupported_external_source', result.reasons)

    def test_known_accuracy_does_not_claim_current_worker_support(self):
        fix = dataclasses.replace(self.fix, accuracy_m=5)
        result = module.worker_compatibility(fix, now_utc_s=1001, now_monotonic_s=101)
        self.assertEqual(result.reasons, ('unsupported_external_source',))

    def test_unknown_sample_and_mock_are_explicit(self):
        fix = dataclasses.replace(self.fix, sample_time_utc_s=None, is_mock=None)
        result = module.worker_compatibility(fix, now_utc_s=1001, now_monotonic_s=101)
        self.assertIn('unknown_sample_time', result.reasons)
        self.assertIn('unknown_mock_status', result.reasons)

    def test_stale_uses_sample_age_plus_monotonic_elapsed(self):
        fix = dataclasses.replace(self.fix, sample_time_utc_s=990)
        result = module.worker_compatibility(fix, now_utc_s=1001, now_monotonic_s=101)
        self.assertIn('stale_sample', result.reasons)

    def test_reserved_transport_types(self):
        for kind in ('ble_lns','bluetooth_nmea','usb_nmea','tcp_nmea','udp_nmea'):
            with self.subTest(kind=kind):
                self.assertEqual(self.validate(dataclasses.replace(self.fix,source_type=kind)).source_type,kind)

    def test_reject_invalid_values(self):
        changes = [dict(latitude=float('nan')), dict(longitude=181), dict(accuracy_m=-1),
                   dict(speed_m_s=float('inf')), dict(course_deg=360), dict(is_mock=True),
                   dict(valid=False), dict(sample_time_utc_s=1001), dict(received_monotonic_s=102),
                   dict(altitude_m=5), dict(altitude_datum='mean_sea_level'),
                   dict(coordinate_system='GCJ02'), dict(source_type='gps'), dict(accuracy_m=True)]
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.validate(dataclasses.replace(self.fix, **change))


if __name__ == '__main__':
    unittest.main()
