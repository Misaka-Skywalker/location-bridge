import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
PATH=ROOT/'android/repo/configure-app-launch.py'

class AppLaunchSetupTests(unittest.TestCase):
    def module(self):
        spec=importlib.util.spec_from_file_location('app_launch_setup',PATH)
        mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod);return mod
    def test_requires_explicit_enable_and_preserves_other_settings(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'termux.properties'
            path.write_text('# user settings\nextra-keys = example\nallow-external-apps = false\n')
            mod=self.module()
            self.assertFalse(mod.enabled(path))
            self.assertEqual(path.read_text(),'# user settings\nextra-keys = example\nallow-external-apps = false\n')
            mod.enable(path)
            self.assertTrue(mod.enabled(path))
            self.assertIn('extra-keys = example',path.read_text())
            first=path.read_bytes();mod.enable(path);self.assertEqual(path.read_bytes(),first)
    def test_duplicate_properties_do_not_leave_conflicting_values(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'termux.properties'
            path.write_text('# allow-external-apps = false\nallow-external-apps=false\nallow-external-apps=true\n')
            mod=self.module();mod.enable(path)
            active=[line for line in path.read_text().splitlines() if line.startswith('allow-external-apps')]
            self.assertEqual(active,['allow-external-apps=true'])
    def test_read_only_status_does_not_create_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'missing/termux.properties'
            self.assertFalse(self.module().enabled(path));self.assertFalse(path.exists())

if __name__=='__main__':unittest.main()
