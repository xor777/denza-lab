#!/usr/bin/env python3
"""Offline ASSY transcript verification and exact retained SMM parser replay."""
import hashlib
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from check_ota_core_fields import ROOT, JAVA, references, read, pids
sys.path.insert(0,str(ROOT/'tools/vehicle-diagnostics'))
import ota_assy_field_read as reader

ARTIFACT=ROOT/'research/vehicle-diagnostics/ota-assy-fields-live-20261021.json'


def method(text,signature):
    start=text.index(signature);brace=text.index('{',start);depth=1;end=brace+1
    while depth:
        depth+=(text[end]=='{')-(text[end]=='}');end+=1
    return text[start:end]


def replay(rows):
    base=ROOT/'captures/diagnostics-followup-20261021/ota-config'
    provenance=json.loads((base/'source-methods.json').read_text())
    source=(base/'CanDiagnostic.java').read_text()
    assert hashlib.sha256(source.encode()).hexdigest()==provenance['javaSha256']
    for item in provenance['methods']:
        assert hashlib.sha256((ROOT/item['source']).read_bytes()).hexdigest()==item['sourceSha256']
    for name,expected in reader.SOURCES.items():assert reader.qualified.sha(reader.STOCK/name)==expected
    ascii_method=method((reader.STOCK/'base/util/FormatConversionUtil.java').read_text(),
                        'public static String bytesAsciiToString(byte[] src)')
    assert source.count('class FormatConversionUtil {')==1
    source=source.replace('class FormatConversionUtil {',
                          'class FormatConversionUtil {\nstatic final String TAG="FormatConversionUtil";\n'+ascii_method)
    smm=(reader.STOCK/'ui/vehicleota/checkversion/diagnose/SmmDiagnostic.java').read_text()
    smm='\n'.join(line for line in smm.splitlines() if not line.startswith(('package ','import ')))+'\n'
    driver='''public class AssyReplay {
      public static void main(String[] a) {
        CanDiagnostic code=CanDiagnostic.fresh();SmmDiagnostic smm=new SmmDiagnostic();
        if(!a[1].equals(code.parseEcuSoftCodeInfo(CanDiagnostic.packet(a[0],1,0,0))))throw new AssertionError("code");
        if(!a[3].equals(smm.parseEcuVersionInfo(CanDiagnostic.packet(a[2],1,0,0))))throw new AssertionError("version");
        for(String bad:new String[]{"62F295312F32","62F295310032","62F295FF","62F295"})
          if(smm.parseEcuVersionInfo(CanDiagnostic.packet(bad,1,0,0))!=null)throw new AssertionError("invalid ASCII");
        System.out.println("2");
      }
    }'''
    with tempfile.TemporaryDirectory(prefix='ota-assy-replay-') as tmp:
        files=[]
        for name,text in [('CanDiagnostic.java',source),('SmmDiagnostic.java',smm),('AssyReplay.java',driver)]:
            p=Path(tmp)/name;p.write_text(text);files.append(str(p))
        subprocess.run([str(JAVA/'javac'),'-d',tmp,*files],check=True,capture_output=True,timeout=30)
        args=[x for row in rows for x in [row['responseHex'],row['stockFieldValueFromPrefix']]]
        run=subprocess.run([str(JAVA/'java'),'-cp',tmp,'AssyReplay',*args],check=True,capture_output=True,text=True,timeout=15)
        assert run.stdout.strip()=='2'


def main():
    evidence=json.loads(ARTIFACT.read_text());refs=len(list(references(evidence)))
    manifest=read(evidence['sources']['build']);base=(ROOT/evidence['sources']['build']['path']).parent
    assert reader.qualified.sha(Path(reader.__file__))==manifest['assyPlannerSha256']
    assert reader.qualified.sha(base/'ota-field-reader.jar')==manifest['jarSha256']
    for row in manifest['sources']:assert reader.qualified.sha(base/row['path'])==row['sha256']
    module=reader.qualified.baseline();fixed=reader.plan(module);reader.self_test(module,fixed)
    assert manifest['plans']==fixed['plans'];run=evidence['run'];session=read(run['session'])
    assert session['state']=='complete' and session['exitCode']==0
    assert all(session[k] for k in ['lifecycleVerified','jarRemoved','processAbsent','healthStable'])
    assert not session.get('hostTerminationRequired') and session['cloudRequests']==0 and not session['apkInstalled']
    before,after=read(run['preflight']),read(run['postflight'])
    for health in [before,after]:
        assert health['state']=='read_complete'
        assert all(health[k] for k in ['guardPass','firmwareHashMatch','crashEmpty','stockPresent','exportIdle'])
        assert not health['activeResearchProcesses']
    assert before['appSha256']==after['appSha256'] and pids(run['preProcesses'])==pids(run['postProcesses'])
    lines=(ROOT/run['protocol']['path']).read_text().splitlines()
    match=re.fullmatch(r'DENZA_SERVE_([0-9a-f]{24}):READY',lines[0]);assert match
    prefix='DENZA_SERVE_'+match[1]+':';expected=[prefix+'READY']
    assert len(session['responses'])==2
    for seq,(step,response) in enumerate(zip(fixed['plans']['assy'],session['responses']),1):
        raw=f"a {seq} {response['frames']} {response['latencyMs']} {response['receivedNanos']} {response['responseHex']}"
        assert response==reader.decode(module.decode,raw,seq,step)
        assert response['state']=='positive' and response['cacheMatch']
        expected += [f"HOST x {seq} 5E7 {step['request']} 4000",prefix+'BEGIN',raw,prefix+'END ok']
    assert lines==expected;replay(session['responses'])
    print(json.dumps({'passed':True,'referencesVerified':refs,'responses':2,'stockParserMatches':2,
                      'deviceOrNetworkAccess':False,'expectedServerTupleKnown':False,'applicablePackageObtained':False}))


if __name__=='__main__':main()
