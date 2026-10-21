#!/usr/bin/env python3
"""Verify the retained, offline-only HAL collection/readback evidence corpus.

Does not load firmware, call IPC, access a device/network, or write artifacts.
The report intentionally qualifies no live table-readback operation.
"""
import hashlib
import json
import re
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPORT = Path(__file__).with_name('hal-collection-readback-20261021.json')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, detail):
    if not condition:
        raise ValueError(detail)


def elf_bytes(path, va, length):
    raw = path.read_bytes()
    require(raw[:6] == b'\x7fELF\x02\x01', f'not ELF64LE: {path}')
    require(struct.unpack_from('<H', raw, 18)[0] == 183, f'not AArch64: {path}')
    offset = struct.unpack_from('<Q', raw, 40)[0]
    entry_size, count = struct.unpack_from('<HH', raw, 58)
    require(entry_size == 64, 'unexpected ELF section size')
    for i in range(count):
        fields = struct.unpack_from('<IIQQQQIIQQ', raw, offset + i * entry_size)
        _, kind, _, address, file_offset, size, *_ = fields
        if kind != 8 and address <= va and va + length <= address + size:
            return raw[file_offset + va - address:file_offset + va - address + length]
    raise ValueError(f'unmapped {path}:{va:#x}+{length:#x}')


def main():
    report = json.loads(REPORT.read_text())
    require(report['schema'] == 'hal-collection-readback-v1', 'schema')
    require(report['conclusion']['qualifiedLiveReadbackOperations'] == [], 'live operation added')
    require(report['conclusion']['currentTableSnapshotAvailable'] is False, 'runtime snapshot overclaim')
    require(report['conclusion']['mqttApplicationOwner'] == 'vhds_source_writer_runtime_unresolved', 'MQTT owner')
    require(report['conclusion']['secretObdApplicationOwner'] == 'unresolved_in_examined_corpus', 'SecretOBD owner')
    for item in report['sources']:
        path = ROOT/item['path']
        require(path.is_file() and sha(path) == item['sha256'], f'source changed/missing: {item["path"]}')

    base = ROOT/report['captureBase']
    ranges = json.loads((base/'native-ranges.json').read_text())
    for item in ranges:
        raw = elf_bytes(ROOT/item['path'], int(item['va'], 16), item['length'])
        require(hashlib.sha256(raw).hexdigest() == item['sha256'], f'native range: {item["name"]}')
    xrefs = json.loads((base/'getter-direct-xrefs.json').read_text())
    callers = {r['caller']['name'] for r in xrefs if r['caller'] and 'getCanMap' in r['targetName']}
    require(callers == {'byd_auto_hal::AutoHardwareInterface::updateParameters(char*, long long)',
                        'byd_auto_hal::AutoHardwareInterface::setTable(unsigned int, char*, unsigned int)'}, 'enumerator direct callers')
    require(any(r['va']=='0xb1154' and 'getCanMapKeys(long*)' in r['targetName'] for r in xrefs), 'replay edge')

    search = json.loads((base/'stock-client-search.json').read_text())
    require(len(search['files']) == 45671 and len(search['selected_trees']) == 50, 'stock scope')
    pattern = re.compile(r'(?<![A-Za-z0-9_])(?:'+'|'.join(re.escape(t) for t in search['tokens'])+r')(?![A-Za-z0-9_])', re.I)
    expected_hits = {r['path']: r['hits'] for r in search['hits']}
    seen = set()
    for item in search['files']:
        name = item['path']
        require(name not in seen, 'duplicate search file')
        seen.add(name)
        path = ROOT/name
        require(path.is_file() and sha(path) == item['sha256'], f'search source changed/missing: {name}')
        hits = [{'line':i,'text':line.strip()} for i,line in enumerate(path.read_text(errors='replace').splitlines(),1) if pattern.search(line)]
        require(hits == expected_hits.get(name, []), f'token result changed: {name}')
    require(len(expected_hits) == 96 and set(expected_hits) <= seen, 'hit scope')
    # Separate stock application call sites from copied API/constant/mapper code.
    app_hits = [name for name in expected_hits if not name.endswith(('BYDAutoFeatureIds.java',
        'BYDAutoDeviceFeaturesMap.java','Setting.java','SettingMapper.java','Mqtt.java',
        'MqttMapper.java','BYDAutoVehicleDataDevice.java'))]
    require(app_hits == ['reverse/candatacollect-jadx/sources/com/byd/CanDataCollect/entity/CanDataHandle.java'], 'new application call site')
    text = (ROOT/app_hits[0]).read_text()
    calls = re.findall(r'\.sendRegisterTable\(\s*(\d+)\s*,', text)
    require(calls == ['3','3','3','3'], 'CanDataCollect owner type')

    table_json = ROOT/'captures/diagnostics-next-depth-20261021/engine/gb-default-table-bytes.json'
    tables = json.loads(table_json.read_text())
    derived = []
    for table in tables:
        entries = table['entries']
        require(len(entries) == table['count'] and table['recordBytes'] == 16, 'GB table dimensions')
        raw = bytes.fromhex(''.join(e['bytes'] for e in entries))
        require(len(raw) == 16*table['count'], 'GB bytes')
        require(elf_bytes(ROOT/report['gbDefaults']['binary'], int(table['tableVA'],16), len(raw)) == raw, 'GB binary/table join')
        ids = [struct.unpack_from('<I', raw, i*16)[0] for i in range(table['count'])]
        require(ids == [int(e['idU32LE'],0) for e in entries], 'GB ID decode')
        require(not set(ids) & {0x7e8,0x7ee}, 'GB target record')
        derived.append({'energyType':table['energyType'],'count':len(ids),'targetMatches':[]})
    require(derived == report['gbDefaults']['derived'], 'GB summary')
    vhds = report['vhdsContinuation']
    require(vhds['runtimeOwnerProven'] is False and vhds['exclusiveOwnerProven'] is False, 'VHDS runtime overclaim')
    require(vhds['newExtraction']['requestedFiles'] == ['/system/bin/vhds'], 'extraction scope')
    canonical = ROOT/vhds['newExtraction']['canonicalCopy']
    extracted = ROOT/vhds['newExtraction']['readerCopy']
    require(canonical.read_bytes() == extracted.read_bytes(), 'VHDS canonical/extracted mismatch')
    require(sha(canonical) == 'a8ae8c73f470e2d524ba57c8f7ca1644b1d94480a5fa5d8cbc4fac36498c55e0', 'VHDS byte identity')
    vhds_ranges = json.loads((base/'vhds-native-ranges.json').read_text())
    require(len(vhds_ranges) == 20 and sum(r['length'] for r in vhds_ranges) == 18268, 'VHDS selected-function scope')
    for item in vhds_ranges:
        raw = elf_bytes(ROOT/item['path'], int(item['va'],16), item['length'])
        require(hashlib.sha256(raw).hexdigest() == item['sha256'], f'VHDS native range: {item["va"]}')
    for table in vhds['builtInWriteBuffers']:
        raw = bytes.fromhex(table['bytes'])
        require(len(raw) % 8 == 0, 'VHDS monitor entries')
        parsed = [{'canId':f'{int.from_bytes(raw[i:i+4],"big"):03X}',
                   'subId':raw[i+4], 'channel':raw[i+5], 'mode':raw[i+6], 'operation':raw[i+7]}
                  for i in range(0,len(raw),8)]
        require(parsed == table['entries'], 'VHDS entry decode')
        require(not {e['canId'] for e in parsed} & {'7E8','7EE'}, 'VHDS target entry')
    require([t['bytes'] for t in vhds['builtInWriteBuffers']] ==
            ['0000034700000101','000004BF000200010000044A00020001'], 'VHDS stack-built buffers')
    require(vhds['protocolPredicate']['tableFid'] == 'AA000165 except isToyota && !isCanFD; legacy000A4F2E otherwise', 'VHDS protocol selection')
    require(report['limitations']['noWireAbsenceConclusion'] is True, 'wire absence overclaim')
    print(f'PASS: {len(report["sources"])} pinned sources; {len(ranges)} native ranges; '
          f'{len(search["files"])} stock source files/{len(expected_hits)} token-hit files; '
          f'GB defaults 33+34 records; VHDS {len(vhds_ranges)} ranges/3 built-in MQTT entries; '
          'no live table readback qualified')


if __name__ == '__main__':
    main()
