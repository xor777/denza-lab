#!/usr/bin/env python3
"""Finite host owner for the isolated supported-DTC reader. Existing ADB only.

No install, reconnect, property write, factory application or session/security
request. The remote reader owns the shared diagnostic lock and per-send guards.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import selectors
import shlex
import socket
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
MAIN = 'dev.denza.diagnostics.probe.SupportedDtcReadProbe'
FIRMWARE = {
    '/system/lib64/hw/auto.default.so':'5d65fd3b430aec7f2e06496ba427e652613fef995c48c2d4066126dec9f446c7',
    '/system/framework/framework.jar':'aa3acd7738e1fa22c4ec68f69b271fd6938cf7756239c1ac6b30051c0d4fda8c',
    '/system/lib64/libbydautoservice.so':'98f057b5072844420625a530bc26c16c8ba3731ed29d6ef2a752a58f7a65bd65',
}
STOCK = ('autoservice','gbacqservice','gbdataservice','com.byd.avc')
EXPORT_PORTS = {5320,5321,5322,46320,46321,46322}
EXPORT_PATHS = ('/data/local/tmp/logtool','/data/local/tmp/denza-ecu-map-metadata')

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def parse_health(raw):
    profile = raw['profile'].strip()
    protocol = raw['protocol'].strip()
    manager = raw['manager'].strip()
    park = raw['park'].strip()
    speed = raw['speed'].strip()
    paths = raw['app-path'].strip()
    if not re.fullmatch(r'package:/data/app/[A-Za-z0-9_~+=/.-]+/base.apk',paths):
        raise ValueError('Unexpected installed APK path')
    hashes = {}
    for line in raw['firmware-hashes'].splitlines():
        match = re.fullmatch(r'([0-9a-f]{64})\s+(/\S+)',line)
        if not match: raise ValueError('Malformed firmware hash')
        if match[2] in hashes: raise ValueError('Duplicate firmware hash')
        hashes[match[2]] = match[1]
    apk_path = paths[len('package:'):]
    match = re.fullmatch(r'([0-9a-f]{64})\s+'+re.escape(apk_path),raw['app-hash'].strip())
    if not match: raise ValueError('Malformed APK hash')
    pids = {}
    active = []
    for line in raw['processes'].splitlines():
        parts = line.split()
        if len(parts) != 2 or not parts[0].isdigit(): continue
        pid,name = parts
        if name in STOCK:
            if name in pids: raise ValueError('Duplicate stock service')
            pids[name] = int(pid)
        if any(x in name.lower() for x in ('denza-supported-dtc-','denza-child-','denza_chassis',
                    'denza-chassis','denza_raw','denza_ecm','denza_brake','diagnosticbus','denza-ecu-map')):
            active.append({'pid':int(pid),'name':name})
    sockets = raw['sockets']
    if sockets.count('local_address') != 2: raise ValueError('Incomplete socket inventory')
    ports = set()
    for line in sockets.splitlines():
        parts = line.split()
        if len(parts)>3 and parts[3]=='0A': ports.add(int(parts[1].rsplit(':',1)[1],16))
    export_idle = (not ports & EXPORT_PORTS and raw['export-leases'].strip()=='absent'
        and bool(raw['export-threads'].strip())
        and not any(n.startswith(('Zmq-','FileTransfer')) for n in raw['export-threads'].splitlines()))
    guard = (profile=='170' and protocol=='CANFD' and manager=='r@-1_0&w@-1_0'
        and bool(re.fullmatch(r"Result: Parcel\(00000000 00000001\s+'[^']*'\)",park))
        and bool(re.fullmatch(r"Result: Parcel\(00000000 00000000\s+'[^']*'\)",speed)))
    return {'guardPass':guard,'firmwareHashMatch':hashes==FIRMWARE,
            'crashEmpty':not raw['crash'].strip(),'stockPids':pids,'stockPresent':set(pids)==set(STOCK),
            'activeResearchProcesses':active,'exportIdle':export_idle,
            'installedApk':apk_path,'appSha256':match[1]}

def adb_command(serial,args,limit=10):
    # Do not let an adb client implicitly start a replacement server.
    with socket.create_connection(('127.0.0.1',5037),timeout=1): pass
    return subprocess.run(['adb','-s',serial]+args,capture_output=True,timeout=limit)

def health(serial,out):
    out.mkdir(parents=True,exist_ok=False)
    raw = {}
    result = {'utc':dt.datetime.now(dt.timezone.utc).isoformat(),'commands':{},'state':'started'}
    commands = [
        ('profile',['getprop','persist.sys.AutoType']),('protocol',['getprop','sys.car.protocol']),
        ('manager',['getprop','sys.diagmgrsrv.task_bk']),
        ('park',['service','call','autoservice','5','i32','1011','i32','89129008']),
        ('speed',['service','call','autoservice','7','i32','1013','i32','-1807745016']),
        ('processes',['ps','-A','-o','PID,NAME']),('crash',['logcat','-b','crash','-d','-v','time']),
        ('firmware-hashes',['sha256sum',*FIRMWARE]),('app-path',['pm','path','dev.denza.apps']),
        ('sockets',['cat','/proc/net/tcp','/proc/net/tcp6']),
        ('export-leases',["if [ -e /data/local/tmp/logtool ] || [ -L /data/local/tmp/logtool ] || "
            "[ -e /data/local/tmp/denza-ecu-map-metadata ] || [ -L /data/local/tmp/denza-ecu-map-metadata ]; "
            "then echo occupied; else echo absent; fi"]),
        ('export-threads',["p=$(pidof com.byd.bydlogtool); case \"$p\" in ''|*[!0-9]*) exit 1;; esac; "
            "for f in /proc/$p/task/*/comm; do cat \"$f\" || exit 1; done"])]
    try:
        for name,args in commands:
            p = adb_command(serial,['shell',*args])
            (out/(name+'.txt')).write_bytes(p.stdout)
            (out/(name+'.stderr')).write_bytes(p.stderr)
            result['commands'][name] = {'exitCode':p.returncode,'stdoutBytes':len(p.stdout)}
            if p.returncode: raise RuntimeError(name+' failed')
            raw[name] = p.stdout.decode()
        path = raw['app-path'].strip()
        if not re.fullmatch(r'package:/data/app/[A-Za-z0-9_~+=/.-]+/base.apk',path):
            raise ValueError('Unexpected installed APK path')
        p = adb_command(serial,['shell','sha256sum',path[len('package:'):]])
        (out/'app-hash.txt').write_bytes(p.stdout); (out/'app-hash.stderr').write_bytes(p.stderr)
        if p.returncode: raise RuntimeError('APK hash failed')
        raw['app-hash'] = p.stdout.decode()
        result.update(parse_health(raw),state='read_complete')
    except Exception as e:
        result.update(state='failed',error=type(e).__name__+': '+str(e))
    finally:
        (out/'status.json').write_text(json.dumps(result,indent=2)+'\n')
    return result

def acceptable(h):
    return (h.get('state')=='read_complete' and all(h.get(k) for k in
        ('guardPass','firmwareHashMatch','crashEmpty','stockPresent','exportIdle')) and not h.get('activeResearchProcesses'))

def check_build(build):
    manifest = json.loads((build/'manifest.json').read_text())
    jar = build/'supported-dtc-probe.jar'
    if manifest.get('main')!=MAIN or manifest.get('jarSha256')!=sha(jar):
        raise ValueError('Build identity mismatch')
    if not manifest.get('sources'): raise ValueError('No pinned source list')
    for row in manifest['sources']:
        path = (ROOT/row['path']).resolve()
        if not path.is_relative_to(ROOT) or sha(path)!=row['sha256']:
            raise ValueError('Source changed after build: '+row['path'])
    return manifest,jar

def run(serial,build,plan,out):
    manifest,jar = check_build(build)
    out.mkdir(mode=0o700,parents=True,exist_ok=False)
    pinned_manifest = (build/'manifest.json').read_bytes()
    pinned_jar = out/'supported-dtc-probe.jar'
    pinned_jar.write_bytes(jar.read_bytes()); pinned_jar.chmod(0o400)
    if sha(pinned_jar)!=manifest['jarSha256'] or json.loads(pinned_manifest)!=manifest:
        raise ValueError('Build changed during snapshot')
    (out/'manifest.json').write_bytes(pinned_manifest)
    (out/'host-source.py').write_bytes(Path(__file__).read_bytes())
    state = {'state':'preflight','plan':plan,'serial':serial,'jarSha256':sha(pinned_jar),
             'hostSourceSha256':sha(Path(__file__)),
             'manifestSha256':hashlib.sha256(pinned_manifest).hexdigest(),'apkInstalled':False}
    proc = None; staged = False; buffers = {'stdout':bytearray(),'stderr':bytearray()}
    token = secrets.token_hex(12)
    nice = 'denza-supported-dtc-'+token
    remote = '/data/local/tmp/'+nice+'.jar'
    state.update(remoteJar=remote,processName=nice)
    def call(name,args,limit=10,required=True):
        p = adb_command(serial,args,limit)
        (out/(name+'.stdout')).write_bytes(p.stdout); (out/(name+'.stderr')).write_bytes(p.stderr)
        state.setdefault('hostCommands',{})[name] = {'exitCode':p.returncode}
        if required and p.returncode: raise RuntimeError(name+' failed')
        return p
    try:
        before = health(serial,out/'preflight'); state['before'] = before
        if not acceptable(before): raise RuntimeError('Preflight refused')
        # Pin the currently installed artifact; historical versionCode is never a baseline.
        p = call('app-immediate',['shell','sha256sum',before['installedApk']])
        if p.stdout.decode().split()[0]!=before['appSha256']: raise RuntimeError('APK changed after preflight')
        latest,_ = check_build(build)
        if latest!=manifest or sha(build/'manifest.json')!=state['manifestSha256']:
            raise RuntimeError('Build changed after preflight')
        staged=True
        call('stage',['push',str(pinned_jar),remote])
        p=call('jar-hash',['shell','sha256sum',remote])
        if p.stdout.decode().split()!=[state['jarSha256'],remote]: raise RuntimeError('Remote artifact mismatch')
        cmd='CLASSPATH='+shlex.quote(remote)+' exec '+shlex.join(['app_process','/system/bin','--nice-name='+nice,MAIN,plan])
        state['state']='running';state['processStartedUTC']=dt.datetime.now(dt.timezone.utc).isoformat()
        proc=subprocess.Popen(['adb','-s',serial,'shell','-T',cmd],stdin=subprocess.PIPE,
                              stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        sel=selectors.DefaultSelector()
        for name,stream in [('stdout',proc.stdout),('stderr',proc.stderr)]:sel.register(stream,selectors.EVENT_READ,name)
        deadline=time.monotonic()+95
        while sel.get_map():
            left=deadline-time.monotonic()
            if left<=0: raise TimeoutError('Finite host deadline')
            for key,_ in sel.select(min(left,.25)):
                block=os.read(key.fileobj.fileno(),65536)
                if not block:sel.unregister(key.fileobj)
                else:
                    buffers[key.data].extend(block)
                    if sum(map(len,buffers.values()))>2*1024*1024:raise ValueError('Output bound')
        state['exitCode']=proc.wait(timeout=2)
        state['state']='process_exited' if proc.returncode==0 else 'probe_failed'
    except Exception as e:
        state.update(state='stopped',error=type(e).__name__+': '+str(e))
    finally:
        if proc:
            if proc.stdin and not proc.stdin.closed:proc.stdin.close()
            if proc.poll() is None:
                try:proc.wait(timeout=6)
                except subprocess.TimeoutExpired:
                    state['hostTerminationRequired']=True;proc.terminate()
                    try:proc.wait(timeout=2)
                    except subprocess.TimeoutExpired:proc.kill();proc.wait(timeout=2)
            state['exitCode']=proc.returncode
        (out/'probe.txt').write_bytes(buffers['stdout']);(out/'probe.stderr').write_bytes(buffers['stderr'])
        if staged:
            try:
                call('remove-own-jar',['shell','rm','--',remote])
                p=call('verify-jar-absent',['shell','test','-e',remote],required=False)
                state['jarRemoved']=p.returncode==1 and not p.stdout and not p.stderr
                p=call('verify-process-absent',['shell','pidof',nice],required=False)
                state['processAbsent']=p.returncode==1 and not p.stdout.strip() and not p.stderr.strip()
            except Exception as e:state['cleanupError']=str(e)
            after=health(serial,out/'postflight');state['after']=after
            before=state.get('before',{})
            state['healthStable']=(acceptable(after) and after.get('appSha256')==before.get('appSha256')
                                   and after.get('stockPids')==before.get('stockPids'))
        state['lifecycleVerified']=(state['state']=='process_exited' and not state.get('hostTerminationRequired')
            and state.get('jarRemoved') is True and state.get('processAbsent') is True and state.get('healthStable') is True)
        (out/'run.json').write_text(json.dumps(state,indent=2)+'\n')
    print(json.dumps({k:state.get(k) for k in ('state','error','exitCode','jarRemoved','processAbsent','healthStable','lifecycleVerified')}))
    # Wire and target completion must separately pass decode_supported_dtc.py.
    if not state['lifecycleVerified']: raise SystemExit(2)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['health','run'])
    p.add_argument('--serial',required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--build',type=Path);p.add_argument('--plan',choices=[
        'ecm-bms','drives','rear-drives','isg-observe','isg-trace','snapshot-ecm-bms','snapshot-drives',
        'bms-snapshot-record'])
    a=p.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9._:\-]+',a.serial):p.error('Existing serial required')
    if a.action=='health':
        h=health(a.serial,a.output);print(json.dumps(h,indent=2))
        if not acceptable(h):raise SystemExit(2)
    else:
        if not a.build or not a.plan:p.error('run requires --build and --plan')
        run(a.serial,a.build.resolve(),a.plan,a.output.resolve())

if __name__=='__main__':main()
