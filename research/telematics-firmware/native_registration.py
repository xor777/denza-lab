#!/usr/bin/env python3
"""Pinned native 211 producer/consumer; no independent packet schema or I/O.

Used by one bounded cloud test. Identity, frames and keys remain in memory.
External effects are stubbed; native validation is executed unchanged.
"""
import argparse
import json
from pathlib import Path
from unicorn.arm64_const import UC_ARM64_REG_PC, UC_ARM64_REG_LR, UC_ARM64_REG_X0
from verify_opaque_native import need, OBJ, AUX, INPUT, STOP
from verify_native_roundtrip import Roundtrip, HELPER, VIN, VIN_VALUE, KEY, UUID, PACKET, WIRE

REGISTRATION_RANGES=[(0x6d9f4,0x6da9c),(0x6dcf0,0x6dfa0),
    (0x6e00c,0x6e124),(0x7c978,0x7d5c0),(0x57710,0x57740)]

class NativeRegistration(Roundtrip):
    def __init__(self,source,vin,key,uuid,iccid,imsi,serial,timestamp):
        need(len(vin)==17 and len(key)==len(uuid)==16,'identity dimensions')
        need(len(iccid)==20 and iccid.isdigit() and len(imsi)==15 and imsi.isdigit(),'SIM dimensions')
        need(len(serial)<=91 and b'\0' not in serial,'serial dimensions')
        need(0<timestamp<=0xffffffff,'timestamp bound')
        self.identity_properties={b'ril.csim.iccid':iccid,b'ril.imsi':imsi,b'debug.ro.serialno':serial}
        self.timestamp=timestamp;self.prepare_reads=[];self.reset_count=0
        super().__init__(source)
        self.u.mem_write(HELPER,b'\xcc'*0x108)
        self.call(0x6d9f4,HELPER)
        self.u.mem_write(HELPER+0xe2,uuid);self.u.mem_write(HELPER+0xf2,key)
        self.u.mem_write(VIN,vin+b'\0');self.u.mem_write(OBJ+0x2d0,b'\0')
        self.put64(0x8eec8,AUX+0x6000)

    def hook(self,engine,at,size,data):
        self.visited.add(at);u=self.u;r=0
        if at==0x6e3b0:
            need(self.reg(3)==211 and self.reg(6) in (1,35),'registration encoder boundary')
            self.bodies.append(bytes(u.mem_read(self.reg(5),self.reg(6))));return
        if at==0x6e124:
            # The native frame is complete; omit the diagnostic hex postamble.
            u.reg_write(UC_ARM64_REG_PC,STOP);return
        if any(lo<=at<hi for lo,hi in REGISTRATION_RANGES):
            need(int.from_bytes(u.mem_read(at,4),'little')&0xffe0001f!=0xd4000001,'native syscall');return
        if at==0x88640 and self.string(self.reg(0)) in self.identity_properties:
            name=self.string(self.reg(0));value=self.identity_properties[name]
            need(self.reg(2)==0,'identity fallback');self.prepare_reads.append(name.decode())
            u.mem_write(self.reg(1),value+b'\0');r=len(value)
        elif at==0x88470:
            need(self.reg(0)==16,'MD5 helper allocation');r=self.alloc(16)
        elif at in (0x7c920,0x88410):pass # MD5 wrapper/RefBase bookkeeping only.
        elif at==0x88f60:r=self.timestamp
        elif at==0x54964:
            need(self.reg(0)==OBJ,'registration continuation context');self.reset_count+=1
        else:return super().hook(engine,at,size,data)
        u.reg_write(UC_ARM64_REG_X0,r);u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_LR))

    def packet(self):
        need(self.call(0x6dcf0,HELPER)==1,'native preparation rejected')
        need(self.call(0x72e0c,HELPER)==1,'native session parameters rejected')
        before=len(self.frames);self.call(0x6e00c,HELPER)
        need(len(self.frames)==before+1 and len(self.frames[-1])==101,'native registration packet')
        return self.frames[-1]

    def response(self,frame):
        need(5<=len(frame)<=1024,'response length bound')
        self.decoded.clear();self.reset_count=0;self.u.mem_write(OBJ+0x35c,b'\xff')
        before=len(self.frames);self.receive(frame)
        need(len(self.frames)==before and not self.sends,'unexpected outbound continuation')
        need(len(self.decoded)==1 and self.decoded[0][0]==211 and self.reset_count==1,'native registration response rejected')
        status=self.u.mem_read(OBJ+0x35c,1)[0]
        return {'native_decoder_accepted':True,'native_handler_executed':True,'command':211,
            'registration_status':status,'registration_accepted':status==1,
            'continuation_captured_only':True}

def verify(source):
    args=(source,VIN_VALUE,KEY,UUID,b'89010000000000000001',b'001010123456789',b'',1700000000)
    m=NativeRegistration(*args);packet=m.packet()
    need(len(m.bodies)==1 and m.bodies[0]==args[5]+args[4],'native identity source mismatch')
    sender=NativeRegistration(*args)
    def reply(status):
        # Synthetic server fixture only; the live path never assembles replies.
        sender.u.mem_write(INPUT,bytes([status]));sender.call(0x6e3b0,HELPER,0,PACKET,211,1,INPUT,1,0)
        sender.call(0x6e858,HELPER,PACKET,WIRE,1);return sender.frames[-1]
    good=reply(1);need(m.response(good)['registration_accepted'],'success fixture')
    rejected=reply(3);result=m.response(rejected)
    need(result['registration_status']==3 and not result['registration_accepted'],'rejection altered')
    zero=reply(0);result=m.response(zero)
    need(result['registration_status']==0 and not result['registration_accepted'],'zero status altered')
    cases=['original_prepare_build_and_encode','native_success_status','native_rejection_preserved','native_zero_status_preserved']
    for name in ('wrong_key','wrong_vin','corrupt_ciphertext'):
        n=NativeRegistration(*args);data=good
        if name=='wrong_key':n.u.mem_write(HELPER+0xf2,b'Z'*16)
        if name=='wrong_vin':n.u.mem_write(VIN,b'OTHER23456789ABCD')
        if name=='corrupt_ciphertext':data=good[:-8]+bytes([good[-8]^64])+good[-7:]
        try:n.response(data)
        except ValueError:pass
        else:raise ValueError('invalid fixture accepted')
        need(not n.reset_count,'invalid fixture reached registration handler');cases.append(name)
    return {'passed':True,'cases':cases,'request_bytes':len(packet),'success_reply_bytes':len(good),
            'scope':'Synthetic offline native 211; no cloud or car'}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('firmware',type=Path);a=p.parse_args()
    print(json.dumps(verify(a.firmware),indent=2))
