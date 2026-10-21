#!/usr/bin/env python3
"""Fixed read-only comparison of eleven factory-bound ECU records with OTA cache.

plan/build/test are offline. run owns one existing ADB stream and shared bus lock,
checks P/0 before every send, and removes only its nonce-owned temporary jar.
No native OTA lifecycle, cloud request, UDS session, security or write service.
"""
import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import selectors
import shlex
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
OLD = ROOT / 'captures/diagnostics-followup-20261021/front-zcu/build'
MAP = ROOT / 'research/vehicle-diagnostics/factory-ecu-map-20261020.json'
CACHE = ROOT / 'captures/diagnostics-discovery-20261018/approved-export-1/files/04-ECUVersion.json'
MAP_SHA = '8db4eb52600c869bcf749114ef925f1fb7bba9941babfec0328c1dcfa4de2fac'
CACHE_SHA = '8e31658bc0387da5ea905595da8bb0d3df859bf7b172d3f80d9ce77f9ebd1d53'
OLD_SHA = '526f77af6799492681d7057dd199748d4aad1680b80c1289d344361768c2730a'
HEALTH_SHA = 'c086250984f3fc124ef6c62ad77dd972acd608215ffb35437741948bbe11b741'
HEALTH_REV = '9db9c494'
MAIN = 'dev.denza.diagnostics.probe.DiagnosticBusMain'
JDK = Path('/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin')
SDK = Path('/opt/homebrew/share/android-commandlinetools')
ANDROID = SDK / 'platforms/android-35/android.jar'
STOCK = ('autoservice', 'gbacqservice', 'gbdataservice', 'com.byd.avc',
         'com.byd.otaupdate', 'com.byd.otaupdate2', 'com.byd.productlineota', 'com.byd.bydlogtool')
GROUPS = {
    'body-main': [('CDC_BYD_00100001', '720'), ('B_ZCU_BYD_00100001', '726')],
    'body-rear': [('R_ZCU_BYD_00100001', '747'), ('F_ZCU_BYD_00100001', '722')],
    'power-main': [('ECM_UAES_01200001', '7E0'), ('BMS_BYD_03300001', '7E7'), ('DCOBC_BYD_00100001', '7E4')],
    'drives-front': [('FMCU_BYD_00100001', '7E3'), ('ISG_BYD_00100001', '7E6')],
    'drives-rear': [('SMCU_BYD_00200001', '744'), ('SMCU3_BYD_00100001', '745')],
}


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def dump(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def plans():
    if sha(MAP) != MAP_SHA or sha(CACHE) != CACHE_SHA:
        raise ValueError('Factory map/cache changed; review before use')
    mapping = {r['ecuId']: r for r in json.loads(MAP.read_text())['ecus']}
    cache = {r['ecuId']: r for r in json.loads(CACHE.read_text())['ecuInfo']}
    result = {}
    for name, members in GROUPS.items():
        steps = []
        for suffix, address in members:
            ecu = 'HTH-2021_' + suffix
            route, values = mapping[ecu], cache[ecu]
            pair = f'{address}/{int(address, 16)+8:03X}'
            if (route['ecuDiaId'] != pair or route['ecuReprogramType'] not in ('can', 'canfd')
                    or route['ecuDId'] != 'F195/F194' or route['ecuHardDid'] != 'F193'
                    or route['ecuConfigDid'] not in ('', 'FF95/FF94')):
                raise ValueError('Unexpected selected factory route')
            fields = [('F194', 'ecuSoftCode')]
            if suffix.startswith('F_ZCU_'):
                fields += [('F193', 'ecuHardVer')]
            else:
                fields += [('F195', 'ecuSoftVer'), ('F193', 'ecuHardVer')]
                if route['ecuConfigDid']:
                    fields += [('FF94', 'ecuConfigCode'), ('FF95', 'ecuConfigVer')]
            if not re.fullmatch('[0-9A-F]{18}', values['ecuSoftCode']) or int(values['ecuSoftCode'], 16) == 0:
                raise ValueError('Missing identity prerequisite')
            for did, field in fields:
                steps.append({'ecuId': ecu, 'address': address, 'request': '22'+did,
                              'field': field, 'cached': values[field], 'identityGate': did == 'F194'})
        if len(steps) > 10:
            raise ValueError('Plan exceeds ten fixed requests')
        result[name] = steps
    return result


def decode(line, seq, step):
    m = re.fullmatch(r'a (\d+) (\d+) (\d+) (\d+) ([0-9A-F]+)', line)
    if not m or int(m[1]) != seq or not 1 <= int(m[2]) <= 16 or not 0 <= int(m[3]) < 4000 or int(m[4]) <= 0:
        raise ValueError('Unexpected exchange metadata or non-answer')
    uds = bytes.fromhex(m[5])
    if not 3 <= len(uds) <= 4095:
        raise ValueError('UDS shape outside bound')
    result = dict(step, seq=seq, responseHex=m[5], frames=int(m[2]),
                  latencyMs=int(m[3]), receivedNanos=int(m[4]))
    if len(uds) == 3 and uds[:2] == b'\x7f\x22':
        return dict(result, state='negative', nrcHex=uds[2:].hex().upper())
    if uds[:3] != bytes.fromhex('62'+step['request'][2:]):
        raise ValueError('Wrong response SID or echoed DID')
    data = uds[3:]
    if step['field'].endswith('Code'):
        used = 9
        if len(data) < used:
            raise ValueError('Software/configuration code shorter than nine bytes')
        value = data[:9].hex().upper()
    elif step['field'] == 'ecuHardVer':
        used = 1
        if len(data) < used:
            raise ValueError('Hardware version has no data')
        value = '.'.join(f'{data[0]:03d}')
    else:
        used = 2
        if len(data) < used:
            raise ValueError('Version shorter than two bytes')
        number = int.from_bytes(data[:2], 'big')
        value = f'{number // 10000}.{number % 10000 // 100:02d}.{number % 100:02d}'
    return dict(result, state='positive', stockFieldValueFromPrefix=value,
                cacheMatch=value == step['cached'], additionalDataHex=data[used:].hex().upper())


def self_test():
    fixed = plans()
    step = fixed['body-main'][1]
    r = decode('a 1 1 23 999 62F1959C75AABBCCDD', 1, step)
    assert r['cacheMatch'] and r['additionalDataHex'] == 'AABBCCDD'
    assert decode('a 1 1 23 999 7F2231', 1, step)['state'] == 'negative'
    assert decode('a 1 1 23 999 62F19329AABBCCDD', 1, fixed['body-main'][2])['cacheMatch']
    for bad in ['a 2 1 23 999 62F1959C75', 'a 1 0 23 999 62F1959C75',
                'a 1 1 4000 999 62F1959C75', 'a 1 1 23 999 62F1949C75',
                'a 1 1 23 999 62F1959C', 'a 1 1 23 999 62F1959']:
        try:
            decode(bad, 1, step)
        except ValueError:
            continue
        raise AssertionError('Invalid response accepted')
    assert [len(s) for s in fixed.values()] == [10, 7, 9, 6, 6]
    return fixed


def local(args, log):
    p = subprocess.run(list(map(str, args)), capture_output=True, cwd=ROOT, timeout=60)
    log.write_bytes(p.stdout+p.stderr)
    if p.returncode:
        raise RuntimeError('Local build/check failed; see '+str(log))


def build(output):
    fixed = self_test()
    if sha(OLD/'build.json') != OLD_SHA:
        raise ValueError('Retained bus manifest changed')
    old = json.loads((OLD/'build.json').read_text())
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    source = output/'source'; source.mkdir()
    upstream = []
    for row in old['sources']:
        p = ROOT/row['path']
        if sha(p) != row['sha256']:
            raise ValueError('Retained source changed')
        if p.name in ('DiagnosticWhitelist.java', 'FixedAllowlistTest.java'):
            continue
        text = p.read_text()
        if p.name == 'DiagnosticBusMain.java':
            anchor = '    public static void main(String[] args) {\n'
            assert text.count(anchor) == 1
            text = text.replace(anchor, anchor + '''        Thread deadline = new Thread(() -> {
            try { Thread.sleep(65_000); } catch (InterruptedException ignored) { return; }
            android.os.Process.killProcess(android.os.Process.myPid());
        }, "ota-read-hard-deadline");
        deadline.setDaemon(true);
        deadline.start();
''')
        (source/p.name).write_text(text)
        upstream.append(row)
    pairs = sorted({(int(s['address'], 16), int(s['request'][2:], 16)) for group in fixed.values() for s in group})
    predicate = ' ||\n            '.join(f'(address == 0x{a:03X} && did == 0x{d:04X})' for a, d in pairs)
    whitelist = '''package dev.denza.diagnostics.probe;
public final class DiagnosticWhitelist {
    public static String refusal(int address, byte[] uds) {
        if (uds == null || uds.length != 3 || uds[0] != 0x22) return "read22 only";
        int did = ((uds[1]&255)<<8) | (uds[2]&255);
        return (PREDICATE) ? null : "outside fixed factory pairs";
    }
    public static boolean allowed(int address, byte[] uds) { return refusal(address, uds) == null; }
}
'''.replace('PREDICATE', predicate)
    (source/'DiagnosticWhitelist.java').write_text(whitelist)
    # The independent service-shape check exercises all byte services and addresses;
    # full per-address DID enumeration verifies that no extra DID slipped in.
    array = ','.join('{'+str(a)+','+str(d)+'}' for a,d in pairs)
    test = '''package dev.denza.diagnostics.probe;
public class FixedAllowlistTest {
 public static void main(String[] args) {
  int[][] pairs = new int[][] {PAIRS}; int count=0;
  for (int a=0; a<2048; a++) for (int did:new int[]{0xF193,0xF194,0xF195,0xFF94,0xFF95}) {
   boolean actual=DiagnosticWhitelist.allowed(a,new byte[]{0x22,(byte)(did>>8),(byte)did});
   if (actual) { boolean expected=false; for(int[] pair:pairs) if(pair[0]==a&&pair[1]==did) expected=true;
    if(!expected)throw new AssertionError("extra pair"); count++; }
  }
  if(count!=pairs.length)throw new AssertionError("missing pair");
  for(int[] pair:pairs) for(int did=0;did<65536;did++) {
   boolean expected=false;for(int[] other:pairs)if(other[0]==pair[0]&&other[1]==did)expected=true;
   if(DiagnosticWhitelist.allowed(pair[0],new byte[]{0x22,(byte)(did>>8),(byte)did})!=expected)throw new AssertionError("DID scope");
  }
  for(int sid=0;sid<256;sid++) if(sid!=0x22 && DiagnosticWhitelist.allowed(0x720,new byte[]{(byte)sid,(byte)0xF1,(byte)0x94}))throw new AssertionError("service");
  if(DiagnosticWhitelist.allowed(0x720,null)||DiagnosticWhitelist.allowed(0x720,new byte[]{0x22,(byte)0xF1})||DiagnosticWhitelist.allowed(0x720,new byte[]{0x22,(byte)0xF1,(byte)0x94,0}))throw new AssertionError("shape");
  System.out.println("PASS fixed pairs="+count+"; services and shape");
 }
}
'''.replace('PAIRS', array)
    (source/'FixedAllowlistTest.java').write_text(test)
    health = subprocess.run(['git','show',HEALTH_REV+':tools/vehicle-diagnostics/run_supported_dtc_probe.py'],
                            cwd=ROOT,capture_output=True,check=True,timeout=10).stdout
    if hashlib.sha256(health).hexdigest() != HEALTH_SHA:
        raise ValueError('Reviewed health source mismatch')
    (output/'health.py').write_bytes(health)
    (output/'runner.py').write_bytes(Path(__file__).read_bytes())
    classes=output/'classes';classes.mkdir()
    local([JDK/'javac','--release','8','-cp',ANDROID,'-d',classes,*sorted(source.glob('*.java'))],output/'javac.txt')
    local([JDK/'java','-cp',classes,MAIN.rsplit('.',1)[0]+'.FixedAllowlistTest'],output/'tests.txt')
    jar=output/'ota-field-reader.jar'
    local([SDK/'build-tools/35.0.0/d8','--release','--min-api','33','--lib',ANDROID,'--output',jar,
           *[p for p in sorted(classes.rglob('*.class')) if p.name!='FixedAllowlistTest.class']],output/'d8.txt')
    manifest={'main':MAIN,'jarSha256':sha(jar),'plans':fixed,'upstreamSources':upstream,
              'sources':[{'path':str(p.relative_to(output)),'sha256':sha(p)} for p in [*sorted(source.glob('*.java')),output/'health.py',output/'runner.py']],
              'transformations':['fixed metadata read whitelist only','independent65s self-process hard deadline'],
              'tests':(output/'tests.txt').read_text().strip(),'cloudRequests':0,'productCodeChanged':False,
              'hypothesis':'Retained ECU cache may differ from fresh stock-format identity/version/configuration reads.',
              'expected':'Every fixed22 read returns62 and its DID; known identity code must match before further fields of that ECU.',
              'falsification':'A fresh parsed field differs from cache; retain full declared UDS. Identity mismatch, negative, malformed or missing reply stops batch. No cloud substitution is automatic.',
              'limits':'Stock prefix transformation only; extra bytes have no inferred meaning. Missing configuration DID is not read. No diagnostic session/security change or retries.'}
    dump(output/'build.json',manifest)
    print(json.dumps({'built':str(output),'jarSha256':manifest['jarSha256'],'requests':sum(map(len,fixed.values())),'tests':manifest['tests']}))


def processes(directory):
    pairs=[line.split() for line in (directory/'processes.txt').read_text().splitlines()]
    pids={row[1]:row[0] for row in pairs if len(row)==2 and row[1] in STOCK}
    if set(pids)!=set(STOCK): raise ValueError('Incomplete selected process inventory')
    # Existing product light-event subscriber, not a diagnostic owner. Its
    # TargetedBydLightEventProxyMain only watches0x1330002C/0x38A0002C; the
    # retained bus still takes its own exclusive lock and rejects stray RX.
    if any((row[-1].startswith(('denza_', 'denza-', 'dev.denza.diagnostics')) or 'DiagnosticBus' in row[-1])
           and row[-1] != 'denza_vehicle_signals'
           for row in pairs if row): raise ValueError('Another research reader is active')
    return pids


def run(build_dir, group, output):
    manifest=json.loads((build_dir/'build.json').read_text())
    fixed=self_test()
    if manifest['main']!=MAIN or manifest['plans']!=fixed or sha(build_dir/'ota-field-reader.jar')!=manifest['jarSha256']:
        raise ValueError('Build/plan changed')
    for row in manifest['sources']:
        if sha(build_dir/row['path'])!=row['sha256']: raise ValueError('Snapshot source changed')
    if sha(Path(__file__))!=sha(build_dir/'runner.py'): raise ValueError('Runner changed after build')
    spec=importlib.util.spec_from_file_location('ota_field_health',build_dir/'health.py')
    health=importlib.util.module_from_spec(spec);spec.loader.exec_module(health)
    output.mkdir(parents=True,exist_ok=False,mode=0o700)
    dump(output/'build.json',manifest)
    steps=fixed[group];dump(output/'plan.json',steps)
    nonce=secrets.token_hex(12);nice='denza_ota_fields_'+nonce
    remote='/data/local/tmp/'+nice+'.jar'
    state={'state':'preflight','plan':group,'responses':[],'cloudRequests':0,'apkInstalled':False,'remoteJar':remote,'processName':nice}
    proc=None;staged=False;log=[];stderr=bytearray()
    def adb(args,required=True):
        p=health.adb_command('127.0.0.1:5555',args,limit=15)
        if required and p.returncode: raise RuntimeError('ADB operation failed')
        return p
    try:
        before=health.health('127.0.0.1:5555',output/'preflight')
        if not health.acceptable(before): raise ValueError('Preflight refused')
        pids=processes(output/'preflight')
        if adb(['shell','sha256sum',before['installedApk']]).stdout.decode().split()[0]!=before['appSha256']:
            raise ValueError('APK changed after preflight')
        staged=True;adb(['push',str(build_dir/'ota-field-reader.jar'),remote])
        if adb(['shell','sha256sum',remote]).stdout.decode().split()!=[manifest['jarSha256'],remote]:
            raise ValueError('Staged jar mismatch')
        cmd='CLASSPATH='+shlex.quote(remote)+' exec '+shlex.join(['app_process','/system/bin','--nice-name='+nice,MAIN,'serve',nonce,'500'])
        proc=subprocess.Popen(['adb','-s','127.0.0.1:5555','shell','-T',cmd],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        state['startedAtUtc']=dt.datetime.now(dt.timezone.utc).isoformat()
        sel=selectors.DefaultSelector()
        sel.register(proc.stdout,selectors.EVENT_READ,'out');sel.register(proc.stderr,selectors.EVENT_READ,'err')
        buf=bytearray();deadline=time.monotonic()+60;total=0
        def line(seconds):
            nonlocal total
            until=min(deadline,time.monotonic()+seconds)
            while b'\n' not in buf:
                left=until-time.monotonic()
                if left<=0: raise TimeoutError('Bounded response deadline')
                for key,_ in sel.select(min(left,.25)):
                    chunk=os.read(key.fileobj.fileno(),4096)
                    if not chunk:
                        sel.unregister(key.fileobj)
                        if key.data=='out': raise RuntimeError('Helper exited early')
                    elif key.data=='err':stderr.extend(chunk)
                    else:buf.extend(chunk)
                    total+=len(chunk)
                    if total>256*1024: raise ValueError('Output bound')
            raw,_,tail=buf.partition(b'\n');buf[:]=tail
            result=raw.decode().rstrip('\r');log.append(result);return result
        prefix='DENZA_SERVE_'+nonce+':'
        if line(12)!=prefix+'READY': raise RuntimeError('Missing READY')
        for seq,step in enumerate(steps,1):
            if time.monotonic()>deadline-7: raise TimeoutError('Overall deadline')
            command=f"x {seq} {step['address']} {step['request']} 4000"
            log.append('HOST '+command);proc.stdin.write((command+'\n').encode());proc.stdin.flush()
            if line(7)!=prefix+'BEGIN': raise RuntimeError('Missing BEGIN')
            response=line(6)
            if response.startswith(prefix+'END '): raise RuntimeError(response)
            if line(1)!=prefix+'END ok': raise RuntimeError('Incomplete exchange')
            parsed=decode(response,seq,step);state['responses'].append(parsed)
            if step['identityGate'] and not parsed.get('cacheMatch'):
                raise ValueError('Identity gate differs or has no positive code; raw response retained')
            if parsed.get('state')!='positive':
                raise ValueError('Negative response; stop batch without substituting stock marker')
        state['state']='complete'
    except Exception as error:
        state.update(state='stopped',error=type(error).__name__+': '+str(error))
    finally:
        if proc:
            try:proc.stdin.write(b'quit\n');proc.stdin.flush()
            except (BrokenPipeError,OSError):pass
            proc.stdin.close()
            try:state['exitCode']=proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                # The owned remote process has its own65s kill deadline; do not
                # kill arbitrary remote PIDs or unlink another reader's lock.
                proc.terminate();state['exitCode']=proc.wait(timeout=5);state['hostTerminationRequired']=True
        (output/'protocol.txt').write_text('\n'.join(log)+'\n');(output/'stderr.txt').write_bytes(stderr)
        if staged:
            try:
                adb(['shell','rm','--',remote])
                p=adb(['shell','test','-e',remote],False)
                state['jarRemoved']=p.returncode==1 and not p.stdout and not p.stderr
                p=adb(['shell','pidof',nice],False)
                state['processAbsent']=p.returncode==1 and not p.stdout and not p.stderr
                after=health.health('127.0.0.1:5555',output/'postflight')
                state['healthStable']=(health.acceptable(after) and before['appSha256']==after['appSha256']
                                       and pids==processes(output/'postflight'))
            except Exception as error:state['cleanupError']=type(error).__name__+': '+str(error)
        state['lifecycleVerified']=(state['state']=='complete' and state.get('exitCode')==0
            and state.get('jarRemoved') is True and state.get('processAbsent') is True
            and state.get('healthStable') is True and not state.get('hostTerminationRequired'))
        dump(output/'session.json',state)
    print(json.dumps({k:state.get(k) for k in ['state','plan','error','exitCode','jarRemoved','processAbsent','healthStable','lifecycleVerified']}))
    if not state['lifecycleVerified']:raise SystemExit(2)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['plan','test','build','run'])
    p.add_argument('--output',type=Path);p.add_argument('--build',type=Path);p.add_argument('--group',choices=GROUPS)
    a=p.parse_args()
    if a.action in ('plan','test'):
        fixed=self_test();print(json.dumps({'offlineChecksPassed':True,'plans':fixed if a.action=='plan' else {k:len(v) for k,v in fixed.items()}}))
    elif a.action=='build':
        if not a.output:p.error('build needs --output')
        build(a.output.resolve())
    else:
        if not a.output or not a.build or not a.group:p.error('run needs --build, --group and --output')
        run(a.build.resolve(),a.group,a.output.resolve())
