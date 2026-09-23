#!/usr/bin/env python3
"""Compare the offline encoder against bounded ARM64 firmware execution.

Synthetic inputs only; no device, network, syscalls, or unknown external calls.
Requires pyelftools, Unicorn 2.1.4 and cryptography. Does not copy firmware.
"""
import argparse
import hashlib
import json
import struct
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import *
from registration_packet import build_registration, build_discovery, build_login, build_status
from status512_body import build_body

EXPECTED = "9e36cdbf841d54a3b1ea3631b5d5b867d5908bd191a113f53b5bb4182df82eb9"
OBJ, STRUCT, OUTPUT, BODY, VIN, DEBUG, GUARD, STACK, TLS, STOP = [0x200000 + i * 0x10000 for i in range(10)]


def native(source, vin, imsi, iccid, key, uuid, timestamp, discovery=False, login=False, status=False, charging=255):
    if hashlib.sha256(source.read_bytes()).hexdigest() != EXPECTED:
        raise ValueError("Unreviewed firmware")
    u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    with source.open("rb") as stream:
        elf = ELFFile(stream)
        segments = [s for s in elf.iter_segments() if s["p_type"] == "PT_LOAD"]
        u.mem_map(0, (max(s["p_vaddr"] + s["p_memsz"] for s in segments) + 4095) & ~4095)
        for segment in segments:
            u.mem_write(segment["p_vaddr"], segment.data())
        rel = elf.get_section_by_name(".rela.dyn")
        for item in rel.iter_relocations():
            if item["r_info_type"] == 1027:  # R_AARCH64_RELATIVE, zero base
                u.mem_write(item["r_offset"], struct.pack("<Q", item["r_addend"]))
    for address in (OBJ, STRUCT, OUTPUT, BODY, VIN, DEBUG, GUARD, STACK, TLS, STOP):
        u.mem_map(address, 0x10000)
    for got, ptr in ((0x8eba8, DEBUG), (0x8ec00, VIN), (0x8ecc8, GUARD)):
        u.mem_write(got, struct.pack("<Q", ptr))
    u.mem_write(GUARD, b"\x01")
    u.mem_write(GUARD + 4, b"\x01")
    u.mem_write(0x8ed00, struct.pack("<Q", GUARD + 4))
    u.mem_write(VIN, vin)
    body = b"\x01\x01" if discovery else imsi + iccid
    u.mem_write(BODY, body)
    u.mem_write(OBJ + 0xe2, uuid)
    u.mem_write(OBJ + 0xf2, key)
    u.reg_write(UC_ARM64_REG_TPIDR_EL0, TLS)
    u.reg_write(UC_ARM64_REG_CPACR_EL1, 3 << 20)
    calls = {}
    instructions = 0
    def reg(i):
        return u.reg_read(UC_ARM64_REG_X0 + i)
    def hook(engine, address, size, data):
        nonlocal instructions
        instructions += 1
        if address in (0x88560, 0x887f0, 0x885a0, 0x88640, 0x88620, 0x883c0, 0x88f60, 0x7e934, 0x88590):
            calls[hex(address)] = calls.get(hex(address), 0) + 1
            result = 0
            if address in (0x88560, 0x887f0):
                assert reg(2) <= 4096
                u.mem_write(reg(0), bytes(u.mem_read(reg(1), reg(2))))
                result = reg(0)
            elif address == 0x885a0:
                assert reg(2) <= 4096
                u.mem_write(reg(0), bytes([reg(1) & 255]) * reg(2))
                result = reg(0)
            elif address == 0x88640:
                assert bytes(u.mem_read(reg(0), 25)).split(b"\0")[0] == b"persist.sys.byd.apn_type"
                u.mem_write(reg(1), b"double_apn\0")
                result = 10
            elif address == 0x88620:
                result = len(bytes(u.mem_read(reg(0), 100)).split(b"\0")[0])
            elif address == 0x88f60:
                result = timestamp
            elif address == 0x88590:
                assert reg(0) == 105
                u.mem_write(OUTPUT, b"\xcc" * 105)
                result = OUTPUT
            u.reg_write(UC_ARM64_REG_X0, result)
            u.reg_write(UC_ARM64_REG_PC, u.reg_read(UC_ARM64_REG_LR))
            return
        allowed = ((0x6e3b0, 0x6ec80), (0x69774, 0x69868), (0x35828, 0x36058), (0x7c978, 0x7d5c0), (0x55350, 0x55644))
        if not any(a <= address < b for a, b in allowed):
            raise ValueError("Unexpected native target " + hex(address))
        word = struct.unpack("<I", u.mem_read(address, 4))[0]
        if word & 0xffe0001f == 0xd4000001:
            raise ValueError("Syscall forbidden")
    u.hook_add(UC_HOOK_CODE, hook)
    def call(address, args, end=STOP):
        u.reg_write(UC_ARM64_REG_SP, STACK + 0xf000)
        u.reg_write(UC_ARM64_REG_LR, STOP)
        for i, value in enumerate(args):
            u.reg_write(UC_ARM64_REG_X0 + i, value)
        u.emu_start(address, end, timeout=5_000_000, count=300_000)
        if u.reg_read(UC_ARM64_REG_PC) != end:
            raise ValueError("Execution bound reached")
        return reg(0)
    if login:
        call(0x7d554, [OBJ, OUTPUT, BODY, 15])
        digest = bytes(u.mem_read(OUTPUT, 16))
        if digest != hashlib.md5(imsi).digest():
            raise ValueError("Native IMSI digest mismatch")
        body = bytes(range(32, 48)) + bytes(16) + digest
        u.mem_write(BODY, body)
    if status:
        cache = [bytes((i * 8 + j) % 256 for j in range(8)) for i in range(25)]
        for i, value in enumerate(cache):
            u.mem_write(OBJ + 0x7f1 + i * 0x8c, value)
        u.mem_write(OBJ + 0x294, struct.pack("<I", charging))
        call(0x55350, [OBJ, 0], 0x55644)
        body = bytes(u.mem_read(OUTPUT, 104))
        if body != build_body(cache, charging):
            raise ValueError("Native status body layout mismatch")
        u.mem_write(BODY, body)
    command = 512 if status else 220 if login else 200 if discovery else 211
    call(0x6e3b0, [OBJ, 0, STRUCT, command, 255 if command in (211, 512) else 254, BODY, len(body), int(status)])
    length = call(0x6e858, [OBJ, STRUCT, OUTPUT, len(body)])
    assert length <= 1024
    return bytes(u.mem_read(OUTPUT, length)), {"instructions": instructions, "stub_calls": calls}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("firmware", type=Path)
    args = parser.parse_args()
    cases = []
    for timestamp, discovery, login, status, charging in ((1, False, False, False, 255), (1700000000, False, False, False, 255), (0xffffffff, False, False, False, 255), (1700000000, True, False, False, 255), (1700000000, False, True, False, 255), (1700000000, False, False, True, 255), (1700000000, False, False, True, 1)):
        inputs = (b"TEST123456789ABCD", b"001010123456789", b"89010000000000000001", bytes(range(16)), bytes(range(16, 32)), timestamp)
        expected = build_discovery(inputs[0], inputs[3], inputs[4], timestamp) if discovery else build_registration(*inputs)
        if login:
            expected = build_login(inputs[0], inputs[1], b"", bytes(range(32, 48)), inputs[3], inputs[4], timestamp)
        if status:
            cache = [bytes((i * 8 + j) % 256 for j in range(8)) for i in range(25)]
            expected = build_status(inputs[0], inputs[3], inputs[4], timestamp, build_body(cache, charging))
        actual, execution = native(args.firmware, *inputs, discovery=discovery, login=login, status=status, charging=charging)
        cases.append({"command": 512 if status else 220 if login else 200 if discovery else 211, "timestamp": timestamp, "byte_equal": actual == expected, "length": len(actual),
                      "native_packet_hex": actual.hex(), "execution": execution})
    result = {"synthetic_only": True, "firmware_sha256": EXPECTED, "all_passed": all(c["byte_equal"] for c in cases), "cases": cases}
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result["all_passed"] else 1)


if __name__ == "__main__":
    main()
