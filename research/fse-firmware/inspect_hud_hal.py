#!/usr/bin/env python3
"""Inspect FSE HUD feature registration, conversion and local SPI routing.

Offline only: no Android process, device, socket or native host execution.
Requires pyelftools and unicorn 2.1.4. Accepts only the inspected OTA HAL hash.
Allocation/map insertion/vector assignment are synthetic hooks; the registration
instructions, Parameter factory, arhudshift, integer codec and message splitter
execute from the supplied firmware. Synthetic inputs do not prove vehicle state.
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

EXPECTED = "29e712e309a9bf547a2c4ad9cfe38d34f56c71acf7721630c3c4ec33d3977868"
START, END = 0xfa4ec, 0x1a2190
HEAP, STACK, TLS, OBJECT, MAPPER, AUX, STOP = (
    0x400000, 0xc00000, 0xc20000, 0xc30000, 0xc40000, 0xc50000, 0xc60000)
TARGETS = (0x1b60a010, 0x38b00036, 0x38b0003a, 0x4c50000f, 0x4c500014)


def packed_relocations(data):
    """APS2 integer stream; format checked against AOSP linker_reloc_iterators.h."""
    # https://android.googlesource.com/platform/bionic/+/master/linker/linker_reloc_iterators.h
    if data[:4] != b"APS2":
        raise ValueError("Expected Android packed RELA")
    pos = 4
    def signed():
        nonlocal pos
        value, shift = 0, 0
        while True:
            byte = data[pos]
            pos += 1
            value |= (byte & 127) << shift
            shift += 7
            if shift > 70:
                raise ValueError("Invalid SLEB128")
            if byte < 128:
                return value - (1 << shift) if byte & 64 else value
    count, offset = signed(), signed()
    done, info, addend = 0, 0, 0
    while done < count:
        size, flags = signed(), signed()
        if not 0 < size <= count - done or flags & ~15:
            raise ValueError("Invalid relocation group")
        delta = signed() if flags & 2 else 0
        if flags & 1:
            info = signed()
        if flags & 12 == 12:
            addend += signed()
        elif flags & 12 != 8:
            addend = 0
        for _ in range(size):
            offset += delta if flags & 2 else signed()
            if not flags & 1:
                info = signed()
            if flags & 12 == 8:
                addend += signed()
            yield offset, info & 0xffffffff, info >> 32, addend
        done += size
    if pos != len(data):
        raise ValueError("Unused packed relocation bytes")


def inspect(path):
    if hashlib.sha256(path.read_bytes()).hexdigest() != EXPECTED:
        raise ValueError("Unreviewed HAL hash")
    u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    names = {}
    with path.open("rb") as stream:
        elf = ELFFile(stream)
        segments = [s for s in elf.iter_segments() if s["p_type"] == "PT_LOAD"]
        u.mem_map(0, (max(s["p_vaddr"] + s["p_memsz"] for s in segments) + 4095) & ~4095)
        for s in segments:
            u.mem_write(s["p_vaddr"], s.data())
        dynsym = elf.get_section_by_name(".dynsym")
        for s in dynsym.iter_symbols():
            if s["st_value"]:
                names[s["st_value"]] = s.name
        # RELR needs no adjustment at the zero load base used by this emulator.
        for offset, kind, index, addend in packed_relocations(elf.get_section_by_name(".rela.dyn").data()):
            if kind == 1027:
                u.mem_write(offset, struct.pack("<Q", addend))
            elif kind in (1025, 257):
                sym = dynsym.get_symbol(index)
                if sym["st_value"]:
                    u.mem_write(offset, struct.pack("<Q", sym["st_value"] + addend))
        plt = elf.get_section_by_name(".plt")["sh_addr"] + 32
        for i, r in enumerate(elf.get_section_by_name(".rela.plt").iter_relocations()):
            names[plt + 16 * i] = dynsym.get_symbol(r["r_info_sym"]).name
    u.mem_map(HEAP, 0x800000)
    for p in (STACK, TLS, OBJECT, MAPPER, AUX, STOP):
        u.mem_map(p, 0x10000)
    u.reg_write(UC_ARM64_REG_TPIDR_EL0, TLS)
    u.reg_write(UC_ARM64_REG_CPACR_EL1, 3 << 20)
    heap = HEAP
    calls, entries, instructions = {}, [], 0
    hud_config, reads, writes = 0, [], []
    filter_sets = {}
    software_version, property_reads = "", []
    cross_enables, cross_writes = [], []

    def reg(i):
        return u.reg_read(UC_ARM64_REG_X0 + i)

    def word(p):
        return struct.unpack("<I", u.mem_read(p, 4))[0]

    def cpp_string(p):
        first = u.mem_read(p, 1)[0]
        if first & 1:
            length, address = struct.unpack("<QQ", u.mem_read(p + 8, 16))
        else:
            length, address = first >> 1, p + 1
        if length > 128:
            raise ValueError("Property string bound")
        return bytes(u.mem_read(address, length)).decode()

    def hook(engine, address, size, data):
        nonlocal heap, instructions
        instructions += 1
        if (START <= address < END or 0x7e1d8 <= address < 0x7e3cc
                or 0x835c0 <= address < 0xa88cc
                or 0x1c18a8 <= address < 0x1c18c8
                or 0x69c60 <= address < 0x69e28
                or 0x1a5718 <= address < 0x1a5784
                or 0x54350 <= address < 0x548c0
                or 0x535f4 <= address < 0x537f4
                or 0x561ec <= address < 0x57ce0
                or 0x1b77b8 <= address < 0x1b92b8
                or 0x1b6828 <= address < 0x1b6ba8
                or 0x1b6c90 <= address < 0x1b76b4):
            op = word(address)
            if op & 0xffe0001f == 0xd4000001:
                raise ValueError("Syscall forbidden")
            return
        name = names.get(address, "")
        calls[name or hex(address)] = calls.get(name or hex(address), 0) + 1
        result = 0
        if name.endswith("FeatureMapper9initValueERKi"):
            u.reg_write(UC_ARM64_REG_PC, 0x7e1d8)
            return
        elif "base11GetProperty" in name:
            key = cpp_string(reg(0))
            if key not in ("apps.setting.product.inswver", "ro.dilink.rse.name", "ro.vehicle.type",
                           "apps.setting.product.outswver", "ro.build.car.region", "ro.dilink.tv.name",
                           "sys.which.rse", "ro.build.multi_display_user", "ro.dilink.board.soc",
                           "ro.byd.ui.integrate"):
                raise ValueError("Unexpected role property " + key)
            value = software_version if key == "apps.setting.product.inswver" else ""
            property_reads.append({"key": key, "value": value})
            encoded_string = value.encode()
            if len(encoded_string) <= 22:
                u.mem_write(reg(8), bytes([len(encoded_string) * 2]) + encoded_string
                            + bytes(23 - len(encoded_string)))
            else:
                capacity = (len(encoded_string) + 16) & ~15
                if capacity > 128 or heap + capacity >= STACK:
                    raise ValueError("Synthetic property string bound")
                u.mem_write(heap, encoded_string + b"\x00")
                u.mem_write(reg(8), struct.pack("<QQQ", capacity | 1, len(encoded_string), heap))
                heap += capacity
        elif "basic_string" in name and "7compareEmmPKcm" in name:
            own = cpp_string(reg(0)).encode()
            if reg(1) > len(own) or reg(4) > 128:
                raise ValueError("String comparison bound")
            own = own[reg(1):reg(1) + min(reg(2), len(own))]
            other = bytes(u.mem_read(reg(3), reg(4)))
            result = (own > other) - (own < other)
        elif name == "memchr":
            if reg(2) > 4096:
                raise ValueError("Search bound")
            offset = bytes(u.mem_read(reg(0), reg(2))).find(bytes([reg(1) & 255]))
            result = 0 if offset < 0 else reg(0) + offset
        elif name == "__cxa_guard_acquire":
            raise ValueError("Expected prepared Cross singleton")
        elif name.endswith("BYDCrossManager12enableDeviceEiPij"):
            count = reg(3)
            if count > 8:
                raise ValueError("Cross enable bound")
            cross_enables.append({"device": reg(1), "fids": [hex(word(reg(2) + 4*i)) for i in range(count)]})
        elif name.endswith("AutoInterface19writeDeviceOriginalEPKhRKjS4_"):
            length = word(reg(2))
            if length > 64:
                raise ValueError("Cross buffer bound")
            cross_writes.append({"length": length, "write_type": word(reg(3)),
                                 "payload_hex": bytes(u.mem_read(reg(1), length)).hex()})
        elif (name == "_ZdlPv" or name == "__cxa_atexit" or name == "pthread_mutex_init"
              or name.endswith("BYDCrossManager16registerObserverENS_2spINS_17IBYDCrossObserverEEE")
              or "RefBase9incStrong" in name or "RefBase9decStrong" in name):
            pass
        elif "FeatureMapper11getIntValue" in name:
            fid = word(reg(1))
            if fid != 0x4c50000b:
                raise ValueError("Unexpected getter in arhudshift: " + hex(fid))
            reads.append(hex(fid))
            result = hud_config
        elif "FeatureMapper11setIntValue" in name:
            writes.append({"fid": hex(word(reg(1))), "value": word(reg(2))})
        elif address in (0x1c1ab0, 0x1c1ac0):
            # One-bucket synthetic std::unordered_set, including its linked list.
            table, key = reg(0), word(reg(1))
            values = filter_sets.setdefault(table, [])
            if key not in values:
                values.append(key)
                if not struct.unpack("<Q", u.mem_read(table, 8))[0]:
                    bucket = heap
                    heap += 16
                    u.mem_write(table, struct.pack("<QQ", bucket, 1))
                    u.mem_write(bucket, struct.pack("<Q", table + 16))
                node = heap
                heap += 32
                first = struct.unpack("<Q", u.mem_read(table + 16, 8))[0]
                u.mem_write(node, struct.pack("<QQI", first, key, key))
                u.mem_write(table + 16, struct.pack("<QQ", node, len(values)))
        elif "setQurParameter" in name or "setReqParameter" in name:
            raw = bytes(u.mem_read(reg(2), 0x38))
            entries.append({"fid": word(reg(1)), "map": "get" if "setQur" in name else "set",
                            "registration_call": hex(u.reg_read(UC_ARM64_REG_LR) - 4),
                            "device": struct.unpack_from("<I", raw, 8)[0],
                            "type": struct.unpack_from("<I", raw, 12)[0],
                            "req_qur_flag": struct.unpack_from("<I", raw, 16)[0],
                            "callbacks": [hex(struct.unpack_from("<Q", raw, offset)[0])
                                          for offset in (24, 32, 40)],
                            "parameter_hex": raw.hex()})
        elif name == "_Znwm":
            n = reg(0)
            if not 0 < n <= 0x10000 or heap + n >= STACK:
                raise ValueError("Allocation bound")
            result = heap
            heap += (n + 15) & ~15
        elif name in ("memcpy", "__memcpy_chk"):
            if reg(2) > 0x10000:
                raise ValueError("Copy bound")
            u.mem_write(reg(0), bytes(u.mem_read(reg(1), reg(2))))
            result = reg(0)
        elif name == "__strlen_chk":
            if reg(1) > 0x10000:
                raise ValueError("String bound")
            result = bytes(u.mem_read(reg(0), reg(1))).index(0)
        elif name == "strlen":
            result = bytes(u.mem_read(reg(0), 256)).index(0)
        elif "vectorIj" in name and "assign" in name:
            if not 0 <= reg(2) - reg(1) <= 0x10000:
                raise ValueError("Vector bound")
            # Only the registration-list copies are omitted; no feature semantics.
        elif "customLogLine" in name:
            pass
        else:
            raise ValueError("Unexpected target " + hex(address) + " " + name)
        u.reg_write(UC_ARM64_REG_X0, result)
        u.reg_write(UC_ARM64_REG_PC, u.reg_read(UC_ARM64_REG_LR))

    u.hook_add(UC_HOOK_CODE, hook)
    def call(address, args):
        u.reg_write(UC_ARM64_REG_SP, STACK + 0xff00)
        u.reg_write(UC_ARM64_REG_LR, STOP)
        for i, p in enumerate(args):
            u.reg_write(UC_ARM64_REG_X0 + i, p)
        u.emu_start(address, STOP, timeout=10_000_000, count=1_000_000)
        if u.reg_read(UC_ARM64_REG_PC) != STOP:
            raise ValueError("Execution bound reached")
    variants = []
    offsets = (0, 4, 8, 12, 16, 36, 40, 44, 0x180, 0x184, 0x18c,
               0x1f8, 0x1fc, 0x410, 0x428, 0x748)
    for canfd, second_flag in ((0, 0), (0, 1), (1, 0), (1, 1)):
        u.mem_write(AUX, bytes(0x10000))
        call(0x835c0, (AUX, canfd, second_flag))
        variants.append({"constructor_flags": [canfd, second_flag],
                         "arhud_fields": {hex(0x994c + n): hex(word(AUX + 0x994c + n))
                                          for n in offsets}})
    call(START, (OBJECT, MAPPER, AUX))
    # The static FunctionTable singleton points to its mapper and AutoID object.
    u.mem_write(0x1ca8a0, struct.pack("<Q", OBJECT))
    u.mem_write(OBJECT + 8, struct.pack("<QQ", MAPPER, AUX))
    shifts = []
    for bypass in (0, 1):
        u.mem_write(0x1ca7a0, bytes([bypass]))
        for hud_config in (0, 2):
            for fid in (0x38b00036, 0x4c500014):
                for value in (1, 2):
                    reads.clear()
                    writes.clear()
                    u.mem_write(OBJECT + 0x800, struct.pack("<IIII", fid, value, 0, 0))
                    call(0x69c60, (OBJECT + 0x800, OBJECT + 0x804, OBJECT + 0x808))
                    shifts.append({"global_1ca7a0": bypass, "hud_config_4c50000b": hud_config,
                                   "input_fid": hex(fid), "input_value": value,
                                   "result_signed": struct.unpack("<i", struct.pack("<I", reg(0) & 0xffffffff))[0],
                                   "output_fid": hex(word(OBJECT + 0x808)),
                                   "output_value": word(OBJECT + 0x80c),
                                   "read_fids": list(reads), "cache_writes": list(writes)})
    encoded = []
    for value in (1, 2):
        u.mem_write(OBJECT + 0x900, bytes(32))
        call(0x1a5718, (0, 0x1b60a010, value, OBJECT + 0x900))
        length = reg(0)
        if length > 32:
            raise ValueError("Codec output bound")
        encoded.append({"fid": "0x1b60a010", "value": value,
                        "length": length, "packet_hex": bytes(u.mem_read(OBJECT + 0x900, length)).hex()})
    # Role flag at 0x1ca4bc selects FSE's local-message set. Its other branch
    # sends CROSS_ID_AUTO_MSG_FSE2IVI; unrelated rear-screen flags remain clear.
    u.mem_write(0x1ca4bc, b"\x01")
    for address in (0x1ca4cc, 0x1ca578, 0x1ca57c):
        u.mem_write(address, b"\x00")
    filter_object = OBJECT + 0x2000
    call(0x54350, (filter_object,))
    routes = []
    for fid in (0x1b60a010, 0x38b00036, 0x12300001):
        payload = struct.pack(">IBI", fid, 4, 2)
        u.mem_write(OBJECT + 0x900, payload)
        u.mem_write(OBJECT + 0x920, bytes(0x100))
        call(0x535f4, (filter_object, OBJECT + 0x900, len(payload),
                       OBJECT + 0x940, OBJECT + 0x920, OBJECT + 0x980, OBJECT + 0x924))
        local_size, remote_size = word(OBJECT + 0x920), word(OBJECT + 0x924)
        if local_size + remote_size != len(payload):
            raise ValueError("Routing did not preserve input length")
        routes.append({"fid": hex(fid), "local_spi_hex": bytes(u.mem_read(OBJECT + 0x940, local_size)).hex(),
                       "remote_cross_hex": bytes(u.mem_read(OBJECT + 0x980, remote_size)).hex()})
    role_cases = []
    # Two independent translation-unit constructors parse the same firmware
    # version property. Prepare the Cross observer without constructing Binder.
    guard = struct.unpack("<Q", u.mem_read(0x1c5638, 8))[0]
    u.mem_write(guard, b"\x01")
    observer = OBJECT + 0x4000
    u.mem_write(observer + 0x18, struct.pack("<Q", OBJECT + 0x5000))
    for software_version in ("FSE", "Di5.1_FSE", "Di5.1_FSE_USER_SIGN_S8152_202607081757_Q0311", "Di5.1", "RSE", ""):
        property_reads.clear()
        cross_enables.clear()
        call(0x561ec, ())
        call(0x1b77b8, ())
        call(0x1b6828, (observer,))
        role_cases.append({"synthetic_software_version": software_version,
                           "read_properties": list(property_reads),
                           "is_fse_write_route": u.mem_read(0x1ca4bc, 1)[0],
                           "cross_subscriptions": list(cross_enables)})
    cross_callback_cases = []
    for fid in (0x2130001c, 0x1230001c, 0xff300039):
        for value in (1, 2):
            payload = struct.pack(">IBI", 0x1b60a010, 4, value)
            u.mem_write(OBJECT + 0x900, payload)
            cross_writes.clear()
            call(0x1b6c90, (observer, 1000, fid, OBJECT + 0x900, len(payload)))
            cross_callback_cases.append({"cross_fid": hex(fid), "hud_value": value,
                                         "write_device_original_calls": list(cross_writes)})
    selected = [dict(e, fid=hex(e["fid"])) for e in entries if e["fid"] in TARGETS]
    # Fail rather than emitting an apparently successful report if a hook,
    # register layout or container model no longer reproduces the reviewed code.
    checks = []
    def require(condition, description):
        if not condition:
            raise ValueError("Verification failed: " + description)
        checks.append(description)
    require(len(entries) == 11604, "CAN-FD initializer registered 11604 map entries")
    require(len(selected) == 10 and all(
        sum(e["fid"] == hex(fid) and e["map"] == kind for e in selected) == 1
        for fid in TARGETS for kind in ("get", "set")),
        "Every target has exactly one request and query registration")
    require(all(e["device"] == 1023 and e["req_qur_flag"] == 3
                and e["type"] == (2 if e["fid"] == "0x1b60a010" else 3)
                and e["callbacks"] == ["0xd82b8" if e["fid"] == "0x1b60a010" else "0x69c60", "0x0", "0x0"]
                for e in selected), "Target types, device and callbacks match disassembly")
    for case in shifts:
        bypass, config = case["global_1ca7a0"], case["hud_config_4c50000b"]
        accepted = bool(bypass or (case["input_fid"] == "0x38b00036") == (config == 0))
        expected_fid = case["input_fid"] if bypass else "0x38b00036"
        if (case["result_signed"] != (0 if accepted else -10014)
                or case["read_fids"] != ["0x4c50000b"] or case["cache_writes"]
                or accepted and (case["output_fid"] != expected_fid
                                 or case["output_value"] != case["input_value"])):
            raise ValueError("arhudshift verification failed: " + repr(case))
    checks.append("16 availability conversion cases preserve values or reject the other HUD configuration")
    require(all(e["length"] == 9 and e["packet_hex"] == "1b60a010040000000" + str(e["value"])
                for e in encoded), "Start and stop encode as reviewed nine-byte messages")
    require(all(r["local_spi_hex"] == (struct.pack(">IBI", int(r["fid"], 16), 4, 2).hex()
                                       if r["fid"] != "0x12300001" else "")
                and r["remote_cross_hex"] == ("123000010400000002" if r["fid"] == "0x12300001" else "")
                for r in routes), "FSE routes HUD prefixes to local SPI and the control prefix to Cross")
    require(all(case["is_fse_write_route"] == int("FSE" in case["synthetic_software_version"])
                and case["cross_subscriptions"] == ([{"device": 1000, "fids": ["0x2130001c", "0xff300039"]}]
                    if "Di5.1" in case["synthetic_software_version"] else [])
                for case in role_cases),
            "Six firmware-version cases show independent FSE routing and Di5 Cross subscription flags")
    require(all(case["write_device_original_calls"] == ([] if case["cross_fid"] == "0x1230001c" else
                [{"length": 9, "write_type": 1, "payload_hex": "1b60a010040000000" + str(case["hud_value"])}])
                for case in cross_callback_cases),
            "Cross observer forwards original start/stop payload and ignores the control FID")
    return {"firmware_sha256": EXPECTED, "offline_only": True, "verification": checks,
            "initializer": hex(START), "instructions": instructions,
            "hook_calls": calls, "registration_count": len(entries),
            "autoid_variants": variants,
            "arhudshift_cases": shifts, "encode_req_int": encoded,
            "fse_local_message_prefixes": [hex(x) for x in filter_sets[filter_object]],
            "fse_routing_cases": routes,
            "role_cases": role_cases,
            "cross_callback_cases": cross_callback_cases,
            "targets": selected}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("hal", type=Path)
    args = parser.parse_args()
    print(json.dumps(inspect(args.hal), indent=2))
