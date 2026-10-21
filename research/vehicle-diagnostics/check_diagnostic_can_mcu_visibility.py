#!/usr/bin/env python3
"""Verify pinned offline MCU visibility evidence; never execute firmware or use I/O to a car."""
import hashlib
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[2]
REPORT = Path(__file__).with_name("diagnostic-can-mcu-visibility-20261021.json")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def target(operand):
    return int(operand.split(",", 1)[0], 0)


def walk_unconditional(rows, start, stop):
    """Follow reviewed straight-line blocks, ignoring called functions' internals."""
    pc, calls, visited = start, [], []
    for _ in range(80):
        if pc == stop:
            return calls, visited
        row = rows[pc]
        visited.append(pc)
        op, operand = row["mnemonic"], row["operands"]
        if op == "jarl":
            calls.append(target(operand))
        if op in ("br", "jr"):
            pc = target(operand)
        else:
            require(not op.startswith("b"), "conditional branch needs explicit interpretation")
            pc += len(bytes.fromhex(row["bytes"]))
    raise AssertionError("control-flow walk exceeded bound")


def check():
    d = json.loads(REPORT.read_text())
    require(d["offlineOnly"] and d["vehicleOperations"] == 0 and not d["productEdits"], "offline scope")
    for source in d["sources"]:
        data = (ROOT / source["path"]).read_bytes()
        require(len(data) == source["bytes"] and
                hashlib.sha256(data).hexdigest() == source["sha256"], "source drift: " + source["path"])
    image = (ROOT / d["image"]["path"]).read_bytes()
    require(hashlib.sha256(image).hexdigest() == d["image"]["sha256"] and
            len(image) == d["image"]["bytes"] == 1933312, "MCU image identity")
    instructions = {}
    for region in d["ranges"]:
        start, end = int(region["startVA"], 0), int(region["endExclusiveVA"], 0)
        require(hashlib.sha256(image[start - 0x40000:end - 0x40000]).hexdigest() == region["sha256"],
                "native range " + region["name"])
        rows = json.loads((ROOT / region["decodedInstructions"]).read_text())
        require(len(rows) == region["instructions"], "decoded row count")
        cursor = start
        for row in rows:
            address, raw = int(row["va"], 0), bytes.fromhex(row["bytes"])
            require(address == cursor and image[address - 0x40000:address - 0x40000 + len(raw)] == raw,
                    "decoded instruction byte/continuity check")
            instructions[address] = row
            cursor += len(raw)
        require(cursor == end, "bounded native region end")

    anchors = {
        0x17082A: ("jarl", "0x1704ac, lp"),
        0x170842: ("jarl", "0x1528c8, lp"),
        0x170530: ("jarl", "0x170b3c, lp"),
        0x170B74: ("andi", "0x7ff, r2, r2"),
        0x170B84: ("jarl", "0x169892, lp"),
        0x170B94: ("jarl", "0x170a12, lp"),
        0x170B9A: ("ld.bu", "0x5[r29], r1"),
        0x170BA4: ("jarl", "0x169892, lp"),
        0x170BB6: ("jarl", "0x170a12, lp"),
        0x170A5C: ("jarl", "0x1708dc, lp"),
        0x170A64: ("jarl", "0x170926, lp"),
        0x170A76: ("jarl", "0x1b0b3e, lp"),
        0x170A9A: ("bc", "0x170ab8"),
        0x1709BE: ("ld.bu", "0x4[r28], r8"),
        0x1709C6: ("addi", "0x5, r28, r7"),
        0x1709CA: ("jarl", "0x1b3ed4, lp"),
        0x1709D2: ("mov", "0x9900001d, r1"),
        0x1709E8: ("jarl", "0x15cc32, lp"),
        0x152CD8: ("jarl", "0x151948, lp"),
        0x152CDC: ("jr", "0x152fdc"),
        0x152DB8: ("jarl", "0x169a6e, lp"),
        0x152DC0: ("jarl", "0x1515a0, lp"),
        0x152DEC: ("jarl", "0x1515a0, lp"),
        0x152E66: ("bne", "0x152ef2"),
        0x152EF2: ("br", "0x152fdc"),
        0x152F0E: ("jarl", "0x1515a0, lp"),
        0x1515B6: ("ld.bu", "0x310d8a[gp], r1"),
        0x151614: ("add", "0xd, r1"),
        0x151616: ("addi", "-0x56f, r1, r0"),
        0x153162: ("cmp", "0x3, r1"),
        0x153172: ("mov", "0xfebc8ed4, r8"),
        0x15318A: ("mov", "0xfebc9286, r8"),
        0x170514: ("ld.bu", "0x3114e9[gp], r1"),
        0x17051A: ("shr", "0x4, r1"),
        0x17051C: ("bnc", "0x170524"),
        0x170520: ("jarl", "0x157c3c, lp"),
        0x157D68: ("ld.bu", "0x4[r29], r7"),
        0x157D6C: ("addi", "0x5, r29, r6"),
        0x157D70: ("jarl", "0x157966, lp"),
        0x157936: ("st.b", "r28, 0x3114e9[gp]"),
        0x158108: ("jarl", "0x1a64b6, lp"),
        0x1A64CE: ("jarl", "0x1a63f6, lp"),
        0x1A64D6: ("jarl", "0x1a6456, lp"),
        0x1A640E: ("mov", "0x0, r6"),
        0x1A6410: ("jarl", "0x1abe74, lp"),
        0x1A646E: ("mov", "0x2, r6"),
        0x1A6470: ("jarl", "0x1abe74, lp"),
    }
    for address, expected in anchors.items():
        row = instructions[address]
        require((row["mnemonic"], row["operands"]) == expected, f"reviewed anchor {address:#x}")
    require([int(row["callVA"], 0) for row in d["receiveOrder"]["order"]] ==
            [0x17082A, 0x170830, 0x170836, 0x17083C, 0x170842, 0x170848], "receive call order")
    local_calls, _ = walk_unconditional(instructions, 0x152CB6, 0x152FDC)
    require(local_calls == [0x151948], "local FF rejection omits recorder")
    bad_cf_calls, _ = walk_unconditional(instructions, 0x152EF2, 0x152FDC)
    require(bad_cf_calls == [], "mismatched CF bypasses recorder")
    accepted_calls, _ = walk_unconditional(instructions, 0x152DB4, 0x152FDC)
    require(accepted_calls == [0x169A6E, 0x1515A0, 0x1515A0],
            "accepted FF records FC before received FF")

    # Recompute encoding candidates without any disassembler dependency. These
    # remain candidates: indirect calls and data/code classification are separate.
    retained = json.loads((ROOT / d["captureBase"] / "direct-call-candidates.json").read_text())
    found = {key: [] for key in retained}
    for offset in range(0, len(image) - 4, 2):
        word0, word1 = struct.unpack_from("<HH", image, offset)
        if word0 & 0xFFC0 != 0xFF80 or word1 & 1:
            continue
        displacement = ((word0 & 0x3F) << 16) | word1
        if displacement & 0x200000:
            displacement -= 0x400000
        address = offset + 0x40000
        key = hex(address + displacement)
        if key in found:
            found[key].append(hex(address))
    require(found == retained, "direct-call encoding candidate scan")
    require(found["0x1515a0"] == d["diagnosticRecorder"]["callSites"], "six direct recorder call candidates")
    require(13 * 106 < 1391 <= 13 * 107, "recorder capacity bound")

    # Replay the actual complete published ISG timeout report, without promoting
    # its filtered CAN trace to a complete ECU response or raw-wire capture.
    log = (ROOT / "captures/diagnostics-isg-followup-20261021/isg-trace/probe.txt").read_text()
    trace_line = next(line for line in log.splitlines() if line.startswith("GATEWAY_TRACE "))
    payload = bytes.fromhex(trace_line.split(" raw=", 1)[1])
    require(payload[:3] == bytes.fromhex("190AFC"), "retained timeout request prefix")
    trace = payload[3:]
    require(len(trace) == 260 and len(trace) % 13 == 0, "20 trace records")
    records = [trace[i:i + 13] for i in range(0, len(trace), 13)]
    received = [record[5:] for record in records if record[3:5] == bytes.fromhex("07EE")]
    require(received[0] == bytes.fromhex("10E7590A09180219"), "known ISG first CAN frame")
    prefix = received[0][2:] + b"".join(frame[1:] for frame in received[1:])
    require(len(prefix) == 125 < 231 and len(records) == 20 < 106,
            "incomplete UDS prefix without recorder capacity exhaustion")
    observation = d["diagnosticRecorder"]["existingIsgObservation"]
    require(observation["publishedTraceBytes"] == len(trace) and
            observation["recoveredUdsPrefixBytes"] == len(prefix) and
            not observation["recordCapacityReached"], "retained observation boundary")
    for declared in range(4096):
        owner3_expected = declared - 3 if declared >= 3 else 3
        other_expected = declared - 1 if declared >= 1 else 1
        require((owner3_expected > 3 and owner3_expected < 920) == (7 <= declared <= 922),
                "owner3 length interval")
        require((other_expected > 6 and other_expected < 920) == (8 <= declared <= 920),
                "other-owner length interval")
    require(d["ownerBranches"]["noOwnerChangeProposed"] and d["qualifiedLivePlan"] is None and
            not d["newVehicleRequestProposed"] and not d["newCollectionTableProposed"] and
            not d["newLogSwitchProposed"], "research does not invent a live plan")
    require(d["rawMirror"]["beforeDiagnosticRejection"] and
            not d["rawMirror"]["unconditionalEmission"] and
            not d["debugTextLog"]["lowLevelOutputEndpointQualified"],
            "conditional visibility remains explicit")
    print(f"PASS: {len(d['sources'])} pinned sources, {len(d['ranges'])} native ranges/"
          f"{len(instructions)} instructions, {len(anchors)} anchors, 3 receive paths, "
          "4096 owner-boundary cases and complete20-record ISG report; no unconditional observer qualified")


if __name__ == "__main__":
    check()
