#!/usr/bin/env python3
"""Migrate the target in an older installed service without printing it."""
import json
import os
from pathlib import Path
import re

root = Path.home() / 'location-bridge'
source = (root / 'bridge_service.py').read_text(encoding='utf-8')
match = re.search(r"^TARGET\s*=\s*['\"]([A-Za-z0-9-]{1,128})['\"]\s*$", source, re.MULTILINE)
if not match:
    raise SystemExit('No legacy target found. Set bridge-config.json manually.')
path = root / 'bridge-config.json'
try:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    raise SystemExit('Configuration already exists; left unchanged.')
with os.fdopen(fd, 'w', encoding='utf-8') as output:
    json.dump({'udid': match.group(1)}, output)
    output.write('\n')
print('Local target configuration created.')
