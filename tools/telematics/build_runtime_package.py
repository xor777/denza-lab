#!/usr/bin/env python3
"""Build a reproducible offline cloud runtime candidate. Never installs or enables it.

Qualification is taken only from reviewed native build metadata, not a CLI switch.
An unqualified candidate is useful for offline verification but must not enter APK assets.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
RESEARCH = ROOT / 'research/telematics-firmware'
FULL_REQUIRED = ('native_registration_codec', 'opaque_data_ingest', 'control_532', 'wake_536',
            'mcu_state', 'native_posix_timers', 'native_sub5_timer', 'post_login', 'heartbeat',
            'stock_lifecycle_bridge')
PROFILE = 'awake-alpha-v1'
PROFILE_REQUIRED = ('native_registration_codec', 'opaque_data_ingest', 'control_awake',
                    'wake_ack_awake', 'timers_awake', 'post_login_awake', 'heartbeat')


def profile_qualification(native):
    """An explicit reviewed profile, never inferred by dropping full-runtime gates."""
    profiles = native.get('profiles', {})
    if not isinstance(profiles, dict):
        raise ValueError('invalid native profiles')
    profile = profiles.get(PROFILE, {})
    if not isinstance(profile, dict) or not isinstance(profile.get('capabilities', {}), dict):
        raise ValueError('invalid awake profile')
    capabilities = {key: profile.get('capabilities', {}).get(key) is True
                    for key in PROFILE_REQUIRED}
    qualified = (native.get('protocol') == 2 and profile.get('qualified') is True
                 and all(capabilities.values()))
    return qualified, capabilities


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_manifest():
    paths = sorted(p for p in (HERE / 'runtime').glob('*.java') if not p.name.endswith('Test.java'))
    paths += [HERE / 'OncarTls.java', Path(__file__).resolve()]
    paths += sorted(p for p in RESEARCH.iterdir() if p.suffix in ('.c', '.h', '.py'))
    return {str(p.relative_to(ROOT)): sha(p) for p in paths}


def canonical_jar(source, target):
    # D8 zip timestamps and traversal order must not change content-addressed installation paths.
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(target, 'w') as result:
        names = sorted(original.namelist())
        if not names or any(not name.endswith('.dex') or '/' in name for name in names):
            raise ValueError('unexpected dex archive contents')
        for name in names:
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_STORED
            entry.external_attr = 0o100600 << 16
            result.writestr(entry, original.read(name))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--firmware', type=Path, required=True)
    parser.add_argument('--linker', type=Path, required=True)
    parser.add_argument('--android-jar', type=Path, required=True)
    parser.add_argument('--d8', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    inputs = source_manifest()
    javac = Path(os.environ['JAVA_HOME']) / 'bin/javac' if 'JAVA_HOME' in os.environ else Path(shutil.which('javac') or '')
    if not javac.is_file():
        raise SystemExit('JAVA_HOME must point to JDK 17')
    clang = Path(shutil.which('clang') or '')
    d8_implementation = args.d8.resolve().parent / 'lib/d8.jar'
    if not clang.is_file() or not d8_implementation.is_file():
        raise SystemExit('clang and the Android build-tools lib/d8.jar are required')
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='denza-cloud-package-') as temporary:
        build = Path(temporary)
        env = dict(os.environ, PYTHONDONTWRITEBYTECODE='1')
        import sys
        subprocess.run([sys.executable, str(RESEARCH / 'build_persistent_runtime.py'),
                        str(args.firmware.resolve()), '--out', str(build / 'native'),
                        '--linker', str(args.linker.resolve())], check=True, env=env, timeout=90)
        native = json.loads((build / 'native/build.json').read_text())
        worker = build / 'native/persistent-engine'
        if native['binary_sha256'] != sha(worker):
            raise ValueError('native manifest mismatch')
        # Java and native protocol versions are changed as one source-reviewed contract.
        c_source = (RESEARCH / 'persistent_runtime.c').read_text()
        if 'CAPS PROTO2=1' not in c_source:
            raise ValueError('native clock protocol is not v2')
        classes = build / 'classes'
        classes.mkdir()
        sources = sorted(p for p in (HERE / 'runtime').glob('*.java') if not p.name.endswith('Test.java'))
        sources += [HERE / 'OncarTls.java']
        subprocess.run([str(javac), '--release', '17', '-g:none', '-encoding', 'UTF-8',
                        '-cp', str(args.android_jar.resolve()), '-d', str(classes),
                        *map(str, sources)], check=True, timeout=90)
        raw_jar = build / 'runtime.jar'
        subprocess.run([str(args.d8.resolve()), '--lib', str(args.android_jar.resolve()),
                        '--min-api', '33', '--release', '--output', str(raw_jar),
                        *map(str, sorted(classes.rglob('*.class')))], check=True, timeout=90)
        jar = build / 'cloud-native-proxy.jar'
        canonical_jar(raw_jar, jar)
        if inputs != source_manifest():
            raise ValueError('sources changed during build; retry after writers finish')
        caps = native['capabilities']
        qualified = native.get('product_qualified') is True and all(caps.get(key) is True for key in FULL_REQUIRED)
        profile_qualified, profile_caps = profile_qualification(native)
        manifest = {
            'protocol': 3, 'native_protocol': 2, 'product_qualified': qualified,
            'profile': PROFILE, 'profile_qualified': profile_qualified,
            'profile_capabilities': profile_caps,
            'runtime_id': sha(jar)[:12] + '-' + sha(worker)[:12],
            'firmware_sha256': native['firmware_sha256'], 'capabilities': caps,
            'files': {'cloud-native-proxy.jar': sha(jar), 'cloud-native-worker': sha(worker)},
            'sources_sha256': inputs,
            'toolchain': {'javac': subprocess.check_output([str(javac), '-version'], text=True).strip(),
                          'javac_sha256': sha(javac),
                          'clang_version': subprocess.check_output([str(clang), '--version'], text=True).strip(),
                          'clang_sha256': sha(clang),
                          'android_jar_sha256': sha(args.android_jar), 'd8_launcher_sha256': sha(args.d8),
                          'd8_implementation_sha256': sha(d8_implementation),
                          'linker_sha256': sha(args.linker)},
        }
        # Manifest is the last publication. Consumer validates both hashes before using the pair.
        for source, name in ((jar, 'cloud-native-proxy.jar'), (worker, 'cloud-native-worker')):
            staging = out / (name + '.pending')
            shutil.copyfile(source, staging)
            staging.chmod(0o600)
            staging.replace(out / name)
        pending = out / 'cloud-native-manifest.json.pending'
        pending.write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n')
        pending.replace(out / 'cloud-native-manifest.json')
        print(json.dumps({'runtime_id': manifest['runtime_id'], 'product_qualified': qualified,
                          'profile': PROFILE, 'profile_qualified': profile_qualified,
                          'output': str(out)}))


if __name__ == '__main__':
    main()
