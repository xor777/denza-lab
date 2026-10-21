#!/usr/bin/env python3
"""Verify retained ISG trace prefix and native byte ranges; no vehicle/native execution."""
import hashlib
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
REPORT=Path(__file__).with_name('isg-gateway-trace-20261021.json')


def check():
    v=json.loads(REPORT.read_text())
    for source in v['sources']:
        data=(ROOT/source['path']).read_bytes()
        assert len(data)==source['bytes']
        assert hashlib.sha256(data).hexdigest()==source['sha256'],source['path']
    transcript=(ROOT/v['sources'][0]['path']).read_text()
    rejected=[line for line in transcript.splitlines() if line.startswith('REJECTED_FRAME ')]
    assert len(rejected)==1
    raw=bytes.fromhex(rejected[0].split(' raw=')[1])
    assert raw[:7]==bytes.fromhex('0A07EEE9020103') and len(raw)==240
    trace=raw[10:];assert len(trace)==230
    uds=bytearray();sequence=1
    for offset in range(0,len(trace),13):
        record=trace[offset:offset+13]
        row=v['canTranscriptPrefix'][offset//13]
        assert record.hex().upper()==row['raw']
        assert row['completeRecord']==(len(record)==13)
        identifier=int.from_bytes(record[3:5],'big');payload=record[5:]
        if identifier!=0x7EE:continue
        if not uds:
            assert payload[:5]==bytes.fromhex('10E7590A09')
            total=((payload[0]&15)<<8)|payload[1];uds.extend(payload[2:])
        else:
            assert payload[0]==0x20|(sequence&15)
            sequence+=1;uds.extend(payload[1:])
    assert len(uds)==107 and total==231 and uds.hex().upper()==v['reconstruction']['udsPrefixHex']
    records=[(uds[i:i+3].hex().upper(),f'{uds[i+3]:02X}') for i in range(3,len(uds),4)]
    assert records==[(r['rawDtc'],r['statusHex']) for r in v['reconstruction']['records']]
    assert len(records)==26 and not v['reconstruction']['qualifiedSuccess']
    image=(ROOT/v['sources'][1]['path']).read_bytes()
    for region in v['native']['ranges']:
        start=int(region['startVA'],16)-0x40000;end=int(region['endExclusiveVA'],16)-0x40000
        assert hashlib.sha256(image[start:end]).hexdigest()==region['sha256']
    assert not v['outer']['secondOuterFragmentRetained']
    print('PASS: sources and4 native byte ranges;107/231 UDS prefix bytes,26 partial tuples; no complete registry')


if __name__=='__main__':check()
