#!/usr/bin/env python3
"""裁掉 APK 里永远不会被执行的负载（幂等，可重复跑）。

删：
  engine/lib64/qt/**                        —— 只有 -qt-hide-window 那条路会用到 Qt，
                                               而 App 恒传 -no-window（VelaChannel:923 默认 true，
                                               Dart 侧从不覆盖），所以这 23 MB 是死的。
  engine/qemu/linux-aarch64/qemu-system-armel
  engine/qemu/linux-aarch64/qemu-system-aarch64
                                            —— 非 headless 的两个（Qt 链、78 MB）；
                                               stub 把这两个名字映射到 headless 真身。
  压缩包里 toolkit.tar 的 win32/fswin 原生模块 —— 65 MB，Android 上不可能加载。

保留 qemu-system-{armel,aarch64}-headless：前者是唯一的执行目标，后者是将来
万一出现 arm64 客机镜像时的退路（设备目录目前全是 arm32）。

用法：python tools/slim-assets.py
"""
import os
import shutil
import sys
import tarfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, 'vela-flutter', 'android', 'app', 'src', 'main', 'assets', 'vela')

DROP_DIRS = ['engine/lib64/qt']
DROP_FILES = [
    'engine/qemu/linux-aarch64/qemu-system-armel',
    'engine/qemu/linux-aarch64/qemu-system-aarch64',
]
WIN32_HINTS = ('win32', '/fswin/')
WIN32_PREFIX = ('node_modules/fswin/',)


def human(n):
    return f'{n / 1e6:.1f} MB'


def drop_tree(rel):
    p = os.path.join(ASSETS, rel)
    if not os.path.isdir(p):
        return 0
    size = sum(os.path.getsize(os.path.join(d, f))
               for d, _, fs in os.walk(p) for f in fs)
    shutil.rmtree(p)
    print(f'dropped dir  {rel}  ({human(size)})')
    return size


def drop_file(rel):
    p = os.path.join(ASSETS, rel)
    if not os.path.isfile(p):
        return 0
    size = os.path.getsize(p)
    os.remove(p)
    print(f'dropped file {rel}  ({human(size)})')
    return size


def slim_tar(name):
    p = os.path.join(ASSETS, name)
    if not os.path.isfile(p):
        return 0
    tmp = p + '.slim'
    kept = removed = 0
    with tarfile.open(p) as src, tarfile.open(tmp, 'w') as dst:
        for m in src:
            low = m.name.lower()
            if m.isfile() and (any(h in low for h in WIN32_HINTS)
                               or low.startswith(WIN32_PREFIX)):
                removed += m.size
                continue
            f = src.extractfile(m) if m.isfile() else None
            dst.addfile(m, f)
            kept += m.size
    before = os.path.getsize(p)
    os.replace(tmp, p)
    print(f'slimmed {name}: removed {human(removed)}, '
          f'{human(before)} -> {human(os.path.getsize(p))} archive')
    return removed


def main():
    if not os.path.isdir(ASSETS):
        sys.exit(f'missing {ASSETS}')
    saved = sum(drop_tree(d) for d in DROP_DIRS)
    saved += sum(drop_file(f) for f in DROP_FILES)
    saved += slim_tar('toolkit.tar')
    print(f'total removed: {human(saved)}')


if __name__ == '__main__':
    main()
