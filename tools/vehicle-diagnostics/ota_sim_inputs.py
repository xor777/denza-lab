#!/usr/bin/env python3
"""Bounded zero-network SIM state snapshot with privacy-safe projections."""
import argparse
import inspect
import json
from pathlib import Path
import secrets
import shlex
import importlib.util

import ota_doip_catalog as catalog
import ota_media_field_read as media

SOURCE=catalog.SOURCE


def validate(values):
    fields={'defaultSubscriptionId','defaultDataSubscriptionId','defaultVoiceSubscriptionId',
            'simState','phoneSubInfoServicePresent','rilIccid','defaultIccid','gsmSimState','activeSubscriptions','telephonyBootstrapWasMissing'}
    assert values['outcome'] in ['complete','partial']
    if values['outcome']=='complete':assert set(values)==fields|{'outcome'}
    else:
        assert set(values)<=fields|{'outcome','errorStage','errorKind'}
        assert values['errorStage'] in fields|{'guard','context','phone','subscriptions'}
        assert values['errorKind'] in ['permission','null','reflection','linkage','other']
    assert all(type(values[k]) is int for k in ['defaultSubscriptionId','defaultDataSubscriptionId','defaultVoiceSubscriptionId','simState'] if k in values)
    if 'phoneSubInfoServicePresent' in values:assert type(values['phoneSubInfoServicePresent']) is bool
    if 'telephonyBootstrapWasMissing' in values:assert type(values['telephonyBootstrapWasMissing']) is bool
    if 'gsmSimState' in values:assert isinstance(values['gsmSimState'],str) and len(values['gsmSimState'])<=64
    if 'activeSubscriptions' in values:assert isinstance(values['activeSubscriptions'],list) and len(values['activeSubscriptions'])<=4
    for row in [*[values[k] for k in ['rilIccid','defaultIccid'] if k in values],*values.get('activeSubscriptions',[])]:
        assert set(row) in [{'isNull','length','decimal19To22','matchesRilProperty'},
                            {'isNull','length','decimal19To22','matchesRilProperty','subscriptionId'}]
        assert type(row['length']) is int and 0<=row['length']<=256
        assert all(type(row[k]) is bool for k in ['isNull','decimal19To22','matchesRilProperty'])
        if 'subscriptionId' in row: assert type(row['subscriptionId']) is int


def build(output):
    module=catalog.qualified.baseline();output.mkdir(parents=True,exist_ok=False,mode=0o700)
    source=output/'source';source.mkdir();classes=output/'classes';classes.mkdir();inputs=output/'inputs';inputs.mkdir()
    for name in ['OtaSimInputs.java','EcuMapMetadataProbe.java','OtaTelephonyBootstrap.java']:
        (source/name).write_bytes((SOURCE/name).read_bytes())
    assert catalog.qualified.sha(source/'EcuMapMetadataProbe.java')==catalog.PINNED['EcuMapMetadataProbe.java']
    module.local([module.JDK/'javac','--release','8','-cp',module.ANDROID,'-d',classes,*sorted(source.glob('*.java'))],output/'javac.txt')
    jar=inputs/'inputs.jar'
    module.local([module.SDK/'build-tools/35.0.0/d8','--release','--min-api','33','--lib',module.ANDROID,'--output',jar,*sorted(classes.glob('*.class'))],output/'d8.txt')
    health=catalog.ROOT/'captures/ota-core-fields-20261021/build-v2/health.py'
    assert catalog.qualified.sha(health)==module.HEALTH_SHA
    (output/'health.py').write_bytes(health.read_bytes())
    owner=inspect.getsource(media.inputs_snapshot).replace('denza_ota_media_','denza_ota_sim_').replace('DENZA_OTA_MEDIA_INPUTS:','DENZA_OTA_SIM_INPUTS:')
    (output/'snapshot-owner.py').write_text(owner)
    (output/'sim-reader.py').write_bytes(Path(__file__).read_bytes())
    manifest={'sources':[{'path':str(p.relative_to(output)),'sha256':catalog.qualified.sha(p)} for p in
                         [*sorted(source.iterdir()),output/'health.py',output/'snapshot-owner.py',output/'sim-reader.py',jar]],
              'plannerSha256':catalog.qualified.sha(Path(__file__)),'mediaOwnerSha256':catalog.qualified.sha(Path(media.__file__)),
              'inputsJarSha256':catalog.qualified.sha(jar),'cloudRequests':0,'ecuCalls':0,
              'hypothesis':'The default Android subscription has no available ICCID, which stops stock OTA login.',
              'expected':'Read default and active subscriptions plus ICCID presence, without retaining any ICCID.',
              'falsification':'A usable default ICCID would contradict the previous getter observation.'}
    module.dump(output/'build.json',manifest)


def run(build_dir,output):
    manifest=json.loads((build_dir/'build.json').read_text())
    assert manifest['plannerSha256']==catalog.qualified.sha(Path(__file__))
    assert manifest['mediaOwnerSha256']==catalog.qualified.sha(Path(media.__file__))
    for row in manifest['sources']:assert catalog.qualified.sha(build_dir/row['path'])==row['sha256']
    source=inspect.getsource(media.inputs_snapshot).replace('denza_ota_media_','denza_ota_sim_').replace('DENZA_OTA_MEDIA_INPUTS:','DENZA_OTA_SIM_INPUTS:')
    assert source==(build_dir/'snapshot-owner.py').read_text()
    env={'qualified':catalog.qualified,'secrets':secrets,'shlex':shlex,'importlib':__import__('importlib'),
         'json':json,'MAIN':'OtaSimInputs','validate_inputs':validate}
    exec(compile(source,str(build_dir/'snapshot-owner.py'),'exec'),env)
    values=env['inputs_snapshot'](catalog.qualified.baseline(),build_dir,output)
    print(json.dumps(values))


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['build','run']);p.add_argument('--output',type=Path,required=True);p.add_argument('--build',type=Path)
    args=p.parse_args()
    if args.action=='build':build(args.output.resolve())
    elif args.build:run(args.build.resolve(),args.output.resolve())
    else:p.error('--build required')


if __name__=='__main__':main()
