#!/usr/bin/env python3
"""Verify Media captures, full raw CAN replies and the retained stock mapping."""
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from check_ota_core_fields import ROOT, JAVA, references, read, pids
sys.path.insert(0,str(ROOT/'tools/vehicle-diagnostics'))
import ota_media_field_read as reader

ARTIFACT=ROOT/'research/vehicle-diagnostics/ota-media-fields-live-20261021.json'


def health(run):
    before,after=read(run['preflight']),read(run['postflight'])
    for h in [before,after]:
        assert h['state']=='read_complete'
        assert all(h[k] for k in ['guardPass','firmwareHashMatch','crashEmpty','stockPresent','exportIdle'])
        assert not h['activeResearchProcesses']
    assert before['appSha256']==after['appSha256'] and pids(run['preProcesses'])==pids(run['postProcesses'])


def main():
    evidence=json.loads(ARTIFACT.read_text());refs=len(list(references(evidence)))
    manifest=read(evidence['sources']['build']);base=(ROOT/evidence['sources']['build']['path']).parent
    assert reader.qualified.sha(Path(reader.__file__))==manifest['mediaPlannerSha256']
    assert reader.qualified.sha(base/'ota-field-reader.jar')==manifest['jarSha256']
    for row in manifest['sources']:assert reader.qualified.sha(base/row['path'])==row['sha256']
    inputs=[]
    for name in ['inputsBefore','inputsAfter']:
        run=evidence[name];health(run);state=read(run['state'])
        assert state['exitCode']==0 and not state['apkInstalled'] and state['cloudRequests']==0
        assert all(state[k] for k in ['jarRemoved','processAbsent','healthStable'])
        lines=[line for line in (ROOT/run['stdout']['path']).read_text().splitlines() if line.startswith('DENZA_OTA_MEDIA_INPUTS:')]
        assert len(lines)==1 and json.loads(lines[0].split(':',1)[1])==state['values']
        reader.validate_inputs(state['values']);inputs.append(state['values'])
    assert inputs[0]==inputs[1];values=inputs[0]
    composed=read(evidence['composition'])
    module=reader.qualified.baseline();fixed=reader.plan(module)
    assert composed['inputsStable'] and composed['cacheMatch']
    assert composed['ecuSoftVer']==values['apps.setting.product.outswver']+'.'+values['mcuVersion']==fixed['cache']['ecuSoftVer']
    run=evidence['diagnostic'];health(run);state=read(run['session'])
    assert state['state']=='complete' and state['exitCode']==0
    assert all(state[k] for k in ['lifecycleVerified','jarRemoved','processAbsent','healthStable'])
    assert not state.get('hostTerminationRequired') and state['cloudRequests']==0 and not state['apkInstalled']
    assert fixed['plans']==manifest['plans'] and len(state['responses'])==2
    lines=(ROOT/run['protocol']['path']).read_text().splitlines()
    match=re.fullmatch(r'DENZA_SERVE_([0-9a-f]{24}):READY',lines[0]);assert match
    prefix='DENZA_SERVE_'+match[1]+':';expected=[prefix+'READY']
    for seq,(step,response) in enumerate(zip(fixed['plans']['media'],state['responses']),1):
        raw=f"a {seq} {response['frames']} {response['latencyMs']} {response['receivedNanos']} {response['responseHex']}"
        assert response==reader.decode(module.decode,raw,seq,step,values)
        assert response['state']=='positive' and response['cacheMatch']
        expected += [f"HOST x {seq} 741 {step['request']} 4000",prefix+'BEGIN',raw,prefix+'END ok']
    assert lines==expected
    # Replay the unchanged stock raw-code/hardware method bodies. Mapping and
    # concatenation above are checked against the full retained resource/inputs.
    source=ROOT/evidence['sources']['stockCanReplay']['path'];provenance=read(evidence['sources']['stockCanReplayManifest'])
    assert reader.qualified.sha(source)==provenance['javaSha256']
    for item in provenance['methods']:assert reader.qualified.sha(ROOT/item['source'])==item['sourceSha256']
    driver='''public class MediaRawReplay {
      public static void main(String[] a) {
        CanDiagnostic c=CanDiagnostic.fresh();
        if(!a[1].equals(c.parseEcuSoftCodeInfo(CanDiagnostic.packet(a[0],1,0,0))))throw new AssertionError("code");
        if(!a[3].equals(c.parseEcuHardVersionInfo(CanDiagnostic.packet(a[2],1,0,0))))throw new AssertionError("hardware");
        System.out.println("2");
      }
    }'''
    with tempfile.TemporaryDirectory(prefix='ota-media-replay-') as tmp:
        file=Path(tmp)/'MediaRawReplay.java';file.write_text(driver)
        subprocess.run([str(JAVA/'javac'),'-d',tmp,str(source),str(file)],check=True,capture_output=True,timeout=30)
        args=[v for r in state['responses'] for v in [r['responseHex'],r['rawMcuValue']]]
        result=subprocess.run([str(JAVA/'java'),'-cp',tmp,'MediaRawReplay',*args],check=True,capture_output=True,text=True,timeout=15)
        assert result.stdout.strip()=='2'
    print(json.dumps({'passed':True,'referencesVerified':refs,'rawStockParserMatches':2,'composedCacheMatches':3,
                      'compositionInputsStable':True,'deviceOrNetworkAccess':False,'applicablePackageObtained':False}))


if __name__=='__main__':main()
