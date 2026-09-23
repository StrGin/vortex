"""Pack the installed aiot-toolkit node_modules into a single asset tar for the device.

Prunes docs/types/sourcemaps/bin-shims — there is no npm on device, so the whole
dependency tree must ship prebuilt.

Two things the tree must contain beyond a plain `npm install` for the *build* step
to run on the phone (node on the device is the official linux-arm64 build running
under the bundled glibc, see assets/vela/node):

  * @rspack/binding-linux-arm64-gnu      (rspack publishes no Android binding, but
  * @parcel/watcher-linux-arm64-glibc     process.platform is "linux" under that
  * @reflink/reflink-linux-arm64-gnu      node, so the linux-arm64 natives are used)

Fetch them with e.g.
  npm pack @rspack/binding-linux-arm64-gnu@<matching @rspack/binding version>
and unpack into re/toolkit-stage/node_modules/<name>/ (the platform guards in npm
refuse to install them on a Windows host).

The archive is written in GNU format on purpose: long node_modules paths then live
in 'L' records instead of PAX 'x' headers.
"""
import os
import tarfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # simpsimvela/
STAGE = os.path.join(ROOT, 're', 'toolkit-stage')
OUT = os.path.join(ROOT, 'vela-flutter', 'android', 'app', 'src', 'main',
               'assets', 'vela', 'toolkit.tar')

SKIP_SUFFIX = ('.md', '.d.ts', '.map', '.cmd', '.ps1', '.tsbuildinfo', '.LICENSE', '.license')
SKIP_DIRS = {'test', 'tests', 'docs', 'example', 'examples', 'coverage', '.bin', 'man'}


def keep(path):
    rel = path.replace('\\', '/')
    parts = rel.split('/')
    if any(p in SKIP_DIRS for p in parts[:-1]):
        return False
    base = parts[-1]
    if base.lower().endswith(SKIP_SUFFIX):
        return False
    if 'node_modules/.bin' in rel:
        return False
    return True


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    n = skipped = 0
    total = 0
    with tarfile.open(OUT, 'w', format=tarfile.GNU_FORMAT) as tf:
        for root, dirs, files in os.walk(os.path.join(STAGE, 'node_modules')):
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
            for f in files:
                p = os.path.join(root, f)
                rel = os.path.relpath(p, STAGE).replace('\\', '/')
                if not keep(rel):
                    skipped += 1
                    continue
                try:
                    st = os.stat(p)
                except OSError:
                    continue
                ti = tarfile.TarInfo(rel)
                ti.size = st.st_size
                ti.mtime = st.st_mtime
                ti.mode = 0o644
                with open(p, 'rb') as fh:
                    tf.addfile(ti, fh)
                n += 1
                total += st.st_size
        pj = os.path.join(STAGE, 'package.json')
        tf.add(pj, arcname='package.json')
    print(f'files={n} skipped={skipped} bytes={total/1e6:.1f} MB')
    print(f'archive={OUT} size={os.path.getsize(OUT)/1e6:.1f} MB')


if __name__ == '__main__':
    main()
