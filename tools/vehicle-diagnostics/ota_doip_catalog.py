#!/usr/bin/env python3
"""One owner-authorized getEcuList, retaining ecuIp in the public projection.

The previously qualified TLS client and exporter lifecycle are copied verbatim
except for adding ecuIp to both projection field lists. No DoIP connection,
ECU call, versionSync, checkVersion, download or installation is performed.
"""
import argparse
import importlib.util
import ipaddress
import json
from pathlib import Path

import ota_remaining_field_read as qualified

ROOT=qualified.ROOT
SOURCE=ROOT/'tools/vehicle-diagnostics'
PINNED={
    'EcuMapMetadataProbe.java':'4b140b1fd1d8540ea253e000d1d65b896d4ee575009b7935221d824890a4f0cf',
    'run_ecu_map_metadata.py':'8dd71f394a0cd9e263fb748122335528a75c652e644b214719be5087aa891b92',
    'EcuMapMetadataProbeTest.java':'162c1e0354da5d6a22f63e9b58ce302c42754659c73feace0f4036418d1da775',
    'test_run_ecu_map_metadata.py':'7793596e52e35527a591b329ae58ca903c2be697de46350911c0cc272a01912a',
}


def load(path,name):
    spec=importlib.util.spec_from_file_location(name,path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module


def build(output):
    module=qualified.baseline();output.mkdir(parents=True,exist_ok=False,mode=0o700)
    source=output/'source';source.mkdir();classes=output/'classes';classes.mkdir()
    for name,digest in PINNED.items():
        path=SOURCE/name
        if qualified.sha(path)!=digest:raise ValueError('Qualified metadata source changed')
        text=path.read_text()
        if name in ('EcuMapMetadataProbe.java','run_ecu_map_metadata.py'):
            anchor='"logicAddress", "ecuDId", "ecuHardDid", "ecuConfigDid", "seq"'
            if text.count(anchor)!=1:raise ValueError('Projection anchor changed')
            text=text.replace(anchor,anchor+', "ecuIp"')
        (source/name).write_text(text)
    extra=source/'EcuMapDoipProjectionTest.java'
    extra.write_text('''import java.nio.charset.StandardCharsets;
import java.util.*;
public class EcuMapDoipProjectionTest {
 public static void main(String[] args) throws Exception {
  String text="{\\"status\\":\\"100\\",\\"ecuList\\":[{\\"ecuId\\":\\"HTH-2021_TEST\\",\\"ecuReprogramType\\":\\"ethernet\\",\\"logicAddress\\":\\"0010\\",\\"ecuIp\\":\\"192.168.195.1\\",\\"private_extra\\":\\"DROP\\"}]}";
  Map<?,?> row=(Map<?,?>)EcuMapMetadataProbe.projectResponse(text.getBytes(StandardCharsets.UTF_8)).get(0);
  if(!"192.168.195.1".equals(row.get("ecuIp"))||row.containsKey("private_extra"))throw new AssertionError("projection");
  System.out.println("PASS exact ecuIp projection; unrelated input discarded");
 }
}
''')
    module.local([module.JDK/'javac','--release','8','-d',classes,*sorted(source.glob('*.java'))],output/'javac.txt')
    module.local([module.JDK/'java','-cp',classes,'EcuMapMetadataProbeTest'],output/'java-tests.txt')
    module.local([module.JDK/'java','-cp',classes,'EcuMapDoipProjectionTest'],output/'projection-test.txt')
    module.local(['python3','-B','-m','unittest','discover','-s',source,'-p','test_run_ecu_map_metadata.py'],output/'host-tests.txt')
    sdk=module.SDK;dex=output/'dex';dex.mkdir()
    production=[p for p in sorted(classes.glob('EcuMapMetadataProbe*.class')) if 'Test' not in p.name]
    module.local([sdk/'build-tools/35.0.0/d8','--release','--min-api','33','--lib',module.ANDROID,'--output',dex,*production],output/'d8.txt')
    (output/'catalog-reader.py').write_bytes(Path(__file__).read_bytes())
    health=ROOT/'captures/ota-core-fields-20261021/build-v2/health.py'
    if qualified.sha(health)!=module.HEALTH_SHA:raise ValueError('Health snapshot changed')
    (output/'health.py').write_bytes(health.read_bytes())
    manifest={'sources':[{'path':str(p.relative_to(output)),'sha256':qualified.sha(p)} for p in
                         [*sorted(source.iterdir()),output/'catalog-reader.py',output/'health.py']],
              'upstreamSources':PINNED,'artifactSha256':qualified.sha(dex/'classes.dex'),
              'plannerSha256':qualified.sha(Path(__file__)),'transformations':['Add ecuIp to Java FIELDS and host ECU_FIELDS only.'],
              'hypothesis':'Ethernet candidate routes were incomplete because the earlier projection omitted ecuIp.',
              'expected':'One getEcuList response supplies or explicitly omits the IP fields for the same ECU candidates.',
              'falsification':'Changed identity/route map or missing IP stops any proposed DoIP read; no address is guessed.',
              'cloudRequestLimit':1,'ecuCalls':0,'apkInstalls':0,'downloadOrInstall':False}
    module.dump(output/'build.json',manifest)
    print(json.dumps({'built':str(output),'artifactSha256':manifest['artifactSha256'],'cloudRequestLimit':1,
                      'tests':[(output/n).read_text().strip() for n in ['java-tests.txt','projection-test.txt','host-tests.txt']]}))


def run(build_dir,output):
    manifest=json.loads((build_dir/'build.json').read_text())
    if manifest['plannerSha256']!=qualified.sha(Path(__file__)):raise ValueError('Catalog planner changed')
    for row in manifest['sources']:
        if qualified.sha(build_dir/row['path'])!=row['sha256']:raise ValueError('Build source changed')
    artifact=build_dir/'dex/classes.dex'
    if qualified.sha(artifact)!=manifest['artifactSha256']:raise ValueError('Artifact changed')
    output.mkdir(parents=True,exist_ok=False,mode=0o700)
    module=qualified.baseline();health=load(build_dir/'health.py','catalog_health')
    before=health.health('127.0.0.1:5555',output/'preflight')
    if not health.acceptable(before):raise ValueError('Preflight refused')
    pids=module.processes(output/'preflight')
    runner=load(build_dir/'source/run_ecu_map_metadata.py','catalog_metadata_runner')
    try:
        rc=runner.main(['--run-acknowledged','--artifact',str(artifact),'--artifact-sha256',manifest['artifactSha256'],
                        '--helper-source-sha256',qualified.sha(build_dir/'source/EcuMapMetadataProbe.java'),
                        '--orchestrator-sha256',qualified.sha(build_dir/'source/run_ecu_map_metadata.py'),
                        '--output',str(output/'session')])
    finally:
        after=health.health('127.0.0.1:5555',output/'postflight')
        stable=health.acceptable(after) and before['appSha256']==after['appSha256'] and pids==module.processes(output/'postflight')
        module.dump(output/'health-comparison.json',{'stable':stable,'cloudRequestLimit':1,'ecuCalls':0})
    if rc or not stable:raise SystemExit(2)
    session=json.loads((output/'session/session.json').read_text());rows=session['result']['ecus']
    selected=[]
    for row in rows:
        if row['route']['kind']!='ethernet':continue
        ip=row.get('ecuIp');valid=False
        if ip:
            try:valid=ipaddress.ip_address(ip).version==4
            except ValueError:pass
        selected.append({k:row.get(k) for k in ['ecuId','ecuIp','ecuDiaId','logicAddress','ecuDId','ecuHardDid','ecuConfigDid','route']}|{'validIpv4':valid})
    module.dump(output/'ethernet-routes.json',selected)
    print(json.dumps({'catalogRecords':len(rows),'ethernetRecords':len(selected),'withValidIpv4':sum(r['validIpv4'] for r in selected),'healthStable':stable}))


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['build','run']);p.add_argument('--output',type=Path,required=True);p.add_argument('--build',type=Path)
    args=p.parse_args()
    if args.action=='build':build(args.output.resolve())
    elif args.build:run(args.build.resolve(),args.output.resolve())
    else:p.error('--build required')


if __name__=='__main__':main()
