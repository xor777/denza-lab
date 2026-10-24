#!/usr/bin/env python3
"""Build the pinned passive vmap3 collector; host-only, no device operations."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import zipfile

try:
    from .native_graph import elf_layout, check_jni_slots
except ImportError:
    from native_graph import elf_layout, check_jni_slots

ROOT = Path(__file__).resolve().parents[2]
SOURCE_SHA256 = 'c2842633cc3db8301f252aff52df86aab4c64a8b10b826f80f4f922a327d340f'
SOURCE_BUILD_ID = '929979daf6ef385413af0e7615e3319aa43464be'
SOURCE_ENTRY = 'lib/arm64-v8a/libmaps-mobile.so'
APK_ENTRY = 'lib/arm64-v8a/libdenza_yandex_vector.so'
HOOKS = (
    ('layer_entry', 0x17c1bdc, 'fd7bbaa9fc6f01a9fa6702a9f85f03a9'),
    ('wrapper_entry', 0x17bb6c8, 'ff8301d1fd7b04a9f44f05a9fd030191'),
    ('decoder_entry', 0x17bbaa0, 'ff4301d1fd7b02a9f65703a9f44f04a9'),
    ('layer_return', 0x17c25c0, 'c0035fd6'),
    ('wrapper_return', 0x17bb748, 'c0035fd6'),
    ('decoder_return', 0x17bbb30, 'c0035fd6'),
)
# These six specific PC-independent forms are the complete relocation allowlist.
ENTRY_WORDS = (0xa9ba7bfd, 0xd10183ff, 0xd10143ff, 0xd65f03c0, 0xd65f03c0, 0xd65f03c0)
HEADER_SIZE = 72


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def verify_source(source):
    """Refuse any source other than the byte-reviewed image before compilation."""
    if hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        raise ValueError('Vector collector only admits the reviewed Navigator native library')
    if bytes.fromhex(SOURCE_BUILD_ID) not in source[:65536]:
        raise ValueError('Pinned native Build-ID missing')
    for (_, address, prefix), word in zip(HOOKS, ENTRY_WORDS):
        expected = bytes.fromhex(prefix)
        if source[address:address + len(expected)] != expected:
            raise ValueError('Pinned vector hook prefix mismatch: ' + hex(address))
        if struct.unpack_from('<I', expected)[0] != word:
            raise ValueError('Unsupported relocated instruction: ' + hex(address))


def build(apk: Path, out: Path) -> dict:
    """Return library/apk_entry/report; parent builder owns packaging and signing."""
    apk, out = Path(apk).resolve(), Path(out).resolve()
    with zipfile.ZipFile(apk) as archive:
        source = archive.read(SOURCE_ENTRY)
    verify_source(source)
    clang = shutil.which('clang')
    command = shutil.which('ld.lld') or shutil.which('rust-lld')
    candidates = sorted((Path.home() / '.rustup/toolchains').glob('*/lib/rustlib/*/bin/rust-lld'))
    lld = Path(command) if command else (candidates[-1] if candidates else None)
    if not clang or not lld:
        raise ValueError('Existing clang and LLVM linker required; no toolchain is downloaded')
    stubs = ROOT / 'captures/stock-map-ans-local-20261021'
    for name in ('libc.so', 'libdl.so'):
        if not (stubs / name).is_file():
            raise ValueError('Existing Android link stub missing: ' + name)
    jni_check = check_jni_slots()
    target = out / 'native-vector'
    target.mkdir(parents=True, exist_ok=False)
    cpp = ROOT / 'experiments/yandex-hud-module/src/main/cpp'
    c, assembly = cpp / 'yandex_vector_collector.c', cpp / 'yandex_vector_relay.S'
    library = target / 'libdenza_yandex_vector.so'
    commands = [
        [clang, '--target=aarch64-linux-android26', '-std=c11', '-O2', '-fPIC', '-ffreestanding',
         '-fno-stack-protector', '-fvisibility=hidden', '-mno-outline-atomics', '-Wall', '-Wextra', '-Werror',
         '-c', str(c), '-o', str(target / 'collector.o')],
        [clang, '--target=aarch64-linux-android26', '-fPIC', '-c', str(assembly), '-o', str(target / 'relay.o')],
        [str(lld), '-flavor', 'gnu', '-shared', '--no-undefined', '-soname', library.name,
         '-z', 'now', '-z', 'relro', '-z', 'noexecstack', '-z', 'max-page-size=16384',
         '-o', str(library), str(target / 'collector.o'), str(target / 'relay.o'), '-L' + str(stubs), '-lc', '-ldl'],
    ]
    for args in commands:
        subprocess.run(args, check=True, timeout=60)
    symbols, loads, needed, bytes_at = elf_layout(library)
    if set(needed) != {'libc.so', 'libdl.so'}:
        raise ValueError('Unexpected native dependencies: ' + str(needed))
    exports = ['Java_dev_denza_hud_probe_NativeVectorCollector_' + name
               for name in ('nativeStart', 'nativePoll', 'nativeStats')]
    for name in exports:
        if name not in symbols or symbols[name][2] != 0:
            raise ValueError('Missing JNI export: ' + name)
    begin = symbols['denza_vector_relay_begin'][0]
    offsets = {name: symbols['denza_vector_relay_' + name][0] - begin
               for name in ('original', 'branch', 'callback', 'event', 'end')}
    for name, expected in (('original', bytes.fromhex('1f2003d5')),
                           ('branch', bytes.fromhex('00000014')), ('callback', bytes(8)), ('event', bytes(8))):
        if bytes_at(begin + offsets[name], len(expected)) != expected:
            raise ValueError('Relay instruction/literal slot changed: ' + name)
    for name, expected in (('target', ENTRY_WORDS[2]), ('wrapper', ENTRY_WORDS[1]),
                           ('layer', ENTRY_WORDS[0]), ('ret', ENTRY_WORDS[3])):
        actual = struct.unpack('<I', bytes_at(symbols['denza_vector_probe_' + name][0], 4))[0]
        if actual != expected:
            raise ValueError('Synthetic probe must exercise exact relocated instruction: ' + name)
    if any(row[7] < 16384 or (row[1] & 3) == 3 for row in loads):
        raise ValueError('Unexpected PT_LOAD alignment or writable executable segment')
    report = {
        'schema': 'denza-native-vector-build-v1', 'host_only': True, 'device_operations': 0,
        'source_native_sha256': SOURCE_SHA256, 'source_build_id': SOURCE_BUILD_ID,
        'source_hooks': [{'name': n, 'elf_va': hex(a), 'prefix': p} for n, a, p in HOOKS],
        'library': str(library), 'apk_entry': APK_ENTRY, 'sha256': sha(library),
        'bytes': library.stat().st_size, 'needed': needed, 'commands': commands,
        'source_sha256': {str(p.relative_to(ROOT)): sha(p) for p in (c, assembly)},
        'link_stub_sha256': {name: sha(stubs / name) for name in ('libc.so', 'libdl.so')},
        'jni_abi_check': jni_check, 'jni_exports': exports, 'relay_offsets': offsets,
        'queue_slots': 8, 'max_tile_bytes': 2097152, 'max_queue_bytes': 8 * (2097152 + HEADER_SIZE),
        'thread_contexts': 64, 'max_nested_frames': 24, 'entry_patch_bytes': 4,
        'native_architecture': 'arm64-v8a', 'envelope': 'YVT1 little-endian version1 header72',
        'runtime_synthetic_abi_test': 'four exact relocated instruction forms, required before install',
        'lifetime': 'hooks/visible relays persist to process exit; collection fails closed on nested overflow',
        'publisher': 'passive; no SOME/IP, vehicle API, active tile fetch, or region store',
    }
    (target / 'build-report.json').write_text(json.dumps(report, indent=2) + '\n')
    return {'library': str(library), 'apk_entry': APK_ENTRY, 'report': report}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(build(args.apk, args.out), indent=2))
