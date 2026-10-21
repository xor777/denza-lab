#!/usr/bin/env python3
"""Fixed ASSY code and ASCII version read using the unchanged qualified bus."""
import argparse
import json
from pathlib import Path
import re

import ota_remaining_field_read as qualified

ROOT=qualified.ROOT
STOCK=ROOT/'captures/firmware/ivi-34.1.33.2605218.1/jadx/OTAUpdatePlatformizedUI/sources/com/byd/otaupdate'
SOURCES={
    'ui/vehicleota/checkversion/diagnose/SmmDiagnostic.java':'465f4347afd7642729faba9ce180d9eb3322aa3d9316032e9d2027fecae0dabb',
    'base/util/FormatConversionUtil.java':'2c44af4b3396a76e0f19bd867a54ba8fdb17877298faa2f3a535412a0f701425',
}


def plan(module):
    for name,expected in SOURCES.items():
        if qualified.sha(STOCK/name)!=expected:raise ValueError('Stock parser source changed')
    for path,expected in [(module.MAP,module.MAP_SHA),(module.CACHE,module.CACHE_SHA)]:
        if qualified.sha(path)!=expected:raise ValueError('Pinned input changed')
    ecu='HTH-2021_ASSY_BYD_00100001'
    row=next(r for r in json.loads(module.MAP.read_text())['ecus'] if r['ecuId']==ecu)
    cache=next(r for r in json.loads(module.CACHE.read_text())['ecuInfo'] if r['ecuId']==ecu)
    assert (row['ecuReprogramType'],row['ecuDiaId'],row['ecuDId'],row['ecuHardDid'],row['ecuConfigDid'])==('canfd','5E7/5EF','F295/F294','F293','')
    assert cache['ecuSoftCode']=='4B450011DF99004201' and cache['ecuSoftVer']=='42.1.8.2605219.1.42.2.3.2605250.2'
    steps=[dict(ecuId=ecu,ecuIds=[ecu],address='5E7',request='22'+did,field=field,cached=cache[field],
                identityGate=(field=='ecuSoftCode')) for did,field in [('F294','ecuSoftCode'),('F295','ecuSoftVer')]]
    return {'summary':{'groups':1,'records':1,'requests':2},'plans':{'assy':steps},
            'limits':['Only code and ASCII version; cached negative hardware version remains unverified.',
                      'Identity mismatch, invalid ASCII, negative/no reply stops the session.',
                      'No cache update, cloud request, download or install.']}


def decode(original,line,seq,step):
    if step['field']!='ecuSoftVer':return original(line,seq,step)
    match=re.fullmatch(r'a (\d+) (\d+) (\d+) (\d+) ([0-9A-F]+)',line)
    if not match or int(match[1])!=seq or not 1<=int(match[2])<=16 or not 0<=int(match[3])<4000 or int(match[4])<=0:
        raise ValueError('Unexpected exchange metadata')
    data=bytes.fromhex(match[5])
    if len(data)==3 and data[:2]==b'\x7f\x22':return original(line,seq,step)
    if data[:3]!=bytes.fromhex('62'+step['request'][2:]) or not 4<=len(data)<=127:
        raise ValueError('Wrong DID or unsupported stock signed-length bound')
    # Exact bytesAsciiToString domain: digits and period, no slash/NUL/padding.
    if any(b<46 or b>57 or b==47 for b in data[3:]):raise ValueError('Stock ASCII parser would return null')
    value=data[3:].decode('ascii')
    return dict(step,seq=seq,responseHex=match[5],frames=int(match[2]),latencyMs=int(match[3]),
                receivedNanos=int(match[4]),state='positive',stockFieldValueFromPrefix=value,
                cacheMatch=value==step['cached'],additionalDataHex='')


def configure(module,fixed):
    original=module.decode
    module.decode=lambda line,seq,step:decode(original,line,seq,step)
    qualified.configure(module,fixed)


def self_test(module,fixed):
    step=fixed['plans']['assy'][1]
    line='a 2 1 20 999 62F295'+step['cached'].encode().hex().upper()
    assert decode(module.decode,line,2,step)['cacheMatch']
    for payload in ['62F295','62F295312F32','62F295310032','62F195312E32','62F295FF']:
        try:decode(module.decode,'a 2 1 20 999 '+payload,2,step)
        except ValueError:pass
        else:raise AssertionError('Invalid stock version accepted')
    assert decode(module.decode,'a 2 1 20 999 7F2231',2,step)['state']=='negative'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['test','build','run']);parser.add_argument('--output',type=Path);parser.add_argument('--build',type=Path)
    args=parser.parse_args();module=qualified.baseline();fixed=plan(module);self_test(module,fixed)
    if args.action=='test':print(json.dumps({'offlineTestsPassed':True,**fixed['summary']}));return
    if not args.output:parser.error('--output required')
    if args.action=='build':
        output=args.output.resolve();qualified.build(module,fixed,output)
        (output/'assy-reader.py').write_bytes(Path(__file__).read_bytes())
        manifest=json.loads((output/'build.json').read_text())
        manifest['sources'].append({'path':'assy-reader.py','sha256':qualified.sha(Path(__file__))})
        manifest['assyPlannerSha256']=qualified.sha(Path(__file__))
        manifest['stockAsciiSources']=[{'path':str((STOCK/name).relative_to(ROOT)),'sha256':sha} for name,sha in SOURCES.items()]
        manifest['limits']=fixed['limits'];module.dump(output/'build.json',manifest);return
    if not args.build:parser.error('--build required')
    build=args.build.resolve();manifest=json.loads((build/'build.json').read_text())
    if manifest['assyPlannerSha256']!=qualified.sha(Path(__file__)):raise ValueError('Reader changed after build')
    configure(module,fixed);module.run(build,'assy',args.output.resolve())


if __name__=='__main__':main()
