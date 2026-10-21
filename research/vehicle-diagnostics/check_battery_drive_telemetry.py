#!/usr/bin/env python3
"""Verify frozen offline battery/drive follow-up; no target or firmware execution.

Uses only stdlib for source/ELF/record/arithmetic checks. The optional pure
AutoID expression evaluator is retained separately and is never run by default.
"""
from pathlib import Path
import gzip
import hashlib
import json
import struct

ROOT = Path(__file__).resolve().parents[2]
REPORT = Path(__file__).with_name("battery-drive-telemetry-20261021.json")


def require(value, detail):
    if not value:
        raise ValueError(detail)


def f32(value):
    return struct.unpack("<f", struct.pack("<f", value))[0]


def elf_offset(data, va, size):
    require(data[:6] == b"\x7fELF\x02\x01", "ELF64 LE")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phsize, phcount = struct.unpack_from("<HH", data, 54)
    for i in range(phcount):
        typ, _, off, addr, _, filesz, _, _ = struct.unpack_from(
            "<IIQQQQQQ", data, phoff + i * phsize)
        if typ == 1 and addr <= va and va + size <= addr + filesz:
            return off + va - addr
    raise ValueError(("unmapped ELF VA", hex(va), size))


def torque(raw):
    if raw & 0xffffffff > 10000:
        return -10014, None
    return 0, f32(f32(f32(raw) * f32(0.1)) - 500.0)


def speed(mode, source, raw):
    """CANFD AutoID-resolved input/output FIDs; unsigned source domain."""
    value = raw & 0xffffffff
    if mode == 2:
        if source not in (0x46d00408, 0x46d00418) or value >= 65534:
            return -10014, None, None
        return 0, 0x44100008 if source == 0x46d00408 else 0x25100008, value - 32000
    if source == 0x44100008 and value <= 30000:
        return 0, source, value - 15000
    if source == 0x25100008 and value <= 60000:
        return 0, source, value - 30000
    return -10014, None, None


def main():
    report = json.loads(REPORT.read_text())
    require(report["offlineOnly"] and report["vehicleOperations"] == 0
            and report["networkOperations"] == 0, "offline boundary")
    require(report["newEngineeringUnitsProved"] == 0, "units remain unqualified")
    blobs = {}
    for source in report["sources"]:
        data = (ROOT / source["path"]).read_bytes()
        require(len(data) == source["bytes"], ("source size", source["path"]))
        require(hashlib.sha256(data).hexdigest() == source["sha256"],
                ("source hash", source["path"]))
        blobs[source["path"]] = data
    evidence = json.loads(blobs[report["nativeEvidence"]])
    instruction_count = 0
    for record in evidence:
        data = blobs[record["path"]]
        va, end = int(record["elfVA"], 16), int(record["endExclusive"], 16)
        offset = elf_offset(data, va, end - va)
        require(offset == int(record["fileOffset"], 16), ("ELF mapping", record["label"]))
        require(data[offset:offset + end - va].hex() == record["bytes"],
                ("range bytes", record["label"]))
        require(hashlib.sha256(data).hexdigest() == record["sourceSHA256"], "range source")
        require(sum(len(bytes.fromhex(i["bytes"])) for i in record["instructions"]) == end - va,
                ("range coverage", record["label"]))
        for instruction in record["instructions"]:
            off = elf_offset(data, int(instruction["va"], 16), 4)
            require(data[off:off + 4].hex() == instruction["bytes"], "instruction bytes")
            instruction_count += 1
    instructions = {i["va"]: i for r in evidence for i in r["instructions"]}
    require(instructions["0x82748"]["operands"] == "w8, #-0x271b"
            and instructions["0x82780"]["operands"] == "w0, #-0x271d",
            "integer getter missing -10011 versus wrong type -10013")
    lines = blobs[report["snapshotSource"]].decode().splitlines()
    for record in report["retainedObservations"]:
        row = lines[record["line"] - 1]
        require(row == record["text"], ("saved line", record["line"]))
        columns = row.split("\t")
        require(columns[0] == "FID" and int(columns[3]) == record["device"]
                and int(columns[4], 16) == int(record["fid"], 16), "saved field identity")
    constructor = json.loads(blobs[report["autoIDConstants"]])
    expected = [
        ["0x46D00428", "0x28A00120", "0x44100008", "0x46D00408", "0x25100008", "0x46D00418"],
        ["0x000FC0B6", "0x0004742B", "0x44100808", "0x0007A80C", "0x25100808", "0x000030F5"],
        ["0x000FC0B6", "0x0004742B", "0x44100008", "0x0007A80C", "0x25100008", "0x000030F5"],
    ]
    for variant, values in zip(constructor["variants"], expected):
        require(list(variant["fields"].values()) == values, "AutoID frozen constants")
    hal = blobs[next(s for s in blobs if s.endswith("/hw/auto.default.so"))]
    require({r["resolvedTargetELFVA"] for r in report["bindingRelocations"]}
            == {"0x7bcb0", "0x5f640"}, "registered converter targets")
    for relocation in report["bindingRelocations"]:
        off = int(relocation["packedRelocationSectionFileOffset"], 16)
        size = relocation["packedRelocationSectionBytes"]
        require(hashlib.sha256(hal[off:off + size]).hexdigest()
                == relocation["packedRelocationSectionSHA256"], "packed relocations")
        off = int(relocation["symbolEntryFileOffset"], 16)
        require(hal[off:off + 24].hex() == relocation["symbolEntryBytes"], "symbol entry")
        require(struct.unpack_from("<Q", hal, off + 8)[0] + relocation["addend"]
                == int(relocation["resolvedTargetELFVA"], 16), "symbol-resolved target")
    accepted_torque = 0
    for raw in range(65536):
        status, api = torque(raw)
        require((status == 0) == (raw <= 10000), "front torque gate")
        if status == 0:
            accepted_torque += 1
            require(-500 <= api <= 500, "front torque numeric range")
            encoded = int(f32(f32(api * 10.0) + 20000.0)) & 65535
            require(encoded == raw + 15000, "torque GB packing")
        else:
            require(api is None and status == -10014, "front torque rejection")
    require(accepted_torque == 10001, "front torque domain size")
    require(torque(4990) == (0, -1.0) and torque(5000) == (0, 0.0), "torque collision")
    for raw in (-1, -2147483648, 0xffffffff):
        require(torque(raw) == (-10014, None), "torque unsigned comparison")
    speed_counts = {}
    for mode, source, limit, shift in [
        (0, 0x44100008, 30000, 15000), (0, 0x25100008, 60000, 30000),
        (2, 0x46d00408, 65533, 32000), (2, 0x46d00418, 65533, 32000),
    ]:
        count = 0
        for raw in range(65536):
            status, destination, value = speed(mode, source, raw)
            require((status == 0) == (raw <= limit), "speed acceptance domain")
            if status == 0:
                count += 1
                require(value == raw - shift, "speed arithmetic")
                require(destination == (0x44100008 if source in (0x44100008, 0x46d00408)
                                        else 0x25100008), "speed output alias")
            else:
                require(status == -10014 and destination is None and value is None,
                        "speed rejection")
        speed_counts[f"{mode}:{source:08X}"] = count
    # The mode2 branch ignores old FIDs, and every other mode follows the old branch.
    for mode in (0, 1, 3, 65535, -1):
        require(speed(mode, 0x44100008, 15000) == (0, 0x44100008, 0), "old mode selector")
    require(speed(2, 0x44100008, 15000) == (-10014, None, None), "new mode old input")
    require(speed(0, 0x46d00408, 32000) == (-10014, None, None), "old mode new input")
    for raw in (-1, -2147483648):
        require(speed(2, 0x46d00408, raw) == (-10014, None, None), "speed unsigned compare")
    # Models reproduce only the traced failure branch, not an executed ECU sequence.
    previous_torque = 0.0
    status, new = torque(65535)
    require((previous_torque if status else new) == 0.0, "rejected torque preserves cache")
    previous_speed = 0
    status, _, new = speed(0, 0x44100008, 65535)
    require((previous_speed if status else new) == 0, "rejected speed preserves cache")
    # REA failure is evidence of an unavailable analysis, never successful pseudocode.
    rea = json.loads(gzip.decompress(blobs[report["REA"]["loadImageReply"]]))
    require(rea["result"]["isError"], "REA load-image failure retained")
    error = json.loads(rea["result"]["content"][0]["text"])["error"]
    require(error["code"] == "resource_constraint" and "OutOfMemoryError" in error["message"],
            "REA resource diagnostic")
    batch = json.loads(gzip.decompress(blobs[report["REA"]["batchReply"]]))
    batch = json.loads(batch["result"]["content"][0]["text"])["normalized_result"]
    require(batch["total"] == 9 and batch["succeeded"] == 0 and batch["failed"] == 9,
            "REA batch semantic failures")
    require(all(i["procedure"]["status"] == "unknown" for i in batch["items"]),
            "REA unknown identities")
    receipt = json.loads(gzip.decompress(blobs[report["REA"]["receipt"]]))
    require(receipt["serverExitCode"] == 0 and receipt["scratchEntries"] == [], "REA cleanup")
    require(receipt["sha256Before"] == receipt["sha256After"]
            == hashlib.sha256(hal).hexdigest(), "REA immutable source")
    print(json.dumps({"valid": True, "pinnedSources": len(blobs),
                      "nativeRanges": len(evidence), "instructionAnchors": instruction_count,
                      "retainedObservations": len(report["retainedObservations"]),
                      "frontTorqueU16Cases": 65536, "frontTorqueAccepted": accepted_torque,
                      "speedU16Cases": 4 * 65536, "speedAcceptedByModeSource": speed_counts,
                      "newEngineeringUnitsProved": 0, "vehicleOperations": 0,
                      "networkOperations": 0, "READecompilationClaimed": False}, indent=2))


if __name__ == "__main__":
    main()
