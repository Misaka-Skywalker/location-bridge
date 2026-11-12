#!/usr/bin/env python3
"""Check the Git index for accidental private data and non-source artifacts.

This conservative check complements manual review; it is not a complete secret scanner.
It reports paths and rule names, never matching values.
"""
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
BLOCKED_PARTS = {'private', 'results', 'handoff', 'tools', '.venv', '__pycache__', 'build'}
BLOCKED_SUFFIXES = {'.apk', '.whl', '.deb', '.zip', '.tar', '.p12', '.pem', '.key', '.jks', '.keystore', '.pyc', '.class', '.dex'}
RULES = {
    'private key': re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    'GitHub credential': re.compile(r'(?:gh[pousr]_|github_pat_)[A-Za-z0-9_]{20,}'),
    'AWS credential': re.compile(r'AKIA[A-Z0-9]{16}'),
    'personal absolute path': re.compile(r'/(?:Users|home)/[A-Za-z0-9_.-]+/'),
    'literal pairing secret': re.compile(r'''["'](?:helper_key|pairing_token)["']\s*:\s*["'][a-fA-F0-9]{64}["']'''),
}


def problems(name, content):
    path = PurePosixPath(name)
    found = []
    if set(path.parts) & BLOCKED_PARTS or path.suffix in BLOCKED_SUFFIXES:
        found.append('private/generated path')
    if path.name in {'source-config.json', 'bridge-config.json', '.env', 'keystore-password.txt'}:
        found.append('local configuration')
    if len(content) > 1_000_000 or b'\0' in content:
        found.append('binary/oversized artifact')
    text = content.decode('utf-8', errors='replace')
    found.extend(rule for rule, regex in RULES.items() if regex.search(text))
    if not name.startswith('tests/') and re.search(r'\b[0-9A-Fa-f]{8}-[0-9A-Fa-f]{16}\b', text):
        found.append('literal device identifier')
    return found


def main():
    paths = subprocess.check_output(['git', 'ls-files', '--cached', '-z'], cwd=ROOT).decode().split('\0')
    paths = [p for p in paths if p]
    if not paths:
        raise SystemExit('No staged/tracked files to audit. Stage the intended source files first.')
    failures = []
    for path in paths:
        data = subprocess.check_output(['git', 'show', ':' + path], cwd=ROOT)
        for rule in problems(path, data):
            failures.append(f'{path}: {rule}')
    if failures:
        print('\n'.join(failures), file=sys.stderr)
        raise SystemExit(1)
    print(f'Public-tree checks passed for {len(paths)} indexed files; manual review is still required.')


if __name__ == '__main__':
    main()
