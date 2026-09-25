#!/usr/bin/env python3
"""Bounded replay of opaque SDK buffers through original cloudmanager routines.

No vehicle, cloud, crypto or native syscalls. Field mapping is executed by the
firmware, never reproduced here. Context is isolated/partial: lifecycle, locks,
other consumers, Binder writes and transport are explicitly stubbed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (UC_ARM64_REG_X0, UC_ARM64_REG_SP, UC_ARM64_REG_LR,
    UC_ARM64_REG_PC, UC_ARM64_REG_TPIDR_EL0, UC_ARM64_REG_CPACR_EL1)

EXPECTED='9e36cdbf841d54a3b1ea3631b5d5b867d5908bd191a113f53b5bb4182df82eb9'
OBJ, AUX, HEAP, INPUT, STACK, TLS, STOP, NOOP=[0x200000+i*0x10000 for i in range(8)]
RANGES=[(0x61cf0,0x61f5c),(0x627d4,0x63150),(0x55350,0x55778),
        (0x6f520,0x6f5e8),(0x699f0,0x69a6c),(0x75bdc,0x75d80),
        (0x7f760,0x7f81c),(0x7f81c,0x7f8bc)]

def need(value,why):
    if not value:raise ValueError(why)

def read_buffers(path):
    need(path.stat().st_size<=2_000_000,'capture size')
    buffers=[]
    for line in path.read_text().splitlines():
        m=re.fullmatch(r'\[CloudCanSnapshotProbe\] OPAQUE seq=\d+ t_ns=\d+ bytes=(\d+) raw=([0-9a-f]+)',line)
        if m:
            value=bytes.fromhex(m[2]);need(len(value)==int(m[1]) and 18<=len(value)<=74,'callback bound');buffers.append(value)
    need(0<len(buffers)<=10000,'callback count');return buffers

class Machine:
    def __init__(self,source):
        need(hashlib.sha256(source.read_bytes()).hexdigest()==EXPECTED,'firmware hash')
        self.u=u=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
        with source.open('rb') as f:
            elf=ELFFile(f);ss=[s for s in elf.iter_segments() if s['p_type']=='PT_LOAD']
            u.mem_map(0,0x98000)
            for s in ss:u.mem_write(s['p_vaddr'],s.data())
            for r in elf.get_section_by_name('.rela.dyn').iter_relocations():
                if r['r_info_type']==1027:u.mem_write(r['r_offset'],struct.pack('<Q',r['r_addend']))
        for p in (OBJ,AUX,HEAP,INPUT,STACK,TLS,STOP,NOOP):u.mem_map(p,0x10000)
        u.reg_write(UC_ARM64_REG_TPIDR_EL0,TLS);u.reg_write(UC_ARM64_REG_CPACR_EL1,3<<20)
        self.heap=HEAP;self.bodies=[];self.sends=[];self.calls={};self.busy=False;self.socket_result=0;self.socket_calls=[]
        self.put64(0x8ed00,AUX);u.mem_write(AUX,b'\x01') # Reviewed CAN-FD selector.
        self.put64(0x8eba8,AUX+8) # Native log flag off.
        for at in (0x8ed58,0x8ee00):self.put64(at,AUX)
        self.put64(0x8ed60,AUX+0x100)
        self.put64(0x8ee08,AUX+0x200);self.put64(AUX+0x200,AUX+0x300);self.put64(AUX+0x328,NOOP)
        # Native helper selector buffers; framing itself is not executed in this pass.
        self.put64(0x8eef0,AUX+0x1000);self.put64(0x8eef8,AUX+0x2000)
        u.mem_write(OBJ+0x294,struct.pack('<I',0xff)) # Explicit unavailable override fixture.
        u.hook_add(UC_HOOK_CODE,self.hook)
        self.call(0x61cf0,OBJ)
    def reg(self,n):return self.u.reg_read(UC_ARM64_REG_X0+n)
    def put64(self,p,v):self.u.mem_write(p,struct.pack('<Q',v))
    def get64(self,p):return struct.unpack('<Q',self.u.mem_read(p,8))[0]
    def alloc(self,n):
        need(0<n<=4096 and self.heap+n<HEAP+0x10000,'allocation bound')
        p=self.heap;self.heap=(p+n+15)&~15;self.u.mem_write(p,b'\xcc'*n);return p
    def hook(self,engine,at,size,data):
        u=self.u;r=0;self.calls[hex(at)]=self.calls.get(hex(at),0)+1
        if at in (0x883c0,0x883f0,0x88990,0x885b0,0x6ecf8,0x54320,0x888b0,NOOP):pass
        elif at==0x88dd0:r=16 if self.busy else 0
        elif at==0x3f7f4:r=0 # Other configured consumer: no matching subscription.
        elif at==0x4ab90:
            need(self.reg(1)==1034 and self.reg(2)==0xaa000023,'subscription boundary');self.subscription_len=self.reg(4)
        elif at==0x6dac4:self.put64(self.reg(8),AUX+0x400)
        elif at==0x6e3b0:
            need(self.reg(3) in (511,512) and self.reg(6) in (104,120),'native report boundary')
            self.bodies.append(bytes(u.mem_read(self.reg(5),self.reg(6))))
        elif at==0x6e858:r=0 # Capture stops before framing/encryption; no fake packet output.
        elif at==0x4aedc:self.sends.append(self.reg(1))
        elif at==0x88590:r=self.alloc(self.reg(0))
        elif at in (0x88560,0x887f0):
            need(self.reg(2)<=4096,'copy bound');u.mem_write(self.reg(0),bytes(u.mem_read(self.reg(1),self.reg(2))));r=self.reg(0)
        elif at==0x885a0:
            need(self.reg(2)<=4096,'set bound');u.mem_write(self.reg(0),bytes([self.reg(1)&255])*self.reg(2));r=self.reg(0)
        elif at==0x88600:
            b=bytes(u.mem_read(self.reg(0),128));need(b'\0' in b,'cstring bound');r=b.index(0)
        elif at==0x890a0:
            b=bytes(u.mem_read(self.reg(1),128));need(b'\0' in b,'cstring bound');u.mem_write(self.reg(0),b[:b.index(0)+1]);r=self.reg(0)
        elif at in (0x89450,0x89470):
            need(self.reg(0)==0x123456 and self.reg(2)<=128,'ssl boundary')
            self.socket_calls.append((at,self.reg(1),self.reg(2)))
            if at==0x89450 and self.socket_result>0:u.mem_write(self.reg(1),self.read_value[:self.socket_result])
            r=self.socket_result&0xffffffffffffffff
        elif at==0x89460:need(self.socket_result<=0,'ssl error boundary');r=2
        else:
            need(any(lo<=at<hi for lo,hi in RANGES),'Unexpected native target '+hex(at))
            word=struct.unpack('<I',u.mem_read(at,4))[0];need(word&0xffe0001f!=0xd4000001,'native syscall');return
        u.reg_write(UC_ARM64_REG_X0,r);u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_LR))
    def call(self,at,*args):
        u=self.u
        for i,v in enumerate(args):u.reg_write(UC_ARM64_REG_X0+i,v)
        u.reg_write(UC_ARM64_REG_SP,STACK+0xf000);u.reg_write(UC_ARM64_REG_LR,STOP)
        u.emu_start(at,STOP,timeout=1_000_000,count=150000)
        need(u.reg_read(UC_ARM64_REG_PC)==STOP,'execution bound');return self.reg(0)
    def ingest(self,b):
        self.u.mem_write(INPUT,b);self.call(0x627d4,OBJ,INPUT,len(b));self.u.mem_write(INPUT,b'\xa5'*len(b))
    def report(self,reply=False):
        n=len(self.bodies);self.call(0x55350,OBJ,int(reply));need(len(self.bodies)==n+1,'native report callback');return self.bodies[-1]

def verify(source,buffers):
    m=Machine(source);cases=[]
    need(m.subscription_len==176,'native subscription table')
    empty=m.report();need(not m.sends,'empty cache attempted send');cases.append('empty_cache_suppresses_send')
    before=bytes(m.u.mem_read(OBJ,0x5000))
    m.call(0x627d4,OBJ,0,18)
    for n in range(18):m.call(0x627d4,OBJ,INPUT,n)
    need(bytes(m.u.mem_read(OBJ,0x5000))==before,'invalid input changed context');cases.append('null_and_short_buffers_rejected')
    m.busy=True;m.ingest(buffers[0]);m.busy=False
    need(bytes(m.u.mem_read(OBJ,0x5000))==before,'busy input changed context');cases.append('busy_mutex_drops_input')
    for b in buffers:m.ingest(b)
    first=m.report();need(first!=empty and m.sends==[512],'opaque replay produced no report');cases.append('opaque_replay_produces_native_report')
    need(m.report()==first,'same cache report changed');cases.append('input_reuse_does_not_change_cached_report')
    n=Machine(source)
    for b in buffers:n.ingest(b)
    need(n.report()==first,'fresh context replay differs');cases.append('fresh_context_same_replay_matches')
    # Correlation is opaque synthetic data, copied by the original builder.
    corr=bytes(range(16));m.u.mem_write(OBJ+0x7d8,corr)
    need(m.report(True)==corr+first and m.sends[-1]==511,'native reply correlation');cases.append('native_builder_preserves_opaque_correlation')
    # Original message constructor must own a deep copy of opaque bytes.
    for size in (1,18,128):
        value=bytes((i*37+size)&255 for i in range(size));m.u.mem_write(INPUT,value);m.u.mem_write(INPUT+0x200,b'localhost\0')
        m.call(0x75bdc,AUX+0x500,512,INPUT+0x200,443,INPUT,size,0)
        ptr=m.get64(AUX+0x520);need(ptr!=INPUT and bytes(m.u.mem_read(ptr,size))==value,'message not copied')
        m.u.mem_write(INPUT,b'\xa5'*size);need(bytes(m.u.mem_read(ptr,size))==value,'message retains source pointer')
    cases.append('queued_message_owns_opaque_copy')
    # SSL wrappers forward pointer/length, preserve success, partial I/O and errors.
    m.put64(AUX+0x610,0x123456);m.read_value=bytes(range(32))
    for at in (0x7f760,0x7f81c):
        for result in (32,7,0,-1):
            m.socket_result=result;m.u.mem_write(INPUT,b'\xcc'*32)
            got=m.call(at,AUX+0x600,99,INPUT,32)&0xffffffff
            need(got==(result&0xffffffff) and m.socket_calls[-1][1:]==(INPUT,32),'ssl return or buffer changed')
            if at==0x7f760 and result>0:need(bytes(m.u.mem_read(INPUT,result))==m.read_value[:result],'ssl read bytes changed')
    cases.append('ssl_wrappers_preserve_buffer_length_and_partial_error_results')
    return {'passed':True,'cases':cases,'callback_count':len(buffers),'native_subscription_bytes':m.subscription_len,
       'native_report_bytes':len(first),'native_reply_bytes':len(corr+first),'output_sha256':hashlib.sha256(first).hexdigest(),
       'scope':'Archived raw callbacks replayed offline; partial context and external effects stubbed; no live cloud or full dispatcher',
       'stub_boundaries':['allocation/memory and RefBase','single-thread mutex operations','subscription write captured only','other configured consumers absent/no-op','packet singleton supplied','framing/encryption not executed','send and update-notify captured only','SSL I/O synthetic'],
       'firmware_sha256':EXPECTED}

def main():
    a=argparse.ArgumentParser(description=__doc__);a.add_argument('firmware',type=Path);a.add_argument('capture',type=Path);x=a.parse_args()
    print(json.dumps(verify(x.firmware,read_buffers(x.capture)),indent=2))
if __name__=='__main__':main()
