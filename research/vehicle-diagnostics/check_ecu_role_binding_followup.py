#!/usr/bin/env python3
"""Reproduce the finite offline ECU role-binding follow-up from pinned sources.

No vehicle, IPC, network, firmware execution, extraction, or mutable corpus index.
The Java anchors constrain a source-reading result; this does not execute Android.
"""
import hashlib
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "research/vehicle-diagnostics/ecu-role-binding-followup-20261021.json"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read_sources(report):
    sources = {}
    for key, receipt in report["sources"].items():
        path = ROOT / receipt["path"]
        data = path.read_bytes()
        assert len(data) == receipt["bytes"], (key, "size")
        assert sha(data) == receipt["sha256"], (key, "sha256")
        sources[key] = data
    return sources


def body(text, marker):
    """Balanced Java block after one exact declaration, excluding nested braces."""
    assert text.count(marker) == 1, marker
    start = text.index("{", text.index(marker))
    depth = 1
    for end in range(start + 1, len(text)):
        depth += (text[end] == "{") - (text[end] == "}")
        if depth == 0:
            return text[start + 1:end]
    raise AssertionError((marker, "unclosed"))


def direct_string_fields(text, declaration):
    block = body(text, declaration)
    # Models have nested Parcelable classes; retain only fields at direct depth.
    fields, depth = [], 0
    for line in block.splitlines():
        if depth == 0:
            match = re.fullmatch(r"\s*public String (\w+);\s*", line)
            if match:
                fields.append(match.group(1))
        depth += line.count("{") - line.count("}")
    return fields


def derive(sources):
    text = lambda key: sources[key].decode("utf-8")
    baseline = json.loads(sources["baseline"])
    factory = json.loads(sources["factory"])["ecus"]
    cache = json.loads(sources["cacheTargets"])["rows"]
    roles = baseline["verifiedFacts"]["remainingNamedRoles"]
    config = json.loads(sources["smmConfig"])
    assert len(factory) == 225 and len(roles) == 9 and len(config) == 7
    assert {r["factoryRecordIndex"] for r in roles} == {5, 7, 9, 10, 137, 138, 140, 169, 170}

    suffix_match = "ecuListBean.ecuId.endsWith(smmModuleBean.ecuIdSuffix)"
    factory_method = body(text("diagnoseFactory"), "public static BaseDiagnostic getDiagnostic(")
    assert suffix_match in factory_method
    assert 'case "can":' in factory_method and 'case "canfd":' in factory_method
    assert "new CanDiagnostic()" in factory_method
    assert "new SmmDiagnostic()" in factory_method
    assert "VehicleOTAUtil.isMediaEcuById(ecuListBean.ecuId)" in factory_method
    get_list = body(text("vehicleUtil"), "public static List<SmmModuleBean> getSMMModuleList()")
    assert "LocalFileUtil.getStringFormRaw(R.raw.smm_module_config)" in get_list
    assert "JSONUtil.parseArray(json, SmmModuleBean.class)" in get_list
    get_new_list = body(text("newSmmHelper"), "public static List<NewSmmModuleBean> getNewSmmModuleList()")
    assert "LocalFileUtil.getStringFormRaw(R.raw.smm_module_config)" in get_new_list
    assert "JSONUtil.parseArray(json, NewSmmModuleBean.class)" in get_new_list
    media_suffix = re.search(r'MEDIA_ECU_ID_SUFFIX = "([^"]+)";', text("vehicleUtil")).group(1)
    assert media_suffix == "_Media_BYD_00200001"
    matches = []
    for item in config:
        joined = []
        for index, ecu in enumerate(factory):
            if ecu["ecuId"].endswith(item["ecuIdSuffix"]):
                joined.append({"factoryRecordIndex": index, "ecuId": ecu["ecuId"],
                               "route": ecu["ecuDiaId"], "versionCodeDids": ecu["ecuDId"]})
        matches.append({"name": item["name"], "ecuIdSuffix": item["ecuIdSuffix"], "factoryMatches": joined})

    role_rows = []
    for role, current in zip(roles, cache):
        index = role["factoryRecordIndex"]
        ecu = factory[index]
        assert current["cachePointer"] == f"/ecuInfo/{index}"
        value = current["value"]
        assert ecu["ecuId"] == role["ecuId"] == value["ecuId"]
        assert ecu["ecuDiaId"] == role["route"]
        assert ecu["ecuDId"].split("/")[1] == role["softwareCodeDid"]
        assert value["ecuSoftCode"] == role["cachedSoftwareCode"]
        named = [c["name"] for c in config if ecu["ecuId"].endswith(c["ecuIdSuffix"])]
        media = ecu["ecuId"].endswith(media_suffix)
        assert not named and not media
        role_rows.append({"factoryRecordIndex": index, "ecuId": ecu["ecuId"],
                          "route": ecu["ecuDiaId"], "softwareCodeDid": role["softwareCodeDid"],
                          "cachedSoftwareCode": value["ecuSoftCode"],
                          "smmNamedMatches": named, "mediaSpecialCase": media,
                          "selectedSourceImplementation": "CanDiagnostic",
                          "qualifiedNamedRole": None})

    client_fields = direct_string_fields(text("ecuListModel"), "public static class EcuListBean ")
    package_fields = direct_string_fields(text("ecuVersionModel"), "public class EcuVersionInfo ")
    assert "ecuDescription" not in client_fields and "ecuDescription" in package_fields
    assert "public List<EcuVersionInfo> targetPackageInfo;" in text("versionModel")
    request = body(text("canDiagnostic"), "private byte[] getEcuDiagnoseSoftCodeCanCmd(")
    assert 'ecuDiaId.split("/")' in request and 'ecuDId.split("/")' in request
    assert "hexStringToBytes(ecuDIdArray[1])" in request
    assert "byte[] sendData = {1, request[0], request[1], 3," in request
    assert "ecuSoftCodeDid[0], ecuSoftCodeDid[1]" in request
    assert "System.arraycopy(response, 0, this.mReceiveDid, 0, response.length)" in request
    parse_code = body(text("canDiagnostic"), "public String parseEcuSoftCodeInfo(")
    assert "new byte[9]" in parse_code and "System.arraycopy(otaInfo, 10, canData, 0, 9)" in parse_code
    assert "return ecuSoftCode.toUpperCase()" in parse_code
    parse_success = body(text("canDiagnostic"), "public void parseDiagnoseSuccessInfo(")
    assignments = sorted(set(re.findall(r"this\.mEcu\.(\w+)\s*=", parse_success)))
    assert assignments == ["ecuConfigCode", "ecuConfigVer", "ecuHardVer", "ecuSoftCode", "ecuSoftVer"]
    set_record = body(text("canDiagnostic"), "public void requestEcuInfo(")
    assert "this.mEcu = ecuListBean" in set_record
    assert "ecuDescription" not in text("canDiagnostic")
    transfer = body(text("vehicleUtil"), "public static void saveVehicleUpgradeInfoToFile(List<EcuListInfo.EcuListBean> listInfo)")
    assert "ecuInfo.ecuId = item.ecuId" in transfer and "ecuInfo.ecuSoftCode = item.ecuSoftCode" in transfer
    assert "e.ecuId.equals(item.ecuId)" in transfer and "info.targetPackageInfo.iterator()" in transfer
    assert "ecuDescription" not in transfer

    projection = text("mapProbe")
    fields_block = projection.split("static final List<String> FIELDS = ", 1)[1].split("));", 1)[0]
    projected_fields = re.findall(r'"(\w+)"', fields_block)
    assert len(projected_fields) == 10 and "ecuDescription" not in projected_fields
    project_method = body(projection, "static List<Object> projectResponse(")
    assert "for(String field:FIELDS) if(row.containsKey(field))" in project_method
    assert "projected.put(field,v)" in project_method
    assert "result.add(projected)" in project_method
    assert all(set(e) - set(projected_fields) <= {"route", "evidence", "eligibleForProductAllowlist"} for e in factory)
    return {
        "factoryCandidates": len(factory),
        "targetNamespaces": role_rows,
        "smmNameRegistry": matches,
        "targetMatchesInSmmRegistry": sum(len(r["smmNamedMatches"]) for r in role_rows),
        "ecuListBeanStringFields": client_fields,
        "ecuVersionInfoStringFields": package_fields,
        "canSuccessAssignedFields": assignments,
        "retainedFactoryProjectionFields": projected_fields,
        "rawFactoryUnprojectedFieldsKnown": False,
        "newQualifiedNamedRoles": 0,
        "newQualifiedPhysicalBrakeJoin": False,
        "newQualifiedChildDtcMemories": 0,
    }


def main():
    report = json.loads(REPORT.read_text())
    assert report["offlineOnly"] is True
    assert all(report["operations"][key] == 0 for key in (
        "vehicleRequests", "adbCalls", "networkRequests", "cloudRequests", "bydLogActions",
        "firmwareExecutions", "newExtractions", "newDecompiles", "productChanges"))
    sources = read_sources(report)
    for anchor in report["sourceAnchors"]:
        lines = sources[anchor["sourceKey"]].decode("utf-8").splitlines()
        assert all(anchor["literal"] in lines[line - 1] for line in anchor["lines"]), anchor
    actual = derive(sources)
    assert actual == report["verifiedFacts"], "derived facts changed"
    receipt = report["apkResourceVerification"]
    assert receipt["resourceSha256"] == sha(sources["smmConfig"])
    assert receipt["resourceBytes"] == len(sources["smmConfig"]) == 4534
    assert receipt["matchesAlreadyExtractedResource"] is True
    assert receipt["apkSha256"] == "a866917a5466cc72c9fd0fda9b2d6467654529478dd1c6a5c08c3ec5d14c3752"
    assert receipt["apkSha256After"] == receipt["apkSha256"]
    # Optional original APK byte recheck, without opening or extracting members.
    apk = ROOT / receipt["apkPath"]
    if apk.exists():
        assert sha(apk.read_bytes()) == receipt["apkSha256"], "original APK changed"
    assert len(report["gapClosure"]) == 10
    assert all(g["qualifiedByThisReport"] is False for g in report["gapClosure"])
    print("PASS: 9 unresolved roles; 7-entry runtime registry; exact FSE positive control; projection boundary; 0 new physical or DTC claims")


if __name__ == "__main__":
    main()
