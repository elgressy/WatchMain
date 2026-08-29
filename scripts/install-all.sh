#!/usr/bin/env bash
# Installs mobile-debug.apk on the connected phone and wear-debug.apk on the
# connected watch in one command. Detects which is which via
# ro.build.characteristics (Wear OS devices report "watch").
#
# Usage: scripts/install-all.sh
# Requires: both devices already visible in `adb devices` (USB for the
# phone, `adb connect <ip:port>` for the watch — see README.md).

set -euo pipefail

cd "$(dirname "$0")/.."
DIST_DIR="dist"
MOBILE_APK="$DIST_DIR/mobile-debug.apk"
WEAR_APK="$DIST_DIR/wear-debug.apk"

for f in "$MOBILE_APK" "$WEAR_APK"; do
  [ -f "$f" ] || { echo "Missing $f — build it first (see README.md)."; exit 1; }
done

devices=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$devices" ]; then
  echo "No adb devices found. Connect the phone over USB and the watch over"
  echo "wireless debugging (adb connect <ip:port>), then re-run this script."
  exit 1
fi

installed_phone=0
installed_watch=0

for serial in $devices; do
  characteristics=$(adb -s "$serial" shell getprop ro.build.characteristics 2>/dev/null | tr -d '\r')
  if echo "$characteristics" | grep -qi watch; then
    echo "==> $serial looks like a watch — installing $WEAR_APK"
    adb -s "$serial" install -r "$WEAR_APK"
    installed_watch=1
  else
    echo "==> $serial looks like a phone — installing $MOBILE_APK"
    adb -s "$serial" install -r "$MOBILE_APK"
    installed_phone=1
  fi
done

echo
[ "$installed_phone" = 1 ] && echo "Phone: mobile-debug.apk installed." || echo "Phone: not found among connected devices."
[ "$installed_watch" = 1 ] && echo "Watch: wear-debug.apk installed." || echo "Watch: not found among connected devices."
