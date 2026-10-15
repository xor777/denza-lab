#!/usr/bin/env python3
"""Run Android pixel/cache fixtures against the installed debug APK; emulator only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--build', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('Emulator only')
    root = Path(__file__).resolve().parents[2]
    build, out = args.build.resolve(), args.out.resolve()
    sdk = Path(os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools'))
    java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
    env = dict(os.environ, JAVA_HOME=str(java))

    def run(*command):
        return subprocess.check_output([str(x) for x in command], env=env, timeout=45, text=True)

    def adb(*command):
        return run('adb', '-s', args.serial, *command)

    if adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        parser.error('Not QEMU')
    report = json.loads((build / 'build-report.json').read_text())
    apk = adb('shell', 'pm', 'path', 'ru.yandex.yandexnavi').strip().removeprefix('package:')
    if adb('shell', 'sha256sum', apk).split()[0] != report['sha256']:
        parser.error('Installed APK differs from build')
    out.mkdir(parents=True, exist_ok=False)
    classes, dex = out / 'classes', out / 'dex'
    classes.mkdir(); dex.mkdir()
    android = sdk / 'platforms/android-35/android.jar'
    test = root / 'experiments/yandex-hud-module/src/androidTest/java/dev/denza/hud/probe/StockHudArtTest.java'
    run(java / 'bin/javac', '--release', '8', '-cp', str(android) + os.pathsep + str(build / 'module.jar'), '-d', classes, test)
    run(java / 'bin/jar', 'cf', out / 'test.jar', '-C', classes, '.')
    run(sdk / 'build-tools/36.0.0/d8', '--min-api', '26', '--lib', android, '--output', dex,
        out / 'test.jar', build / 'module.jar')
    remote = '/data/local/tmp/denza-hud-artwork-test'
    adb('shell', 'mkdir', '-p', remote)
    adb('push', dex / 'classes.dex', remote + '/test.dex')
    output = adb('shell', 'env', 'CLASSPATH=' + remote + '/test.dex', 'app_process', '/system/bin',
                 'dev.denza.hud.probe.StockHudArtTest', remote + '/images')
    (out / 'test.log').write_text(output)
    adb('pull', remote + '/images', out)
    result = {'apk_sha256': report['sha256'], 'result': output.strip(),
              'images_sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (out / 'images').glob('*.png')}}
    (out / 'report.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
