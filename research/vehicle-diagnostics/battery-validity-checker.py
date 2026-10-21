#!/usr/bin/env python3
"""Check only pinned offline battery validity evidence; no vehicle/network calls.
Run with .venv-firmware/bin/python research/vehicle-diagnostics/battery-validity-checker.py
"""
from pathlib import Path
import hashlib,io,importlib.util,json,re,struct
from elftools.elf.elffile import ELFFile
ROOT=Path(__file__).resolve().parents[2]
p=ROOT/'research/vehicle-diagnostics/battery-validity-followup-20261021.json';d=json.loads(p.read_text())
assert d['offlineOnly'] and d['vehicleOperations']==d['networkOperations']==0 and not d['productChanges']
for s in d['sources']:
 b=(ROOT/s['path']).read_bytes();assert len(b)==s['bytes'] and hashlib.sha256(b).hexdigest()==s['sha256'],('source',s['path'])
def rd(p,a,n):
 e=ELFFile(io.BytesIO(p.read_bytes()))
 for s in e.iter_sections():
  if s['sh_type']!='SHT_NOBITS' and s['sh_addr']<=a and a+n<=s['sh_addr']+s['sh_size']:return s.data()[a-s['sh_addr']:a-s['sh_addr']+n]
 raise AssertionError((p,a,n))
for a in d['anchors']:
 lo=int(a['va'],16);hi=int(a['endExclusive'],16)
 assert rd(ROOT/a['source'],lo,hi-lo).hex()==a['bytes'],a['meaning']
for t in d['textAnchors']:
 assert '\n'.join((ROOT/t['source']).read_text().splitlines()[t['firstLine']-1:t['lastLine']])==t['text']
def module(p,name):
 s=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
m=module(ROOT/d['captureBase']/'validity_models.py','battery_validity_models')
assert m.counterexamples()==d['arraysAndZero']['sourceDerivedCounterexamples']
# Exhaustive source-domain comparison: startup, metadata and callback are
# intentionally distinct contracts, not one invented validity predicate.
primary=[v for v in range(65536) if m.sync_primary_count(v)]
extended=[v for v in range(65536) if m.sync_extended_count(v)]
metadata=[v for v in range(65536) if m.metadata_count(v)]
callback=[v for v in range(65536) if m.callback_count(v)]
assert primary==[v for v in range(1,299) if v not in [254,255]]
assert extended==list(range(1,299)) and metadata==list(range(1,254))
assert callback==list(range(300)) and m.callback_count(-1)
for r in d['countsAndSupport']['countBoundaryCases']:
 n=r['value'];assert r['syncPrimaryAccept']==m.sync_primary_count(n) and r['syncExtendedAccept']==m.sync_extended_count(n) and r['pageMetadataAccept']==m.metadata_count(n) and r['callbackSignedAccept']==m.callback_count(n)
# All combinations of concrete policy inputs retain their causal ordering.
policy_cases=0
for cloud in [False,True]:
 for online in [0,1,2,255]:
  for monitor in [0,1,2,255]:
   for canfd in [False,True]:
    for meta in range(256):
     actual=m.hev_policy(cloud,online,monitor,canfd,meta)
     assert actual==8 if meta==255 else actual!=8
     enabled=cloud and online==1 and monitor!=0 and canfd and meta!=255
     assert (actual==1)==bool(enabled)
     policy_cases+=1
   for meta in range(256):
    actual=m.ev_policy(cloud,online,monitor,meta)
    assert actual==5 if meta!=240 else actual!=5
    assert (actual==1)==bool(cloud and online==1 and monitor!=0 and meta==240)
    policy_cases+=1
# Native text, rather than magnitude, identifies policy-reason meanings.
DATA=ROOT/'captures/firmware/ivi-34.1.33.2605218.1/system/system/bin/gbdataservice'
for r in d['nativeBMSPolicy']['reasons']:
 assert rd(DATA,int(r['stringVA'],16),180).split(b'\0',1)[0].decode()==r['textUTF8']
assert {r['code'] for r in d['nativeBMSPolicy']['reasons']}=={2,3,4,5,6,8}
# Source cache initialization + the actual copy/count contract admits a complete
# latch with never-acquired slots; no bad values are sent to firmware.
a=m.counterexamples()['duplicatePageZero'];assert a['complete'] and a['zeroCellSlotsWithinDeclaredCount']==163 and a['zeroProbeSlotsWithinDeclaredCount']==8
assert m.counterexamples()['sequentialCompletePages']['complete']
assert m.counterexamples()['sequentialCompletePages']['zeroCellSlotsWithinDeclaredCount']==0
assert m.counterexamples()['partialCountInitialization']=={'initialized':True,'cells':166,'probes':0}
# Both immutable completed owner captures qualify166/14, exact tuple and noFF
# in the metadata-policy byte. They do not reveal the current service property.
decoder=module(ROOT/'research/vehicle-diagnostics/decode_battery_pages.py','battery_page_decoder')
for r in d['countsAndSupport']['retainedSupport']:
 fs=decoder.parse_log(ROOT/r['source']['path']);meta=[f for f in fs if (f.can_id,f.sub,f.channel,len(f.payload))==(0x43a,0,2,8)]
 assert len(meta)==r['43ARecords']==20
 assert {(f.payload[0],f.payload[1]) for f in meta}=={(166,14)}
 assert [f.payload[4] for f in meta]==r['metadataByte5Values'] and not r['metadataFFCount']
# Verify enum declarations and the separate API identity, without inventing an
# array status or assuming the unlabelled contactor codes2/3 are invalid.
for r in d['humanReadableStatesOutsideArrays']['enumConstants']:
 line=(ROOT/r['source']).read_text().splitlines()[r['line']-1]
 assert re.search(r'\b'+re.escape(r['name'])+r' = '+str(r['value'])+r';',line)
assert len(d['humanReadableStatesOutsideArrays']['enumConstants'])==16
print(json.dumps({'valid':True,'pinnedSources':len(d['sources']),'nativeByteAnchors':len(d['anchors']),'textAnchors':len(d['textAnchors']),'countInputValuesChecked':65536,'policyInputCombinations':policy_cases,'retainedMetadataFrames':40,'nativePolicyReasonStrings':6,'separateStateEnumConstants':16,'duplicatePageZeroCells':163,'duplicatePageZeroProbes':8,'newCellFaultOrBalancingEnums':0,'vehicleOperations':0,'networkOperations':0}))
