#!/usr/bin/env python3
"""Build stdin/stdout-only ARM64 original-code worker; no embedded real identity."""
import argparse,hashlib,json,subprocess
from pathlib import Path
from elftools.elf.elffile import ELFFile
from capstone import Cs,CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN
from verify_opaque_native import EXPECTED,RANGES,need
from verify_native_roundtrip import CODEC_RANGES,DISPATCH_RANGES
from native_session import SESSION_RANGES

def main():
 p=argparse.ArgumentParser();p.add_argument('firmware',type=Path);p.add_argument('--out',type=Path,required=True);p.add_argument('--linker',required=True);p.add_argument('--control-experiment',action='store_true');a=p.parse_args()
 need(hashlib.sha256(a.firmware.read_bytes()).hexdigest()==EXPECTED,'firmware hash')
 out=a.out.resolve();out.mkdir(parents=True,exist_ok=True)
 ranges=RANGES[:5]+CODEC_RANGES+DISPATCH_RANGES+SESSION_RANGES+[(0x6d9f4,0x6da9c),(0x6dcf0,0x6dfa0),(0x6e00c,0x6e3b0),(0x7c978,0x7d5c0),(0x57710,0x57740)]
 if a.control_experiment:
  from verify_native_control import CONTROL_RANGES
  ranges+=CONTROL_RANGES
 image=bytearray(0x98000)
 with a.firmware.open('rb') as f:
  elf=ELFFile(f)
  for s in elf.iter_segments():
   if s['p_type']=='PT_LOAD':image[s['p_vaddr']:s['p_vaddr']+len(s.data())]=s.data()
  original=bytes(image)
  for name in ('.text','.plt'):
   s=elf.get_section_by_name(name);lo=s['sh_addr'];n=s['sh_size'];need(n%4==0,'alignment');image[lo:lo+n]=bytes.fromhex('000020d4')*(n//4)
  for lo,hi in ranges:
   image[lo:hi]=original[lo:hi]
   for at in range(lo,hi,4):need(int.from_bytes(image[at:at+4],'little')&0xffe0001f!=0xd4000001,'native syscall')
 cs=Cs(CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN)
 for entry in (0x6e858,0x727bc,0x6e3b0):
  ops=list(cs.disasm(original[entry:entry+16],entry));need(len(ops)==4 and all(i.mnemonic in ('stp','sub') and 'sp' in i.op_str for i in ops),'thunk prologue')
 pages={0x3f000,0x48000,0x4a000,0x4b000,0x52000,0x54000,0x55000,0x5d000,0x6d000,0x6e000,0x69000,0x72000,0x75000,0x7c000,0x7e000,0x88000,0x89000,0x98000,0x39000}
 for lo,hi in ranges:pages.update(range(lo&~4095,(hi+4095)&~4095,4096))
 if a.control_experiment:pages.add(0x73000)  # Captured original result-sender entry.
 (out/'session_image.bin').write_bytes(image)
 (out/'session_pages.h').write_text('static const unsigned long EXEC_PAGES[]={'+','.join(hex(v) for v in sorted(pages))+'};\n')
 (out/'image.S').write_text('.section .rodata\n.balign 16\n.global native_image_start\nnative_image_start:\n.incbin "session_image.bin"\n.global native_image_end\nnative_image_end:\n.section .note.GNU-stack,"",%progbits\n')
 source=Path(__file__).with_name('session_runtime.c').resolve()
 commands=[['clang','--target=aarch64-linux-gnu','-O2','-std=c11','-ffreestanding','-fno-builtin','-fno-stack-protector','-fno-pie','-Wall','-Wextra','-Werror','-I',str(out),'-c',str(source),'-o','worker.o'],['clang','--target=aarch64-linux-gnu','-c','image.S','-o','image.o'],[a.linker,'-m','aarch64elf','-static','-e','_start','-z','noexecstack','worker.o','image.o','-o','native-session-worker']]
 if a.control_experiment:commands[0].insert(1,'-DCONTROL_EXPERIMENT')
 for c in commands:subprocess.run(c,cwd=out,check=True)
 binary=out/'native-session-worker';binary.chmod(0o600)
 source_hash=hashlib.sha256(source.read_bytes()).hexdigest()
 allocator_header=source.with_name('bounded_arena.h')
 d={'firmware_sha256':EXPECTED,'control_experiment':a.control_experiment,'ranges':[[hex(x),hex(y)] for x,y in ranges],'commands':commands,'binary_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),'source_sha256':source_hash,'source_files_sha256':{'session_runtime.c':source_hash,'bounded_arena.h':hashlib.sha256(allocator_header.read_bytes()).hexdigest()}}
 (out/'build.json').write_text(json.dumps(d,indent=2)+'\n');print(d['binary_sha256'])
if __name__=='__main__':main()
