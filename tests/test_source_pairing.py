import contextlib
import io
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from unittest.mock import patch


class PairingFailureTests(unittest.TestCase):
    def test_failed_launch_never_prints_key_or_command(self):
        path = Path(__file__).resolve().parents[1] / 'android/repo/configure-source.sh'
        code = path.read_text().split("<<'PYCODE'\n",1)[1].rsplit('\nPYCODE',1)[0]
        secret = 'ab' * 32
        failures = [types.SimpleNamespace(returncode=1),
                    subprocess.TimeoutExpired(['am','--es','pairing_token',secret],10)]
        for failure in failures:
            with self.subTest(failure=type(failure).__name__), tempfile.TemporaryDirectory() as tmp:
                output = io.StringIO()
                options = {'side_effect':failure} if isinstance(failure,Exception) else {'return_value':failure}
                with patch.object(Path,'home',return_value=Path(tmp)), patch('secrets.token_hex',return_value=secret), \
                     patch('subprocess.run',**options), contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                    with self.assertRaises(SystemExit) as caught:
                        exec(compile(code,str(path),'exec'),{})
                text = output.getvalue() + str(caught.exception)
                self.assertNotIn(secret,text)
                self.assertNotIn('pairing_token',text)
                self.assertIn('无法打开助手',text)


if __name__ == '__main__': unittest.main()
