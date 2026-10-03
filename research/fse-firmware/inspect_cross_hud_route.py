#!/usr/bin/env python3
"""Replay Cross buffer paths from the pinned FSE or IVI service library.

Offline only. Socket input/output, callbacks, allocation and containers are
synthetic. Does not open sockets, execute native host code or contact a vehicle.
This proves selected function paths, not Binder access or network delivery.
"""
import argparse
import hashlib
import json
import struct
from pathlib import Path

from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (UC_ARM64_REG_X0, UC_ARM64_REG_SP,
                                UC_ARM64_REG_LR, UC_ARM64_REG_PC,
                                UC_ARM64_REG_TPIDR_EL0, UC_ARM64_REG_CPACR_EL1)
from inspect_hud_hal import packed_relocations

EXPECTED = "50073cc57f42f5e885a3d160db257342ab5b5bd8166541ee6d94b69598b03949"
IVI_EXPECTED = "eeb188911f9d346a8f53d91b4cba8ed237941024c9e5fa50a7aed7e8096143a9"
HEAP, STACK, OBJECT, TLS, STOP, CALLBACK = 0x100000, 0x2100000, 0x2200000, 0x2300000, 0x2400000, 0x2400100


def inspect(path):
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest not in (EXPECTED, IVI_EXPECTED):
        raise ValueError("Unreviewed Cross service hash")
    is_ivi = digest == IVI_EXPECTED
    u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    names, definitions = {}, {}
    with path.open("rb") as stream:
        elf = ELFFile(stream)
        segments = [s for s in elf.iter_segments() if s["p_type"] == "PT_LOAD"]
        u.mem_map(0, (max(s["p_vaddr"] + s["p_memsz"] for s in segments) + 4095) & ~4095)
        for segment in segments:
            u.mem_write(segment["p_vaddr"], segment.data())
        symbols = elf.get_section_by_name(".dynsym")
        for symbol in symbols.iter_symbols():
            if symbol["st_value"]:
                definitions[symbol.name] = symbol["st_value"]
        for offset, kind, index, addend in packed_relocations(elf.get_section_by_name(".rela.dyn").data()):
            if kind == 1027:
                u.mem_write(offset, struct.pack("<Q", addend))
            elif kind in (1025, 257):
                symbol = symbols.get_symbol(index)
                if symbol["st_value"]:
                    u.mem_write(offset, struct.pack("<Q", symbol["st_value"] + addend))
        base = elf.get_section_by_name(".plt")["sh_addr"] + 32
        for i, relocation in enumerate(elf.get_section_by_name(".rela.plt").iter_relocations()):
            names[base + i * 16] = symbols.get_symbol(relocation["r_info_sym"]).name
    u.mem_map(HEAP, STACK - HEAP)
    u.mem_map(STACK, 0x100000)
    for address in (OBJECT, TLS, STOP):
        u.mem_map(address, 0x10000)
    u.reg_write(UC_ARM64_REG_TPIDR_EL0, TLS)
    u.reg_write(UC_ARM64_REG_CPACR_EL1, 3 << 20)
    heap, instructions = HEAP, 0
    registrations, calls, callbacks, sends, queries, cached = [], {}, [], [], [], []
    packet = b""
    received = False

    def reg(i):
        return u.reg_read(UC_ARM64_REG_X0 + i)

    def word(address):
        return struct.unpack("<I", u.mem_read(address, 4))[0]

    def pointer(address, value):
        u.mem_write(address, struct.pack("<Q", value))

    def hook(engine, address, size, data):
        nonlocal heap, instructions, received
        instructions += 1
        ranges = ((0x1b580, 0x1b85c), (0x30900, 0x30cc4)) if is_ivi else (
                (0x28100, 0x2ba50), (0x26f6c, 0x27150),
                (0x25d88, 0x25d94), (0x2678c, 0x2685c),
                (0x2f59c, 0x2f964), (0x2fad4, 0x304dc))
        if any(lo <= address < hi for lo, hi in ranges):
            if word(address) & 0xffe0001f == 0xd4000001:
                raise ValueError("Syscall forbidden")
            return
        name = names.get(address, "")
        calls[name or hex(address)] = calls.get(name or hex(address), 0) + 1
        result = 0
        if address == CALLBACK:
            if reg(3) > 64:
                raise ValueError("Callback size bound")
            callbacks.append({"device": reg(0), "fid": hex(reg(1)),
                              "payload_hex": bytes(u.mem_read(reg(2), reg(3))).hex()})
        elif name.endswith("FeatureMapper9initValueERKi") or name.endswith("FeatureMapper10getQurTypeERKj") or name.endswith("FeatureMapper9getDeviceERKj"):
            if "getDevice" in name:
                queries.append(hex(word(reg(1))))
            u.reg_write(UC_ARM64_REG_PC, definitions[name])
            return
        elif is_ivi and name.endswith("PubSubManager9setBufferEiiPci"):
            u.reg_write(UC_ARM64_REG_PC, definitions[name])
            return
        elif is_ivi and name.endswith("PubSubManager13getDeviceTypeEv"):
            result = 1  # synthetic IVI identity; not a live role observation
        elif "setQurParameter" in name or "setReqParameter" in name:
            p = reg(2)
            registrations.append({"fid": hex(word(reg(1))), "map": "get" if "setQur" in name else "set",
                                  "device": word(p + 8), "type": word(p + 12),
                                  "call": hex(u.reg_read(UC_ARM64_REG_LR) - 4)})
        elif name == "_Znwm":
            length = reg(0)
            if not 0 < length <= 0x20000 or heap + length >= STACK:
                raise ValueError("Allocation bound")
            result = heap
            heap += (length + 15) & ~15
        elif name in ("memset", "__memset_chk"):
            if reg(2) > 0x40000:
                raise ValueError("Clear bound")
            u.mem_write(reg(0), bytes([reg(1) & 255]) * reg(2))
            result = reg(0)
        elif name in ("memcpy", "__memcpy_chk"):
            if reg(2) > 0x10010:
                raise ValueError("Copy bound")
            u.mem_write(reg(0), bytes(u.mem_read(reg(1), reg(2))))
            result = reg(0)
        elif name == "__strlen_chk":
            if reg(1) > 128:
                raise ValueError("String bound")
            result = bytes(u.mem_read(reg(0), reg(1))).index(0)
        elif name in ("pthread_mutex_lock", "pthread_mutex_unlock", "__android_log_print"):
            pass
        elif name.endswith("PubSubManager7pubDataEPcii"):
            if reg(2) > 64:
                raise ValueError("Publication size bound")
            sends.append(bytes(u.mem_read(reg(1), reg(2))).hex())
            result = 1  # synthetic successful publication
        elif name == "zmq_recv":
            if received:
                raise ValueError("Only one synthetic receive allowed")
            if not packet or len(packet) > reg(2):
                raise ValueError("Receive size bound")
            u.mem_write(reg(1), packet)
            received = True
            result = len(packet)
        elif name.endswith("FeatureMapper14setStringValueERKjPcS2_"):
            cached.append(hex(word(reg(1))))
        else:
            raise ValueError("Unexpected target " + hex(address) + " " + name)
        u.reg_write(UC_ARM64_REG_X0, result)
        u.reg_write(UC_ARM64_REG_PC, u.reg_read(UC_ARM64_REG_LR))

    u.hook_add(UC_HOOK_CODE, hook)

    def call(address, args):
        u.reg_write(UC_ARM64_REG_SP, STACK + 0xfff00)
        u.reg_write(UC_ARM64_REG_LR, STOP)
        for i, value in enumerate(args):
            u.reg_write(UC_ARM64_REG_X0 + i, value)
        u.emu_start(address, STOP, timeout=10_000_000, count=250000)
        if u.reg_read(UC_ARM64_REG_PC) != STOP:
            raise ValueError("Execution bound reached")

    if is_ivi:
        service, manager = OBJECT, OBJECT + 0x3000
        pointer(service + 0x138, manager)
        u.mem_write(0x3dbec, struct.pack("<i", -1))
        u.mem_write(0x3dc5c, struct.pack("<i", -1))
        u.mem_write(0x3dc0c, b"\x00")
        u.mem_write(0x3dc14, b"\x00")
        cases = []
        for value in (1, 2):
            body = struct.pack(">IBI", 0x1b60a010, 4, value)
            packet = struct.pack(">II", 0x2130001c, len(body)) + body
            u.mem_write(OBJECT + 0x6000, body)
            sends.clear()
            callbacks.clear()
            call(0x1b580, (service, 1000, 0x2130001c, OBJECT + 0x6000, len(body), 0))
            if reg(0) != 1 or sends != [packet.hex()] or callbacks:
                raise ValueError("Unexpected IVI publication result")
            cases.append({"hud_value": value, "service_result": reg(0),
                          "published_hex": sends[0], "local_callback": False})
        return {"firmware_sha256": digest, "offline_only": True, "role": "IVI",
                "instructions": instructions, "cases": cases, "hook_calls": calls,
                "limits": ["Synthetic IVI role, service object and successful pubData",
                           "No JNI, Binder, socket, permissions or hardware execution",
                           "Rear loopback flags explicitly clear; no role initialization"]}

    pointer(OBJECT, OBJECT + 0x1000)
    pointer(OBJECT + 8, OBJECT + 0x2000)
    call(0x28100, (OBJECT + 0x3000, OBJECT, OBJECT + 8))
    targets = [r for r in registrations if r["fid"] in ("0x2130001c", "0xff300039")]
    if len(targets) != 4 or any(r["device"] != 1000 or r["type"] != 0x300000 for r in targets):
        raise ValueError("Unexpected Cross target registration")
    # A synthetic one-bucket query map uses the real getDevice lookup. The
    # registration initializer above supplies its device and type metadata.
    mapper = OBJECT + 0x1000
    pointer(mapper + 8, OBJECT + 0x4000)
    pointer(mapper + 16, 1)
    pointer(OBJECT + 0x4000, mapper + 24)
    pointer(mapper + 24, OBJECT + 0x4020)
    pointer(OBJECT + 0x4020 + 24, OBJECT + 0x4060)
    u.mem_write(OBJECT + 0x4068, struct.pack("<I", 1000))
    manager, worker = OBJECT + 0x3000, OBJECT + 0x5000
    pointer(manager + 0x90, mapper)
    pointer(manager + 0xa0, worker)
    pointer(manager + 0xc8, CALLBACK)
    pointer(manager + 0xe0, 0x1234)  # synthetic socket handle
    u.mem_write(worker + 0xa8, struct.pack("<I", 2))
    u.mem_write(0x3cc5c, struct.pack("<i", -1))  # suppress optional logs
    cases = []
    # Equal bodies, different source/destination nibbles. Each case receives a
    # synthetic registration: controls test dispatch masks, not real catalogue IDs.
    for fid in (0x2130001c, 0x1230001c, 0xff30001c):
        body = bytes.fromhex("1b60a0100400000002")
        packet = struct.pack(">II", fid, len(body)) + body
        u.mem_write(OBJECT + 0x6000, body)
        sends.clear()
        callbacks.clear()
        queries.clear()
        cached.clear()
        # Rear-variant loopback flags are clear; ordinary setBuffer publishes.
        u.mem_write(0x3cc0c, b"\x00")
        u.mem_write(0x3cc14, b"\x00")
        call(0x2f59c, (manager, 1000, fid, OBJECT + 0x6000, len(body)))
        if sends != [packet.hex()] or callbacks:
            raise ValueError("Unexpected outgoing publication/loopback")
        u.mem_write(OBJECT + 0x4028, struct.pack("<QI", fid, fid))
        received = False
        call(0x2fad4, (manager,))
        expected = {"device": 1000, "fid": hex(fid), "payload_hex": body.hex()}
        if callbacks != [expected] or queries != [hex(fid)] or cached != [hex(fid)]:
            raise ValueError("Unexpected buffer dispatch")
        cases.append({"fid": hex(fid), "catalogue_target": fid == 0x2130001c,
                      "published_hex": sends[0], "local_setter_callback": False,
                      "receiver_callbacks": list(callbacks)})
    return {"firmware_sha256": EXPECTED, "offline_only": True,
            "instructions": instructions, "registration_count": len(registrations),
            "targets": targets, "cases": cases, "hook_calls": calls,
            "limits": ["Synthetic ready connection and receive bytes; no network or Binder execution",
                       "Synthetic callback instead of service subscribers and HAL",
                       "Control FIDs have synthetic registrations; catalogue validity not asserted",
                       "Rear loopback role flags explicitly clear; no runtime role initialization"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("library", type=Path)
    print(json.dumps(inspect(parser.parse_args().library), indent=2))
