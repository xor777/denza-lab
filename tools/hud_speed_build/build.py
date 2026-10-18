#!/usr/bin/env python3
"""Build a locally signed HUD Speed with the host-only Denza adapter; no network or install."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile
import xml.etree.ElementTree as ET
from identity import PIN, LABEL, VERSION_CODE, VERSION_NAME, PACKAGE
from manifest import edit

ROOT = Path(__file__).resolve().parents[2]
CERTIFICATE = '4e2b390deba4ee0b336f85d916a372ca0dfe3fd70a7379cb22fe778773f92202'


def run(args, **kwargs):
    subprocess.run([str(x) for x in args], check=True, **kwargs)


def verify_manifest(original, candidate, analyzer, env):
    def read(path):
        return ET.fromstring(subprocess.check_output([str(analyzer), 'manifest', 'print', str(path)], env=env))
    before, after = read(original), read(candidate)
    android = '{http://schemas.android.com/apk/res/android}'
    for name, expected in [('versionName', VERSION_NAME), ('versionCode', str(VERSION_CODE))]:
        if after.attrib[android + name] != expected:
            raise RuntimeError('Build identity mismatch: ' + name)
        after.attrib[android + name] = before.attrib[android + name]
    if after.find('application').attrib[android + 'label'] != LABEL:
        raise RuntimeError('Adapter label missing')
    after.find('application').attrib[android + 'label'] = before.find('application').attrib[android + 'label']
    queries = after.find('queries')
    added = [q for q in queries if q.tag == 'package' and q.attrib == {android + 'name': 'dev.denza.apps'}]
    if len(added) != 1: raise RuntimeError('Host query mismatch')
    queries.remove(added[0])
    def tree(node): return node.tag, dict(node.attrib), (node.text or '').strip(), [tree(c) for c in node]
    if tree(before) != tree(after): raise RuntimeError('Unexpected manifest change')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=ROOT/'captures/hud-speed-20261018/hudspeed-radarbase-release-79000000.apk')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if hashlib.sha256(args.apk.read_bytes()).hexdigest() != PIN:
        parser.error('Input must be the reviewed HUD Speed 79.0 APK')
    args.out = args.out.resolve()
    args.out.mkdir(parents=True, exist_ok=False)
    sdk = Path(os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools'))
    java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
    env = dict(os.environ, JAVA_HOME=str(java))
    bt = sdk/'build-tools/36.0.0'
    android = sdk/'platforms/android-35/android.jar'
    jadx = Path('/opt/homebrew/Cellar/jadx/1.5.5/libexec/lib/jadx-1.5.5-all.jar')
    link = ROOT/'libraries/hud-link/src/main/java/dev/denza/hudlink'
    # Deliberately exclude HudLink/HudOutput/HudSomeIp: this APK cannot become a second writer.
    shared = ['HudHostClient', 'HudHostProtocol', 'HudRadarWarning', 'HudSink', 'HudSource',
              'HudRole', 'HudFields', 'HudRect', 'HudImage', 'HudWindow', 'HudMode', 'HudProtocol', 'HudMessage']
    module = ROOT/'experiments/hud-speed-module'
    sources = sorted((module/'src/main/java').rglob('*.java')) + [link/(name+'.java') for name in shared]
    classes = args.out/'classes'; classes.mkdir()
    run([java/'bin/javac', '--release', '8', '-encoding', 'UTF-8', '-cp', android, '-d', classes, *sources])
    # Model tests use production reflection against named fixture objects, with no Android runtime.
    tests = args.out/'tests'; tests.mkdir()
    run([java/'bin/javac', '--release', '8', '-encoding', 'UTF-8', '-cp', classes, '-d', tests,
         *sorted((module/'src/test/java').rglob('*.java'))])
    run([java/'bin/java', '-cp', str(classes)+os.pathsep+str(tests), 'dev.denza.hud.speed.SourceTest'])
    run([java/'bin/jar', 'cf', args.out/'module.jar', '-C', classes, '.'])
    dex = args.out/'dex'; dex.mkdir()
    run([bt/'d8', '--min-api', '23', '--lib', android, '--output', dex, args.out/'module.jar'], env=env)
    patcher = args.out/'patcher'; patcher.mkdir()
    run([java/'bin/javac', '-cp', jadx, '-d', patcher, ROOT/'tools/hud_speed_build/PatchHooks.java'])
    with zipfile.ZipFile(args.apk) as original:
        if {x for x in original.namelist() if x.startswith('classes') and x.endswith('.dex')} != {'classes.dex', 'classes2.dex'}:
            raise RuntimeError('Unexpected input dex layout')
        source = args.out/'original.dex'; source.write_bytes(original.read('classes.dex'))
        run([java/'bin/java', '-Xmx1g', '-cp', str(patcher)+os.pathsep+str(jadx), 'PatchHooks', source,
             args.out/'patched.dex', args.out/'service.dex'])
        changes = {'classes.dex': (args.out/'patched.dex').read_bytes(),
                   'AndroidManifest.xml': edit(original.read('AndroidManifest.xml')),
                   'classes3.dex': (args.out/'service.dex').read_bytes(),
                   'classes4.dex': (dex/'classes.dex').read_bytes()}
        with zipfile.ZipFile(args.out/'unsigned.apk', 'w') as output:
            for info in original.infolist():
                if info.filename.startswith('META-INF/') and (info.filename.endswith(('.SF','.RSA','.DSA','.EC')) or info.filename == 'META-INF/MANIFEST.MF'):
                    continue
                output.writestr(info, changes.pop(info.filename, original.read(info.filename)))
            for name, content in changes.items(): output.writestr(name, content, compress_type=zipfile.ZIP_DEFLATED)
    run([bt/'zipalign', '-P', '16', '-f', '4', args.out/'unsigned.apk', args.out/'aligned.apk'])
    key = ROOT/'captures/hud-speed-signing/adapter.p12'
    if not key.exists():
        raise RuntimeError('Owner signing key missing: restore captures/hud-speed-signing/adapter.p12; never silently rotate it')
    result = args.out/'HUD-Speed-Denza-79.0-1.apk'
    run([bt/'apksigner', 'sign', '--ks', key, '--ks-key-alias', 'hud-speed', '--ks-pass',
         'pass:denza-local-experiment', '--out', result, args.out/'aligned.apk'], env=env)
    signature = subprocess.check_output([str(bt/'apksigner'), 'verify', '--verbose', '--print-certs', str(result)], env=env, text=True)
    if 'Signer #1 certificate SHA-256 digest: '+CERTIFICATE not in signature:
        raise RuntimeError('Signer does not match the Denza Apps gate')
    (args.out/'signature.txt').write_text(signature)
    run([bt/'zipalign', '-c', '-P', '16', '4', result])
    verify_manifest(args.apk, result, sdk/'cmdline-tools/latest/bin/apkanalyzer', env)
    with zipfile.ZipFile(args.apk) as before, zipfile.ZipFile(result) as after:
        changed = [name for name in before.namelist() if not name.startswith('META-INF/') and before.read(name) != after.read(name)]
        if set(changed) != {'AndroidManifest.xml', 'classes.dex'}:
            raise RuntimeError('Unexpected changed entries: '+str(changed))
        added = set(after.namelist()) - set(before.namelist())
        if {name for name in added if not name.startswith('META-INF/')} != {'classes3.dex', 'classes4.dex'}:
            raise RuntimeError('Unexpected added entries')
    report = dict(input_sha256=PIN, package=PACKAGE, version=VERSION_NAME, version_code=VERSION_CODE,
                  apk=str(result), sha256=hashlib.sha256(result.read_bytes()).hexdigest(), certificate_sha256=CERTIFICATE,
                  changed_original_entries=changed, manifest_semantic_check='passed', local_hud_writer=False,
                  source_sha256={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources})
    (args.out/'build-report.json').write_text(json.dumps(report, indent=2)+'\n')
    result.with_suffix('.apk.sha256').write_text(report['sha256']+'  '+result.name+'\n')
    print(json.dumps({k:v for k,v in report.items() if k != 'source_sha256'}, indent=2))


if __name__ == '__main__': main()
