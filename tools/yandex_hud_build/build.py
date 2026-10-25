#!/usr/bin/env python3
"""Build an owner-only experimental Navigator APK from the SHA-pinned original."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import zipfile
import xml.etree.ElementTree as ET
from identity import BUILD_NUMBER, LABEL, VERSION_CODE, VERSION_NAME
from manifest import LAUNCH_TARGET, QUERIED_PACKAGES, SHARED_PERMISSION, edit
from resources import ACCOUNT_TYPE, ORIGINAL_ACCOUNT_TYPE, isolate_account_type
from stock_art import extract as stock_art
from native_graph import build as build_native_graph
from native_vector import build as build_native_vector
from offline_zstd import prepare as prepare_offline_zstd

ROOT = Path(__file__).resolve().parents[2]
PIN = '3b0dec3277f261fd2a2e8b6c0ccb287b1c2bf629b5333bff213fb2f6425024b3'


def run(args, **kwargs):
    subprocess.run([str(x) for x in args], check=True, **kwargs)


# Explicit product closure. Historical senders/converters remain in research source,
# but cannot silently return to the module DEX when that tree gains a new Java file.
ADAS_PRODUCT_SHARED = (
    'AdasGateSnapshot', 'BootIdentity', 'BridgeStatus', 'Coordinates', 'DryRunRecorder',
    'FixFreshness', 'GraphRoadNetwork', 'GraphTile', 'GraphTileCache', 'Json', 'LaneGuidance',
    'LiveSessionLease', 'OfflineMapGate', 'OfflineVectorPackage', 'Pb', 'Publisher',
    'RoadFacilities', 'RoadNetwork', 'RouteAttributes', 'RouteVectorAttributes', 'RouteVectorTilePlan',
    'StockMessages', 'StockPath', 'StockRouteJson', 'Topics', 'VectorRoadEnrichment',
    'VectorRoadTile', 'VectorRoadTileCache', 'VectorTilePacket', 'YandexRoadClass', 'YandexRoute',
    'YandexTrafficContext',
)
LEGACY_ADAS_CLASSES = frozenset(('StockFeed', 'NavigatorDriver', 'LiveEgressGuard', 'RouteConverter',
                                'FieldDefaults', 'ExperimentVariant', 'LegacyRoadFacilities', 'LegacyLaneGuidance'))


def product_adas_sources(root=ROOT):
    base = root / 'libraries/adas-link/src/main/java/dev/denza/adaslink'
    return sorted([base / (name + '.java') for name in ADAS_PRODUCT_SHARED]
                  + list((base / 'stocknative').glob('*.java'))
                  + list((base / 'yandexnative').glob('*.java')))


def verify_product_classes(entries):
    for name in entries:
        if name.startswith('dev/denza/adaslink/') and name.endswith('.class'):
            simple = name.rsplit('/', 1)[-1].split('$', 1)[0].removesuffix('.class')
            if simple in LEGACY_ADAS_CLASSES:
                raise RuntimeError('Historical sender class leaked into product module: ' + name)


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
    for queried in QUERIED_PACKAGES:  # HUD service, Denza Apps host, stock-map state read.
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


def _edge_catalog(path, schema, asset, lane_count=None):
    """Validate and pin an optional owner-confirmed, private geometry evidence asset."""
    if path is None:
        return None, None
    if path.stat().st_size > 2_000_000:
        raise ValueError('Public-road evidence exceeds 2 MB')
    content = path.read_bytes()
    data = json.loads(content)
    if not isinstance(data, dict) or data.get('schema') != schema:
        raise ValueError('Public-road evidence schema')
    if lane_count is not None and (type(data.get('laneNum')) is not int or data['laneNum'] != lane_count):
        raise ValueError('Owner lane evidence must have confirmed total laneNum=2')
    for name, expected in [('coordinateFrame', 'WGS84'), ('pointOrder', 'lon,lat'),
                           ('units', 'degrees'), ('endpointGridDegrees', 1e-7)]:
        if name in data and data[name] != expected:
            raise ValueError('Road evidence coordinate metadata: ' + name)
    edges = data.get('edges')
    if not isinstance(edges, list) or len(edges) > 10000:
        raise ValueError('Public-road evidence edge limit')
    unique = set()
    for edge in edges:
        if not isinstance(edge, list) or len(edge) != 4:
            raise ValueError('Public-road evidence edge')
        if any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v)
               or abs(v) > (180 if i % 2 == 0 else 90) for i, v in enumerate(edge)):
            raise ValueError('Public-road evidence WGS84 bounds')
        q = tuple(math.floor(v * 1e7 + 0.5) for v in edge)
        if q[:2] == q[2:]:
            raise ValueError('Public-road evidence degenerate edge')
        unique.add(tuple(sorted((q[:2], q[2:]))))
    return content, {'schema': data['schema'], 'sha256': hashlib.sha256(content).hexdigest(),
                     'edges': len(unique), 'asset': asset, **({'laneNum': lane_count} if lane_count is not None else {})}


def public_road_catalog(path):
    return _edge_catalog(path, 'denza-owner-confirmed-public-roads-v1',
                         'assets/denza-hud/public-road-evidence.json')


def owner_lane_catalog(path):
    return _edge_catalog(path, 'denza-owner-confirmed-lanes-v1',
                         'assets/denza-hud/owner-lane-evidence.json', lane_count=2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=ROOT / 'captures/hud-lanes-20261011/yandex-installed.apk')
    parser.add_argument('--stock-apk', type=Path, default=ROOT / 'captures/hud-firmware-20260923/system/system/app/BydLaunchermap/BydLaunchermap.apk')
    parser.add_argument('--abi', choices=('universal', 'arm64-v8a'), default='universal',
                        help='Keep all architectures, or only ARM64 native libraries')
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--owner-lane-evidence', type=Path, help='Owner-confirmed total laneNum=2 on exact WGS84 edges')
    parser.add_argument('--public-road-evidence', type=Path, help='Owner-confirmed exact WGS84 edge catalog, packaged and hash-pinned')
    parser.add_argument('--renderer-profile', choices=('current', 'stock-prototype'), default='current',
                        help='Opt-in map presentation; current keeps the existing owner design')
    parser.add_argument('--jadx-jar', type=Path, default=Path('/opt/homebrew/Cellar/jadx/1.5.5/libexec/lib/jadx-1.5.5-all.jar'))
    args = parser.parse_args()
    args.out = args.out.resolve()
    if hashlib.sha256(args.apk.read_bytes()).hexdigest() != PIN:
        parser.error('Input APK is not the reviewed Navigator 29.8.1')
    public_roads, public_roads_report = public_road_catalog(args.public_road_evidence)
    owner_lanes, owner_lanes_report = owner_lane_catalog(args.owner_lane_evidence)
    artwork, artwork_report = stock_art(args.stock_apk)
    args.out.mkdir(parents=True, exist_ok=False)
    sdk = Path(os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools'))
    java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
    env = dict(os.environ, JAVA_HOME=str(java))
    build_tools = sdk / 'build-tools/36.0.0'
    android = sdk / 'platforms/android-35/android.jar'
    with zipfile.ZipFile(args.apk) as original:
        dependency_abis = sorted({name.split('/')[1] for name in original.namelist()
                                  if name.startswith('lib/') and name.endswith('.so')})
    if args.abi != 'universal': dependency_abis = [args.abi]
    offline_zstd = prepare_offline_zstd(args.out, abis=tuple(dependency_abis))
    unit_classes = args.out / 'test-classes'; unit_classes.mkdir()
    run(['python3', ROOT/'tools/yandex_hud_build/stock_camera_defaults.py', '--check'])
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
         *product_adas_sources(),
         camera_tests/'CameraTest.java', camera_tests/'StockCameraTest.java',
         package/'HudWire.java', package/'ModuleFields.java', package/'Reflect.java', package/'NavigatorSource.java',
         package/'StockHudSymbols.java', package/'RouteSnapshot.java', package/'GuidanceSnapshot.java', package/'TrafficSignalSnapshot.java',
         package/'HudAr.java', package/'HudArSource.java', package/'HudEvents.java', package/'ManeuverHold.java',
         package/'YandexVectorPrefetch.java',
         package/'YandexRouteVectorSource.java',
         package/'YandexOfflineInventory.java',
         package/'ObservedVectorRouteAttributes.java',
         package/'EvidencePolicy.java', package/'DriveEvidenceLog.java', package/'DriveEvidenceStreams.java', package/'AdasStateJournal.java',
         # The ADAS status overlay's plain-Java part: wire, observations, rules, figures.
         *[package/(name + '.java') for name in ('AdasWire', 'AdasFeed', 'AdasObservations', 'AdasOverlayState', 'AdasSvgPath', 'AdasDigits', 'CallbackQueue')],
         *[p for p in sorted((ROOT/'experiments/yandex-hud-module/src/test/java/dev/denza/hud/probe').glob('*.java'))
           if p.name != 'YandexOfflineMapSourceHashTest.java']])
    # Factory sender and the independent Yandex input policy own ADAS acceptance.
    # Historical StockFeed/variant goldens are intentionally not targets for this path.
    adas = ROOT / 'libraries/adas-link/src/main/java'
    run([ROOT/'gradlew', ':adas-link:test'], cwd=ROOT, env=env)
    run(['python3', ROOT/'tools/adas_bridge/runtime_harness/run.py', '--out', args.out/'live-runtime-harness'])
    run(['python3', ROOT/'tools/adas_bridge/passive_client_harness/run.py', '--out', args.out/'passive-client-harness'])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hud.probe.ModuleTest'])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hud.probe.ArTest'])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hud.probe.RouteSnapshotTest'])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hud.probe.YandexVectorPrefetchTest'])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hud.probe.YandexRouteVectorSourceTest'])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hud.probe.YandexOfflineInventoryTest'])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hud.probe.ObservedVectorRouteAttributesTest'])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hud.probe.GuidanceSnapshotTest'])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hud.probe.TrafficSignalSnapshotTest'])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hud.probe.EvidencePolicyTest'])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hud.probe.DriveEvidenceLogTest'])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hud.probe.DriveEvidenceStreamsTest'])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hud.probe.CallbackQueueTest'])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hud.probe.DriveJournalTest'])
    run([java/'bin/java', '-cp', unit_classes, 'dev.denza.hud.probe.AdasOverlayTest',
         ROOT/'experiments/adas-status-probe/src/test/fixtures/native-status-car-20261023.txt',
         ROOT/'tools/design-canvas/luminofor/spec.json'])
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
    # Only signatures for the SDK's real JNI callback. Never package these classes: the
    # original Navigator DEX owns the interface and its classloader identity.
    sdk_stubs = args.out / 'sdk-compile-only'; sdk_stubs.mkdir()
    stub_sources = sorted((ROOT/'tools/yandex_hud_build/compile_only').rglob('*.java'))
    run([java/'bin/javac', '--release', '8', '-encoding', 'UTF-8', '-classpath', android,
         '-d', sdk_stubs, *stub_sources])
    stub_jar = args.out/'sdk-compile-only.jar'
    run([java/'bin/jar', 'cf', stub_jar, '-C', sdk_stubs, '.'])
    sources = sorted((ROOT / 'experiments/yandex-hud-module/src/main/java').rglob('*.java')) + sorted(link.rglob('*.java')) + product_adas_sources()
    source_hashes = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
    run([java/'bin/javac', '--release', '8', '-encoding', 'UTF-8',
         '-classpath', os.pathsep.join(map(str, (android, stub_jar, offline_zstd['classes_jar']))), '-d', classes, *sources])
    run([java/'bin/javac', '--release', '8', '-encoding', 'UTF-8',
         '-classpath', os.pathsep.join(map(str, (classes, android))), '-d', unit_classes,
         ROOT/'experiments/yandex-hud-module/src/test/java/dev/denza/hud/probe/YandexOfflineMapSourceHashTest.java'])
    run([java/'bin/java', '-cp', os.pathsep.join(map(str, (classes, unit_classes, android))),
         'dev.denza.hud.probe.YandexOfflineMapSourceHashTest'])
    run([java/'bin/java', '-cp', os.pathsep.join(map(str, (classes, unit_classes, android))),
         'dev.denza.hud.probe.RotatingWriterTest'])
    run([java/'bin/jar', 'cf', args.out/'module.jar', '-C', classes, '.'])
    with zipfile.ZipFile(args.out/'module.jar') as module:
        verify_product_classes(module.namelist())
        if any(name.startswith('com/yandex/') for name in module.namelist()):
            raise RuntimeError('SDK compile-only classes leaked into module.jar')
    dex = args.out/'module-dex'; dex.mkdir()
    run([build_tools/'d8', '--min-api', '26', '--lib', android, '--classpath', stub_jar,
         '--output', dex, args.out/'module.jar', offline_zstd['classes_jar']], env=env)
    if {p.name for p in dex.glob('*.dex')} != {'classes.dex'}:
        raise RuntimeError('Module DEX count changed; packaging must account for every DEX')
    patcher = args.out/'patcher'; patcher.mkdir()
    run([java/'bin/javac', '-cp', args.jadx_jar, '-d', patcher, ROOT/'tools/yandex_hud_build/PatchHooks.java'])
    native_graph = build_native_graph(args.apk, args.out)
    native_vector = build_native_vector(args.apk, args.out)
    changes = dict(artwork)
    changes[native_graph["apk_entry"]] = Path(native_graph["library"]).read_bytes()
    changes[native_vector["apk_entry"]] = Path(native_vector["library"]).read_bytes()
    for entry in offline_zstd['native_entries'] + offline_zstd['asset_entries']:
        changes[entry['apk_entry']] = Path(entry['path']).read_bytes()
    with zipfile.ZipFile(args.apk) as original:
        excluded = excluded_native_entries(original, args.abi)
        removed_native_bytes = sum(original.getinfo(name).compress_size for name in excluded)
        for name, mode in [('classes10.dex', 'context'), ('classes12.dex', 'guidance')]:
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
        if public_roads is not None:
            changes[public_roads_report['asset']] = public_roads
        if owner_lanes is not None:
            changes[owner_lanes_report['asset']] = owner_lanes
        changes['assets/denza-hud/theme.json'] = subprocess.check_output(['node', ROOT/'tools/yandex_hud_build/theme.cjs', args.renderer_profile], cwd=ROOT)
        # The ADAS overlay sets its words in the board's own Roboto (Apache 2.0, licence alongside),
        # not the system's: the glass and tools/design-canvas/adas-overlay then measure alike.
        for name in ('Roboto-400.ttf', 'Roboto-500.ttf', 'Roboto-LICENSE.txt'):
            changes['assets/denza-hud/fonts/' + name] = (ROOT/'tools/design-canvas/luminofor/fonts'/name).read_bytes()
        with zipfile.ZipFile(args.out/'unsigned.apk', 'w', allowZip64=False) as output:
            for info in original.infolist():
                if info.filename in excluded:
                    continue
                if info.filename.startswith('META-INF/') and (info.filename.endswith(('.SF','.RSA','.DSA','.EC')) or info.filename == 'META-INF/MANIFEST.MF'):
                    continue
                output.writestr(info, changes.pop(info.filename, original.read(info.filename)))
            for name, content in changes.items():
                output.writestr(name, content, compress_type=zipfile.ZIP_STORED if name.endswith('.so') else zipfile.ZIP_DEFLATED)
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
    old_value = 'resource 0x7f1515b5 string/passport_account_type\n      () "' + ORIGINAL_ACCOUNT_TYPE + '"'
    new_value = old_value.replace(ORIGINAL_ACCOUNT_TYPE, ACCOUNT_TYPE)
    if before_resources.count(old_value) != 1 or after_resources != before_resources.replace(old_value, new_value):
        raise RuntimeError('Unexpected resource change beyond passport_account_type')
    if source_hashes != {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}:
        raise RuntimeError('Module sources changed during build; rebuild from a stable snapshot')
    report = {'input_sha256': PIN, 'apk': str(result), 'sha256': hashlib.sha256(result.read_bytes()).hexdigest(),
              'extra_dex': extra, 'module_source_sha256': source_hashes,
              'package': 'ru.yandex.yandexnavi', 'label': LABEL, 'build_number': BUILD_NUMBER,
              'version': VERSION_NAME, 'version_code': VERSION_CODE, 'debuggable': True,
              'runtime': 'embedded Java and ARM64 graph/vector observers, no Frida or root', 'manifest_semantic_check': 'passed'}
    report['public_road_evidence'] = public_roads_report
    report['owner_lane_evidence'] = owner_lanes_report
    report['native_graph'] = native_graph['report']
    report['native_vector'] = native_vector['report']
    report['offline_zstd'] = offline_zstd['report']
    report['sdk_compile_only_sha256'] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                                         for p in stub_sources}
    report['removed_permission_declarations'] = [SHARED_PERMISSION]
    report['account_type'] = ACCOUNT_TYPE
    report['abi'] = args.abi
    report['renderer_profile'] = args.renderer_profile
    report['apk_bytes'] = result.stat().st_size
    report['removed_native_entries'] = sorted(excluded)
    report['removed_native_compressed_bytes'] = removed_native_bytes
    report['stock_artwork'] = artwork_report
    report['resource_semantic_check'] = 'passed: only passport_account_type changed'
    # Prove untouched ZIP contents, not just a successful signature.
    with zipfile.ZipFile(args.apk) as before, zipfile.ZipFile(result) as after:
        if owner_lanes is not None and after.read(owner_lanes_report['asset']) != owner_lanes:
            raise RuntimeError('Owner lane evidence changed while packaging')
        if public_roads is not None and after.read(public_roads_report['asset']) != public_roads:
            raise RuntimeError('Public-road evidence changed while packaging')
        allowed = {'classes10.dex','classes12.dex','AndroidManifest.xml','resources.arsc'}
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
        module_dex = after.read(extra)
        forbidden = (b'Ldev/denza/adaslink/RoadStore;', b'denza-adas-roads.bin')
        if any(value in module_dex for value in forbidden):
            raise RuntimeError('OSM loader leaked into the application DEX')
        if any('denza-adas-roads' in name for name in after.namelist()):
            raise RuntimeError('OSM store leaked into APK entries')
        if after.read(native_graph['apk_entry']) != Path(native_graph['library']).read_bytes():
            raise RuntimeError('Native collector changed while packaging')
        if after.read(native_vector['apk_entry']) != Path(native_vector['library']).read_bytes():
            raise RuntimeError('Native vector collector changed while packaging')
        for entry in offline_zstd['native_entries'] + offline_zstd['asset_entries']:
            if hashlib.sha256(after.read(entry['apk_entry'])).hexdigest() != entry['sha256']:
                raise RuntimeError('Offline Zstd dependency changed while packaging')
        report['osm_in_apk'] = False
        report['osm_absence_check'] = 'no RoadStore class, database path, or packaged store'
        for name, content in artwork.items():
            if after.read(name) != content: raise RuntimeError('Stock asset changed: ' + name)
    (args.out/'build-report.json').write_text(json.dumps(report, indent=2)+'\n')
    result.with_suffix('.apk.sha256').write_text(report['sha256'] + '  ' + result.name + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
