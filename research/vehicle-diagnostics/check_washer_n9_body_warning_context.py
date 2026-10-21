#!/usr/bin/env python3
"""Replay retained N9 261/body context. Offline only; no vehicle or network calls."""
import collections
import hashlib
import json
from pathlib import Path
import re
import statistics
import struct
import sys

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "research/vehicle-diagnostics/washer-n9-body-warning-context-20261021.json"
BASE = ROOT / "captures/washer-n9-20261016"
MCU = "captures/firmware/ivi-34.1.33.2605218.1/mcu/app.bin"
CELLS = "captures/firmware/pulled-from-car-20261018/native/libCellsClusterDs.so"
CLIENT = "captures/firmware/pulled-from-car-20261018/native/libClusterClient.android.release.so"
HAL = "captures/firmware/ivi-34.1.33.2605218.1/system/system/lib64/hw/auto.default.so"
GD = "captures/cluster-native-20261018/pck/decoded/"
sys.path.insert(0, str(ROOT / "tools"))
from analyze_can_turn_capture import parse_capture, DONE_RE

BUFFER = re.compile(r"BUFFER seq=(\d+) t_ns=(\d+) stream=(\w+) len=(\d+) raw=([0-9a-f]*)$")
# Descriptor order and exact wire coordinates are checked against MCU bytes below.
FIELDS = [
    (0x00, "online", (0, 0, 0, 0)),
    (0x08, "selected_warning", (1, 0, 2, 1)),
    (0x14, "door_left_front", (2, 4, 2, 4)),
    (0x15, "door_right_front", (2, 5, 2, 5)),
    (0x16, "door_left_rear", (2, 6, 2, 6)),
    (0x17, "door_right_rear", (2, 7, 2, 7)),
    (0x18, "trunk", (3, 0, 3, 0)),
    (0x19, "hood", (3, 1, 3, 1)),
    (0x1A, "belt_driver", (3, 2, 3, 2)),
    (0x1B, "belt_passenger", (3, 3, 3, 3)),
    (0x1C, "belt_row2_left", (3, 4, 3, 4)),
    (0x1D, "belt_row2_middle", (3, 5, 3, 5)),
    (0x1E, "belt_row2_right", (3, 6, 3, 6)),
    (0x1F, "power_generation", (3, 7, 4, 3)),
    (0x26, "energy_feedback", (4, 6, 4, 7)),
    (0x28, "text_color", (5, 0, 5, 2)),
    (0x2B, "tja_standby_light", (5, 3, 5, 5)),
    (0x2E, "light_color", (5, 6, 6, 0)),
    (0x31, "belt_row3_left", (6, 1, 6, 1)),
    (0x32, "belt_row3_middle", (6, 2, 6, 2)),
    (0x33, "belt_row3_right", (6, 3, 6, 3)),
    (0x35, "voice_frequency", (6, 5, 6, 7)),
    (0x38, "voice_type", (7, 0, 7, 3)),
    (0x3C, "unnamed_3c", (7, 4, 7, 7)),
    (0x40, "unnamed_40", (8, 0, 8, 7)),
]
OPENINGS = [name for _, name, _ in FIELDS if name.startswith("door_")] + ["trunk", "hood"]
BELTS = [name for _, name, _ in FIELDS if name.startswith("belt_")]


def digest(path):
    return hashlib.sha256((ROOT / path).read_bytes()).hexdigest()


def elf_bytes(data, address, size):
    ph = struct.unpack_from("<Q", data, 32)[0]
    width, count = struct.unpack_from("<HH", data, 54)
    for index in range(count):
        kind, _, offset, base, _, length, _, _ = struct.unpack_from("<IIQQQQQQ", data, ph + index * width)
        if kind == 1 and base <= address and address + size <= base + length:
            return data[offset + address - base:offset + address - base + size]
    raise ValueError(hex(address))


def extract(payload, coords):
    first_byte, first_bit, last_byte, last_bit = coords
    start = (first_byte - 1) * 8 + first_bit
    end = (last_byte - 1) * 8 + last_bit
    return (int.from_bytes(payload, "little") >> start) & ((1 << (end - start + 1)) - 1)


def intervals(values, scale):
    gaps = [(right - left) / scale for left, right in zip(values, values[1:])]
    return {"min": min(gaps), "median": statistics.median(gaps), "max": max(gaps)} if gaps else None


def histogram(values):
    return dict(sorted(collections.Counter(str(value) for value in values).items()))


def main():
    sources = {MCU, CELLS, CLIENT, HAL, "tools/analyze_can_turn_capture.py",
        "captures/cluster-native-20261018/tables/data-items.tsv",
        "captures/cluster-native-20261018/tables/can-signals.tsv",
        "captures/cluster-native-20261018/client-api/classes/WarnCarBody.xml",
        "captures/firmware/pulled-from-car-20261018/popup_nads.json",
        "captures/diagnostics-origin-20261021/powertrain/hal-registrations.json",
        "research/vehicle-diagnostics/washer-observation-modes-20261021.json",
        GD + "scenes/popup/popup_comp/scripts/popup_car_body.gd",
        GD + "common/global_define/hmi_def.gd"}
    mcu = (ROOT / MCU).read_bytes()
    raw_at = lambda address, size: mcu[address - 0x40000:address - 0x40000 + size]
    header = struct.unpack("<IIII", raw_at(0xD637C, 16))
    assert header == (0xE0026100, 0, 25, 0xDF884), header
    descriptors = []
    for index, (low, name, coords) in enumerate(FIELDS):
        address = 0xDF884 + index * 12
        descriptor = struct.unpack("<4BII", raw_at(address, 12))
        assert descriptor[:4] == coords
        fid, size, cache = struct.unpack("<III", raw_at(descriptor[5], 12))
        assert fid == 0x26100000 | low
        expected_handler = {0: 0x176966, 0x35: 0x18C85E, 0x38: 0x18C792}.get(low, 0x18A09C)
        assert descriptor[4] == expected_handler
        descriptors.append(dict(fid=f"{fid:08X}", name=name, descriptorVa=hex(address),
            bytes=raw_at(address, 12).hex(), coordinates=list(coords), handlerVa=hex(descriptor[4]),
            propertyVa=hex(descriptor[5]), propertyBytes=raw_at(descriptor[5], 12).hex(), cacheVa=hex(cache), size=size))
    anchors = []
    for path, address, size, label in [
        (CELLS, 0x183680, 0x668, "body reads, 13F display gate, direct opening publication, nine belt helper calls"),
        (CELLS, 0x185C60, 196, "raw belt 0 maps item1 only when raw power3 and online1; otherwise item0"),
        (CLIENT, 0x445958, 16, "row2 left client inversion"),
        (CLIENT, 0x4459A4, 16, "row2 middle client inversion"),
        (CLIENT, 0x4459F0, 16, "row3 middle client inversion"),
        (CLIENT, 0x445E4C, 16, "driver client inversion"),
        (CLIENT, 0x445E98, 16, "passenger client inversion"),
        (CLIENT, 0x445EE4, 16, "row2 right client inversion"),
        (CLIENT, 0x445F30, 16, "row3 left client inversion"),
        (CLIENT, 0x445F7C, 16, "row3 right client inversion"),
        (HAL, 0xE0C50, 20, "binary0/1 validation converter, no input mutation"),
    ]:
        anchors.append(dict(path=path, va=hex(address), bytes=elf_bytes((ROOT/path).read_bytes(), address, size).hex(), label=label))
    for address, size, label in [(0x176966, 134, "online arrival sets1 and rearms5000ms timeout"),
            (0x1769EC, 98, "online timeout sets0"),
            (0x196F94, 32, "scalar compare-and-write cache returns changed flag"),
            (0x18C792, 204, "voice type calls generic18A09C at18C80C"),
            (0x18C85E, 36, "voice frequency calls generic18A09C at18C87A")]:
        anchors.append(dict(path=MCU, va=hex(address), bytes=raw_at(address,size).hex(), label=label))
    line_anchors = []
    for path, needles in [
        (GD + "scenes/popup/popup_comp/scripts/popup_car_body.gd", ["168 :", "func _car_cap_state", "func _car_belt_state", "func _carbody_state_handle"]),
        (GD + "common/global_define/hmi_def.gd", ["WARN_ID_CAR_BODY_DOOR_SAFETY_BELT = 319"]),
        ("captures/firmware/pulled-from-car-20261018/popup_nads.json", ['"id": "13F"']),
        ("captures/cluster-native-20261018/tables/data-items.tsv", ["DATA_ITEM_ID_MAIN_BELT", "DATA_ITEM_ID_LEFT_FRONT_DOOR", "DATA_ITEM_ID_FRONT_HATCH_DOOR"]),
    ]:
        lines = (ROOT/path).read_text().splitlines()
        for needle in needles:
            matches = [(number, line) for number,line in enumerate(lines,1) if needle in line]
            assert len(matches) == 1, (path, needle)
            number, line = matches[0]
            line_anchors.append(dict(path=path, line=number, text=line))
    windows, all_decoded, streams = [], [], []
    for path in sorted(BASE.rglob("*.log")):
        text = path.read_text()
        if "[RawCanTurnProbe] START" not in text:
            continue
        rel = str(path.relative_to(ROOT))
        sources.add(rel)
        footer = list(DONE_RE.finditer(text))
        assert len(footer) == 1, rel
        done = {key:int(value) for key,value in footer[0].groupdict().items()}
        assert done["dropped"] == done["errors"] == done["queued"] == 0, rel
        label = re.search(r"START[^\n]* stream=(\w+)", text)
        label = label[1] if label else "bigdata"
        rows = []
        if label == "bigdata":
            for frame in parse_capture(text.splitlines()).frames:
                rows.append((frame.sequence, frame.time_ns, frame.address.can_id, frame.address.sub_id, frame.address.channel, frame.payload, None, None))
        else:
            for line_number,line in enumerate(text.splitlines(),1):
                match = BUFFER.search(line)
                if match:
                    sequence, time_ns, _, length, raw = match.groups()
                    envelope = bytes.fromhex(raw)
                    assert len(envelope) == int(length)
                    rows.append((int(sequence), int(time_ns), int.from_bytes(envelope[:4],"big"), envelope[4], envelope[5], envelope[10:], int.from_bytes(envelope[6:10],"big"), line_number))
        assert len(rows) == done["received"]
        assert [row[0] for row in rows] == list(range(1,len(rows)+1))
        selected = [row for row in rows if row[2:5] == (0x261,0,0)]
        streams.append(dict(path=rel, stream=label, received=len(rows), warningFrames=len(selected), done=done))
        if not selected:
            continue
        decoded = [{name:extract(row[5], coords) for low,name,coords in FIELDS if low} for row in selected]
        assert all(len(row[5]) == 8 for row in selected)
        all_decoded.extend(decoded)
        transitions = []
        previous = None
        for row,fields in zip(selected,decoded):
            state = {name:fields[name] for name in OPENINGS}
            if state != previous:
                transitions.append(dict(selectedFrameIndex=len([x for x in selected if x[0] < row[0]]), sourceLine=row[7], sequence=row[0],
                    timeNs=row[1], afterFirstSeconds=(row[1]-selected[0][1])/1e9, payload=row[5].hex(), openings=state))
                previous = state
        windows.append(dict(path=rel, count=len(selected), firstLine=selected[0][7], lastLine=selected[-1][7],
            fieldHistograms={name:histogram(fields[name] for fields in decoded) for _,name,_ in FIELDS if name != "online"},
            openingCombinations=histogram(",".join(name for name in OPENINGS if fields[name]) or "all_closed" for fields in decoded),
            noSupportedOpeningFrames=sum(not any(fields[name] for name in OPENINGS) for fields in decoded),
            allEightRawBeltsOneFrames=sum(all(fields[name] == 1 for name in BELTS) for fields in decoded),
            payloads=dict(sorted(collections.Counter(row[5].hex() for row in selected).items())),
            byteSumModulo256=histogram(sum(row[5]) % 256 for row in selected),
            consecutivePayloadChanges=sum(a[5] != b[5] for a,b in zip(selected,selected[1:])),
            hostDeliveryIntervalsSeconds=intervals([row[1] for row in selected],1e9),
            envelopeCounterIntervalsMs=intervals([row[6] for row in selected],1),
            openingTransitions=transitions))
    assert len(streams) == 20 and len(all_decoded) == 557
    assert all(fields["selected_warning"] == 0x13F and fields["hood"] == 1 for fields in all_decoded)
    registrations = json.loads((ROOT / "captures/diagnostics-origin-20261021/powertrain/hal-registrations.json").read_text())
    opening_registrations = [row for row in registrations if row.get("kind") == "get" and row.get("device") == 1007
        and 0x26100014 <= row["fid"] <= 0x26100019]
    assert len(opening_registrations) == 6
    assert all(row["converter"] == 0xE0C50 for row in opening_registrations)
    scalars = []
    wanted = {0x26100008} | {0x26100000 | low for low,_,_ in FIELDS if low in range(0x14,0x1A)}
    for phase in ("liquid-present", "empty", "refilled", "refilled-hood-open"):
        path = BASE / phase / "fids.tsv"
        rel = str(path.relative_to(ROOT))
        sources.add(rel)
        passes = collections.defaultdict(list)
        for number,line in enumerate(path.read_text().splitlines(),1):
            parts = line.split("\t")
            if parts[0] == "FID" and int(parts[3]) == 1007 and int(parts[4],16) in wanted:
                passes[int(parts[1])].append(dict(line=number, timeNs=int(parts[2]), fid=parts[4], value=parts[5]))
        for pass_number, reads in sorted(passes.items()):
            values = {int(read["fid"],16):int(read["value"],16) for read in reads}
            assert len(values) == 7 and values[0x26100008] == 0x13F
            opened = [name for low,name,_ in FIELDS if 0x14 <= low <= 0x19 and values[0x26100000 | low] == 1]
            assert opened
            scalars.append(dict(phase=phase, passNumber=pass_number, path=rel, reads=reads, openElements=opened,
                readSpanSeconds=(max(read["timeNs"] for read in reads)-min(read["timeNs"] for read in reads))/1e9))
    return dict(schema=1, date="2026-10-21", offlineOnly=True, vehicleCalls=0, physicalEmptyAccepted=True,
        scope="Saved N9 captures only; corpus consumer is not proven loaded by the observed cluster runtime.",
        sources=[dict(path=path, sha256=digest(path)) for path in sorted(sources)],
        descriptorTable=dict(path=MCU, loadBase="0x40000", va="0xd637c", bytes=raw_at(0xD637C,16).hex(),
            coordinateConvention="one-based byte, LSB0 bit, little-endian contiguous extraction", descriptors=descriptors),
        nativeByteAnchors=anchors, lineAnchors=line_anchors,
        scalarOpeningRegistrations=opening_registrations,
        streamCoverage=streams, rawWindows=windows, scalarSnapshots=scalars,
        conclusions=[
            "All557 recorded CAN261/sub0/channel0 frames select13F, have hood=1, trunk=0, and all8 directly described belt fields=1. No raw frame is an all-supported-openings-clear control.",
            "13F is explicitly the body/doors/hood/trunk/windows/seatbelts warning in stock resources, not an unidentified fallback code. Recorded openings are semantically consistent with that body presentation, but the upstream selection/priority rule is not recovered.",
            "Availability20 and empty179 have only hood open; liquid-present179 have hood plus LF and LR open; refill179 have hood plus RR, then LF and RR. Every first-cycle scalar pass also has at least one opening; with hood0 a door remains open. Scalar reads are not atomic and are not substituted for same-frame values.",
            "All8 raw belt fields1 produce item0 and client buckled1 in this implementation. They do not establish unbuckled warning, physical belt engagement, or occupant presence. First-row-middle FID26100034 is read by native but absent from this MCU descriptor list; byte6 bit4 is not assigned that name by this report.",
            "Voice frequency/type wire fields are0 in every frame. This alone neither proves no audible warning nor a silence/suppression cause.",
            "Payload bytes and door fields change while13F stays fixed; host envelopes/counters advance. This excludes an entirely identical frozen retained packet sequence at this observation point, not an upstream cached or default selected-ID field. No source-defined counter/checksum semantics are assigned to unnamed3C/40 fields.",
            "Repeated scalar GET13F may reflect cached value. The MCU online flag is arrival/timeout metadata, not a payload bit. The raw exporter can decimate in interval-only mode; actual N9 collection mode was not retained, so the557 exports do not prove uninterrupted original-bus warning history.",
            "The corpus supplies no body-clear control and no demonstrated upstream priority rule. It therefore cannot distinguish a legitimately persistent13F, stale selected-ID subfield, missed1DD interval, or absent1DD generation. None is a proven root cause of the accepted empty-reservoir observation.",
        ])


if __name__ == "__main__":
    result = main()
    if sys.argv[1:] == ["--report"]:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        assert not sys.argv[1:], "usage: check_washer_n9_body_warning_context.py [--report]"
        assert result == json.loads(REPORT.read_text()), "report differs from offline replay"
        print(json.dumps(dict(ok=True, streams=len(result["streamCoverage"]), rawFrames=sum(row["count"] for row in result["rawWindows"]),
            scalarPasses=len(result["scalarSnapshots"]), sources=len(result["sources"]))))
