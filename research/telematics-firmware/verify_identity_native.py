#!/usr/bin/env python3
"""Bounded execution of stock identity preparation, with synthetic inputs only.

Requires pyelftools and Unicorn 2.1.4. No device, network, or native syscalls.
Executes the reviewed ARM64 constructor, prepare, MD5, and 211 body builder.
Stops at entry to the serializer: this does not emulate a server response.
"""
import argparse
import hashlib
import json
import struct
from pathlib import Path

from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (
    UC_ARM64_REG_X0, UC_ARM64_REG_PC, UC_ARM64_REG_LR,
    UC_ARM64_REG_SP, UC_ARM64_REG_TPIDR_EL0, UC_ARM64_REG_CPACR_EL1,
)

EXPECTED = "9e36cdbf841d54a3b1ea3631b5d5b867d5908bd191a113f53b5bb4182df82eb9"
OBJ, HELPER, OUTPUT, STACK, TLS, STOP = [0x200000 + i * 0x10000 for i in range(6)]
IMSI_A, IMSI_B = "001010123456789", "001010987654321"
ICCID_A, ICCID_B = "89010000000000000001", "89010000000000000002"


class IdentityMachine:
    def __init__(self, source):
        self.u = u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        with source.open("rb") as stream:
            elf = ELFFile(stream)
            segments = [s for s in elf.iter_segments() if s["p_type"] == "PT_LOAD"]
            u.mem_map(0, (max(s["p_vaddr"] + s["p_memsz"] for s in segments) + 4095) & ~4095)
            for segment in segments:
                u.mem_write(segment["p_vaddr"], segment.data())
            for item in elf.get_section_by_name(".rela.dyn").iter_relocations():
                if item["r_info_type"] == 1027:
                    u.mem_write(item["r_offset"], struct.pack("<Q", item["r_addend"]))
        for address in (OBJ, HELPER, OUTPUT, STACK, TLS, STOP):
            u.mem_map(address, 0x10000)
        u.mem_write(0x8eec8, struct.pack("<Q", OUTPUT))
        u.reg_write(UC_ARM64_REG_TPIDR_EL0, TLS)
        u.reg_write(UC_ARM64_REG_CPACR_EL1, 3 << 20)
        self.properties = {}
        self.reads = []
        self.instructions = 0
        self.body = None
        self.command = None
        u.hook_add(UC_HOOK_CODE, self.hook)
        # Poison memory so cache initialization must be performed by the constructor.
        u.mem_write(OBJ, b"\xcc" * 0x108)
        self.call(0x6d9f4)
        assert bytes(u.mem_read(OBJ + 0x64, 20)) == bytes(20)
        assert bytes(u.mem_read(OBJ + 0x79, 15)) == bytes(15)
        assert bytes(u.mem_read(OBJ + 0xa7, 16)) == bytes(16)

    def reg(self, index):
        return self.u.reg_read(UC_ARM64_REG_X0 + index)

    def hook(self, engine, address, size, data):
        self.instructions += 1
        u = self.u
        if address == 0x6e3b0:
            assert self.reg(3) == 211 and self.reg(6) == 35
            self.command = self.reg(3)
            self.body = bytes(u.mem_read(self.reg(5), self.reg(6)))
            u.emu_stop()
            return
        stubs = (0x88990, 0x88470, 0x7c920, 0x88410, 0x883f0,
                 0x883c0, 0x88640, 0x88560, 0x887f0, 0x885a0)
        if address in stubs:
            result = 0
            if address == 0x88470:
                assert self.reg(0) == 16
                u.mem_write(HELPER, bytes(16))
                result = HELPER
            elif address == 0x88640:
                key = bytes(u.mem_read(self.reg(0), 92)).split(b"\0")[0].decode("ascii")
                assert key in ("ril.csim.iccid", "ril.imsi", "debug.ro.serialno")
                assert self.reg(2) == 0
                self.reads.append(key)
                value = self.properties.get(key, "").encode("ascii")
                assert len(value) < 92 and b"\0" not in value
                u.mem_write(self.reg(1), value + b"\0")
                result = len(value)
            elif address in (0x88560, 0x887f0):
                assert self.reg(2) <= 4096
                u.mem_write(self.reg(0), bytes(u.mem_read(self.reg(1), self.reg(2))))
                result = self.reg(0)
            elif address == 0x885a0:
                assert self.reg(2) <= 4096
                u.mem_write(self.reg(0), bytes([self.reg(1) & 255]) * self.reg(2))
                result = self.reg(0)
            u.reg_write(UC_ARM64_REG_X0, result)
            u.reg_write(UC_ARM64_REG_PC, u.reg_read(UC_ARM64_REG_LR))
            return
        allowed = ((0x6d9f4, 0x6da9c), (0x6dcf0, 0x6dfa0),
                   (0x6e00c, 0x6e10c), (0x699f0, 0x69a6c),
                   (0x7c978, 0x7d5c0))
        if not any(start <= address < end for start, end in allowed):
            raise ValueError("Unexpected native target " + hex(address))
        word = struct.unpack("<I", u.mem_read(address, 4))[0]
        if word & 0xffe0001f == 0xd4000001:
            raise ValueError("Syscall forbidden")

    def call(self, address):
        u = self.u
        u.reg_write(UC_ARM64_REG_SP, STACK + 0xf000)
        u.reg_write(UC_ARM64_REG_LR, STOP)
        u.reg_write(UC_ARM64_REG_X0, OBJ)
        u.emu_start(address, STOP, timeout=5_000_000, count=300_000)
        if u.reg_read(UC_ARM64_REG_PC) not in (STOP, 0x6e3b0):
            raise ValueError("Execution bound reached")
        return self.reg(0)

    def step(self, iccid, imsi=IMSI_A):
        self.properties = {"ril.csim.iccid": iccid, "ril.imsi": imsi}
        self.reads = []
        self.body = self.command = None
        before = self.instructions
        ready = self.call(0x6dcf0)
        assert ready in (0, 1)
        if ready:
            self.call(0x6e00c)
            assert self.body is not None
        digest = bytes(self.u.mem_read(OBJ + 0xa7, 16))
        cached_imsi = bytes(self.u.mem_read(OBJ + 0x79, 15))
        if ready:
            assert digest == hashlib.md5(cached_imsi).digest()
        return {
            "input_iccid": iccid, "input_imsi": imsi, "prepare_ok": bool(ready),
            "property_reads": list(self.reads), "command": self.command,
            "body_hex": self.body.hex() if self.body is not None else None,
            "cached_iccid_hex": bytes(self.u.mem_read(OBJ + 0x64, 20)).hex(),
            "cached_imsi_hex": cached_imsi.hex(), "imsi_md5_hex": digest.hex(),
            "instructions": self.instructions - before,
        }


def fixed(value, width):
    return value.encode("ascii")[:width].ljust(width, b"\0")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("firmware", type=Path)
    args = parser.parse_args()
    if hashlib.sha256(args.firmware.read_bytes()).hexdigest() != EXPECTED:
        raise ValueError("Unreviewed firmware")
    cases = []

    def check(name, steps):
        machine = IdentityMachine(args.firmware)
        results = []
        for iccid, imsi, expected_iccid, expected_imsi in steps:
            result = machine.step(iccid, imsi)
            expected_body = None if expected_iccid is None else (
                fixed(expected_imsi, 15) + fixed(expected_iccid, 20)).hex()
            assert result["body_hex"] == expected_body, (name, result)
            result["expected_body_equal"] = True
            results.append(result)
        cases.append({"name": name, "steps": results})

    for name, value in (
        ("decimal_20", ICCID_A), ("decimal_19", ICCID_A[:19]),
        ("decimal_19_upper_F", ICCID_A[:19] + "F"),
        ("decimal_19_lower_f", ICCID_A[:19] + "f"),
        ("decimal_22_truncated", ICCID_A + "23"),
        ("one_digit", "8"), ("twenty_F", "F" * 20),
        ("non_decimal", "89ABCDEF000000000001"),
    ):
        check(name, [(value, IMSI_A, value, IMSI_A)])
    check("empty_iccid", [("", IMSI_A, None, None)])
    check("empty_imsi", [(ICCID_A, "", None, None)])
    check("short_imsi", [(ICCID_A, "00101", ICCID_A, "00101")])
    check("cached_identity_ignores_changes", [
        (ICCID_A, IMSI_A, ICCID_A, IMSI_A),
        (ICCID_B, IMSI_B, ICCID_A, IMSI_A),
        ("", "", ICCID_A, IMSI_A),
    ])
    check("partial_prepare_can_mix_identities", [
        (ICCID_A, "", None, None),
        (ICCID_B, IMSI_B, ICCID_A, IMSI_B),
    ])
    check("empty_iccid_does_not_latch", [
        ("", IMSI_A, None, None),
        (ICCID_B, IMSI_B, ICCID_B, IMSI_B),
    ])
    # A zero first byte of binary MD5 is treated as an empty cache by prepare.
    zero_md5_imsi = next(f"00101{i:010d}" for i in range(10000)
                         if hashlib.md5(f"00101{i:010d}".encode()).digest()[0] == 0)
    check("zero_md5_first_byte_rereads_imsi", [
        (ICCID_A, zero_md5_imsi, ICCID_A, zero_md5_imsi),
        (ICCID_B, IMSI_B, ICCID_A, IMSI_B),
    ])
    print(json.dumps({
        "synthetic_only": True, "firmware_sha256": EXPECTED,
        "scope": "constructor + prepare + native MD5 + 211 body; stop before serializer",
        "stubbed": ["Android RefBase/helper lifetime", "allocation", "logging",
                    "property_get", "memcpy", "memset"],
        "all_passed": True, "cases": cases,
    }, indent=2))


if __name__ == "__main__":
    main()
