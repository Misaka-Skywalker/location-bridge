#!/usr/bin/env python3
"""Run offline Python and pure Java tests without Android devices or credentials."""
from pathlib import Path
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def java_tool(name):
    configured = os.environ.get('JAVA_HOME')
    candidate = Path(configured) / 'bin' / name if configured else None
    if candidate and candidate.is_file():
        return str(candidate)
    found = shutil.which(name)
    if not found:
        raise SystemExit('Install JDK 17 or later to run Java tests.')
    return found


def main():
    subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', 'tests', '-v'], cwd=ROOT, check=True)
    source_dir = ROOT / 'android/helper/src/org/locationbridge/source'
    sources = [p for p in sorted(source_dir.glob('*.java'))
               if not re.search(r'^import (?:android\.|org\.json\.)', p.read_text(), re.M)]
    tests = sorted((ROOT / 'android/helper/tests').glob('*Test.java'))
    with tempfile.TemporaryDirectory(prefix='location-bridge-tests-') as output:
        subprocess.run([java_tool('javac'), '-encoding', 'UTF-8', '-d', output,
                        *map(str, sources + tests)], cwd=ROOT, check=True)
        for test in tests:
            subprocess.run([java_tool('java'), '-cp', output,
                            'org.locationbridge.source.' + test.stem], cwd=ROOT, check=True)
    print(f'Passed Python suite and {len(tests)} Java test programs.')


if __name__ == '__main__':
    main()
