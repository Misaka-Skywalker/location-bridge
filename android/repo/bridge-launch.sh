#!/data/data/com.termux/files/usr/bin/bash
set -uo pipefail
umask 077
task_dir="$HOME/location-bridge"
mkdir -p "$task_dir/service"
log="$task_dir/service/console.log"
if [[ -f "$log" ]] && (( $(wc -c < "$log") > 2000000 )); then
  mv -f "$log" "$log.previous"
fi
python "$task_dir/bridge_service.py" "$@" 2>&1 | tee -a "$log"
exit "${PIPESTATUS[0]}"
