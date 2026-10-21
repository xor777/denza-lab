#!/usr/bin/env python3
"""Verify frozen, offline remaining-role and brake-route evidence.

Default uses only Python's standard library and checks source hashes, raw MCU
bytes, instruction continuity/anchors and retained request/reply transcripts.
--redisassemble also requires pypcode 4.0.1 and V850:LE:32:v850e3v5.
Neither mode executes firmware, contacts a service, nor invokes ADB.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct


ROOT = Path(__file__).resolve().parents[2]
REPORT = Path(__file__).with_name("remaining-ecu-identity-20261021.json")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def load(path):
    return json.loads(path.read_text())


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def decode_frame(value):
    raw = bytes.fromhex(value)
    require(raw[0] == 10 and raw[4:7] == b"\x01\x01\x01", "owner10 success, single gateway fragment")
    require(raw[3] == len(raw) - 7, "declared UDS payload length")
    return int.from_bytes(raw[1:3], "big"), raw[7:]


def facts(report):
    paths = {key: ROOT / row["path"] for key, row in report["sources"].items()}
    factory, cache = load(paths["factory"])["ecus"], load(paths["cache"])["ecuInfo"]
    gaps = load(paths["previousRoleGaps"])["targets"]
    require(len(factory) == len(cache) == 225 and len(gaps) == 9, "finite source populations")
    catalog = {}
    for key in ("catalogPage1", "catalogPage2"):
        for row in load(paths[key])["data"]["Data"]:
            require(row["brand"] == "腾势汽车" and row["model"] == "Z9GT/Z9GT DM"
                    and row["version"] == "V6.61", "exact model catalog scope")
            catalog[row["id"]] = row
    require(len(catalog) == 172, "catalog row population")
    targets, collisions = [], []
    for old in gaps:
        index = old["factoryRecordIndex"]
        f, c = factory[index], cache[index]
        require(f["ecuId"] == c["ecuId"] == old["factoryEcuId"], "factory/cache identity")
        require(f["ecuDiaId"] == old["route"] and f["ecuDId"] == old["versionCodeDidPair"], "route and DID")
        require(c["ecuSoftCode"] == old["cachedSoftwareCode"], "cached code")
        did = f["ecuDId"].split("/")[1]
        matches = [{"factoryRecordIndex": i, "ecuId": item["ecuId"], "route": item["ecuDiaId"]}
                   for i, item in enumerate(factory) if item["ecuDId"].split("/")[1] == did]
        if len(matches) > 1:
            collisions.append({"did": did, "rows": matches})
        candidates = [{"rowId": row_id, "systemZh": catalog[row_id]["system"]}
                      for row_id in old["candidateNamedRoleRowIds"]]
        targets.append({"factoryRecordIndex": index, "ecuId": f["ecuId"], "route": f["ecuDiaId"],
                        "softwareCodeDid": did, "cachedSoftwareCode": c["ecuSoftCode"],
                        "candidateNamedRoles": candidates, "qualifiedNamedSubrole": None})
    require([(c["did"], len(c["rows"])) for c in collisions] == [("F394", 2), ("F494", 2)], "two exact route-scoped DID collisions")

    # These are historical saved responses, not new target operations.
    brake_text, esp_text = paths["brakeRaw"].read_text(), paths["espRaw"].read_text()
    brake = []
    for request, frame in re.findall(r"REQUEST step=\d+ ([0-9A-F]+).*?FRAME step=\d+[^\n]* raw=([0-9A-F]+)", brake_text, re.S):
        request_raw = bytes.fromhex(request)
        if int.from_bytes(request_raw[1:3], "big") != 0x7E5:
            continue
        response_id, payload = decode_frame(frame)
        require(response_id == 0x7ED and request_raw[3] == len(request_raw) - 4, "OBD route pair and request length")
        if request_raw[4:] in (b"\x09\x04", b"\x09\x0a"):
            require(payload[:3] == bytes((0x49, request_raw[5], 1)), "OBD Mode09 item")
            brake.append({"requestIdHex": "7E5", "responseIdHex": "7ED", "requestHex": request_raw[4:].hex().upper(),
                          "payloadHex": payload.hex().upper(), "text": payload[3:].rstrip(b"\0").decode("ascii")})
    require([row["text"] for row in brake] == ["4000825042253153", "BSCM-BrakeSystem"], "retained brake names")
    esp_frame = re.search(r"FRAME step=1[^\n]* raw=([0-9A-F]+)", esp_text).group(1)
    esp_response_id, esp_payload = decode_frame(esp_frame)
    require("REQUEST step=1 0A07820322F194" in esp_text and esp_response_id == 0x78A
            and esp_payload == bytes.fromhex("62F19432330011DF0B000101"), "retained ESP identification")
    aliases = [{"factoryRecordIndex": i, "ecuId": f["ecuId"], "route": f["ecuDiaId"],
                "softwareCodeDid": "F194", "cachedSoftwareCode": cache[i]["ecuSoftCode"]}
               for i, f in enumerate(factory) if f["ecuDiaId"] == "782/78A"]
    require(len(aliases) == 3 and all(a["cachedSoftwareCode"] == esp_payload[3:].hex().upper() for a in aliases), "three retained alias candidates")
    require(not any(f["route"].get("requestId") == 0x7E5 for f in factory), "no factory7E5 row")

    children = []
    child_text = paths["childrenRaw"].read_text()
    child_report = load(paths["childrenReport"])
    for row in child_report["identities"]:
        request_match = re.search(r"REQUEST step=" + str(row["step"]) + r" ecu=([^ ]+) t_ns=\d+ raw=([0-9A-F]+)", child_text)
        frame_match = re.search(r"FRAME step=" + str(row["step"]) + r" t_ns=\d+ raw=([0-9A-F]+)", child_text)
        require(request_match and frame_match, "retained child request/frame")
        child_id, request = request_match.groups()
        raw = bytes.fromhex(request)
        response_id, payload = decode_frame(frame_match.group(1))
        req, did = int.from_bytes(raw[1:3], "big"), raw[5:7].hex().upper()
        require(raw[3:5] == b"\x03\x22" and payload[:3] == b"\x62" + raw[5:7], "child code read only")
        code = payload[3:].hex().upper()
        require(child_id == row["ecuId"] and code == row["cachedCodeHex"] == row["softwareCodeHex"]
                and req + 8 == response_id and did == row["didHex"], "child identity/cache framing")
        f = next(item for item in factory if item["ecuId"] == child_id)
        c = next(item for item in cache if item["ecuId"] == child_id)
        require(f["ecuDiaId"] == f"{req:X}/{response_id:X}" and f["ecuDId"].split("/")[1] == did
                and c["ecuSoftCode"] == code, "child factory route identity")
        children.append({"ecuId": child_id, "requestIdHex": f"{req:X}", "responseIdHex": f"{response_id:X}",
                         "softwareCodeDid": did, "softwareCode": code, "physicalBus": None,
                         "physicalUnitIdentity": None, "childDtcMemoryQualified": False})
    require(len(children) == 8 and len({row["requestIdHex"] for row in children}) == 4, "eight identities behind four parents")

    # Arithmetic projection of the byte-checked assignment and masking below;
    # this is not firmware execution or proof of hardware transmission.
    encoded = [((request | 0x40000000) & 0x1FFFFFFF) | 0x20000000 for request in range(0x800)]
    require(len(set(encoded)) == 0x800 and all((word & 0x7FF) == i for i, word in enumerate(encoded)), "11-bit ID preservation model")
    return {"factoryCandidates": 225, "exactModelCatalogRows": 172, "remainingNamedRoles": targets,
            "sameDidAtDifferentFactoryRoutes": collisions, "brakeObdIdentity": brake,
            "espIdentity": {"requestIdHex": "782", "responseIdHex": "78A", "softwareCode": esp_payload[3:].hex().upper(),
                            "factoryAliases": aliases}, "brakePhysicalRelationship": None,
            "existingQualifiedChildIdentities": children,
            "standardIdEncodingProjection": {"testedNumericIds": 2048, "distinctEncodedWords": 2048,
                                             "7E5": hex(encoded[0x7E5]), "782": hex(encoded[0x782]),
                                             "sameEncodedId": encoded[0x7E5] == encoded[0x782]}}


def check(redisassemble=False):
    report = load(REPORT)
    require(report["offlineOnly"] and all(v == 0 for v in report["operations"].values()), "offline scope")
    require(sha(Path(__file__).read_bytes()) == report["checker"]["sha256"], "frozen checker identity")
    for row in list(report["sources"].values()) + list(report["artifacts"].values()):
        data = (ROOT / row["path"]).read_bytes()
        require(len(data) == row["bytes"] and sha(data) == row["sha256"], "frozen source drift: " + row["path"])
    image = (ROOT / report["image"]["path"]).read_bytes()
    require(len(image) == report["image"]["bytes"] and sha(image) == report["image"]["sha256"], "original MCU identity")
    base = int(report["image"]["loadBase"], 0)
    ranges = load(ROOT / report["artifacts"]["nativeRanges"]["path"])
    rows = {}
    instruction_count = 0
    context = None
    if redisassemble:
        from importlib.metadata import version
        from pypcode import Context
        require(version("pypcode") == "4.0.1", "reviewed extended RH850 decoder")
        context = Context("V850:LE:32:v850e3v5")
    for region in ranges:
        start, end = int(region["startVA"], 0), int(region["endExclusiveVA"], 0)
        data = image[start - base:end - base]
        require(sha(data) == region["sha256"], "range digest")
        cursor = start
        for row in region["instructions"]:
            address, raw = int(row["va"], 0), bytes.fromhex(row["bytes"])
            require(cursor == address and data[address - start:address - start + len(raw)] == raw, "instruction bytes/continuity")
            require(int(row["fileOffset"], 0) == address - base and address not in rows, "address mapping and no overlap")
            rows[address] = row
            cursor += len(raw)
            instruction_count += 1
        require(cursor == end, "exact instruction-aligned extent")
        if context:
            decoded = context.disassemble(data, base_address=start).instructions
            actual = [(i.addr.offset, i.mnem, i.body) for i in decoded]
            expected = [(int(i["va"], 0), i["mnemonic"], i["operands"]) for i in region["instructions"]]
            require(actual == expected, "independent decode of retained ranges")
    for anchor in report["nativeAnchors"]:
        row = rows[int(anchor["va"], 0)]
        require([row["mnemonic"], row["operands"]] == anchor["instruction"], "native anchor " + anchor["va"])
    require(struct.unpack_from("<III", image, 0xCD9D8 - base) == (0xAA00010A, 0x16C840, 0), "MCU generic dispatch")
    require(struct.unpack_from("<III", image, 0xA6488 - base) == (0x9900010A, 240, 0xFEEFBE31), "MCU callback descriptor")
    require(facts(report) == report["verifiedFacts"], "facts replay")
    require(instruction_count == report["nativeInstructionCount"], "reported instruction count")
    print(f"PASS: {len(report['sources'])} frozen sources, {len(ranges)} native ranges/{instruction_count} instructions; "
          "2048 numeric ID projections; 9 roles remain unbound; 8 historical child identities; brake physical relationship unknown")
    if context:
        print("PASS: pypcode4.0.1/v850e3v5 independently reproduced all retained instructions")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--redisassemble", action="store_true")
    args = parser.parse_args()
    check(args.redisassemble)
