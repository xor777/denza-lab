"""Offline, pinned-image disassembly evidence. Does not execute MCU code."""
from pathlib import Path
import hashlib
import json
import struct
import sys
from importlib.metadata import version
from pypcode import Context

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "captures/diagnostics-discovery-20261018/ecu-map-followup"
IMAGE = ROOT / "captures/firmware/ivi-34.1.33.2605218.1/mcu/app.bin"
SHA = "6c277d1eb819b765d997554fc727835314b9477cae414aa806ea7cf78eda4d02"
BASE = 0x40000
GP = 0xFEBDD868
# Explicit starts/ends verified from short, instruction-aligned ranges; some
# are basic blocks rather than whole functions. Never decode the entire image.
RANGES = [
    ("version-fid-callback", 0x16BD80, 0x16BDAA),
    ("version-input", 0x151F68, 0x1520BC),
    ("version-timeout-callbacks", 0x151EA8, 0x151F68),
    ("version-completion", 0x151AD2, 0x151BDC),
    ("version-transcript", 0x151BDC, 0x151CDE),
    ("version-self", 0x151CDE, 0x151DA6),
    ("version-enqueue", 0x151DA6, 0x151E2C),
    ("generic-fid-callback", 0x16C840, 0x16C86A),
    ("generic-input-dispatch", 0x15147E, 0x15154C),
    ("generic-input-header", 0x150888, 0x150900),
    ("generic-nonself-transmit", 0x1511FA, 0x15131C),
    ("generic-response-expected", 0x150528, 0x15055C),
    ("generic-receiver-filter-and-single-frame", 0x1528C8, 0x152A2C),
    ("generic-receiver-return", 0x152FDC, 0x152FF6),
    ("generic-completion", 0x15059E, 0x150726),
    ("vhds-fid-callback", 0x16D85A, 0x16D884),
    ("vhds-input", 0x14C468, 0x14C83C),
    ("vhds-receiver-filter", 0x14CBC6, 0x14CC0C),
    ("can-enqueue", 0x169A6E, 0x169B54),
    ("can-queue-drain", 0x169B54, 0x169C72),
    ("rx-dispatch-basic-block", 0x170828, 0x17084C),
]
DISPATCH = [(0xCD954, 0xAA000032, 0x16BD80),
            (0xCD9D8, 0xAA00010A, 0x16C840),
            (0xCDAF8, 0xAA000167, 0x16D85A)]
DESCRIPTORS = [(0xA6404, 0x99000053, 0xEC, 0xFEEFBC50),
               (0xA6488, 0x9900010A, 0xF0, 0xFEEFBE31),
               (0xA6680, 0x99000167, 0x40, 0xFEEFB907)]


def main():
    assert sys.version_info >= (3, 12), "pypcode 4.0.1 requires Python >=3.12"
    image = IMAGE.read_bytes()
    assert hashlib.sha256(image).hexdigest() == SHA
    assert len(image) == 1933312
    assert version("pypcode") == "4.0.1", "Use the reviewed v850e3v5 decoder"
    OUT.mkdir(parents=True, exist_ok=True)
    ctx = Context("V850:LE:32:v850e3v5")
    records = []
    text = []
    for name, start, end in RANGES:
        raw = image[start - BASE:end - BASE]
        instructions = ctx.disassemble(raw, base_address=start).instructions
        assert instructions[0].addr.offset == start
        assert instructions[-1].addr.offset + instructions[-1].length == end
        rows = []
        text.append(f"; {name}: VA {start:#x}..{end:#x} (end exclusive)")
        for ins in instructions:
            va = ins.addr.offset
            body = {"va": hex(va), "fileOffset": hex(va - BASE),
                    "bytes": image[va - BASE:va - BASE + ins.length].hex(),
                    "mnemonic": ins.mnem, "operands": ins.body}
            rows.append(body)
            text.append(f"{va:08x}: {body['bytes']:<12} {ins.mnem:10s} {ins.body}")
        records.append({"name": name, "startVA": hex(start), "endExclusiveVA": hex(end),
                        "sha256": hashlib.sha256(raw).hexdigest(), "instructions": rows})
        text.append("")
    dispatch = []
    for va, fid, callback in DISPATCH:
        words = struct.unpack_from("<III", image, va - BASE)
        assert words == (fid, callback, 0)
        dispatch.append({"va": hex(va), "fileOffset": hex(va - BASE),
                         "fid": hex(fid), "callbackVA": hex(callback), "thirdWord": 0})
    descriptors = []
    for va, fid, size, ram in DESCRIPTORS:
        assert struct.unpack_from("<III", image, va - BASE) == (fid, size, ram)
        descriptors.append({"va": hex(va), "fileOffset": hex(va - BASE),
                            "fid": hex(fid), "size": size, "bufferRAM": hex(ram)})
    evidence = {"schema": 1, "offlineOnly": True, "image": str(IMAGE.relative_to(ROOT)),
                "imageSHA256": SHA, "imageBytes": len(image), "loadBase": hex(BASE),
                "gp": hex(GP), "decoder": "pypcode 4.0.1 V850:LE:32:v850e3v5",
                "applicability": "owner-supplied MCU package; installed image not proven",
                "profile": None, "eligibleForLiveRequests": False,
                "dispatchRows": dispatch, "outputDescriptors": descriptors, "ranges": records}
    evidence["routes"] = [
        {"name": "version-and-software-code", "inputFID": "0xaa000032",
         "outputFID": "0x99000053", "inputHandlerVA": "0x151f68",
         "requestId": {"source": "input bytes 0..1, big endian", "ram": "0xfebc9900"},
         "responseId": {"source": "input bytes 2..3, big endian", "ram": "0xfebc9902"},
         "did": {"source": "input bytes 4..5, big endian", "ram": "0xfebc9904"},
         "firstNonselfRequest": "10 01", "internalFrameSelector": 0,
         "canEnqueueVA": "0x169a6e", "selfRequestReply": ["0x741", "0x749"],
         "selfDIDs": ["0xf194", "0xf195"], "addressNameLookupObserved": False},
        {"name": "mqtt-eol-generic", "inputFID": "0xaa00010a",
         "outputFID": "0x9900010a", "inputHandlerVA": "0x15147e",
         "normalInputVA": "0x150888", "fragmentedInputVA": "0x15131c",
         "requestId": {"source": "input bytes 1..2, big endian", "ram": "0xfebc926e"},
         "payload": "normal input byte 3 = length; bytes 4 onward = service and data",
         "responseId": {"source": "requestId + 8", "filterVA": "0x1528f0"},
         "activeRAM": "0xfeeee5f2", "internalFrameSelector": 0,
         "canEnqueueVA": "0x169a6e", "selfRequestId": "0x741",
         "nonselfServiceWhitelistObserved": False, "addressNameLookupObserved": False,
         "functional7DF": {"responseExpected": False,
             "helperVA": "0x150528", "explicitBranchVA": "0x150536",
             "normalInputClearsActiveVA": "0x1512f0",
             "rxRequiresActiveVA": "0x1528e4",
             "rxAcceptsOnlyRequestPlusEightVA": "0x1528f0",
             "collectAll7E8Through7EFSupportedByThisPath": False}},
        {"name": "vhds-twoc", "inputFID": "0xaa000167", "outputFID": "0x99000167",
         "inputHandlerVA": "0x14c468",
         "requestId": {"source": "input bytes 1..2, big endian", "ram": "0xfeeeda10"},
         "responseId": {"source": "requestId + 8", "filterVA": "0x14cbde"},
         "receiverVA": "0x14cc0c", "addressNameLookupObserved": False},
    ]
    evidence["limits"] = [
        "No installed MCU hash checked; this is the owner's OTA binary",
        "No ECU address/name dictionary recovered by the three traced paths",
        "No assertion that a table cannot exist elsewhere in the image",
        "Internal selector 0 is not assigned a physical CAN bus label here",
        "Legacy fault-FID support and Android access permission not proved by MCU handlers",
    ]
    (OUT / "mcu-route-input-trace.json").write_text(json.dumps(evidence, indent=2) + "\n")
    (OUT / "mcu-route-input-trace.asm").write_text("\n".join(text))
    print(f"Verified image, {len(dispatch)} FID dispatch rows, {len(descriptors)} outputs, "
          f"{len(records)} bounded ranges / {sum(len(r['instructions']) for r in records)} instructions")


if __name__ == "__main__":
    main()
