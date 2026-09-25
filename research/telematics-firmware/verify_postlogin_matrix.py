#!/usr/bin/env python3
"""Inventory post-login branches with synthetic state, never a car or network.

This reports gaps instead of treating unknown fixture reads as successful proof.
The supplied identity/replies must be the existing synthetic native fixture.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

from unicorn.arm64_const import UC_ARM64_REG_PC, UC_ARM64_REG_LR
from verify_persistent_engine import Process


def run(binary, fixture_path, result_path):
    fixture=json.loads(fixture_path.read_text())
    operations=fixture['script']
    identity={op.split(' ',1)[0]:bytes.fromhex(op.split(' ',1)[1])
              for op in operations if op.split(' ',1)[0] in ('V','K','U','C','M')}
    script=(''.join(f'OP {i} 1 {op}\n' for i,op in enumerate(operations,1))+'QUIT\n').encode()
    getters={(int(k.split('/')[0]),int(k.split('/')[1],16)):v for k,v in fixture['integers'].items()}
    baseline={**fixture['properties'],'sys.vin_valid_record_time':'0'}
    cases=[('baseline',{}, {})]
    # Independent state variations. Identity and transport properties are fixed.
    for key,value in baseline.items():
        if key.startswith(('persist.sys.','persist.gnss.')) and key not in (
                'persist.sys.cloud.last_vin','persist.sys.byd.apn_type','persist.sys.gpsinfo'):
            for variant in ('','0','1'):
                if variant!=value:cases.append((key+'='+repr(variant),{key:variant},{}))
    for gps in ('','0','abc','11.25_22.5_1_1_11_111.5_.1_111.11_1_1',
                '-11.25_-22.5_1_1_11_111.5_.1_111.11_1_1','_','1__1'):
        cases.append(('gps_case_'+str(len(cases)),{'persist.sys.gpsinfo':gps},{}))
    for size in (0,1,5,19,20,64):
        cases.append(('sdk_version_length_'+str(size),{}, {(1027,0x99000402):(0,b'1'*size)}))
    cases.append(('sdk_version_error',{}, {(1027,0x99000402):(-1,b'')}))
    result={'binary_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),
            'fixture_sha256':hashlib.sha256(fixture_path.read_bytes()).hexdigest(),
            'live_qualified':False,'cases':[]}
    for name,changes,buffers in cases:
        p=Process(binary,script,auto_replies=True,auto_vin_fixture=identity['V'],
                  auto_vin19_fixture=identity['V']+b'00',observed_getters=getters,
                  observed_properties={**baseline,**changes},observed_buffers=buffers)
        row={'name':name}
        try:
            code,output=p.run()
            failures=re.findall(r'"stage":"([a-z0-9_]+)"',output)
            unknown=sorted(getattr(p,'unqualified_properties',set()))
            unknown_getters=sorted(getattr(p,'unqualified_sdk_getters',set()))
            row.update(exit=code,login='LOGIN 1\n' in output,failures=failures,
                       unknown_properties=unknown,unknown_getters=unknown_getters)
            row['result']='pass' if code==0 and row['login'] and not unknown and not unknown_getters else 'boundary'
        except RuntimeError:
            row.update(result='trap',pc=hex(p.u.reg_read(UC_ARM64_REG_PC)),
                       lr=hex(p.u.reg_read(UC_ARM64_REG_LR)))
        result['cases'].append(row)
        result_path.write_text(json.dumps(result,indent=2)+'\n')
        if row['result']!='pass':print(json.dumps(row),flush=True)
    counts={kind:sum(r['result']==kind for r in result['cases']) for kind in ('pass','boundary','trap')}
    result['counts']=counts;result_path.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(counts),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('binary',type=Path);parser.add_argument('fixture',type=Path)
    parser.add_argument('--result',type=Path,required=True)
    args=parser.parse_args();run(args.binary,args.fixture,args.result)
