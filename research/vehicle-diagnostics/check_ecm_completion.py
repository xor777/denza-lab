#!/usr/bin/env python3
"""Replay the finite 2026-10-21 ECM completion pass; no vehicle I/O."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "research/vehicle-diagnostics/ecm-completion-20261021.json"
IDENTITY = "62F19432440011E004000000"
MODE01_LENGTHS = {
    "00": 4, "20": 4, "40": 4, "0C": 2, "01": 4, "21": 2,
    "2C": 1, "2D": 1, "2E": 1, "30": 1, "31": 2, "41": 4,
    "43": 2, "45": 1, "47": 1, "49": 1, "4A": 1, "4C": 1,
    "54": 2, "56": 2,
}
EXPECTED_MAPS = {"0100": "4100BE3EA813", "0120": "4120A01FB011",
                 "0140": "4140FED01401", "0900": "490055404000"}


def require(condition, why):
    if not condition:
        raise ValueError(why)


def supported(raw):
    word = int.from_bytes(bytes.fromhex(raw), "big")
    return [bit + 1 for bit in range(32) if word & (1 << (31 - bit))]


def transcript(text, plan):
    """Keep complete wire records, including PID56 that stopped the original collector."""
    lines = text.splitlines()
    require(bool(lines), "empty transcript")
    ready = re.fullmatch(r"(DENZA_SERVE_[0-9a-f]{24}:)READY", lines[0])
    require(ready is not None, "missing READY/nonce")
    prefix = ready[1]
    require((len(lines) - 1) % 4 == 0, "incomplete exchange")
    records = []
    for offset in range(1, len(lines), 4):
        seq = len(records) + 1
        command, begin, answer, end = lines[offset:offset + 4]
        require(seq <= len(plan), "extra request")
        req = plan[seq - 1]
        require(command == f"HOST x {seq} 7E0 {req} 4000", "request order/target")
        require(begin == prefix + "BEGIN" and end == prefix + "END ok", "boundary")
        match = re.fullmatch(r"a (\d+) (\d+) (\d+) (\d+) ([0-9A-F]+)", answer)
        require(match is not None and int(match[1]) == seq, "reply sequence")
        require(1 <= int(match[2]) <= 16, "vendor fragment count")
        raw = match[5]
        data = bytes.fromhex(raw)
        require(bool(data), "empty reply")
        if req == "22F194":
            require(raw == IDENTITY, "ECM identity changed")
        elif req.startswith("01"):
            require(raw.startswith("41" + req[2:]), "wrong Mode01 reply")
            # Preserve the exact record that stopped the original collector.
            # Its conditional second bank was qualified after the live pass.
            if req == "0156":
                require(raw == "415680", "unexpected short PID56")
            else:
                require(len(data) == 2 + MODE01_LENGTHS[req[2:]], "PID record length")
            if req == "010C":
                require(raw == "410C0000", "engine not stopped")
        elif req == "0900":
            require(len(data) == 6 and raw.startswith("4900"), "info support length")
        elif req == "0904":
            require(raw.startswith("4904") and len(data) >= 3, "calibration prefix")
            require(1 <= data[2] <= 8 and len(data) == 3 + 16 * data[2], "calibration count")
            require(all(x == 0 or 32 <= x <= 126 for x in data[3:]), "calibration ASCII")
        elif req == "090A":
            require(raw.startswith("490A01") and len(data) == 23, "ECU name shape")
            require(all(x == 0 or 32 <= x <= 126 for x in data[3:]), "ECU name ASCII")
        else:
            raise ValueError("request outside this finite plan")
        if req in EXPECTED_MAPS:
            require(raw == EXPECTED_MAPS[req], "support map changed")
        stamp = int(match[4])
        require(not records or stamp > records[-1]["receivedNanos"], "nonmonotonic receive time")
        records.append({"seq": seq, "requestHex": req, "responseHex": raw,
                        "frames": int(match[2]), "latencyMs": int(match[3]),
                        "receivedNanos": stamp})
    return records


def decoded_values(records):
    values = {r["requestHex"]: bytes.fromhex(r["responseHex"])[2:] for r in records}
    out = {}
    for pid in ("2C", "2E", "45", "47", "49", "4A", "4C"):
        out[pid] = {"value": values["01" + pid][0] * 100 / 255, "unit": "%"}
    out["2D"] = {"value": (values["012D"][0] - 128) * 100 / 128, "unit": "%"}
    for pid in ("21", "31"):
        out[pid] = {"value": int.from_bytes(values["01" + pid], "big"), "unit": "km"}
    out["30"] = {"value": values["0130"][0], "unit": "count"}
    out["43"] = {"value": int.from_bytes(values["0143"], "big") * 100 / 255, "unit": "%"}
    out["54"] = {"value": int.from_bytes(values["0154"], "big") - 32767, "unit": "Pa"}
    out["56"] = {"value": (values["0156"][0] - 128) * 100 / 128, "unit": "%", "bank": "B1"}
    return out


def check():
    evidence = json.loads(EVIDENCE.read_text())
    for source in evidence["sources"]:
        p = ROOT / source["path"]
        require(hashlib.sha256(p.read_bytes()).hexdigest() == source["sha256"], "hash: " + str(p))
    all_records = []
    texts = []
    for run in evidence["runs"]:
        result = json.loads((ROOT / run["resultPath"]).read_text())
        text = (ROOT / run["protocolPath"]).read_text()
        records = transcript(text, result["fixedPlan"])
        require(records == run["wireResponses"], "wire response projection")
        if run["outcome"] == "stopped_on_short_0156":
            require(result["state"] == "stopped" and result["error"] == "PID response mismatch", "stop reason")
            require(len(records) == 21 and records[-1]["requestHex"] == "0156", "stop point")
            require(records[:-1] == result["responses"], "accepted prefix")
            require("0900" not in [r["requestHex"] for r in records], "unexecuted tail")
        else:
            require(result["state"] == "complete" and records == result["responses"], "run completion")
            require(len(records) == len(result["fixedPlan"]), "plan complete")
        require(result["exitCode"] == 0 and result["jarRemoved"] and result["processAbsent"], "cleanup")
        all_records.extend(records)
        texts.append((text, result["fixedPlan"]))
    require(len(all_records) == 29, "total wire responses")
    require(decoded_values(all_records) == evidence["decodedValues"], "arithmetic projection")
    cal = [r for r in all_records if r["requestHex"] == "0904"][0]
    require(bytes.fromhex(cal["responseHex"])[3:].rstrip(b"\0").decode("ascii") == "Z31L07AB01", "CALID")
    require(supported("55404000") == evidence["mode09SupportedInfoTypes"], "Mode09 support")
    require(4 in supported("55404000") and 10 in supported("55404000"), "info types not advertised")
    for i, health in enumerate(evidence["healthPaths"]):
        status = json.loads((ROOT / health / "status.json").read_text())
        require(all(status[k] for k in ("guardPass", "firmwareHashMatch", "appHashMatch", "crashEmpty")), "health")
        require(status["processes"] == evidence["stockProcesses"], "stock process restart")
        require("denza_ecm" not in (ROOT / health / "processes.txt").read_text(), "helper survives")
    old = json.loads((ROOT / evidence["priorTelemetryPath"]).read_text())
    advertised = {p for p in old["supportedPidHex"] if int(p, 16) % 32}
    history = json.loads((ROOT / evidence["originalObdPath"]).read_text())
    received = {r["pid"] for v in history.values() if isinstance(v, list) for r in v
                if isinstance(r, dict) and r.get("request") == "07E0" and r.get("result") == "ok" and "pid" in r}
    received.update(r["requestHex"][2:] for run in old["runs"] for r in run["responses"] if r["requestHex"].startswith("01"))
    received.update(r["requestHex"][2:] for r in all_records if r["requestHex"].startswith("01"))
    require(advertised <= received and len(advertised) == 43, "advertised leaf coverage")
    # Damage the actual retained transcript, rather than testing a mirror of its
    # generator. Each case must fail before any projected measurements are used.
    text, plan = texts[0]
    damaged = ["", "\n".join(text.splitlines()[:-1]),
               text.replace("HOST x 1 7E0", "HOST x 1 7E2", 1),
               text.replace(IDENTITY, "62F194000000000000000000", 1),
               text.replace("410C0000", "410C0004", 1),
               text.replace("4100BE3EA813", "410000000000", 1),
               text.replace("4154E8B6", "4154E8", 1),
               text.replace("415680", "41568000", 1),
               text.replace(":END ok", ":END err", 1)]
    for bad in damaged:
        try:
            transcript(bad, plan)
        except ValueError:
            continue
        raise AssertionError("damaged capture accepted")
    return {"status": "pass", "sourceHashes": len(evidence["sources"]),
            "wireResponses": len(all_records), "advertisedNonBitmapPidsReceived": 43,
            "conditionalSingleBankRecord": "0156", "calibrationId": "Z31L07AB01",
            "damagedTranscriptsRejected": len(damaged), "vehicleActions": 0}


if __name__ == "__main__":
    print(json.dumps(check(), indent=2))
