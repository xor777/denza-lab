#!/usr/bin/env python3
"""Verify the bounded catalog attempt, exact projection-only edits and cleanup."""
import json
from pathlib import Path
import sys

from check_ota_core_fields import ROOT, references, read, pids
sys.path.insert(0,str(ROOT/'tools/vehicle-diagnostics'))
import ota_doip_catalog as reader

ARTIFACT=ROOT/'research/vehicle-diagnostics/ota-doip-catalog-20261021.json'


def main():
    evidence=json.loads(ARTIFACT.read_text());refs=len(list(references(evidence)))
    manifest=read(evidence['sources']['build']);base=(ROOT/evidence['sources']['build']['path']).parent
    assert reader.qualified.sha(Path(reader.__file__))==manifest['plannerSha256']
    assert reader.qualified.sha(base/'dex/classes.dex')==manifest['artifactSha256']
    for row in manifest['sources']:assert reader.qualified.sha(base/row['path'])==row['sha256']
    assert manifest['cloudRequestLimit']==1 and manifest['ecuCalls']==0 and not manifest['downloadOrInstall']
    for name,digest in reader.PINNED.items():
        original=reader.SOURCE/name;assert reader.qualified.sha(original)==digest
        text=original.read_text()
        if name in ('EcuMapMetadataProbe.java','run_ecu_map_metadata.py'):
            anchor='"logicAddress", "ecuDId", "ecuHardDid", "ecuConfigDid", "seq"'
            assert text.count(anchor)==1;text=text.replace(anchor,anchor+', "ecuIp"')
        assert (base/'source'/name).read_text()==text
    session=read(evidence['session'])
    assert session['status']=='refused' and session['goSent'] and 'result' not in session
    assert session['error']=='helper_http_auth' and session['helperError']=={'stage':'https','code':'http_auth'}
    assert all(session[k] for k in ['exportClosed','watchdogTerminal','leaseReleased','postApkIdentityVerified'])
    assert not session.get('helperCleanupUnverified') and not session.get('cleanupError')
    assert session['artifactSha256']==manifest['artifactSha256']
    assert session['orchestratorSha256']==reader.qualified.sha(base/'source/run_ecu_map_metadata.py')
    assert session['helperSourceSha256']==reader.qualified.sha(base/'source/EcuMapMetadataProbe.java')
    before,after=read(evidence['preflight']),read(evidence['postflight'])
    for h in [before,after]:
        assert h['state']=='read_complete'
        assert all(h[k] for k in ['guardPass','firmwareHashMatch','crashEmpty','stockPresent','exportIdle'])
        assert not h['activeResearchProcesses']
    assert before['appSha256']==after['appSha256'] and pids(evidence['preProcesses'])==pids(evidence['postProcesses'])
    assert read(evidence['healthComparison'])['stable']
    cfg=read(evidence['sources']['localUdsConfig'])
    assert cfg['DoipCommonConfig']['LocalIpv4Address']=='192.168.195.2'
    assert {r['PhysicalAddress'] for r in cfg['ServerInstances']}=={'0x002b','0x002c'}
    assert evidence['summary']['exactHttpStatusKnown'] is False and evidence['summary']['httpStatusCandidates']==[401,403]
    print(json.dumps({'passed':True,'referencesVerified':refs,'projectionOnlyMutation':True,
                      'result':'HTTP401or403_before_OTA_application_response','cleanupVerified':True,
                      'ecuCalls':0,'doipIpsObtained':False,'deviceOrNetworkAccess':False}))


if __name__=='__main__':main()
