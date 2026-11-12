#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
umask 077
python - <<'PYCODE'
import hashlib,json,os,re,secrets,subprocess
from pathlib import Path
root=Path.home()/'location-bridge'
root.mkdir(exist_ok=True)
path=root/'source-config.json'
try: value=json.loads(path.read_text())
except (OSError,ValueError): value={}
key=value.get('helper_key')
if not isinstance(key,str) or not re.fullmatch('[0-9a-f]{64}',key):
    key=secrets.token_hex(32)
    temporary=root/'source-config.json.next'
    temporary.write_text(json.dumps({'package':'org.locationbridge.source','helper_key':key}))
    temporary.chmod(0o600)
    os.replace(temporary,path)
path.chmod(0o600)
print('Termux 配对指纹：'+hashlib.sha256(bytes.fromhex(key)).hexdigest()[:12],flush=True)
print('请核对助手显示相同指纹，在“设置 → 与 Termux 配对”中确认；密钥仅存手机私有目录。',flush=True)
try:
    result=subprocess.run(['am','start','-n','org.locationbridge.source/.MainActivity',
                          '--es','pairing_token',key],timeout=10,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
except (OSError,subprocess.TimeoutExpired):
    raise SystemExit('无法打开助手，请解锁后重新运行配置。') from None
if result.returncode:
    raise SystemExit('无法打开助手，请解锁后重新运行配置。')
PYCODE
