#!/usr/bin/env python3
"""Reproduce the narrow washer body-read source boundary, offline.

Reads retained local sources and prints JSON. No network, vehicle, subprocess,
firmware execution, extraction or file write. This verifies evidence coverage,
not the nonexistence of an undocumented ECU DID.
"""
import hashlib
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "research/vehicle-diagnostics/washer-body-read-20261021.json"


def read_json(path):
    return json.loads(path.read_text())


def main():
    report = read_json(REPORT)
    for source in report["sources"]:
        actual = hashlib.sha256((ROOT / source["path"]).read_bytes()).hexdigest()
        assert actual == source["sha256"], source["path"]
    assert report["qualifiedReadRequests"] == []
    assert report["qualifiedSensorPartNumbers"] == []
    assert report["qualifiedSensorWiring"] == []
    assert report["qualifiedWasherSensorDtcDefinitions"] == []

    stock = read_json(ROOT / report["sources"][0]["path"])
    system = next(s for s in stock["data"] if s["systemName"] == "雨刮洗涤系统")
    assert stock["data"].index(system) == 16
    cases = system["cases"]
    assert len(cases) == 6
    assert [c["caseName"] for c in cases[:2]] == ["主动控制前洗涤开", "主动控制前洗涤关"]
    for case in cases[:2]:
        assert [s["sid"] for s in case["steps"]] == ["10", "27", "2F"]
        assert {(s["canId"], s["canIdResp"]) for s in case["steps"]} == {("0x747", "0x74F")}
        assert case["steps"][-1]["did"] == "0x3362"
    for case in cases[2:]:
        assert [s["sid"] for s in case["steps"]] == ["10", "27", "2F"]
        assert {(s["canId"], s["canIdResp"]) for s in case["steps"]} == {("0x720", "0x728")}
        assert case["steps"][-1]["did"] == "0x3360"

    catalog = read_json(ROOT / report["sources"][1]["path"])
    raw_rows = read_json(ROOT / report["sources"][3]["path"])["data"]["Data"]
    body = []
    for token, row_id, route in (("F_ZCU", 1394, 0x722), ("R_ZCU", 1395, 0x747)):
        row = next(s for s in catalog["systems"] if s["token"] == token)
        raw = next(s for s in raw_rows if s["id"] == row_id)
        assert row["rowId"] == row_id
        assert row["capabilities"]["liveData"] is True
        assert raw["live_data"] == "√"
        assert raw["model"] == "Z9GT/Z9GT DM"
        assert row["observedFamilyRequestIds"] == [route]
        assert not any(k in raw for k in ("did", "offset", "scale", "unit", "live_data_records"))
        body.append(dict(token=token, rowId=row_id, request=f"{route:03X}", liveDataSupportOnly=True))
    right = next(s for s in catalog["systems"] if s["token"] == "R_ZCU")
    assert "右域控制器" in right["systemZh"]
    fields = read_json(ROOT / report["sources"][4]["path"])["data"]
    live_data = next(f for f in fields if f["id"] == 16)
    assert live_data["field"] == "live_data" and live_data["data_type"] == "SupportEnum"

    bounded = report["boundedLocalSearch"]["SpotInspectionAssets"]
    asset_root = ROOT / bounded["root"]
    candidates = sorted(p for p in asset_root.rglob("*") if p.is_file() and p.suffix in bounded["extensions"])
    pattern = re.compile(bounded["pattern"])
    matching = [str(p.relative_to(asset_root)) for p in candidates if pattern.search(p.read_text())]
    assert matching == bounded["matchingFiles"]
    for p in candidates:
        assert not re.search(r"洗涤液位|洗涤液不足|washer.{0,20}(?:level|sensor)|(?:level|sensor).{0,20}washer", p.read_text(), re.I)

    prior = read_json(ROOT / report["sources"][6]["path"])
    prior_body = [m for m in prior["modules"] if m["abbreviation"] in ("R_ZCU", "F_ZCU")]
    assert len(prior_body) == 2
    for module in prior_body:
        expected = report["priorDtcScanBoundary"][module["abbreviation"]]
        assert module["request"] == expected["request"]
        assert module["active"] == expected["active"] and module["history"] == expected["history"]
        replies = [e["answer"] for e in module["exchanges"]]
        assert replies[0] == expected["countReply"]
        if "listReply" in expected:
            assert replies[1] == expected["listReply"]

    front = read_json(ROOT / report["sources"][7]["path"])
    anchor = report["frontZcuIdentityAnchor"]
    assert front["requestAddressHex"] == anchor["request"]
    assert front["ecuId"] == anchor["ecuId"]
    assert front["responses"][0]["didHex"] == "F194"
    assert front["responses"][0]["dataHex"] == anchor["softwareCode"]
    assert [r["didHex"] for r in front["responses"][1:]] == anchor["configurationDids"]
    assert [r["dataHex"] for r in front["responses"][1:]] == anchor["configurationData"]
    assert anchor["washerLevelMeaning"] is None

    public = read_json(ROOT / report["publicPrimaryFollowup"]["evidence"])
    queries = sum(len(b["queries"]) for b in public["queryBatches"])
    assert queries == 28 and public["qualifiedLevelDefinitions"] == 0
    assert public["source"]["manualUrl"] == report["publicPrimaryFollowup"]["ownerManual"]["linkedUrl"]
    return dict(
        offlineOnly=True, vehicleCalls=0, networkCalls=0, sourceHashesVerified=len(report["sources"]),
        qualifiedReadRequests=[], washerLevelDid=None, sensorOwner=None, decoder=None,
        factoryCases=len(cases), washerActuatorRoute="747/74F", washerControlIdentifier="3362",
        controlDoesNotDefineReadIdentifier=True, rightZoneIsNotRear=True,
        publicCatalogRows=body, publicLiveDataFieldType=live_data["data_type"],
        boundedAssetFilesChecked=len(candidates), boundedAssetMatches=matching,
        publicQueryCount=queries, publicOwnerManualCnDmApplicability=False,
        qualification="no_source_qualified_washer_level_read_request",
        limitation="Evidence is finite; no assertion that no undocumented level DID exists.")


if __name__ == "__main__":
    print(json.dumps(main(), ensure_ascii=False, indent=2))
