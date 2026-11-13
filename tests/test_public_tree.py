import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('public_tree', Path(__file__).resolve().parents[1] / 'scripts/check_public_tree.py')
public_tree = importlib.util.module_from_spec(spec)
spec.loader.exec_module(public_tree)


class PublicTreeTests(unittest.TestCase):
    def test_private_artifacts_blocked(self):
        for path in ('android/helper/private/signing.p12', 'results/track.json', 'source-config.json', 'download.apk'):
            self.assertTrue(public_tree.problems(path, b'{}'), path)

    def test_secrets_are_detected_without_reporting_values(self):
        token = b'ghp_' + b'a' * 36
        self.assertEqual(public_tree.problems('config.py', token), ['GitHub credential'])
        self.assertEqual(public_tree.problems('config.py', b'-----BEGIN ' + b'PRIVATE KEY-----'), ['private key'])

    def test_normal_source_and_synthetic_locations_are_allowed(self):
        self.assertEqual(public_tree.problems('android/repo/example.py', b'LAT = 30.0\n'), [])

    def test_termux_app_home_is_not_a_personal_home_directory(self):
        app_home = b'/data/data/com.termux/files/' + b'home/location-bridge/bridge-launch.sh'
        self.assertEqual(public_tree.problems('Launcher.java', app_home), [])
        for root in (b'/home/', b'/Users/'):
            self.assertEqual(public_tree.problems('config.py', root + b'alice/project/'),
                             ['personal absolute path'])
        self.assertEqual(public_tree.problems('config.py', app_home + b' /home/' + b'alice/project/'),
                         ['personal absolute path'])


if __name__ == '__main__':
    unittest.main()
