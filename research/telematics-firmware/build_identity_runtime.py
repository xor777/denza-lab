#!/usr/bin/env python3
"""Build the isolated synthetic ARM64 runtime probe; no vehicle access.

Copies only the reviewed instruction ranges into an otherwise non-executable
image. Runs no ELF initializers, resolves no stock dynamic libraries and never
executes cloudmanager main. Requires pyelftools, Clang and an ELF-capable LLD.
Generated images/binaries belong under ignored captures, not in Git.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from elftools.elf.elffile import ELFFile

EXPECTED = '9e36cdbf841d54a3b1ea3631b5d5b867d5908bd191a113f53b5bb4182df82eb9'
RANGES = [(0x6d9f4,0x6da9c),(0x6dcf0,0x6dfa0),(0x6e00c,0x6e110),
          (0x6f01c,0x6f0f0),(0x699f0,0x69a6c),(0x7c978,0x7d5c0)]

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('firmware',type=Path)
    ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--clang',default='clang')
    ap.add_argument('--linker',required=True)
    a=ap.parse_args()
    source=a.firmware.read_bytes()
    if hashlib.sha256(source).hexdigest()!=EXPECTED:raise ValueError('Unreviewed firmware')
    out=a.out.resolve();out.mkdir(parents=True,exist_ok=True)
    image=bytearray(0x98000)
    with a.firmware.open('rb') as f:
        e=ELFFile(f)
        for s in e.iter_segments():
            if s['p_type']=='PT_LOAD':
                start=s['p_vaddr'];data=s.data()
                if start+len(data)>len(image):raise ValueError('Image bound')
                image[start:start+len(data)]=data
        t=e.get_section_by_name('.text');start=t['sh_addr'];end=start+t['sh_size']
        original=bytes(image)
        image[start:end]=bytes.fromhex('000020d4')*((end-start)//4)  # BRK in unused code
        for lo,hi in RANGES:
            image[lo:hi]=original[lo:hi]
            for at in range(lo,hi,4):
                word=int.from_bytes(image[at:at+4],'little')
                if word & 0xffe0001f == 0xd4000001:raise ValueError('Syscall inside reviewed range')
        # No lazy binding or stock library loader may run, even accidentally.
        plt=e.get_section_by_name('.plt');lo=plt['sh_addr'];size=plt['sh_size']
        image[lo:lo+size]=bytes.fromhex('000020d4')*(size//4)
    (out/'identity-image.bin').write_bytes(image)
    imsi_a,imsi_b='001010123456789','001010987654321'
    imsi_zero=next(f'00101{i:010d}' for i in range(10000) if hashlib.md5(f'00101{i:010d}'.encode()).digest()[0]==0)
    defs={'IMSI_A':imsi_a,'IMSI_B':imsi_b,'IMSI_ZERO':imsi_zero,
          'ICCID_A':'89010000000000000001','ICCID_B':'89010000000000000002'}
    header='/* Synthetic fixtures; no real subscriber information. */\n'
    for k,v in defs.items():header+=f'#define {k} "{v}"\n'
    for k,v in [('A',imsi_a),('B',imsi_b),('ZERO',imsi_zero)]:
        header+=f'static const unsigned char DIGEST_{k}[16]={{'+','.join(str(x) for x in hashlib.md5(v.encode()).digest())+'};\n'
    (out/'identity_runtime_expected.h').write_text(header)
    # Relative incbin path resolved through the compiler working directory.
    (out/'image.S').write_text('.section .rodata\n.balign 16\n.global identity_image_start\nidentity_image_start:\n.incbin "identity-image.bin"\n.global identity_image_end\nidentity_image_end:\n.section .note.GNU-stack,"",%progbits\n')
    c=Path(__file__).with_name('identity_runtime_probe.c').resolve()
    commands=[
        [a.clang,'--target=aarch64-linux-gnu','-O2','-std=c11','-ffreestanding','-fno-builtin','-fno-stack-protector','-fno-pie','-Wall','-Wextra','-Werror','-I',str(out),'-c',str(c),'-o','probe.o'],
        [a.clang,'--target=aarch64-linux-gnu','-c','image.S','-o','image.o'],
        [a.linker,'-m','aarch64elf','-static','-e','_start','-z','noexecstack','probe.o','image.o','-o','identity-runtime-probe'],
    ]
    for command in commands:subprocess.run(command,cwd=out,check=True)
    def info(p):return {'path':str(p),'size':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
    manifest={'scope':'Synthetic original-instruction execution; not daemon or cloud integration',
      'firmware_sha256':EXPECTED,'instruction_ranges':[[hex(x),hex(y)] for x,y in RANGES],
      'boundaries':['No ELF startup, constructors or dynamic libraries loaded','Only packet-helper constructor executes, not cloud service constructor',
      'Private mapping stubs: RefBase, allocation, logging, properties, memory, nonce, serializer',
      '211/220 return at body capture before serialization, signing, framing or networking',
      'Seccomp allows only stdout/stderr write and process exit after mapping',
      'CPU limit 2 seconds and wall timer 5 seconds; core dumps disabled'],
      'commands':commands,'files':[info(p) for p in [a.firmware.resolve(),c,Path(__file__).resolve(),out/'identity-image.bin',out/'identity-runtime-probe']]}
    (out/'build.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(json.dumps({'binary':str(out/'identity-runtime-probe'),'sha256':manifest['files'][-1]['sha256']}))
if __name__=='__main__':main()
