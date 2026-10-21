#!/usr/bin/env python3
"""Replay hashes, exact bytes and bounded host arithmetic; no target execution."""
from pathlib import Path
import hashlib
import json
import struct

ROOT = Path(__file__).resolve().parents[2]
REPORT = Path(__file__).with_name("telemetry-qualification-followup-20261021.json")


def require(value, detail):
    if not value:
        raise ValueError(detail)


def f32(value):
    return struct.unpack("<f", struct.pack("<f", value))[0]


def offset(data, va, size):
    require(data[:6] == b"\x7fELF\x02\x01", "ELF64 little endian")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    entsize, count = struct.unpack_from("<HH", data, 54)
    for i in range(count):
        typ, _, off, addr, _, filesz, _, _ = struct.unpack_from("<IIQQQQQQ", data, phoff + i * entsize)
        if typ == 1 and addr <= va and va + size <= addr + filesz:
            return off + va - addr
    raise ValueError(("unmapped ELF address", hex(va), size))


def torque(raw):
    return f32(f32(raw) * f32(0.1))


def pack(value, shift):
    return int(f32(f32(value * 10.0) + shift)) & 65535


def main():
    report = json.loads(REPORT.read_text())
    require(report["offlineOnly"] and report["vehicleOperations"] == report["ADBOperations"]
            == report["networkOperations"] == report["firmwareExecutions"] == 0, "offline boundary")
    require(report["newEngineeringUnitsProved"] == 0 and not report["productChanges"], "qualification boundary")
    blobs = {}
    for item in report["sources"]:
        data = (ROOT / item["path"]).read_bytes()
        require(len(data) == item["bytes"] and hashlib.sha256(data).hexdigest() == item["sha256"], ("source hash", item["path"]))
        blobs[item["path"]] = data
    native = json.loads(blobs[report["nativeEvidence"]])
    instruction_count = 0
    by_label = {}
    for item in native:
        data = blobs[item["path"]]
        start, end = int(item["elfVA"], 16), int(item["endExclusive"], 16)
        pos = offset(data, start, end - start)
        require(pos == int(item["fileOffset"], 16), ("ELF mapping", item["label"]))
        require(data[pos:pos + end - start].hex() == item["bytes"], ("range bytes", item["label"]))
        require(hashlib.sha256(data).hexdigest() == item["sourceSHA256"], "range source")
        require(len(item["instructions"]) * 4 == end - start, "complete instruction range")
        by_label[item["label"]] = {int(i["va"], 16): i for i in item["instructions"]}
        for ins in item["instructions"]:
            pos = offset(data, int(ins["va"], 16), 4)
            require(data[pos:pos + 4].hex() == ins["bytes"], "instruction bytes")
            instruction_count += 1
    rear = by_label["rear_torque_converter"]
    require(rear[0xe0ed0]["mnemonic"] == "scvtf" and rear[0xe0ed8]["mnemonic"] == "fmul"
            and rear[0xe0edc]["operands"] == "s0, [x2]", "rear torque arithmetic and output")
    require(not any(i["mnemonic"].startswith(("b.", "cb", "tb")) for i in rear.values()), "rear torque no reject branch")
    integer = by_label["client_getInt_complete"]
    floating = by_label["client_getDouble_complete"]
    require(integer[0x1d410]["operands"] == "x0, #0x1d4c4" and integer[0x1d4c4]["operands"] == "w19, #0x3e8"
            and integer[0x1d4c8]["operands"] == "w19, #0x8000, lsl #16", "integer null-service return")
    require(floating[0x1d580]["operands"] == "x0, #0x1d634" and floating[0x1d634]["operands"] == "w19, #0x3e8"
            and floating[0x1d638]["operands"] == "w19, #0x8000, lsl #16", "float null-service return")
    proxy_i, proxy_f = by_label["proxy_getInt_complete"], by_label["proxy_getDouble_complete"]
    for instructions, transport, remote, store, dest in [
            (proxy_i, 0x16afc, 0x16b0c, 0x16b18, "w0, [x19]"),
            (proxy_f, 0x16c2c, 0x16c3c, 0x16c48, "s0, [x19]")]:
        require(instructions[transport]["mnemonic"] == instructions[remote]["mnemonic"] == "cbnz", "proxy success gates")
        require(instructions[store]["mnemonic"] == "str" and instructions[store]["operands"] == dest, "proxy output store")
        require(sum(i["operands"].endswith("[x19]") and i["mnemonic"].startswith("str") for i in instructions.values()) == 1,
                "sole caller-output store")
    table = json.loads(blobs[report["proxyVtableEvidence"]])
    client = blobs[table["source"]]
    pos = offset(client, int(table["vtableVA"], 16), len(bytes.fromhex(table["bytes"])))
    require(pos == int(table["fileOffset"], 16) and client[pos:pos + 112].hex() == table["bytes"], "proxy vtable bytes")
    for name in ("getInt", "getDouble"):
        entry = table[name]
        require(int(table["addressPointVA"], 16) + int(entry["slot"], 16) == int(entry["locationVA"], 16), "virtual slot address")
        pos = offset(client, int(entry["locationVA"], 16), 8)
        require(struct.unpack_from("<Q", client, pos)[0] == int(entry["targetELFVA"], 16), "virtual slot target")
    relr_bytes = bytes.fromhex(table["relrBytes"])
    pos = int(table["relrFileOffset"], 16)
    require(client[pos:pos + len(relr_bytes)] == relr_bytes, "RELR source bytes")
    relr_addresses, cursor = set(), 0
    for (entry,) in struct.iter_unpack("<Q", relr_bytes):
        if entry & 1:
            relr_addresses.update(cursor + 8 * bit for bit in range(63) if entry & (1 << (bit + 1)))
            cursor += 63 * 8
        else:
            relr_addresses.add(entry)
            cursor = entry + 8
    require({0x28d20, 0x28d28} <= relr_addresses, "both virtual slots have load-base RELR")
    mcu = blobs[next(p for p in blobs if p.endswith("/mcu/app.bin"))]
    fields = json.loads(blobs[report["MCUEvidence"]])
    for item in fields:
        for prefix, size in (("table", 16), ("descriptor", 12), ("property", 12)):
            off = int(item[prefix + "FileOffset"], 16)
            require(mcu[off:off + size].hex() == item[prefix + "Bytes"], ("MCU bytes", item["FID"], prefix))
        require(struct.unpack_from("<II", bytes.fromhex(item["descriptorBytes"]), 4)
                == (0x18a09c, int(item["propertyVA"], 16)), "descriptor handler/property")
        require(struct.unpack_from("<II", bytes.fromhex(item["propertyBytes"])) == (int(item["FID"], 16), 2), "property FID/width")
        require(item["unsignedBits"] == 16 and not item["installedMCUHashMatched"], "producer scope")
        # Every possible source word survives the nibble-aligned generic unsigned extractor.
        a, bit, z, end_bit = item["coordinatesOneBasedByteLSB0"]
        width = 8 * (z - a) + end_bit - bit + 1
        for raw in range(65536):
            payload = (raw << ((a - 1) * 8 + bit)).to_bytes(z, "little")
            recovered = (int.from_bytes(payload, "little") >> ((a - 1) * 8 + bit)) & ((1 << width) - 1)
            require(recovered == raw, "unsigned producer extraction projection")
    for raw in range(65536):
        value = torque(raw)
        require(0 <= value <= 6553.5, "rear torque API numeric range")
        require(pack(value, 20000.0) == (raw + 20000) & 65535, "all rear torque GB words")
        require(((raw & 65535) + 20000) & 65535 == (raw + 20000) & 65535, "all rear speed GB words")
    require(pack(torque(45535), 20000.0) == 65535 and pack(torque(45536), 20000.0) == 0, "wrap boundary")
    require(torque(65535) == 6553.5 and pack(torque(65535), 20000.0) == 19999, "all-ones propagation")
    calls = by_label["three_drive_shared_output_lifetime"]
    for call in (0x141e8, 0x14228, 0x14258, 0x14300, 0x1433c, 0x1436c):
        require(calls[call]["operands"] == "#0x38730" and calls[call + 4]["operands"] == "s0, [sp, #4]", "unconditional shared float output load")
    require(calls[0x143d8]["operands"] == "w2, #8" and calls[0x143dc]["operands"] == "x3, sp, #0x10"
            and calls[0x143f0]["operands"] == "w8, [sp, #0x10]", "same engine state/speed buffer")
    # The error contract leaves caller output untouched. These are host conditional schedules, not firmware runs.
    float_output = 12.5  # An earlier successful torque read.
    failed_status = -2147482648
    require(failed_status != 0 and pack(float_output, 20000.0) == 20125
            and pack(float_output, 10000.0) == 10125, "side/cross-field stale output projection")
    int_output = 3  # Earlier successful state read; subsequent speed error performs no write.
    require(int_output & 65535 == 3, "engine state reused as speed projection")
    lines = blobs["captures/diagnostics-sweep-20261020/fids.tsv"].decode().splitlines()
    observations = json.loads(blobs[report["retainedObservations"]])
    for item in observations:
        require(lines[item["line"] - 1] == item["text"], "retained observation exact line")
    for item in report["REA"]["successfulClientFunctions"]:
        reply = json.loads(blobs[item["result"]])
        selection = reply["raw_result"]["selection"]
        require(selection["file_offset"] == int(item["fileOffset"], 16)
                and int(selection["mapped_address"], 16) == int(item["mappedProcedure"], 16), "REA file-offset mapping")
        require(reply["normalized_result"] and "BYDAuto" in reply["normalized_result"], "REA known procedure output")
    failure = json.loads(blobs[report["REA"]["HALAttempt"]["reply"]])
    require(failure["code"] == "provider_timeout" and failure["details"]["timeout_ms"] == 180000, "retained HAL timeout")
    runtime_root = Path(failure["details"]["diagnostics"]["runtime_root"])
    require(not runtime_root.exists(), "failed HAL runtime root cleaned")
    client_root = Path("/Users/dmitry/Library/Caches/denza-rea-telemetry-followup-client")
    require(not client_root.exists() or not list(client_root.iterdir()), "successful client runtime roots cleaned")
    print(json.dumps({"status": "PASS", "sources": len(blobs), "nativeRanges": len(native), "instructions": instruction_count,
                      "MCUfields": len(fields), "producerWordsProjected": 65536 * len(fields),
                      "rearTorqueWordsProjected": 65536, "rearSpeedWordsProjected": 65536,
                      "retainedGETrows": len(observations), "successfulREAFunctions": len(report["REA"]["successfulClientFunctions"]),
                      "newEngineeringUnits": 0, "vehicleOperations": 0}))


if __name__ == "__main__":
    main()
