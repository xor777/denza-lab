#!/usr/bin/env python3
"""Compile Java sources and exercise lifecycle faults locally; never builds an APK or opens ADB."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
TESTS = ['CloudRuntimeSupervisorTest', 'CloudRuntimeBoundaryTest', 'CloudRuntimeProtocolTest',
         'CloudNativePipeTest', 'CloudSessionLoopTest', 'CloudPrimitiveBridgeTest',
         'CloudReadOnlyFilesTest',
         'CloudNativeClockTest', 'CloudDnsResolverTest', 'CloudPowerGuardTest',
         'CloudSecondaryTransportTest',
         'CloudAwakeProtocolTest',
         'CloudGuardianStateTest', 'CloudGuardianLeaseTest', 'CloudGuardianAcceptTest',
         'CloudProtocol2FixtureTest', 'CloudProtocol3FixtureTest']


def establish_cases(fixture):
    """One independent SDK/property perturbation per production establish run."""
    yield 'baseline', {}, {}
    properties = fixture['properties']
    for key, original in properties.items():
        if key.startswith(('persist.sys.', 'persist.gnss.')) and key not in (
                'persist.sys.cloud.last_vin', 'persist.sys.byd.apn_type',
                'persist.sys.gpsinfo'):
            for value in ('', '0', '1'):
                if value != original:
                    yield f'{key}={value!r}', {key: value}, {}
    for index, value in enumerate(('', '0', 'abc',
            '11.25_22.5_1_1_11_111.5_.1_111.11_1_1',
            '-11.25_-22.5_1_1_11_111.5_.1_111.11_1_1', '_', '1__1')):
        yield f'gps_case_{index}', {'persist.sys.gpsinfo': value}, {}
    for size in (0, 1, 5, 19, 20, 64):
        yield f'sdk_version_length_{size}', {}, {
            '1027/99000402': {'status': 0, 'bytes': (b'1' * size).hex()}}
    yield 'sdk_version_error', {}, {'1027/99000402': {'status': -1}}


def startup_refused(properties):
    return (any(properties.get(key, '') not in ('', '0') for key in (
                'persist.sys.cloudtest', 'persist.sys.repair_mode.enable',
                'persist.sys.repair_mode_record')) or
            properties.get('persist.sys.cloud_enable') == '0' or
            not properties.get('persist.sys.energytype', '').lstrip('-').isdigit() or
            properties.get('apps.setting.product.outswver') != properties.get('persist.sys.version') or
            properties.get('mcu_version') != properties.get('persist.sys.mcu_version'))


def run_establish_matrix(java, classpath, out, binary, fixture_path, result_path,
                         source_hashes, replay_hashes, selected_cases):
    fixture = json.loads(fixture_path.read_text())
    first_path = fixture_path.with_name('first-capability-fixture.json')
    first = json.loads(first_path.read_text())
    first_ints = ('1009/47002011', '1009/47002012', '1023/2940002a',
                  '1023/4540000c', '1025/4f401038')
    if not all(key in first['integers'] for key in first_ints):
        raise SystemExit('first capability fixture lacks five explicit SDK getters')
    report = {
        'scope': 'production Java establish and original ARM64; synthetic SDK/DNS/TLS; no live qualification',
        'qualification': False,
        'binary_sha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
        'fixture_sha256': hashlib.sha256(fixture_path.read_bytes()).hexdigest(),
        'first_capability_fixture_sha256': hashlib.sha256(first_path.read_bytes()).hexdigest(),
        'sha256': source_hashes,
        'input_sha256': replay_hashes,
        'cases': [],
    }
    result_path.parent.mkdir(parents=True, exist_ok=True)
    fixture_file = Path(out) / 'establish-case.json'
    command = [str(java / 'java'), '-cp', classpath,
               'dev.denza.tools.runtime.CloudNativeEstablishIntegrationTest',
               sys.executable, str(HERE / 'native_test_process.py'), str(binary.resolve()),
               str(fixture_file)]
    cases = list(establish_cases(fixture))
    cases.extend((('first_capability_zero', {}, {}),
                  ('first_capability_absent', {}, {})))
    if selected_cases:
        missing = selected_cases - {name for name, _, _ in cases}
        if missing:
            raise SystemExit('unknown matrix cases: ' + ', '.join(sorted(missing)))
        cases = [case for case in cases if case[0] in selected_cases]
    for name, properties, buffers in cases:
        variant = copy.deepcopy(first if name.startswith('first_capability_') else fixture)
        variant['properties'].update(properties)
        variant['buffer_results'].update(buffers)
        if 'persist.sys.cloud_fid_uploaded' in properties:
            variant['integers'].update({key: first['integers'][key] for key in first_ints})
        if name == 'first_capability_absent':
            variant['properties'].pop('persist.sys.cloud_fid_uploaded')
            variant['absent_properties'] = ['persist.sys.cloud_fid_uploaded']
        fixture_file.write_text(json.dumps(variant))
        refused = startup_refused(variant['properties'])
        row = {'name': name, 'property_changes': properties, 'buffer_changes': buffers,
               'expected': 'unsupported_firmware_before_stock_pause' if refused else 'established'}
        try:
            run = subprocess.run(command + (['expect-startup-refusal'] if refused else []),
                                 timeout=90, text=True, capture_output=True)
            row.update(result='pass' if run.returncode == 0 else 'fail',
                       exit=run.returncode, stdout=run.stdout.strip(),
                       stderr=run.stderr.strip())
        except subprocess.TimeoutExpired as error:
            row.update(result='timeout', timeout_seconds=90,
                       stdout=(error.stdout or b'').decode(errors='replace') if isinstance(error.stdout, bytes) else error.stdout,
                       stderr=(error.stderr or b'').decode(errors='replace') if isinstance(error.stderr, bytes) else error.stderr)
        report['cases'].append(row)
        report['counts'] = {kind: sum(item['result'] == kind for item in report['cases'])
                            for kind in ('pass', 'fail', 'timeout')}
        report['passed'] = report['counts']['fail'] == report['counts']['timeout'] == 0
        result_path.write_text(json.dumps(report, indent=2) + '\n')
        print(f"{name}: {row['result']}", flush=True)
    return report['passed']

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--result', type=Path)
    parser.add_argument('--fixture-out', type=Path)
    parser.add_argument('--fixture3-out', type=Path)
    parser.add_argument('--native-binary', type=Path,
                        help='Run only real ARM64/Java boundary replay through the offline emulator')
    parser.add_argument('--integration-fixture', type=Path)
    parser.add_argument('--production-establish', action='store_true',
                        help='Exercise production establish with the real ARM64 fixture')
    parser.add_argument('--production-establish-matrix', action='store_true',
                        help='Run baseline and independent property/SDK variants through production establish')
    parser.add_argument('--matrix-case', action='append', default=[],
                        help='With --production-establish-matrix, run only this exact case name (repeatable)')
    args = parser.parse_args()
    if args.production_establish and args.production_establish_matrix:
        parser.error('choose one production establish mode')
    if (args.production_establish or args.production_establish_matrix) and not args.native_binary:
        parser.error('production establish requires a native binary and fixture')
    if args.production_establish_matrix and not args.result:
        parser.error('--production-establish-matrix requires --result')
    if args.matrix_case and not args.production_establish_matrix:
        parser.error('--matrix-case requires --production-establish-matrix')
    if bool(args.native_binary) != bool(args.integration_fixture):
        parser.error('--native-binary and --integration-fixture must be provided together')
    if args.native_binary:
        dependencies = subprocess.run([sys.executable, '-c', 'import unicorn, elftools'],
                                      capture_output=True, text=True)
        if dependencies.returncode:
            raise SystemExit('native replay needs unicorn and pyelftools in the selected Python environment')
    java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home')) / 'bin'
    android = Path(os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools')) / 'platforms/android-35/android.jar'
    json_jars = list((Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json/20250517').glob('*/*.jar'))
    if len(json_jars) != 1 or not android.is_file():
        raise SystemExit('local Android 35 and cached org.json 20250517 are required')
    sources = sorted(HERE.glob('*.java')) + [HERE.parent/'OncarTls.java']
    source_hashes = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
    replay_inputs = ([args.native_binary, args.integration_fixture, HERE / 'native_test_process.py',
                      ROOT / 'research/telematics-firmware/verify_persistent_engine.py']
                     if args.native_binary else [])
    replay_hashes = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in replay_inputs}
    with tempfile.TemporaryDirectory(prefix='denza-cloud-runtime-') as out:
        subprocess.run([str(java/'javac'), '-cp', f'{android}:{json_jars[0]}', '-d', out, *map(str, sources)], check=True, timeout=60)
        classpath = f'{out}:{json_jars[0]}:{android}'
        if args.production_establish_matrix:
            passed = run_establish_matrix(java, classpath, out, args.native_binary,
                                          args.integration_fixture, args.result,
                                          source_hashes, replay_hashes, set(args.matrix_case))
            if source_hashes != {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}:
                raise SystemExit('Java sources changed during verification; rerun on a stable snapshot')
            if replay_hashes != {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in replay_inputs}:
                raise SystemExit('Native replay inputs changed during verification; rerun on a stable snapshot')
            if not passed:
                raise SystemExit('production establish matrix has failing cases; see --result')
            return
        results = {}
        tests = [('CloudNativeEstablishIntegrationTest' if args.production_establish else
                  'CloudNativeBridgeIntegrationTest')] if args.native_binary else TESTS
        for test in tests:
            extra = ([sys.executable, str(HERE / 'native_test_process.py'),
                      str(args.native_binary.resolve()), str(args.integration_fixture.resolve())]
                     if args.native_binary else [])
            run = subprocess.run([str(java/'java'), '-cp', f'{out}:{json_jars[0]}:{android}',
                                  f'dev.denza.tools.runtime.{test}', *extra], check=False,
                                 timeout=90 if extra else 45, text=True, capture_output=True)
            if run.returncode:
                print(run.stderr, end="")
                raise SystemExit(f"{test} failed")
            results[test] = run.stdout.strip()
            print(run.stdout.strip())
        if args.fixture_out:
            args.fixture_out.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run([str(java/'java'), '-cp', f'{out}:{json_jars[0]}:{android}',
                            'dev.denza.tools.runtime.CloudProtocol2FixtureTest',
                            str(args.fixture_out)], check=True, timeout=45, capture_output=True, text=True)
        if args.fixture3_out:
            args.fixture3_out.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run([str(java/'java'), '-cp', f'{out}:{json_jars[0]}:{android}',
                            'dev.denza.tools.runtime.CloudProtocol3FixtureTest',
                            str(args.fixture3_out)], check=True, timeout=45, capture_output=True, text=True)
    if source_hashes != {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}:
        raise SystemExit('Java sources changed during verification; rerun on a stable snapshot')
    if replay_hashes != {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in replay_inputs}:
        raise SystemExit('Native replay inputs changed during verification; rerun on a stable snapshot')
    evidence = {'passed': True, 'scope': 'host injected boundaries; Android API compilation; no car/cloud/APK',
                'tests': results, 'sha256': source_hashes}
    if args.native_binary:
        evidence['native_replay'] = {
            'qualification': False,
            'binary_sha256': hashlib.sha256(args.native_binary.read_bytes()).hexdigest(),
            'fixture_sha256': hashlib.sha256(args.integration_fixture.read_bytes()).hexdigest(),
            'emulator_sha256': hashlib.sha256((HERE / 'native_test_process.py').read_bytes()).hexdigest(),
            'input_sha256': replay_hashes,
        }
    if args.result:
        args.result.parent.mkdir(parents=True, exist_ok=True)
        args.result.write_text(json.dumps(evidence, indent=2)+'\n')

if __name__ == '__main__':
    main()
