#!/usr/bin/env bash
# Copy a quick-app project's *sources* onto the phone's public edit surface so the
# app can import it (App: 工程 → 从编辑面导入 → 导入).
#
#   ./push-project.sh /d/code/HyperBoxPro [NameOnPhone]
#
# node_modules/build/dist are skipped on purpose: the app builds with the
# aiot-toolkit bundled in the APK (verified building HyperBoxPro on the PC with
# exactly that toolkit + NODE_PATH).
set -euo pipefail

export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-/d/download/sim/build-env/sdk/platform-tools/adb.exe}"
SRC="${1:?usage: push-project.sh <local project dir> [name on phone]}"
NAME="${2:-$(basename "$SRC")}"
SERIAL="${SERIAL:-}"

if [ -z "$SERIAL" ]; then
  SERIAL="$("$ADB" devices | awk '$2 == "device" && $1 !~ /^emulator-/ { print $1; exit }')"
fi
[ -n "$SERIAL" ] || { echo "no physical device; pass SERIAL=<serial>" >&2; exit 1; }
[ -d "$SRC" ] || { echo "no such dir: $SRC" >&2; exit 1; }

DEST="/sdcard/Vortex/projects/$NAME"
echo "device: $SERIAL"
echo "push   $SRC -> $DEST (sources only)"

# Pack locally, adb push the tarball, unpack on device: piping a tar stream into
# `adb shell tar -x` stops halfway on this device (only the dotfiles land), while
# a pushed file unpacks fine.
TARBALL="$(mktemp -t velaprojectXXXXXX.tar.gz)"
trap 'rm -f "$TARBALL"' EXIT
(cd "$SRC" && tar -czf "$TARBALL" \
    --exclude=node_modules --exclude=build --exclude=dist \
    --exclude=.git --exclude=.husky --exclude=.vscode --exclude=.idea \
    --exclude=coverage .)
echo "tarball $(du -h "$TARBALL" | cut -f1)"

"$ADB" -s "$SERIAL" shell "mkdir -p '$DEST'"
"$ADB" -s "$SERIAL" push "$(cygpath -w "$TARBALL" 2>/dev/null || printf '%s' "$TARBALL")" \
    "/data/local/tmp/vela-project.tar.gz" >/dev/null
"$ADB" -s "$SERIAL" shell "tar -xzf /data/local/tmp/vela-project.tar.gz -C '$DEST' && rm -f /data/local/tmp/vela-project.tar.gz && echo EXTRACT_OK"

echo "--- on device ---"
"$ADB" -s "$SERIAL" shell "ls '$DEST'; du -sh '$DEST' 2>/dev/null"
cat <<EOF

Next: 手机打开 Vortex → 工程 → 「从编辑面导入」里会出现 $NAME → 导入
然后在该工程卡片里点「构建并安装启动」。
EOF
