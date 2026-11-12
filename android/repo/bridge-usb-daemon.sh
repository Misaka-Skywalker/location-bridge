#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
: "${TERMUX_USB_FD:?USB permission is required}"
service_dir="$HOME/location-bridge/service"
exec usbmuxd -f -p -S "$service_dir/usbmuxd.sock" -P "$service_dir/usbmuxd.pid" -l "$service_dir/usbmuxd.log"
