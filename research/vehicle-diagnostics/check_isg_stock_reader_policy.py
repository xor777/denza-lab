#!/usr/bin/env python3
"""Recheck pinned stock 190A parser bytes and replay the retained ISG payload.

Offline only. The copy model is an assembly-derived model, not execution of the
firmware and not evidence that this IP client handled the captured CAN reply.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import struct

ROOT = Path(__file__).resolve().parents[2]
DEFAULT = ROOT / "research/vehicle-diagnostics/isg-stock-reader-policy-20261021.json"
ELF_SHA = "86416b6e127dffac1102ef8dde16cd297a96620d78c6dd4b5fc4d87e1171d161"
EXTENTS = {0x9FC5C: 0x260, 0xAFFCC: 0xB9C, 0xC565C: 0x2D0,
           0xC3958: 0x24, 0xC2A9C: 0x14C, 0xBD998: 0xAC, 0xC317C: 0x108}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def va_bytes(elf, address, size):
    require(elf[:6] == b"\x7fELF\x02\x01", "ELF64 little-endian")
    offset = struct.unpack_from("<Q", elf, 0x28)[0]
    step, count = struct.unpack_from("<HH", elf, 0x3A)
    for index in range(count):
        section = struct.unpack_from("<IIQQQQIIQQ", elf, offset + index * step)
        _, kind, _, start, file_offset, length, *_ = section
        if kind != 8 and start <= address and address + size <= start + length:
            return elf[file_offset + address - start:file_offset + address - start + size]
    raise AssertionError(f"unmapped ELF extent {address:#x}+{size:#x}")


def branch_target(elf, address):
    word, = struct.unpack("<I", va_bytes(elf, address, 4))
    require(word >> 26 == 0b100101, f"BL instruction {address:#x}")
    displacement = word & 0x3FFFFFF
    if displacement & 0x2000000:
        displacement -= 0x4000000
    return address + 4 * displacement


def verify(data):
    require(data["schema"] == "denza-lab.isg-stock-reader-policy.v1", "schema")
    require(data["vehicleOperations"] == data["firmwareExecutions"] == 0, "offline scope")
    for source in data["sources"]:
        raw = (ROOT / source["path"]).read_bytes()
        require(len(raw) == source["bytes"] and hashlib.sha256(raw).hexdigest() == source["sha256"], "source " + source["path"])
    elf = (ROOT / data["nativeLibrary"]["path"]).read_bytes()
    require(hashlib.sha256(elf).hexdigest() == data["nativeLibrary"]["sha256"] == ELF_SHA, "exact library")
    require({int(row["address"], 16): row["bytes"] for row in data["nativeFunctions"]} == EXTENTS, "bounded function extents")
    for row in data["nativeFunctions"]:
        raw = va_bytes(elf, int(row["address"], 16), row["bytes"])
        require(hashlib.sha256(raw).hexdigest() == row["sha256"], "function " + row["address"])
    for row in data["instructionAnchors"]:
        raw = bytes.fromhex(row["hex"])
        require(va_bytes(elf, int(row["address"], 16), len(raw)) == raw, "instruction " + row["address"])
    require(struct.unpack("<6Q", va_bytes(elf, 0x100C98, 48)) ==
            (0xC5548, 0xC55B8, 0xC5624, 0xBEE3C, 0xBEEA0, 0xC565C), "positive task vtable")
    require(struct.unpack("<6Q", va_bytes(elf, 0x100D70, 48)) ==
            (0xC5AD0, 0xC5AF8, 0xC5B3C, 0xC3904, 0xC3920, 0xC3958), "fallback task vtable")
    for address, target in ((0xC56F4, 0xF8AF0), (0xC5728, 0xF8BB0),
                            (0xC581C, 0xF9030), (0xC2B48, 0xF7970),
                            (0xC3220, 0xF7970), (0xBD9F8, 0xF7970)):
        require(branch_target(elf, address) == target, "native call edge")

    lines = (ROOT / data["capturePath"]).read_text().splitlines()
    def event(tag, seq):
        matches = [line for line in lines if line.startswith(tag + " ") and
                   re.search(r"\bseq=" + str(seq) + r"\b", line)]
        require(len(matches) == 1, "single event " + tag)
        return dict(field.split("=", 1) for field in matches[0].split()[1:])
    raw = bytes.fromhex(event("REJECTED_FRAME", 6)["raw"])
    require(raw[:7] == bytes.fromhex("0A07EEE7010101") and len(raw) == 240, "successful gateway envelope")
    payload = raw[7:7 + raw[3]]
    require(raw[238:] == b"\0\0", "outside-declared tail")
    require(payload == bytes.fromhex(event("RESPONSE", 6)["uds"]), "same declared response")
    require(len(payload) == 231 and payload[:3] == bytes.fromhex("590A09"), "full supported response")
    require(event("RESPONSE", 4)["uds"] == "62F19448370011E0EA000000", "software identity")
    require(event("RESPONSE", 5)["uds"] == "590109010000", "format01 and mask09 count0")
    records = [payload[i:i + 4] for i in range(3, len(payload), 4)]
    require(len(records) == 57 and all(row[3] == 0 for row in records), "57 zero-status records")
    counts = Counter(row[:3] for row in records)
    require(len(counts) == 56 and {key.hex().upper(): n for key, n in counts.items() if n > 1} == {"C1D487": 2}, "duplicate code")
    positions = [i for i, row in enumerate(records, 1) if row[:3] == bytes.fromhex("C1D487")]
    require(positions == data["retainedReply"]["duplicateOrdinals"] == [28, 40], "duplicate positions")
    require(data["retainedReply"]["payloadSha256"] == hashlib.sha256(payload).hexdigest(), "payload digest")
    require(data["retainedReply"]["orderedRecordCount"] == 57 and data["retainedReply"]["uniqueRawCodeCount"] == 56, "reported counts")

    # C5718/5728 assigns one zero word, then C57FC/C581C appends each input
    # record. This reproduces only that construction for a complete payload.
    model = [b"\0" * 4]
    model.extend(records)
    projection = data["assemblyDerivedModel"]
    require(projection["inputRecordHex"] == [row.hex().upper() for row in records], "ordered source records")
    require(projection["outputRecordHex"] == [row.hex().upper() for row in model], "native construction model")
    require(projection["nativeVectorSize"] == 58 and projection["inputRecordCountFormula"] == "(payload_bytes - 2) >> 2", "native count formula")
    require((len(payload) - 2) >> 2 == len(records), "native loop count for this input")
    require(projection["executedFirmware"] is False and projection["observedLiveNativeOutput"] is False, "model boundary")
    require(not data["interpretation"]["actualCaptureUsedThisNativeParser"] and
            not data["interpretation"]["duplicateCauseEstablished"] and
            not data["interpretation"]["standardsConformanceEstablished"], "no transport or normative promotion")
    require(data["qualifiedFactoryDefinitions"] == data["qualifiedFormat01Mappings"] == [], "no meaning or format promotion")
    require(data["originalReaderOutcome"]["remainsFailed"] and
            event("REJECTED_FRAME", 6)["reason"] == "DUPLICATE_DTC_CODE", "historical failure preserved")
    require(data["originalReaderOutcome"]["endExact"] in lines, "historical END")
    print("PASS native_functions=7 declared_uds=231 ordered_records=57 unique_raw=56 model_vector=58 duplicate_cause=unknown qualified_definitions=0")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, default=DEFAULT)
    args = parser.parse_args()
    verify(json.loads(args.report.read_text()))
