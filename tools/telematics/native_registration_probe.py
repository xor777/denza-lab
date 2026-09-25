#!/usr/bin/env python3
"""Bounded 211 exchange using original native codecs; preview by default.

No login, telemetry, actuator commands, modem writes or service restart.
Real identifiers/keys/packets stay in memory. The native code executes in a
bounded host emulator; TLS uses the existing car-origin transport and signer.
Optional IMSI-suffix test includes one original-pair restoration exchange.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import resource
import secrets
import sys
import threading
import time

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'research/telematics-firmware'))
from native_registration import NativeRegistration
from verify_opaque_native import EXPECTED, need
from registration_probe import read_buffer, OWNER_VIN_SHA256
from tls_identity_probe import adb_read, execute, DEFAULT_BINARY, HOST, PORT

FIRMWARE=ROOT/'captures/telematics-20260923/readable-firmware/current-files/system/bin/cloudmanager'
TLS_HASH='ec9bfc505658871e9b1735dc0148a8eb0fc05816017a7057a38bafee40896c03'

def snapshot(serial):
    queries={'boot':['cat','/proc/sys/kernel/random/boot_id'],'tcp':['service','call','cloudmanager','7'],
        'step':['getprop','sys.tcp_step'],'pids':['pidof','cloudmanager','mqttserv','cloudctrlserv'],
        'profile':['getprop','persist.sys.byd.apn_type'],
        'wifi_retention':['settings','get','global','byd_off_wifi_switch']}
    result={k:adb_read(serial,['shell',*v]).strip() for k,v in queries.items()}
    path=adb_read(serial,['shell','pm','path','dev.denza.apps']).strip().removeprefix('package:')
    need(bool(re.fullmatch(r'/data/app/[A-Za-z0-9_~=/+.-]+/base\.apk',path)),'APK path')
    result['apk_sha256']=adb_read(serial,['shell','sha256sum',path]).split()[0]
    return result

def tcp_on(value):return bool(re.fullmatch(r"Result: Parcel\(00000000 00000001\s+'[^']*'\)",value))

def test_imsi(original):
    """One format-preserving variant; identifiers remain in memory only."""
    need(bool(re.fullmatch(rb'[0-9]{15}',original)),'IMSI dimensions')
    return original[:5]+bytes(48+(digit-48+1+secrets.randbelow(9))%10 for digit in original[5:])

def exchange(serial, report, packet, machine, restore_machine=None):
    """At most one test and one original-pair restoration; no retries."""
    try:
        execute(DEFAULT_BINARY,serial,report,(packet,machine.response),HOST,PORT,transport_source='car')
    finally:
        if restore_machine is not None:
            recovery={'scope':'One original-pair 211 restoration','cloud_contacted':False,
                'stock_signature_calls':0,'application_bytes_sent':0}
            report['original_pair_restoration']=recovery
            try:
                restore_machine.timestamp=int(time.time())
                restore_packet=restore_machine.packet()
                execute(DEFAULT_BINARY,serial,recovery,(restore_packet,restore_machine.response),HOST,PORT,transport_source='car')
            except Exception as error:
                recovery.update({'failed':True,'error_type':type(error).__name__,'retry':False})
            response=(recovery.get('native') or {}).get('application_response') or {}
            recovery['accepted']=bool(recovery.get('mutual_tls_verified') and
                response.get('native_handler_executed') and response.get('registration_accepted'))

def run(serial,report,alter_imsi=False):
    report['phase']='baseline'
    report['before']=snapshot(serial)
    need(tcp_on(report['before']['tcp']) and report['before']['step']=='6','stock baseline not healthy')
    need(report['before']['profile']=='double_apn','unexpected stock profile')
    need(hashlib.sha256(DEFAULT_BINARY.read_bytes()).hexdigest()==TLS_HASH,'TLS binary changed')
    # We execute the archived image in a host emulator, not the installed daemon.
    # Android denies shell reads of that executable; do not claim its live hash.
    need(hashlib.sha256(FIRMWARE.read_bytes()).hexdigest()==EXPECTED,'archived firmware changed')
    report['firmware_provenance']={'archived_sha256':EXPECTED,'live_executable_hash_verified':False,
        'live_executable_read':'Permission denied in recorded preflight',
        'installed_build_fingerprint':adb_read(serial,['shell','getprop','ro.build.fingerprint']).strip()}
    baseline=ROOT/'captures/telematics-20260923/battery-upload/extra-files/system/lib64'
    for name in ('libbydauto.so','libbydautoservice.so'):
        need(hashlib.sha256((baseline/name).read_bytes()).hexdigest()==adb_read(serial,['shell','sha256sum','/system/lib64/'+name]).split()[0],'reader library changed')
    report['phase']='native_packet'
    vin=read_buffer(serial,1001,0x9900021a,17)
    need(hashlib.sha256(vin).hexdigest()==OWNER_VIN_SHA256,'different vehicle')
    params=read_buffer(serial,1034,0x99000005,33);need(params[0]!=0,'cloud parameters not ready')
    imsi=adb_read(serial,['shell','getprop','ril.imsi']).strip().encode('ascii')
    iccid=adb_read(serial,['shell','getprop','ril.csim.iccid']).strip().encode('ascii')
    device_serial=adb_read(serial,['shell','getprop','debug.ro.serialno']).strip().encode('ascii')
    restore_machine=None
    if alter_imsi:
        restore_machine=NativeRegistration(FIRMWARE,vin,params[1:17],params[17:33],iccid,imsi,device_serial,int(time.time()))
        restore_machine.packet() # Validate restoration construction before any cloud attempt.
        imsi=test_imsi(imsi)
    machine=NativeRegistration(FIRMWARE,vin,params[1:17],params[17:33],iccid,imsi,device_serial,int(time.time()))
    packet=machine.packet()
    report['inputs']={'owner_vehicle_verified':True,'archived_firmware_verified':True,'reader_libraries_verified':True,
        'identity_source':'original_iccid_changed_imsi_suffix' if alter_imsi else 'current_factory_modem_pair',
        'imsi_prefix_unchanged':True,'imsi_suffix_changed':alter_imsi,
        'native_prepare_reads':machine.prepare_reads,
        'packet_bytes':len(packet),'native_code_host_emulated':True}
    # At most one transport attempt. The old packet encoders are never invoked.
    stop=threading.Event();samples=[]
    def monitor():
        while not stop.is_set() and len(samples)<20:
            entry={'utc':dt.datetime.now(dt.timezone.utc).isoformat()}
            try:
                value=adb_read(serial,['shell','service','call','cloudmanager','7'],timeout=3).strip()
                entry['tcp_connected']=tcp_on(value)
                if not entry['tcp_connected']:stop.set()
            except Exception as error:entry['read_error']=type(error).__name__;stop.set()
            samples.append(entry)
            stop.wait(2)
    watcher=threading.Thread(target=monitor,daemon=True);watcher.start()
    try:
        exchange(serial,report,packet,machine,restore_machine)
    finally:
        stop.set();watcher.join(4)
        report['stock_tcp_samples']=samples;report['monitor_stopped']=not watcher.is_alive()
    response=(report.get('native') or {}).get('application_response') or {}
    report['registration_accepted']=bool(response.get('registration_accepted'))
    report['native_response_handler_verified']=bool(response.get('native_handler_executed'))
    report['login_sent']=False;report['telemetry_sent']=False;report['actuator_commands_sent']=False
    if alter_imsi:
        report['modem_pair_unchanged']=(
            adb_read(serial,['shell','getprop','ril.imsi']).strip().encode('ascii')==restore_machine.identity_properties[b'ril.imsi'] and
            adb_read(serial,['shell','getprop','ril.csim.iccid']).strip().encode('ascii')==iccid)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--execute',action='store_true')
    p.add_argument('--test-imsi-suffix',action='store_true',help='One changed IMSI suffix registration, then one original-pair restoration; stock modem unchanged')
    p.add_argument('--serial',default='127.0.0.1:5555');p.add_argument('--report',type=Path);a=p.parse_args()
    report={'mode':'execute' if a.execute else 'preview','scope':'One native 211 request/response, not a full session',
        'endpoint':f'{HOST}:{PORT}','cloud_contacted':False,'stock_signature_calls':0,
        'application_bytes_sent':0,'login_sent':False,'telemetry_sent':False,'actuator_commands_sent':False}
    if a.test_imsi_suffix:
        report['scope']='One changed-IMSI native 211 plus one original-pair restoration; no full session'
        report['max_registration_attempts']=2
    if not a.execute:
        report['proposal']=('Original ICCID with changed IMSI suffix, same first five digits; one native 211 attempt then one original-pair restoration. No login, modem writes or retries.' if a.test_imsi_suffix else
            'Original native prepare/body/codec and checked reply handler; current owner identity; one car-origin TLS exchange; close. No retries.')
        print(json.dumps(report,indent=2));return
    if not a.report:p.error('--execute requires a new --report path (one-attempt marker)')
    resource.setrlimit(resource.RLIMIT_CORE,(0,0))
    a.report.parent.mkdir(parents=True,exist_ok=True)
    # Existing report, including a failed attempt, is never silently reused.
    fd=os.open(a.report,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'w') as f:
        report['started_utc']=dt.datetime.now(dt.timezone.utc).isoformat();json.dump(report,f);f.flush()
        try:run(a.serial,report,a.test_imsi_suffix)
        except Exception as error:report.update({'failed':True,'error_type':type(error).__name__,'retry':False})
        finally:
            try:
                report['after']=snapshot(a.serial)
                report['endpoint_state_equal']=report.get('before')==report['after']
            except Exception as error:report['after_error']=type(error).__name__
            report['finished_utc']=dt.datetime.now(dt.timezone.utc).isoformat()
            f.seek(0);f.truncate();json.dump(report,f,indent=2);f.write('\n');f.flush()
    print(json.dumps(report,indent=2))
    acceptable_result=(report.get('original_pair_restoration',{}).get('accepted') and
        report.get('modem_pair_unchanged')) if a.test_imsi_suffix else report.get('registration_accepted')
    if not (acceptable_result and report.get('native_response_handler_verified') and
            report.get('mutual_tls_verified') and report.get('endpoint_state_equal') and
            report.get('monitor_stopped') and all(s.get('tcp_connected') for s in report.get('stock_tcp_samples',[]))):
        raise SystemExit(1)

if __name__=='__main__':main()
