#!/usr/bin/env python3
"""One fixed identity read per session for zero/negative OTA cache records.

Uses the qualified bus unchanged. Every NRC/no-reply remains a stopped base
session, with a separate observation record only after cleanup verification.
No retries, version substitution, OTA lifecycle or cloud request. A positive
code stops the cohort for review, even when it is all zero.
"""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import re

import ota_remaining_field_read as qualified

ROOT = qualified.ROOT
SCOPE = ROOT / 'captures/ota-remaining-fields-20261021/build/scope.json'
SCOPE_SHA = '0c57eda166e1d51ddfa53a0e741dc45c0fd703090052d987c40683a1ec6dfa20'


def plan(module):
    if qualified.sha(SCOPE) != SCOPE_SHA:
        raise ValueError('Retained cohort classification changed')
    for path, expected in [(module.MAP,module.MAP_SHA),(module.CACHE,module.CACHE_SHA)]:
        if qualified.sha(path) != expected: raise ValueError('Pinned input changed')
    ids = {r['ecuId'] for r in json.loads(SCOPE.read_text())['excluded']
           if r['reason'] == 'no_positive_cached_identity'}
    mapping = {r['ecuId']:r for r in json.loads(module.MAP.read_text())['ecus']}
    cache = {r['ecuId']:r for r in json.loads(module.CACHE.read_text())['ecuInfo']}
    keys = ['ecuReprogramType','ecuChannel','ecuDiaId','logicAddress','ecuDId','ecuHardDid','ecuConfigDid']
    grouped = defaultdict(list)
    for ecu in sorted(ids):
        row = mapping[ecu]
        if row['ecuReprogramType'] not in ('can','canfd') or cache[ecu]['ecuSoftCode'] not in ('7F22','000000000000000000'):
            raise ValueError('Unexpected transport or cache identity')
        grouped[tuple(row.get(k) for k in keys)].append(ecu)
    namespaces=[]
    for key, aliases in grouped.items():
        row=mapping[aliases[0]];route=re.fullmatch(r'([0-9A-F]{3})/([0-9A-F]{3})',row['ecuDiaId'])
        pair=re.fullmatch(r'([0-9A-F]{4})/([0-9A-F]{4})',row['ecuDId'])
        if not route or not pair or int(route[2],16)!=int(route[1],16)+8 or route[1]=='7DF':
            raise ValueError('Unqualified factory route/DIDs')
        values={cache[i]['ecuSoftCode'] for i in aliases}
        if len(values)!=1:raise ValueError('Conflicting aliases')
        namespaces.append({'signature':dict(zip(keys,key)),'ecuIds':aliases,
                           'steps':[dict(ecuId=aliases[0],ecuIds=aliases,address=route[1],request='22'+pair[2],
                                         field='ecuSoftCode',cached=values.pop(),identityGate=False)]})
    namespaces.sort(key=lambda n:(n['steps'][0]['cached']!='7F22',n['steps'][0]['address'],n['steps'][0]['request']))
    plans={f'unknown-{i:03d}':n['steps'] for i,n in enumerate(namespaces,1)}
    assert len(ids)==47 and len(plans)==44
    assert len({(s[0]['address'],s[0]['request']) for s in plans.values()})==44
    return {'summary':{'records':47,'groups':44,'requests':44,
                       'cachedIdentityTypes':dict(Counter(cache[i]['ecuSoftCode'] for i in ids))},
            'plans':plans,'namespaces':namespaces,'limits':[
                'Only the factory-mapped code DID is read; no version/configuration read follows.',
                'A negative code or timeout is not proof that an ECU is absent.',
                'Any positive response stops the cohort for separate interpretation.',
                'No cache is overwritten and no cloud query or update is started.']}


def classify(session, protocol, step):
    if session.get('exitCode')!=0 or session.get('hostTerminationRequired') or not all(
            session.get(k) is True for k in ['jarRemoved','processAbsent','healthStable']):
        raise ValueError('Cleanup/health not verified')
    if session.get('cloudRequests')!=0 or session.get('apkInstalled') is not False:
        raise ValueError('Unexpected side effect declaration')
    lines=protocol.splitlines()
    if len(lines)!=5:raise ValueError('Not exactly one complete exchange')
    match=re.fullmatch(r'DENZA_SERVE_([0-9a-f]{24}):READY',lines[0])
    if not match:raise ValueError('Missing ready')
    prefix='DENZA_SERVE_'+match[1]+':'
    if lines[1]!=f"HOST x 1 {step['address']} {step['request']} 4000" or lines[2]!=prefix+'BEGIN' or lines[4]!=prefix+'END ok':
        raise ValueError('Unexpected command or boundary')
    if lines[3].startswith('a '):
        response=qualified.baseline().decode(lines[3],1,step)
        if session.get('responses')!=[response]:raise ValueError('Decoded record differs')
        if response['state']=='positive':
            if session['state']!='complete' or not session.get('lifecycleVerified'):raise ValueError('Incomplete positive')
            return {'kind':'positive_requires_review','response':response,'cleanupVerified':True,'continueAllowed':False}
        if session['state']!='stopped' or session.get('lifecycleVerified') is not False or session.get('error')!='ValueError: Negative response; stop batch without substituting stock marker':
            raise ValueError('Unexpected negative stop')
        return {'kind':'negative_uds','response':response,'cleanupVerified':True,'continueAllowed':True}
    match=re.fullmatch(r'n 1 (3|4|T) (\d+) (\d+)',lines[3])
    if not match or not 0<=int(match[2])<=6000 or int(match[3])<=0:
        raise ValueError('Unexpected/busy/noise record')
    if session['state']!='stopped' or session.get('responses')!=[] or session.get('lifecycleVerified') is not False or session.get('error')!='ValueError: Unexpected exchange metadata or non-answer':
        raise ValueError('Unexpected no-reply stop')
    return {'kind':'no_reply','gatewayResult':match[1],'latencyMs':int(match[2]),
            'receivedNanos':int(match[3]),'rawRecord':lines[3],'cleanupVerified':True,'continueAllowed':True}


def self_test(fixed):
    step=next(iter(fixed['plans'].values()))[0];nonce='0'*24;prefix='DENZA_SERVE_'+nonce+':'
    session=dict(exitCode=0,jarRemoved=True,processAbsent=True,healthStable=True,cloudRequests=0,apkInstalled=False,
                 state='stopped',lifecycleVerified=False,responses=[],error='ValueError: Unexpected exchange metadata or non-answer')
    def protocol(line):return '\n'.join([prefix+'READY',f"HOST x 1 {step['address']} {step['request']} 4000",prefix+'BEGIN',line,prefix+'END ok'])+'\n'
    for code in ['3','4','T']:assert classify(session,protocol('n 1 '+code+' 4000 999'),step)['continueAllowed']
    for bad in ['n 1 5 20 999','n 2 3 20 999','n 1 3 9000 999']:
        try:classify(session,protocol(bad),step)
        except ValueError:pass
        else:raise AssertionError('Invalid exchange accepted')
    raw='a 1 1 20 999 7F2231';response=qualified.baseline().decode(raw,1,step)
    negative=dict(session,responses=[response],error='ValueError: Negative response; stop batch without substituting stock marker')
    assert classify(negative,protocol(raw),step)['response']['nrcHex']=='31'
    for key in ['jarRemoved','processAbsent','healthStable']:
        try:classify(dict(negative,**{key:False}),protocol(raw),step)
        except ValueError:pass
        else:raise AssertionError('Unverified cleanup accepted')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['plan','test','build','run'])
    parser.add_argument('--build',type=Path);parser.add_argument('--output',type=Path);parser.add_argument('--group')
    args=parser.parse_args();module=qualified.baseline();fixed=plan(module);self_test(fixed)
    if args.action in ('plan','test'):
        if args.output:module.dump(args.output.resolve(),fixed)
        print(json.dumps(fixed['summary']));return
    if not args.output:parser.error('--output required')
    if args.action=='build':
        output=args.output.resolve();qualified.build(module,fixed,output)
        (output/'unknown-identity-reader.py').write_bytes(Path(__file__).read_bytes())
        manifest=json.loads((output/'build.json').read_text())
        manifest['sources'].append({'path':'unknown-identity-reader.py','sha256':qualified.sha(Path(__file__))})
        manifest['unknownIdentityPlannerSha256']=qualified.sha(Path(__file__))
        manifest['hypothesis']='Zero/negative cached identity might conceal a now-readable positive code.'
        manifest['expected']='One fixed read per session. Preserve raw NRC/no-reply and verify cleanup separately.'
        manifest['falsification']='Any positive code stops the cohort for review; no automatic cache/cloud substitution.'
        module.dump(output/'build.json',manifest);return
    if not args.build or args.group not in fixed['plans']:parser.error('Fixed --group and --build required')
    build=args.build.resolve();manifest=json.loads((build/'build.json').read_text())
    if manifest['unknownIdentityPlannerSha256']!=qualified.sha(Path(__file__)):raise ValueError('Planner changed after build')
    qualified.configure(module,fixed)
    try:module.run(build,args.group,args.output.resolve())
    except SystemExit as stopped:
        if stopped.code!=2:raise
    output=args.output.resolve();session=json.loads((output/'session.json').read_text())
    observation=classify(session,(output/'protocol.txt').read_text(),fixed['plans'][args.group][0])
    module.dump(output/'observation.json',observation)
    print(json.dumps({'group':args.group,'kind':observation['kind'],'cleanupVerified':True,'continueAllowed':observation['continueAllowed']}))
    if not observation['continueAllowed']:raise SystemExit(3)


if __name__=='__main__':main()
