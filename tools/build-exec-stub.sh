#!/usr/bin/env bash
# 编译 vela-exec-stub.c → jniLibs/arm64-v8a/libvela_vela_exec_stub.so
#
# 要求产物是**非 PIE 的静态 ELF、无 PT_INTERP**（内核直接 exec，不经过
# /lib/ld-linux-aarch64.so.1——Android 上没有它）。
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
src="$here/native/vela-exec-stub.c"
out="$root/vela-flutter/android/app/src/main/jniLibs/arm64-v8a/libvela_vela_exec_stub.so"

NDK="${NDK:-D:/download/sim/build-env/sdk/ndk/28.2.13676358}"
CLANG="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin/clang.exe 2>/dev/null | head -1)"
if [ -z "$CLANG" ]; then
  echo "clang not found under $NDK" >&2
  exit 1
fi

mkdir -p "$(dirname "$out")"
"$CLANG" --target=aarch64-linux-android21 -static -nostdlib -fno-builtin \
  -O2 -Wl,--build-id=none -Wl,-e,_start -Wl,-no-pie -Wl,--no-dynamic-linker -o "$out" "$src"

python - "$out" <<'PY'
import struct, sys
p = sys.argv[1]
d = open(p, "rb").read()
assert d[:4] == b"\x7fELF", "not an ELF"
e_type = struct.unpack_from("<H", d, 16)[0]
phoff, = struct.unpack_from("<Q", d, 32)
phentsize, phnum = struct.unpack_from("<HH", d, 54)
assert e_type == 2, f"expected ET_EXEC(2), got {e_type} -- 需要 -no-pie"
interp = None
for i in range(phnum):
    off = phoff + i * phentsize
    p_type = struct.unpack_from("<I", d, off)[0]
    if p_type == 3:
        p_offset, = struct.unpack_from("<Q", d, off + 8)
        p_filesz, = struct.unpack_from("<Q", d, off + 32)
        interp = d[p_offset:p_offset + p_filesz].rstrip(b"\0").decode()
print(f"{p}: ET_EXEC, {len(d)} bytes, PT_INTERP={interp}")
assert interp is None, "静态产物不该有 PT_INTERP"
PY
