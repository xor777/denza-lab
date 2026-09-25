#!/usr/bin/env python3
"""Offline qualification ledger, not a claim that arbitrary controls work.

Runs the pinned firmware under Unicorn, with synthetic nonfunctional payloads.
Every external effect is captured by NativeControl. No ADB, sockets or vehicle.
Unknown original-code dependencies are reported, never replaced by success.
"""
import argparse
import json
import struct
from pathlib import Path

from verify_native_control import NativeControl
from verify_native_roundtrip import HELPER, PACKET, WIRE
from verify_opaque_native import EXPECTED, INPUT, OBJ, HEAP, need


def terminal_case(source, subcommand, flag):
    m = NativeControl(source)
    stage = "request"
    try:
        frame, opaque = NativeControl(source).request(subcommand)
        m.receive(frame)
        need(len(m.vehicle_writes) == 1, "request not forwarded")
        need(m.vehicle_writes[0][6:] == opaque, "opaque body changed")
        stage = "mcu_result"
        reply = bytearray(m.vehicle_writes[0])
        reply[4] = flag
        m.u.mem_write(INPUT, bytes(reply))
        m.call(0x6039c, OBJ, 0x99000004, INPUT, len(reply))
        need(m.results == [(subcommand, flag, 0)], "result missing or changed")
        need(m.u.mem_read(OBJ + 0x254, 1) == b"\0", "native busy not cleared")
        return {"subcommand": subcommand, "flag": flag, "closure": True,
                "timers": m.timers, "heap_bytes": m.heap - HEAP}
    except Exception as error:
        return {"subcommand": subcommand, "flag": flag, "closure": False,
                "stage": stage, "boundary": str(error), "timers": m.timers}


def audit(source):
    # Representative controls plus timer32 and the path that request-only
    # sweeping missed. Extending this set is research, not an actuator allowlist.
    cases = [terminal_case(source, sub, flag)
             for sub in (0, 1, 3, 5, 9, 17, 39, 56, 255) for flag in (1, 2)]
    frame, _ = NativeControl(source).request()
    m = NativeControl(source)
    for index in range(100):
        m.receive(frame)
        reply = bytearray(m.vehicle_writes[-1])
        reply[4] = 1
        m.u.mem_write(INPUT, bytes(reply))
        m.call(0x6039c, OBJ, 0x99000004, INPUT, len(reply))
        need(len(m.results) == index + 1, "missing sequential native result")
    # The sleep path is an observed unresolved dependency, not permission to
    # synthesize an awake state to get around it.
    peer = NativeControl(source)
    peer.u.mem_write(INPUT, b"\1")
    peer.call(0x6e3b0, HELPER, 0, PACKET, 536, 254, INPUT, 1, 0)
    peer.call(0x6e858, HELPER, PACKET, WIRE, 1)
    asleep = NativeControl(source)
    asleep.u.mem_write(OBJ + 0x284, struct.pack("<I", 0))
    try:
        asleep.receive(peer.frames[-1])
        wake = {"closure": True}
    except Exception as error:
        wake = {"closure": False, "boundary": str(error)}
    return {
        "firmware_sha256": EXPECTED,
        "qualified_for_product": False,
        "terminal_cases": cases,
        "sequential_completed": len(m.results),
        "sequential_heap_bytes": m.heap - HEAP,
        "asleep_wake": wake,
        "scope": "Synthetic original-code paths only. Does not validate real command payloads, "
                 "the C worker's IPC, long-term allocation, hardware or cloud acceptance.",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("firmware", type=Path)
    print(json.dumps(audit(parser.parse_args().firmware), indent=2))
