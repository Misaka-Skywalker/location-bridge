#!/usr/bin/env python3
"""Opt-in Termux app-command setup. No change is made without --enable."""
import argparse
import os
from pathlib import Path
import re
import stat
import tempfile

PROPERTY = re.compile(r'^[ \t\f]*allow-external-apps(?:[ \t\f]*[=:][ \t\f]*|[ \t\f]+)(.*)$')


def enabled(path):
    if not path.exists():
        return False
    values = [match.group(1).strip() for line in path.read_text().splitlines()
              if (match := PROPERTY.match(line))]
    return bool(values) and values[-1] == 'true'


def enable(path):
    if path.is_symlink():
        raise ValueError('Edit the linked termux.properties file manually.')
    lines = path.read_text().splitlines() if path.exists() else []
    output, found = [], False
    for line in lines:
        if PROPERTY.match(line):
            if not found:
                output.append('allow-external-apps=true')
                found = True
        else:
            output.append(line)
    if not found:
        output.append('allow-external-apps=true')
    path.parent.mkdir(parents=True, exist_ok=True)
    mode = stat.S_IMODE(path.stat().st_mode) if path.exists() else 0o600
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write('\n'.join(output) + '\n')
        temporary.chmod(mode)
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--enable', action='store_true',
                        help='Allow apps with the separately granted RUN_COMMAND permission to execute Termux commands.')
    args = parser.parse_args()
    path = Path.home() / '.termux/termux.properties'
    if args.enable:
        enable(path)
    print('Termux app commands: ' + ('enabled' if enabled(path) else 'disabled'))
    print('Grant RUN_COMMAND only to trusted apps. This setting does not grant that permission.')


if __name__ == '__main__':
    main()
