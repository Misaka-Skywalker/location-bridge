#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
: "${TERMUX_USB_FD:?USB permission is required}"
service_dir="$HOME/location-bridge/service"
# Keep stderr's normal unbuffered writes so startup failures are visible immediately.
exec usbmuxd -f -p -S "$service_dir/usbmuxd.sock" -P "$service_dir/usbmuxd.pid" >>"$service_dir/usbmuxd.log" 2>&1
