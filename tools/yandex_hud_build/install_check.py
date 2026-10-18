#!/usr/bin/env python3
"""Reproduce cross-signer permission conflict on a disposable emulator, then install the fix."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

PACKAGE = 'ru.yandex.yandexnavi'
FIXTURE = 'dev.denza.hudpermission.probe'
SECOND_FIXTURE = 'dev.denza.hudpermissionsecond.probe'
PERMISSION = 'com.yandex.permission.READ_CREDENTIALS'
ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--before', type=Path, required=True)
    parser.add_argument('--after', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'): parser.error('Disposable emulator only')
    def adb(*command, check=True):
        return subprocess.run(['adb', '-s', args.serial, *command], timeout=60,
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=check)
    if adb('shell', 'getprop', 'ro.kernel.qemu').stdout.strip() != '1': parser.error('Not QEMU')
    for name in (PACKAGE, FIXTURE, SECOND_FIXTURE):
        if 'package:' in adb('shell', 'pm', 'path', name, check=False).stdout:
            parser.error(f'{name} already installed; use a clean disposable emulator')
    args.out.mkdir(parents=True, exist_ok=False)
    sdk = Path(os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools'))
    java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
    env = dict(os.environ, JAVA_HOME=str(java))
    tools = sdk / 'build-tools/36.0.0'
    def run(command):
        subprocess.run([str(x) for x in command], env=env, check=True, timeout=60)
    # Deliberately a different certificate from the Navigator fork. No components or code.
    key = args.out / 'fixture.p12'
    run([java/'bin/keytool', '-genkeypair', '-keystore', key, '-storepass', 'emulator-fixture',
         '-keypass', 'emulator-fixture', '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', '2048',
         '-validity', '30', '-dname', 'CN=Disposable permission owner test'])
    run([tools/'aapt2', 'link', '-I', sdk/'platforms/android-35/android.jar', '--manifest',
         ROOT/'experiments/yandex-hud-module/test-permission-owner/AndroidManifest.xml',
         '--version-code', '1', '--version-name', '1', '-o', args.out/'unsigned.apk'])
    run([tools/'zipalign', '-f', '4', args.out/'unsigned.apk', args.out/'aligned.apk'])
    run([tools/'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:emulator-fixture',
         '--out', args.out/'emulator-only-permission-owner.apk', args.out/'aligned.apk'])
    # Two official-family apps can declare the same permission with the same signer.
    run([tools/'aapt2', 'link', '-I', sdk/'platforms/android-35/android.jar', '--manifest',
         ROOT/'experiments/yandex-hud-module/test-permission-owner/AndroidManifest.xml',
         '--rename-manifest-package', SECOND_FIXTURE, '--version-code', '1', '--version-name', '1',
         '-o', args.out/'second-unsigned.apk'])
    run([tools/'zipalign', '-f', '4', args.out/'second-unsigned.apk', args.out/'second-aligned.apk'])
    run([tools/'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:emulator-fixture',
         '--out', args.out/'emulator-only-second-owner.apk', args.out/'second-aligned.apk'])
    adb('install', '--no-streaming', str(args.out/'emulator-only-permission-owner.apk'))
    adb('install', '--no-streaming', str(args.out/'emulator-only-second-owner.apk'))
    before = adb('install', '--no-streaming', str(args.before), check=False)
    (args.out/'before-install.txt').write_text(before.stdout)
    if before.returncode == 0 or 'INSTALL_FAILED_DUPLICATE_PERMISSION' not in before.stdout or PERMISSION not in before.stdout:
        raise RuntimeError('Expected duplicate permission failure; inspect before-install.txt')
    print(before.stdout, flush=True)
    after = adb('install', '--no-streaming', str(args.after), check=False)
    (args.out/'after-install.txt').write_text(after.stdout)
    if after.returncode != 0: raise RuntimeError('Fixed candidate did not install')
    permission = adb('shell', 'dumpsys', 'package', 'permissions').stdout
    (args.out/'permission-owner.txt').write_text(permission)
    if 'sourcePackage=' + FIXTURE not in permission: raise RuntimeError('Foreign permission ownership changed')
    # Installation order must work both ways: original-signer app can be installed later too.
    adb('uninstall', FIXTURE)
    adb('uninstall', SECOND_FIXTURE)
    reinstall = adb('install', '--no-streaming', str(args.out/'emulator-only-permission-owner.apk'))
    (args.out/'owner-reinstalled.txt').write_text(reinstall.stdout)
    adb('install', '--no-streaming', str(args.out/'emulator-only-second-owner.apk'))
    report = {'before_sha256': hashlib.sha256(args.before.read_bytes()).hexdigest(),
              'after_sha256': hashlib.sha256(args.after.read_bytes()).hexdigest(),
              'before_error': 'INSTALL_FAILED_DUPLICATE_PERMISSION', 'permission': PERMISSION,
              'different_signer_fixture': FIXTURE, 'after_installed': True,
              'owner_preserved': True, 'both_install_orders': True, 'neighbor_apps': 2}
    (args.out/'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2), flush=True)


if __name__ == '__main__':
    main()
