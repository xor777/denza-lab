#!/usr/bin/env python3
"""Offline evidence check and replay of retained stock parsing; no vehicle/network."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
ARTIFACT = ROOT / 'research/vehicle-diagnostics/ota-core-fields-live-20261021.json'
JAVA = Path('/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin')
STOCK = {'autoservice', 'gbacqservice', 'gbdataservice', 'com.byd.avc',
         'com.byd.otaupdate', 'com.byd.otaupdate2', 'com.byd.productlineota', 'com.byd.bydlogtool'}


def references(value):
    if isinstance(value, dict):
        if 'path' in value and 'sha256' in value:
            path = (ROOT / value['path']).resolve()
            assert path.is_relative_to(ROOT)
            assert hashlib.sha256(path.read_bytes()).hexdigest() == value['sha256'], value['path']
            yield value
        for item in value.values():
            yield from references(item)
    elif isinstance(value, list):
        for item in value:
            yield from references(item)


def read(ref):
    return json.loads((ROOT / ref['path']).read_text())


def pids(ref):
    rows = [line.split() for line in (ROOT / ref['path']).read_text().splitlines()]
    selected = {r[1]: r[0] for r in rows if len(r) == 2 and r[1] in STOCK}
    assert set(selected) == STOCK
    return selected


def main():
    evidence = json.loads(ARTIFACT.read_text())
    count = len(list(references(evidence)))
    build_ref = evidence['sources']['build']
    build = read(build_ref)
    build_dir = (ROOT / build_ref['path']).parent
    assert hashlib.sha256((build_dir/'ota-field-reader.jar').read_bytes()).hexdigest() == build['jarSha256']
    for row in build['sources']:
        assert hashlib.sha256((build_dir/row['path']).read_bytes()).hexdigest() == row['sha256']
    rows = []
    for run in evidence['runs']:
        session = read(run['session'])
        assert session['state'] == 'complete' and session['exitCode'] == 0
        assert all(session[k] for k in ['lifecycleVerified', 'jarRemoved', 'processAbsent', 'healthStable'])
        assert not session.get('hostTerminationRequired') and session['cloudRequests'] == 0
        before, after = read(run['preflight']), read(run['postflight'])
        for health in [before, after]:
            assert health['state'] == 'read_complete'
            assert all(health[k] for k in ['guardPass', 'firmwareHashMatch', 'crashEmpty', 'stockPresent', 'exportIdle'])
            assert not health['activeResearchProcesses']
        assert before['appSha256'] == after['appSha256']
        assert pids(run['preProcesses']) == pids(run['postProcesses'])
        plan = build['plans'][run['plan']]
        assert len(session['responses']) == len(plan) == run['requests'] <= 10
        protocol = (ROOT / run['protocol']['path']).read_text().splitlines()
        m = re.fullmatch(r'DENZA_SERVE_([0-9a-f]{24}):READY', protocol[0])
        assert m
        prefix = f'DENZA_SERVE_{m[1]}:'
        expected = [prefix+'READY']
        identities = set()
        for seq, (step, response) in enumerate(zip(plan, session['responses']), 1):
            assert all(step[k] == response[k] for k in step)
            assert response['seq'] == seq and response['state'] == 'positive' and response['cacheMatch']
            assert 0 <= response['latencyMs'] < 4000 and response['receivedNanos'] > 0
            if step['identityGate']:
                assert step['request'] == '22F194'
                identities.add(step['ecuId'])
            else:
                assert step['ecuId'] in identities
            uds = bytes.fromhex(response['responseHex'])
            assert uds[:3] == bytes.fromhex('62'+step['request'][2:])
            expected += [f"HOST x {seq} {step['address']} {step['request']} 4000", prefix+'BEGIN',
                         f"a {seq} {response['frames']} {response['latencyMs']} {response['receivedNanos']} {response['responseHex']}", prefix+'END ok']
            actual = evidence['selectedEcus'][step['ecuId']]['fields'][step['field']]
            assert actual['actual'] == response['stockFieldValueFromPrefix'] == step['cached']
            assert actual['responseHex'] == response['responseHex']
            rows.append(response)
        assert protocol == expected
    assert len(rows) == evidence['summary']['requests'] == 38
    assert len({r['ecuId'] for r in rows}) == evidence['summary']['selectedEcuRecords'] == 11
    first = read(evidence['firstAttempt']['session'])
    assert first['state'] == 'stopped' and first['responses'] == [] and 'exitCode' not in first
    replay = read(evidence['sources']['parserReplayManifest'])
    source = ROOT / evidence['sources']['parserReplay']['path']
    assert hashlib.sha256(source.read_bytes()).hexdigest() == replay['javaSha256']
    for item in replay['methods']:
        assert hashlib.sha256((ROOT/item['source']).read_bytes()).hexdigest() == item['sourceSha256']
    driver = '''public class CoreFieldsReplay {
      public static void main(String[] a) {
        CanDiagnostic c = CanDiagnostic.fresh();
        for(int i=0;i<a.length;i+=3) {
          byte[] packet=CanDiagnostic.packet(a[i+1],1,0,0);
          String actual=a[i].endsWith("Code") ? c.parseEcuSoftCodeInfo(packet) :
              a[i].equals("ecuHardVer") ? c.parseEcuHardVersionInfo(packet) : c.parseEcuVersionInfo(packet);
          if(!a[i+2].equals(actual))throw new AssertionError(a[i]+": "+actual+" != "+a[i+2]);
        }
        System.out.println("stock_method_matches="+(a.length/3));
      }
    }'''
    with tempfile.TemporaryDirectory(prefix='ota-core-replay-') as tmp:
        file = Path(tmp)/'CoreFieldsReplay.java'; file.write_text(driver)
        subprocess.run([str(JAVA/'javac'), '-d', tmp, str(source), str(file)],check=True,capture_output=True,timeout=30)
        args = [value for r in rows for value in [r['field'],r['responseHex'],r['stockFieldValueFromPrefix']]]
        replayed = subprocess.run([str(JAVA/'java'),'-cp',tmp,'CoreFieldsReplay',*args],check=True,capture_output=True,text=True,timeout=10)
        assert replayed.stdout.strip() == 'stock_method_matches=38'
    print(json.dumps({'passed':True,'referencesVerified':count,'requests':38,'ecuRecords':11,
                      'stockParserMatches':38,'cleanLifecycles':5,'deviceOrNetworkAccess':False,
                      'expectedServerTupleKnown':False,'applicablePackageObtained':False}))


if __name__ == '__main__':
    main()
