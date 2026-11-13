#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
umask 077
task_dir="$HOME/location-bridge"
mkdir -p "$task_dir/service" "$HOME/.shortcuts"
exec 9>"$task_dir/service/service.lock"
if ! flock -n 9; then
  printf '请先停止定位桥，再更新。\n'
  exit 1
fi
for name in bridge_service.py bridge_usb.py bridge_status.py bridge_worker.py bridge_fusion.py bridge_external.py external_location.py bridge-usb-daemon.sh bridge-launch.sh; do
  curl --fail --silent --show-error --noproxy '*' "http://127.0.0.1:18765/$name" -o "$task_dir/$name.next"
done
for name in bridge_service.py bridge_usb.py bridge_status.py bridge_worker.py bridge_fusion.py bridge_external.py external_location.py bridge-usb-daemon.sh bridge-launch.sh; do
  mv "$task_dir/$name.next" "$task_dir/$name"
done
chmod 700 "$task_dir/bridge-usb-daemon.sh" "$task_dir/bridge-launch.sh"
python - <<'PY'
from pathlib import Path
root = Path.home() / '.shortcuts'
entries = {
    '定位桥-USB启动': 'start --mode hybrid --transport usb',
    '定位桥-曲线测试': 'start --mode test --transport usb --duration 90',
    '定位桥-停止': 'stop',
    '定位桥-状态': 'status',
    '定位桥-WiFi启动': 'start --mode hybrid --transport wifi',
    '定位桥-融合USB': 'start --mode fusion --transport usb',
    '定位桥-融合WiFi': 'start --mode fusion --transport wifi',
}
for name, args in entries.items():
    target = root / name
    target.write_text('#!/data/data/com.termux/files/usr/bin/bash\nexec "$HOME/location-bridge/bridge-launch.sh" ' + args + '\n')
    target.chmod(0o700)
print('已安装本地启动、曲线测试、停止和状态快捷方式。')
PY
flock -u 9
python "$task_dir/bridge_service.py" status
