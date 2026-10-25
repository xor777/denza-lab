"""Bounded native path serializer comparison on retained ORIGINAL phase1 inputs.

No StockFeed output is a reference. Full sender replay is explicitly unsupported:
phase1 snapshots have no consumed getter/clock or Handler execution transcript.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
from . import bench

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
JAVA=HERE/'Phase1NativeSerializerReplay.java'
TOPIC='1126084593287170'
RESOURCE_NAMES={'autonavi_car_result_share_driving','route_browser_fragment_distance_space','km','route_meter',
 'route_foot_navi_no_name_road','autonavi_car_result_share_enter'} | {'navi_action_'+x for x in (
 'turnleft','turnright','left_front','right_front','left_back','right_back','turn_left_back','enter_ring','leave_ring','along_rest','along_charge','along')}
LIMITS=[
 'Only the complete native path object inside route payload.data[0] is compared, byte for byte; no fields within that object are masked.',
 'Route envelopes, end POI/AVP, RouteType, wall timestamps, selection and all dynamic/lifecycle outputs are outside this bounded comparison.',
 'Phase1 path snapshots were recorded before processing; repeated consumed native getters, identities, semantic clocks and Handler order were not recorded.',
 'Full StockSender replay is unsupported from phase1. Existing capture gaps remain visible; this result cannot establish full sender equivalence.']


def original_resources(apk:Path,aapt:Path,locale:str)->dict[str,str]:
    text=subprocess.run([str(aapt),'dump','resources',str(apk)],check=True,capture_output=True,text=True,timeout=30).stdout
    values={};name=None
    for line in text.splitlines():
        match=re.match(r'\s*resource 0x[0-9a-f]+ string/(\S+)$',line)
        if match:name=match[1];continue
        match=re.match(r'\s*\(([^)]*)\) (".*")$',line)
        if name in RESOURCE_NAMES and match and match[1] in ('','en'):
            values.setdefault(name,{})[match[1]]=json.loads(match[2])
    result={name:(config.get('en',config.get('')) if locale=='EN' else config.get('')) for name,config in values.items()}
    if set(result)!=RESOURCE_NAMES or any(not isinstance(v,str) for v in result.values()):
        raise ValueError('missing exact original APK route string resources')
    return result


def route_path_bytes(row:dict)->str:
    data=bytes.fromhex(row['payloadHex'])
    def varint(pos):
        value=shift=0
        while pos<len(data) and shift<64:
            b=data[pos];pos+=1;value|=(b&127)<<shift
            if b<128:return value,pos
            shift+=7
        raise ValueError('truncated route protobuf')
    tag,p=varint(0);size,p=varint(p)
    if tag!=10 or p+size!=len(data):raise ValueError('unexpected original route wrapper')
    tag,p=varint(p);count,p=varint(p)
    if tag!=24 or count!=1:raise ValueError('only original single-path route packets supported')
    tag,p=varint(p);size,p=varint(p)
    if tag!=34 or p+size!=len(data):raise ValueError('unexpected original route body')
    detail=json.loads(data[p:].decode())
    if detail['length']!=1 or detail['currentIndex']!=0 or detail['pathCount']!=1:
        raise ValueError('phase1 native comparison does not reconstruct multipart route packets')
    payload=detail['payload'];message=json.loads(payload)
    if len(message['data'])!=1:raise ValueError('one original path output required')
    found=list(re.finditer(r'"data":\[',payload))
    if len(found)!=1:raise ValueError('unambiguous original path JSON span required')
    begin=found[0].end();_,end=json.JSONDecoder().raw_decode(payload,begin)
    return payload[begin:end]


def field_differences(a,b,path='$'):
    if isinstance(a,dict) and isinstance(b,dict):
        result=[]
        for key in sorted(a.keys()|b.keys()):
            if key not in a or key not in b:result.append(dict(path=path+'.'+key,original_present=key in a,computed_present=key in b))
            else:result.extend(field_differences(a[key],b[key],path+'.'+key))
        return result
    if isinstance(a,list) and isinstance(b,list):
        result=[] if len(a)==len(b) else [dict(path=path,original_length=len(a),computed_length=len(b))]
        for index,(x,y)in enumerate(zip(a,b)):result.extend(field_differences(x,y,path+'['+str(index)+']'))
        return result
    return [] if type(a)==type(b) and a==b else [dict(path=path,original=a,computed=b)]


def compare_paths(inputs:list[dict],reference:list[dict],computed:list[dict])->dict:
    routes=[r for r in inputs[1:] if r.get('event')=='route']
    packets=[r for r in reference[1:] if r.get('kind')=='fire' and r.get('topic')==TOPIC]
    if len(routes)!=len(packets) or len(computed)!=len(routes):raise ValueError('original input/route-output/computed counts differ; ordinal alignment cannot be guessed')
    comparisons=[]
    for row,packet,port in zip(routes,packets,computed):
        if port.get('input_seq')!=row['seq']:raise ValueError('computed route input identity/order differs')
        original=route_path_bytes(packet);actual=port['path_json']
        if str(json.loads(original)['pathID'])!=str(row['data']['path']['pathId']):raise ValueError('original route ordinal/native path identity differs')
        a,b=original.encode(),actual.encode();same=a==b
        first=next((i for i,(x,y) in enumerate(zip(a,b)) if x!=y),min(len(a),len(b))) if not same else None
        comparisons.append(dict(input_seq=row['seq'],reference_seq=packet['seq'],path_id=row['data']['path']['pathId'],equal=same,
            original_bytes=len(a),computed_bytes=len(b),original_sha256=hashlib.sha256(a).hexdigest(),computed_sha256=hashlib.sha256(b).hexdigest(),first_differing_byte=first,
            field_differences=field_differences(json.loads(original),json.loads(actual))))
    if not comparisons:raise ValueError('no original route input/output pair')
    return dict(verdict='LIMITED',path_serialization_verdict='EQUAL' if all(r['equal'] for r in comparisons) else 'DIFFERENT',
        full_sender='UNSUPPORTED_PHASE1_MISSING_CONSUMED_READS',equivalenceEstablished=False,
        scope='NativeRouteSerializer path object output only',limits=LIMITS,comparisons=comparisons,
        counts=dict(route_inputs=len(routes),compared_original_route_outputs=len(packets),
                    original_other_reference_records=len(reference)-1-len(packets)))


def run(case:Path,out:Path,aapt:Path)->dict:
    inputs=bench.read_jsonl(case/'inputs.jsonl');reference=bench.read_jsonl(case/'reference.jsonl')
    bench.validate_inputs(inputs)
    source=bench.source(inputs[0]);manifest=json.loads((case/'capture-manifest.json').read_text())
    verified=bench.validate_artifacts(case/'capture-manifest.json',source)
    if bench.source(reference[0])!=source or reference[0].get('input_sha256')!=bench.digest(case/'inputs.jsonl'):
        raise ValueError('reference does not pin exact original input/source')
    for key,name in [('input_sha256','inputs.jsonl'),('reference_sha256','reference.jsonl')]:
        if manifest.get(key)!=bench.digest(case/name):raise ValueError('capture manifest '+key+' differs')
    apk=Path(next(a['path']for a in verified if a['role']=='apk'))
    strings=original_resources(apk,aapt,inputs[0]['locale'])
    routes=[dict(seq=r['seq'],path=r['data']['path'])for r in inputs[1:]if r.get('event')=='route']
    out.mkdir(parents=True,exist_ok=False)
    prepared=out/'native-input.json';prepared.write_text(json.dumps(dict(routes=routes,strings=strings),ensure_ascii=False))
    sources=sorted(p for p in (ROOT/'libraries/adas-link/src/main/java').rglob('*.java') if 'import android.'not in p.read_text())
    runtime=bench.jdk()
    with tempfile.TemporaryDirectory(prefix='phase1-native-')as build:
        subprocess.run([str(runtime/'javac'),'--release','8','-encoding','UTF-8','-nowarn','-d',build,*map(str,sources),str(JAVA)],check=True,capture_output=True,text=True,timeout=60)
        result=subprocess.run([str(runtime/'java'),'-cp',build,'dev.denza.adaslink.stocknative.Phase1NativeSerializerReplay',str(prepared)],check=True,capture_output=True,text=True,timeout=30)
    (out/'computed-paths.jsonl').write_text(result.stdout)
    computed=[json.loads(line)for line in result.stdout.splitlines()]
    report=compare_paths(inputs,reference,computed)
    report.update(input_path=str((case/'inputs.jsonl').resolve()),input_sha256=bench.digest(case/'inputs.jsonl'),
        reference_path=str((case/'reference.jsonl').resolve()),reference_sha256=bench.digest(case/'reference.jsonl'),
        original_capture_status=manifest.get('status'),original_capture_gaps=manifest.get('gaps',[]),verified_artifacts=verified,
        apk_resources=dict(apk_sha256=bench.digest(apk),locale=inputs[0]['locale'],aapt2_path=str(aapt.resolve()),aapt2_sha256=bench.digest(aapt),values=strings),
        source_hashes={str(p.relative_to(ROOT)):bench.digest(p)for p in sources+[JAVA,Path(__file__)]})
    (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case',type=Path,required=True);parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--aapt2',type=Path,required=True)
    args=parser.parse_args();report=run(args.case,args.out,args.aapt2)
    print(json.dumps(dict(verdict=report['verdict'],path_serialization_verdict=report['path_serialization_verdict'],counts=report['counts'],full_sender=report['full_sender'])))
if __name__=='__main__':main()
