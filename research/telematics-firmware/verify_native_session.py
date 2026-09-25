#!/usr/bin/env python3
"""Synthetic fixtures for original discovery/login/status code. No I/O to car/cloud."""
import argparse
import json
from native_session import NativeSession
from verify_native_roundtrip import HELPER, KEY, UUID, VIN_VALUE, PACKET, WIRE, Roundtrip
from verify_opaque_native import INPUT, OBJ, need, read_buffers
from pathlib import Path

def verify(source,capture):
    args=(source,VIN_VALUE,KEY,UUID,b'89010000000000000001',b'001010123456789',b'',1700000000)
    def fresh():return NativeSession(*args)
    peer=fresh()
    def fixture(command,flag,body):
        peer.u.mem_write(INPUT,body)
        peer.call(0x6e3b0,HELPER,0,PACKET,command,flag,INPUT,len(body),0)
        peer.call(0x6e858,HELPER,PACKET,WIRE,len(body));return peer.frames[-1]
    m=fresh();cases=[]
    need(len(m.produce(200))==69 and m.bodies[-1]==b'\1\1','original discovery producer');cases.append('native_discovery_producer')
    host=b'test.denzacloud.com'
    # Fixture only: native server-response parser reads this synthetic body.
    discovery=fixture(200,1,(6003).to_bytes(2,'big')+bytes(20)+bytes([len(host)])+host)
    parsed=m.accept(discovery,200)
    need(parsed['endpoint_host']==host.decode() and parsed['endpoint_port']==6003,'original endpoint parser');cases.append('native_discovery_endpoint_parser')
    need(len(m.produce(220))==117,'original login producer');cases.append('native_login_producer')
    rejected=fixture(220,3,bytes(16))
    need(not m.accept(rejected,220)['login_accepted'],'native rejection lost');cases.append('native_login_rejection')
    accepted=fixture(220,1,bytes(16))
    need(m.accept(accepted,220)['login_accepted'],'native login failed');cases.append('native_login_acceptance')
    for b in read_buffers(capture):m.ingest(b)
    request=Roundtrip(source).request()
    response=m.accept(request,511)
    need(len(response)==181,'native status reply');cases.append('authenticated_login_then_original_status_reply')
    for name in ('wrong_key','corrupt_login','status_before_login','unexpected_command'):
        n=fresh();data=accepted;command=220
        if name=='wrong_key':n.u.mem_write(HELPER+0xf2,b'Z'*16)
        if name=='corrupt_login':data=data[:-8]+bytes([data[-8]^64])+data[-7:]
        if name=='status_before_login':data=request;command=511
        if name=='unexpected_command':data=discovery;command=220
        try:n.accept(data,command)
        except ValueError:pass
        else:raise ValueError('negative accepted: '+name)
        need(not n.sends and n.u.mem_read(OBJ+0x2d0,1)==b'\0','negative side effect');cases.append(name)
    return {'passed':True,'cases':cases,'native_reply_bytes':len(response),'scope':'offline synthetic peer; captured post-login lifecycle'}
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('firmware',type=Path);p.add_argument('capture',type=Path);a=p.parse_args()
    print(json.dumps(verify(a.firmware,a.capture),indent=2))
