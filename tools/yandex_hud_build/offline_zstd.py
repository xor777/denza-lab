#!/usr/bin/env python3
"""Prepare the official pinned Android zstd-jni dependency, without compiling anything.

Contract for the parent APK builder:
  dep = prepare(out, abis=('arm64-v8a',))
  dep['classes_jar'] goes BOTH on javac's classpath and into d8's program inputs.
  Merge native_entries and asset_entries using their full apk_entry names.
  Keep com.github.luben.zstd.** names/members unchanged if a shrinker is introduced.

Only public Maven/upstream artifacts are inputs. No Navigator APK, private capture,
SDK address, local toolchain, compiler, dexer or Android device is used here.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import struct
import tempfile
import urllib.request
import zipfile

VERSION = '1.5.7-12'
COORDINATES = 'com.github.luben:zstd-jni:' + VERSION + '@aar'
LIBRARY = 'libzstd-jni-' + VERSION + '.so'
MAVEN = 'https://repo.maven.apache.org/maven2/com/github/luben/zstd-jni/' + VERSION + '/'
UPSTREAM = 'https://raw.githubusercontent.com/luben/zstd-jni/v' + VERSION + '/'
ARTIFACTS = {
    'aar': ('zstd-jni-' + VERSION + '.aar', MAVEN + 'zstd-jni-' + VERSION + '.aar',
            'd941f350817e48af4cc72f9f473a3ce400727e3e93c8d85a3f623d2907d99bf2', 8 * 1024 * 1024),
    'sources': ('zstd-jni-' + VERSION + '-sources.jar', MAVEN + 'zstd-jni-' + VERSION + '-sources.jar',
                '3cbb19809552b151146935ff6dc71e877ed4f02f0b994952dd935fa7fa542bd4', 1024 * 1024),
    'pom': ('zstd-jni-' + VERSION + '.pom', MAVEN + 'zstd-jni-' + VERSION + '.pom',
            '92f00184b29608664f7b4d291146d6cde9cc6d6837e7c9c53e38395e44ccb6aa', 65536),
    'jni_license': ('LICENSE.zstd-jni', UPSTREAM + 'LICENSE',
                    '289a6adada5726047b9e68bf607cc44fbbac953bbb4fd29f53ffe75b5cb3e7b1', 65536),
    'native_license': ('LICENSE.zstd-jni-native', UPSTREAM + 'src/main/native/LICENSE',
                       '2c1a7fa704df8f3a606f6fc010b8b5aaebf403f3aeec339a12048f1ba7331a0b', 65536),
}
# Derived members are pinned in addition to the complete official AAR.
ABIS = {
    'arm64-v8a': (183, '62882cee334f92a3c849c5e853ad7b9b7ba83da0a9355a6382ed3fdd0367b376'),
    'armeabi-v7a': (40, '7d539c5922d014484827fe1ff346ae167ef8fede7cca11482de2c2e4bffe7092'),
    'x86': (3, '0e8efd8a5dfccae15df9621f43d9a79e6431e3c8f4df6c01cea6a0ef511995d7'),
    'x86_64': (62, '0f0a4380fea6245de99f1a073a54850f193a813a9db5f0e5587691b6e8f49576'),
}


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _obtain(cache, record):
    name, url, expected, maximum = record
    target = cache / name
    if target.exists():
        if target.stat().st_size > maximum:
            raise ValueError('Cached dependency exceeds byte budget: ' + name)
        raw = target.read_bytes()
    else:
        with urllib.request.urlopen(url, timeout=30) as response:
            raw = response.read(maximum + 1)
        if len(raw) > maximum:
            raise ValueError('Dependency download exceeds byte budget: ' + name)
        if _sha(raw) != expected:
            raise ValueError('Dependency download SHA-256 mismatch: ' + name)
        with tempfile.NamedTemporaryFile(dir=cache, prefix=name + '.', delete=False) as temporary:
            pending = Path(temporary.name)
            try:
                temporary.write(raw)
                temporary.flush()
                os.replace(pending, target)
            finally:
                if pending.exists():
                    pending.unlink()
    if _sha(raw) != expected:
        raise ValueError('Cached dependency SHA-256 mismatch: ' + name)
    return target, raw


def _member(archive, name, maximum):
    info = archive.getinfo(name)
    if info.flag_bits & 1 or info.file_size < 0 or info.file_size > maximum:
        raise ValueError('AAR member budget/encryption: ' + name)
    raw = archive.read(info)
    if len(raw) != info.file_size:
        raise ValueError('AAR member length changed: ' + name)
    return raw


def _elf(raw, abi):
    if len(raw) < 64 or raw[:4] != b'\x7fELF' or raw[5] != 1 or raw[4] not in (1, 2):
        raise ValueError('Expected little-endian Android ELF: ' + abi)
    machine, expected_hash = ABIS[abi]
    if struct.unpack_from('<H', raw, 16)[0] != 3 or struct.unpack_from('<H', raw, 18)[0] != machine or _sha(raw) != expected_hash:
        raise ValueError('Android library machine/SHA mismatch: ' + abi)
    wide = raw[4] == 2
    offset = struct.unpack_from('<Q' if wide else '<I', raw, 32 if wide else 28)[0]
    size, count = struct.unpack_from('<HH', raw, 54 if wide else 42)
    minimum = 56 if wide else 32
    if count > 128 or size < minimum or offset + size * count > len(raw):
        raise ValueError('ELF program-header bounds: ' + abi)
    loads, dynamic = [], None
    for i in range(count):
        row = struct.unpack_from('<IIQQQQQQ' if wide else '<IIIIIIII', raw, offset + i * size)
        kind = row[0]
        flags, file_at, address, file_bytes, align = (row[1], row[2], row[3], row[5], row[7]) if wide else (row[6], row[1], row[2], row[4], row[7])
        if file_at + file_bytes > len(raw):
            raise ValueError('ELF segment file bounds: ' + abi)
        if kind == 1:
            if align < 16384 or flags & 3 == 3:
                raise ValueError('ELF alignment/writable-executable segment: ' + abi)
            loads.append((file_at, address, file_bytes, align))
        if kind == 2:
            dynamic = (file_at, file_bytes)
    if not loads or dynamic is None:
        raise ValueError('ELF load/dynamic segments missing: ' + abi)
    stride = 16 if wide else 8
    tags = []
    for i in range(dynamic[0], dynamic[0] + dynamic[1] - stride + 1, stride):
        tag, value = struct.unpack_from('<QQ' if wide else '<II', raw, i)
        if tag == 0:
            break
        tags.append((tag, value))
    strings = next((v for t, v in tags if t == 5), None)
    string_bytes = next((v for t, v in tags if t == 10), None)
    string_offset = next((f + strings - a for f, a, n, _ in loads if strings is not None and a <= strings < a + n), None)
    if string_offset is None or string_bytes is None or string_offset + string_bytes > len(raw):
        raise ValueError('ELF string table bounds: ' + abi)
    needed = []
    for tag, value in tags:
        if tag != 1:
            continue
        if value >= string_bytes:
            raise ValueError('ELF dependency name offset: ' + abi)
        end = raw.find(b'\0', string_offset + value, min(string_offset + string_bytes, string_offset + value + 256))
        if end < 0:
            raise ValueError('ELF dependency name length: ' + abi)
        needed.append(raw[string_offset + value:end].decode('ascii'))
    if not set(needed) <= {'libc.so', 'libdl.so', 'libm.so', 'liblog.so'}:
        raise ValueError('Unexpected Android native dependencies: ' + repr(needed))
    return {'machine': machine, 'load_alignment': [row[3] for row in loads], 'needed': needed}


def prepare(out, abis=('arm64-v8a',), cache_dir=None):
    """Download/hash/extract only. Returns classes_jar, native_entries, asset_entries, report."""
    selected = tuple(sorted(set(abis)))
    if not selected or any(abi not in ABIS for abi in selected):
        raise ValueError('Unsupported offline zstd ABI selection')
    cache = Path(cache_dir) if cache_dir is not None else Path.home() / '.cache' / 'denza-lab' / 'offline-zstd' / VERSION
    cache.mkdir(parents=True, exist_ok=True)
    acquired = {key: _obtain(cache, value) for key, value in ARTIFACTS.items()}
    target = Path(out).resolve() / 'offline-zstd'
    target.mkdir(parents=True, exist_ok=False)
    classes = target / 'classes.jar'
    native_entries, asset_entries, native_metadata = [], [], {}
    with zipfile.ZipFile(io.BytesIO(acquired['aar'][1])) as archive:
        raw_classes = _member(archive, 'classes.jar', 2 * 1024 * 1024)
        with zipfile.ZipFile(io.BytesIO(raw_classes)) as jar:
            names = set(jar.namelist())
            required = {'com/github/luben/zstd/ZstdDecompressCtx.class', 'com/github/luben/zstd/ZstdException.class',
                        'com/github/luben/zstd/util/Native.class'}
            if not required <= names:
                raise ValueError('Required official decoder classes missing')
            majors = set()
            for name in names:
                if name.endswith('.class'):
                    raw = _member(jar, name, 1024 * 1024)
                    if len(raw) < 8 or raw[:4] != b'\xca\xfe\xba\xbe':
                        raise ValueError('Invalid dependency class: ' + name)
                    majors.add(struct.unpack_from('>H', raw, 6)[0])
            if not majors or max(majors) > 52:
                raise ValueError('Dependency requires a newer Java bytecode level')
        classes.write_bytes(raw_classes)
        for abi in selected:
            data = _member(archive, 'jni/' + abi + '/' + LIBRARY, 4 * 1024 * 1024)
            native_metadata[abi] = _elf(data, abi)
            library = target / 'jni' / abi / LIBRARY
            library.parent.mkdir(parents=True, exist_ok=True)
            library.write_bytes(data)
            native_entries.append({'path': str(library), 'apk_entry': 'lib/' + abi + '/' + LIBRARY,
                                   'sha256': _sha(data), 'bytes': len(data)})
    licenses = target / 'licenses'
    licenses.mkdir()
    for key, name in [('jni_license', 'LICENSE.zstd-jni'), ('native_license', 'LICENSE.zstd-native')]:
        path = licenses / name
        path.write_bytes(acquired[key][1])
        asset_entries.append({'path': str(path), 'apk_entry': 'assets/licenses/offline-zstd/' + name,
                              'sha256': _sha(acquired[key][1]), 'bytes': path.stat().st_size})
    notice = licenses / 'NOTICE.txt'
    notice.write_text('Offline vector tile decoding uses official ' + COORDINATES + '.\n'
                      'Upstream: https://github.com/luben/zstd-jni/tree/v' + VERSION + '\n'
                      'JNI bindings: BSD-2-Clause (LICENSE.zstd-jni).\n'
                      'Zstandard native code: BSD-3-Clause option (LICENSE.zstd-native).\n'
                      'AAR SHA-256: ' + ARTIFACTS['aar'][2] + '\n'
                      'No Navigator native code is used by this decoder.\n', encoding='utf-8')
    asset_entries.append({'path': str(notice), 'apk_entry': 'assets/licenses/offline-zstd/NOTICE.txt',
                          'sha256': _sha(notice.read_bytes()), 'bytes': notice.stat().st_size})
    report = {'schema': 'denza-offline-zstd-dependency-v1', 'coordinates': COORDINATES,
              'source_apk_dependency': False, 'compilation_or_execution_performed': False,
              'artifacts': {key: {'url': ARTIFACTS[key][1], 'sha256': _sha(data), 'bytes': len(data)}
                            for key, (_, data) in acquired.items()},
              'classes_jar_sha256': _sha(raw_classes), 'class_major_versions': sorted(majors),
              'native': native_metadata, 'native_entries': native_entries, 'asset_entries': asset_entries,
              'keep_rule': '-keep class com.github.luben.zstd.** { *; }',
              'integration': {'javac_classpath': str(classes), 'd8_program_input': str(classes),
                              'aar_manifest_merge_required': False, 'jni_names_must_not_change': True,
                              'native_entry_storage': 'Follow parent APK native extraction/alignment policy; use full lib/<abi>/ names'}}
    (target / 'dependency-report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return {'classes_jar': str(classes), 'native_entries': native_entries, 'asset_entries': asset_entries, 'report': report}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--abi', choices=tuple(ABIS), action='append', help='Defaults to arm64-v8a; repeat for universal APK')
    parser.add_argument('--cache', type=Path)
    args = parser.parse_args()
    print(json.dumps(prepare(args.out, args.abi or ('arm64-v8a',), args.cache), indent=2))
