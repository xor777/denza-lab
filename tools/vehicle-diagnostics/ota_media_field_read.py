#!/usr/bin/env python3
"""Fixed Media code/hardware reads plus stock composition inputs, no OTA lifecycle."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import secrets
import shlex

import ota_remaining_field_read as qualified

ROOT=qualified.ROOT
STOCK=ROOT/'captures/firmware/ivi-34.1.33.2605218.1/jadx/OTAUpdatePlatformizedUI'
MAPPING=STOCK/'resources/res/raw/media_softcode_mapping.json'
MAPPING_SHA='0099acf1425c3bffd86ac769082b56bdca3c2a8cb807a23777bfa41d0e020dc9'
GETTER=ROOT/'tools/vehicle-diagnostics/OtaMediaInputs.java'
MAIN='dev.denza.diagnostics.probe.OtaMediaInputs'


def plan(module):
    for path,expected in [(module.MAP,module.MAP_SHA),(module.CACHE,module.CACHE_SHA),(MAPPING,MAPPING_SHA)]:
        if qualified.sha(path)!=expected:raise ValueError('Pinned input changed')
    ecu='HTH-2021_Media_BYD_00200001'
    row=next(r for r in json.loads(module.MAP.read_text())['ecus'] if r['ecuId']==ecu)
    cache=next(r for r in json.loads(module.CACHE.read_text())['ecuInfo'] if r['ecuId']==ecu)
    assert (row['ecuReprogramType'],row['ecuDiaId'],row['ecuDId'],row['ecuHardDid'],row['ecuConfigDid'])==('canfd','741/749','F195/F194','F193','')
    steps=[dict(ecuId=ecu,ecuIds=[ecu],address='741',request='22'+did,field=field,cached=cache[field],
                identityGate=(field=='ecuSoftCode')) for did,field in [('F194','ecuSoftCode'),('F193','ecuHardVer')]]
    return {'summary':{'groups':1,'records':1,'requests':2},'plans':{'media':steps},'cache':cache,
            'limits':['No F195: stock Media software version uses Android property plus MCU getter.',
                      'Input getters are current service values, not a forced MCU version query.',
                      'No cache update, cloud request, download or install.']}


def validate_inputs(values):
    assert set(values)=={'apps.setting.product.outswver','ro.build.car.series','ro.build.system.fission_single_os',
                         'ro.build.multi_display_user','ro.hardware.info','mcuVersion'}
    for key in ['apps.setting.product.outswver','mcuVersion']:
        if not re.fullmatch(r'\d+(\.\d+){4}',values[key]):raise ValueError('Unexpected version shape')
    if values['ro.build.car.series']!='denza' or values['ro.build.system.fission_single_os']!='0' or values['ro.build.multi_display_user'] not in ('0','1'):
        raise ValueError('Media property profile changed; review mapping first')
    if not re.fullmatch(r'[A-Za-z0-9.]{1,32}',values['ro.hardware.info']):raise ValueError('Hardware property shape')


def mapped_code(raw,inputs):
    for item in json.loads(MAPPING.read_text())['softcode_mapping']:
        modules=item.get('module',[])
        ivi=any(m.get('name')=='ivi' and m.get('softcode')==inputs['ro.build.car.series'] and
                m.get('fission',0)==int(inputs['ro.build.system.fission_single_os']) and
                m.get('displayUser',0)==int(inputs['ro.build.multi_display_user']) for m in modules)
        mcu=any(m.get('name')=='mcu' and m.get('softcode')==raw for m in modules)
        if item.get('softcode') and ivi and mcu:return item['softcode']
    raise ValueError('No stock mapping for measured code')


def decode(original,line,seq,step,inputs):
    result=original(line,seq,step)
    if result['state']!='positive':return result
    raw=result['stockFieldValueFromPrefix']
    if step['field']=='ecuSoftCode':value=mapped_code(raw,inputs)
    else:
        if len(bytes.fromhex(result['responseHex']))<8:raise ValueError('Short stock hardware payload')
        value=inputs['ro.hardware.info']+'-'+raw
    return dict(result,rawMcuValue=raw,stockFieldValueFromPrefix=value,cacheMatch=value==step['cached'])


def build(module,fixed,output):
    qualified.build(module,fixed,output)
    helper=output/'inputs';helper.mkdir();classes=helper/'classes';classes.mkdir()
    source=helper/'OtaMediaInputs.java';source.write_bytes(GETTER.read_bytes())
    module.local([module.JDK/'javac','--release','8','-cp',module.ANDROID,'-d',classes,source,output/'source/ShellProxyBootstrap.java'],helper/'javac.txt')
    jar=helper/'inputs.jar'
    module.local([module.SDK/'build-tools/35.0.0/d8','--release','--min-api','33','--lib',module.ANDROID,'--output',jar,*sorted(classes.rglob('*.class'))],helper/'d8.txt')
    (output/'media-reader.py').write_bytes(Path(__file__).read_bytes())
    manifest=json.loads((output/'build.json').read_text())
    manifest['sources'] += [{'path':str(p.relative_to(output)),'sha256':qualified.sha(p)} for p in [source,jar,output/'media-reader.py']]
    manifest['mediaPlannerSha256']=qualified.sha(Path(__file__));manifest['inputsJarSha256']=qualified.sha(jar)
    manifest['mappingSha256']=MAPPING_SHA;manifest['limits']=fixed['limits'];module.dump(output/'build.json',manifest)


def inputs_snapshot(module,build,output):
    spec=importlib.util.spec_from_file_location('media_health',build/'health.py');health=importlib.util.module_from_spec(spec);spec.loader.exec_module(health)
    output.mkdir(parents=True,exist_ok=False,mode=0o700)
    name='denza_ota_media_'+secrets.token_hex(12);remote='/data/local/tmp/'+name+'.jar';staged=False
    state={'cloudRequests':0,'apkInstalled':False,'processName':name,'remoteJar':remote}
    def adb(args,required=True):
        r=health.adb_command('127.0.0.1:5555',args,limit=22)
        if required and r.returncode:raise RuntimeError('ADB operation failed')
        return r
    try:
        before=health.health('127.0.0.1:5555',output/'preflight')
        if not health.acceptable(before):raise ValueError('Inputs preflight refused')
        pids=module.processes(output/'preflight');staged=True
        jar=build/'inputs/inputs.jar';adb(['push',str(jar),remote])
        if adb(['shell','sha256sum',remote]).stdout.decode().split()!=[qualified.sha(jar),remote]:raise ValueError('Staged getter changed')
        command='CLASSPATH='+shlex.quote(remote)+' exec '+shlex.join(['app_process','/system/bin','--nice-name='+name,MAIN])
        result=adb(['shell','-T',command],False)
        (output/'stdout.txt').write_bytes(result.stdout);(output/'stderr.txt').write_bytes(result.stderr)
        state['exitCode']=result.returncode
        if result.returncode:raise ValueError('Getter failed')
        lines=[x for x in result.stdout.decode().splitlines() if x.startswith('DENZA_OTA_MEDIA_INPUTS:')]
        if len(lines)!=1:raise ValueError('Missing unique getter record')
        values=json.loads(lines[0].split(':',1)[1]);validate_inputs(values);state['values']=values
    finally:
        if staged:
            adb(['shell','rm','--',remote]);missing=adb(['shell','test','-e',remote],False);absent=adb(['shell','pidof',name],False)
            state['jarRemoved']=missing.returncode==1 and not missing.stdout and not missing.stderr
            state['processAbsent']=absent.returncode==1 and not absent.stdout and not absent.stderr
            after=health.health('127.0.0.1:5555',output/'postflight')
            state['healthStable']=health.acceptable(after) and before['appSha256']==after['appSha256'] and pids==module.processes(output/'postflight')
        module.dump(output/'inputs.json',state)
    if not all(state.get(k) for k in ['jarRemoved','processAbsent','healthStable']):raise ValueError('Inputs cleanup incomplete')
    return state['values']


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['test','build','run']);p.add_argument('--build',type=Path);p.add_argument('--output',type=Path)
    args=p.parse_args();module=qualified.baseline();fixed=plan(module)
    sample={'apps.setting.product.outswver':'34.1.33.2605218.1','mcuVersion':'34.2.3.2605202.2','ro.build.car.series':'denza',
            'ro.build.system.fission_single_os':'0','ro.build.multi_display_user':'0','ro.hardware.info':'V20.E40.00'}
    validate_inputs(sample)
    assert decode(module.decode,'a 1 1 20 999 62F1944B450011E0930034E2',1,fixed['plans']['media'][0],sample)['cacheMatch']
    assert decode(module.decode,'a 2 1 20 999 62F1930100000000',2,fixed['plans']['media'][1],sample)['cacheMatch']
    if args.action=='test':print(json.dumps({'offlineTestsPassed':True,**fixed['summary']}));return
    if not args.output:p.error('--output required')
    output=args.output.resolve()
    if args.action=='build':build(module,fixed,output);return
    if not args.build:p.error('--build required')
    build_dir=args.build.resolve();manifest=json.loads((build_dir/'build.json').read_text())
    for row in manifest['sources']:
        if qualified.sha(build_dir/row['path'])!=row['sha256']:raise ValueError('Build source changed')
    if manifest['mediaPlannerSha256']!=qualified.sha(Path(__file__)):raise ValueError('Planner changed after build')
    output.mkdir(parents=True,exist_ok=False,mode=0o700)
    before=inputs_snapshot(module,build_dir,output/'inputs-before')
    original=module.decode;module.decode=lambda line,seq,step:decode(original,line,seq,step,before)
    qualified.configure(module,fixed);module.run(build_dir,'media',output/'diagnostic')
    after=inputs_snapshot(module,build_dir,output/'inputs-after')
    if before!=after:raise ValueError('Media composition inputs changed during reads')
    version=before['apps.setting.product.outswver']+'.'+before['mcuVersion']
    module.dump(output/'composition.json',{'inputsStable':True,'ecuSoftVer':version,'cacheMatch':version==fixed['cache']['ecuSoftVer'],
                                         'cloudRequests':0,'apkInstalled':False})
    print(json.dumps({'inputsStable':True,'softwareVersion':version,'cacheMatch':version==fixed['cache']['ecuSoftVer']}))


if __name__=='__main__':main()
