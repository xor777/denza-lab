#!/usr/bin/env python3
"""Verify a completed remaining-field capture offline, including stock parser replay."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

from check_ota_core_fields import ROOT, JAVA, references, read, pids

ARTIFACT = ROOT / 'research/vehicle-diagnostics/ota-remaining-fields-live-20261021.json'


def main():
    evidence = json.loads(ARTIFACT.read_text())
    checked = len(list(references(evidence)))
    manifest = read(evidence['sources']['build'])
    base = (ROOT/evidence['sources']['build']['path']).parent
    assert hashlib.sha256((base/'ota-field-reader.jar').read_bytes()).hexdigest() == manifest['jarSha256']
    for row in manifest['sources']:
        assert hashlib.sha256((base/row['path']).read_bytes()).hexdigest() == row['sha256']
    scope = json.loads((base/'scope.json').read_text())
    assert scope['plans'] == manifest['plans']
    assert scope['summary'] == evidence['planSummary']
    values = []; aliases = set(); requests = set(); unique_pairs = set()
    for run in evidence['runs']:
        session = read(run['session']); group = run['group']
        assert session['plan'] == group and group not in requests
        requests.add(group)
        assert session['state'] == 'complete' and session['exitCode'] == 0
        assert all(session[k] for k in ['lifecycleVerified','jarRemoved','processAbsent','healthStable'])
        assert not session.get('hostTerminationRequired') and session['cloudRequests'] == 0 and not session['apkInstalled']
        before, after = read(run['preflight']), read(run['postflight'])
        for health in [before,after]:
            assert health['state'] == 'read_complete'
            assert all(health[k] for k in ['guardPass','firmwareHashMatch','crashEmpty','stockPresent','exportIdle'])
            assert not health['activeResearchProcesses']
        assert before['appSha256'] == after['appSha256']
        assert pids(run['preProcesses']) == pids(run['postProcesses'])
        plan = manifest['plans'][group]
        assert len(plan) == len(session['responses']) <= 10
        protocol = (ROOT/run['protocol']['path']).read_text().splitlines()
        match = re.fullmatch(r'DENZA_SERVE_([0-9a-f]{24}):READY',protocol[0]); assert match
        prefix = f'DENZA_SERVE_{match[1]}:'; expected = [prefix+'READY']; identified = set()
        for seq,(step,response) in enumerate(zip(plan,session['responses']),1):
            assert all(response[k] == v for k,v in step.items())
            assert response['seq'] == seq and response['state'] == 'positive'
            assert response['cacheMatch'] and response['stockFieldValueFromPrefix'] == step['cached']
            assert 0 <= response['latencyMs'] < 4000 and response['receivedNanos'] > 0
            if step['identityGate']:
                assert step['field'] == 'ecuSoftCode'
                identified.add(step['ecuId'])
            else: assert step['ecuId'] in identified
            assert response['responseHex'].startswith('62'+step['request'][2:])
            expected += [f"HOST x {seq} {step['address']} {step['request']} 4000",prefix+'BEGIN',
                         f"a {seq} {response['frames']} {response['latencyMs']} {response['receivedNanos']} {response['responseHex']}",prefix+'END ok']
            values.append(response); aliases.update(step['ecuIds']);unique_pairs.add((step['address'],step['request']))
        assert protocol == expected
    assert requests == set(manifest['plans'])
    assert len(values) == evidence['summary']['responses'] == scope['summary']['requests']
    assert len(aliases) == evidence['summary']['serverRecordsRepresented'] == scope['summary']['records']
    assert len(unique_pairs) == evidence['summary']['uniqueAddressDidPairs']
    replay = read(evidence['sources']['stockReplayManifest'])
    source = ROOT/evidence['sources']['stockReplay']['path']
    assert hashlib.sha256(source.read_bytes()).hexdigest() == replay['javaSha256']
    for item in replay['methods']:
        assert hashlib.sha256((ROOT/item['source']).read_bytes()).hexdigest() == item['sourceSha256']
    driver = '''public class RemainingFieldsReplay {
      public static void main(String[] a) {
        CanDiagnostic c=CanDiagnostic.fresh();
        for(int i=0;i<a.length;i+=3){byte[] packet=CanDiagnostic.packet(a[i+1],1,0,0);
          String actual=a[i].endsWith("Code")?c.parseEcuSoftCodeInfo(packet):a[i].equals("ecuHardVer")?c.parseEcuHardVersionInfo(packet):c.parseEcuVersionInfo(packet);
          if(!a[i+2].equals(actual))throw new AssertionError(a[i]+": "+actual+" != "+a[i+2]);}
        System.out.println(a.length/3);
      }
    }'''
    with tempfile.TemporaryDirectory(prefix='ota-remaining-replay-') as tmp:
        file=Path(tmp)/'RemainingFieldsReplay.java';file.write_text(driver)
        subprocess.run([str(JAVA/'javac'),'-d',tmp,str(source),str(file)],check=True,capture_output=True,timeout=30)
        args=[v for r in values for v in [r['field'],r['responseHex'],r['stockFieldValueFromPrefix']]]
        result=subprocess.run([str(JAVA/'java'),'-cp',tmp,'RemainingFieldsReplay',*args],check=True,capture_output=True,text=True,timeout=15)
        assert int(result.stdout.strip()) == len(values)
    print(json.dumps({'passed':True,'referencesVerified':checked,'groups':len(requests),'responses':len(values),
                      'uniqueAddressDidPairs':len(unique_pairs),'serverRecordsRepresented':len(aliases),
                      'stockParserMatches':len(values),'deviceOrNetworkAccess':False,
                      'expectedServerTupleKnown':False,'applicablePackageObtained':False}))


if __name__=='__main__':main()
