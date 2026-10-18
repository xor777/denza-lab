#!/usr/bin/env python3
"""Exact-hash OFFLINE ARM64 interpreter: FIDs, registration, codec.

No firmware is executed natively; a small pure-Python ARM64 interpreter
evaluates an allowlist of instructions in synthetic memory. All external calls are allowlisted hooks.
No sockets, device paths, syscalls or live Android interaction are possible.
Run from repository root with .venv-firmware/bin/python.
"""
import argparse
import re
import json
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'research/cluster-native'))
from inspect_native import Native
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

PATH = ROOT/'captures/firmware/ivi-34.1.33.2605218.1/system/system/lib64/hw/auto.default.so'
SHA = '5d65fd3b430aec7f2e06496ba427e652613fef995c48c2d4066126dec9f446c7'
OUT = ROOT/'captures/diagnostics-discovery-20261018/fault-protocol-followup'
TARGETS = (0xcaf3b, 0xc5a52, 552969, 714594)
HEAP, STACK, TLS, OBJ, MAPPER, IDS, STOP, BUF = (
    0x400000, 0xc00000, 0xc20000, 0xc30000, 0xc40000, 0xc50000, 0xc60000, 0xc70000)
FUNCTIONS = (0x821a0,0x82a80,0x82e00,0x83190,0x83f20,0x84420,0x84480,0x89460,0xb0a70,
             0xb3460, 0xb5170, 0xb6560, 0x583d0, 0x58960, 0x58760,
             0x58190,0x59390,0x1af8a0,0x1af2d0,0x1b1740,
             0xb7ab0,0xe1500,0x103020)

class PureArm64:
    def __init__(self,native):
        self.n=native
        self.mem=bytearray(0x1000000)
        self.reg={}
        self.z=False
        self.c=False
        self.pc=0
        self.hook=None
        self.ins={}
        cs=Cs(CS_ARCH_ARM64,CS_MODE_ARM)
        for s in native.elf.iter_segments():
            if s['p_type']=='PT_LOAD': self.write(s['p_vaddr'],s.data())
        for a in (0x89460,0xb7ab0,0xe1500,0x103020,0x1af8a0,0xb5170):
            for i in cs.disasm(native.bytes_at(a,native.functions[a]['size']),a):
                self.ins[i.address]=(i.mnemonic,self.split(i.op_str))
        # Linker-added/out-of-line literal-load stubs used by AutoID.
        for i in cs.disasm(native.bytes_at(0x1cf700,0x6c),0x1cf700):
            self.ins[i.address]=(i.mnemonic,self.split(i.op_str))

    @staticmethod
    def split(s): return re.split(r',\s*(?![^\[]*\])',s) if s else []
    def read(self,a,n):
        if not 0<=a<=len(self.mem)-n or n>0x10000: raise ValueError('Memory read bound')
        return bytes(self.mem[a:a+n])
    def write(self,a,b):
        if not 0<=a<=len(self.mem)-len(b): raise ValueError('Memory write bound')
        self.mem[a:a+len(b)]=b
    def width(self,r):
        if r.startswith(('x','sp')): return 64
        if r.startswith('w'): return 32
        if r.startswith('q'): return 128
        if r.startswith('d'): return 64
        if r.startswith('s'): return 32
        if r.startswith('v'):
            part=r.split('.')[1]
            if '[' in part: return {'b':8,'h':16,'s':32,'d':64}[part[0]]
            return int(part[:-1])*{'b':8,'h':16,'s':32,'d':64}[part[-1]]
        raise ValueError('Register '+r)
    def key(self,r):
        if r.startswith(('w','x')) and r not in ('xzr','wzr'): return 'x'+r[1:]
        if r.startswith(('q','d','s','v')) and r!='sp': return 'v'+re.search(r'\d+',r).group()
        return r
    def r(self,r):
        if r.startswith('#'): return int(r[1:],0)
        if r in ('xzr','wzr'): return 0
        val=self.reg.get(self.key(r),0)
        if '[' in r: val >>= self.width(r)*int(re.search(r'\[(\d+)\]',r).group(1))
        return val&((1<<self.width(r))-1)
    def w(self,r,v):
        if r in ('xzr','wzr'): return
        width=self.width(r); key=self.key(r); v &= (1<<width)-1
        if '[' in r:
            shift=width*int(re.search(r'\[(\d+)\]',r).group(1))
            self.reg[key]=(self.reg.get(key,0)&~(((1<<width)-1)<<shift))|(v<<shift)
        else: self.reg[key]=v
    def cond(self,c):
        return {'eq':self.z,'ne':not self.z,'lo':not self.c,'hs':self.c,'hi':self.c and not self.z,'ls':not self.c or self.z}.get(c)
    def operand(self,args,index):
        v=self.r(args[index])
        if index+1<len(args) and args[index+1].startswith('lsl '): v<<=int(args[index+1][5:],0)
        if index+1<len(args) and args[index+1].startswith('lsr '): v>>=int(args[index+1][5:],0)
        return v
    def address(self,args,i):
        s=args[i]; parts=self.split(s[s.index('[')+1:s.index(']')]); base=self.r(parts[0])
        off=self.operand(parts,1) if len(parts)>1 else 0
        a=base+off
        if s.endswith('!'):self.w(parts[0],a)
        if len(args)>i+1 and args[i+1].startswith('#'):self.w(parts[0],base+self.r(args[i+1]))
        return a
    def run(self,start):
        self.pc=start
        for count in range(500000):
            pc=self.pc
            if pc not in self.ins: raise ValueError(f'Unknown PC {pc:#x}')
            op,a=self.ins[pc]
            nextpc=pc+4
            if op in ('mov','movi','fmov','adrp'): self.w(a[0],self.r(a[1]))
            elif op=='movk':
                shift=int(a[2][5:],0) if len(a)>2 else 0
                self.w(a[0],(self.r(a[0])&~(65535<<shift))|(self.r(a[1])<<shift))
            elif op in ('add','sub','orr','ands'):
                x,y=self.r(a[1]),self.operand(a,2)
                z={'add':lambda:x+y,'sub':lambda:x-y,'orr':lambda:x|y,'ands':lambda:x&y}[op]()
                self.w(a[0],z)
                if op=='ands':self.z=self.r(a[0])==0;self.c=False
            elif op in ('tst','cmp'):
                x,y=self.r(a[0]),self.operand(a,1)
                self.z=(x&y)==0 if op=='tst' else x==y
                self.c=False if op=='tst' else x>=y
            elif op=='csel':self.w(a[0],self.r(a[1] if self.cond(a[3]) else a[2]))
            elif op=='csetm':self.w(a[0],-int(self.cond(a[1])))
            elif op=='csinc':self.w(a[0],self.r(a[1]) if self.cond(a[3]) else self.r(a[2])+1)
            elif op=='dup':
                width={'b':8,'h':16,'s':32,'d':64}[a[0][-1]]
                self.w(a[0],sum(self.r(a[1])<<(j*width) for j in range(self.width(a[0])//width)))
            elif op in ('bit','bif','bsl'):
                d,x,y=[self.r(p) for p in a]
                v=(d&~y)|(x&y) if op=='bit' else (d&y)|(x&~y) if op=='bif' else (d&x)|(~d&y)
                self.w(a[0],v)
            elif op in ('ldr','ldur','ldrb'):
                length=1 if op=='ldrb' else self.width(a[0])//8
                self.w(a[0],int.from_bytes(self.read(self.address(a,1),length),'little'))
            elif op in ('str','stur','strb'):
                length=1 if op=='strb' else self.width(a[0])//8
                self.write(self.address(a,1),(self.r(a[0])&((1<<(length*8))-1)).to_bytes(length,'little'))
            elif op in ('stp','ldp'):
                addr=self.address(a,2); length=self.width(a[0])//8
                for j in (0,1):
                    if op=='stp':self.write(addr+j*length,self.r(a[j]).to_bytes(length,'little'))
                    else:self.w(a[j],int.from_bytes(self.read(addr+j*length,length),'little'))
            elif op=='mrs':
                if a[1]!='tpidr_el0':raise ValueError('Unallowed system register')
                self.w(a[0],0xc20000)
            elif op=='b':nextpc=self.r(a[0])
            elif op.startswith('b.'):
                c=self.cond(op[2:])
                if c is None:raise ValueError('Unsupported condition')
                if c:nextpc=self.r(a[0])
            elif op in ('cbz','cbnz'):
                if (self.r(a[0])==0)==(op=='cbz'):nextpc=self.r(a[1])
            elif op in ('tbz','tbnz'):
                if ((self.r(a[0])>>self.r(a[1]))&1)==(0 if op=='tbz' else 1):nextpc=self.r(a[2])
            elif op=='bl':
                self.w('x30',nextpc)
                self.hook(self.r(a[0]))
            elif op=='lsr': self.w(a[0],self.r(a[1])>>self.r(a[2]))
            elif op=='bfxil':
                low,nbits=self.r(a[2]),self.r(a[3]);mask=(1<<nbits)-1
                self.w(a[0],(self.r(a[0])&~mask)|((self.r(a[1])>>low)&mask))
            elif op=='rev':
                width=self.width(a[1])//8
                self.w(a[0],int.from_bytes(self.r(a[1]).to_bytes(width,'little'),'big'))
            elif op=='ret':return
            else:raise ValueError(f'Unsupported instruction {pc:#x}: {op} {a}')
            self.pc=nextpc
        raise ValueError('Instruction count bound')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary',type=Path,default=PATH,help='Exact inspected IVI auto.default.so (SHA gated)')
    parser.add_argument('--output',type=Path,default=OUT,help='Offline report/disassembly directory')
    args=parser.parse_args()
    binary=args.binary.resolve()
    output=args.output.resolve()
    output.mkdir(parents=True,exist_ok=True)
    n = Native(binary)
    if n.sha256 != SHA:
        raise ValueError('Unreviewed binary SHA256: '+n.sha256)
    cs = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    def dump(a, size=None, name=None):
        f = n.functions[a] if size is None else {'size':size, 'name': name or hex(a)}
        lines = [f'# {n.label(f["name"])} / binary SHA256 {SHA}']
        for i in cs.disasm(n.bytes_at(a, f['size']), a):
            s = f'{i.address:08x}: {i.mnemonic:8s} {i.op_str}'
            if i.mnemonic in ('b','bl') and i.op_str.startswith('#'):
                s += ' ; ' + n.targets.get(int(i.op_str[1:],16), '')
            lines.append(s)
        (output/f'{a:x}.asm').write_text('\n'.join(lines)+'\n')
    for a in FUNCTIONS:
        dump(a)
    u=PureArm64(n)
    heap=HEAP
    registrations=[]
    ota_registrations=[]
    control_registrations=[]
    all_counts={}
    mapkeys={}
    setter_checks=[]
    variant=None
    def reg(i): return u.r('x'+str(i))
    def word(p): return int.from_bytes(u.read(p,4),'little')
    def hook(address):
        nonlocal heap
        name=n.plt.get(address,'')
        result=0
        if name.endswith('FeatureMapper9initValueERKi'):
            t=word(reg(1))
            if t>10: result=0
            else:
                result=heap
                heap+=0x140
                u.write(result,bytes(0x140))
                u.write(result+12,t.to_bytes(4,'little'))
                rawtype=1 if t in (0,1,2,3,9) else 2 if t in (4,5,6,10) else 3
                u.write(result+0x30,rawtype.to_bytes(4,'little'))
        elif 'setQurParameter' in name or 'setReqParameter' in name:
            fid=word(reg(1))
            all_counts[variant]=all_counts.get(variant,0)+1
            mapname='get' if 'setQur' in name else 'set'
            mapkeys.setdefault((variant,mapname),set()).add(fid)
            raw=u.read(reg(2),0x38)
            device=struct.unpack_from('<I',raw,8)[0]
            if fid in TARGETS or device==1032 or fid in (0x27500038,0x05500030,0x44900818):
                item={'variant':variant,'fid':hex(fid),'map':'get' if 'setQur' in name else 'set',
                    'call':hex(u.pc),'device':struct.unpack_from('<I',raw,8)[0],
                    'type':struct.unpack_from('<I',raw,12)[0],
                    'req_qur_flag':struct.unpack_from('<I',raw,16)[0],
                    'callbacks':[hex(struct.unpack_from('<Q',raw,o)[0]) for o in (24,32,40)],
                    'parameter_hex':raw.hex()}
                if fid in TARGETS:registrations.append(item)
                if device==1032:ota_registrations.append(item)
                if fid in (0x27500038,0x05500030,0x44900818):control_registrations.append(item)
        elif name in ('memcpy','__memcpy_chk'):
            if reg(2)>0x10000: raise ValueError('Copy bound')
            u.write(reg(0),u.read(reg(1),reg(2)))
            result=reg(0)
        elif 'vectorIj' in name and 'assign' in name:
            if not 0<=reg(2)-reg(1)<=0x10000: raise ValueError('Vector bound')
        elif 'customLogLine' in name:
            pass
        elif 'FeatureMapper7isReqId' in name:
            result=int(word(reg(1)) in mapkeys[(variant,'set')])
            setter_checks.append({'variant':variant,'fid':hex(word(reg(1))),'is_registered':bool(result),'call':hex(u.pc)})
        else: raise ValueError(f'Unallowed call target {address:#x} {name}')
        u.w('x0',result)
    u.hook=hook
    def call(a,args):
        u.w('sp',STACK+0xff00)
        u.w('x30',STOP)
        for i,arg in enumerate(args): u.w('x'+str(i),arg)
        u.run(a)
        return reg(0)&0xffffffff
    variants=[]
    for canfd,second,start in [(0,0,0xb7ab0),(0,1,0xe1500),(1,0,0x103020)]:
        variant=f'canfd={canfd},second={second},table={start:#x}'
        heap=HEAP
        u.write(IDS,bytes(0x10000))
        call(0x89460,(IDS,canfd,second))
        offsets={hex(fid):[hex(o) for o in range(0,0xab38,4) if word(IDS+o)==fid] for fid in TARGETS}
        variants.append({'variant':variant,'offsets':offsets})
        call(start,(OBJ,MAPPER,IDS))
        u.write(OBJ+0x100,IDS.to_bytes(8,'little'))
        u.write(OBJ+0xe8,MAPPER.to_bytes(8,'little'))
        u.write(BUF+512,b'\x03\x14\x25')
        result=call(0xb5170,(OBJ,0xc5a52,BUF+512,3))
        variants[-1]['synthetic_setString_return']=result if result<0x80000000 else result-2**32
        assert result==(-10011)&0xffffffff
    samples=[]
    for length in (1,3,16,247,248):
        payload=bytes((i*17+3)&255 for i in range(length))
        u.write(BUF,bytes(1024))
        u.write(BUF+512,payload)
        result=call(0x1af8a0,(OBJ,0xc5a52,BUF+512,length,BUF))
        samples.append({'payload_length':length,'return':result if result<0x80000000 else result-2**32,
                        'frame_hex':bytes(u.read(BUF,result)).hex() if result<1024 else None})
        if length<=247:
            assert bytes(u.read(BUF,result))==bytes.fromhex('000c5a52')+bytes([length])+payload
        else: assert result==(-10016)&0xffffffff
    for r in registrations+control_registrations:
        callpc=int(r['call'],16)
        dump(callpc-96,160,'registration context')
    assert not registrations, 'Legacy target unexpectedly registered'
    assert list(all_counts.values())==[2775,2287,11604], 'Incomplete registration walk'
    assert len(control_registrations)==5, 'Missing independent control registrations'
    assert all(v['offsets']['0xcaf3b']==['0x6408'] and v['offsets']['0xc5a52']==['0x640c'] for v in variants)
    assert u.read(0x3e909,16).split(b'\0')[0]==b'/dev/spidev_ivi'
    vtables=[]
    for base,name,start in [(0x1d21c0,'FeatureListCan',0xb7ab0),
                            (0x1d21f0,'FeatureListToyota',0xe1500),
                            (0x1d2220,'FeatureListCanFD',0x103020)]:
        init=struct.unpack('<Q',n.bytes_at(base+0x20,8))[0]
        assert init==start
        vtables.append({'class':name,'vtable':hex(base),'initialize':hex(init)})
    report={'binary':str(binary),'sha256':SHA,'synthetic_only':True,
            'constructor_variants':variants,'registrations':registrations,'registration_counts':all_counts,
            'ota_registrations':ota_registrations,'control_registrations':control_registrations,
            'setter_checks':setter_checks,'codec_samples':samples,'vtables':vtables}
    (output/'native-trace.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'binary_sha256':SHA,'registrations':all_counts,'legacy_matches':len(registrations),'control_registrations':len(control_registrations),'output':str(output)},indent=2))

if __name__=='__main__': main()
