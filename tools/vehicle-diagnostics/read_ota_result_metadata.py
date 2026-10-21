#!/usr/bin/env python3
"""Read three fixed OTA result preferences, retaining only routes and versions.

Default is offline. The authorized run reuses the history export lifecycle;
credentials/unrelated preferences remain transient and are never projected.
No OEM request, ECU operation or OTA lifecycle invocation.
"""
import argparse
import importlib.util
import ipaddress
import json
from pathlib import Path, PurePosixPath
import re

import ota_remaining_field_read as qualified
import read_ota_history as history

ROOT=qualified.ROOT
HISTORY_SHA='70853071a955b7b4b103cc845a37bc7aca6ec176db020dab521021dcb54b9ad7'
PREFS=tuple(f'/data/user/0/{p}/shared_prefs/share_data_prefs.xml' for p in
            ['com.byd.otaupdate','com.byd.otaupdate2','com.byd.productlineota'])
KEYS=('otaResultEcuListInfo','upgradeResultSubmitInfo','upgradeResultNormalModelInfo')
FIELDS=('ecuId','ecuIp','ecuPort','ecuSoftCode','ecuSoftVer','ecuOriginSVer','ecuHardVer','ecuDiaId','ecuReprogramType','ecuConfigDid')
NORMAL=('normalOriginVer','normalStatus','normalStrategyVersion','normalTime','normalVersion','strategyType')


def projected_rows(value):
    rows=history.inventory._project_rows(value,FIELDS,'ecuId')
    for row in rows:
        if row.get('ecuIp'):ipaddress.ip_address(row['ecuIp'])
        if row.get('ecuPort') and not re.fullmatch(r'\d{1,5}',row['ecuPort']):raise ValueError('Invalid port')
    return rows


def normal(value):
    if value is None:return None
    if type(value) is not dict:raise ValueError('Invalid normal model')
    result={}
    for key in NORMAL:
        v=value.get(key)
        if v is not None and (type(v) is not str or not re.fullmatch(r'[A-Za-z0-9_.:/ -]{0,128}',v)):
            raise ValueError('Invalid normal field')
        result[key]=v
    return result


def project(path,raw):
    if path not in PREFS:raise ValueError('Fixed preference file required')
    result={}
    for key in KEYS:
        value=history.inventory._preference(raw,key)
        if value is None:result[key]={'status':'key_missing'};continue
        if not value.strip():result[key]={'status':'key_no_data'};continue
        obj=history.inventory._json(value)
        if obj is None:result[key]={'status':'key_no_data'};continue
        row={'status':'present'}
        if key=='otaResultEcuListInfo':row['ecus']=projected_rows(obj)
        elif key=='upgradeResultSubmitInfo':
            if type(obj) is not dict:raise ValueError('Invalid submit model')
            row['ecus']=projected_rows(obj.get('ecuModel') or []);row['normalModel']=normal(obj.get('normalModel'))
        else:row['normalModel']=normal(obj)
        result[key]=row
    return {'preferences':result}


def configure():
    if qualified.sha(Path(history.__file__))!=HISTORY_SHA:raise ValueError('Qualified history reader changed')
    history.PREFS=PREFS;history.FILES=PREFS
    history.DIRECTORIES=frozenset(str(PurePosixPath(p).parent) for p in PREFS)
    history.project=project


def test():
    fixture=b'<map><string name="user_token">DO_NOT_PROJECT</string><string name="otaResultEcuListInfo">[{&quot;ecuId&quot;:&quot;HTH-2021_TEST&quot;,&quot;ecuIp&quot;:&quot;192.168.195.1&quot;,&quot;private_field&quot;:&quot;DROP&quot;}]</string></map>'
    result=project(PREFS[0],fixture)
    assert result['preferences']['otaResultEcuListInfo']['ecus'][0]['ecuIp']=='192.168.195.1'
    assert result['preferences']['upgradeResultSubmitInfo']['status']=='key_missing'
    assert 'DO_NOT_PROJECT' not in json.dumps(result) and 'DROP' not in json.dumps(result)
    assert normal({'normalVersion':'V1.7.0','normalReason':'DO_NOT_PROJECT','uuid':'DO_NOT_PROJECT'})['normalVersion']=='V1.7.0'


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',action='store_true');p.add_argument('--output',type=Path)
    args=p.parse_args();configure();test()
    if not args.run:print(json.dumps({'offlineTestsPassed':True,'files':PREFS,'keys':KEYS,'oemRequests':0,'ecuCalls':0}));return
    if not args.output:p.error('--output required')
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=False,mode=0o700)
    module=qualified.baseline();path=ROOT/'captures/ota-core-fields-20261021/build-v2/health.py'
    if qualified.sha(path)!=module.HEALTH_SHA:raise ValueError('Health source changed')
    spec=importlib.util.spec_from_file_location('results_health',path);health=importlib.util.module_from_spec(spec);spec.loader.exec_module(health)
    before=health.health('127.0.0.1:5555',output/'preflight')
    if not health.acceptable(before):raise ValueError('Preflight refused')
    pids=module.processes(output/'preflight')
    result=history.run(history.HistoryDevice())
    module.dump(output/'session.json',result)
    after=health.health('127.0.0.1:5555',output/'postflight')
    stable=health.acceptable(after) and before['appSha256']==after['appSha256'] and pids==module.processes(output/'postflight')
    sources=[Path(__file__),Path(history.__file__),Path(history.lifecycle.__file__),Path(history.wire.__file__),Path(history.inventory.__file__),path]
    module.dump(output/'manifest.json',{'sources':[{'path':str(p.relative_to(ROOT)),'sha256':qualified.sha(p)} for p in sources],
                                      'healthStable':stable,'oemRequests':0,'ecuCalls':0,'apkInstalls':0})
    print(json.dumps({'status':result['status'],'healthStable':stable,'files':[{'source':f['source'],'status':f['status'],
                      'keys':{k:v['status'] for k,v in f.get('preferences',{}).items()}} for f in result.get('files',[])]}))
    if result['status']!='complete' or not stable:raise SystemExit(2)


if __name__=='__main__':main()
