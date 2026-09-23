#!/usr/bin/env python3
"""W1: assemble the Vela simulator runtime into Flutter assets.

Layout produced under <project>/android/app/src/main/assets/vela/:
  engine/                 linux-aarch64 Android-emulator payload (trimmed for headless)
  glibc/lib/*             from lib64/glibc-2.29.tgz (ld-linux-aarch64.so.1 + libc 2.31 etc.)
  skins/<device>/         layout + background.png + foreground.png (device frame + touch mask)
  devices.json            device profile table

System images are intentionally NOT packaged: they are ~0.9-1.2 GB each and are
downloaded at runtime from the Xiaomi FDS CDN (see IMAGE_URL_TEMPLATE below,
derived from vela.aiot-emulator dist/emulator/index.js getImageDownloadUrl()).

Exec bits are lost when the zip is read on Windows; VelaHost.java chmods on extract.
"""
import io
import json
import os
import re
import shutil
import sys
import tarfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
EMULATOR_ZIP = os.path.join(ROOT, 're', 'dl', 'emulator-linux-aarch64.zip')
SKINS_SRC = os.path.expanduser('~/.vela/sdk/skins/builtin')
OUT = os.path.join(ROOT, 'vela-flutter', 'android', 'app', 'src', 'main', 'assets', 'vela')

CDN_BASE = 'https://vela-ide.cnbj3-fusion.mi-fds.com/vela-ide'
IMAGE_URL_TEMPLATE = CDN_BASE + '/system-images/{type}/{time}/{type}.zip'

# from vela.aiot-emulator dist/emulator/index.js (VelaImageVersionList)
IMAGES = [
    {'type': 'vela-miwear-watch-5.0', 'time': '20250716', 'label': 'vela-miwear-watch-5', 'flavor': 'watch'},
    {'type': 'vela-miwear-watch-5.0-beta', 'time': '20260717', 'label': 'vela-miwear-watch-5(beta)', 'flavor': 'watch'},
    {'type': 'vela-watch-5.0', 'time': '20250716', 'label': 'vela-watch-5', 'flavor': 'watch'},
    {'type': 'vela-release-4.0', 'time': '20250526', 'label': 'vela-miwear-watch-4', 'flavor': 'watch'},
    {'type': 'vela-pre-4.0', 'time': '20250225', 'label': 'vela-watch-4', 'flavor': 'watch'},
    {'type': 'vela-miwear-minisound-5.0', 'time': '20250318', 'label': 'vela-miwear-minisound-5', 'flavor': 'sound'},
    {'type': 'openvela-smartspeaker-5.0', 'time': '20260128', 'label': 'openvela-smartspeaker-5', 'flavor': 'sound'},
]

# Trim: desktop GPU / WebRTC / Qt helpers the headless qemu path does not load.
# NOTE: lib64/gles_swiftshader/ is deliberately NOT dropped -- Android has no
# desktop GL, and without the bundled SwiftShader backend the engine aborts at
# startup ("OpenGLES emulation failed to initialize"). crashpad_handler stays
# out on purpose: without it the engine logs "Crash handler not found, crash
# reporting disabled" and carries on.
DROP = (
    'include/', 'resources/', 'lib/libflatbuffers.a', 'lib/cmake/',
    'goldfish-webrtc-bridge', 'nimble_bridge', 'qsn', 'crashpad_handler', 'qemu-img',
)
KEEP_PREFIX = ('qemu/linux-aarch64/', 'lib64/', 'lib/pc-bios/', 'bin64/')
KEEP_EXACT = ('emulator',)


def want(name):
    rel = name.split('/', 1)[1] if name.startswith('linux-aarch64/') else None
    if rel is None:
        return None
    if any(rel.startswith(d) or rel == d for d in DROP):
        return None
    if rel in KEEP_EXACT or rel.startswith(KEEP_PREFIX):
        return rel
    return None


def build_toolkit_tar(stage, out_path):
    """Pack node_modules into a stored tar (gradle keeps it uncompressed)."""
    import tarfile
    skip_suffix = ('.md', '.d.ts', '.map', '.cmd', '.ps1', '.tsbuildinfo')
    skip_dirs = {'test', 'tests', 'docs', 'example', 'examples', 'coverage',
                 '.bin', 'man'}
    n = skipped = 0
    total = 0
    with tarfile.open(out_path, 'w') as tf:
        for base, dirs, files in os.walk(os.path.join(stage, 'node_modules')):
            dirs[:] = [d for d in dirs if d not in skip_dirs]
            for f in files:
                if f.lower().endswith(skip_suffix):
                    skipped += 1
                    continue
                fp = os.path.join(base, f)
                rel = os.path.relpath(fp, stage).replace(os.sep, '/')
                st = os.stat(fp)
                ti = tarfile.TarInfo(rel)
                ti.size = st.st_size
                ti.mtime = st.st_mtime
                ti.mode = 0o644
                with open(fp, 'rb') as fh:
                    tf.addfile(ti, fh)
                n += 1
                total += st.st_size
        tf.add(os.path.join(stage, 'package.json'), arcname='package.json')
    print(f'toolkit.tar: {n} files ({skipped} pruned), {total/1e6:.0f} MB content, '
          f'{os.path.getsize(out_path)/1e6:.0f} MB archive')


def parse_layout(path):
    """Skin `layout` files: device display rect, portrait canvas, offsets, props."""
    txt = open(path, encoding='utf8', errors='replace').read()
    d = {}
    m = re.search(r'parts\s*\{\s*device\s*\{\s*display\s*\{(.*?)\}', txt, re.S)
    if m:
        for k in ('width', 'height', 'x', 'y', 'corner_radius'):
            v = re.search(rf'\b{k}\s+(\d+)', m.group(1))
            if v:
                d[k] = int(v.group(1))
    m = re.search(r'layouts\s*\{\s*portrait\s*\{(.*?)\n  \}', txt, re.S)
    if m:
        for k in ('width', 'height'):
            v = re.search(rf'^\s*{k}\s+(\d+)', m.group(1), re.M)
            if v:
                d['canvas_' + k] = int(v.group(1))
        v = re.search(r'part2\s*\{\s*name \S+\s*x (\d+)\s+y (\d+)', m.group(1))
        if v:
            d['device_x'], d['device_y'] = int(v.group(1)), int(v.group(2))
    m = re.search(r'props\s*\{(.*?)\}', txt, re.S)
    if m:
        for k in ('shape', 'density', 'flavor'):
            v = re.search(rf'\b{k}\s+(\S+)', m.group(1))
            if v:
                d[k] = int(v.group(1)) if v.group(1).isdigit() else v.group(1)
    return d


def main():
    if not os.path.exists(EMULATOR_ZIP):
        sys.exit(f'missing {EMULATOR_ZIP}')
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT, exist_ok=True)

    z = zipfile.ZipFile(EMULATOR_ZIP)
    n = total = 0
    glibc_tmp = os.path.join(OUT, '_glibc.tgz')
    for info in z.infolist():
        if info.is_dir():
            continue
        rel = want(info.filename)
        if rel == 'lib64/glibc-2.29.tgz':
            open(glibc_tmp, 'wb').write(z.read(info))
            continue
        if rel is None:
            continue
        dest = os.path.join(OUT, 'engine', *rel.split('/'))
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        data = z.read(info)
        open(dest, 'wb').write(data)
        n += 1
        total += len(data)
    print(f'engine: {n} files, {total/1e6:.1f} MB')

    # The zip's libtcmalloc_minimal.so.4 interposes malloc and aborts on Android
    # ("Attempt to realloc invalid pointer") the first time the screenshot path
    # allocates its 466*466*4 frame buffer. Ship a stub that exports the tcmalloc
    # symbols the engine imports and interposes nothing, so glibc malloc wins.
    stub = os.path.join(HERE, 'prebuilt', 'libtcmalloc_minimal.so.4')
    if os.path.isfile(stub):
        dst = os.path.join(OUT, 'engine', 'lib64', 'libtcmalloc_minimal.so.4')
        shutil.copy2(stub, dst)
        print(f'tcmalloc stub installed ({os.path.getsize(dst)} B)')
    else:
        print('WARN: tools/prebuilt/libtcmalloc_minimal.so.4 missing '
              '-> engine aborts on the first screenshot')

    # glibc bundle lives beside the engine, not inside it
    if glibc_tmp and os.path.exists(glibc_tmp):
        gd = os.path.join(OUT, 'glibc')
        with tarfile.open(glibc_tmp) as t:
            t.extractall(gd)
        os.remove(glibc_tmp)
        libs = os.path.join(gd, 'lib')
        names = sorted(os.listdir(libs))
        print(f'glibc: {len(names)} files')
        for key in ('ld-linux-aarch64.so.1', 'libc.so.6'):
            print(f'   {key}:', 'OK' if os.path.exists(os.path.join(libs, key)) else 'MISSING')
        # The zip's glibc is 2.31 and cannot resolve the engine's GLIBC_2.34/2.35
        # symbols. Refresh with: python re/build-glibc-bundle.py <termux-glibc.deb>
        # (Termux 2.44 + the linkat->renameat patch Android needs for hard links).
        print('   NOTE: refresh with re/build-glibc-bundle.py before shipping')

    # ---- self-contained bionic node runtime (re/build-node-bundle.py) --------
    nb = os.path.join(ROOT, 're', 'node-bundle')
    if os.path.isdir(nb):
        dst_root = os.path.join(OUT, 'node')
        shutil.rmtree(dst_root, ignore_errors=True)
        cnt = 0
        nbytes = 0
        for base, _, fs in os.walk(nb):
            for f in fs:
                src = os.path.join(base, f)
                rel = os.path.relpath(src, nb).replace(os.sep, '/')
                d = os.path.join(dst_root, *rel.split('/'))
                os.makedirs(os.path.dirname(d), exist_ok=True)
                shutil.copy2(src, d)
                cnt += 1
                nbytes += os.path.getsize(src)
        print(f'node runtime: {cnt} files, {nbytes/1e6:.0f} MB -> assets/vela/node')
    else:
        print('WARN: re/node-bundle missing -> 设备上将没有可用的 node')

    # ---- project templates (from the aiot-project vsix) ---------------------
    tmpl_src = os.path.join(ROOT, 're', 'ext', 'vela.aiot-project', 'extension',
                            'template', 'project-app', 'template')
    if os.path.isdir(tmpl_src):
        dst = os.path.join(OUT, 'templates', 'project-app')
        shutil.copytree(tmpl_src, dst)
        n = 0
        for base, _, fs in os.walk(dst):
            for f in fs:
                fp = os.path.join(base, f)
                try:
                    txt = open(fp, encoding='utf8').read()
                except (UnicodeDecodeError, OSError):
                    continue
                # The template ships "minPlatformVersion": "{{...}}" which is a JSON
                # string; the toolkit rejects it ("must be number"). Ship a valid
                # default and let VelaProjects rewrite it per project.
                fixed = txt.replace('"minPlatformVersion": "{{minPlatformVersion}}"',
                                    '"minPlatformVersion": 500')
                if fixed != txt:
                    io.open(fp, 'w', encoding='utf8').write(fixed)
                    n += 1
        print(f'templates: {sum(len(fs) for _, _, fs in os.walk(dst))} files '
              f'({n} manifest fixed)')
    else:
        print('WARN: no project template found -> 工程创建将不可用')

    # ---- offline aiot-toolkit dependency tree -------------------------------
    stage = os.path.join(ROOT, 're', 'toolkit-stage')
    if os.path.isdir(os.path.join(stage, 'node_modules')):
        build_toolkit_tar(stage, os.path.join(OUT, 'toolkit.tar'))
    else:
        print('WARN: re/toolkit-stage missing -> 设备端构建将不可用')

    # skins -> devices.json
    devs = []
    if os.path.isdir(SKINS_SRC):
        for name in sorted(os.listdir(SKINS_SRC)):
            src = os.path.join(SKINS_SRC, name)
            lay = os.path.join(src, 'layout')
            if not os.path.isfile(lay):
                continue
            dst = os.path.join(OUT, 'skins', name)
            os.makedirs(dst, exist_ok=True)
            for f in os.listdir(src):
                shutil.copy2(os.path.join(src, f), os.path.join(dst, f))
            d = parse_layout(lay)
            d['skin'] = name
            d['avdId'] = name
            d['imageType'] = 'vela-pre-4.0' if d.get('flavor') == 'band' or d.get('shape') == 'pill-shaped' \
                else 'vela-miwear-watch-5.0'
            d['cpuArch'] = 'arm'
            d['abi'] = 'armeabi-v7a'
            d['ncore'] = 2
            d['ramSizeMb'] = 1024
            devs.append(d)
    else:
        print('WARN: no local skins dir, devices.json will be empty')
    json.dump({'devices': devs, 'images': IMAGES, 'imageUrlTemplate': IMAGE_URL_TEMPLATE},
              open(os.path.join(OUT, 'devices.json'), 'w', encoding='utf8'),
              ensure_ascii=False, indent=1)
    print(f'devices: {len(devs)}')
    for d in devs:
        print(f"   {d['avdId']:<20} {d.get('width')}x{d.get('height')} {d.get('shape')} "
              f"canvas={d.get('canvas_width')}x{d.get('canvas_height')} img={d['imageType']}")


if __name__ == '__main__':
    main()
