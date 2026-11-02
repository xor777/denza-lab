#!/usr/bin/env python3
"""Check any HUD Speed APK for every obfuscated name the adapter reaches; no pin, no build.

Run it on a new upstream APK before moving the pin: it prints the APK's version and each
member that moved, with candidates of the same shape. build.py runs the same check.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
JADX = Path('/opt/homebrew/Cellar/jadx/1.5.5/libexec/lib/jadx-1.5.5-all.jar')


def check(apk, java, work):
    """Symbols.java's report for apk: service_dex, the hook event types, missing members."""
    classes = Path(work)/'symbols'; classes.mkdir(exist_ok=True)
    subprocess.run([str(java/'bin/javac'), '-cp', str(JADX), '-d', str(classes),
                    str(ROOT/'tools/hud_speed_build/Symbols.java')], check=True)
    return json.loads(subprocess.check_output([str(java/'bin/java'), '-Xmx1g', '-cp',
                                               str(classes)+os.pathsep+str(JADX), 'Symbols', str(apk)]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    args = parser.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools'))
    java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
    analyzer = sdk/'cmdline-tools/latest/bin/apkanalyzer'
    env = dict(os.environ, JAVA_HOME=str(java))
    version = {name: subprocess.check_output([str(analyzer), 'manifest', name, str(args.apk)], env=env, text=True).strip()
               for name in ('version-name', 'version-code')}
    with tempfile.TemporaryDirectory() as work:
        report = check(args.apk, java, work)
    print(json.dumps(dict(version, **report), indent=2, ensure_ascii=False))
    if report['missing']: raise SystemExit(1)


if __name__ == '__main__': main()
