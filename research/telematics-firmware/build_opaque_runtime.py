#!/usr/bin/env python3
"""Build isolated opaque-buffer runtime; no device access or cloud traffic.

Expected report bytes come from the original ARM64 code in Unicorn, never a
Python/C field encoder. Generated fixtures contain private vehicle buffers and
must remain under ignored captures with restricted permissions.
"""
import argparse,hashlib,json,subprocess
from pathlib import Path
from elftools.elf.elffile import ELFFile
from verify_opaque_native import EXPECTED,RANGES,Machine,read_buffers,need

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('firmware',type=Path);p.add_argument('capture',type=Path);p.add_argument('--out',type=Path,required=True);p.add_argument('--linker',required=True);p.add_argument('--clang',default='clang');a=p.parse_args()
    need(hashlib.sha256(a.firmware.read_bytes()).hexdigest()==EXPECTED,'firmware hash')
    out=a.out.resolve();out.mkdir(exist_ok=True,parents=True)
    buffers=read_buffers(a.capture);machine=Machine(a.firmware)
    for b in buffers:machine.ingest(b)
    native_body=machine.report();need(len(native_body)==104,'native fixture length')
    image=bytearray(0x98000)
    with a.firmware.open('rb') as f:
        elf=ELFFile(f)
        for s in elf.iter_segments():
            if s['p_type']=='PT_LOAD':
                start=s['p_vaddr'];data=s.data();need(start+len(data)<=len(image),'image bound');image[start:start+len(data)]=data
        original=bytes(image)
        for name in ('.text','.plt'):
            s=elf.get_section_by_name(name);lo=s['sh_addr'];n=s['sh_size'];need(n%4==0,'section bound');image[lo:lo+n]=bytes.fromhex('000020d4')*(n//4)
        for lo,hi in RANGES:
            image[lo:hi]=original[lo:hi]
            for at in range(lo,hi,4):need(int.from_bytes(image[at:at+4],'little')&0xffe0001f!=0xd4000001,'syscall in native range')
    (out/'opaque-image.bin').write_bytes(image)
    def array(b):return '{'+','.join(str(v) for v in b)+'}'
    header='/* PRIVATE raw callback fixtures; generated, do not commit. */\n'
    header+=f'#define BUFFER_COUNT {len(buffers)}\nstatic const unsigned char BUFFERS[BUFFER_COUNT][74]={{\n'+',\n'.join(array(b) for b in buffers)+'\n};\n'
    header+='static const unsigned char BUFFER_SIZES[BUFFER_COUNT]='+array([len(b) for b in buffers])+';\n'
    header+='static const unsigned char NATIVE_EXPECTED_BODY[104]='+array(native_body)+';\n'
    hp=out/'opaque_runtime_fixtures.h';hp.write_text(header);hp.chmod(0o600)
    (out/'image.S').write_text('.section .rodata\n.balign 16\n.global identity_image_start\nidentity_image_start:\n.incbin "opaque-image.bin"\n.global identity_image_end\nidentity_image_end:\n.section .note.GNU-stack,"",%progbits\n')
    c=Path(__file__).with_name('opaque_runtime_probe.c').resolve()
    commands=[[a.clang,'--target=aarch64-linux-gnu','-O2','-std=c11','-ffreestanding','-fno-builtin','-fno-stack-protector','-fno-pie','-Wall','-Wextra','-Werror','-I',str(out),'-c',str(c),'-o','probe.o'],[a.clang,'--target=aarch64-linux-gnu','-c','image.S','-o','image.o'],[a.linker,'-m','aarch64elf','-static','-e','_start','-z','noexecstack','probe.o','image.o','-o','opaque-runtime-probe']]
    for command in commands:subprocess.run(command,cwd=out,check=True)
    binary=out/'opaque-runtime-probe';binary.chmod(0o600)
    def info(p):return {'path':str(p),'size':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
    result={'scope':'Local opaque data/native builder, queue-copy and SSL-wrapper tests; all external effects stubbed; not a complete session','commands':commands,'firmware_sha256':EXPECTED,'native_ranges':[[hex(lo),hex(hi)] for lo,hi in RANGES],'callback_count':len(buffers),'boundaries':['Partial context; no full native service constructor','Native subscription write captured, never dispatched','Other consumers absent/no-op','Original report builder runs; framing and send stubbed','Original message constructor copies opaque buffers','SSL functions mocked; no socket/crypto','Default seccomp: stdout/stderr write and exit; CPU2s, wall5s','--stream adds stdin-only read, 25s wall limit, bounded IPC lines/buffers; no native schema parser'], 'files':[info(p) for p in (a.firmware.resolve(),a.capture.resolve(),Path(__file__).resolve(),c,Path(__file__).with_name('verify_opaque_native.py').resolve(),hp,binary)]}
    (out/'build.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'binary':str(binary),'sha256':info(binary)['sha256']}))
if __name__=='__main__':main()
