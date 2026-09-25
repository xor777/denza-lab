#!/usr/bin/env python3
"""Replay original firmware 532/511 with the C worker allocator on the host.

Unicorn still captures synthetic external effects. This audits arena ownership
and reuse through original instructions; it is not a worker or vehicle test.
"""
import argparse
import ctypes
import json
import subprocess
import sys
import tempfile
from pathlib import Path

from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_PC, UC_ARM64_REG_LR
from verify_native_control import NativeControl
from verify_native_roundtrip import Roundtrip, HELPER, PACKET, WIRE, CORRELATION
from verify_opaque_native import HEAP, INPUT, OBJ, EXPECTED, need, read_buffers

WRAPPER = r'''
#include "bounded_arena.h"
static arena_u8 bytes[0x10000] __attribute__((aligned(16)));
static struct bounded_arena arena;
void arena_setup(void) { arena_init(&arena,bytes,sizeof(bytes)); }
long arena_get(unsigned long n) {
 enum arena_status status;
 void *p=arena_allocate(&arena,n,&status);
 return status==ARENA_OK ? (long)((arena_u8 *)p-bytes) : -(long)status;
}
int arena_drop(unsigned long offset) {
 return arena_deallocate(&arena,offset ? bytes+offset : 0);
}
unsigned int arena_end(void) { return arena.end; }
unsigned int arena_live_bytes(void) {
 unsigned int at=0,total=0;
 while(at<arena.end) {
  struct arena_block *block;
  if(arena_block_at(&arena,at,&block)!=ARENA_OK)return 0xffffffffU;
  if(block->live)total+=block->size;
  at+=ARENA_HEADER+block->size;
 }
 return total;
}
'''


def load_allocator(directory):
    source = Path(directory) / "arena_ffi.c"
    library = Path(directory) / ("arena_ffi.dylib" if sys.platform == "darwin" else "arena_ffi.so")
    source.write_text(WRAPPER)
    subprocess.run(["clang", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
                    "-shared", "-fPIC", "-I", str(Path(__file__).parent),
                    str(source), "-o", str(library)], check=True)
    ffi = ctypes.CDLL(str(library))
    ffi.arena_get.argtypes = [ctypes.c_ulong]
    ffi.arena_get.restype = ctypes.c_long
    ffi.arena_drop.argtypes = [ctypes.c_ulong]
    ffi.arena_drop.restype = ctypes.c_int
    ffi.arena_end.restype = ctypes.c_uint
    ffi.arena_live_bytes.restype = ctypes.c_uint
    return ffi


class AllocatedControl(NativeControl):
    allocator = None

    def __init__(self, source, **kwargs):
        self.allocator.arena_setup()
        self.allocations = self.deallocations = 0
        self.high_water = 0
        super().__init__(source, **kwargs)

    def hook(self, engine, at, size, data):
        if at in (0x88470, 0x88590):
            offset = self.allocator.arena_get(self.reg(0))
            need(offset > 0, 'arena allocation status ' + str(offset))
            n = self.reg(0)
            self.u.mem_write(HEAP + offset, b'\xcc' * ((n + 15) & ~15))
            self.allocations += 1
            self.high_water = max(self.high_water, self.allocator.arena_end())
            result = HEAP + offset
        elif at in (0x88460, 0x885b0):
            pointer = self.reg(0)
            need(pointer == 0 or HEAP < pointer < HEAP + 0x10000,
                 'delete outside arena ' + hex(pointer))
            status = self.allocator.arena_drop(pointer - HEAP if pointer else 0)
            need(status == 0, 'arena deallocation status ' + str(status))
            self.deallocations += 1
            result = 0
        else:
            return super().hook(engine, at, size, data)
        self.u.reg_write(UC_ARM64_REG_X0, result)
        self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))


def audit(source, capture, iterations, ffi):
    AllocatedControl.allocator = ffi
    sender = AllocatedControl(source)
    control_frames = []
    for index in range(iterations):
        # The original dispatcher deduplicates a repeated 532 correlation.
        # Build distinct synthetic requests through its original encoder.
        opaque = index.to_bytes(4, 'little') + CORRELATION[4:] + bytes((3, 0, 0, 0))
        sender.u.mem_write(INPUT, opaque)
        sender.call(0x6e3b0, HELPER, 0, PACKET, 532, 254, INPUT, len(opaque), 0)
        sender.call(0x6e858, HELPER, PACKET, WIRE, len(opaque))
        control_frames.append(sender.frames[-1])
    status_frame = Roundtrip.request(sender)
    machine = AllocatedControl(source)
    callbacks = read_buffers(capture)
    machine.ingest(callbacks[0])  # One recorded opaque SDK callback makes 511 report data available.
    controls = statuses = 0
    for control_frame in control_frames:
        prior_writes = len(machine.vehicle_writes)
        machine.receive(control_frame)
        need(len(machine.vehicle_writes) == prior_writes + 1, 'native control not forwarded')
        reply = bytearray(machine.vehicle_writes[-1])
        reply[4] = 1
        machine.u.mem_write(INPUT, bytes(reply))
        prior_results = len(machine.results)
        machine.call(0x6039c, OBJ, 0x99000004, INPUT, len(reply))
        need(len(machine.results) == prior_results + 1 and
             machine.results[-1] == (3, 1, 0), 'native control not closed')
        controls += 1
        prior_sends, prior_frames = len(machine.sends), len(machine.frames)
        machine.receive(status_frame)
        need(len(machine.sends) == prior_sends + 1 and machine.sends[-1] == 511 and
             len(machine.frames) == prior_frames + 1, 'native status not answered')
        statuses += 1
    return {'firmware_sha256': EXPECTED, 'controls_completed': controls,
            'statuses_answered': statuses, 'arena_high_water_bytes': machine.high_water,
            'arena_end_bytes': ffi.arena_end(), 'arena_live_bytes': ffi.arena_live_bytes(),
            'allocations': machine.allocations,
            'deallocations': machine.deallocations,
            'scope': 'Original firmware under Unicorn, compiled C arena; synthetic inputs and captured external effects only.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('firmware', type=Path)
    parser.add_argument('capture', type=Path)
    parser.add_argument('--iterations', type=int, default=100)
    args = parser.parse_args()
    need(1 <= args.iterations <= 500, 'iteration bound')
    with tempfile.TemporaryDirectory(prefix='denza-arena-audit-') as temp:
        print(json.dumps(audit(args.firmware, args.capture, args.iterations, load_allocator(temp)), indent=2))
