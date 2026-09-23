#!/usr/bin/env bash
# Deploy Vortex to the phone over USB: install APK, push the multi-GB system
# image into the app-private dir (a phone-side CDN download of the same bytes is slow),
# and expose the on-device emulator gRPC port so a PC tool can inspect it.
#
#   ./deploy-phone.sh [--apk path] [--image dir] [--pkg com.velasim.app] [--serial SERIAL]
#
# Requires: USB debugging; `run-as` works because the target app is debuggable.
set -euo pipefail

# adb is a native Windows binary: keep remote (files/... or /data/...) paths from
# being rewritten by MSYS, and hand it Windows-style local paths instead.
export MSYS_NO_PATHCONV=1
winpath() { cygpath -w "$1" 2>/dev/null || printf '%s' "$1"; }

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
ADB="${ADB:-/d/download/sim/build-env/sdk/platform-tools/adb.exe}"
APK="${APK:-$ROOT/vela-flutter/build/app/outputs/flutter-apk/app-release.apk}"
IMAGE_DIR="${IMAGE_DIR:-$ROOT/re/img}"
IMAGE_TYPE="${IMAGE_TYPE:-vela-miwear-watch-5.0}"
# 目录名要跟 assets/vela/devices.json 里的 flavor 对齐：建 AVD 时按
# system-images/<flavor>/<imageType> 找镜像，vela-pre-4.0（手环）的 flavor 是 band。
case "$IMAGE_TYPE" in
  vela-pre-4.0) IMAGE_FLAVOR="${IMAGE_FLAVOR:-band}";;
  *)            IMAGE_FLAVOR="${IMAGE_FLAVOR:-watch}";;
esac
PKG="${PKG:-com.velasim.app}"
SERIAL="${SERIAL:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="$2"; shift 2;;
    --image) IMAGE_DIR="$2"; shift 2;;
    --pkg) PKG="$2"; shift 2;;
    --serial) SERIAL="$2"; shift 2;;
    --flavor) IMAGE_FLAVOR="$2"; shift 2;;
    *) echo "unknown arg $1" >&2; exit 2;;
  esac
done

# A workstation running Android emulators must not be mistaken for the phone:
# pick the first non-emulator device unless --serial says otherwise.
if [ -z "$SERIAL" ]; then
  SERIAL="$("$ADB" devices | awk '$2 == "device" && $1 !~ /^emulator-/ { print $1; exit }')"
fi
[ -n "$SERIAL" ] || { echo "no physical device; pass --serial <serial>" >&2; exit 1; }
echo "device: $SERIAL"

# --- 1. APK ---------------------------------------------------------------
if [ -f "$APK" ]; then
  echo "[1/4] install $APK ($(du -h "$APK" | cut -f1))"
  "$ADB" -s "$SERIAL" install -r "$(winpath "$APK")"
else
  echo "[1/4] skip install (no APK at $APK)"
fi

# The app keeps its runtime under <files>/vela/.vela (VelaPaths.velaHome) and only
# treats an image directory as installed when it holds the three guest files plus a
# .vela-image.json probe marker (VelaImageStore.isComplete). `adb shell` cannot write
# /data/data/<pkg>, so everything goes through run-as via /data/local/tmp.
IMG_DST="files/vela/.vela/sdk/system-images/$IMAGE_FLAVOR/$IMAGE_TYPE"
echo "[2/4] push system image parts from $IMAGE_DIR -> $IMG_DST"
"$ADB" -s "$SERIAL" shell "run-as $PKG sh -c 'mkdir -p $IMG_DST files/vela/.vela/sdk/vvd'" || {
  echo "run-as failed: is the app installed and debuggable?" >&2; exit 1; }

for f in nuttx vela_system.bin vela_data.bin advancedFeatures.ini; do
  src="$IMAGE_DIR/$f"
  [ -f "$src" ] || { echo "   missing $src -- skipped"; continue; }
  dst="$IMG_DST/$f"
  cur_size="$("$ADB" -s "$SERIAL" shell "run-as $PKG sh -c 'stat -c %s $dst 2>/dev/null || echo -1'" | tr -d '\r')"
  if [ "$cur_size" = "$(stat -c %s "$src")" ]; then
    echo "   $f already present ($(numfmt --to=iec "$cur_size")), skip"
    continue
  fi
  echo "   $f ($(numfmt --to=iec "$(stat -c %s "$src")"))"
  "$ADB" -s "$SERIAL" push "$(winpath "$src")" "/data/local/tmp/$f" >/dev/null
  # run-as cannot read /data/local/tmp/* directly for some SELinux labels, so cat through shell
  "$ADB" -s "$SERIAL" shell "run-as $PKG sh -c 'cat /data/local/tmp/$f > $dst && chmod 644 $dst'"
  "$ADB" -s "$SERIAL" shell "rm -f /data/local/tmp/$f"
  new_size="$("$ADB" -s "$SERIAL" shell "run-as $PKG sh -c 'stat -c %s $dst'" | tr -d '\r')"
  [ "$new_size" = "$(stat -c %s "$src")" ] || { echo "   SIZE MISMATCH $f: $new_size" >&2; exit 1; }
done

probe="$(mktemp)"
printf '{"type":"%s","flavor":"%s","installedAt":%s}\n' \
  "$IMAGE_TYPE" "$IMAGE_FLAVOR" "$(date +%s)000" > "$probe"
"$ADB" -s "$SERIAL" push "$(winpath "$probe")" "/data/local/tmp/vela-image.json" >/dev/null
"$ADB" -s "$SERIAL" shell "run-as $PKG sh -c 'cat /data/local/tmp/vela-image.json > $IMG_DST/.vela-image.json'"
"$ADB" -s "$SERIAL" shell "rm -f /data/local/tmp/vela-image.json"
rm -f "$probe"
echo "   probe marker written"

# --- 3. device profiles / skins are inside the APK assets; nothing to push.
echo "[3/4] assets staged by the app on first run (engine + glibc + skins)"

# --- 4. port forward + launch --------------------------------------------
echo "[4/4] forward tcp:18554 -> device 8554 (on-device emulator gRPC), tcp:15554 -> 5554 (adb)"
"$ADB" -s "$SERIAL" forward tcp:18554 tcp:8554 >/dev/null
"$ADB" -s "$SERIAL" forward tcp:15554 tcp:5554 >/dev/null
"$ADB" -s "$SERIAL" shell "monkey -p $PKG -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1 || \
  "$ADB" -s "$SERIAL" shell "am start -n $PKG/.MainActivity"

cat <<EOF

Next:
  * on-device engine needs Shizuku for the runas_app domain (exec + /proc); without it
    VelaEngine falls back to in-process spawn and may be blocked by SELinux.
  * inspect the on-device emulator from the PC:  python $ROOT/re/grpc_probe.py 127.0.0.1:18554
  * guest shell:  adb -s $SERIAL shell   (after the app starts the engine)
EOF
