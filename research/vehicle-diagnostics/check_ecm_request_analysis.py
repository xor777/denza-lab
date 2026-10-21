#!/usr/bin/env python3
"""Replay retained ECM evidence and verify reviewed native bytes, entirely offline.

Uses only the Python standard library. Does not execute firmware, import any
vehicle transport, make requests or claim to reproduce the remote ECU policy.
"""
import hashlib
import json
from pathlib import Path
import re
import struct

ROOT = Path(__file__).resolve().parents[2]
REPORT = Path(__file__).with_name("ecm-request-analysis-20261021.json")


def require(condition, label):
    if not condition:
        raise AssertionError(label)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_va(image, address, size):
    """Read one AArch64 ELF virtual range using its file-backed LOAD segment."""
    require(image[:6] == b"\x7fELF\x02\x01", "ELF64 little-endian")
    require(struct.unpack_from("<H", image, 18)[0] == 183, "AArch64")
    phoff = struct.unpack_from("<Q", image, 32)[0]
    entsize, count = struct.unpack_from("<HH", image, 54)
    require(entsize == 56 and count < 100, "bounded program headers")
    for index in range(count):
        kind, _, offset, va, _, filesz, _, _ = struct.unpack_from(
            "<IIQQQQQQ", image, phoff + index * entsize)
        if kind == 1 and va <= address and address + size <= va + filesz:
            return image[offset + address - va:offset + address - va + size]
    raise AssertionError(f"Unmapped ELF range {address:#x}+{size}")


def outer_response(raw):
    require(len(raw) >= 7 and raw[0] == 0x0A, "outer response header")
    require(raw[4:6] == b"\x01\x01", "one complete outer fragment")
    require(raw[3] == len(raw) - 7, "outer payload byte count")
    return int.from_bytes(raw[1:3], "big"), raw[6], raw[7:]


def check():
    report = json.loads(REPORT.read_text())
    require(report["offlineOnly"] and report["vehicleOperations"] == 0,
            "offline scope")
    for source in report["sources"]:
        data = (ROOT / source["path"]).read_bytes()
        require(len(data) == source["bytes"] and digest(data) == source["sha256"],
                "source drift: " + source["path"])

    # Independently read the actual TX/RX text rather than trusting the aggregate.
    for observation in report["observations"]:
        transcript = (ROOT / observation["source"]).read_text()
        tx = observation.get("txEnvelopeHex")
        rx = observation.get("rxEnvelopeHex")
        if tx:
            require(any(line.startswith("REQUEST ") and tx in line
                        for line in transcript.splitlines()), "retained TX " + tx)
            raw = bytes.fromhex(tx)
            require(raw[:3] == bytes.fromhex("0A07E0") and
                    raw[3] == len(raw) - 4 and
                    raw[4:].hex().upper() == observation["requestHex"],
                    "TX length and UDS payload")
        if rx:
            require(any(line.startswith("FRAME ") and "raw=" + rx in line
                        for line in transcript.splitlines()), "retained RX " + rx)
            address, result, uds = outer_response(bytes.fromhex(rx))
            require(address == 0x7E8 and result in (1, 2), "ECM RX route/result")
            require(uds.hex().upper() == observation["udsHex"], "exact UDS reply")
            require((result == 2) == (uds[0] == 0x7F), "negative envelope category")
        else:
            require(re.search(r"^RESPONSE .*request=" + observation["requestHex"] +
                              r" .*uds=" + observation["udsHex"] + r"$",
                              transcript, re.MULTILINE) is not None,
                    "retained assembled reply " + observation["requestHex"])

    supported = json.loads((ROOT / report["sources"][0]["path"]).read_text())
    ecm = next(item for item in supported["modules"] if item["name"] == "ECM")
    require(ecm["softwareCode"] == report["identity"]["softwareCodeHex"] and
            ecm["formatRead"]["formatId"] == 0 and not ecm["supportedListRead"],
            "aggregate preserves unreceived ECM registry")
    require(ecm["negativeResponses"] == [{"request": "190A", "responseHex": "7F1913",
                                         "nrcHex": "13",
                                         "unsupportedServiceOrSubfunction": False}],
            "NRC13 is not unsupported-subfunction evidence")
    calibration = bytes.fromhex(report["identity"]["mode09CalibrationResponseHex"])
    require(calibration[:3] == bytes.fromhex("490401") and len(calibration[3:]) == 16,
            "one Mode09 calibration record")
    require(calibration[3:].rstrip(b"\0").decode("ascii") ==
            report["identity"]["calibrationId"] == "Z31L07AB01", "CALID decode")
    calibration_log = (ROOT / "captures/diagnostics-followup-20261021/engine/live-03/protocol.txt").read_text()
    require(calibration.hex().upper() in calibration_log and
            "62F194" + report["identity"]["softwareCodeHex"] in calibration_log,
            "F194 and CALID in same retained plan")

    # Check every stored instruction against the exact MCU bytes. No MCU code runs.
    mcu_info = report["firmware"]["mcu"]
    mcu = (ROOT / mcu_info["path"]).read_bytes()
    require(digest(mcu) == mcu_info["sha256"], "MCU identity")
    instructions = {}
    for region in mcu_info["ranges"]:
        start, end = int(region["startVA"], 0), int(region["endExclusiveVA"], 0)
        require(digest(mcu[start - 0x40000:end - 0x40000]) == region["sha256"],
                "MCU range " + region["name"])
        cursor = start
        rows = json.loads((ROOT / region["instructionRecords"]).read_text())
        for row in rows:
            address, code = int(row["va"], 0), bytes.fromhex(row["bytes"])
            require(address == cursor and mcu[address - 0x40000:address - 0x40000 + len(code)] == code,
                    "MCU instruction continuity and bytes")
            instructions[address] = (row["mnemonic"], row["operands"])
            cursor += len(code)
        require(cursor == end, "MCU bounded range end")
    anchors = {
        0x1508DA: ("ld.bu", "0x3[r29], r1"),
        0x15094A: ("bne", "0x1511fa"),
        0x151260: ("st.b", "r28, 0x9[sp]"),
        0x151268: ("addi", "0x4, r29, r7"),
        0x15126C: ("mov", "r28, r8"),
        0x15126E: ("jarl", "0x1b3ed4, lp"),
        0x15127C: ("movea", "0x55, r0, r7"),
        0x1528FA: ("add", "0x8, r1"),
        0x152A18: ("ld.bu", "0x8[r29], r7"),
        0x152A1E: ("jarl", "0x152ff6, lp"),
        0x153030: ("addi", "-0x78, r26, r0"),
        0x153034: ("bne", "0x153076"),
        0x153050: ("movea", "0x1388, r0, r7"),
        0x153074: ("br", "0x1530e0"),
        0x1530A2: ("addi", "0x7, r27, r7"),
        0x1530A8: ("jarl", "0x1b3ed4, lp"),
        0x1530DC: ("jarl", "0x151948, lp"),
        0x151978: ("movea", "0x7f, r0, r1"),
        0x151988: ("mov", "0x2, r1"),
        0x152C52: ("cmp", "0x1, r28"),
        0x152C56: ("addi", "-0x1, r28, r1"),
        0x152C8C: ("cmp", "0x6, r1"),
        0x152C8E: ("bnh", "0x152c9c"),
        0x152C96: ("addi", "-0x398, r1, r0"),
        0x152C9A: ("bnc", "0x152ce0"),
        0x152CC6: ("movea", "0x13, r0, r1"),
        0x152CD8: ("jarl", "0x151948, lp"),
    }
    for address, expected in anchors.items():
        require(instructions.get(address) == expected, f"reviewed MCU anchor {address:#x}")

    # The decoder's lifted operations explicitly settle carry polarity for ADDI,
    # which differs from the borrow flag produced by CMP. Walk all 12-bit ISO-TP
    # declared lengths and compare the source branch flags with a closed interval.
    pcode = (ROOT / report["captureBase"] / "mcu-size-limit.pcode.txt").read_text()
    require("CYf = carry(r1, 0xfffffc68)" in pcode and
            "CYf == 0x0" in pcode and "goto ram[152ce0:4]" in pcode,
            "reviewed ADDI/BNC carry semantics")
    accepted_lengths = []
    for declared in range(4096):
        expected = declared - 1 if declared >= 1 else 1
        cmp_carry, cmp_zero = expected < 6, expected == 6
        lower_reject = cmp_carry or cmp_zero
        addi_carry = expected + 0xFFFFFC68 > 0xFFFFFFFF
        upper_accept = not addi_carry
        accepted = not lower_reject and upper_accept
        require(accepted == (8 <= declared <= 920), "size interval carry boundary")
        if accepted:
            accepted_lengths.append(declared)
    gate = report["responseSizeGate"]
    require(accepted_lengths == list(range(8, 921)) and
            gate["acceptedPositiveFirstFrameUdsBytesMax"] == 920 and
            gate["locallyRejectedAtOrAboveUdsBytes"] == 921,
            "exact response-size interval")
    for row in gate["boundaries"]:
        require(row["expected"] == row["L"] - 1 and
                row["accepted"] == (row["L"] in accepted_lengths),
                "recorded boundary")
    require((920 - 3) // 4 == gate["maxWholeDtcRecordsThroughThisGate"] == 229 and
            3 + 4 * 230 == gate["firstRejectedWholeDtcUdsBytes"] == 923 and
            3 + 4 * gate["earlierEcmMaskFFCount"] == gate["earlierEcmListBytesAtCount"] == 1035,
            "DTC list size arithmetic")
    count = bytes.fromhex(gate["countReplyHex"])
    require(count[:4] == bytes.fromhex("5901FF00") and
            int.from_bytes(count[4:], "big") == 258,
            "retained maskFF count is not guessed registry length")
    accepted_controls = []
    for module in supported["modules"]:
        if module["supportedListRead"]:
            size = 3 + 4 * module["supportedCount"]
            accepted_controls.append({
                "name": module["name"], "route": module["requestIdHex"],
                "supportedRecords": module["supportedCount"],
                "completeUdsBytes": size,
                "withinInspectedMcuSizeGate": 8 <= size <= 920,
            })
    require(accepted_controls == gate["acceptedRegistryControls"] and
            len(accepted_controls) == 4 and
            all(row["withinInspectedMcuSizeGate"] for row in accepted_controls),
            "four retained complete registries fit recovered size gate")

    # Exact AArch64 constants/call-site bytes and vtable binding establish that
    # the stock supported-DTC helper appends 19 and 0A with no parameter.
    lib = (ROOT / report["firmware"]["libuds"]["path"]).read_bytes()
    native_anchors = {
        0x9FE24: "290380524a018052e9630039602200a9ea530039",
        0x9FE38: "080040f9081d40f9e163009100013fd6",
        0x9FE48: "600240f9080040f9081d40f9e153009100013fd6",
        0xCF298: "5f2403d5082443a91f0109ebe00000542900403909010039081840f908050091081800f9c0035fd6",
        0xB0070: "f0220194",
    }
    for address, raw_hex in native_anchors.items():
        expected = bytes.fromhex(raw_hex)
        require(read_va(lib, address, len(expected)) == expected,
                f"reviewed stock constructor anchor {address:#x}")
    vtable = json.loads((ROOT / report["captureBase"] / "libuds-package-vtable.json").read_text())
    entry = next(row for row in vtable if row["vptrOffset"] == "0x38")
    require(entry["address"] == "0x102a70" and
            entry["relocation"]["resolved"] == "0xcf298" and
            entry["relocation"]["symbol"].endswith("CUdsPackage7putDataERKh"),
            "package byte-append dispatch")
    require(report["alternatives"]["newSourceBackedRegistryRequest"] is None and
            not report["alternatives"]["newVehicleRequestProposed"] and
            not report["causeAssessment"]["rootCauseEstablished"],
            "unknown ECU policy preserved")
    require(not report["causeAssessment"]["nrcPhysicalOriginEstablished"],
            "shared local/remote negative publisher ambiguity preserved")
    print(f"PASS: {len(report['sources'])} pinned sources, {len(report['observations'])} retained exchanges, "
          f"{len(mcu_info['ranges'])} MCU ranges/{len(instructions)} instructions, "
          "4096 size-gate cases and 5 stock-library anchors; "
          "local/remote NRC origin and ECM registry remain unqualified")


if __name__ == "__main__":
    check()
