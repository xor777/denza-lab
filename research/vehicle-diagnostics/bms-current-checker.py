#!/usr/bin/env python3
"""Replay the bounded offline BMS/ISG consumer evidence. No ADB, network or vehicle operations.
Run: .venv-firmware/bin/python research/vehicle-diagnostics/bms-current-checker.py
"""
from pathlib import Path
import hashlib,io,importlib.util,json,re,struct
from elftools.elf.elffile import ELFFile
ROOT=Path(__file__).resolve().parents[2]
BMS=ROOT/'research/vehicle-diagnostics/bms-current-consumers-20261021.json'
ISG=ROOT/'research/vehicle-diagnostics/isg-source-boundary-20261021.json'
bms=json.loads(BMS.read_text());isg=json.loads(ISG.read_text())
assert bms['offlineOnly'] and isg['offlineOnly']
assert bms['vehicleOperations']==isg['vehicleOperations']==0
assert bms['networkOperations']==isg['networkOperations']==0
sources={s['path']:s for d in [bms,isg] for s in d['sources']}
for rel,s in sources.items():
 p=ROOT/rel
 assert p.stat().st_size==s['bytes'],('size',rel)
 assert hashlib.sha256(p.read_bytes()).hexdigest()==s['sha256'],('hash',rel)
def rd(p,a,n):
 e=ELFFile(io.BytesIO(p.read_bytes()))
 for s in e.iter_sections():
  if s['sh_type']!='SHT_NOBITS' and s['sh_addr']<=a and a+n<=s['sh_addr']+s['sh_size']:return s.data()[a-s['sh_addr']:a-s['sh_addr']+n]
 raise AssertionError((p,hex(a),n))
anchors=bms['anchors']+isg['anchors']
for a in anchors:
 lo=int(a['va'],16);hi=int(a['endExclusive'],16)
 assert rd(ROOT/a['source'],lo,hi-lo).hex()==a['bytes'],('native anchor',a['meaning'])
# The evaluator is part of the pinned ignored evidence corpus; it implements only
# pure constant operations and writes to synthetic local object/stack addresses.
p=ROOT/bms['captureBase']/'evaluate_autoid.py'
spec=importlib.util.spec_from_file_location('bms_autoid_expression',p);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
expected=json.loads((ROOT/bms['captureBase']/'autoid-selected-fields.json').read_text())
for v in expected['variants']:
 assert module.run(v['canfd'],v['toyota'])==v,('constructor',v['canfd'],v['toyota'])
CAN=expected['variants'][0]['fields']
assert CAN['0x4298']=='0x44400018' and CAN['0x429c']=='0xC4400018'
# Selected list gate, rather than a universal bitmask, creates the444 alias.
ids={int(x,16) for x in bms['selectiveCanonicalization']['CANids']}
assert len(ids)==18 and 0x444 in ids and 0x286 in ids and 0x46c in ids
assert 0x44a not in ids and 0x345 not in ids
assert 0x44400818&0xfffff3ff==0x44400018
# Pinned MCU generic descriptor/property pair. Do not infer layout from hex IDs.
mcu=ROOT/'captures/firmware/ivi-34.1.33.2605218.1/mcu/app.bin';mb=mcu.read_bytes()
for d in [bms['chargingCurrentDualCache']['inputMCU']]+isg['broadcastCandidates']:
 a=int(d.get('descriptorVA',d.get('MCUdescriptorVA')),16)-0x40000
 p=int(d.get('propertyVA',d.get('MCUpropertyVA')),16)-0x40000
 assert mb[a:a+12].hex()==d['descriptorBytes']
 assert mb[p:p+12].hex()==d['propertyBytes']
# Reparse every retained typed observation from its exact line and type bits.
lines=(ROOT/'captures/diagnostics-sweep-20261020/fids.tsv').read_text().splitlines()
for records in [bms['chargingCurrentDualCache']['retainedGETs']['rawAlias'],bms['chargingCurrentDualCache']['retainedGETs']['converted'],bms['stockGBCurrentConsumer']['retainedConfig'],bms['stockGBCurrentConsumer']['retainedAlternateCurrent']]+[r['retainedGET'] for r in isg['broadcastCandidates']]:
 for r in records:
  a=lines[r['line']-1].split('\t');assert a[0]=='FID'
  assert int(a[1])==r['pass'] and int(a[2])==r['elapsedRealtimeNanos'] and a[5]==r['intWord']
  if 'floatWord' in r:
   assert a[6]==r['floatWord'] and struct.unpack('<f',struct.pack('<I',int(a[6],16)))[0]==r['float32']
  else:assert struct.unpack('<i',struct.pack('<I',int(a[5],16)))[0]==r['integer']
f32=lambda x:struct.unpack('<f',struct.pack('<f',x))[0]
convert=lambda x:f32(f32(f32(x)*f32(0.1))-f32(500.0))
for a,c in zip(bms['chargingCurrentDualCache']['retainedGETs']['rawAlias'],bms['chargingCurrentDualCache']['retainedGETs']['converted']):
 assert a['float32']==4955.0 and convert(a['float32'])==c['float32']==-4.5
# All genericu16 current inputs produce the exact raw-float alias; neither the
# source converter nor these formula checks invent a physical invalid domain.
for raw in range(65536):
 alias=f32(raw);converted=convert(raw)
 assert alias==raw and -500.0<=converted<=6053.5
 assert (int(f32(alias+f32(5000.0)))&65535)==((raw+5000)&65535)
assert convert(5000)==0.0
assert (int(f32(4955.0+5000.0))&65535)==9955
# Existing source captures, not synthetic replacement data.
raw=bms['chargingCurrentDualCache']['recentRawReplay'];lines=(ROOT/raw['source']).read_text().splitlines()
assert len(raw['records'])==20
for r in raw['records']:
 l=lines[r['line']-1];assert 'id=0x444 sub=0x00 ch=2' in l
 payload=re.search(r'payload=(\w+)',l)[1];assert payload==r['payload']
 b=bytes.fromhex(payload);assert len(b)==8 and int.from_bytes(b[2:4],'little')==r['wireCurrentU16LE']==5000
for r in isg['broadcastCandidates']:
 rr=r['recentRaw'];lines=(ROOT/rr['source']).read_text().splitlines();assert len(rr['records'])==rr['count']
 c=r['coordinatesOneBasedByteLSB0'];start=(c[0]-1)*8+c[1];end=(c[2]-1)*8+c[3]
 for q in rr['records']:
  packet=bytes.fromhex(re.search(r'raw=(\w+)',lines[q['line']-1])[1]);assert int.from_bytes(packet[:4],'big')==int(r['sourceCANid'],16) and packet[5]==0
  payload=packet[10:];assert payload.hex()==q['payload']
  wire=(int.from_bytes(payload,'little')>>start)&((1<<(end-start+1))-1);assert wire==q['wire']
  assert q['HALAlgebra']==(wire-1000 if r['name']=='ENGINE_CHARGE_POWER' else wire)
assert [r['recentRaw']['count'] for r in isg['broadcastCandidates']]==[19,0,20]
assert isg['generationStateEnum']['values']=={'0':'INVALID','1':'GENERATING','2':'END','3':'ERROR'}
assert bms['newEngineeringUnitsProved']==0
print(json.dumps({'valid':True,'uniquePinnedSources':len(sources),'nativeByteAnchors':len(anchors),'AutoIDVariants':3,'CANFDConstantExpressionSteps':expected['variants'][0]['steps'],'genericU16AliasCases':65536,'typedAliasGETPairs':2,'retainedRaw444Frames':20,'retainedGenerationValueFrames':19,'retainedEngineChargePowerFrames':20,'qualifiedNewEngineeringUnits':0,'vehicleOperations':0,'networkOperations':0}))
