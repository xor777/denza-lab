#!/usr/bin/env python3
"""Build an owner-only experimental Navigator APK from the SHA-pinned original."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile
import xml.etree.ElementTree as ET
from manifest import edit

ROOT = Path(__file__).resolve().parents[2]
PIN = '3b0dec3277f261fd2a2e8b6c0ccb287b1c2bf629b5333bff213fb2f6425024b3'


def run(args, **kwargs):
    subprocess.run([str(x) for x in args], check=True, **kwargs)


def verify_manifest(original, candidate, env):
    """Independently decode binary XML with the Android SDK, then compare its semantics."""
    def read(path):
        return ET.fromstring(subprocess.check_output(['apkanalyzer', 'manifest', 'print', str(path)], env=env))
    before, after = read(original), read(candidate)
    android = '{http://schemas.android.com/apk/res/android}'
    if after.attrib[android + 'versionName'] != '29.8.1-denza-hud-exp.1':
        raise RuntimeError('Experimental version missing')
    after.attrib[android + 'versionName'] = before.attrib[android + 'versionName']
    if after.find('application').attrib.pop(android + 'debuggable') != 'true':
        raise RuntimeError('Candidate must be a debug build')
    queries = after.find('queries')
    added = [node for node in queries if node.tag == 'package'
             and node.attrib == {android + 'name': 'com.ts.car.someip.service'}]
    if len(added) != 1: raise RuntimeError('HUD query mismatch')
    queries.remove(added[0])
    def tree(node):
        return node.tag, dict(node.attrib), (node.text or '').strip(), [tree(child) for child in node]
    if tree(before) != tree(after): raise RuntimeError('Unexpected manifest change')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=ROOT / 'captures/hud-lanes-20261011/yandex-installed.apk')
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--jadx-jar', type=Path, default=Path('/opt/homebrew/Cellar/jadx/1.5.5/libexec/lib/jadx-1.5.5-all.jar'))
    args = parser.parse_args()
    args.out = args.out.resolve()
    if hashlib.sha256(args.apk.read_bytes()).hexdigest() != PIN:
        parser.error('Input APK is not the reviewed Navigator 29.8.1')
    args.out.mkdir(parents=True, exist_ok=False)
    sdk = Path(os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools'))
    java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
    env = dict(os.environ, JAVA_HOME=str(java))
    build_tools = sdk / 'build-tools/36.0.0'
    android = sdk / 'platforms/android-35/android.jar'
    unit_classes = args.out / 'test-classes'; unit_classes.mkdir()
    package = ROOT / 'experiments/yandex-hud-module/src/main/java/dev/denza/hud/probe'
    run([java/'bin/javac', '--release', '8', '-d', unit_classes,
         package/'HudWire.java', package/'Reflect.java', package/'NavigatorSource.java',
         ROOT/'experiments/yandex-hud-module/src/test/java/dev/denza/hud/probe/ModuleTest.java'])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hud.probe.ModuleTest'])
    classes = args.out / 'module-classes'; classes.mkdir()
    sources = sorted((ROOT / 'experiments/yandex-hud-module/src/main/java').rglob('*.java'))
    run([java/'bin/javac', '--release', '8', '-classpath', android, '-d', classes, *sources])
    run([java/'bin/jar', 'cf', args.out/'module.jar', '-C', classes, '.'])
    dex = args.out/'module-dex'; dex.mkdir()
    run([build_tools/'d8', '--min-api', '26', '--lib', android, '--output', dex, args.out/'module.jar'], env=env)
    patcher = args.out/'patcher'; patcher.mkdir()
    run([java/'bin/javac', '-cp', args.jadx_jar, '-d', patcher, ROOT/'tools/yandex_hud_build/PatchHooks.java'])
    changes = {}
    with zipfile.ZipFile(args.apk) as original:
        for name, mode in [('classes10.dex', 'context'), ('classes12.dex', 'guidance')]:
            source = args.out/('original-'+name); source.write_bytes(original.read(name))
            target = args.out/name
            run([java/'bin/java', '-Xmx1g', '-cp', str(patcher)+os.pathsep+str(args.jadx_jar), 'PatchHooks', source, target, mode, args.out/'guidance-hook.dex'])
            changes[name] = target.read_bytes()
        changes['AndroidManifest.xml'] = edit(original.read('AndroidManifest.xml'))
        dex_names = [x for x in original.namelist() if x.startswith('classes') and x.endswith('.dex')]
        extra = 'classes'+str(max([1 if x == 'classes.dex' else int(x[7:-4]) for x in dex_names])+1)+'.dex'
        changes[extra] = (dex/'classes.dex').read_bytes()
        changes['classes'+str(int(extra[7:-4])+1)+'.dex'] = (args.out/'guidance-hook.dex').read_bytes()
        changes['assets/denza-hud/theme.json'] = subprocess.check_output(['node', ROOT/'tools/yandex_hud_build/theme.cjs'], cwd=ROOT)
        with zipfile.ZipFile(args.out/'unsigned.apk', 'w', allowZip64=False) as output:
            for info in original.infolist():
                if info.filename.startswith('META-INF/') and (info.filename.endswith(('.SF','.RSA','.DSA','.EC')) or info.filename == 'META-INF/MANIFEST.MF'):
                    continue
                output.writestr(info, changes.pop(info.filename, original.read(info.filename)))
            for name, content in changes.items(): output.writestr(name, content, compress_type=zipfile.ZIP_DEFLATED)
    run([build_tools/'zipalign', '-P', '16', '-f', '4', args.out/'unsigned.apk', args.out/'aligned.apk'])
    # Dedicated local experimental identity, never committed. No original certificate spoofing.
    identity = ROOT/'captures/yandex-hud-signing'; identity.mkdir(exist_ok=True)
    key = identity/'experiment.p12'
    if not key.exists():
        run([java/'bin/keytool', '-genkeypair', '-keystore', key, '-storepass', 'denza-local-experiment',
             '-keypass', 'denza-local-experiment', '-alias', 'hud-experiment', '-keyalg', 'RSA', '-keysize', '3072',
             '-validity', '3650', '-dname', 'CN=Denza HUD local experiment, O=Owner local research'])
    result = args.out/'yandex-navigator-29.8.1-denza-hud-exp.1.apk'
    run([build_tools/'apksigner', 'sign', '--ks', key, '--ks-key-alias', 'hud-experiment', '--ks-pass', 'pass:denza-local-experiment', '--out', result, args.out/'aligned.apk'], env=env)
    run([build_tools/'apksigner', 'verify', '--verbose', '--print-certs', result], env=env)
    run([build_tools/'zipalign', '-c', '-P', '16', '4', result])
    verify_manifest(args.apk, result, env)
    report = {'input_sha256': PIN, 'apk': str(result), 'sha256': hashlib.sha256(result.read_bytes()).hexdigest(),
              'extra_dex': extra, 'module_source_sha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
              'package': 'ru.yandex.yandexnavi', 'version': '29.8.1-denza-hud-exp.1', 'debuggable': True,
              'runtime': 'embedded Java, no Frida', 'manifest_semantic_check': 'passed'}
    # Prove untouched ZIP contents, not just a successful signature.
    with zipfile.ZipFile(args.apk) as before, zipfile.ZipFile(result) as after:
        allowed = {'classes10.dex','classes12.dex','AndroidManifest.xml'}
        changed = []
        for name in before.namelist():
            if name.startswith('META-INF/'): continue
            if before.read(name) != after.read(name): changed.append(name)
        if set(changed) != allowed: raise RuntimeError('Unexpected changed APK entries: '+str(changed))
        report['changed_original_entries'] = changed
    (args.out/'build-report.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
