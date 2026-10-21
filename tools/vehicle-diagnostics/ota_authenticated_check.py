#!/usr/bin/env python3
"""One in-memory login and one GCY checkVersion with unchanged retained ECU fields."""
import argparse
import json
from pathlib import Path
import secrets

import ota_login_catalog as login

catalog=login.catalog
exact=login.exact
PINNED={**catalog.PINNED,
    'OtaCheckVersionProbe.java':'c43b8fd2e6fa1e165e38374b3ba85a80e1ed4fc5e715b81b094c3ad937a6dd4e',
    'OtaCheckVersionProbeTest.java':'b1ff02b20e2109d0c90910b64ea7c0e0cfbe1243a125da87b2d6c903b02648a6',
    'run_ota_check_metadata.py':'d6baae01c25b963a4188d43f6740cae613bb88b1074b0ceff82020ed0a3e82c6',
    'test_run_ota_check_metadata.py':'d0f5ef409eefed961e6d9ddab50853e32f0fb05e353e25b3184bdcaa175263f0'}
POST='    static byte[] post(EcuMapMetadataProbe.Inputs inputs,SSLContext context,byte[] body,long end) throws Exception {'
CHECK_CHANGES=[
    (POST, '    static void authorizeRequest() throws Exception {\n        require(OtaLoginProtocol.requests==1 && !OtaLoginProtocol.loginRequest,"login_request_order");\n        OtaLoginProtocol.requests++;\n    }\n'+POST+'\n        authorizeRequest();'),
    ('EcuMapMetadataProbe.stage="trust";SSLContext context=EcuMapMetadataProbe.tlsContext(inputs);',
     'EcuMapMetadataProbe.stage="trust";SSLContext context=EcuMapMetadataProbe.tlsContext(inputs);\n            EcuMapMetadataProbe.stage="login_inputs";OtaLoginProtocol.identity=OtaLoginInputs.read();OtaLoginProtocol.oldToken=inputs.token;'),
    ('privacy(snapshot,inputs);','privacy(snapshot,inputs);OtaLoginProtocol.privacy(snapshot);'),
    ('EcuMapMetadataProbe.stage="https";byte[] body=body(inputs,cached);',
     'EcuMapMetadataProbe.stage="https_login";inputs=OtaLoginProtocol.login(inputs,context,end);\n            EcuMapMetadataProbe.stage="https_check";byte[] body=body(inputs,cached);'),
    ('privacy(result,inputs);EcuMapMetadataProbe.remaining(end);','privacy(result,inputs);OtaLoginProtocol.privacy(result);EcuMapMetadataProbe.remaining(end);'),
]


def derived(name,text):
    if name=='EcuMapMetadataProbe.java':
        text=exact.transform(text,exact.JAVA_OLD,exact.JAVA_NEW)
        text=exact.transform(text,'byte[] request=httpRequest(inputs);','byte[] request=OtaLoginProtocol.request(inputs);')
    if name=='run_ecu_map_metadata.py':
        text=exact.transform(text,exact.HOST_OLD,exact.HOST_NEW)
        for old,new in login.HOST_CHANGES:text=exact.transform(text,old,new)
    if name=='test_run_ecu_map_metadata.py':text=exact.transform(text,login.TEST_OLD,login.TEST_NEW)
    if name=='OtaCheckVersionProbe.java':
        for old,new in CHECK_CHANGES:text=exact.transform(text,old,new)
    if name=='run_ota_check_metadata.py':
        text=exact.transform(text,'STAGES = base.HELPER_STAGES | {"input_cache", "input_plans"}',
                             'STAGES = base.HELPER_STAGES | {"input_cache", "input_plans", "https_check"}')
    return text


def build(output):
    module=catalog.qualified.baseline();output.mkdir(parents=True,exist_ok=False,mode=0o700)
    source=output/'source';source.mkdir();classes=output/'classes';classes.mkdir();dex=output/'dex';dex.mkdir()
    for name,digest in PINNED.items():
        path=catalog.SOURCE/name
        if catalog.qualified.sha(path)!=digest:raise ValueError('Qualified input changed: '+name)
        (source/name).write_text(derived(name,path.read_text()))
    for name in ['OtaLoginInputs.java','OtaLoginProtocol.java','OtaLoginProtocolTest.java','OtaTelephonyBootstrap.java']:
        prior=catalog.ROOT/'captures/ota-login-catalog-20261021/build-v6/source'/name
        if prior.read_bytes()!=(catalog.SOURCE/name).read_bytes():raise ValueError('Qualified login source changed')
        (source/name).write_bytes(prior.read_bytes())
    (source/'OtaAuthenticatedCheckTest.java').write_text('''import java.nio.charset.StandardCharsets;
public class OtaAuthenticatedCheckTest {
 interface Action {void run() throws Exception;}
 static void reject(Action a) throws Exception {try{a.run();throw new AssertionError();}catch(EcuMapMetadataProbe.Failure expected){}}
 public static void main(String[] args) throws Exception {
  OtaLoginProtocol.identity=new OtaLoginProtocol.Identity("TEST1234567890001","12345678901234567890","86");
  EcuMapMetadataProbe.Inputs old=new EcuMapMetadataProbe.Inputs(null,null,"SYNTHETIC_OLD_TOKEN","","V1.7.0");
  reject(()->OtaCheckVersionProbe.authorizeRequest());
  OtaLoginProtocol.loginRequest=true;OtaLoginProtocol.request(old);
  reject(()->OtaCheckVersionProbe.authorizeRequest());
  OtaLoginProtocol.loginRequest=false;
  EcuMapMetadataProbe.Inputs fresh=OtaLoginProtocol.parse("{\\"status\\":\\"100\\",\\"token\\":\\"SYNTHETIC_NEW_TOKEN\\"}".getBytes(StandardCharsets.UTF_8),old);
  OtaCheckVersionProbe.authorizeRequest();
  if(OtaLoginProtocol.requests!=2)throw new AssertionError();
  String request=new String(OtaCheckVersionProbe.httpRequest(fresh,"{}".getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8);
  if(!request.startsWith("POST /ota/otaService/checkVersion ")||!request.contains("Authorization: basicSYNTHETIC_NEW_TOKEN")||request.contains(old.token))throw new AssertionError();
  reject(()->OtaCheckVersionProbe.authorizeRequest());
  reject(()->OtaLoginProtocol.request(fresh));
  System.out.println("PASS login-before-check, fresh token and two-request ceiling; sockets=0");
 }
}
''')
    module.local([module.JDK/'javac','--release','8','-cp',module.ANDROID,'-d',classes,*sorted(source.glob('*.java'))],output/'javac.txt')
    for test in ['EcuMapMetadataProbeTest','OtaCheckVersionProbeTest','OtaLoginProtocolTest','OtaAuthenticatedCheckTest']:
        args=[module.JDK/'java','-cp',classes,test]
        if test=='OtaCheckVersionProbeTest':args.append(module.CACHE)
        module.local(args,output/(test+'.txt'))
    module.local(['python3','-B','-m','unittest','discover','-s',source,'-p','test*.py'],output/'host-tests.txt')
    production=[p for p in sorted(classes.glob('*.class')) if 'Test' not in p.name]
    module.local([module.SDK/'build-tools/35.0.0/d8','--release','--min-api','33','--lib',module.ANDROID,'--output',dex,*production],output/'d8.txt')
    health=catalog.ROOT/'captures/ota-core-fields-20261021/build-v2/health.py';assert catalog.qualified.sha(health)==module.HEALTH_SHA
    (output/'health.py').write_bytes(health.read_bytes());(output/'authenticated-check.py').write_bytes(Path(__file__).read_bytes())
    manifest={'sources':[{'path':str(p.relative_to(output)),'sha256':catalog.qualified.sha(p)} for p in
                         [*sorted(source.iterdir()),output/'health.py',output/'authenticated-check.py']],
              'plannerSha256':catalog.qualified.sha(Path(__file__)),'artifactSha256':catalog.qualified.sha(dex/'classes.dex'),
              'upstreamSources':PINNED,'cloudRequestLimit':2,'ecuCalls':0,'nativeOtaInvoked':False,'registration':False,
              'cacheSha256':module.CACHE_SHA,'ecuFieldsChanged':False,'tokenPersistence':False,
              'hypothesis':'Fresh authentication may yield a different checkVersion strategy than the earlier token.',
              'expected':'One loginIn then one checkVersion with exactly the retained six-field ECU list.',
              'falsification':'The same111 response rules out fresh authentication alone as a way to obtain the plan.',
              'totalNetworkDeadlineSeconds':25,'downloadOrInstall':False}
    module.dump(output/'build.json',manifest)
    print(json.dumps({'artifactSha256':manifest['artifactSha256'],'tests':[(output/n).read_text().strip() for n in
                    ['OtaCheckVersionProbeTest.txt','OtaAuthenticatedCheckTest.txt','host-tests.txt']]}))


def run(build_dir,output):
    manifest=json.loads((build_dir/'build.json').read_text());module=catalog.qualified.baseline()
    assert manifest['plannerSha256']==catalog.qualified.sha(Path(__file__))
    for row in manifest['sources']:assert catalog.qualified.sha(build_dir/row['path'])==row['sha256']
    artifact=build_dir/'dex/classes.dex';assert catalog.qualified.sha(artifact)==manifest['artifactSha256']
    output.mkdir(parents=True,exist_ok=False,mode=0o700)
    health=catalog.load(build_dir/'health.py','authenticated_check_health')
    before=health.health('127.0.0.1:5555',output/'preflight');assert health.acceptable(before)
    pids=module.processes(output/'preflight');runner=catalog.load(build_dir/'source/run_ota_check_metadata.py','authenticated_check_owner')
    def snapshot(value):
        runner.snapshot(value)
        if value['cache']['sha256']!=manifest['cacheSha256']:raise runner.base.Refused('cache_changed')
    try:
        state=runner.run(runner.Device(),artifact,manifest['artifactSha256'],secrets.token_hex(16),
                         lambda value:runner.write_json(output/'snapshot-before-request.json',value),snapshot_validator=snapshot)
        state.update(artifactSha256=manifest['artifactSha256'],cloudRequestLimit=2,ecuCalls=0)
        runner.write_json(output/'session.json',state)
    finally:
        after=health.health('127.0.0.1:5555',output/'postflight')
        stable=health.acceptable(after) and before['appSha256']==after['appSha256'] and pids==module.processes(output/'postflight')
        module.dump(output/'health-comparison.json',{'stable':stable,'cloudRequestLimit':2,'ecuCalls':0})
    print(json.dumps({k:state.get(k) for k in ['status','goSent','exportClosed','leaseReleased','error','helperError','response']}))
    if not stable or state['status']!='complete':raise SystemExit(2)


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['build','run']);p.add_argument('--output',type=Path,required=True);p.add_argument('--build',type=Path)
    args=p.parse_args()
    if args.action=='build':build(args.output.resolve())
    elif args.build:run(args.build.resolve(),args.output.resolve())
    else:p.error('--build required')


if __name__=='__main__':main()
