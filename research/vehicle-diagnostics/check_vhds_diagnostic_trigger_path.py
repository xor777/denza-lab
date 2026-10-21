#!/usr/bin/env python3
"""Offline verification and synthetic byte projection for retained VHDS code.

No firmware execution, IPC, network, vehicle access, artifact writes, or live
command generation. Synthetic cases are source arithmetic examples only.
"""
import hashlib
import json
import re
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPORT = Path(__file__).with_name('vhds-diagnostic-trigger-path-20261021.json')


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def elf_bytes(path, va, count):
    raw = path.read_bytes()
    require(raw[:6] == b'\x7fELF\x02\x01', 'ELF64LE')
    require(struct.unpack_from('<H', raw, 18)[0] == 183, 'AArch64')
    offset = struct.unpack_from('<Q', raw, 40)[0]
    entry_size, entries = struct.unpack_from('<HH', raw, 58)
    require(entry_size == 64, 'ELF section size')
    for i in range(entries):
        _, kind, _, address, file_offset, size, *_ = struct.unpack_from('<IIQQQQIIQQ', raw, offset+i*64)
        if kind != 8 and address <= va and va+count <= address+size:
            return raw[file_offset+va-address:file_offset+va-address+count]
    raise ValueError(f'unmapped {va:#x}+{count:#x}')


def native_hex_pairs(text):
    """Model only 15D80's observed ASCII conversion, including permissiveness."""
    def nibble(char):
        if '0' <= char <= '9': return ord(char)-ord('0')
        if 'A' <= char <= 'F': return ord(char)-ord('A')+10
        if 'a' <= char <= 'f': return ord(char)-ord('a')+10
        return 0
    result = []
    for i in range(0, len(text), 2):
        result.append((nibble(text[i])*16+nibble(text[i+1])) if i+1<len(text) else nibble(text[i]))
    return bytes(result)


def project(check_id, check_type, item):
    """Bounded numeric-to-node model; not the native strtoll/error parser."""
    require(isinstance(item,str) and item.isascii() and len(item)<=8, 'source item length scope')
    suffix = native_hex_pairs(item)
    request = check_id & 0xffff
    response = (check_id+8) & 0xffff
    node_payload = bytes([len(suffix)+1, check_type & 0xff])+suffix
    return {'requestId':f'{request:04X}', 'expectedResponseId':f'{response:04X}',
            'payloadLength':len(node_payload),'nodePayload':node_payload.hex().upper(),
            'defaultType2Envelope':(b'\x02'+request.to_bytes(2,'big')+node_payload).hex().upper(),
            'siblingType3Envelope':(b'\x03'+request.to_bytes(2,'big')+node_payload).hex().upper()}


def trigger_compare(logic, current, threshold, previous_byte):
    comparison = {0:current==threshold,1:current>threshold,2:current>=threshold}.get(logic,False)
    return comparison and previous_byte==0


def main():
    r=json.loads(REPORT.read_text())
    require(r['schema']=='vhds-diagnostic-trigger-path-v1','schema')
    require(r['claims']['runtimeTaskObserved'] is False,'runtime overclaim')
    require(r['claims']['specificWarningToDtcMappingRecovered'] is False,'warning/DTC overclaim')
    require(r['claims']['dtcDictionaryRecovered'] is False,'dictionary overclaim')
    require(r['claims']['readOnlySidWhitelistAtSendBoundary'] is False,'SID whitelist overclaim')
    require(r['claims']['canTriggerDefaultPrefix']=='02','trigger/sibling transport conflation')
    require(r['claims']['siblingPrefix03LinkedToThisTrigger'] is False,'unproved sibling link')
    require(r['claims']['schedulingHandoffFullyTraced'] is False,'scheduling handoff overclaim')
    for s in r['sources']:
        p=ROOT/s['path']
        require(p.is_file() and sha(p)==s['sha256'],f'changed/missing source: {s["path"]}')
    binary=ROOT/r['binary']['path']
    require(sha(binary)==r['binary']['sha256'],'VHDS byte identity')
    base=ROOT/r['captureBase']
    ranges=json.loads((base/'native-ranges.json').read_text())
    require(len(ranges)==26 and sum(x['length'] for x in ranges)==26252,'bounded function scope')
    for entry in ranges:
        raw=elf_bytes(binary,int(entry['va'],16),entry['length'])
        require(hashlib.sha256(raw).hexdigest()==entry['sha256'],f'native extent {entry["va"]}')
    strings=json.loads((base/'key-string-anchors.json').read_text())
    for s in strings:
        raw=bytes.fromhex(s['bytes'])
        require(elf_bytes(binary,int(s['va'],16),len(raw))==raw,f'string {s["va"]}')
        require(raw[:-1].decode()==s['text'] and raw[-1:]==b'\x00','string decode')
    table=json.loads((base/'notify-case-table.json').read_text())
    raw=elf_bytes(binary,int(table['tableVA'],16),7)
    require(raw.hex()==table['bytes'],'notify jump table bytes')
    derived=[{'diagnosisType':i,'target':hex(int(table['targetBase'],16)+4*x)} for i,x in enumerate(raw)]
    require(derived==table['targets'] and derived[4]['target']=='0x25388','type4 vector selection')
    embedded=json.loads((base/'embedded-task-payload-search.json').read_text())
    keys=['checkId','checkType','checkDidList']
    found=[]
    for section in embedded['sectionScope']:
        raw=elf_bytes(binary,int(section['va'],16),section['bytes'])
        require(hashlib.sha256(raw).hexdigest()==section['sha256'],'embedded section hash')
        for match in re.finditer(rb'[\x20-\x7e]{4,}',raw):
            text=match.group().decode()
            if re.search(r'checkId|checkType|checkDidList|resultData',text):
                found.append({'va':hex(int(section['va'],16)+match.start()),'text':text})
    require(found==embedded['keyStrings'],'embedded key strings')
    require([x for x in found if sum(k in x['text'] for k in keys)>=2]==embedded['compositeCandidates']==[],'embedded composite candidates')
    require([x for x in found if any('"'+k+'"' in x['text'] for k in keys)]==embedded['quotedJsonKeyCandidates']==[],'embedded JSON candidates')
    for edge in r['directEdges']:
        address=int(edge['va'],16)
        word=struct.unpack('<I',elf_bytes(binary,address,4))[0]
        require(word&0xfc000000==0x94000000,'expected BL')
        immediate=word&0x3ffffff
        if immediate&(1<<25):immediate-=1<<26
        require(address+4*immediate==int(edge['target'],16),f'branch {edge["va"]}')
    for case in r['syntheticProjections']:
        require(case['kind']=='synthetic_not_captured_task','synthetic provenance')
        require(project(int(case['input']['checkId'],16),int(case['input']['checkType'],16),case['input']['item'])==case['expected'],'projection')
    require(native_hex_pairs('ABC')==bytes.fromhex('AB0C'),'odd final nibble')
    require(native_hex_pairs('G1')==b'\x01','permissive invalid nibble')
    require(project(0xfff8,0x122,'')['expectedResponseId']=='0000','reply wrap')
    require(project(0x107e6,0x122,'F194')['requestId']=='07E6','request narrowing')
    try:project(0x7e6,0x22,'012345678')
    except ValueError:pass
    else:raise ValueError('length cap')
    require(trigger_compare(0,0,0,0) is True,'zero can retrigger')
    require(trigger_compare(0,1,1,2) is False,'previous value guard')
    require(trigger_compare(1,2,1,0) and not trigger_compare(1,1,1,0),'strict greater')
    require(trigger_compare(2,1,1,0) and not trigger_compare(2,0,1,0),'greater/equal')
    print(f'PASS: {len(r["sources"])} sources; {len(ranges)} functions/26252 bytes; '
          f'{len(strings)} strings/{len(r["directEdges"])} BL edges; '
          f'{len(r["syntheticProjections"])} synthetic projections; source-only trigger schema')


if __name__=='__main__':
    main()
