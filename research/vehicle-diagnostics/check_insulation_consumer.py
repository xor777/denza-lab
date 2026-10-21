#!/usr/bin/env python3
"""Check the pinned stock insulation consumer offline, using only Python stdlib.

Default binary: the existing owner-OTA gbacqservice in this repository's corpus.
Use --binary PATH for the same bytes elsewhere. No firmware execution, device,
network, downloads, imports from capture directories or output-file writes.
Readable assembly labels are preserved in the companion JSON; this script
checks their exact instruction bytes, not a newly decoded disassembly.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = Path(__file__).with_name('insulation-consumer-20261021.json')


def executable_segments(data):
    if len(data) < 64 or data[:7] != b'\x7fELF\x02\x01\x01':
        raise ValueError('Expected ELF64 little-endian version1')
    if struct.unpack_from('<H', data, 18)[0] != 183:
        raise ValueError('Expected AArch64 ELF')
    offset = struct.unpack_from('<Q', data, 32)[0]
    stride, count = struct.unpack_from('<HH', data, 54)
    if stride < 56 or count == 0 or offset + stride * count > len(data):
        raise ValueError('Invalid ELF program-header table')
    segments = []
    for i in range(count):
        kind, flags, file_offset, address, _, file_size, memory_size, _ = struct.unpack_from(
            '<IIQQQQQQ', data, offset + stride * i)
        if kind != 1 or not flags & 1:  # PT_LOAD, PF_X
            continue
        if file_size > memory_size or file_offset + file_size > len(data):
            raise ValueError('Invalid executable segment extent')
        segments.append((address, file_offset, file_size))
    if not segments:
        raise ValueError('No executable load segment')
    return segments


def bytes_at(data, segments, address, length):
    for base, offset, size in segments:
        if base <= address and address + length <= base + size:
            start = offset + address - base
            return data[start:start + length]
    raise ValueError(f'Anchor outside file-backed executable segment: {address:#x}')


def check(binary, evidence):
    data = binary.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    source = evidence['source']
    if len(data) != source['bytes'] or digest != source['sha256']:
        raise ValueError('Source hash/size mismatch; this evidence applies only to the pinned binary')
    segments = executable_segments(data)
    anchors = evidence['instructionAnchors']
    if len(anchors) != 28 or len({row[0] for row in anchors}) != 28:
        raise ValueError('Expected 28 unique instruction anchors')
    for address_hex, raw_hex, _ in anchors:
        address = int(address_hex, 16)
        raw = bytes.fromhex(raw_hex)
        if address % 4 or len(raw) != 4:
            raise ValueError(f'Invalid instruction anchor: {address_hex}')
        if bytes_at(data, segments, address, 4) != raw:
            raise ValueError(f'Instruction-byte mismatch: {address_hex}')
    # Exhaustive Python arithmetic check of the separately traced CMP/CSEL/STRH
    # branch. This neither emulates nor executes the firmware.
    for raw16 in range(65536):
        branch = (0xFFFFEA60 if 60000 < raw16 else raw16) & 0xFFFF
        if branch != min(raw16, 60000):
            raise ValueError(f'Derived low16 expression mismatch: {raw16}')
    for raw, low16, expected_word, expected_hex in evidence['arithmeticExamples']:
        word = min(raw & 0xFFFF, 60000)
        if low16 != raw & 0xFFFF or word != expected_word or word.to_bytes(2, 'big').hex() != expected_hex:
            raise ValueError(f'Arithmetic example mismatch: {raw}')
    return {
        'passed': True, 'offlineOnly': True, 'sourcePath': str(binary),
        'sourceSha256': digest, 'instructionAnchorsChecked': len(anchors),
        'exhaustiveLow16CasesChecked': 65536,
        'expression': 'min(getter_raw & 0xFFFF, 60000)',
        'engineeringUnitPromoted': False, 'vehicleOperations': 0,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, help='Path to the exact pinned gbacqservice binary')
    args = parser.parse_args()
    evidence = json.loads(EVIDENCE.read_text())
    binary = args.binary if args.binary is not None else ROOT / evidence['source']['path']
    print(json.dumps(check(binary, evidence), indent=2))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, struct.error) as error:
        print(json.dumps({'passed': False, 'error': str(error)}), file=sys.stderr)
        raise SystemExit(1)
