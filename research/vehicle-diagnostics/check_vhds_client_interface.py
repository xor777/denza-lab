#!/usr/bin/env python3
"""Offline byte anchors for the small stock VHDS Binder interface library."""
import hashlib
import json
from pathlib import Path
import struct

from check_isg_stock_reader_policy import va_bytes

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "research/vehicle-diagnostics/vhds-client-interface-20261021.json"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    d = json.loads(REPORT.read_text())
    require(d["schema"] == "denza-lab.vhds-client-interface.v1", "schema")
    require(d["vehicleOperations"] == d["binderCalls"] == d["firmwareExecutions"] == 0, "offline scope")
    for source in d["sources"]:
        raw = (ROOT / source["path"]).read_bytes()
        require(len(raw) == source["bytes"] and hashlib.sha256(raw).hexdigest() == source["sha256"], "source " + source["path"])
    elf = (ROOT / d["library"]["path"]).read_bytes()
    require(len(elf) == 34864 and hashlib.sha256(elf).hexdigest() ==
            "42cb603668f8c4b262888fd6590765b2d5bbdce37ad1a5919fdb12d726c31f21", "exact libvhds")
    extents = {0x4330: 196, 0x4C90: 652, 0x5120: 280, 0x5240: 280,
               0x5360: 180, 0x5420: 156, 0x54C0: 224}
    require({int(r["address"], 16): r["bytes"] for r in d["ranges"]} == extents, "selected extents")
    for row in d["ranges"]:
        raw = va_bytes(elf, int(row["address"], 16), row["bytes"])
        require(hashlib.sha256(raw).hexdigest() == row["sha256"], "native range")
    offsets = va_bytes(elf, 0x3377, 5)
    require(offsets == bytes.fromhex("004D374315"), "five service transaction targets")
    targets = [0x4D10 + x * 4 for x in offsets]
    require(targets == [0x4D10, 0x4E44, 0x4DEC, 0x4E1C, 0x4D64], "dispatcher entries")
    for code, address in enumerate([0x51F0, 0x5310, 0x53CC, 0x5478, 0x5554], 1):
        require(struct.unpack("<I", va_bytes(elf, address, 4))[0] == 0x52800001 | (code << 5), "transaction code immediate")
        require(va_bytes(elf, address + 4, 8) == bytes.fromhex("E3031FAAE4031F2A"), "null reply and flags0")
    for row in d["anchors"]:
        raw = bytes.fromhex(row["hex"])
        require(va_bytes(elf, int(row["address"], 16), len(raw)) == raw, "instruction anchor")
    require([r["code"] for r in d["applicationTransactions"]] == [1, 2, 3, 4, 5], "application transactions")
    require(all(r["replyParcel"] is None and r["serverSerializesResult"] is False
                for r in d["applicationTransactions"]), "no claimed serialized readback")
    require(d["callback"]["code"] == 1 and d["callback"]["payload"] == "int32", "bounded listener payload")
    require(d["qualifiedTableReadback"] is False and d["qualifiedDictionaryReadback"] is False,
            "no read API promotion")
    migration = d["corpusRegistration"]
    old = (ROOT / migration["oldIndexSnapshot"]).read_bytes()
    require(hashlib.sha256(old).hexdigest() == migration["oldIndexSha256"] ==
            "2ba97c1167095a1eda48211e41da6acbc7b2dec1344f74d6ac6a5ab9e5a50794", "old HAL index bytes retained")
    require(migration["priorRoleReportIndexPin"] == "46d135b567ae9406ab0dd07530019eb251a92f556193ab644b2ef73bcc76a8ff"
            and migration["olderPinAlreadyMismatchedBeforeRefresh"] is True, "pre-existing historical index mismatch kept explicit")
    print("PASS libvhds_bytes=34864 native_ranges=7 application_transactions=5 listener_int32=1 qualified_readback=0")


if __name__ == "__main__":
    main()
