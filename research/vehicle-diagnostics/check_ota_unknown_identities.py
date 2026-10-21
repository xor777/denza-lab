#!/usr/bin/env python3
"""Verify fixed negative/default identity observations without vehicle access."""
from collections import Counter
import hashlib
import json
import sys
from pathlib import Path

from check_ota_core_fields import ROOT, references, read, pids
sys.path.insert(0,str(ROOT/'tools/vehicle-diagnostics'))
import ota_unknown_identity_read as reader

ARTIFACT=ROOT/'research/vehicle-diagnostics/ota-unknown-identities-live-20261021.json'


def main():
    evidence=json.loads(ARTIFACT.read_text());refs=len(list(references(evidence)))
    manifest=read(evidence['sources']['build']);base=(ROOT/evidence['sources']['build']['path']).parent
    for item in manifest['sources']:
        assert hashlib.sha256((base/item['path']).read_bytes()).hexdigest()==item['sha256']
    assert hashlib.sha256((base/'ota-field-reader.jar').read_bytes()).hexdigest()==manifest['jarSha256']
    assert reader.qualified.sha(Path(reader.__file__))==manifest['unknownIdentityPlannerSha256']
    scope=reader.plan(reader.qualified.baseline());reader.self_test(scope)
    assert scope['plans']==manifest['plans']
    outcomes=Counter();nrcs=Counter();gateways=Counter();aliases=set();groups=set()
    for run in evidence['runs']:
        group=run['group'];assert group not in groups;groups.add(group)
        session=read(run['session']);assert session['plan']==group
        step=scope['plans'][group][0]
        assert len(scope['plans'][group])==1 and not step['identityGate']
        outcome=reader.classify(session,(ROOT/run['protocol']['path']).read_text(),step)
        assert outcome==read(run['observation'])
        before,after=read(run['preflight']),read(run['postflight'])
        for health in [before,after]:
            assert health['state']=='read_complete'
            assert all(health[k] for k in ['guardPass','firmwareHashMatch','crashEmpty','stockPresent','exportIdle'])
            assert not health['activeResearchProcesses']
        assert before['appSha256']==after['appSha256']
        assert pids(run['preProcesses'])==pids(run['postProcesses'])
        outcomes[outcome['kind']]+=1;aliases.update(step['ecuIds'])
        if outcome['kind']=='negative_uds':nrcs[outcome['response']['nrcHex']]+=1
        elif outcome['kind']=='no_reply':gateways[outcome['gatewayResult']]+=1
    assert evidence['summary']['completedGroups']==len(groups)
    assert evidence['summary']['recordsRepresented']==len(aliases)
    assert evidence['summary']['outcomes']==dict(outcomes)
    assert evidence['summary']['nrcs']==dict(nrcs)
    assert evidence['summary']['gatewayResults']==dict(gateways)
    if evidence['summary']['allPlannedGroupsObserved']:assert groups==set(scope['plans'])
    print(json.dumps({'passed':True,'referencesVerified':refs,'groups':len(groups),'recordsRepresented':len(aliases),
                      'outcomes':dict(outcomes),'nrcs':dict(nrcs),'gatewayResults':dict(gateways),
                      'deviceOrNetworkAccess':False,'expectedServerTupleKnown':False,'applicablePackageObtained':False}))


if __name__=='__main__':main()
