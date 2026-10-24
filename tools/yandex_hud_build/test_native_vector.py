"""Host contracts for passive native vector capture, no device access."""
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile

from tools.yandex_hud_build import native_vector
from tools.yandex_hud_build.native_graph import elf_layout

ROOT = native_vector.ROOT
CPP = ROOT / 'experiments/yandex-hud-module/src/main/cpp'
CORPUS = ROOT / 'captures/firmware/yandexnavi-29.8.1-3b0dec32/jadx/Navigator/resources/lib/arm64-v8a/libmaps-mobile.so'

HARNESS = r'''
#define DENZA_VECTOR_TEST 1
#include "experiments/yandex-hud-module/src/main/cpp/yandex_vector_collector.c"
#include <assert.h>
#include <string.h>
static uintptr_t regs[31];
static uint8_t params[0x50] __attribute__((aligned(8)));
static TileId tile = {123, 234, 14};
static uint8_t payload[] = {8, 1, 18, 0};
static uintptr_t range[2];
static uint8_t output[32];
static void reset(void) {
    memset(slots, 0, sizeof(slots)); memset(threads, 0, sizeof(threads));
    memset(regs, 0, sizeof(regs)); memset(params, 0, sizeof(params));
    memset(output, 0, sizeof(output));
    seen = captured = polled = dropped = invalid = oversize = captured_bytes = sequence = 0;
    parse_failed = mismatched_ids = missing_context = context_overflow = abandoned = reentrant = 0;
    layer_seen = wrapper_seen = return_mismatch = 0;
    native_state = 1;
    memcpy(params + 0x18, &tile, 12); memcpy(params + 0x38, &tile, 12);
    range[0] = (uintptr_t)payload; range[1] = range[0] + sizeof(payload);
    output[16] = 1;
}
static void event(unsigned kind, uintptr_t sp, uintptr_t key, uintptr_t arg) {
    regs[0] = arg; regs[8] = (uintptr_t)output; regs[30] = 0xabcdef;
    denza_vector_event(regs, kind, sp, key);
}
static void enter(uintptr_t key) {
    event(LAYER_IN, 0x10000, key, (uintptr_t)params);
    event(WRAPPER_IN, 0xff00, key, (uintptr_t)&tile);
    event(DECODER_IN, 0xfe00, key, (uintptr_t)range);
}
static void leave(uintptr_t key) {
    event(DECODER_OUT, 0xfe00, key, 0);
    event(WRAPPER_OUT, 0xff00, key, 0);
    event(LAYER_OUT, 0x10000, key, 0);
}
static unsigned ready(void) {
    unsigned n = 0; for (unsigned i = 0; i < SLOT_COUNT; ++i) n += slots[i].state == READY; return n;
}
static uint32_t u32(const uint8_t *p) { uint32_t n; memcpy(&n,p,4); return n; }
int main(void) {
    reset(); enter(1); assert(ready() == 0); leave(1);
    assert(ready() == 1 && captured == 1 && return_mismatch == 0);
    Slot *s = &slots[0]; assert(s->size == HEADER_SIZE + sizeof(payload));
    assert(!memcmp(s->bytes,"YVT1",4)); assert(s->bytes[4] == 1 && s->bytes[6] == 72);
    assert(u32(s->bytes+8) == 4 && u32(s->bytes+12) == 1 && u32(s->bytes+68) == 0);
    for (unsigned i=0;i<3;++i) assert(!memcmp(s->bytes+32+i*12,&tile,12));
    assert(!memcmp(s->bytes+72,payload,sizeof(payload)));
    reset(); enter(1); output[16] = 0; leave(1); assert(!ready() && parse_failed == 1);
    reset(); ((TileId *)(params+0x38))->x++; enter(1); leave(1);
    assert(!ready() && mismatched_ids == 1);
    reset(); ((TileId *)(params+0x18))->y++; enter(1); leave(1);
    assert(!ready() && mismatched_ids == 1);
    reset(); ((TileId *)(params+0x18))->z=31; enter(1); leave(1);
    assert(!ready() && missing_context == 1 && invalid == 1);
    reset(); event(DECODER_IN,0xfe00,1,(uintptr_t)range); event(DECODER_OUT,0xfe00,1,0);
    assert(!ready() && missing_context == 1);
    reset(); enter(1); event(DECODER_IN,0xfd00,1,(uintptr_t)range);
    event(DECODER_OUT,0xfd00,1,0); leave(1); assert(ready() == 2 && !return_mismatch);
    reset(); enter(1); /* Exception bypasses decoder RET; wrapper ordinary RET survives. */
    event(WRAPPER_OUT,0xff00,1,0); event(LAYER_OUT,0x10000,1,0);
    assert(!ready() && abandoned == 1 && !return_mismatch);
    reset(); enter(1); enter(2); leave(2); leave(1); assert(ready() == 2 && !return_mismatch);
    reset(); for (unsigned i=1;i<=SLOT_COUNT+1;++i) {enter(i);leave(i);}
    assert(ready() == SLOT_COUNT && dropped == 1);
    reset(); for (unsigned i=1;i<=THREAD_COUNT*3;++i) {
        event(LAYER_IN,0x10000,i,(uintptr_t)params);event(LAYER_OUT,0x10000,i,0);
    } assert(!context_overflow && layer_seen == THREAD_COUNT*3);
    reset(); range[1] = range[0]+MAX_TILE_BYTES+1; enter(1);leave(1);
    assert(!ready() && oversize == 1);
    reset(); enter(1); regs[30]=1; denza_vector_event(regs,DECODER_OUT,0xfe00,1);
    assert(!ready() && return_mismatch == 1);
    reset(); for(unsigned i=0;i<=FRAME_COUNT;++i)
        event(LAYER_IN,0x10000-i*16,1,(uintptr_t)params);
    assert(native_state == -12 && context_overflow == 1);
    reset(); event(LAYER_IN,0x10000,1,(uintptr_t)params);
    event(WRAPPER_IN,0xff00,1,(uintptr_t)&tile);
    event(LAYER_IN,0xfe00,1,(uintptr_t)params);
    event(DECODER_IN,0xfd00,1,(uintptr_t)range); assert(!ready() && missing_context == 1);
    reset(); enter(1); /* Original RET LR and all saved registers are read-only. */
    uintptr_t before[31]; memcpy(before,regs,sizeof(before));
    denza_vector_event(regs,DECODER_OUT,0xfe00,1); assert(!memcmp(regs,before,sizeof(before)));
    return 0;
}
'''


class CollectorStateTest(unittest.TestCase):
    def test_bounded_success_context_failure_and_exception_lifecycle(self):
        clang = shutil.which('clang')
        if not clang:
            self.skipTest('Existing host clang unavailable')
        with tempfile.TemporaryDirectory(prefix='denza-vector-state-') as tmp:
            source, binary = Path(tmp) / 'state.c', Path(tmp) / 'state'
            source.write_text(HARNESS)
            subprocess.run([clang, '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                            '-Wno-unused-variable', '-Wno-unused-function', '-I', str(ROOT),
                            str(source), '-o', str(binary)], check=True, timeout=30)
            subprocess.run([str(binary)], check=True, timeout=10)

    def test_different_native_image_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'reviewed Navigator'):
            native_vector.verify_source(b'not the reviewed native image')


@unittest.skipUnless(CORPUS.is_file(), 'Pinned local native corpus required')
class RelayAbiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='denza-vector-build-')
        cls.directory = Path(cls.tmp.name)
        apk = cls.directory / 'source.apk'
        with zipfile.ZipFile(apk, 'w') as archive:
            archive.write(CORPUS, native_vector.SOURCE_ENTRY)
        cls.built = native_vector.build(apk, cls.directory / 'build')

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_compiled_exports_dependencies_and_exact_probes(self):
        report = self.built['report']
        self.assertEqual(6, len(report['source_hooks']))
        self.assertEqual(['libc.so', 'libdl.so'], report['needed'])
        self.assertEqual(3, len(report['jni_exports']))
        self.assertEqual(0, report['device_operations'])
        self.assertEqual(native_vector.APK_ENTRY, self.built['apk_entry'])

    def test_aarch64_all_registers_for_all_relocated_instructions(self):
        try:
            from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
            from unicorn.arm64_const import UC_ARM64_REG_SP, UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_LR
        except ImportError:
            self.skipTest('Use .venv-firmware Python for retained Unicorn AArch64 test')
        symbols, loads, _, bytes_at = elf_layout(Path(self.built['library']))
        base, relay, stack, snapshot, stop = 0x10000000, 0x12000000, 0x20000000, 0x30000000, 0x40000000
        for suffix in ('target', 'wrapper', 'layer', 'ret'):
            with self.subTest(instruction=suffix):
                u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
                # One compact RX image mapping; no BSS or libc execution is needed.
                high = max(row[3] + row[6] for row in loads)
                u.mem_map(base, (high + 4095) & ~4095)
                for row in loads:
                    u.mem_write(base + row[3], bytes_at(row[3], row[5]))
                u.mem_map(relay,4096);u.mem_map(stack,65536);u.mem_map(snapshot,4096);u.mem_map(stop,4096)
                begin = symbols['denza_vector_relay_begin'][0]
                end = symbols['denza_vector_relay_end'][0]
                code = bytearray(bytes_at(begin,end-begin))
                original = symbols['denza_vector_relay_original'][0]-begin
                branch = symbols['denza_vector_relay_branch'][0]-begin
                callback = symbols['denza_vector_relay_callback'][0]-begin
                target = symbols['denza_vector_probe_'+suffix][0]
                code[original:original+4] = bytes_at(target,4)
                delta = base+target+4-(relay+branch)
                struct.pack_into('<I',code,branch,0x14000000|((delta>>2)&0x3ffffff))
                struct.pack_into('<Q',code,callback,base+symbols['denza_vector_probe_callback'][0])
                u.mem_write(relay,bytes(code))
                u.reg_write(UC_ARM64_REG_SP,stack+0xf000)
                u.reg_write(UC_ARM64_REG_X0,relay);u.reg_write(UC_ARM64_REG_X1,snapshot)
                u.reg_write(UC_ARM64_REG_LR,stop)
                invoke = 'denza_vector_probe_invoke_ret' if suffix=='ret' else 'denza_vector_probe_invoke'
                u.emu_start(base+symbols[invoke][0],stop,count=10000)
                data = bytes(u.mem_read(snapshot,832)); words=struct.unpack('<104Q',data)
                self.assertEqual(snapshot,words[0])
                for i in range(1,30):self.assertEqual(relay if i==16 else 0x100+i,words[i],str(i))
                self.assertEqual(words[100],words[30])
                self.assertEqual((0xa0000000,0x01000000,0x08000000),words[31:34])
                for i in range(32):self.assertEqual(bytes([i])*16,data[0x110+i*16:0x120+i*16],str(i))
                self.assertEqual(words[98],words[99])
                self.assertEqual(stop,u.reg_read(UC_ARM64_REG_LR))


if __name__ == '__main__':
    unittest.main()
