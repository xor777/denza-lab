#!/usr/bin/env python3
"""Build an owner-only experimental Navigator APK from the SHA-pinned original."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile
import xml.etree.ElementTree as ET
from identity import (APK, PIN, BASE_VERSION_CODE, BASE_VERSION_NAME, BUILD_NUMBER, LABEL, VERSION_CODE,
                      VERSION_NAME)
from manifest import LAUNCH_TARGET, QUERIED_PACKAGES, SHARED_PERMISSION, edit
from resources import ACCOUNT_TYPE, ORIGINAL_ACCOUNT_TYPE, isolate_account_type
from stock_art import extract as stock_art
import symbols

ROOT = Path(__file__).resolve().parents[2]


def run(args, **kwargs):
    subprocess.run([str(x) for x in args], check=True, **kwargs)


def excluded_native_entries(archive, abi):
    """Filter only ABI-specific lib/ entries; never trim code, resources or assets."""
    names = {info.filename for info in archive.infolist()
             if info.filename.startswith('lib/') and not info.is_dir()}
    available = {name.split('/')[1] for name in names}
    if abi == 'universal':
        return set()
    if abi not in available:
        raise ValueError('Requested ABI is missing from the original APK: ' + abi)
    return {name for name in names if name.split('/')[1] != abi}


def verify_manifest(original, candidate, env):
    """Independently decode binary XML with the Android SDK, then compare its semantics."""
    def read(path):
        return ET.fromstring(subprocess.check_output(['apkanalyzer', 'manifest', 'print', str(path)], env=env))
    before, after = read(original), read(candidate)
    android = '{http://schemas.android.com/apk/res/android}'
    for name, base in [('versionName', BASE_VERSION_NAME), ('versionCode', str(BASE_VERSION_CODE))]:
        if before.attrib[android + name] != base:
            raise RuntimeError('identity.py base does not match the input APK: ' + name)
    for name, expected in [('versionName', VERSION_NAME), ('versionCode', str(VERSION_CODE))]:
        if after.attrib[android + name] != expected:
            raise RuntimeError('YandexHUD build identity mismatch: ' + name)
        after.attrib[android + name] = before.attrib[android + name]
    if after.find('application').attrib.pop(android + 'debuggable') != 'true':
        raise RuntimeError('Candidate must be a debug build')
    if after.find('application').attrib[android + 'label'] != LABEL:
        raise RuntimeError('Experimental launcher label missing')
    after.find('application').attrib[android + 'label'] = before.find('application').attrib[android + 'label']
    target = lambda root: next(node for node in root.find('application').findall('activity')
                               if node.attrib.get(android + 'name') == LAUNCH_TARGET)
    if target(after).attrib[android + 'label'] != LABEL:
        raise RuntimeError('Experimental launcher label missing on the launch activity')
    target(after).attrib[android + 'label'] = target(before).attrib[android + 'label']
    queries = after.find('queries')
    for queried in QUERIED_PACKAGES:  # The HUD service, and Denza Apps as the host.
        added = [node for node in queries if node.tag == 'package'
                 and node.attrib == {android + 'name': queried}]
        if len(added) != 1: raise RuntimeError('Package query mismatch: ' + queried)
        queries.remove(added[0])
    shared = [node for node in before.findall('permission') if node.attrib.get(android + 'name') == SHARED_PERMISSION]
    if len(shared) != 1: raise RuntimeError('Expected one original shared signature permission')
    before.remove(shared[0])
    def tree(node):
        return node.tag, dict(node.attrib), (node.text or '').strip(), [tree(child) for child in node]
    if tree(before) != tree(after): raise RuntimeError('Unexpected manifest change')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=ROOT / APK)
    parser.add_argument('--stock-apk', type=Path, default=ROOT / 'captures/hud-firmware-20260923/system/system/app/BydLaunchermap/BydLaunchermap.apk')
    parser.add_argument('--abi', choices=('universal', 'arm64-v8a'), default='universal',
                        help='Keep all architectures, or only ARM64 native libraries')
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--renderer-profile', choices=('current', 'stock-prototype'), default='current',
                        help='Opt-in map presentation; current keeps the existing owner design')
    parser.add_argument('--jadx-jar', type=Path, default=Path('/opt/homebrew/Cellar/jadx/1.5.5/libexec/lib/jadx-1.5.5-all.jar'))
    args = parser.parse_args()
    args.out = args.out.resolve()
    if hashlib.sha256(args.apk.read_bytes()).hexdigest() != PIN:
        parser.error(f'Input APK is not the reviewed Navigator {BASE_VERSION_NAME}; check a new one with symbols.py')
    artwork, artwork_report = stock_art(args.stock_apk)
    args.out.mkdir(parents=True, exist_ok=False)
    sdk = Path(os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools'))
    java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
    env = dict(os.environ, JAVA_HOME=str(java))
    build_tools = sdk / 'build-tools/36.0.0'
    android = sdk / 'platforms/android-35/android.jar'
    unit_classes = args.out / 'test-classes'; unit_classes.mkdir()
    run(['python3', ROOT/'tools/yandex_hud_build/stock_camera_defaults.py', '--check'])
    # The hook classes' dex files and every MapKit name the module reaches, before any build work.
    names = symbols.check(args.apk, java, args.out, args.jadx_jar)
    if names['missing']:
        raise RuntimeError('Navigator names moved: ' + json.dumps(names['missing'], indent=2))
    hook_dex = {names['hooks']['context']: 'context', names['hooks']['guidance']: 'guidance'}
    if len(hook_dex) != 2: raise RuntimeError('Both hooks in one dex: PatchHooks patches one hook per dex')
    package = ROOT / 'experiments/yandex-hud-module/src/main/java/dev/denza/hud/probe'
    # The HUD wire and compositor shared with Denza Apps; the plain-Java part the module's fields
    # pass through (the scene, the placement table, the road packet) runs in the host tests too.
    link = ROOT / 'libraries/hud-link/src/main/java'
    plain = ['HudMessage', 'HudProtocol', 'HudFields', 'HudRoadPacket', 'HudRole', 'HudScene', 'HudComposition',
             'HudLayer', 'HudMode', 'HudWindow', 'HudRect', 'HudImage', 'HudSource', 'HudSink']
    # The stock HUD camera lives in the library too (dev.denza.hudlink.camera); its oracle and
    # controller tests are plain mains, run here and, through a JUnit wrapper, by Gradle.
    camera_tests = ROOT / 'libraries/hud-link/src/test/java/dev/denza/hudlink/camera'
    run([java/'bin/javac', '--release', '8', '-encoding', 'UTF-8', '-d', unit_classes,
         *[link/'dev/denza/hudlink'/(name + '.java') for name in plain],
         *sorted((link/'dev/denza/hudlink/camera').glob('*.java')),
         camera_tests/'CameraTest.java', camera_tests/'StockCameraTest.java',
         package/'HudWire.java', package/'ModuleFields.java', package/'Reflect.java', package/'NavigatorSource.java',
         package/'StockHudSymbols.java',
         package/'HudAr.java', package/'HudArSource.java', package/'HudEvents.java', package/'ManeuverHold.java', package/'ManeuverMeters.java',
         *sorted((ROOT/'experiments/yandex-hud-module/src/test/java/dev/denza/hud/probe').glob('*.java'))])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hud.probe.ModuleTest'])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hud.probe.ArTest'])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hudlink.camera.CameraTest',
         ROOT/'research/yandex-hud-renderer/stock-camera-queue-oracle.csv',
         ROOT/'research/yandex-hud-renderer/stock-camera-projection-oracle.csv',
         ROOT/'research/yandex-hud-renderer/stock-camera-rule-oracle.csv',
         ROOT/'research/yandex-hud-renderer/stock-camera-curvature-oracle.csv'])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hudlink.camera.StockCameraTest',
         ROOT/'research/yandex-hud-renderer/stock-camera-mapkit-calibration.csv'])
    selected = subprocess.check_output([str(java/'bin/java'), '-cp', str(unit_classes),
                                       'dev.denza.hud.probe.ModuleTest', 'assets'], text=True).splitlines()
    for name in selected:
        if 'assets/denza-hud/stock/' + name + '.png' not in artwork:
            raise RuntimeError('Missing mapped stock artwork: ' + name)
    classes = args.out / 'module-classes'; classes.mkdir()
    sources = sorted((ROOT / 'experiments/yandex-hud-module/src/main/java').rglob('*.java')) + sorted(link.rglob('*.java'))
    run([java/'bin/javac', '--release', '8', '-encoding', 'UTF-8', '-classpath', android, '-d', classes, *sources])
    run([java/'bin/jar', 'cf', args.out/'module.jar', '-C', classes, '.'])
    dex = args.out/'module-dex'; dex.mkdir()
    run([build_tools/'d8', '--min-api', '26', '--lib', android, '--output', dex, args.out/'module.jar'], env=env)
    patcher = args.out/'patcher'; patcher.mkdir()
    run([java/'bin/javac', '-cp', args.jadx_jar, '-d', patcher, ROOT/'tools/yandex_hud_build/PatchHooks.java'])
    changes = dict(artwork)
    with zipfile.ZipFile(args.apk) as original:
        excluded = excluded_native_entries(original, args.abi)
        removed_native_bytes = sum(original.getinfo(name).compress_size for name in excluded)
        for name, mode in hook_dex.items():
            source = args.out/('original-'+name); source.write_bytes(original.read(name))
            target = args.out/name
            run([java/'bin/java', '-Xmx1g', '-cp', str(patcher)+os.pathsep+str(args.jadx_jar), 'PatchHooks', source, target, mode, args.out/'guidance-hook.dex'])
            changes[name] = target.read_bytes()
        changes['AndroidManifest.xml'] = edit(original.read('AndroidManifest.xml'))
        changes['resources.arsc'] = isolate_account_type(original.read('resources.arsc'))
        dex_names = [x for x in original.namelist() if x.startswith('classes') and x.endswith('.dex')]
        extra = 'classes'+str(max([1 if x == 'classes.dex' else int(x[7:-4]) for x in dex_names])+1)+'.dex'
        changes[extra] = (dex/'classes.dex').read_bytes()
        changes['classes'+str(int(extra[7:-4])+1)+'.dex'] = (args.out/'guidance-hook.dex').read_bytes()
        changes['assets/denza-hud/theme.json'] = subprocess.check_output(['node', ROOT/'tools/yandex_hud_build/theme.cjs', args.renderer_profile], cwd=ROOT)
        with zipfile.ZipFile(args.out/'unsigned.apk', 'w', allowZip64=False) as output:
            for info in original.infolist():
                if info.filename in excluded:
                    continue
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
    architecture = 'arm64' if args.abi == 'arm64-v8a' else 'universal'
    profile_suffix = '-stock-prototype' if args.renderer_profile == 'stock-prototype' else ''
    result = args.out/f'{LABEL}-{BUILD_NUMBER}{profile_suffix}-{architecture}-debug.apk'
    run([build_tools/'apksigner', 'sign', '--ks', key, '--ks-key-alias', 'hud-experiment', '--ks-pass', 'pass:denza-local-experiment', '--out', result, args.out/'aligned.apk'], env=env)
    run([build_tools/'apksigner', 'verify', '--verbose', '--print-certs', result], env=env)
    run([build_tools/'zipalign', '-c', '-P', '16', '4', result])
    verify_manifest(args.apk, result, env)
    # Independent Android SDK decoder: exactly one resource value may differ.
    def resource_dump(apk):
        return subprocess.check_output([str(build_tools/'aapt2'), 'dump', 'resources', str(apk)], text=True)
    before_resources, after_resources = resource_dump(args.apk), resource_dump(result)
    old_value = re.search(r'resource 0x[0-9a-f]{8} string/passport_account_type\n      \(\) "'
                          + re.escape(ORIGINAL_ACCOUNT_TYPE) + '"', before_resources)
    old_value = old_value.group(0) if old_value else 'string/passport_account_type missing'
    new_value = old_value.replace(ORIGINAL_ACCOUNT_TYPE, ACCOUNT_TYPE)
    if before_resources.count(old_value) != 1 or after_resources != before_resources.replace(old_value, new_value):
        raise RuntimeError('Unexpected resource change beyond passport_account_type')
    report = {'input_sha256': PIN, 'apk': str(result), 'sha256': hashlib.sha256(result.read_bytes()).hexdigest(),
              'extra_dex': extra, 'module_source_sha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
              'package': 'ru.yandex.yandexnavi', 'label': LABEL, 'build_number': BUILD_NUMBER,
              'version': VERSION_NAME, 'version_code': VERSION_CODE, 'debuggable': True,
              'runtime': 'embedded Java, no Frida', 'manifest_semantic_check': 'passed'}
    report['removed_permission_declarations'] = [SHARED_PERMISSION]
    report['account_type'] = ACCOUNT_TYPE
    report['hook_dex'] = hook_dex
    report['abi'] = args.abi
    report['renderer_profile'] = args.renderer_profile
    report['apk_bytes'] = result.stat().st_size
    report['removed_native_entries'] = sorted(excluded)
    report['removed_native_compressed_bytes'] = removed_native_bytes
    report['stock_artwork'] = artwork_report
    report['resource_semantic_check'] = 'passed: only passport_account_type changed'
    # Prove untouched ZIP contents, not just a successful signature.
    with zipfile.ZipFile(args.apk) as before, zipfile.ZipFile(result) as after:
        allowed = {*hook_dex, 'AndroidManifest.xml', 'resources.arsc'}
        missing = {name for name in set(before.namelist()) - set(after.namelist())
                   if not name.startswith('META-INF/')}
        if missing != excluded: raise RuntimeError('Unexpected removed APK entries: ' + str(missing ^ excluded))
        report['native_abis'] = sorted({name.split('/')[1] for name in after.namelist()
                                       if name.startswith('lib/') and not name.endswith('/')})
        if args.abi != 'universal' and report['native_abis'] != [args.abi]:
            raise RuntimeError('Unexpected native ABIs in candidate')
        changed = []
        for name in before.namelist():
            if name.startswith('META-INF/') or name in excluded: continue
            if before.read(name) != after.read(name): changed.append(name)
        if set(changed) != allowed: raise RuntimeError('Unexpected changed APK entries: '+str(changed))
        report['changed_original_entries'] = changed
        for name, content in artwork.items():
            if after.read(name) != content: raise RuntimeError('Stock asset changed: ' + name)
    (args.out/'build-report.json').write_text(json.dumps(report, indent=2)+'\n')
    result.with_suffix('.apk.sha256').write_text(report['sha256'] + '  ' + result.name + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
