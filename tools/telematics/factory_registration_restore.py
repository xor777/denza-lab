#!/usr/bin/env python3
"""Restore this car's current factory SIM pair with original native 211/200/220.

Recovery only: one pair, at most three authenticated cloud exchanges, no
telemetry, controls, modem writes, identity variants or automatic retries.
Preview is the default. The owner must first stop the app's competing READY
loop and confirm that no CUSTOM worker owns the cloud path.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import resource
import subprocess
import time

import imsi_login_compare as original
from registration_probe import read_buffer, OWNER_VIN_SHA256
from verify_opaque_native import EXPECTED, need

REMOTE='/data/local/tmp/denza-factory-restore-20260925'


class Recovery(original.Comparison):
    def __init__(self,serial,out):
        super().__init__(serial,out)
        self.report={'started_utc':original.now(),
            'scope':'Current factory modem pair, original native 211/200/220 only',
            'max_cloud_exchanges':3,'modem_writes':False,'legs':{},
            'guard_delay_s':480,'recovery_only':True}

    def call(self,machine,command,leg,host,port):
        need(self.calls<3 and command in (211,200,220),'recovery exchange bound')
        return super().call(machine,command,leg,host,port)

    def arm_and_pause(self):
        need(self.shell('test ! -e '+REMOTE+' && echo ABSENT')=='ABSENT','prior recovery files')
        self.shell('mkdir -m 700 '+REMOTE)
        guard='''#!/system/bin/sh
umask 077
cd /data/local/tmp/denza-factory-restore-20260925 || exit 1
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
        subprocess.run(['adb','-s',self.serial,'push',str(self.out/'guard.sh'),REMOTE+'/guard.sh'],
                       capture_output=True,check=True,timeout=15)
        self.guard_pid=self.shell('touch '+REMOTE+'/armed; nohup sh '+REMOTE+'/guard.sh >/dev/null 2>&1 </dev/null & echo $!')
        need(self.guard_pid.isdigit(),'guard PID')
        time.sleep(.2)
        need(self.shell('cat '+REMOTE+'/guard.log')=='armed','guard not armed')
        self.report['guard_armed']=True;self.paused=True;self.save()
        self.shell('service call cloudmanager 1 i32 -5')
        end=time.monotonic()+8
        while self.stock() and time.monotonic()<end:time.sleep(.5)
        need(not self.stock(),'stock did not pause')
        self.report['stock_paused_utc']=original.now();self.save()

    def restore_once(self):
        machine,leg=self.prepare_leg('factory_pair',(self.iccid,self.imsi))
        self.report['factory_login_accepted']=self.login(machine,leg)

    def cleanup(self):
        if self.paused:
            try:
                self.shell('service call cloudmanager 1 i32 4')
                end=time.monotonic()+45
                while not self.stock() and time.monotonic()<end:time.sleep(2)
                self.report['stock_tcp_restored']=self.stock()
            except Exception as error:
                self.report['stock_restore_error']=type(error).__name__
        else:
            self.report['stock_tcp_restored']=self.stock()
        if self.guard_pid and self.report.get('stock_tcp_restored'):
            self.shell('rm -f '+REMOTE+'/armed')
            args=self.shell('cat /proc/'+self.guard_pid+'/cmdline 2>/dev/null || true')
            if REMOTE+'/guard.sh' in args:self.shell('kill '+self.guard_pid+' 2>/dev/null || true')
            self.report['guard_log']=self.shell('cat '+REMOTE+'/guard.log')
            self.shell('rm -r '+REMOTE);self.report['remote_directory_removed']=True
        self.report['modem_pair_unchanged']=(
            self.shell('getprop ril.imsi').encode('ascii')==self.imsi and
            self.shell('getprop ril.csim.iccid').encode('ascii')==self.iccid)
        self.report['after']=original.registration.snapshot(self.serial)
        self.report['factory_step6']=self.report['after']['step']=='6'
        self.save()

    def run(self):
        self.report['before']=original.registration.snapshot(self.serial);self.save()
        before=self.report['before']
        need(not original.registration.tcp_on(before['tcp']) and
             before['step']=='6' and before['profile']=='double_apn',
             'recovery baseline requires stock TCP0, step6 and double_apn')
        need(not self.shell('pidof dev.denza.apps || true'),'competing app process')
        need('wlan0' in self.shell('ip route get 1.1.1.1'),'Wi-Fi route missing')
        need(hashlib.sha256(original.DEFAULT_BINARY.read_bytes()).hexdigest()==
             original.registration.TLS_HASH,'TLS binary changed')
        need(hashlib.sha256(original.registration.FIRMWARE.read_bytes()).hexdigest()==EXPECTED,
             'archived firmware changed')
        for name in ('libbydauto.so','libbydautoservice.so'):
            path=original.registration.ROOT/'captures/telematics-20260923/battery-upload/extra-files/system/lib64'/name
            need(hashlib.sha256(path.read_bytes()).hexdigest()==
                 self.shell('sha256sum /system/lib64/'+name).split()[0],
                 'reader library changed')
        self.vin=read_buffer(self.serial,1001,0x9900021a,17)
        need(hashlib.sha256(self.vin).hexdigest()==OWNER_VIN_SHA256,'different vehicle')
        self.params=read_buffer(self.serial,1034,0x99000005,33)
        need(self.params[0]!=0,'cloud parameters unavailable')
        self.iccid=self.shell('getprop ril.csim.iccid').encode('ascii')
        self.imsi=self.shell('getprop ril.imsi').encode('ascii')
        self.device_serial=self.shell('getprop debug.ro.serialno').encode('ascii')
        need(bool(re.fullmatch(rb'[0-9]{20}',self.iccid)) and
             bool(re.fullmatch(rb'[0-9]{15}',self.imsi)),
             'factory modem pair unavailable')
        self.report['inputs']={'owner_vehicle_verified':True,
            'archived_firmware_verified':True,'reader_libraries_verified':True,
            'identity_source':'current_factory_modem_pair',
            'native_code_host_emulated':True}
        self.save()
        try:
            self.arm_and_pause()
            self.restore_once()
        finally:
            self.cleanup()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute',action='store_true')
    parser.add_argument('--serial',default='127.0.0.1:5555')
    parser.add_argument('--out',type=Path)
    args=parser.parse_args()
    if not args.execute:
        print(json.dumps({'mode':'preview','cloud_contacted':False,
            'scope':'Current factory modem pair, original native 211/200/220',
            'requires':'app stopped, CUSTOM absent, stock TCP0/step6/double_apn',
            'max_cloud_exchanges':3,'native_zero_delay_s':70,
            'guard_s':480,'no_telemetry_or_controls':True}))
        return
    if args.out is None:parser.error('--out required')
    resource.setrlimit(resource.RLIMIT_CORE,(0,0));os.umask(0o077)
    args.out.mkdir(mode=0o700,parents=True,exist_ok=False)
    recovery=Recovery(args.serial,args.out)
    try:recovery.run()
    except Exception as error:recovery.report['error_type']=type(error).__name__
    finally:recovery.report['finished_utc']=original.now();recovery.save()
    r=recovery.report
    print(json.dumps({'registration':r['legs'].get('factory_pair',{}).get('211',{}).get('native',{}).get('application_response',{}).get('registration_status'),
        'factory_login_accepted':r.get('factory_login_accepted'),
        'stock_tcp_restored':r.get('stock_tcp_restored'),
        'factory_step6':r.get('factory_step6'),
        'modem_pair_unchanged':r.get('modem_pair_unchanged'),
        'error_type':r.get('error_type')},indent=2))
    if (r.get('error_type') or not r.get('factory_login_accepted') or
        not r.get('stock_tcp_restored') or not r.get('factory_step6') or
        not r.get('modem_pair_unchanged')):raise SystemExit(1)


if __name__=='__main__':main()
