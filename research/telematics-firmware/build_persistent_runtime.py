#!/usr/bin/env python3
"""Build the isolated persistent ARM64 engine; no embedded car identity."""
import argparse,hashlib,json,subprocess
from pathlib import Path
from elftools.elf.elffile import ELFFile
from capstone import Cs,CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN
from verify_opaque_native import EXPECTED,RANGES,need
from verify_native_roundtrip import CODEC_RANGES,DISPATCH_RANGES
from native_session import SESSION_RANGES
from verify_native_control import CONTROL_RANGES
from verify_wake_callback_native import CALLBACK_RANGES

def main():
 p=argparse.ArgumentParser();p.add_argument('firmware',type=Path);p.add_argument('--out',type=Path,required=True);p.add_argument('--linker',required=True);a=p.parse_args()
 need(hashlib.sha256(a.firmware.read_bytes()).hexdigest()==EXPECTED,'firmware hash')
 out=a.out.resolve();out.mkdir(parents=True,exist_ok=True)
 ranges=RANGES[:5]+CODEC_RANGES+DISPATCH_RANGES+SESSION_RANGES+CONTROL_RANGES+list(CALLBACK_RANGES)+[(0x6d9f4,0x6da9c),(0x6dcf0,0x6dfa0),(0x6e00c,0x6e3b0),(0x7c978,0x7d5c0),(0x57710,0x57740),
  (0x4a41c,0x4a494),(0x4a6d0,0x4a858),(0x4a96c,0x4ab90),(0x5c5f4,0x5c71c),
  (0x5e278,0x5e4d8),(0x5e4d8,0x5e6c8),(0x5e6c8,0x5e7e8),(0x5b238,0x5b288),(0x5b4b8,0x5b744),(0x6809c,0x6810c),
  (0x6b260,0x6d9f4),(0x6db78,0x6dcf0),(0x6f294,0x6f3a0),
  (0x3dac0,0x3dc74),(0x47b78,0x47cf4),
  (0x383a8,0x385f8),(0x38680,0x38d1c),
  (0x52ea0,0x530d0),(0x53270,0x535d4),(0x53668,0x53818),
  (0x4aedc,0x4b150),(0x4b150,0x4b35c),(0x4bc60,0x4c484),(0x4add4,0x4aedc),
  (0x49f80,0x4a41c),
  (0x69868,0x699a4),(0x5eef0,0x5ef80),(0x5e7e8,0x5e88c),
  (0x54964,0x54c7c),
  (0x539d8,0x54320),
  (0x611b8,0x611e0),(0x61230,0x612a8),(0x54cc0,0x54e20),
  (0x510d4,0x51990),(0x40e80,0x40f94),(0x50734,0x508d0),(0x4ca68,0x4dd68),
  (0x36b78,0x36ce4),(0x36edc,0x36f30),
  (0x60c0c,0x6114c),(0x451f0,0x45380),(0x44c94,0x45030),(0x45430,0x454cc),
  (0x44dc0,0x44e40),(0x45c88,0x45cf4),(0x4404c,0x440c0),
  (0x7113c,0x71200),(0x712d0,0x713b0),(0x714bc,0x71574),(0x69b9c,0x69c10),
  (0x699a4,0x699f0),(0x54e20,0x54fc4),(0x69630,0x696d0),(0x4f2e4,0x4f318),
  (0x42448,0x42490),(0x4689c,0x468d0),(0x48018,0x48240),
  (0x48280,0x48468),
  (0x48c6c,0x48cb8),
  (0x546f8,0x548cc),(0x66980,0x66a60),
  (0x6822c,0x688cc),(0x7e508,0x7e820)]
 ranges += [(0x73228,0x732c4),(0x732c4,0x735ac),(0x735ac,0x73660),
            (0x80aec,0x80b24),(0x8333c,0x8347c),(0x8347c,0x834d8),
            (0x739a0,0x74730),(0x6fe4c,0x6fe7c),(0x74abc,0x74da8),
            (0x752d8,0x759d8),(0x759d8,0x75af8),
            (0x75b70,0x75bdc),
            (0x75b0c,0x75b70),
            (0x75bdc,0x75d60),
            (0x5d238,0x5e100),(0x846a0,0x84720),(0x4b3e0,0x4b690),
            (0x4b694,0x4bc60),
            (0x55b30,0x56ac0),(0x5502c,0x550f0),(0x5522c,0x5534c),
            (0x71e7c,0x725b4),(0x6f0f0,0x6f294),(0x7030c,0x703c8),
            (0x706bc,0x70778),(0x70a14,0x70ae0),(0x70b9c,0x70c68)]
 image=bytearray(0x98000)
 with a.firmware.open('rb') as f:
  elf=ELFFile(f)
  write_pages=set()
  for s in elf.iter_segments():
   if s['p_type']=='PT_LOAD':
    image[s['p_vaddr']:s['p_vaddr']+len(s.data())]=s.data()
    if s['p_flags']&2:
     write_pages.update(range(s['p_vaddr']&~4095,(s['p_vaddr']+s['p_memsz']+4095)&~4095,4096))
  relocations=[(r['r_offset'],r['r_addend']) for r in elf.get_section_by_name('.rela.dyn').iter_relocations() if r['r_info_type']==1027]
  need(all(0<=at<0x98000 and 0<=addend<0x98000 for at,addend in relocations),'relative relocation range')
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
 need(not (pages & write_pages),'executable data page')
 (out/'session_image.bin').write_bytes(image)
 (out/'session_pages.h').write_text('static const unsigned long EXEC_PAGES[]={'+','.join(hex(v) for v in sorted(pages))+'};\n')
 (out/'persistent_relocations.h').write_text('static const unsigned long RELATIVE_RELOCS[][2]={'+','.join('{'+hex(at)+','+hex(addend)+'}' for at,addend in relocations)+'};\nstatic const unsigned long WRITE_PAGES[]={'+','.join(hex(v) for v in sorted(write_pages))+'};\n')
 (out/'image.S').write_text('.section .rodata\n.balign 16\n.global native_image_start\nnative_image_start:\n.incbin "session_image.bin"\n.global native_image_end\nnative_image_end:\n.section .note.GNU-stack,"",%progbits\n')
 source=Path(__file__).with_name('persistent_runtime.c').resolve()
 commands=[['clang','--target=aarch64-linux-gnu','-O2','-std=c11','-ffreestanding','-fno-builtin','-fno-stack-protector','-fno-pie','-Wall','-Wextra','-Werror','-I',str(out),'-c',str(source),'-o','engine.o'],['clang','--target=aarch64-linux-gnu','-c','image.S','-o','image.o'],[a.linker,'-m','aarch64elf','-static','-e','_start','-z','noexecstack','engine.o','image.o','-o','persistent-engine']]
 for c in commands:subprocess.run(c,cwd=out,check=True)
 binary=out/'persistent-engine';binary.chmod(0o600)
 source_hash=hashlib.sha256(source.read_bytes()).hexdigest()
 allocator_header=source.with_name('bounded_arena.h')
 with a.firmware.open('rb') as f:
  original_elf=ELFFile(f);text_section=original_elf.get_section_by_name('.text')
  text_lo=text_section['sh_addr'];text_hi=text_lo+text_section['sh_size']
 selected=sorted((max(lo,text_lo),min(hi,text_hi)) for lo,hi in ranges
                 if max(lo,text_lo)<min(hi,text_hi))
 merged=[]
 for lo,hi in selected:
  if merged and lo<=merged[-1][1]:merged[-1]=(merged[-1][0],max(hi,merged[-1][1]))
  else:merged.append((lo,hi))
 size_inventory={'selected_original_text_bytes':sum(hi-lo for lo,hi in merged),
                 'original_text_bytes':text_section['sh_size'],
                 'range_entries':len(ranges),'merged_text_regions':len(merged),
                 'embedded_image_bytes':len(image),'final_elf_bytes':binary.stat().st_size}
 d={'firmware_sha256':EXPECTED,'runtime':'persistent-engine/2','protocol':2,
    'virtual_public_profile':'double_apn','product_qualified':False,
    'profiles':{'awake-alpha-v1':{'qualified':False,
      'capabilities':{'native_registration_codec':True,'opaque_data_ingest':True,
                      'control_awake':False,'wake_ack_awake':False,
                      'timers_awake':False,'post_login_awake':False,
                      'heartbeat':False}}},
    'capabilities':{'native_registration_codec':True,'opaque_data_ingest':True,
                    'control_532':False,'wake_536':False,'mcu_state':False,
                    'native_posix_timers':False,'native_sub5_timer':False,
                    'post_login':False,'heartbeat':False,
                    'stock_lifecycle_bridge':False,
                    'in_process_session_reset':False},
    'ranges':[[hex(x),hex(y)] for x,y in ranges],
    'relative_relocations':len(relocations),'writable_pages':[hex(x) for x in sorted(write_pages)],
    'size_inventory':size_inventory,
    'commands':commands,
    'binary_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),
    'source_sha256':source_hash,'source_files_sha256':{'persistent_runtime.c':source_hash,
    'build_persistent_runtime.py':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    'bounded_arena.h':hashlib.sha256(allocator_header.read_bytes()).hexdigest(),
    'persistent_timer.h':hashlib.sha256(source.with_name('persistent_timer.h').read_bytes()).hexdigest()}}
 (out/'build.json').write_text(json.dumps(d,indent=2)+'\n');print(d['binary_sha256'])
if __name__=='__main__':main()
