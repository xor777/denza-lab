#!/usr/bin/env python3
"""One bounded native discovery/login/status test; preview unless --execute.

Stock cloud gate is paused briefly, Wi-Fi/profile/ADB untouched. Device-local
90s guard reopens only the cloud gate if host cleanup cannot. No actuator code.
Native firmware owns all vehicle bodies and protocol codecs. Real data stays in
memory except a private opaque callback evidence file; reports omit identities.
"""
import argparse
from collections import deque
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import resource
import struct
import subprocess
import sys
import threading
import time

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'research/telematics-firmware'))
from native_session import NativeSession
from verify_opaque_native import need, OBJ
from inspect_status512 import inspect_body
from registration_probe import read_buffer, OWNER_VIN_SHA256
from native_registration_probe import snapshot, tcp_on, FIRMWARE, EXPECTED
from tls_identity_probe import adb_read, execute

JAR=ROOT/'captures/telematics-20260924/opaque-native-adapter/opaque-callback.jar'
JAR_HASH='e814997095433a13af0f5537afd07d810d152294d7abaa8007d946114c7d4cbb'
BINARY=ROOT/'captures/telematics-20260924/native-session-status/tls-session-client'
REMOTE='/data/local/tmp/denza-native-session-20260924'

def now():return dt.datetime.now(dt.timezone.utc).isoformat()
def sh(serial,command,timeout=12):return adb_read(serial,['shell',command],timeout=timeout).strip()
def tcp_off(value):return bool(re.fullmatch(r"Result: Parcel\(00000000 00000000\s+'[^']*'\)",value))
def write_json(path,value):
    temp=path.with_suffix('.tmp');temp.write_text(json.dumps(value,indent=2)+'\n');temp.chmod(0o600);temp.replace(path)

def getter(serial,transaction,device,fid):
    reply=adb_read(serial,['shell','service','call','autoservice',str(transaction),'i32',str(device),'i32',str(fid)])
    match=re.search(r'Parcel\(\s*00000000\s+([0-9a-f]{8})',reply)
    need(match,'scalar getter unavailable');return bytes.fromhex(match[1])

class Callbacks:
    def __init__(self,serial,out):
        self.rows=deque(maxlen=10000);self.lines=[];self.ready=threading.Event();self.error=None
        self.file=out.open('x');os.chmod(out,0o600)
        self.process=subprocess.Popen(['adb','-s',serial,'shell','-T',
            f'CLASSPATH={REMOTE}.jar timeout 45s app_process /system/bin dev.denza.tools.CloudCanSnapshotProbe 40 512 --opaque'],
            stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        self.thread=threading.Thread(target=self.read,daemon=True);self.thread.start()
    def read(self):
        try:
            for raw in self.process.stdout:
                line=raw.decode().strip();self.file.write(line+'\n')
                if ' READY callback=' in line:self.ready.set()
                match=re.fullmatch(r'\[CloudCanSnapshotProbe\] OPAQUE seq=\d+ t_ns=(\d+) bytes=(\d+) raw=([0-9a-f]+)',line)
                if match:
                    value=bytes.fromhex(match[3]);need(len(value)==int(match[2]),'callback length')
                    self.rows.append((time.monotonic(),value))
                elif len(self.lines)<100:self.lines.append(line)
        except Exception as error:self.error=type(error).__name__
        finally:self.file.close()
    def ingest(self,machine):
        # Snapshot atomic deque copy; original native callback owns field routing.
        rows=list(self.rows);need(rows and len(rows)<10000 and not self.error,'callback source unavailable')
        source_age=time.monotonic()-rows[-1][0];need(source_age<2,'callback source stale')
        started=time.monotonic()
        for _,value in rows:machine.ingest(value)
        return {'callback_count':len(rows),'last_callback_age_at_ingest_s':round(source_age,3),
            'native_ingest_duration_s':round(time.monotonic()-started,3),
            'last_callback_age_after_ingest_s':round(time.monotonic()-rows[-1][0],3)}
    def close(self):
        if self.process.poll() is None:
            try:self.process.stdin.write(b'STOP\n');self.process.stdin.flush()
            except BrokenPipeError:pass
        try:self.process.wait(timeout=6)
        except subprocess.TimeoutExpired:self.process.terminate();self.process.wait(timeout=3)
        self.thread.join(3)
        result={'returncode':self.process.returncode,'reader_stopped':not self.thread.is_alive(),
            'unregistered':any('UNREGISTERED' in line for line in self.lines),
            'done':[x for x in self.lines if 'DONE ' in x],'error':self.error}
        for stream in (self.process.stdin,self.process.stdout,self.process.stderr):stream.close()
        return result

def run(serial,out,report):
    report['before']=snapshot(serial);write_json(out/'report.json',report)
    need(tcp_on(report['before']['tcp']) and report['before']['step']=='6','stock baseline unhealthy')
    need(report['before']['profile']=='double_apn' and report['before']['wifi_retention']=='1','unexpected baseline')
    need('wlan0' in sh(serial,'ip route get 1.1.1.1'),'car Wi-Fi route unavailable')
    need(sh(serial,'getprop persist.sys.cloud.token_flag')=='1','unprovisioned token lifecycle outside scope')
    need(hashlib.sha256(FIRMWARE.read_bytes()).hexdigest()==EXPECTED,'archived firmware changed')
    need(hashlib.sha256(JAR.read_bytes()).hexdigest()==JAR_HASH,'reader changed')
    for name in ('libbydauto.so','libbydautoservice.so'):
        source=ROOT/'captures/telematics-20260923/battery-upload/extra-files/system/lib64'/name
        need(hashlib.sha256(source.read_bytes()).hexdigest()==sh(serial,'sha256sum /system/lib64/'+name).split()[0],'reader library changed')
    report['firmware']={'archived_sha256':EXPECTED,'installed_executable_hash_verified':False,
        'installed_fingerprint':sh(serial,'getprop ro.build.fingerprint')}
    report['tls_binary_sha256']=hashlib.sha256(BINARY.read_bytes()).hexdigest()
    vin=read_buffer(serial,1001,0x9900021a,17)
    need(hashlib.sha256(vin).hexdigest()==OWNER_VIN_SHA256,'different vehicle')
    params=read_buffer(serial,1034,0x99000005,33);need(params[0]!=0,'cloud parameters unavailable')
    machine=NativeSession(FIRMWARE,vin,params[1:17],params[17:33],
        sh(serial,'getprop ril.csim.iccid').encode('ascii'),sh(serial,'getprop ril.imsi').encode('ascii'),
        sh(serial,'getprop debug.ro.serialno').encode('ascii'),int(time.time()))
    report['discovery']={}
    execute(BINARY,serial,report['discovery'],(machine.produce(200),lambda frame:machine.accept(frame,200)),
            'dilinkaddr-cn.denzacloud.com',6021)
    discovery=report['discovery'].get('native',{}).get('application_response',{})
    need(report['discovery'].get('mutual_tls_verified') and discovery.get('native_endpoint_parser'),'discovery rejected')
    need((machine.endpoint_host,machine.endpoint_port)==('dilinknat0-cn.denzacloud.com',6041),'unreviewed endpoint')
    write_json(out/'report.json',report)
    # Prepare/verify all disposable files before touching the stock gate.
    need(sh(serial,f'ls {REMOTE}* 2>/dev/null || true')=='','prior experiment files exist')
    subprocess.run(['adb','-s',serial,'push',str(JAR),REMOTE+'.jar'],check=True,capture_output=True,timeout=15)
    need(sh(serial,f'sha256sum {REMOTE}.jar').split()[0]==JAR_HASH,'installed callback hash')
    guard=(f'echo armed > {REMOTE}.guard.log\n'
           'sleep 90\n'
           f'if [ -e {REMOTE}.armed ]; then\n'
           f'  service call cloudmanager 1 i32 4 >> {REMOTE}.guard.log\n'
           'fi\n')
    (out/'guard.sh').write_text(guard)
    subprocess.run(['adb','-s',serial,'push',str(out/'guard.sh'),REMOTE+'.guard.sh'],check=True,capture_output=True,timeout=15)
    callbacks=None;guard_pid=None;paused=False
    try:
        guard_pid=sh(serial,f'touch {REMOTE}.armed; nohup sh {REMOTE}.guard.sh > /dev/null 2>&1 < /dev/null & echo $!')
        need(guard_pid.isdigit(),'guard PID')
        need(sh(serial,f'kill -0 {guard_pid}; cat {REMOTE}.guard.log')=='armed','guard not armed')
        report['guard']={'device_local':True,'delay_s':90,'pid':int(guard_pid)}
        callbacks=Callbacks(serial,out/'callbacks.log');need(callbacks.ready.wait(5),'callback reader not ready')
        deadline=time.monotonic()+6
        while len(callbacks.rows)<100 and time.monotonic()<deadline:time.sleep(.1)
        need(len(callbacks.rows)>=100,'too few fresh callbacks')
        report['pause_utc']=now();paused=True
        report['pause_reply']=sh(serial,'service call cloudmanager 1 i32 -5')
        deadline=time.monotonic()+8
        while time.monotonic()<deadline:
            if tcp_off(sh(serial,'service call cloudmanager 7')):break
            time.sleep(.5)
        else:raise ValueError('stock cloud gate did not close')
        write_json(out/'report.json',report)
        def ready():
            need(tcp_off(sh(serial,'service call cloudmanager 7')),'stock session competed before adapter ready')
            report['adapter_ready_utc']=now();write_json(out/'report.json',report)
            print('ADAPTER_READY: refresh vehicle status in the official phone app now',flush=True)
        def reply(frame):
            need(tcp_off(sh(serial,'service call cloudmanager 7')),'stock session competed with adapter')
            report['received_utc']=now();report['live_cache']=callbacks.ingest(machine)
            charging=int.from_bytes(getter(serial,5,1009,0x34400018),'big');need(charging<=255,'charging getter')
            # Original callback's scalar destination; report layout remains native.
            machine.u.mem_write(OBJ+0x294,struct.pack('<I',charging))
            soc=struct.unpack('>f',getter(serial,7,1014,0x4a505038))[0]
            wire=machine.accept(frame,511)
            # Read-only cross-check of the native output; never construct/change it.
            body=machine.bodies[-1][16:];decoded=inspect_body(body)
            need(decoded['soc_percent'] is not None and abs(decoded['soc_percent']-soc)<=0.2,'SOC sources disagree')
            report['native_status']={'incoming_command':machine.decoded[-1][0],'request_bytes':len(frame),
                'reply_bytes':len(wire),'body_bytes':len(body),'soc_percent':decoded['soc_percent'],
                'independent_soc_percent':soc,'charging_getter':charging,
                'post_login_lifecycle':'captured; no configuration, token renewal or actuator calls',
                'context':'original native cache defaults plus fresh SDK callbacks; full field completeness unproved'}
            write_json(out/'report.json',report);return wire
        report['login']={}
        execute(BINARY,serial,report['login'],(machine.produce(220),lambda frame:machine.accept(frame,220),reply,ready),
                machine.endpoint_host,machine.endpoint_port)
        report['native_received_commands']=[x[0] for x in machine.decoded]
        report['native_effects']=machine.effects
        report['status_reply_sent']=any(x.get('event')=='session_write' and x.get('bytes')==181
            for x in report['login'].get('native',{}).get('events',[]))
    finally:
        # Reopen cloud only; preserve profile/Wi-Fi/tunnel. Device guard remains
        # armed until TCP is independently confirmed restored.
        if paused:
            report['restore_utc']=now()
            try:
                report['restore_reply']=sh(serial,'service call cloudmanager 1 i32 4')
                deadline=time.monotonic()+50
                while time.monotonic()<deadline:
                    if tcp_on(sh(serial,'service call cloudmanager 7')):
                        report['restored_tcp']=True;break
                    time.sleep(2)
            except Exception as error:report['restore_error']=type(error).__name__
        if callbacks:report['callback_cleanup']=callbacks.close()
        if not paused or report.get('restored_tcp'):
            sh(serial,f'rm -f {REMOTE}.armed')
            if guard_pid and guard_pid.isdigit():
                # This exact shell is ours; verify its argv before stopping it.
                args=sh(serial,f'cat /proc/{guard_pid}/cmdline 2>/dev/null || true')
                if REMOTE+'.guard.sh' in args:sh(serial,f'kill {guard_pid} 2>/dev/null || true')
            report['guard_log']=sh(serial,f'cat {REMOTE}.guard.log 2>/dev/null || true')
            sh(serial,f'rm -f {REMOTE}.jar {REMOTE}.guard.sh {REMOTE}.guard.log')
            report['remote_files_removed']=sh(serial,f'ls {REMOTE}* 2>/dev/null || true')==''
        report['after']=snapshot(serial)
        report['endpoint_state_equal']=report['before']==report['after']
        write_json(out/'report.json',report)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--execute',action='store_true');p.add_argument('--serial',default='127.0.0.1:5555');p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    if not a.execute:
        print('Preview: native 200, 220 and one possible 511 response; pause cloud gate only, device 90s restore guard. No Wi-Fi/APK/modem changes.');return
    resource.setrlimit(resource.RLIMIT_CORE,(0,0));os.umask(0o077)
    a.out.mkdir(parents=True,exist_ok=False)
    report={'started_utc':now(),'identity_source':'current original modem pair; no identity substitution','max_discovery_attempts':1,'max_login_attempts':1}
    try:run(a.serial,a.out,report)
    except Exception as error:report.update({'failed':True,'error_type':type(error).__name__,'error':str(error)[:120]})
    finally:
        report['finished_utc']=now();write_json(a.out/'report.json',report)
    print(json.dumps(report,indent=2))
    if not (report.get('status_reply_sent') and report.get('endpoint_state_equal') and report.get('remote_files_removed')):raise SystemExit(1)
if __name__=='__main__':main()
