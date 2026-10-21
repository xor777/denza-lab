#!/usr/bin/env python3
"""Offline verification of SIM projections and helper bootstrap correction."""
import inspect
import json
from pathlib import Path
import sys

from check_ota_core_fields import ROOT, references, read, pids
sys.path.insert(0,str(ROOT/'tools/vehicle-diagnostics'))
import ota_sim_inputs as reader

ARTIFACT=ROOT/'research/vehicle-diagnostics/ota-sim-inputs-20261021.json'


def main():
    evidence=json.loads(ARTIFACT.read_text());count=len(list(references(evidence)))
    for index,run in enumerate(evidence['runs']):
        manifest=read(run['build']);base=(ROOT/run['build']['path']).parent
        for row in manifest['sources']:assert reader.catalog.qualified.sha(base/row['path'])==row['sha256']
        assert reader.catalog.qualified.sha(base/'inputs/inputs.jar')==manifest['inputsJarSha256']
        assert reader.catalog.qualified.sha(base/'sim-reader.py')==manifest['plannerSha256']
        assert manifest['cloudRequests']==0 and manifest['ecuCalls']==0
        owner=inspect.getsource(reader.media.inputs_snapshot).replace('denza_ota_media_','denza_ota_sim_').replace('DENZA_OTA_MEDIA_INPUTS:','DENZA_OTA_SIM_INPUTS:')
        assert owner==(base/'snapshot-owner.py').read_text()
        snapshot=read(run['snapshot'])
        assert all(snapshot[k] for k in ['jarRemoved','processAbsent','healthStable'])
        assert snapshot['cloudRequests']==0 and snapshot['apkInstalled'] is False
        before,after=read(run['preflight']),read(run['postflight'])
        for value in [before,after]:
            assert all(value[k] for k in ['guardPass','firmwareHashMatch','crashEmpty','stockPresent','exportIdle'])
            assert not value['activeResearchProcesses']
        assert before['appSha256']==after['appSha256'] and pids(run['preProcesses'])==pids(run['postProcesses'])
        stdout=(ROOT/run['stdout']['path']).read_text().strip()
        if index==0:
            assert snapshot['exitCode']==1 and stdout=='DENZA_OTA_SIM_INPUTS_ERROR'
        else:
            assert snapshot['exitCode']==0
            values=json.loads(stdout.removeprefix('DENZA_OTA_SIM_INPUTS:'))
            assert values==snapshot['values']
            if index==1:
                assert values=={'defaultSubscriptionId':-1,'defaultDataSubscriptionId':-1,
                                'outcome':'partial','errorStage':'defaultVoiceSubscriptionId','errorKind':'null'}
            else:
                reader.validate(values)
                assert values['outcome']=='complete' and values['telephonyBootstrapWasMissing']
                assert all(values[k]==1 for k in ['defaultSubscriptionId','defaultDataSubscriptionId','defaultVoiceSubscriptionId'])
                assert values['simState']==5 and values['gsmSimState']=='LOADED'
                assert values['phoneSubInfoServicePresent']
                assert values['defaultIccid']=={'isNull':False,'length':20,'decimal19To22':True,'matchesRilProperty':True}
                assert values['activeSubscriptions']==[dict(values['defaultIccid'],subscriptionId=1)]
    print(json.dumps({'passed':True,'referencesVerified':count,'sessionsCleaned':3,'defaultIccidAvailable':True,
                      'initialMissingIccidWasHelperArtifact':True,'privateIdentifiersRetained':False,
                      'vehicleOrNetworkAccess':False}))


if __name__=='__main__':main()
