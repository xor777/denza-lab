#!/usr/bin/env python3
"""Synthetic original worker acceptance on an explicit Android serial, no cloud."""
import argparse,json,subprocess,struct
from pathlib import Path
from unittest.mock import patch
from native_session import NativeSession
from verify_native_roundtrip import Roundtrip,KEY,UUID,VIN_VALUE,HELPER,PACKET,WIRE
from verify_opaque_native import need,INPUT,read_buffers

def run(serial,source,capture):
 args=(source,VIN_VALUE,KEY,UUID,b'89010000000000000001',b'001010123456789',b'',1700000000)
 nonce=bytes(range(32,48));peer=NativeSession(*args)
 def response(cmd,flag,body):
  peer.u.mem_write(INPUT,body);peer.call(0x6e3b0,HELPER,0,PACKET,cmd,flag,INPUT,len(body),0);peer.call(0x6e858,HELPER,PACKET,WIRE,len(body));return peer.frames[-1]
 discovery=response(200,1,(6041).to_bytes(2,'big')+bytes(20)+bytes([len(b'dilinknat0-cn.denzacloud.com')])+b'dilinknat0-cn.denzacloud.com')
 login=response(220,1,bytes(16));reject=response(220,3,bytes(16))
 # NativeRegistration owns the211-only encoder fixtures.
 from native_registration import NativeRegistration
 r=NativeRegistration(*args);r.u.mem_write(INPUT,b'\1');r.call(0x6e3b0,HELPER,0,PACKET,211,1,INPUT,1,0);r.call(0x6e858,HELPER,PACKET,WIRE,1);registration=r.frames[-1]
 init=[f'{c} {v.hex()}' for c,v in [('V',VIN_VALUE),('K',KEY),('U',UUID),('C',args[4]),('M',args[5]),('S',b''),('T',struct.pack('<I',1700000000)),('N',nonce)]]+['START']
 buffers=read_buffers(capture);request=Roundtrip(source).request()
 cases=[]
 def execute(name,lines,valid=True):
  p=subprocess.run(['adb','-s',serial,'shell','-T','/data/local/tmp/denza-session-worker'],input='\n'.join(lines+['QUIT'])+'\n',capture_output=True,text=True,timeout=20)
  need((p.returncode==0)==valid,'runtime '+name+': '+p.stdout[-180:]+' '+p.stderr[-180:]);cases.append({'name':name,'passed':True,'exit':p.returncode});return p.stdout.splitlines()
 lines=execute('native_bootstrap_and_reply',init+['G','R211 '+registration.hex(),'D','R200 '+discovery.hex(),'L','R220 '+login.hex()]+['I '+b.hex() for b in buffers]+['R511 '+request.hex()])
 wires=[bytes.fromhex(x[5:]) for x in lines if x.startswith('WIRE ')];answer=[x for x in lines if x.startswith('STATUS ')][0].split()
 oracle=NativeSession(*args)
 with patch('native_session.os.urandom',return_value=nonce):
  need(wires[0]==r.packet() and wires[1]==oracle.produce(200) and wires[2]==oracle.produce(220),'native wire mismatch')
 oracle.accept(login,220)
 for b in buffers:oracle.ingest(b)
 need(bytes.fromhex(answer[2])==oracle.accept(request,511),'native reply mismatch')
 need('REG 1' in lines and 'ENDPOINT dilinknat0-cn.denzacloud.com 6041' in lines and 'LOGIN 1' in lines,'native state mismatch')
 execute('rejected_login_preserved',init+['R220 '+reject.hex()])
 execute('prelogin_status_blocked',init+['R511 '+request.hex()],False)
 execute('unexpected_command_blocked',init+['R220 '+discovery.hex()],False)
 bad=login[:-8]+bytes([login[-8]^64])+login[-7:]
 execute('corrupted_login_blocked',init+['R220 '+bad.hex()],False)
 execute('identity_frozen',init+['C '+args[4].hex()],False)
 execute('missing_pair',init[1:],False)
 return {'passed':True,'serial':serial,'cases':cases,'callbacks':len(buffers),'reply_bytes':len(bytes.fromhex(answer[2])),'scope':'Synthetic worker only; no cloud or factory signature'}
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('serial');p.add_argument('firmware',type=Path);p.add_argument('capture',type=Path);a=p.parse_args();print(json.dumps(run(a.serial,a.firmware,a.capture),indent=2))
