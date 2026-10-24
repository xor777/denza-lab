#!/usr/bin/env python3
"""Build the exact Navigator graph observer with existing local tools; no device I/O."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE_SHA256 = 'c2842633cc3db8301f252aff52df86aab4c64a8b10b826f80f4f922a327d340f'
SOURCE_ENTRY = 'lib/arm64-v8a/libmaps-mobile.so'
APK_ENTRY = 'lib/arm64-v8a/libdenza_yandex_graph.so'
DECODER_OFFSET = 0x298bba0
PREFIX = bytes.fromhex('ff8307d1fd7b18a9fc6f19a9fa671aa9')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def elf_layout(path):
    """Small ELF64-LE reader for build checks; no optional host Python packages."""
    data = path.read_bytes()
    if data[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', data, 18)[0] != 183:
        raise ValueError('Expected little-endian AArch64 ELF: ' + str(path))
    phoff, shoff = struct.unpack_from('<QQ', data, 32)
    phentsize, phnum, shentsize, shnum, shstrndx = struct.unpack_from('<HHHHH', data, 54)
    sections = [struct.unpack_from('<IIQQQQIIQQ', data, shoff + i * shentsize) for i in range(shnum)]
    names_section = sections[shstrndx]
    strings = data[names_section[4]:names_section[4] + names_section[5]]

    def string(table, offset):
        end = table.index(b'\0', offset)
        return table[offset:end].decode()

    named = {string(strings, row[0]): row for row in sections}
    symbols = {}
    for section_name in ('.symtab', '.dynsym'):
        row = named.get(section_name)
        if row is None:
            continue
        table_section = sections[row[6]]
        table = data[table_section[4]:table_section[4] + table_section[5]]
        for offset in range(row[4], row[4] + row[5], row[9]):
            name, info, other, index, value, size = struct.unpack_from('<IBBHQQ', data, offset)
            if name:
                symbols[string(table, name)] = (value, size, other)
    loads = []
    for i in range(phnum):
        row = struct.unpack_from('<IIQQQQQQ', data, phoff + i * phentsize)
        if row[0] == 1:
            loads.append(row)

    def bytes_at(address, size):
        for row in loads:
            if row[3] <= address and address + size <= row[3] + row[5]:
                offset = row[2] + address - row[3]
                return data[offset:offset + size]
        raise ValueError('Unmapped ELF address: ' + hex(address))

    dynstr = named['.dynstr']
    table = data[dynstr[4]:dynstr[4] + dynstr[5]]
    dynamic = named['.dynamic']
    needed = []
    for offset in range(dynamic[4], dynamic[4] + dynamic[5], 16):
        tag, value = struct.unpack_from('<QQ', data, offset)
        if tag == 1:
            needed.append(string(table, value))
    return symbols, loads, needed, bytes_at


def check_jni_slots():
    java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
    header = java / 'include/jni.h'
    source = header.read_text()
    source = source[source.index('struct JNINativeInterface_ {'):].split('};', 1)[0]
    names = ['reserved0', 'reserved1', 'reserved2', 'reserved3'] + re.findall(r'\(JNICALL \*(\w+)\)', source)
    expected = {'NewByteArray': 176, 'NewLongArray': 180, 'SetByteArrayRegion': 208, 'SetLongArrayRegion': 212}
    for name, index in expected.items():
        if names.index(name) != index:
            raise ValueError('JNI ABI table slot mismatch: ' + name)
    return {'header_sha256': sha(header), 'slots': expected}


def build(apk: Path, out: Path) -> dict:
    """Return library/apk_entry/report; parent APK builder owns ZIP packaging/signing."""
    apk, out = Path(apk).resolve(), Path(out).resolve()
    with zipfile.ZipFile(apk) as archive:
        source = archive.read(SOURCE_ENTRY)
    if hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        raise ValueError('Graph collector only admits the reviewed Navigator native library')
    if source[DECODER_OFFSET:DECODER_OFFSET + 16] != PREFIX:
        raise ValueError('Pinned decoder instruction prefix mismatch')
    # The relay relocates exactly one PC-independent SUB SP instruction.
    if struct.unpack_from('<I', PREFIX)[0] != 0xd10783ff:
        raise ValueError('Unsupported original entry instruction')
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
    target = out / 'native-graph'
    target.mkdir(parents=True, exist_ok=False)
    cpp = ROOT / 'experiments/yandex-hud-module/src/main/cpp'
    c = cpp / 'yandex_graph_collector.c'
    assembly = cpp / 'yandex_graph_relay.S'
    library = target / 'libdenza_yandex_graph.so'
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
    exports = ['Java_dev_denza_hud_probe_NativeGraphCollector_' + name
               for name in ('nativeStart', 'nativePoll', 'nativeStats')]
    for name in exports:
        if name not in symbols or symbols[name][2] != 0:
            raise ValueError('Missing JNI export: ' + name)
    begin = symbols['denza_graph_relay_begin'][0]
    offsets = {name: symbols['denza_graph_relay_' + name][0] - begin
               for name in ('original', 'branch', 'callback', 'end')}
    if bytes_at(begin + offsets['original'], 4) != bytes.fromhex('1f2003d5'):
        raise ValueError('Relay original instruction slot changed')
    if bytes_at(begin + offsets['branch'], 4) != bytes.fromhex('00000014'):
        raise ValueError('Relay branch instruction slot changed')
    if bytes_at(begin + offsets['callback'], 8) != bytes(8):
        raise ValueError('Relay callback literal changed')
    if bytes_at(symbols['denza_graph_probe_target'][0], 4) != PREFIX[:4]:
        raise ValueError('Synthetic target must exercise the exact original stack instruction')
    if any(row[7] < 16384 or (row[1] & 3) == 3 for row in loads):
        raise ValueError('Unexpected PT_LOAD alignment or writable executable segment')
    report = {'schema': 'denza-native-graph-build-v1', 'host_only': True, 'device_operations': 0,
              'source_native_sha256': SOURCE_SHA256, 'source_decoder_offset': hex(DECODER_OFFSET),
              'source_decoder_prefix': PREFIX.hex(), 'library': str(library), 'apk_entry': APK_ENTRY,
              'sha256': sha(library), 'bytes': library.stat().st_size, 'needed': needed,
              'source_sha256': {str(p.relative_to(ROOT)): sha(p) for p in (c, assembly)},
              'link_stub_sha256': {name: sha(stubs / name) for name in ('libc.so', 'libdl.so')},
              'commands': commands, 'jni_abi_check': jni_check, 'jni_exports': exports,
              'relay_offsets': offsets, 'max_queue_bytes': 8 * 2 * 1024 * 1024,
              'entry_patch_bytes': 4, 'native_architecture': 'arm64-v8a',
              'runtime_synthetic_abi_test': 'required before each first successful native installation',
              'lifetime': 'hook and installed relay persist until process exit; no unhook API',
              'publisher': 'no SOME/IP or vehicle API dependency'}
    (target / 'build-report.json').write_text(json.dumps(report, indent=2) + '\n')
    return {'library': str(library), 'apk_entry': APK_ENTRY, 'report': report}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(build(args.apk, args.out), indent=2))
