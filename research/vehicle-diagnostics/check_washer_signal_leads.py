#!/usr/bin/env python3
"""Recheck washer-warning and controller leads, offline; print JSON to stdout.

No vehicle/network calls, firmware execution, extraction or file writes.
Requires the retained local corpus and N9 captures; no new mapping is inferred.
"""
import collections
import hashlib
import json
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research/vehicle-diagnostics"))
sys.path.insert(0, str(ROOT / "captures/washer-n9-20261016/cycle-02"))
from check_oem_warning_mappings import elf_va, mov_w, bl_target
from analyze_state import read_stream


def main():
    report = json.loads((ROOT / "research/vehicle-diagnostics/oem-warning-mappings-20261021.json").read_text())
    for source in report["sources"]:
        assert hashlib.sha256((ROOT / source["path"]).read_bytes()).hexdigest() == source["sha256"]
    popup = json.loads((ROOT / report["dictionary"]["path"]).read_text())
    warning = next(row for row in popup["dictionary"] if row["id"] == "1DD")
    assert warning["text"] == "请加注洗涤液"
    body = next(row for row in popup["dictionary"] if row["id"] == "13F")
    native = (ROOT / "captures/firmware/pulled-from-car-20261018/native/libCellsClusterDs.so").read_bytes()
    anchor = elf_va(native, 0x17EBE4, 16)
    words = struct.unpack("<IIII", anchor)
    assert mov_w(words[0], 0x1DD, 8) and bl_target(words[3], 0x17EBF0) == 0x184680

    stock = json.loads((ROOT / "research/vehicle-diagnostics/remaining-role-followup-20261021.json").read_text())["inputs"]["stockFunctionMap"]
    raw = (ROOT / stock["path"]).read_bytes()
    assert hashlib.sha256(raw).hexdigest() == stock["sha256"]
    system = next(s for s in json.loads(raw)["data"] if s["systemName"] == "雨刮洗涤系统")
    cases = [dict(name=c["caseName"], steps=[dict(request=s["canId"], sid=s["sid"], did=s["did"])
              for s in c["steps"]]) for c in system["cases"]]
    assert len(cases) == 6
    assert all(s["request"] == "0x747" for c in cases[:2] for s in c["steps"])
    assert all(c["steps"][-1]["did"] == "0x3362" and c["steps"][-1]["sid"] == "2F" for c in cases[:2])

    n9 = ROOT / "captures/washer-n9-20261016"
    windows = []
    for phase in ("liquid-present", "empty", "refilled"):
        frames, checked = read_stream(n9 / "cycle-02" / phase / "gb.log", "gb")
        selected = [f for f in frames if f.address.can_id == 0x261 and f.address.sub_id == 0 and f.address.channel == 0]
        ids = collections.Counter((f.payload[0] | f.payload[1] << 8) & 0x3FF for f in selected)
        assert ids == {0x13F: 179}
        windows.append(dict(phase=phase, raw_sha256=checked["raw_sha256"],
                            decoded_warning_ids={hex(k): v for k,v in ids.items()}))
    gets = []
    for phase in ("liquid-present", "empty", "refilled", "refilled-hood-open"):
        path = n9 / phase / "fids.tsv"
        manifest = json.loads((path.parent / "sha256.json").read_text())
        assert hashlib.sha256(path.read_bytes()).hexdigest() == manifest["fids.tsv"]
        rows = [line.split("\t") for line in path.read_text().splitlines()]
        values = [r[5] for r in rows if r[0] == "FID" and r[3:5] == ["1007", "0x26100008"]]
        assert values == ["0x0000013F", "0x0000013F"]
        gets.append(dict(phase=phase, values=values))
    return dict(offlineOnly=True, vehicleCalls=0, warning=warning,
        warningMeaningRussian="Добавьте жидкость омывателя", translationIsOEM=False,
        verifiedWarningSourceHashes=len(report["sources"]),
        nativeAllowlist=dict(va="0x17EBE4", bytes=anchor.hex(), insertTarget="0x184680"),
        readPath=dict(device=1007, fid="0x26100008", transaction=5,
                      raw="CAN 0x261 sub0 ch0, (b0 | b1 << 8) & 0x03FF"),
        factoryActuatorCases=cases, n9GBWindows=windows, n9ScalarGets=gets,
        bodyWarning=body,
        limitations=["1DD is a named warning, not a direct level-sensor measurement or UDS DTC.",
            "No 1DD observed in retained N9 windows. 13F does not prove priority masking of 1DD.",
            "Warning/native corpus provenance and direct diagnostic transport were qualified on Z9GT/profile170, not N9/profile168.",
            "747 is a source-backed front-washer actuator route, not established sensor wiring ownership.",
            "2F3362 does not authorize or define 223362. No level DID or applicable circuit DTC identified.",
            "No actuator, diagnostic session, unlock, brute-force DID scan or whitelist change performed."])


if __name__ == "__main__":
    print(json.dumps(main(), ensure_ascii=False, indent=2))
