#!/usr/bin/env python3
"""Bounded offline execution of the stock cloudmanager network notification.

Synthetic object and properties only. Never accesses a device/network, invokes
native external calls or changes the ELF. Dependencies: Unicorn 2.1.4, pyelftools.
Calls beyond notify_nw are recorded stubs, not an emulation of their effects.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (
    UC_ARM64_REG_X0, UC_ARM64_REG_LR, UC_ARM64_REG_PC, UC_ARM64_REG_SP,
    UC_ARM64_REG_TPIDR_EL0, UC_ARM64_REG_CPACR_EL1,
)

EXPECTED = "9e36cdbf841d54a3b1ea3631b5d5b867d5908bd191a113f53b5bb4182df82eb9"
OBJ, STACK, TLS, DATA, STOP = [0x200000 + i * 0x10000 for i in range(5)]
ENTRY, END = 0x4B694, 0x4BC60
STUBS = {
    0x883C0: "log", 0x88640: "property_get", 0x887B0: "string_assign",
    0x7E508: "network_event_recorder", 0x36B78: "connected_state_helper",
    0x49F80: "vin_readiness_check", 0x4BC60: "resolve_endpoints",
    0x4C410: "connection_dispatch", 0x4ADD4: "dispatch_fallback",
    0x4A858: "missing_guid_path", 0x4AD30: "format_integer",
    0x885C0: "property_set", 0x88890: "mutex_lock",
    0x888B0: "mutex_unlock", 0x889B0: "condition_signal",
    0x4B35C: "disconnect_fallback",
}


def run(source, profile, state, initial_gate=0, dns_ok=True, guid=True):
    raw = source.read_bytes()
    if hashlib.sha256(raw).hexdigest() != EXPECTED:
        raise ValueError("Unreviewed firmware")
    u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    with source.open("rb") as stream:
        elf = ELFFile(stream)
        segments = [s for s in elf.iter_segments() if s["p_type"] == "PT_LOAD"]
        size = (max(s["p_vaddr"] + s["p_memsz"] for s in segments) + 4095) & ~4095
        u.mem_map(0, size)
        for s in segments:
            u.mem_write(s["p_vaddr"], s.data())
        for r in elf.get_section_by_name(".rela.dyn").iter_relocations():
            if r["r_info_type"] == 1027:
                u.mem_write(r["r_offset"], struct.pack("<Q", r["r_addend"]))
    for a in (OBJ, STACK, TLS, DATA, STOP):
        u.mem_map(a, 0x10000)
    # Pre-existing singleton, logging flag and network event recorder.
    for got, pointer in ((0x8ECC8, DATA), (0x8ECD0, DATA + 0x100), (0x8EBA8, DATA + 0x200)):
        u.mem_write(got, struct.pack("<Q", pointer))
    u.mem_write(DATA, b"\x01")
    u.mem_write(OBJ + 0x35D, bytes([1, initial_gate]))
    u.mem_write(OBJ + 0x615, bytes([int(guid)]))
    u.mem_write(OBJ + 0x2E20 + 0x142E, b"\xff\xff")
    properties = {"persist.sys.byd.apn_type": profile, "net.lte.apn1.state": "disconnected"}
    calls, writes = [], []
    count = 0

    def reg(i):
        return u.reg_read(UC_ARM64_REG_X0 + i)

    def string(address):
        data = bytes(u.mem_read(address, 128))
        if b"\0" not in data:
            raise ValueError("Unbounded string")
        return data.split(b"\0", 1)[0].decode()

    def hook(_u, address, size, _data):
        nonlocal count
        count += 1
        if address in STUBS:
            name, result = STUBS[address], 0
            if name == "property_get":
                key = string(reg(0))
                if key not in properties:
                    raise ValueError("Unexpected property read " + key)
                value = properties[key].encode()
                u.mem_write(reg(1), value + b"\0")
                result = len(value)
            elif name == "string_assign":
                value = string(reg(1)).encode()
                if len(value) > 22:
                    raise ValueError("Only bounded libc++ short strings supported")
                u.mem_write(reg(0), bytes([len(value) * 2]) + value + b"\0")
                result = reg(0)
            elif name == "format_integer":
                value = str(reg(3) & 0xFFFFFFFF).encode()
                if len(value) >= reg(1):
                    raise ValueError("Invalid format buffer")
                u.mem_write(reg(0), value + b"\0")
                result = len(value)
            elif name == "property_set":
                key, value = string(reg(0)), string(reg(1))
                if key not in ("sys.tcp_step", "sys.tcp_reg_errcode"):
                    raise ValueError("Unexpected simulated write " + key)
                writes.append({"key": key, "value": value})
            elif name == "resolve_endpoints":
                result = int(dns_ok)
            elif name == "connection_dispatch":
                result = 1
            if name not in ("log", "string_assign", "format_integer", "property_get"):
                event = {"call": name, "address": hex(address)}
                if name in ("network_event_recorder", "connection_dispatch"):
                    event["arg1"] = reg(1)
                    if name == "network_event_recorder":
                        event["arg2"] = reg(2)
                calls.append(event)
            u.reg_write(UC_ARM64_REG_X0, result)
            u.reg_write(UC_ARM64_REG_PC, u.reg_read(UC_ARM64_REG_LR))
            return
        if not ENTRY <= address < END:
            raise ValueError("Unknown external target " + hex(address))
        word = struct.unpack("<I", u.mem_read(address, 4))[0]
        if word & 0xFFE0001F == 0xD4000001:
            raise ValueError("Syscall forbidden")

    u.hook_add(UC_HOOK_CODE, hook)
    u.reg_write(UC_ARM64_REG_SP, STACK + 0xF000)
    u.reg_write(UC_ARM64_REG_LR, STOP)
    u.reg_write(UC_ARM64_REG_TPIDR_EL0, TLS)
    u.reg_write(UC_ARM64_REG_CPACR_EL1, 3 << 20)
    u.reg_write(UC_ARM64_REG_X0, OBJ)
    u.reg_write(UC_ARM64_REG_X0 + 1, state & 0xFFFFFFFF)
    u.emu_start(ENTRY, STOP, timeout=1_000_000, count=10000)
    if u.reg_read(UC_ARM64_REG_PC) != STOP:
        raise ValueError("Execution bound reached")
    return {"profile": profile, "state": state, "initial_gate": initial_gate,
            "dns_stub_success": dns_ok, "guid_present": guid,
            "final_network_ok": u.mem_read(OBJ + 0x35D, 1)[0],
            "final_gate": u.mem_read(OBJ + 0x35E, 1)[0],
            "queued_event": struct.unpack("<h", u.mem_read(OBJ + 0x2E20 + 0x142E, 2))[0],
            "calls": calls, "simulated_property_writes": writes, "instructions": count}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("firmware", type=Path)
    args = parser.parse_args()
    cases = []
    for profile, connected, disconnected in (("triple_apn", 1, -2), ("double_apn", 4, -5)):
        for initial in (0, 1):
            for state in range(-5, 5):
                case = run(args.firmware, profile, state, initial)
                expected = 1 if state == connected else 0 if state == disconnected else initial
                if case["final_gate"] != expected:
                    raise ValueError("Unexpected gate result")
                cases.append(case)
    cases.append(run(args.firmware, "double_apn", 4, dns_ok=False))
    cases.append(run(args.firmware, "double_apn", 4, guid=False))
    print(json.dumps({"mode": "offline_native_emulation", "firmware_sha256": EXPECTED,
        "device_or_network_access": False, "elf_modified": False, "all_passed": True,
        "limit": "Only notify_nw executes; downstream calls are recorded stubs. No TLS/registration or complete rollback proof.",
        "cases": cases}, indent=2))


if __name__ == "__main__":
    main()
