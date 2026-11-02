#!/usr/bin/env python3
"""Check any Navigator APK for the two hooks and the MapKit names the module reaches; no pin, no build.

Run it on a new upstream APK before moving the pin: it prints the APK's version, the dex holding
each hook and every class or method name that is gone. build.py runs the same check. A name that
exists can still have changed meaning: the emulator acceptance stays the proof.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT/'experiments/yandex-hud-module/src/main/java/dev/denza/hud/probe'
JADX = Path('/opt/homebrew/Cellar/jadx/1.5.5/libexec/lib/jadx-1.5.5-all.jar')


def names():
    """Reflect.type/make class names and Reflect.call method names, read from the module source."""
    classes, methods = set(), set()
    for path in sorted(MODULE.glob('*.java')):
        text = path.read_text()
        classes.update(re.findall(r'"(com\.yandex\.[\w.$]+)"', text))
        for start in (m.end() for m in re.finditer(r'\bcall\(', text)):
            depth, i = 0, start  # The method name follows the target, the first top-level argument.
            while depth or text[i] != ',':
                depth += {'(': 1, ')': -1}.get(text[i], 0)
                if depth < 0: break
                i += 1
            name = re.match(r',\s*"(\w+)"', text[i:])
            if name: methods.add(name.group(1))
    return sorted(classes), sorted(methods)


def check(apk, java, work, jadx=JADX):
    """Symbols.java's report for apk: the dex of each hook class and everything missing."""
    work = Path(work)
    classes = work/'symbols'; classes.mkdir(exist_ok=True)
    subprocess.run([str(java/'bin/javac'), '-cp', str(jadx), '-d', str(classes),
                    str(ROOT/'tools/yandex_hud_build/Symbols.java')], check=True)
    types, methods = names()
    listing = work/'symbols.txt'
    listing.write_text(''.join(f'class {x}\n' for x in types) + ''.join(f'method {x}\n' for x in methods))
    report = json.loads(subprocess.check_output([str(java/'bin/java'), '-Xmx2g', '-cp',
                                                 str(classes)+os.pathsep+str(jadx), 'Symbols', str(apk), str(listing)]))
    report['checked'] = {'classes': len(types), 'methods': len(methods)}
    return report


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
