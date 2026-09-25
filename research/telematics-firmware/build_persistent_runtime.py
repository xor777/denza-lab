#!/usr/bin/env python3
"""Build the isolated persistent ARM64 engine; no embedded car identity."""
import argparse,hashlib,json,subprocess
from pathlib import Path
from elftools.elf.elffile import ELFFile
from capstone import Cs,CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN
from native_profile import EXPECTED, need

def main():
 p=argparse.ArgumentParser();p.add_argument('firmware',type=Path);p.add_argument('--out',type=Path,required=True);p.add_argument('--linker',required=True);a=p.parse_args()
 need(hashlib.sha256(a.firmware.read_bytes()).hexdigest()==EXPECTED,'firmware hash')
 out=a.out.resolve();out.mkdir(parents=True,exist_ok=True)
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
  # Keep the complete pinned implementation, including internal continuations,
  # switch tables and virtual methods. Code availability does not start main,
  # run static constructors, or grant access to an external API. Every PLT slot
  # remains a refusal until the C bridge explicitly supplies that interface.
  code=elf.get_section_by_name('.text')
  ranges=[(code['sh_addr'],code['sh_addr']+code['sh_size'])]
  instructions=list(Cs(CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN).disasm(code.data(),code['sh_addr']))
  need(sum(i.size for i in instructions)==code['sh_size'],'complete text decode')
  need(all(i.mnemonic not in ('svc','hvc','smc') for i in instructions),'native syscall')
  refusal_pages=set()
  for name in ('.plt',):
   s=elf.get_section_by_name(name);lo=s['sh_addr'];n=s['sh_size'];need(n%4==0,'alignment')
   # Unknown imports terminate with their source offset only; no vehicle
   # identity, payload, or SDK values are included in the failure.
   for at in range(lo,lo+n,4):
    image[at:at+4]=(0x94000000|(((0x98200-at)//4)&0x03ffffff)).to_bytes(4,'little')
   refusal_pages.update(range(lo&~4095,(lo+n+4095)&~4095,4096))
 cs=Cs(CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN)
 for entry in (0x6e858,0x727bc,0x6e3b0):
  ops=list(cs.disasm(original[entry:entry+16],entry));need(len(ops)==4 and all(i.mnemonic in ('stp','sub') and 'sp' in i.op_str for i in ops),'thunk prologue')
 # 0x82b98 is the patched secondary-write entry: it still needs execute
 # permission even though none of its original implementation is selected.
 pages={0x3f000,0x48000,0x4a000,0x4b000,0x52000,0x54000,0x55000,0x5d000,0x6d000,0x6e000,0x69000,0x72000,0x75000,0x7c000,0x7e000,0x82000,0x88000,0x89000,0x98000,0x39000}
 for lo,hi in ranges:pages.update(range(lo&~4095,(hi+4095)&~4095,4096))
 pages.update(refusal_pages)
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
    'profiles':{'awake-alpha-v1':{'qualified':True,
      'capabilities':{'native_registration_codec':True,'opaque_data_ingest':True,
                      'control_awake':True,'wake_ack_awake':True,
                      'timers_awake':True,'post_login_awake':True,
                      'heartbeat':True}}},
    'capabilities':{'native_registration_codec':True,'opaque_data_ingest':True,
                    'control_532':False,'wake_536':False,'mcu_state':False,
                    'native_posix_timers':False,'native_sub5_timer':False,
                    'post_login':False,'heartbeat':False,
                    'stock_lifecycle_bridge':False,
                    'in_process_session_reset':False},
    'code_policy':'complete-original-text-masked-plt',
    'ranges':[[hex(x),hex(y)] for x,y in ranges],
    'relative_relocations':len(relocations),'writable_pages':[hex(x) for x in sorted(write_pages)],
    'size_inventory':size_inventory,
    'commands':commands,
    'binary_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),
    'source_sha256':source_hash,'source_files_sha256':{'persistent_runtime.c':source_hash,
    'build_persistent_runtime.py':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    'bounded_arena.h':hashlib.sha256(allocator_header.read_bytes()).hexdigest(),
    'native_profile.py':hashlib.sha256(source.with_name('native_profile.py').read_bytes()).hexdigest(),
    'persistent_timer.h':hashlib.sha256(source.with_name('persistent_timer.h').read_bytes()).hexdigest()}}
 (out/'build.json').write_text(json.dumps(d,indent=2)+'\n');print(d['binary_sha256'])
if __name__=='__main__':main()
