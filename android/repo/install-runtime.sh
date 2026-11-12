#!/data/data/com.termux/files/usr/bin/bash
set -uo pipefail
task_dir="$HOME/location-bridge"
mkdir -p "$task_dir"
(
  set -e
  cd "$task_dir"
  printf '\nDOWNLOADING_OFFLINE_RUNTIME\n'
  curl --fail --noproxy '*' http://127.0.0.1:18765/images/python-3.13-slim-bookworm-linux-arm64.oci.tar -o python-arm64.oci.tar
  curl --fail --noproxy '*' http://127.0.0.1:18765/wheel-bundle.tar -o wheel-bundle.tar
  curl --fail --noproxy '*' http://127.0.0.1:18765/runtime-sha256.txt -o runtime-sha256.txt
  # Accept only the two expected filenames; never let a manifest name another path.
  python - <<'PYCODE'
from pathlib import Path
import re
lines = Path('runtime-sha256.txt').read_text(encoding='ascii').splitlines()
expected = ['python-arm64.oci.tar', 'wheel-bundle.tar']
if len(lines) != 2 or any(not re.fullmatch(r'[0-9a-f]{64}  ' + re.escape(name), line)
                          for line, name in zip(lines, expected)):
    raise SystemExit('Invalid runtime checksum manifest')
PYCODE
  sha256sum -c runtime-sha256.txt
  printf '\nIMPORTING_LINUX_CONTAINER\n'
  if ! proot-distro list --quiet | grep -Fxq lb-python; then
    proot-distro install --name lb-python --architecture aarch64 "$task_dir/python-arm64.oci.tar"
  fi
  tar -xf wheel-bundle.tar
  printf '\nCHECKING_GUEST_PYTHON\n'
  proot-distro login --isolated --bind "$task_dir:/mnt/lb" lb-python -- /usr/local/bin/python --version
  proot-distro login --isolated --bind "$task_dir:/mnt/lb" lb-python -- /usr/local/bin/python -m venv /opt/location-bridge
  printf '\nINSTALLING_OFFLINE_WHEELS\n'
  proot-distro login --isolated --bind "$task_dir:/mnt/lb" lb-python -- /opt/location-bridge/bin/python -m pip install --no-index --find-links /mnt/lb/wheels --require-hashes -r /mnt/lb/wheels/requirements.txt
  printf '\nCHECKING_PYTHON_IMPORTS\n'
  proot-distro login --isolated lb-python -- /opt/location-bridge/bin/python -c 'import platform,importlib.metadata as m; import cryptography,qh3,av,pymobiledevice3; print(platform.python_version(),platform.machine()); print({n:m.version(n) for n in ("pymobiledevice3","cryptography","qh3","av")})'
  proot-distro login --isolated lb-python -- /opt/location-bridge/bin/python -m pip check
  proot-distro login --isolated lb-python -- /opt/location-bridge/bin/pymobiledevice3 remote pair-host --help
) 2>&1 | tee "$task_dir/host-runtime.txt"
result=${PIPESTATUS[0]}
printf '\nRUNTIME_EXIT=%s\n' "$result" | tee -a "$task_dir/host-runtime.txt"
curl --fail --noproxy '*' --data-binary "@$task_dir/host-runtime.txt" http://127.0.0.1:18765/report/host-runtime
exit "$result"
