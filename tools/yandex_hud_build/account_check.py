#!/usr/bin/env python3
"""Prove account-type isolation in a disposable emulator, with no user credentials."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'ru.yandex.yandexnavi'
OWNER = 'dev.denza.hudaccountowner.probe'
CHECK = 'dev.denza.hudaccountcheck.probe'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--before', type=Path, required=True)
    parser.add_argument('--after', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('Disposable emulator only')

    def adb(*command):
        return subprocess.check_output(['adb', '-s', args.serial, *map(str, command)],
                                       timeout=90, text=True, stderr=subprocess.STDOUT)

    if adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        parser.error('Not QEMU')
    installed = adb('shell', 'pm', 'list', 'packages').splitlines()
    for package in (PACKAGE, OWNER, CHECK):
        if 'package:' + package in installed:
            parser.error(f'{package} already installed; use a clean disposable emulator')
    args.out.mkdir(parents=True, exist_ok=False)
    sdk = Path(os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools'))
    java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
    env = dict(os.environ, JAVA_HOME=str(java))
    build_tools = sdk/'build-tools/36.0.0'
    android = sdk/'platforms/android-35/android.jar'

    def run(*command):
        subprocess.run([str(x) for x in command], env=env, timeout=60, check=True)

    owner_key = args.out/'owner.p12'
    run(java/'bin/keytool', '-genkeypair', '-keystore', owner_key, '-storepass', 'emulator-fixture',
        '-keypass', 'emulator-fixture', '-alias', 'owner', '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '30', '-dname', 'CN=Disposable account owner test')
    for name, key, password in (
            ('owner', owner_key, 'emulator-fixture'),
            ('access', ROOT/'captures/yandex-hud-signing/experiment.p12', 'denza-local-experiment')):
        source = ROOT/'experiments/yandex-hud-module'/('test-account-' + name)
        output = args.out/name
        output.mkdir()
        classes = output/'classes'
        classes.mkdir()
        run(java/'bin/javac', '--release', '8', '-classpath', android, '-d', classes, *source.glob('*.java'))
        run(java/'bin/jar', 'cf', output/'classes.jar', '-C', classes, '.')
        run(build_tools/'d8', '--min-api', '26', '--lib', android, '--output', output, output/'classes.jar')
        resources = []
        if (source/'res').exists():
            run(build_tools/'aapt2', 'compile', '--dir', source/'res', '-o', output/'resources.zip')
            resources.append(output/'resources.zip')
        run(build_tools/'aapt2', 'link', '-I', android, '--manifest', source/'AndroidManifest.xml',
            '--version-code', '1', '--version-name', '1', '-o', output/'unsigned.apk', *resources)
        with zipfile.ZipFile(output/'unsigned.apk', 'a') as apk:
            apk.write(output/'classes.dex', 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)
        run(build_tools/'zipalign', '-f', '4', output/'unsigned.apk', output/'aligned.apk')
        run(build_tools/'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:' + password,
            '--out', output/'emulator-only.apk', output/'aligned.apk')
    adb('install', '--no-streaming', args.out/'owner/emulator-only.apk')
    adb('install', '--no-streaming', args.before)
    adb('install', '--no-streaming', args.out/'access/emulator-only.apk')

    def check(name, expected):
        result = adb('shell', 'am', 'instrument', '-w', '-e', 'expectedType', expected,
                     CHECK + '/dev.denza.hud.accountcheck.AccountAccessCheck')
        (args.out/(name + '.txt')).write_text(result)
        print(result, flush=True)
        required = ['INSTRUMENTATION_CODE: -1', 'foreign_type_denied=true', 'resource_and_sdk_type=' + expected]
        if name == 'after':
            required += ['own_account_added=true', 'own_account_removed=true']
        if not all(marker in result for marker in required):
            raise RuntimeError('Account isolation check failed: ' + name)

    check('before', 'com.yandex.passport')
    adb('install', '--no-streaming', '-r', args.after)
    check('after', 'com.yandex.passport.denza')
    owner_lines = [line for line in adb('shell', 'dumpsys', 'account').splitlines()
                   if 'AuthenticatorDescription' in line and 'com.yandex.passport' in line]
    if not any('type=com.yandex.passport}' in line and OWNER in line for line in owner_lines):
        raise RuntimeError('Foreign owner changed')
    if not any('type=com.yandex.passport.denza}' in line and PACKAGE in line for line in owner_lines):
        raise RuntimeError('Missing isolated owner')
    (args.out/'owners.txt').write_text('\n'.join(owner_lines) + '\n')
    report = {'before_sha256': hashlib.sha256(args.before.read_bytes()).hexdigest(),
              'after_sha256': hashlib.sha256(args.after.read_bytes()).hexdigest(),
              'old_type_rejected': True, 'resource_and_sdk_type': 'com.yandex.passport.denza',
              'own_account_added_and_removed': True, 'foreign_type_still_denied': True,
              'owners': owner_lines, 'full_server_login_tested': False}
    (args.out/'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2), flush=True)


if __name__ == '__main__':
    main()
