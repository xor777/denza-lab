#!/usr/bin/env python3
"""Replay finite warning/DTC linkage evidence; only local files, no firmware execution."""
from pathlib import Path
import csv
import gzip
import hashlib
import io
import json
import re
import struct

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / 'research/vehicle-diagnostics/warning-dtc-linkage-20261021.json'
OUT = ROOT / 'captures/diagnostics-linkage-20261021/warnings'
PATTERN = r'DTC|[Dd]iagnostic|[Dd]tc|fault.?[Cc]ode|FaultCode|故障码'
WARNING_API = r'0x26100008|638582792|INSTRUMENT_DD_FAULT_NOTICE_261|onTextInfoChanged|\.getTextInfo\('
CL = 'captures/cluster-native-20261018/'
NATIVE = 'captures/firmware/pulled-from-car-20261018/native/'
CELLS = NATIVE + 'libCellsClusterDs.so'
CLIENT = NATIVE + 'libClusterClient.android.release.so'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def elf_va(data, address, length):
    phoff = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    for index in range(count):
        kind, flags, offset, va, pa, filesz, memsz, align = struct.unpack_from(
            '<IIQQQQQQ', data, phoff + index * size)
        if kind == 1 and va <= address and address + length <= va + filesz:
            return data[offset + address - va:offset + address - va + length]
    raise ValueError(f'VA not file backed: {address:#x}')


def literal_references():
    functions = []
    with (ROOT / (CL + 'libCellsClusterDs/functions.tsv')).open() as stream:
        for row in csv.DictReader(stream, delimiter='\t'):
            functions.append((int(row['address'], 16), int(row['size']), row['demangled']))
    functions.sort()
    index, previous, found = 0, [], []
    with gzip.open(ROOT / (CL + 'libCellsClusterDs/disassembly.txt.gz'), 'rt') as stream:
        for line in stream:
            match = re.match(r'^([0-9a-f]{8})\s', line)
            if not match:
                continue
            va = int(match[1], 16)
            while index + 1 < len(functions) and functions[index + 1][0] <= va:
                index += 1
            match = re.search(r'movk\s+(w\d+), #0x2610, lsl #16$', line.rstrip())
            if match:
                exact = any(re.search(r'mov\s+' + match[1] + r', #8$', text)
                            for text in previous[-4:])
                found.append(dict(va=hex(va), function=functions[index][2],
                                  prior=previous[-4:], instruction=line.strip(),
                                  exactFID26100008=exact))
            previous = (previous + [line.strip()])[-8:]
    return found


def scan_groups(manifest):
    result = []
    for group in manifest['groups']:
        matches = []
        for row in group['files']:
            data = (ROOT / row['path']).read_bytes()
            require(len(data) == row['bytes'] and hashlib.sha256(data).hexdigest() == row['sha256'],
                    f"scan input changed: {row['path']}")
            for line, text in enumerate(data.decode().splitlines(), 1):
                if re.search(group['pattern'], text):
                    matches.append(dict(path=row['path'], line=line, text=text))
        result.append(dict(name=group['name'], pattern=group['pattern'],
                           files=len(group['files']), matchCount=len(matches), matches=matches))
    return result


def main():
    report = json.loads(REPORT.read_text())
    require(report['offlineOnly'] and report['vehicleOperations'] == 0
            and report['networkOperations'] == 0 and report['archiveExtractions'] == 0,
            'offline scope changed')
    require(report['result']['qualifiedWarningDTCLinks'] == 0
            and report['result']['qualifiedSenderECUIdentities'] == 0,
            'unproved DTC link or ECU identity promoted')
    sources = {}
    for row in report['sources']:
        data = (ROOT / row['path']).read_bytes()
        require(len(data) == row['bytes'] and hashlib.sha256(data).hexdigest() == row['sha256'],
                f"source changed: {row['path']}")
        sources[row['path']] = data
    manifest = json.loads(sources[report['searchManifest']])
    scans = scan_groups(manifest)
    require(scans == report['boundedSearches'], 'bounded search findings differ')
    refs = literal_references()
    require(refs == json.loads(sources[report['canonicalNative261ReferenceScan']['raw']]),
            'native literal reference set differs')
    require([r for r in refs if r['exactFID26100008']] == report['exactWarningFIDReferences'],
            'exact warning-FID reference set differs')
    require(len(refs) == 47 and sum(row['exactFID26100008'] for row in refs) == 8,
            'native literal reference count differs')
    for row in report['byteAnchors']:
        require(elf_va(sources[row['path']], int(row['va'], 16), len(bytes.fromhex(row['bytes'])))
                == bytes.fromhex(row['bytes']), f"byte anchor: {row['label']}")
    original = json.loads(sources['captures/firmware/pulled-from-car-20261018/popup_nads.json'])
    dictionary = original['dictionary']
    require(len(dictionary) == 628 and set().union(*(set(row) for row in dictionary)) ==
            {'id', 'text', 'textColor', 'icon'}, 'OEM warning dictionary shape differs')
    config_path = report['separateIVIDiagnostics']['path']
    diagnostics = json.loads(sources[config_path])['diagnostic_infor_config']['diag_infor_value']
    require(len(diagnostics) == report['separateIVIDiagnostics']['entries'] == 213,
            'separate IVI diagnostic catalog differs')
    require(set().union(*(set(row) for row in diagnostics)) ==
            {'dtc', 'diagnostictype', 'moduletype', 'destination', 'modulename', 'describe'},
            'diagnostic catalog schema differs; inspect new join fields')
    exact_texts = sorted(set(row['text'] for row in dictionary) &
                         set(row['describe'] for row in diagnostics))
    require(exact_texts == report['separateIVIDiagnostics']['exactChineseTextIntersections'],
            'exact Chinese intersection differs; intersection alone is not a mapping')
    for row in report['priorityWarnings']:
        require(next(r for r in dictionary if r['id'] == row['id']) == row['original'],
                'priority original text/color differs')
        require(row['dtc'] is None and row['owningECU'] is None
                and not row['translationIsOEM'], 'unproved priority mapping promoted')
    items = list(csv.DictReader(io.StringIO(sources[CL+'tables/data-items.tsv'].decode()), delimiter='\t'))
    item = next(row for row in items if row['id_hex'] == '0x122')
    require(item['name'] == 'checkEngineOverheat' and item['id'] == '290',
            'backend DataItem identity differs')
    generic = next(row for row in dictionary if row['id'] == '122')
    overheat = next(row for row in dictionary if row['id'] == '1E')
    require(generic['text'] == '系统异常，请行驶至服务店检修'
            and overheat['text'] == '发动机冷却液温度过高，请安全停车并联系服务店',
            'namespace counterexample differs')
    signals = list(csv.DictReader(io.StringIO(sources[CL+'tables/can-signals.tsv'].decode()), delimiter='\t'))
    signal = next(row for row in signals if row['fid'] == '0x3d901015')
    require(signal['real_fid'] == '0x3d901815' and signal['type'] == 'CAN_DATA_TYPE_UINT8'
            and signal['no_need_platform'] == '0x100'
            and signal['static_data_hex'].startswith('0001'), 'history flag table differs')
    client = sources[CLIENT]
    jump_va = 0x19B3D0 + (0x122 - 0x3B) * 2
    offset = struct.unpack('<H', elf_va(client, jump_va, 2))[0]
    require(0x461928 + offset * 4 == 0x466FF0, 'Telltale DataItem290 no-op path differs')
    cells = sources[CELLS]
    # Decode the exact AArch64 MOVZ/MOVK arguments, not just prose annotations.
    low, high, call = struct.unpack('<III', elf_va(cells, 0x25F264, 12))
    require(low == 0x52800001 | (0x1015 << 5)
            and high == 0x72A00001 | (0x3D90 << 5)
            and call == 0x94000000 | ((0x324140 - 0x25F26C) // 4),
            'history flag input/call differs')
    result = dict(status='PASS', offlineOnly=True, warningDTCLinks=0, senderECUIdentities=0,
                  recoveredFlagToBackendItem=1, priorityWarningTexts=len(report['priorityWarnings']),
                  godotScripts=next(row['files'] for row in scans if row['name']=='godot'),
                  canonicalNative261References=47, exactWarningFIDReferences=8,
                  runtimeLoadingVerified=False, physicalSensorBindingVerified=False)
    (OUT / 'check.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
