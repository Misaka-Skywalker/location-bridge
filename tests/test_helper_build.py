import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('helper_build', Path(__file__).resolve().parents[1] / 'android/helper/build.py')
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class SigningMaterialTests(unittest.TestCase):
    def test_partial_signing_material_is_preserved_and_rejected(self):
        for existing in ('key.p12', 'password.txt'):
            with self.subTest(existing=existing), tempfile.TemporaryDirectory() as tmp:
                directory = Path(tmp)
                saved = directory / existing
                saved.write_text('recovery-material')
                with self.assertRaises(RuntimeError):
                    build.check_signing_pair(directory / 'key.p12', directory / 'password.txt')
                self.assertEqual(saved.read_text(), 'recovery-material')
                self.assertEqual(len(list(directory.iterdir())), 1)

    def test_new_install_and_complete_pair_are_allowed(self):
        with tempfile.TemporaryDirectory() as tmp:
            key, password = Path(tmp) / 'key.p12', Path(tmp) / 'password.txt'
            build.check_signing_pair(key, password)
            key.touch(); password.touch()
            build.check_signing_pair(key, password)
