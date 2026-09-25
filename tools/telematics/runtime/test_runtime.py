#!/usr/bin/env python3
"""Compile Java sources and exercise lifecycle faults locally; never builds an APK or opens ADB."""
import argparse
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
         'CloudNativeClockTest', 'CloudDnsResolverTest', 'CloudPowerGuardTest',
         'CloudSecondaryTransportTest',
         'CloudAwakeProtocolTest',
         'CloudGuardianStateTest', 'CloudGuardianLeaseTest',
         'CloudProtocol2FixtureTest', 'CloudProtocol3FixtureTest']

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--result', type=Path)
    parser.add_argument('--fixture-out', type=Path)
    parser.add_argument('--fixture3-out', type=Path)
    parser.add_argument('--native-binary', type=Path,
                        help='Run only real ARM64/Java boundary replay through the offline emulator')
    parser.add_argument('--integration-fixture', type=Path)
    args = parser.parse_args()
    if bool(args.native_binary) != bool(args.integration_fixture):
        parser.error('--native-binary and --integration-fixture must be provided together')
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
        results = {}
        tests = ['CloudNativeBridgeIntegrationTest'] if args.native_binary else TESTS
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
