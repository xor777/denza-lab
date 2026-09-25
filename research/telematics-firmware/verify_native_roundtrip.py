#!/usr/bin/env python3
"""Local original-code status request/reply test; no cloud or vehicle writes.

Synthetic session material, retained opaque SDK data. Both packet directions,
AES/CRC, validation, status dispatch and report layout are executed by firmware.
No Python vehicle schema or packet encoder. Native lifecycle remains partial.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_PC, UC_ARM64_REG_LR, UC_ARM64_REG_SP
from verify_opaque_native import Machine, need, read_buffers, EXPECTED, OBJ, AUX, INPUT

HELPER=AUX+0x400
VIN=AUX+0x3000
PACKET=AUX+0x4000
WIRE=AUX+0x5000
UUID=bytes(range(16,32))
KEY=bytes(range(16))
VIN_VALUE=b'TEST123456789ABCD'
CORRELATION=bytes(range(32,48))
CODEC_RANGES=[(0x6e3b0,0x6ec80),(0x69774,0x69868),
              (0x35828,0x367a4),(0x727bc,0x72eec)]
# Only prelude, validation/rejection exits, and status 511 in the large switch.
DISPATCH_RANGES=[(0x573ec,0x57624),(0x5764c,0x57710),
                 (0x57cac,0x57cb4),(0x57f60,0x57f80)]

class Roundtrip(Machine):
    def __init__(self,source):
        self.frames=[];self.decoded=[];self.decoder_args=None;self.pending_wire=None
        self.logs=[];self.visited=set()
        super().__init__(source)
        # Real hardware rejects null data pointers. The base ELF mapping starts
        # at zero, so explicitly unmap its unused header page for this test.
        self.u.mem_unmap(0,4096)
        self.put64(0x8ed18,AUX+0x700) # Explicit empty auxiliary subscription vector.
        self.put64(0x8ec00,VIN);self.u.mem_write(VIN,VIN_VALUE+b'\0')
        self.put64(0x8ecc8,AUX)
        self.u.mem_write(HELPER+0xe2,UUID);self.u.mem_write(HELPER+0xf2,KEY)
        self.u.mem_write(OBJ+0x615,b'\1');self.u.mem_write(OBJ+0x2d0,b'\1')

    def string(self,p,bound=256):
        b=bytes(self.u.mem_read(p,bound));need(b'\0' in b,'string bound');return b.split(b'\0',1)[0]

    def hook(self,engine,at,size,data):
        self.visited.add(at);u=self.u;r=0
        if at==0x6e3b0:
            need(self.reg(3)==511 or self.reg(3)==512,'status only encoder')
            need(self.reg(6) in (16,104,120),'opaque body size')
            self.bodies.append(bytes(u.mem_read(self.reg(5),self.reg(6))))
        if at==0x6e858:self.pending_wire=self.reg(2)
        if at==0x6ec7c:
            need(self.pending_wire is not None and 0<self.reg(0)<=1024,'wire size')
            self.frames.append(bytes(u.mem_read(self.pending_wire,self.reg(0))));self.pending_wire=None
        if at==0x727bc:
            sp=u.reg_read(UC_ARM64_REG_SP)
            self.decoder_args=(self.reg(5),self.reg(6),self.reg(7),self.get64(sp),self.get64(sp+8))
        if at==0x57560:
            need(self.decoder_args is not None,'decoder call missing')
            cmd,flag,version,body,n=self.decoder_args
            if self.reg(0)&1:
                count=int.from_bytes(u.mem_read(n,2),'little');need(count<=1024,'decoded bound')
                self.decoded.append((int.from_bytes(u.mem_read(cmd,2),'little'),
                    bytes(u.mem_read(body,count)),bytes(u.mem_read(flag,1)),bytes(u.mem_read(version,1))))
            self.decoder_args=None
        if any(lo<=at<hi for lo,hi in CODEC_RANGES+DISPATCH_RANGES):
            word=int.from_bytes(u.mem_read(at,4),'little');need(word&0xffe0001f!=0xd4000001,'native syscall');return
        if at==0x883c0:
            self.logs.append(self.string(self.reg(2)).decode('ascii','replace'))
        elif at==0x887f0:
            need(self.reg(2)<=self.reg(3) and self.reg(2)<=4096,'checked copy bound')
            u.mem_write(self.reg(0),bytes(u.mem_read(self.reg(1),self.reg(2))));r=self.reg(0)
        elif at==0x89010:
            n=self.reg(2);need(n<=17,'comparison bound');a=u.mem_read(self.reg(0),n);b=u.mem_read(self.reg(1),n)
            for x,y in zip(a,b):
                if x!=y:r=(x-y)&0xffffffffffffffff;break
                if x==0:break
        elif at==0x88620:r=len(self.string(self.reg(0),128))
        elif at==0x88640:
            need(self.string(self.reg(0))==b'persist.sys.byd.apn_type','property boundary')
            u.mem_write(self.reg(1),b'double_apn\0');r=10
        elif at==0x88f60:r=1700000000
        elif at in (0x7e934,0x7e97c):pass # Protocol journal; no validation substituted.
        else:return super().hook(engine,at,size,data)
        u.reg_write(UC_ARM64_REG_X0,r);u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_LR))

    def request(self):
        self.u.mem_write(INPUT,CORRELATION)
        self.call(0x6e3b0,HELPER,0,PACKET,511,254,INPUT,len(CORRELATION),1)
        self.call(0x6e858,HELPER,PACKET,WIRE,len(CORRELATION))
        return self.frames[-1]

    def receive(self,frame):
        self.u.mem_write(INPUT,frame)
        self.call(0x573ec,OBJ,0,INPUT,len(frame))

def verify(source,buffers):
    sender=Roundtrip(source);request=sender.request()
    m=Roundtrip(source)
    for b in buffers:m.ingest(b)
    m.report();body=m.bodies[-1];need(len(body)==104,'report fixture')
    m.bodies.clear();m.frames.clear();m.sends.clear();m.logs.clear()
    m.receive(request)
    need(m.sends==[511] and m.bodies==[CORRELATION+body] and len(m.frames)==1,'native request reply')
    need(len(m.decoded)==1 and m.decoded[0][0]==511 and m.decoded[0][1][1:]==CORRELATION,'native request decoder')
    response=m.frames[0]
    peer=Roundtrip(source);peer.u.mem_write(OBJ+0x2d0,b'\0');peer.receive(response)
    need(not peer.sends and peer.decoded[0][0]==511 and peer.decoded[0][1][1:]==CORRELATION+body,'native response decoder')
    cases=['original_request_decode_dispatch_report_encode','original_response_decrypt_preserves_correlation_and_body']
    negative=[]
    for name in ('wrong_uuid','wrong_key','wrong_vin','zero_uuid','zero_key','sentinel_uuid','same_key_uuid','guid_not_ready','not_logged_in','corrupt_payload','bad_magic','truncated_frame'):
        n=Roundtrip(source);frame=request
        if name=='wrong_uuid':n.u.mem_write(HELPER+0xe2,b'X'*16)
        if name=='wrong_key':n.u.mem_write(HELPER+0xf2,b'Y'*16)
        if name=='wrong_vin':n.u.mem_write(VIN,b'OTHER23456789ABCD')
        if name=='zero_uuid':n.u.mem_write(HELPER+0xe2,b'\0'*16)
        if name=='zero_key':n.u.mem_write(HELPER+0xf2,b'\0'*16)
        if name=='sentinel_uuid':n.u.mem_write(HELPER+0xe2,b'\xa7'*16)
        if name=='same_key_uuid':n.u.mem_write(HELPER+0xf2,UUID)
        if name=='guid_not_ready':n.u.mem_write(OBJ+0x615,b'\0')
        if name=='not_logged_in':n.u.mem_write(OBJ+0x2d0,b'\0')
        if name=='corrupt_payload':frame=request[:-8]+bytes([request[-8]^0x40])+request[-7:]
        if name=='bad_magic':frame=bytes([0])+request[1:]
        if name=='truncated_frame':frame=request[:4]
        n.receive(frame)
        need(not n.sends and not n.bodies and not n.frames,'unexpected acceptance '+name)
        need(bool(n.decoded)==(name=='not_logged_in'),'unexpected decoder result '+name)
        negative.append({'case':name,'rejected':True,'reason_formats':n.logs[-2:]})
        cases.append(name)
    return {'passed':True,'cases':cases,'negative':negative,'firmware_sha256':EXPECTED,
        'request_bytes':len(request),'response_bytes':len(response),'report_body_bytes':len(body),
        'callback_count':len(buffers),'response_sha256':hashlib.sha256(response).hexdigest(),
        'native_ranges':[[hex(lo),hex(hi)] for lo,hi in CODEC_RANGES+DISPATCH_RANGES],
        'scope':'Local synthetic session; no cloud login, sockets, actuator dispatch or complete runtime',
        'stubs':['Memory/allocation/RefBase and single-thread locks','Partial cache and supplied singleton/session context',
                 'Synthetic VIN/key/UUID and double_apn property','Empty auxiliary subscription vector; null page unmapped',
                 'Deterministic time and RTT journal no-op',
                 'Subscription and network send captured only; other consumers absent']}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('firmware',type=Path);p.add_argument('capture',type=Path);a=p.parse_args()
    print(json.dumps(verify(a.firmware,read_buffers(a.capture)),indent=2))
if __name__=='__main__':main()
