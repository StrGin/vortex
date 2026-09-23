#!/usr/bin/env python3
"""Author a Vela AVD directory from scratch and (optionally) boot it with the official
Windows emulator. This is the reference implementation for VelaAvd.java: it proves the
config.ini contract is enough, with nothing copied from an IDE-created AVD.

  python make-avd.py <avdId> [--skin xiaomi_s4] [--image-dir re/img] [--boot]
"""
import argparse
import json
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, 'vela-flutter', 'android', 'app', 'src', 'main', 'assets', 'vela')
DEVICES = json.load(open(os.path.join(ASSETS, 'devices.json'), encoding='utf8'))
VVD_HOME = os.path.expanduser('~/.vela/vvd')
EMULATOR = r'C:\Users\admin\.vela\sdk\emulator\windows-x86_64\emulator.exe'

CONFIG_ORDER = [
    ('AvdId', '{avd}'),
    ('abi.type', 'armeabi-v7a'),
    ('avd.ini.displayname', '{avd}'),
    ('avd.ini.encoding', 'UTF-8'),
    ('fastboot.forceChosenSnapshotBoot', 'no'),
    ('fastboot.forceColdBoot', 'yes'),
    ('fastboot.forceFastBoot', 'no'),
    ('hw.arc', 'false'),
    ('hw.audioInput', 'yes'),
    ('hw.battery', 'yes'),
    ('hw.camera.back', 'None'),
    ('hw.camera.front', 'None'),
    ('hw.cpu.arch', 'arm'),
    ('hw.cpu.ncore', '{ncore}'),
    ('hw.dPad', 'no'),
    ('hw.gps', 'yes'),
    ('hw.gpu.enabled', 'no'),
    ('hw.gpu.mode', 'host'),
    ('hw.initialOrientation', 'Portrait'),
    ('hw.keyboard', 'yes'),
    ('hw.lcd.density', '{density}'),
    ('hw.mainKeys', 'no'),
    ('hw.ramSize', '{ram}'),
    ('hw.sdCard', 'no'),
    ('hw.sensors.orientation', 'yes'),
    ('hw.sensors.magnetic_field', 'yes'),
    ('hw.sensors.proximity', 'yes'),
    ('hw.sensors.light', 'yes'),
    ('hw.sensors.temperature', 'yes'),
    ('hw.sensors.humidity', 'yes'),
    ('hw.sensors.heart_rate', 'yes'),
    ('hw.accelerometer', 'yes'),
    ('hw.gyroscope', 'yes'),
    ('hw.trackBall', 'no'),
    ('runtime.network.latency', 'none'),
    ('runtime.network.speed', 'full'),
    ('showDeviceFrame', 'yes'),
    ('image.sysdir.2', ''),
    ('skin.dynamic', 'yes'),
    ('skin.name', '{skin}'),
    ('skin.path', '{skinpath}'),
    ('hw.lcd.shape', '{shape}'),
    ('hw.device.flavor', '{flavor}'),
    ('ide.image.type', '{image}'),
    ('image.sysdir.1', '{imagedir}'),
]


def author(avd, skin, image_dir, image_type, force=False):
    prof = next((d for d in DEVICES['devices'] if d['skin'] == skin), None)
    if not prof:
        sys.exit(f'unknown skin {skin}; have {[d["skin"] for d in DEVICES["devices"]]}')
    avd_dir = os.path.join(VVD_HOME, avd + '.vvd')
    if os.path.exists(avd_dir) and not force:
        print('exists:', avd_dir)
    else:
        os.makedirs(avd_dir, exist_ok=True)
    for sub in ('share', 'data'):
        os.makedirs(os.path.join(avd_dir, sub), exist_ok=True)
    for f in ('vela_system.bin', 'vela_data.bin'):
        src, dst = os.path.join(image_dir, f), os.path.join(avd_dir, f)
        if not os.path.exists(dst):
            print('copy', f)
            shutil.copyfile(src, dst)
    vals = {
        'avd': avd, 'ncore': str(prof.get('ncore', 2)), 'ram': str(prof.get('ramSizeMb', 1024)),
        'density': str(prof.get('density', 320)), 'skin': skin,
        'skinpath': os.path.join(ASSETS, 'skins', skin).replace('/', '\\'),
        'shape': prof.get('shape', 'rect'), 'flavor': prof.get('flavor', 'watch'),
        'image': image_type, 'imagedir': os.path.abspath(image_dir).replace('/', '\\'),
    }
    with open(os.path.join(avd_dir, 'config.ini'), 'w', encoding='utf8', newline='\n') as fh:
        for k, tpl in CONFIG_ORDER:
            fh.write(f'{k}={tpl.format(**vals)}\n')
    with open(os.path.join(VVD_HOME, avd + '.ini'), 'w', encoding='utf8', newline='\n') as fh:
        fh.write(f'path={avd_dir}\npath.rel=vvd\\{avd}.vvd')
    open(os.path.join(avd_dir, 'AVD.conf'), 'w', encoding='utf8').write(
        '[perAvd]\nloc\\latitude=37.422\nloc\\longitude=-122.084\n'
        'battery\\charge_level=100\nset\\pauseAvdWhenMinimized=false\n')
    open(os.path.join(avd_dir, 'emulator-user.ini'), 'w', encoding='utf8').write(
        'window.x = 100\nwindow.y = 100\nuuid = %d\n' % (os.times()[4] * 1000))
    return avd_dir


def boot(avd, log):
    cmd = [EMULATOR, '-vela', '-avd', avd, '-show-kernel', '-no-window', '-gpu', 'off',
           '-read-only', '-verbose', '-qemu', '-device', 'virtio-snd,bus=virtio-mmio-bus.2',
           '-allow-host-audio', '-semihosting', '-smp', '2']
    print(' '.join(cmd))
    return subprocess.Popen(cmd, stdout=open(log, 'wb'), stderr=subprocess.STDOUT,
                            cwd=os.path.dirname(EMULATOR))


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('avd')
    ap.add_argument('--skin', default='xiaomi_s4')
    ap.add_argument('--image-dir', default=os.path.join(ROOT, 're', 'img'))
    ap.add_argument('--image-type', default='vela-miwear-watch-5.0')
    ap.add_argument('--boot', action='store_true')
    a = ap.parse_args()
    d = author(a.avd, a.skin, a.image_dir, a.image_type, force=True)
    print('authored', d)
    print(open(os.path.join(d, 'config.ini'), encoding='utf8').read())
    if a.boot:
        p = boot(a.avd, os.path.join(ROOT, 're', 'avd-boot.log'))
        print('pid', p.pid)
