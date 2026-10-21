#!/usr/bin/env python3
"""Offline counterexample: sampled CAN export is not a warning-event history.

The timing sequence is synthetic. The N9's active reporting mode and interval
were not captured. Nothing here establishes that a real 1DD was missed.
No vehicle/network calls, firmware execution, or product changes.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "research/vehicle-diagnostics/washer-observation-modes-20261021.json"
PINS = {
    "mcu": ("captures/firmware/ivi-34.1.33.2605218.1/mcu/app.bin", "6c277d1eb819b765d997554fc727835314b9477cae414aa806ea7cf78eda4d02"),
    "modes": ("captures/diagnostics-can-visibility-20261021/mcu/mcu-170a12.asm", "de28275f912e3d2805e994946d7aeddf727c3033e34ccd938aeb9a8c672fb963"),
    "dispatch": ("captures/diagnostics-can-visibility-20261021/mcu/mcu-1704ac.asm", "a193c3cd1525f45ade84c16fa80c9efb3fb4e4a639c979a6ade38d9371b4a3bd"),
    "listener": ("captures/split-firmware-20260923/jadx/framework/sources/android/hardware/bydauto/instrument/AbsBYDAutoInstrumentListener.java", "e6157cd624a172810177b04a1e5b8ad6756d2521c8777df23f6e055f0b579772"),
    "scalarTrace": ("research/vehicle-diagnostics/washer-sensor-origin-20261021.json", "a527c6789982ee317459b52f5aea7cb05787add31ab92b243369ff2e2812b180"),
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def mirror(mode, frames, interval=1000, initial_payload=b"\x00\x00", last_tick=0):
    """Selected 170A12 branches; equal DLC, no wrap, serialized arrivals.

    Counter units are deliberately unspecified. Each tuple models an already
    matched dynamic-table record, not table discovery or transport delivery.
    """
    require(mode in (0, 1, 2, 3), "invalid reporting mode")
    cached = initial_payload
    emitted = []
    for tick, payload in frames:
        require(tick >= last_tick, "counter wrap is outside this model")
        changed = payload != cached
        elapsed = tick - last_tick >= interval
        send = mode == 0 or mode == 1 and changed or mode == 2 and elapsed or mode == 3 and (changed or elapsed)
        if send:
            cached = payload
            emitted.append((tick, payload))
            if mode in (2, 3):
                last_tick = tick
        elif mode == 2:
            cached = payload
    return emitted


def main(write):
    data, sources = {}, []
    for key, (path, digest) in PINS.items():
        raw = (ROOT / path).read_bytes()
        require(hashlib.sha256(raw).hexdigest() == digest, path)
        data[key] = raw
        sources.append(dict(path=path, sha256=digest))
    # Independently compare every retained disassembly byte with the MCU image.
    instruction_count = 0
    for key in ("modes", "dispatch"):
        for address, hexbytes in re.findall(r"(?m)^([0-9a-f]{8}): ([0-9a-f]+)\s", data[key].decode()):
            offset = int(address, 16) - 0x40000
            expected = bytes.fromhex(hexbytes)
            require(data["mcu"][offset:offset + len(expected)] == expected, address)
            instruction_count += 1
    require(instruction_count > 100, "missing disassembly")
    listener = data["listener"].decode()
    require("else if (type == 638582792) {\n                    onTextInfoChanged(value);" in listener,
            "different listener dispatch")
    scalar = json.loads(data["scalarTrace"])
    require(scalar["receiver"]["warning"]["fid"] == "0x26100008", "different scalar trace")
    body = b"\x3f\x01"
    washer = b"\xdd\x01"
    frames = [(1000, body), (1250, washer), (1500, body), (2000, body)]
    outputs = {str(mode): mirror(mode, frames) for mode in range(4)}
    require(outputs["0"] == frames, "every-frame branch")
    require(outputs["1"] == frames[:3], "change branch")
    require(outputs["2"] == [frames[0], frames[3]], "interval branch")
    require(outputs["3"] == frames[:3], "change-or-interval branch")
    require(mirror(2, [(999, body), (1000, washer)]) == [(1000, washer)], "inclusive interval boundary")
    require(mirror(3, [(1000, body), (2000, body)]) == [(1000, body), (2000, body)], "unchanged periodic export")

    def presentation(rows):
        return [dict(counter=tick, warning=f"0x{int.from_bytes(payload, 'little') & 1023:03X}")
                for tick, payload in rows]

    report = dict(schema="denza-lab.washer-observation-modes.v1", date="2026-10-21",
        offlineOnly=True, vehicleCalls=0, sources=sources,
        checkedDisassemblyInstructions=instruction_count,
        sourceOrder=dict(staticFieldDispatch="17052A -> 17042C",
                         dynamicRawMirror="170530 -> 170B3C -> 170A12",
                         consequence="The dynamic raw reporting mode is not the condition for entering the earlier static warning-field decoder."),
        rawModes={"0": "every matched frame", "1": "payload change",
                  "2": "elapsed interval; otherwise cache only", "3": "payload change or elapsed interval"},
        syntheticCounterexample=dict(observedOnCar=False, counterUnits="unspecified synthetic units",
            interval=1000, incoming= presentation(frames), rawExports={mode: presentation(rows) for mode, rows in outputs.items()},
            scalarChanges=presentation(frames[:3]), sparseScalarReads=presentation([frames[0], frames[3]]),
            conclusion="Mode2 can omit a transient1DD from raw export while the earlier static scalar changes to1DD and back. Sparse GETs can then read only13F. This is a counterexample to treating these samples as a complete history, not a claim about the real N9 event."),
        directObservationCandidate=dict(device=1007, fid="0x26100008", sdkCallback="onTextInfoChanged(int)",
            sourceBinding="AbsBYDAutoInstrumentListener routes decimal638582792 directly to onTextInfoChanged.",
            limit="Listener registration/delivery/lifecycle in a chosen app or shell collector is not live-qualified by this offline pass. No recorder was installed or started."),
        n9Applicability=dict(activeRawMode=None, interval=None, installedMcuHashMatched=False,
            realMissed1DDProved=False, actualPhysicalCanRateKnown=False,
            limit="Approximately1Hz callbacks alone cannot distinguish physical bus rate from MCU reporting policy. Mode0/1/3 would preserve the modeled payload changes under the model assumptions."))
    if write:
        REPORT.write_text(json.dumps(report, indent=2) + "\n")
    else:
        require(json.loads(REPORT.read_text()) == report, "report differs from replay")
    print(json.dumps(dict(checkedDisassemblyInstructions=instruction_count, syntheticModeCases=6,
                         mode2DropsSynthetic1DD=True, actualN9ModeKnown=False, realMissed1DDProved=False)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    main(parser.parse_args().write)
