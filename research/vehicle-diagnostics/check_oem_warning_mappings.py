#!/usr/bin/env python3
"""Verify retained warning sources and finite mappings; no vehicle or network I/O.

Run from any directory with Python3. The ignored local firmware corpus must be
present. Original binaries are read as bytes, never loaded or executed.
"""
from pathlib import Path
import csv
import hashlib
import io
import json
import re
import struct

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / 'research/vehicle-diagnostics/oem-warning-mappings-20261021.json'
OUT = ROOT / 'captures/diagnostics-followup-20261021/warnings/check.json'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def elf_va(data, address, length):
    require(data[:6] == b'\x7fELF\x02\x01', 'expected ELF64 little-endian')
    phoff = struct.unpack_from('<Q', data, 32)[0]
    entsize, count = struct.unpack_from('<HH', data, 54)
    for index in range(count):
        kind, flags, offset, va, pa, size, memsize, align = struct.unpack_from(
            '<IIQQQQQQ', data, phoff + index * entsize)
        if kind == 1 and va <= address and address + length <= va + size:
            return data[offset + address - va:offset + address - va + length]
    raise ValueError(f'VA outside file-backed ELF segment: {address:#x}')


def mov_w(word, immediate, register, keep=False, shift=0):
    base = 0x72800000 if keep else 0x52800000
    return word == base | ((shift // 16) << 21) | (immediate << 5) | register


def bl_target(word, pc):
    require(word >> 26 == 0b100101, 'expected AArch64 BL')
    immediate = word & 0x03FFFFFF
    if immediate & 0x02000000:
        immediate -= 0x04000000
    return pc + immediate * 4


def main():
    report = json.loads(EVIDENCE.read_text())
    require(report['offlineOnly'] and report['vehicleOperations'] == 0
            and report['networkOperations'] == 0, 'offline scope changed')
    require(report['warningMappings'] == 8 and report['dtcMappings'] == 0,
            'do not promote warnings into DTC mappings')
    require(not report['applicability']['runtimeClusterLoadingVerified']
            and not report['applicability']['installedMCUImageVerified'],
            'unproved runtime applicability promoted')
    sources = {}
    for row in report['sources']:
        require(row['path'] not in sources, 'duplicate source')
        data = (ROOT / row['path']).read_bytes()
        require(len(data) == row['bytes'], f"source size: {row['path']}")
        require(hashlib.sha256(data).hexdigest() == row['sha256'],
                f"source SHA256: {row['path']}")
        sources[row['path']] = data
    popup_path = report['dictionary']['path']
    popup = json.loads(sources[popup_path])
    require(popup['version'] == '20250408' and popup['language'] == 'zh',
            'different popup dictionary revision/language')
    require(len(popup['dictionary']) == report['dictionary']['entries'] == 628,
            'dictionary entry count')
    require(not any(row['id'] == '0' for row in popup['dictionary']),
            'warning zero now has a message')
    provenance_path = 'captures/adas-speed-20261018/reports/followup-provenance.json'
    provenance = next(row for row in json.loads(sources[provenance_path])
                      if row['local_path'] == popup_path)
    require(provenance['matches_live'] and provenance['sha256'] ==
            hashlib.sha256(sources[popup_path]).hexdigest()
            and provenance['live_paths'] == ['/system/bin/config/popup_nads.json'],
            'popup retained car provenance')
    for row in report['sourceLineAnchors']:
        lines = sources[row['path']].decode().splitlines()
        require(lines[row['line'] - 1] == row['text'], 'source line anchor differs')
    for row in report['gdcInputs']:
        data = sources[row['local']]
        require(len(data) == row['size'] and data[:4] == b'GDSC'
                and hashlib.sha256(data).hexdigest() == row['sha256']
                and hashlib.md5(data).hexdigest() == row['md5'],
                'original script token buffer provenance')
    for row in report['nativeByteAnchors']:
        data = sources[row['path']]
        va, expected = int(row['va'], 16), bytes.fromhex(row['bytes'])
        actual = (elf_va(data, va, len(expected)) if data[:4] == b'\x7fELF'
                  else data[va - 0x40000:va - 0x40000 + len(expected)])
        require(actual == expected, f"native byte anchor: {row['label']}")
    native_prefix = 'captures/firmware/pulled-from-car-20261018/native/'
    cells = sources[native_prefix + 'libCellsClusterDs.so']
    client = sources[native_prefix + 'libClusterClient.android.release.so']
    expected_texts = {
        '1B': ('制动系统故障，请安全停车并联系服务店', '0xE60008FF'),
        '1E': ('发动机冷却液温度过高，请安全停车并联系服务店', '0xE60008FF'),
        '20': ('机油压力不足，请安全停车并联系服务店', '0xE60008FF'),
        '46': ('请检查ABS系统', '0xF18D00FF'),
        '5D': ('动力电池电量低，驱动功率受限', '0xF18D00FF'),
        '21': ('系统故障，请安全停车并联系服务店', '0xE60008FF'),
        '47': ('请检查四驱系统', '0xF18D00FF'),
        '358': ('电池温度高，加速性能下降', '0xEEF5FFFF'),
    }
    require({r['original']['id'] for r in report['mappings']} == set(expected_texts),
            'selected mapping set changed')
    for row in report['mappings']:
        original = popup['dictionary'][row['sourceDictionaryIndex']]
        require(original == row['original'], 'dictionary row differs')
        key, value = original['id'], int(original['id'], 16)
        require((original['text'], original['textColor']) == expected_texts[key],
                'OEM message or original RGBA differs')
        require(row['warningIdDecimal'] == value and format(value, 'X') == key,
                'GDScript %X lookup changes ID')
        require(row['owningECU'] is None and row['severityNumeric'] is None
                and not row['dtcMappingProven'] and not row['translationIsOEM'],
                'unproved ownership/severity/translation')
        require('"id": "' + key + '"' in
                sources[popup_path].decode().splitlines()[row['sourceLine'] - 1],
                'OEM JSON line anchor differs')
        va = int(row['allowlistVA'], 16)
        words = struct.unpack('<IIII', elf_va(cells, va, 16))
        require(mov_w(words[0], value, 8)
                and bl_target(words[3], va + 12) == 0x184680,
                'ID not proven in native warning allowlist')
    words = struct.unpack('<III', elf_va(cells, 0x182E78, 12))
    require(mov_w(words[0], 8, 1) and mov_w(words[1], 0x2610, 1, True, 16)
            and bl_target(words[2], 0x182E80) == 0x3241C0,
            'native UINT16 warning property read')
    word = struct.unpack('<I', elf_va(cells, 0x1827D8, 4))[0]
    require(mov_w(word, 0x320, 1), 'wrong common warning DataItem')
    require(struct.unpack('<H', elf_va(client, 0x19B04C, 2))[0] == 0,
            'DataItem 0x320 (800 decimal) jump target changed')
    require(elf_va(client, 0x1931EA, 12) == b'emit_signal\0'
            and elf_va(client, 0x184386, 12) == b'sig_warn_id\0',
            'wrong client warning signal')
    layouts = [json.loads(line) for line in sources[
        'captures/cluster-native-20261018/libClusterClient/dwarf-layouts.jsonl'].decode().splitlines()]
    require(any(row['name'] == 'WarnId' and any(
        member['name'] == 'warn_id' and member['location'] == 24
        for member in row['members']) for row in layouts), 'WarnId integer field layout')
    require(any(row['name'] == 'PopupMsgHandle' and any(
        member['name'] == '_warnId' and member['location'] == 40
        for member in row['members']) for row in layouts), 'PopupMsgHandle WarnId reference layout')
    binder_path = next(p for p in sources if p.endswith('/libbydauto.so'))
    tx_offset = struct.unpack('<H', elf_va(sources[binder_path], 0xDA0E, 2))[0]
    require(0x154E0 + tx_offset * 4 == 0x157DC, 'Binder tx5 branch differs')
    prop = report['property']
    require((prop['device'], prop['transaction'], prop['decimal'], prop['type']) ==
            (1007, 5, 0x26100008, 'getInt'), 'framework read route changed')
    table = list(csv.DictReader(io.StringIO(sources[
        'captures/cluster-native-20261018/tables/can-signals.tsv'].decode()), delimiter='\t'))
    signal = next(r for r in table if int(r['fid'], 16) == 0x26100008)
    require(signal['type'] == 'CAN_DATA_TYPE_UINT16' and signal['length'] == '2'
            and signal['static_data_hex'].startswith('0000ff03'),
            'native warning scalar domain differs')
    item_table = list(csv.DictReader(io.StringIO(sources[
        'captures/cluster-native-20261018/tables/data-items.tsv'].decode()), delimiter='\t'))
    item = next(r for r in item_table if r['id_hex'] == '0x320')
    require(item['name'] == 'commonWarningInfoId' and
            item['type'] == 'DATA_ITEM_TYPE_INTEGER', 'DataItem name/type')
    mcu = sources[report['mcu']['path']]
    def mcu_at(va, length):
        return mcu[va - 0x40000:va - 0x40000 + length]
    require(struct.unpack('<IIII', mcu_at(0xD637C, 16)) ==
            (0xE0026100, 0, 25, 0xDF884), 'MCU CAN/channel table')
    require(struct.unpack('<4BII', mcu_at(0xDF890, 12)) ==
            (1, 0, 2, 1, 0x18A09C, 0x93A5C), 'MCU exact ten-bit descriptor')
    require(struct.unpack('<III', mcu_at(0x93A5C, 12)) ==
            (0x26100008, 2, 0xFEEF5168), 'MCU property association')
    frames = []
    raw_path = 'captures/diagnostics-live-20261021/073428-passive/gb.log'
    for line, text in enumerate(sources[raw_path].decode().splitlines(), 1):
        match = re.search(r'raw=([0-9a-fA-F]+)', text)
        if not match:
            continue
        data = bytes.fromhex(match[1])
        if len(data) == 18 and int.from_bytes(data[:4], 'big') == 0x261 and data[4:6] == b'\0\0':
            frames.append(dict(line=line, envelopeHex=data.hex(), payloadHex=data[10:].hex(),
                               warningId=int.from_bytes(data[10:12], 'little') & 0x3FF))
    require(frames == report['mcu']['retainedFrames'] and len(frames) == 19
            and {f['warningId'] for f in frames} == {0}, 'retained CAN261 evidence')
    require(int.from_bytes(bytes.fromhex(frames[0]['payloadHex'])[:2], 'little') == 0xC00,
            'example no longer exercises upper-bit masking')
    retained_get = []
    sweep = 'captures/diagnostics-sweep-20261020/fids.tsv'
    for line, text in enumerate(sources[sweep].decode().splitlines(), 1):
        fields = text.split('\t')
        if fields[0] == 'FID' and fields[3:5] == ['1007', '0x26100008']:
            retained_get.append(dict(line=line, passNumber=int(fields[1]),
                                     elapsedRealtimeNanos=int(fields[2]), word=fields[5]))
    require(retained_get == prop['retainedGET'] and len(retained_get) == 2
            and all(r['word'] == '0x00000000' for r in retained_get), 'retained GETs')
    result = dict(status='PASS', offlineOnly=True, sourceHashes=len(sources),
                  scriptTokenBuffers=len(report['gdcInputs']),
                  nativeByteAnchors=len(report['nativeByteAnchors']),
                  sourceLineAnchors=len(report['sourceLineAnchors']), warningMappings=8,
                  dtcMappings=0, historicalRawFrames=19, historicalGETs=2,
                  runtimeLoadingVerified=False, installedMCUImageVerified=False)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
