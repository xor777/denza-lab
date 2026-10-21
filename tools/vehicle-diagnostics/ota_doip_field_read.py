#!/usr/bin/env python3
"""Fixed read-only DoIP identity sessions using server-confirmed routes."""
import argparse
import inspect
import importlib.util
import json
from pathlib import Path
import re
import secrets
import shlex

import ota_remaining_field_read as qualified
import ota_media_field_read as media

ROOT=qualified.ROOT
SOURCE=ROOT/'tools/vehicle-diagnostics'
CATALOG=ROOT/'captures/ota-login-catalog-20261021/read-v6/ethernet-routes.json'
PRIOR=ROOT/'captures/adas-speed-20261018/reports/ads-direct-read'
GROUPS=[('adc','192.168.195.3',0x71a,[0xf194,0xf195]),
        ('cdc','192.168.195.11',0x10,[0xf194,0xf195,0xf294,0xf295]),
        ('lidar-left','192.168.195.5',0x78c,[0xf194,0xf195]),
        ('lidar-right','192.168.195.7',0x73e,[0xf194,0xf195]),
        ('lidar-middle','192.168.195.16',0x75f,[0xf194,0xf195])]


def plans(module):
    evidence=json.loads((ROOT/'research/vehicle-diagnostics/ota-login-catalog-20261021.json').read_text())
    assert qualified.sha(CATALOG)==evidence['ethernetRoutes']['sha256']
    assert qualified.sha(module.CACHE)==module.CACHE_SHA
    routes=json.loads(CATALOG.read_text());cache={r['ecuId']:r for r in json.loads(module.CACHE.read_text())['ecuInfo']}
    result={};represented=set()
    for name,ip,target,dids in GROUPS:
        selected=[r for r in routes if r['ecuIp']==ip and int(r['logicAddress'],16)==target]
        assert selected and all(r['route']['pairClient']==1 for r in selected)
        steps=[]
        for did in dids:
            field='ecuSoftCode' if did in [0xf194,0xf294] else 'ecuSoftVer'
            aliases=[r['ecuId'] for r in selected if int(r['ecuDId'].split('/')[1 if field=='ecuSoftCode' else 0],16)==did]
            assert aliases;values={cache[ecu][field] for ecu in aliases};assert len(values)==1
            represented.update(aliases)
            steps.append({'did':f'{did:04X}','field':field,'ecuIds':aliases,'cached':values.pop()})
        result[name]={'ip':ip,'target':f'{target:04X}','steps':steps}
    assert len(represented)==8 and sum(len(p['steps']) for p in result.values())==12
    return result


def frame(target,did):
    return '02FD8001000000070001'+target+'22'+did


def validate(value,plan,name):
    assert set(value)<={'group','ip','target','routingAccepted','routingServer','outcome','error','socketClosed','sent','exchanges'}
    assert value['outcome'] in ['complete','stopped'] and value['socketClosed'] is True
    if 'group' in value:assert (value['group'],value['ip'],value['target'])==(name,plan['ip'],plan['target'])
    else:assert value['outcome']=='stopped' and not value['sent']
    if 'error' in value:assert value['error'] in {'scope_or_lock','guard_transaction','guard_shape','profile_or_manager','not_stationary','other_doip_session',
        'deadline','receive_eof','receive_header','receive_length','routing_response','routing_denied','foreign_response','diagnostic_ack',
        'diagnostic_nack','receive_type','uds_response','duplicate_ack','uds_negative','response_frame_limit','socket_timeout','connect_refused','bind_failed','route_unavailable','operation_failed'}
    expected=['02FD00050000000B0001000000000000000000']+[frame(plan['target'],s['did']) for s in plan['steps']]
    assert value['sent']==expected[:len(value['sent'])]
    assert len(value['exchanges'])<=len(plan['steps'])
    if value['outcome']=='complete':assert value['sent']==expected and len(value['exchanges'])==len(plan['steps'])
    for step,row in zip(plan['steps'],value['exchanges']):
        assert set(row)<={'did','ack','outcome','uds','responsePending'} and row['did']==step['did']
        if 'ack' in row:assert type(row['ack']) is bool
        if 'responsePending' in row:assert row['responsePending'] is True
        if 'uds' in row:
            assert isinstance(row['uds'],str) and re.fullmatch('[0-9A-F]{6,134}',row['uds']) and len(row['uds'])%2==0
            assert row['outcome'] in ['positive','negative']
            assert row['uds'].startswith('62'+step['did']) if row['outcome']=='positive' else re.fullmatch('7F22[0-9A-F]{2}',row['uds'])


def compare(value,plan):
    values=[]
    for step,row in zip(plan['steps'],value['exchanges']):
        record=dict(step,observation=row)
        if row.get('outcome')=='positive':
            data=bytes.fromhex(row['uds'])[3:];field=step['field'];parsed=None
            if field=='ecuSoftCode' and len(data)>=9 and any(data[:9]):parsed=data[:9].hex().upper()
            if field=='ecuSoftVer' and len(data)>=6:
                text=f'{int.from_bytes(data[:2],"big"):05d}';parsed=text[:1]+'.'+text[1:3]+'.'+text[3:]
            record.update(stockParseValid=parsed is not None,value=parsed,cacheMatch=parsed==step['cached'] if parsed is not None else None)
        values.append(record)
    return values


def owner_source():
    return inspect.getsource(media.inputs_snapshot).replace('denza_ota_media_','denza_ota_doip_').replace('DENZA_OTA_MEDIA_INPUTS:','DENZA_OTA_DOIP_READ:')


def build(output):
    module=qualified.baseline();fixed=plans(module);output.mkdir(parents=True,exist_ok=False,mode=0o700)
    source=output/'source';source.mkdir();classes=output/'classes';classes.mkdir();inputs=output/'inputs';inputs.mkdir()
    for name in ['OtaDoipWire.java','OtaDoipWireTest.java','OtaDoipReadProbe.java']:(source/name).write_bytes((SOURCE/name).read_bytes())
    rows=[]
    for index,(name,ip,target,dids) in enumerate(GROUPS):
        rows.append('new Group('+json.dumps(name)+','+json.dumps(ip)+','+str(target)+',new int[]{'+','.join(map(str,dids))+'})')
        (source/f'OtaDoipEntry{index}.java').write_text(f'public class OtaDoipEntry{index} {{ public static void main(String[] args) {{ OtaDoipReadProbe.run({index},args); }} }}\n')
    (source/'OtaDoipPlan.java').write_text('public final class OtaDoipPlan { static class Group { final String name,ip; final int target; final int[] dids; Group(String n,String p,int t,int[] d){name=n;ip=p;target=t;dids=d;} } static final Group[] GROUPS={'+','.join(rows)+'}; }\n')
    module.local([module.JDK/'javac','--release','8','-cp',module.ANDROID,'-d',classes,*sorted(source.glob('*.java'))],output/'javac.txt')
    module.local([module.JDK/'java','-cp',classes,'OtaDoipWireTest'],output/'wire-tests.txt')
    jar=inputs/'inputs.jar';production=[p for p in sorted(classes.glob('*.class')) if 'Test' not in p.name]
    module.local([module.SDK/'build-tools/35.0.0/d8','--release','--min-api','33','--lib',module.ANDROID,'--output',jar,*production],output/'d8.txt')
    health=ROOT/'captures/ota-core-fields-20261021/build-v2/health.py';assert qualified.sha(health)==module.HEALTH_SHA
    (output/'health.py').write_bytes(health.read_bytes());(output/'snapshot-owner.py').write_text(owner_source());(output/'doip-reader.py').write_bytes(Path(__file__).read_bytes())
    manifest={'sources':[{'path':str(p.relative_to(output)),'sha256':qualified.sha(p)} for p in [*sorted(source.iterdir()),output/'health.py',output/'snapshot-owner.py',output/'doip-reader.py',jar]],
              'plannerSha256':qualified.sha(Path(__file__)),'mediaOwnerSha256':qualified.sha(Path(media.__file__)),
              'inputsJarSha256':qualified.sha(jar),'plans':fixed,'cloudRequests':0,'connectionsPerRun':1,'requestLimitPerRun':4,
              'globalDeadlineSeconds':18,'hardProcessDeadlineSeconds':20,'responseDeadlineMillis':2500,
              'hypothesis':'Fresh DoIP software identity fields differ from cached fields used by OTA.',
              'expected':'Positive fixed DID replies can be compared with cached values; a refusal stops without session changes.',
              'falsification':'Matching values disprove staleness for those measured fields only; a negative/timeout does not prove ECU absence.',
              'excluded':['UDS10','UDS27','UDS3E','ECU write/reset/clear','native UDS service lifecycle','broadcast or guessed addresses','retry','update/download']}
    module.dump(output/'build.json',manifest);print((output/'wire-tests.txt').read_text().strip())


def run(build_dir,output,name):
    module=qualified.baseline();manifest=json.loads((build_dir/'build.json').read_text())
    assert manifest['plannerSha256']==qualified.sha(Path(__file__)) and manifest['plans']==plans(module)
    assert manifest['mediaOwnerSha256']==qualified.sha(Path(media.__file__))
    for row in manifest['sources']:assert qualified.sha(build_dir/row['path'])==row['sha256']
    assert owner_source()==(build_dir/'snapshot-owner.py').read_text()
    group=manifest['plans'][name];index=[g[0] for g in GROUPS].index(name)
    env={'qualified':qualified,'secrets':secrets,'shlex':shlex,'importlib':__import__('importlib'),'json':json,
         'MAIN':f'OtaDoipEntry{index}','validate_inputs':lambda value:validate(value,group,name)}
    exec(compile(owner_source(),str(build_dir/'snapshot-owner.py'),'exec'),env)
    result=env['inputs_snapshot'](module,build_dir,output);values=compare(result,group)
    module.dump(output/'comparison.json',{'group':name,'outcome':result['outcome'],'error':result.get('error'),'values':values,
                                       'cloudRequests':0,'newEcuRequestCount':max(0,len(result['sent'])-1)})
    print(json.dumps({'group':name,'outcome':result['outcome'],'error':result.get('error'),'values':values}))


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['build','run']);p.add_argument('--output',type=Path,required=True);p.add_argument('--build',type=Path);p.add_argument('--group',choices=[g[0] for g in GROUPS])
    args=p.parse_args()
    if args.action=='build':build(args.output.resolve())
    elif args.build and args.group:run(args.build.resolve(),args.output.resolve(),args.group)
    else:p.error('--build and --group required')


if __name__=='__main__':main()
