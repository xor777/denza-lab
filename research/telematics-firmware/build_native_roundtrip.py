#!/usr/bin/env python3
"""Build local native status roundtrip, no device calls; private generated data."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
from verify_opaque_native import EXPECTED, RANGES, need, read_buffers, OBJ
from verify_native_roundtrip import Roundtrip, CODEC_RANGES, DISPATCH_RANGES

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('firmware',type=Path);p.add_argument('capture',type=Path)
    p.add_argument('--out',type=Path,required=True);p.add_argument('--linker',required=True);p.add_argument('--clang',default='clang');a=p.parse_args()
    need(hashlib.sha256(a.firmware.read_bytes()).hexdigest()==EXPECTED,'firmware hash')
    out=a.out.resolve();out.mkdir(parents=True,exist_ok=True)
    buffers=read_buffers(a.capture);m=Roundtrip(a.firmware);request=m.request();m.bodies.clear();m.frames.clear()
    for b in buffers:m.ingest(b)
    m.receive(request);need(m.sends==[511] and len(m.frames)==1,'offline oracle');response=m.frames[0];body=m.bodies[-1][16:]
    ranges=RANGES[:5]+CODEC_RANGES+DISPATCH_RANGES
    image=bytearray(0x98000)
    with a.firmware.open('rb') as f:
        elf=ELFFile(f)
        for s in elf.iter_segments():
            if s['p_type']=='PT_LOAD':image[s['p_vaddr']:s['p_vaddr']+len(s.data())]=s.data()
        original=bytes(image)
        for name in ('.text','.plt'):
            s=elf.get_section_by_name(name);lo=s['sh_addr'];n=s['sh_size'];need(n%4==0,'section alignment');image[lo:lo+n]=bytes.fromhex('000020d4')*(n//4)
        for lo,hi in ranges:
            image[lo:hi]=original[lo:hi]
            for at in range(lo,hi,4):need(int.from_bytes(image[at:at+4],'little')&0xffe0001f!=0xd4000001,'native syscall')
    cs=Cs(CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN)
    for entry in (0x6e858,0x727bc):
        prologue=list(cs.disasm(original[entry:entry+16],entry));need(len(prologue)==4 and all(i.mnemonic=='stp' and '[sp' in i.op_str for i in prologue),'nonrelocatable prologue')
    (out/'native-image.bin').write_bytes(image)
    # All callable stub pages plus original range pages and the two entry thunks.
    pages={0x3f000,0x4a000,0x54000,0x6d000,0x6e000,0x7e000,0x88000,0x89000,0x98000}
    for lo,hi in ranges:pages.update(range(lo&~4095,(hi+4095)&~4095,4096))
    def array(b):return '{'+','.join(str(v) for v in b)+'}'
    header='/* PRIVATE generated original-code fixtures; do not commit. */\n'
    header+=f'#define BUFFER_COUNT {len(buffers)}\nstatic const unsigned char BUFFERS[BUFFER_COUNT][74]={{\n'+',\n'.join(array(b) for b in buffers)+'\n};\n'
    header+='static const unsigned char BUFFER_SIZES[BUFFER_COUNT]='+array([len(b) for b in buffers])+';\n'
    for name,value in [('EXPECTED_REQUEST',request),('EXPECTED_RESPONSE',response),('EXPECTED_BODY',body)]:header+=f'static const unsigned char {name}[]={array(value)};\n'
    header+='static const unsigned long EXEC_PAGES[]='+array(sorted(pages))+';\n'
    hp=out/'native_roundtrip_fixtures.h';hp.write_text(header);hp.chmod(0o600)
    (out/'image.S').write_text('.section .rodata\n.balign 16\n.global native_image_start\nnative_image_start:\n.incbin "native-image.bin"\n.global native_image_end\nnative_image_end:\n.section .note.GNU-stack,"",%progbits\n')
    c=Path(__file__).with_name('native_roundtrip_probe.c').resolve()
    commands=[[a.clang,'--target=aarch64-linux-gnu','-O2','-std=c11','-ffreestanding','-fno-builtin','-fno-stack-protector','-fno-pie','-Wall','-Wextra','-Werror','-I',str(out),'-c',str(c),'-o','probe.o'],[a.clang,'--target=aarch64-linux-gnu','-c','image.S','-o','image.o'],[a.linker,'-m','aarch64elf','-static','-e','_start','-z','noexecstack','probe.o','image.o','-o','native-roundtrip-probe']]
    for command in commands:subprocess.run(command,cwd=out,check=True)
    binary=out/'native-roundtrip-probe';binary.chmod(0o600)
    def info(p):return {'path':str(p.resolve()),'size':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
    result={'scope':'Original status decoder/dispatcher/reply only; synthetic local session; no cloud or actuators','commands':commands,'firmware_sha256':EXPECTED,'native_ranges':[[hex(lo),hex(hi)] for lo,hi in ranges],
        'files':[info(p) for p in (a.firmware,a.capture,Path(__file__),c,Path(__file__).with_name('verify_native_roundtrip.py'),Path(__file__).with_name('verify_opaque_native.py'),hp,binary)],
        'boundaries':['W^X; unused native code traps','Seccomp permits only stdout/stderr/exit; CPU 2s wall 5s','Partial context and synthetic session flags/key/UUID/VIN','Explicit empty auxiliary subscription vector','Memory/RefBase/locks stubbed; RTT journal no-op','Native subscription and send captured only','Original AES/CRC and decoder results unchanged; no actuator handler code copied']}
    (out/'build.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(info(binary)))
if __name__=='__main__':main()
