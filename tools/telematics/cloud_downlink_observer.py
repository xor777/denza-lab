#!/usr/bin/env python3
"""Bounded read-only live downlink observation, private payloads discarded.

Preview by default. --execute uses an existing ADB serial for logcat only.
Never sends cloud/Binder messages, changes logging settings or clears a buffer.
"""
import argparse
from datetime import datetime, timezone
import json
import re
import selectors
import subprocess
import time

NUMBERS = [
 (r'server_data_to_mcu mFuncNum is (\d{1,5}),replyFlag is (\d{1,3}),mFuncVision (\d{1,3})',
  'tcp_decoded_frame', ('command', 'reply_flag', 'version')),
 (r'socket_received_cb length is (\d{1,6}),encrypt_flag:(\d{1,3})',
  'tcp_bytes_received', ('length', 'encrypted')),
 (r'send_complete status :([01]), key_id :(\d{1,5})', 'tcp_send_complete', ('success', 'command')),
 (r'211 reg_status (\d{1,3})', 'tcp_registration_status', ('status',)),
 (r'rsp 536 mMcuStatus :(\d{1,3}), m536Type :(\d{1,3})',
  'tcp_mcu_status_reply', ('mcu_status', 'request_type')),
 (r'onMessageArrivedV2.*fid.*: (\d{1,6})', 'mqtt_fid_event', ('fid',)),
]
MARKERS = [
 ('[onMessageArrivedV2] topic:', 'mqtt_message_arrived'),
 ('[MqttCloudControlServ]:parseMQTTMsg', 'mqtt_control_parser'),
 ('cloud_msg_arrived_callback', 'mqtt_dispatch_callback'),
 ('[BYDcloudMqttserver] MqttAsync lost connect', 'mqtt_connection_lost'),
 ('532 timeout clear busy', 'tcp_control_timeout'),
 ('recv 532 uuid is same and do nothing!!', 'tcp_control_duplicate'),
 ('532 is busy and return !!!', 'tcp_control_busy'),
 ('532 ik Authentication failed.', 'tcp_control_authentication_failed'),
 ('repaie mode on, can not remote control vehicle', 'tcp_control_repair_mode'),
 ('recv 532 but acc on and speed > 1!!', 'tcp_control_speed_rejection'),
]

def classify(line):
    # Full Android log prefix required; a payload containing these words is not
    # deliberately inspected. Only controlled event labels and bounded numbers survive.
    m = re.match(r'^\s*(\d{10}\.\d+)\s+(\d+)\s+(\d+)\s+[VDIWEF]\s+([^:]+):\s?(.*)$',line)
    if not m:return None
    timestamp,pid,tid,tag,message=m.groups();tag=tag.strip()
    if tag not in ('[BYDCLOUD]main','[BYDCLOUD]socket','mqttserv','cloudctrlserv'):return None
    meta={'epoch':timestamp,'pid':int(pid),'tid':int(tid),'tag':tag}
    for pattern,event,keys in NUMBERS:
        match=re.search(pattern,message)
        if match:return {**meta,'event':event,**dict(zip(keys,map(int,match.groups())))}
    match=re.search(r'recv 532 cmd is 0x([0-9a-fA-F]{1,2})(?=\s|$)',message)
    if match:return {**meta,'event':'tcp_control_subcommand','subcommand':int(match[1],16)}
    match=re.search(r'532_cmd:(\d{1,3})-> reply reult:(sucess|fail|ikey fail) !',message)
    if match:return {**meta,'event':'tcp_control_result','subcommand':int(match[1]),
                     'result':{'sucess':'success','fail':'failure','ikey fail':'authentication_failed'}[match[2]]}
    for marker,event in MARKERS:
        if marker in message:return {**meta,'event':event}
    return None

def observe(serial,seconds):
    command=['adb','-s',serial,'logcat','-b','main','-b','system','-v','epoch','-T','1',
      '[BYDCLOUD]main:V','[BYDCLOUD]socket:V','mqttserv:V','cloudctrlserv:V','*:S']
    started=datetime.now(timezone.utc).isoformat()
    start=time.monotonic();events=[];lines=0;buffer=b'';reason='deadline'
    child=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
    try:
        with selectors.DefaultSelector() as ready:
            ready.register(child.stdout,selectors.EVENT_READ)
            while time.monotonic()-start<seconds and len(events)<512 and lines<20000:
                if not ready.select(timeout=max(0,min(1,seconds-(time.monotonic()-start)))):continue
                import os
                chunk=os.read(child.stdout.fileno(),65536)
                if not chunk:reason='stream_closed';break
                buffer+=chunk
                while b'\n' in buffer:
                    line,buffer=buffer.split(b'\n',1);lines+=1
                    event=classify(line.decode('utf-8',errors='replace'))
                    if event and len(events)<512:events.append(event)
                if len(buffer)>262144:buffer=b''
            else:
                if len(events)>=512 or lines>=20000:reason='event_or_line_limit'
    finally:
        child.terminate()
        try:child.wait(timeout=2)
        except subprocess.TimeoutExpired:child.kill();child.wait(timeout=2)
        child.stdout.close()
    return {'started_utc':started,'finished_utc':datetime.now(timezone.utc).isoformat(),'elapsed_seconds':round(time.monotonic()-start,2),
      'stop_reason':reason,'lines_seen':lines,'events':events,'privacy':'payloads, topics and raw lines discarded',
      'interpretation':'Absence is inconclusive: log levels/retention and phone backend cache can hide a request.'}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--execute',action='store_true')
    p.add_argument('--serial');p.add_argument('--seconds',type=int,default=90);a=p.parse_args()
    if not a.execute:print(json.dumps({'mode':'preview','action':'bounded logcat only','max_seconds':180}));return
    if not a.serial or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:-]{0,127}',a.serial):p.error('explicit valid --serial required')
    if not 1<=a.seconds<=180:p.error('--seconds must be 1..180')
    print(json.dumps(observe(a.serial,a.seconds),indent=2))
if __name__=='__main__':main()
