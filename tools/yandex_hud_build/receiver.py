#!/usr/bin/env python3
"""Build ONLY the isolated emulator's protocol recorder; never install it on the car."""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    sdk = Path(os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools'))
    java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
    tools = sdk / 'build-tools/36.0.0'
    android = sdk / 'platforms/android-35/android.jar'
    env = dict(os.environ, JAVA_HOME=str(java))

    def run(command):
        subprocess.run([str(value) for value in command], check=True, env=env)

    source = root / 'experiments/yandex-hud-module/test-receiver'
    classes, dex = out / 'classes', out / 'dex'
    classes.mkdir()
    dex.mkdir()
    run([java/'bin/javac', '--release', '8', '-cp', android, '-d', classes, *sorted(source.rglob('*.java'))])
    run([java/'bin/jar', 'cf', out/'classes.jar', '-C', classes, '.'])
    run([tools/'d8', '--min-api', '26', '--lib', android, '--output', dex, out/'classes.jar'])
    run([tools/'aapt2', 'link', '-I', android, '--manifest', source/'AndroidManifest.xml',
         '--version-code', '1', '--version-name', '1', '-o', out/'unsigned.apk'])
    with zipfile.ZipFile(out/'unsigned.apk', 'a') as archive:
        archive.write(dex/'classes.dex', 'classes.dex')
    run([tools/'zipalign', '-f', '4', out/'unsigned.apk', out/'aligned.apk'])
    run([tools/'apksigner', 'sign', '--ks', root/'captures/yandex-hud-signing/experiment.p12',
         '--ks-pass', 'pass:denza-local-experiment', '--out', out/'emulator-only-hud-receiver.apk', out/'aligned.apk'])


if __name__ == '__main__':
    main()
