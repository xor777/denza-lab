#!/usr/bin/env python3
"""Verify finite washer warning presentation evidence; local files only.

This checks retained binary bytes and Godot token provenance. It does not load
libraries, execute firmware, contact ADB/network, or validate a physical sensor.
"""
from pathlib import Path
import hashlib
import json
import re
import struct

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / 'research/vehicle-diagnostics/washer-warning-conditions-20261021.json'


def require(value, reason):
    if not value:
        raise ValueError(reason)


def elf_va(data, va, length):
    require(data[:6] == b'\x7fELF\x02\x01', 'expected little-endian ELF64')
    phoff = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    for index in range(count):
        kind, flags, offset, base, pa, filesz, memsz, align = struct.unpack_from(
            '<IIQQQQQQ', data, phoff + index * size)
        if kind == 1 and base <= va and va + length <= base + filesz:
            return data[offset + va - base:offset + va - base + length]
    raise ValueError(f'address outside file-backed segment: {va:#x}')


def mov32(word, value, register, keep=False):
    return word == (0x72A00000 if keep else 0x52800000) | (value << 5) | register


def bl_target(word, pc):
    require(word >> 26 == 0b100101, 'expected AArch64 BL')
    immediate = word & 0x3FFFFFF
    if immediate & 0x2000000:
        immediate -= 0x4000000
    return pc + 4 * immediate


def main():
    report = json.loads(REPORT.read_text())
    require(report['offlineOnly'] and report['vehicleOperations'] == 0
            and report['networkOperations'] == 0, 'scope no longer offline')
    require(not report['applicability']['runtimeClusterLoadingVerified']
            and not report['applicability']['physicalSensorProven'],
            'static presentation evidence promoted to runtime/sensor proof')
    sources = {}
    for row in report['sources']:
        data = (ROOT / row['path']).read_bytes()
        require(row['path'] not in sources, 'duplicate source path')
        require(len(data) == row['bytes']
                and hashlib.sha256(data).hexdigest() == row['sha256'],
                f"source differs: {row['path']}")
        sources[row['path']] = data
    popup = json.loads(sources[report['dictionary']['path']])
    original = popup['dictionary'][318]
    require(original == report['dictionary']['original']
            and original['id'] == '1DD' and original['text'] == '请加注洗涤液'
            and original['textColor'] == '0xEEF5FFFF'
            and original['icon'].endswith('/0x1DD.png'), 'washer dictionary row')
    require(not any(row['id'] == '0' for row in popup['dictionary']),
            'dictionary now gives warning0 a message')
    for row in report['gdcInputs']:
        data = sources[row['local']]
        require(data[:4] == b'GDSC' and len(data) == row['size']
                and hashlib.md5(data).hexdigest() == row['md5']
                and hashlib.sha256(data).hexdigest() == row['sha256'],
                'original GDC token-buffer provenance differs')
    for row in report['nativeByteAnchors']:
        expected = bytes.fromhex(row['bytes'])
        require(elf_va(sources[row['path']], int(row['va'], 16), len(expected)) == expected,
                f"native anchor differs: {row['label']}")
    for row in report['sourceLineAnchors']:
        require(sources[row['path']].decode().splitlines()[row['line'] - 1] == row['text'],
                f"source line differs: {row['path']}:{row['line']}")
    cells = sources['captures/firmware/pulled-from-car-20261018/native/libCellsClusterDs.so']
    def words(va, count):
        return struct.unpack('<' + 'I' * count, elf_va(cells, va, 4 * count))
    admit = words(0x17EBE4, 4)
    require(mov32(admit[0], 477, 8)
            and bl_target(admit[3], 0x17EBF0) == 0x184680,
            '1DD is no longer inserted into common admission set')
    # Straight-line initialization up to the washer insertion: no model/config
    # branch selects whether this entry is inserted. Calls may still throw.
    init_words = words(0x17C4B0, (0x17EBF4 - 0x17C4B0) // 4)
    require(not any((word & 0xFF000010 == 0x54000000)
                    or (word & 0x7E000000 in (0x34000000, 0x36000000))
                    for word in init_words),
            'conditional branch now precedes 1DD admission-set insertion')
    raw = words(0x182E78, 3)
    require(mov32(raw[0], 8, 1) and mov32(raw[1], 0x2610, 1, True)
            and bl_target(raw[2], 0x182E80) == 0x3241C0,
            'raw UINT16 FID26100008 read differs')
    comparison_immediates = set()
    for word in words(0x182DC0, 1364 // 4):
        if word & 0xFFC003FF == 0x71000108:
            comparison_immediates.add((word >> 10) & 0xFFF)
    require({0x64, 0x13F, 0x165, 0x167, 0xAE, 0x1EC, 0x1EE,
             0x262, 0x267, 0x268, 0x269, 0x238, 0x263, 0x247}
            <= comparison_immediates and 477 not in comparison_immediates,
            'ID-specific warning comparisons differ')
    require(mov32(words(0x18278C, 1)[0], 500, 2)
            and mov32(words(0x18322C, 1)[0], 100, 2), 'native timer durations differ')
    layouts = [json.loads(line) for line in sources[
        'captures/cluster-native-20261018/libCellsClusterDs/dwarf-layouts.jsonl'].decode().splitlines()]
    layout = next(row for row in layouts if row['name'] == 'BusinessUi2')
    members = {row['name']: row['location'] for row in layout['members']}
    require(all(members.get(name) == offset for name, offset in {
        'm_u16WarningInfoIndex': 0x20, 'm_warningInfoSet': 0x88,
        'm_pWarningInfoDalayTimer': 0xE8, 'm_pWarningInfoForceDisplayTimer': 0xF0,
        'm_bhide261WarningInfo': 0x133, 'm_bIsDowTriggerStatus': 0x135,
        'm_u16_pre_warning_info_index': 0x140, 'm_u8_can_40C_0c_26_30': 0x142,
    }.items()), 'BusinessUi2 member meaning differs')
    enums = [json.loads(line) for line in sources[
        'captures/cluster-native-20261018/libCellsClusterDs/dwarf-enums.jsonl'].decode().splitlines()]
    expected_enums = {'CLUSTER_DEBUG_WARNING_HIDE_261': 267,
                      'CLUSTER_DEBUG_WARNING_SHOW_261': 268,
                      'BUSINESS_MSG_ID_IS_DOW_WARNING_INFO': 0x8010}
    require(all(any(value['name'] == name and value['value'] == number
                    for row in enums for value in row['values'])
                for name, number in expected_enums.items()), 'debug/DOW enum differs')
    gdroot = ROOT / 'captures/cluster-native-20261018/pck/decoded'
    scripts = sorted(gdroot.rglob('*.gd'))
    require(len(scripts) == 375, 'negative search scope changed')
    washer_refs = [(str(path.relative_to(ROOT)), line_no, line)
                   for path in scripts
                   for line_no, line in enumerate(path.read_text().splitlines(), 1)
                   if re.search(r'\b477\b|WARN_ID_ADD_WASH_LIQUID|0x1DD', line)]
    require(washer_refs == [('captures/cluster-native-20261018/pck/decoded/common/global_define/hmi_def.gd',
                             2644, '    WARN_ID_ADD_WASH_LIQUID = 477 ,')],
            'new washer-specific Godot reference found')
    require(report['native']['washerSpecificBranches'] == 0
            and report['observation']['rawWithoutPopup'], 'presentation conclusion differs')
    print(json.dumps(dict(status='PASS', offlineOnly=True, vehicleOperations=0,
                          networkOperations=0, warning='0x1DD', sourceHashes=len(sources),
                          gdcInputs=len(report['gdcInputs']),
                          nativeByteAnchors=len(report['nativeByteAnchors']),
                          sourceLineAnchors=len(report['sourceLineAnchors']),
                          scannedGodotScripts=len(scripts), washerGodotReferences=len(washer_refs),
                          rawWithoutPopup=True, physicalSensorProven=False,
                          runtimeClusterLoadingVerified=False), ensure_ascii=False))


if __name__ == '__main__':
    main()
