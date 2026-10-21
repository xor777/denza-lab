#!/usr/bin/env python3
"""Replay pinned, synthetic native alarm blocks. No firmware/device/network I/O.

Run with .venv-firmware/bin/python. The interpreter accepts only bounded integer
instructions and a small synthetic memory; firmware calls are never executed.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import sys

ROOT = Path(__file__).resolve().parents[2]
REPORT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT/'research/vehicle-diagnostics/gb-alarm-dtc-boundary-20261021.json'
d = json.loads(REPORT.read_text())
assert d['vehicleOperations'] == d['cloudOperations'] == 0
assert d['firmwareExecuted'] is False
for s in d['sources']:
    b = (ROOT/s['path']).read_bytes()
    assert len(b) == s['bytes'] and hashlib.sha256(b).hexdigest() == s['sha256'], s['path']
path = ROOT/d['captureBase']/'replay_alarm_blocks.py'
spec = importlib.util.spec_from_file_location('gb_alarm_replay', path)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
def read(a,n):
    for s in m.ELF.iter_sections():
        if s['sh_type'] != 'SHT_NOBITS' and s['sh_addr'] <= a and a+n <= s['sh_addr']+s['sh_size']:
            return s.data()[a-s['sh_addr']:a-s['sh_addr']+n]
    raise AssertionError(hex(a))
for a in d['anchors']:
    lo,hi = int(a['start'],16),int(a['endExclusive'],16)
    assert read(lo,hi-lo).hex() == a['hex'], a['start']
assert d['native']['profile'] == 170 and d['native']['energytype'] == 2
assert 0x19e58 + read(0x8295 + 1039-1001,1)[0]*4 == 0x19fc8
expected = {
    '43800008':(0,0x1a0f8), '4380000A':(10,0x1a1b0),
    '4380000C':(9,0x1a330), '4380000E':(2,0x1a360),
    '43800010':(3,0x1a3a4), '43800012':(18,0x1a3d4),
    '43800014':(7,0x1a420), '43800016':(8,0x1a488),
    '2440003E':(16,0x1a898), '26C00010':(12,0x1a8bc),
    '26C00022':(14,0x1a7fc), '2440000C':(1,0x1a514),
    '2400000C':(15,0x1a948), '2400000A':(17,0x1a9f0),
}
assert len(d['alarmInputs']) == len(expected)
assert {r['fid'] for r in d['alarmInputs']} == set(expected)
names = {}
for line in (ROOT/'captures/ambient-light-20260923/data/fids-canfd.tsv').read_text().splitlines():
    c = line.split('\t')
    if len(c) == 3:
        try: names.setdefault(int(c[2]),set()).add(c[1])
        except ValueError: pass
init = json.loads((ROOT/'captures/diagnostics-next-depth-20261021/engine/gb-fid-initializer-values.json').read_text())
initializers = init['variants'][0]['writes']
callback_cases = 0
for r in d['alarmInputs']:
    bit,start = expected[r['fid']]
    assert (r['bit'],int(r['bodyVA'],16),r['device']) == (bit,start,1039)
    assert r['sourceNames'] == sorted(names[int(r['fid'],16)])
    if r['initializer']:
        assert r['initializer'] in initializers and int(r['initializer']['value'],16) == int(r['fid'],16)
    for sim in (False,True):
        active = r['triggerRawValuesSimulationOn' if sim else 'triggerRawValuesSimulationOff']
        assert len(active) == len(set(active)) and all(0 <= v < 256 for v in active)
        for raw in range(256):
            for old in (0,0xffffffff):
                actual,_ = m.callback(start,raw,old,sim)
                want = (old & ~(1 << bit)) | ((1 << bit) if raw in active else 0)
                assert actual == want, (r['fid'],raw,sim,old)
                callback_cases += 1
assert d['alarmRecord']['zeroStoreVAs'] == ['0x19350','0x1937C','0x19380','0x19384']
for a in d['alarmRecord']['zeroStoreVAs']:
    i = m.INS[int(a,16)]
    assert i.mnemonic == 'strb' and i.op_str.startswith('wzr,')
assert d['alarmRecord']['bytes'] == 10 and d['alarmRecord']['identifier'] == 7
packet_cases = 0
for level in range(4):
    for byte in range(4):
        for value in range(256):
            mask = value << (8*byte)
            actual,_ = m.packet(level,mask)
            assert actual == bytes([7,level])+mask.to_bytes(4,'big')+bytes(4)
            packet_cases += 1
for r in d['alarmRecord']['syntheticReplays']:
    assert r['synthetic'] is True
    assert m.packet(r['level'],int(r['bitmap'],16))[0].hex() == r['packetHex']
assert d['verification']['callbackCases'] == callback_cases == 14336
assert d['verification']['packetCases'] == packet_cases == 4096
print(json.dumps({'valid':True,'sources':len(d['sources']),'anchors':len(d['anchors']),'nativeAlarmInputs':len(expected),'callbackCases':callback_cases,'packetCases':packet_cases,'fixedZeroCodeCounts':4,'qualifiedWarningDtcMappings':0,'vehicleOperations':0}))
