#!/usr/bin/env python3
"""Replay local source/resource joins; no network, APK execution or vehicle access."""
from __future__ import annotations

from collections import Counter
import hashlib
import json
from pathlib import Path
import zipfile


ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "research/vehicle-diagnostics/dtc-dictionary-local-paths-20261021.json"


def load(path: str):
    return json.loads((ROOT / path).read_text())


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def dtc_lists(value):
    found = []
    if isinstance(value, dict):
        if isinstance(value.get("dtcList"), list):
            found.append(value["dtcList"])
        for child in value.values():
            found.extend(dtc_lists(child))
    elif isinstance(value, list):
        for child in value:
            found.extend(dtc_lists(child))
    return found


def main() -> None:
    evidence = json.loads(EVIDENCE.read_text())
    require(evidence["schema"] == "denza-lab.dtc-dictionary-local-paths.v1", "schema")
    for key in ("vehicleOperations", "adbOperations", "otaOperations", "authenticatedRequests",
                "privateApiRequests", "privateRuntimeDatabasesOpened"):
        require(evidence[key] == 0, key)
    for key in ("qualifiedVehicleEcuDefinitions", "qualifiedEcuSoftwareBindings",
                "qualifiedWarningDtcLinks", "qualifiedFormat01RawDisplayMappings"):
        require(evidence[key] == [], key)
    require(not evidence["clientExecution"], "no client execution")

    sources = evidence["sources"]
    require(len({source["path"] for source in sources}) == len(sources), "unique source paths")
    hashes = {}
    for source in sources:
        raw = (ROOT / source["path"]).read_bytes()
        hashes[source["path"]] = digest(raw)
        require(len(raw) == source["bytes"], "source size: " + source["path"])
        require(hashes[source["path"]] == source["sha256"], "source hash: " + source["path"])

    anchors = load(evidence["sourceAnchors"])
    for anchor in anchors:
        path = ROOT / anchor["path"]
        if anchor["kind"] == "source_lines":
            lines = path.read_text().splitlines(keepends=True)
            raw = "".join(lines[anchor["startLine"] - 1:anchor["endLine"]]).encode()
        else:
            raw = path.read_bytes()[anchor["start"]:anchor["endExclusive"]]
        require(raw.decode() == anchor["text"] and digest(raw) == anchor["sha256"], "source anchor: " + anchor["name"])

    base = evidence["captureBase"]
    scan = load(base + "/targeted-apk-scan.json")
    summary = evidence["boundedApkInspection"]
    require(len(scan["apks"]) == summary["apkCount"] == 5, "five named candidate APKs")
    require(sum(apk["scannedMemberCount"] for apk in scan["apks"]) == summary["scannedMemberCount"] == 4014, "member count")
    require(sum(apk["scannedUncompressedBytes"] for apk in scan["apks"]) == summary["scannedUncompressedBytes"] == 59728492, "uncompressed byte count")
    tokens = scan["identityTokens"] + scan["codeTokens"] + [code[1:] for code in scan["codeTokens"]]
    needles = [token.encode(encoding).lower() for token in tokens
               for encoding in ("utf-8", "utf-16-le", "utf-16-be")]
    tables = []
    actual_tables = []
    for apk in scan["apks"]:
        require(not apk["literalHits"] and not apk["sqliteHeaders"] and not apk["limits"], "finite no-hit scope")
        require(digest((ROOT / apk["path"]).read_bytes()) == apk["sha256"], "candidate APK identity")
        with zipfile.ZipFile(ROOT / apk["path"]) as archive:
            require(len(apk["members"]) == apk["scannedMemberCount"], "member accounting")
            for member in apk["members"]:
                raw = archive.read(member["member"])
                require(len(raw) == member["bytes"] and digest(raw) == member["sha256"], "member bytes: " + member["member"])
                require(not raw.startswith(b"SQLite format 3\0"), "no bundled SQLite file")
                lower = raw.lower()
                require(all(needle not in lower for needle in needles), "replayed literal scan: " + member["member"])
                if member["member"].endswith(".json"):
                    try:
                        actual_tables.extend(dtc_lists(json.loads(raw)))
                    except (ValueError, UnicodeError):
                        pass
        tables.extend(apk["dtcLists"])
    require(len(tables) == 1, "one populated bundled dtcList")
    require(actual_tables == [[{"code": 1193046, "desc": "雷达故障"}]], "independently replayed JSON table discovery")
    single = evidence["singleBundledDtcList"]
    require(tables[0]["member"] == single["zipMember"] == "res/7t.json", "actual obfuscated ZIP name")
    require(tables[0]["rows"] == [{"code": 1193046, "desc": "雷达故障"}], "literal LA_BCM table")
    require(tables[0]["ecuAbbrName"] == "LA_BCM" and tables[0]["requestCanId"] == 0x720, "different ECU")
    require(single["rawCodeHex"] == "123456" and single["targetSoftwareBinding"] is None, "no target dictionary promotion")
    require(digest((ROOT / single["restoredResourcePath"]).read_bytes()) == single["memberSha256"], "JADX resource matches APK member")

    skipped = load(base + "/previously-skipped-json-scan.json")
    gap = evidence["closedResourceCoverageGap"]
    require(skipped["memberCount"] == gap["additionalJsonMemberCount"] == 110, "additional JSON coverage")
    require(skipped["bytes"] == gap["additionalUncompressedBytes"] == 2792585, "additional byte coverage")
    require(skipped["selectedApks"] == gap["additionalApksWithJsonMembers"] == 14, "additional APK coverage")
    require(dict(Counter(row["classification"] for row in skipped["rows"])) == gap["classifications"], "resource classifications")
    grouped = {}
    for row in skipped["rows"]:
        require(row["parsed"] and not row["literalHits"] and not row["semanticKeyHits"], "bounded parsed no-hit resources")
        require(not row["member"].startswith(("assets/", "res/raw/")), "previous prefix did skip this member")
        grouped.setdefault(row["apkPath"], []).append(row)
    for apk_path, rows in grouped.items():
        apk_hash = digest((ROOT / apk_path).read_bytes())
        with zipfile.ZipFile(ROOT / apk_path) as archive:
            for row in rows:
                require(apk_hash == row["apkSha256"], "additional source APK hash")
                raw = archive.read(row["member"])
                require(digest(raw) == row["sha256"] and len(raw) == row["bytes"], "additional member hash")
                json.loads(raw)

    hu = load(evidence["headunitConfigProjection"])
    config = load(hu["sourcePath"])
    rows = config["diagnostic_infor_config"]["diag_infor_value"]
    require(len(rows) == hu["rowCount"] == 213, "head-unit definitions remain explicit")
    require(dict(Counter(row.get("modulename") for row in rows)) == hu["moduleNames"], "head-unit source grouping")
    require(hu["qualifiedTargetDefinitions"] == [] and not hu["controllerSoftwareBindingFields"], "no target binding from HU names")
    supported = load("research/vehicle-diagnostics/supported-dtc-20261021.json")
    for module in supported["modules"]:
        if module.get("supportedListRead") and module.get("formatRead", {}).get("formatId") == 0:
            registry = {record["raw24Hex"] for record in module["records"]}
            intersection = sorted({str(row["dtc"]).upper() for row in rows} & registry)
            require(intersection == hu["exactTextualIntersectionsWithObservedFormat00Registries"][module["name"]] == [], "exact config/registry intersection")

    public = evidence["publicStaticTisFollowup"]
    imports = load(public["fetchManifest"])
    require({row["httpStatus"] for row in imports} == {200, 404}, "actual static fetch outcomes")
    require(sum(row["httpStatus"] == 404 for row in imports) == 2, "two exact unavailable imports")
    for row in imports:
        entry = (ROOT / row["parentPath"]).read_bytes()
        literal = row["parentLiteral"].encode()
        offset = row["parentLiteralByteOffset"]
        require(digest(entry) == row["parentSha256"] and entry[offset:offset + len(literal)] == literal, "exact primary import reference")
        require(row["authentication"] == "none" and not row["privateApi"], "public static only")
    for field in ("actualAccessKeyRecovered", "originalManualBodyRequested", "apiRequested", "clientExecuted"):
        require(public["manualPageObservation"][field] is False, "manual access boundary: " + field)
    require(public["newQualifiedDefinitions"] == [], "no public dictionary recovered")
    print(f"PASS sources={len(sources)} apk_members=4014 additional_json=110 hu_config_rows=213 qualified_vehicle_definitions=0 vehicle_operations=0")


if __name__ == "__main__":
    main()
