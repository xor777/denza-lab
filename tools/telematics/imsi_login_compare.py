#!/usr/bin/env python3
"""Owner-car A/B/A identity experiment using only original native codecs.

No telemetry or actuator dispatch. Modem identifiers/Wi-Fi remain untouched.
Registration0 follows the firmware's70s delay before discovery and login.
Stock cloud is briefly paused with a device-local restoration guard.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import resource
import re
import secrets
import subprocess
import sys
import time

import native_registration_probe as registration
from native_session import NativeSession
from registration_probe import read_buffer, OWNER_VIN_SHA256
from tls_identity_probe import adb_read, execute, DEFAULT_BINARY, HOST, PORT
from verify_opaque_native import need

REMOTE='/data/local/tmp/denza-imsi-compare-20260924'

def now():return dt.datetime.now(dt.timezone.utc).isoformat()
def receive_registration(machine,frame):
    machine.allowed_inbound={211}
    try:return machine.response(frame)
    finally:machine.allowed_inbound=None

def continuation_delay(status):
    # Pinned reset211Variable plus alarm3 table/keepalive3 branch.
    need(status in (0,1),'native registration failure')
    return 70 if status==0 else 0

def test_iccid(original):
    """One user-requested 89860 + 15 digits variant; no modem writes."""
    need(bool(re.fullmatch(rb'[0-9]{20}',original)),'ICCID dimensions')
    suffix=bytes(48+secrets.randbelow(10) for _ in range(15))
    value=b'89860'+suffix
    if value==original:
        value=value[:-1]+bytes([48+(value[-1]-48+1)%10])
    return value

class Comparison:
    def __init__(self,serial,out,test_field='imsi-suffix'):
        need(test_field in ('imsi-suffix','iccid'),'test field')
        self.test_field=test_field
        self.variant_name='changed_suffix' if test_field=='imsi-suffix' else 'changed_iccid'
        self.serial=serial;self.out=out;self.paused=False;self.guard_pid=None
        self.started=time.monotonic();self.calls=0
        self.report={'started_utc':now(),'scope':'Native211/200/220 A/B/A; no telemetry or controls',
            'max_cloud_exchanges':9,'modem_writes':False,'legs':{},'guard_delay_s':480,
            'test_field':test_field}
    def save(self):
        p=self.out/'report.json';temp=self.out/'report.tmp'
        temp.write_text(json.dumps(self.report,indent=2)+'\n');temp.chmod(0o600);temp.replace(p)
    def shell(self,command):return adb_read(self.serial,['shell',command]).strip()
    def stock(self):
        value=self.shell('service call cloudmanager 7')
        need(re.fullmatch(r"Result: Parcel\(00000000 0000000[01]\s+'[^']*'\)",value),'stock TCP read')
        return registration.tcp_on(value)
    def require_paused(self):
        if self.paused:need(not self.stock(),'stock cloud competed with experiment')
    def call(self,machine,command,leg,host,port):
        need(self.calls<9 and time.monotonic()-self.started<420,'experiment exchange/time budget')
        self.require_paused();machine.timestamp=int(time.time())
        packet=machine.packet() if command==211 else machine.produce(command)
        entry={'started_utc':now(),'command':command};leg[str(command)]=entry
        self.calls+=1;self.report['exchanges_started']=self.calls;self.save()
        decoder=(lambda frame:receive_registration(machine,frame)) if command==211 else (lambda frame:machine.accept(frame,command))
        execute(DEFAULT_BINARY,self.serial,entry,(packet,decoder),host,port,transport_source='car')
        entry['finished_utc']=now();self.save()
        need(entry.get('mutual_tls_verified'),'TLS validation missing')
        response=entry.get('native',{}).get('application_response') or {}
        need(not response.get('validation_failed'),'native response invalid')
        return response
    def prepare_leg(self,name,identity):
        iccid,imsi=identity
        leg={'identity_source':name};self.report['legs'][name]=leg;self.save()
        m=NativeSession(registration.FIRMWARE,self.vin,self.params[1:17],self.params[17:33],
            iccid,imsi,self.device_serial,int(time.time()))
        result=self.call(m,211,leg,HOST,PORT)
        need(result.get('native_handler_executed'),'registration handler absent')
        delay=continuation_delay(result.get('registration_status'))
        leg['native_delay_s']=delay;self.save()
        print(name+': registration='+str(result['registration_status'])+', native delay='+str(delay)+'s',flush=True)
        until=time.monotonic()+delay
        while time.monotonic()<until:
            self.require_paused()
            time.sleep(max(0,min(10,until-time.monotonic())))
        result=self.call(m,200,leg,'dilinkaddr-cn.denzacloud.com',6021)
        need(result.get('native_endpoint_parser') and (m.endpoint_host,m.endpoint_port)==('dilinknat0-cn.denzacloud.com',6041),'discovery mismatch')
        return m,leg
    def login(self,m,leg):
        result=self.call(m,220,leg,m.endpoint_host,m.endpoint_port)
        need(result.get('native_handler_executed'),'login handler absent')
        leg['login_accepted']=bool(result.get('login_accepted'));self.save()
        print(leg['identity_source']+': login='+str(leg['login_accepted']),flush=True)
        return leg['login_accepted']
    def arm_and_pause(self):
        need(self.shell('test ! -e '+REMOTE+' && echo ABSENT')=='ABSENT','prior experiment files')
        self.shell('mkdir -m 700 '+REMOTE)
        guard='''#!/system/bin/sh
umask 077
cd /data/local/tmp/denza-imsi-compare-20260924 || exit 1
echo armed > guard.log
n=0
while [ "$n" -lt 480 ]; do
 sleep 1
 [ -e armed ] || exit 0
 n=$((n+1))
done
service call cloudmanager 1 i32 4 >> guard.log
'''
        (self.out/'guard.sh').write_text(guard)
        subprocess.run(['adb','-s',self.serial,'push',str(self.out/'guard.sh'),REMOTE+'/guard.sh'],capture_output=True,check=True,timeout=15)
        self.guard_pid=self.shell('touch '+REMOTE+'/armed; nohup sh '+REMOTE+'/guard.sh >/dev/null 2>&1 </dev/null & echo $!')
        need(self.guard_pid.isdigit(),'guard PID')
        time.sleep(.2);need(self.shell('cat '+REMOTE+'/guard.log')=='armed','guard not armed')
        self.report['guard_pid']=int(self.guard_pid);self.paused=True;self.save()
        self.shell('service call cloudmanager 1 i32 -5')
        end=time.monotonic()+8
        while self.stock() and time.monotonic()<end:time.sleep(.5)
        need(not self.stock(),'stock did not pause')
        self.report['stock_paused_utc']=now();self.save()
    def run(self):
        self.report['before']=registration.snapshot(self.serial);self.save()
        before=self.report['before']
        need(registration.tcp_on(before['tcp']) and before['step']=='6' and before['profile']=='double_apn','baseline not healthy')
        need('wlan0' in self.shell('ip route get 1.1.1.1'),'Wi-Fi route missing')
        need(hashlib.sha256(DEFAULT_BINARY.read_bytes()).hexdigest()==registration.TLS_HASH,'TLS binary changed')
        for name in ('libbydauto.so','libbydautoservice.so'):
            p=registration.ROOT/'captures/telematics-20260923/battery-upload/extra-files/system/lib64'/name
            need(hashlib.sha256(p.read_bytes()).hexdigest()==self.shell('sha256sum /system/lib64/'+name).split()[0],'reader changed')
        self.vin=read_buffer(self.serial,1001,0x9900021a,17)
        need(hashlib.sha256(self.vin).hexdigest()==OWNER_VIN_SHA256,'different vehicle')
        self.params=read_buffer(self.serial,1034,0x99000005,33);need(self.params[0]!=0,'cloud parameters unavailable')
        self.iccid=self.shell('getprop ril.csim.iccid').encode('ascii')
        self.imsi=self.shell('getprop ril.imsi').encode('ascii')
        self.device_serial=self.shell('getprop debug.ro.serialno').encode('ascii')
        variant=((self.iccid,registration.test_imsi(self.imsi)) if self.test_field=='imsi-suffix'
                 else (test_iccid(self.iccid),self.imsi))
        self.report['imsi_operator_prefix']=self.imsi[:5].decode('ascii')
        self.report['variant_constraints']={'iccid_unchanged':variant[0]==self.iccid,
            'imsi_unchanged':variant[1]==self.imsi,'imsi_prefix_unchanged':variant[1][:5]==self.imsi[:5]}
        self.compare_pairs(variant)
    def compare_pairs(self,variant):
        variant_started=False
        try:
            original,leg=self.prepare_leg('original_before',(self.iccid,self.imsi))
            self.arm_and_pause()
            need(self.login(original,leg),'original login did not pass; no variant attempted')
            variant_started=True;self.report['variant_attempted']=True;self.save()
            changed,leg=self.prepare_leg(self.variant_name,variant)
            self.login(changed,leg)
        finally:
            if variant_started:
                try:
                    restored,leg=self.prepare_leg('original_restore',(self.iccid,self.imsi))
                    self.report['original_login_restored']=self.login(restored,leg)
                except Exception as error:
                    self.report['original_restore_error']=type(error).__name__
                self.save()
            self.cleanup()
    def cleanup(self):
        if self.paused:
            try:
                self.shell('service call cloudmanager 1 i32 4')
                end=time.monotonic()+45
                while not self.stock() and time.monotonic()<end:time.sleep(2)
                self.report['stock_restored']=self.stock()
            except Exception as error:self.report['stock_restore_error']=type(error).__name__
        else:self.report['stock_restored']=self.stock()
        if self.guard_pid and self.report.get('stock_restored'):
            self.shell('rm -f '+REMOTE+'/armed')
            args=self.shell('cat /proc/'+self.guard_pid+'/cmdline 2>/dev/null || true')
            if REMOTE+'/guard.sh' in args:self.shell('kill '+self.guard_pid+' 2>/dev/null || true')
            self.report['guard_log']=self.shell('cat '+REMOTE+'/guard.log')
            self.shell('rm -r '+REMOTE);self.report['remote_directory_removed']=True
        self.report['modem_pair_unchanged']=(self.shell('getprop ril.imsi').encode('ascii')==self.imsi and self.shell('getprop ril.csim.iccid').encode('ascii')==self.iccid)
        self.report['after']=registration.snapshot(self.serial)
        self.report['baseline_equal']=self.report['before']==self.report['after'];self.save()

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--execute',action='store_true');p.add_argument('--serial',default='127.0.0.1:5555');p.add_argument('--out',type=Path)
    p.add_argument('--test-field',choices=('imsi-suffix','iccid'),default='imsi-suffix',help='Change only IMSI suffix, or only ICCID using the owner-specified 89860 + 15 digits template')
    a=p.parse_args()
    if not a.execute:
        print(json.dumps({'mode':'preview','cloud_contacted':False,'test_field':a.test_field,'scope':'Original pair, one changed field, original restore: native211/200/220','native_zero_delay_s':70,'max_exchanges':9,'guard_s':480,'no_telemetry_or_controls':True}));return
    if a.out is None:p.error('--out required')
    resource.setrlimit(resource.RLIMIT_CORE,(0,0));os.umask(0o077);a.out.mkdir(mode=0o700,parents=True,exist_ok=False)
    experiment=Comparison(a.serial,a.out,a.test_field)
    try:experiment.run()
    except Exception as error:experiment.report['error_type']=type(error).__name__
    finally:experiment.report['finished_utc']=now();experiment.save()
    r=experiment.report
    print(json.dumps({'legs':{name:{'registration':leg.get('211',{}).get('native',{}).get('application_response',{}).get('registration_status'),'login':leg.get('login_accepted')} for name,leg in r['legs'].items()},'stock_restored':r.get('stock_restored'),'original_login_restored':r.get('original_login_restored'),'error_type':r.get('error_type')},indent=2))
    if r.get('error_type') or not r.get('original_login_restored') or not r.get('baseline_equal'):raise SystemExit(1)

if __name__=='__main__':main()
