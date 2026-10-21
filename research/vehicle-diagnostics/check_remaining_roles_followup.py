#!/usr/bin/env python3
"""Verify the finite, offline remaining-child-role audit against retained inputs.

Does not execute firmware, contact a service, invoke ADB, or send diagnostic reads.
"""

import collections
import hashlib
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "research/vehicle-diagnostics/remaining-role-followup-20261021.json"
TARGETS = [
    (169, "723/72B", "F395/F394", [1406, 1407]),
    (170, "723/72B", "F495/F494", [1406, 1407]),
    (137, "747/74F", "F645/F644", [1390, 1391, 1392]),
    (138, "747/74F", "F655/F654", [1390, 1391, 1392]),
    (140, "747/74F", "FB25/FB24", [1390, 1391, 1392]),
    (5, "720/728", "C295/C294", [1455]),
    (10, "720/728", "C395/C394", [1397]),
    (7, "720/728", "C495/C494", [1398]),
    (9, "720/728", "C595/C594", [1396]),
]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path):
    return json.loads(path.read_text())


def collect_facts(inputs):
    paths = {key: ROOT / value["path"] for key, value in inputs.items()}
    factory = load(paths["factory"])["ecus"]
    cache = load(paths["cache"])["ecuInfo"]
    catalog = load(paths["catalog"])
    previous = load(paths["previousRoleGap"])
    assert len(factory) == len(cache) == 225
    assert catalog["model"] == "Z9GT/Z9GT DM"
    assert catalog["scannerVersion"] == "6.61"

    rows = {}
    for page in (1, 2):
        for index, row in enumerate(load(paths[f"rawCatalogPage{page}"])["data"]["Data"]):
            rows[row["id"]] = (page, index, row)
    used_row_ids = sorted({i for _, _, _, ids in TARGETS for i in ids})
    catalog_by_id = {row["rowId"]: row for row in catalog["systems"]}
    exact_rows = []
    for row_id in used_row_ids:
        page, index, row = rows[row_id]
        assert row["brand"] == "腾势汽车"
        assert row["model"] == "Z9GT/Z9GT DM" and row["version"] == "V6.61"
        assert row["system"] == catalog_by_id[row_id]["systemZh"]
        assert row["read_ecu_information"] == "√"
        assert len(row) == 33
        assert row["actuation_test"] == row["special_functions"] == "×"
        assert row["part"] is row["system_type"] is row["ecu_configuration"] is None
        exact_rows.append({"rowId": row_id, "page": page,
                           "pointer": f"/data/Data/{index}", "rawRow": row})

    targets = []
    prev_by_index = {t["factoryRecordIndex"]: t for t in previous["targets"]}
    for index, route, did_pair, row_ids in TARGETS:
        f, c = factory[index], cache[index]
        assert f["ecuId"] == c["ecuId"] == prev_by_index[index]["factoryEcuId"]
        assert f["ecuDiaId"] == route and f["ecuDId"] == did_pair
        assert c["ecuSoftCode"] == prev_by_index[index]["cachedSoftwareCode"]
        assert re.fullmatch(r"[0-9A-F]{18}", c["ecuSoftCode"])
        matches = [{"index": i, "ecuId": item["ecuId"]}
                   for i, item in enumerate(cache)
                   if item.get("ecuSoftCode") == c["ecuSoftCode"]]
        assert matches == [{"index": index, "ecuId": f["ecuId"]}]
        targets.append({"factoryRecordIndex": index, "factoryPointer": f"/ecus/{index}",
                        "cachePointer": f"/ecuInfo/{index}", "factoryRecord": f,
                        "cachedRecord": c, "exactCodeMatchesInAll225CacheRows": matches,
                        "candidateRoleRowIds": row_ids, "qualifiedNamedSubrole": None})

    script = load(paths["stockFunctionMap"])
    by_route = collections.defaultdict(list)
    all_dids = set()
    for system_index, system in enumerate(script["data"]):
        for case_index, case in enumerate(system.get("cases", [])):
            case_routes = sorted({int(step["canId"], 16)
                                  for step in case.get("steps", []) if step.get("canId")})
            dids = sorted({int(step["did"], 16)
                           for step in case.get("steps", []) if step.get("did")})
            all_dids.update(dids)
            for route in case_routes:
                by_route[route].append({"pointer": f"/data/{system_index}/cases/{case_index}",
                                        "systemName": system.get("systemName"),
                                        "caseName": case.get("caseName"),
                                        "dids": [f"{did:04X}" for did in dids]})
    code_dids = {int(pair.split("/")[1], 16) for _, _, pair, _ in TARGETS}
    assert not code_dids.intersection(all_dids)
    assert len(by_route[0x720]) == 96 and len(by_route[0x747]) == 47
    assert 0x723 not in by_route
    function_routes = [{"requestId": f"{route:X}", "caseCount": len(cases),
                        "systemCaseCounts": dict(sorted(collections.Counter(
                            c["systemName"] for c in cases).items())),
                        "allCaseDids": sorted({did for case in cases for did in case["dids"]})}
                       for route, cases in sorted(by_route.items())]
    attachments = load(paths["stockLowPower"])["data"]["attach"]
    attachment_names = [{"pointer": f"/data/attach/{i}",
                         "ecuAbbrName": item["ecuAbbrName"],
                         "ecuName": item["ecuName"], "bus": item["bus"],
                         "can": item["can"]} for i, item in enumerate(attachments)]
    assert attachment_names[0]["ecuAbbrName"] == "LA_BCM"
    assert attachment_names[0]["can"]["reqCanId"] == 0x720

    devtools = paths["devtoolsVersionManager"].read_text()
    request_ids = [int(x) for x in re.findall(r"arrayList.add\((\d+)\)", devtools)]
    mapper = devtools.split("public final void Y(byte[] bArr) {", 1)[1].split(
        "public final byte[] v(int i)", 1)[0]
    response_ids = [int(x) for x in re.findall(r"i [!=]= (\d+)", mapper)]
    assert not {0x720, 0x723, 0x747}.intersection(request_ids)
    assert not {0x728, 0x72B, 0x74F}.intersection(response_ids)

    version = paths["frameworkVersionDevice"].read_text()
    literals = ["private static int mDeviceType = 1027;",
                "int result = super.set(mDeviceType, 626182, 626182);",
                "byte[] vbyte = super.getBuffer(mDeviceType, 458157);",
                'String version = new String(vbyte, "US-ASCII");']
    anchors = [{"line": i, "literal": line.strip()}
               for i, line in enumerate(version.splitlines(), 1)
               if line.strip() in literals]
    assert all(any(a["literal"] == literal for a in anchors) for literal in literals)
    return {"factoryRows": len(factory), "cacheRows": len(cache),
            "targetCount": len(targets), "newQualifiedNamedSubroles": 0,
            "targets": targets, "exactRawCatalogRows": exact_rows,
            "catalogFieldsPerTargetRow": 33,
            "catalogNamespaceJoinColumnsPresent": [],
            "stockFunctionMap": {"routes": function_routes,
                                 "targetCodeDidsPresent": [],
                                 "scope": "Bundled script, vehicle/software applicability unqualified; no execution"},
            "stockLowPowerNamedRoutes": attachment_names,
            "devtoolsVersionManager": {
                "requestIds": [f"{x:X}" for x in request_ids],
                "responseIds": [f"{x:X}" for x in response_ids],
                "targetParentRouteIntersection": []},
            "genericInstrumentVersion": {
                "deviceId": 1027, "getBufferFidDecimal": 458157,
                "querySetterFidDecimal": 626182, "anchors": anchors,
                "childNamespaceSelector": None,
                "scope": "Generic instrument version string, no MCU/audio child-name or software-code join"}}


def main():
    report = load(REPORT)
    for value in report["inputs"].values():
        path = ROOT / value["path"]
        assert sha(path) == value["sha256"], f"Changed input: {path}"
    facts = collect_facts(report["inputs"])
    assert facts == report["verifiedFacts"], "Derived observations differ"
    raw = report["rawAudit"]
    assert sha(ROOT / raw["path"]) == raw["sha256"]
    assert load(ROOT / raw["path"]) == facts
    case_capture = report["rawFunctionCases"]
    assert sha(ROOT / case_capture["path"]) == case_capture["sha256"]
    captured_cases = load(ROOT / case_capture["path"])
    assert len(captured_cases) == len(facts["stockFunctionMap"]["routes"])
    for captured, route in zip(captured_cases, facts["stockFunctionMap"]["routes"]):
        assert captured["requestId"] == route["requestId"]
        assert len(captured["cases"]) == route["caseCount"]
        assert dict(sorted(collections.Counter(c["systemName"] for c in captured["cases"]).items())) == route["systemCaseCounts"]
        assert sorted({did for c in captured["cases"] for did in c["dids"]}) == route["allCaseDids"]
    assert report["actions"]["vehicleRequests"] == 0
    assert report["actions"]["networkRequests"] == 0
    assert report["qualifiedRoleResolvingMetadataReads"] == []
    print("PASS: 9 exact factory/cache joins; 9 raw catalog rows; all software codes unique in 225 cache rows")
    print("PASS: bounded stock function/version source checks; 0 newly bound child roles; no new reads proposed")


if __name__ == "__main__":
    main()
