#!/usr/bin/env python3
"""Bounded original 200/220/511 path, external effects captured in private context.

No vehicle payload layout, DNS, socket or device writes implemented here.
Discovery endpoint, login decision and status reply come from original code.
"""
import os
import re
from unicorn.arm64_const import UC_ARM64_REG_PC, UC_ARM64_REG_LR, UC_ARM64_REG_X0
from native_registration import NativeRegistration
from verify_native_roundtrip import Roundtrip, HELPER
from verify_opaque_native import need, OBJ, AUX

SESSION_RANGES=[(0x6edac,0x6efcc),(0x6f01c,0x6f0ec),
    (0x57790,0x57a24),(0x57abc,0x57af0),(0x57c58,0x57cc4),
    (0x48740,0x487c0),(0x535d4,0x5365c)]

class NativeSession(NativeRegistration):
    def __init__(self,*args):
        self.endpoint_host=None; self.endpoint_port=None
        self.effects=[]; self.allowed_inbound=None
        super().__init__(*args)
        self.put64(0x8eda8,AUX+0x8000);self.put64(0x8eed0,AUX+0x9000)
        self.put64(0x8ecc0,AUX+0xa000)
        self.put64(OBJ+0x2b0,AUX+0xb000)
        need(self.call(0x6dcf0,HELPER)==1,'native preparation rejected')
        need(self.call(0x72e0c,HELPER)==1,'native session parameters rejected')

    def hook(self,engine,at,size,data):
        u=self.u;r=0
        if at==0x6e3b0 and self.reg(3) in (200,220):
            need(0<self.reg(6)<=256,'bootstrap body bound')
            self.bodies.append(bytes(u.mem_read(self.reg(5),self.reg(6))));return
        if at==0x6e3b0 and self.reg(3) in (511,512):
            return Roundtrip.hook(self,engine,at,size,data)
        if at==0x57560:
            # Original decoder has already authenticated/decrypted the frame.
            # Gate before dispatch, so even a valid actuator frame cannot run.
            result=Roundtrip.hook(self,engine,at,size,data)
            if self.decoded:
                need(self.allowed_inbound is not None and self.decoded[-1][0] in self.allowed_inbound,
                     'command outside experiment')
                command,body,flag,version=self.decoded[-1]
                if command==511:
                    need(flag==b'\xfe' and version in (b'\0',b'\1') and len(body)==17,
                         'not a status request')
            return result
        if any(lo<=at<hi for lo,hi in SESSION_RANGES):
            need(int.from_bytes(u.mem_read(at,4),'little')&0xffe0001f!=0xd4000001,'native syscall');return
        if at==0x699a4:
            need(self.reg(1)==16,'nonce size');u.mem_write(self.reg(0),os.urandom(16))
        elif at==0x88570:
            need(self.reg(2)<=128,'memmove bound');u.mem_write(self.reg(0),bytes(u.mem_read(self.reg(1),self.reg(2))));r=self.reg(0)
        elif at==0x88960:
            n=self.reg(2);need(n<=128,'memcmp bound')
            a=bytes(u.mem_read(self.reg(0),n));b=bytes(u.mem_read(self.reg(1),n));r=((a>b)-(a<b))&0xffffffffffffffff
        elif at==0x52ea0:
            need(self.reg(0)==OBJ and self.reg(2)==220,'endpoint resolver boundary')
            host=self.string(self.reg(1),256).decode('ascii')
            need(bool(re.fullmatch(r'[a-z0-9-]+\.denzacloud\.com',host)),'endpoint outside Denza')
            self.endpoint_host=host;r=1
        elif at==0x75b70:
            need(self.reg(0)==AUX+0xb000 and 1<=self.reg(2)<=65535,'endpoint port boundary')
            self.endpoint_port=self.reg(2)
        elif at==0x48018:
            need(self.string(self.reg(1))==b'sys.tcp_step' and self.reg(2) in (4,6),'property effect boundary')
            self.effects.append({'captured':'tcp_step','value':self.reg(2)})
        elif at in (0x480c4,0x4b3e0,0x5d238,0x55b30,0x48280,0x75904):
            self.effects.append({'captured':hex(at),'argument':self.reg(1) if at!=0x55b30 else None})
        elif at==0x39288:pass # RefBase scope destructor.
        else:return super().hook(engine,at,size,data)
        u.reg_write(UC_ARM64_REG_X0,r);u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_LR))

    def produce(self,command):
        need(command in (200,220),'bootstrap producer')
        before=len(self.frames)
        self.call(0x6edac if command==200 else 0x6f01c,HELPER)
        need(len(self.frames)==before+1,'native producer emitted no frame')
        return self.frames[-1]

    def accept(self,frame,command):
        need(command in (200,220,511) and 53<=len(frame)<=1024,'inbound scope')
        self.decoded.clear();self.allowed_inbound={command}
        before=len(self.frames);send_before=len(self.sends)
        try:self.receive(frame)
        finally:self.allowed_inbound=None
        need(len(self.decoded)==1 and self.decoded[0][0]==command,'native decoder rejected')
        if command==200:
            need(self.endpoint_host and self.endpoint_port and self.sends[send_before:]==[220],'native discovery rejected')
            return {'command':200,'endpoint_host':self.endpoint_host,'endpoint_port':self.endpoint_port,
                    'native_endpoint_parser':True,'native_login_send_captured':True}
        if command==220:
            return {'command':220,'login_accepted':self.u.mem_read(OBJ+0x2d0,1)==b'\1',
                    'native_handler_executed':True,'post_login_side_effects_captured':True}
        need(self.sends[send_before:]==[511] and len(self.frames)==before+1,'native status response absent')
        return self.frames[-1]
