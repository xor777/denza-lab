#!/usr/bin/env python3
"""Recheck the finite, offline component-package name investigation.

Only reads pinned evidence, the already-readable XML, and the two owner's local
ZIP central directories. --rehash-archives additionally rereads complete ZIPs.
No firmware execution, decryption, vehicle, network or OTA lifecycle operation.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "research/vehicle-diagnostics/ecu-package-name-followup-20261021.json"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def load_sources(report):
    result = {}
    for key, receipt in report["sources"].items():
        data = (ROOT / receipt["path"]).read_bytes()
        assert len(data) == receipt["bytes"], (key, "bytes")
        assert digest(data) == receipt["sha256"], (key, "sha256")
        result[key] = data
    return result


def component_projection(path, data):
    root = ET.fromstring(data)
    return {
        "schema": "denza-lab.ota-component-manifest-projection.v1",
        "originalPath": path,
        "originalBytes": len(data),
        "originalSha256": digest(data),
        "transformation": "XML attribute allowlist only; package text/cipher material excluded",
        "moduleInfo": [dict(x.attrib) for x in root.findall("./ModuleInfo/Module")],
        "upgradeModules": [x.text for x in root.findall("./UpgradeModule/Module")],
        "packages": [
            {"moduleName": x.get("ModuleName"), "fileName": x.get("FileName"),
             "container": group.tag, "kind": stage.tag}
            for group in root.findall("./Data/*") for stage in group
            for x in stage.findall("./Package")
        ],
        "screenInfo": [
            {k: x.get(k) for k in ("ScreenName", "ScreenId", "DiagDId")}
            for x in root.findall("./ScreenInfo/Screen")
        ],
        "cameraInfo": [
            {k: x.get(k) for k in ("CameraName", "CameraId", "DiagDId")}
            for x in root.findall("./CameraInfo/Camera")
        ],
    }


def archive_members(path):
    with zipfile.ZipFile(path) as archive:
        members = [
            {"name": x.filename, "bytes": x.file_size,
             "compressedBytes": x.compress_size, "crc32": f"{x.CRC:08x}",
             "compression": x.compress_type, "flags": x.flag_bits,
             "headerOffset": x.header_offset}
            for x in archive.infolist()
        ]
        # FSE config is a plain eleven-byte module selector. No XCD/wrapper read.
        config = archive.read("config").decode() if "config" in archive.namelist() else None
    return members, config


def derive(report, sources):
    baseline = json.loads(sources["baseline"])
    factory = json.loads(sources["factory"])
    cache = json.loads(sources["cacheTargets"])
    rows = [x for x in baseline["gapClosure"] if "ecuId" in x]
    assert len(rows) == 9
    manifest = json.loads(sources["componentManifest"])
    original = ROOT / manifest["originalPath"]
    assert component_projection(manifest["originalPath"], original.read_bytes()) == manifest
    inventories = json.loads(sources["archiveInventories"])
    assert len(inventories) == 2
    inventory_facts = []
    for item in inventories:
        path = Path(item["archivePath"])
        assert path.stat().st_size == item["archiveBytes"]
        members, config = archive_members(path)
        assert members == item["members"]
        assert config == item.get("plainConfig")
        inventory_facts.append({
            "archive": path.name, "members": len(members),
            "archiveBytes": item["archiveBytes"], "archiveSha256": item["archiveSha256"],
            "packageRoots": sorted({x["name"].split("/")[0] for x in members}),
            "plainConfig": config,
        })
    # Scope is exact full identity text in outer package metadata, not a binary,
    # suffix, DID-number or shared-prefix scan.
    searchable = json.dumps(manifest, ensure_ascii=False) + json.dumps(inventories, ensure_ascii=False)
    identity_matches = []
    for row in rows:
        assert row["qualifiedByThisReport"] is False and row["status"] == "unproven"
        identity_matches.append({
            "ecuId": row["ecuId"], "fullSoftwareCode": row["fullSoftwareCode"],
            "exactEcuIdHits": searchable.count(row["ecuId"]),
            "exactFullSoftwareCodeHits": searchable.count(row["fullSoftwareCode"]),
        })
    assert all(x["exactEcuIdHits"] == x["exactFullSoftwareCodeHits"] == 0 for x in identity_matches)
    # Factory and current-code sources are retained to keep all identity parts
    # together; no new interpretation of their suffixes is performed.
    def nested_rows(value):
        if isinstance(value, list):
            for item in value:
                yield from nested_rows(item)
        elif isinstance(value, dict):
            if "ecuId" in value:
                yield value
            for item in value.values():
                yield from nested_rows(item)
    cache_rows = list(nested_rows(cache))
    for row in rows:
        factory_row = factory["ecus"][row["factoryRecordIndex"]]
        assert factory_row["ecuId"] == row["ecuId"]
        assert factory_row["ecuDiaId"] == row["route"]
        assert factory_row["ecuDId"].split("/")[1] == row["softwareCodeDid"]
        matches = [x for x in cache_rows if x.get("ecuId") == row["ecuId"]]
        assert len(matches) == 1
        assert matches[0]["ecuSoftCode"] == row["fullSoftwareCode"]
    model = sources["ecuVersionModel"].decode()
    fields = set(re.findall(r"public (?:String|boolean) (\w+);", model))
    retained_fields = ["ecuId", "ecuDescription", "ecuSoftCode", "ecuSoftVer",
                       "ecuHardVer", "ecuUpgradePart", "fileName", "isSourcePackage"]
    assert set(retained_fields) <= fields
    cache_helper = sources["versionDownloadCache"].decode()
    assert "String json = JSONUtil.toJson(downloadFileInfo);" in cache_helper
    assert "SpUtil.putString(downloadInfoKey, json);" in cache_helper
    presenter = sources["checkVersionPresenter"].decode()
    package_lists = ["targetPackageInfo", "configPackageInfo", "sourcePackageInfo", "configSourceInfo"]
    assert all(f"this.mAllPackageInfo.addAll(info.{name});" in presenter for name in package_lists)
    comparison = [x for x in presenter.splitlines() if "if (cacheInfo != null && TextUtils.equals(info.encryptMd5" in x]
    assert len(comparison) == 1
    compared_fields = re.findall(r"TextUtils.equals\(info\.(\w+), cacheInfo\.\1\)", comparison[0])
    assert compared_fields == ["encryptMd5", "encryptSHA256", "ecuId", "fileDir", "fileName", "versionPath", "dcdnVersionPath"]
    receipt = json.loads(sources["exportReceipt"])
    missing = [x["path"] for x in receipt["files"] if x["status"] == "missing" and x["path"].endswith("/strategy.json")]
    version_files = [{"path": x["path"], "bytes": x["sizeBytes"], "sha256": x["sha256"]}
                     for x in receipt["files"] if x["path"] in ("/collect2/VehicleVersion2", "/collect2/SourceVehicleVersion2")]
    assert len(missing) == len(version_files) == 2
    return {
        "outerArchives": inventory_facts,
        "readableIviManifest": {
            "packageCount": len(manifest["packages"]),
            "moduleNames": [x["ModuleName"] for x in manifest["moduleInfo"]],
            "upgradeModules": manifest["upgradeModules"],
            "screenDids": sorted({x["DiagDId"] for x in manifest["screenInfo"]}),
            "cameraDids": sorted({x["DiagDId"] for x in manifest["cameraInfo"]}),
        },
        "exactIdentityMatchesInOuterMetadata": identity_matches,
        "downloadRecordStorage": {
            "preferenceName": "share_data_prefs",
            "key": "otaUpdateDownloadFileInfo",
            "fullModelSerialized": True,
            "safeFieldsDeclaredInModel": retained_fields,
            "packageListsFeedingDownloadQueue": package_lists,
            "cacheEqualityFields": compared_fields,
            "installedSoftwareCodeComparedByCacheEquality": False,
            "recordAcquired": False,
        },
        "retainedExport": {
            "missingStrategyPathsAtSnapshot": missing,
            "versionLabelFiles": version_files,
            "currentFilesystemStateEstablished": False,
            "fullPreferenceFileRetainedByReceipt": False,
        },
        "namedChildRolesClosed": 0,
        "remainingNamedChildRoles": 9,
        "brakePhysicalRelationship": None,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rehash-archives", action="store_true")
    args = parser.parse_args()
    report = json.loads(REPORT.read_text())
    assert report["offlineOnly"] is True
    assert all(v == 0 for v in report["operations"].values())
    sources = load_sources(report)
    for anchor in report["sourceAnchors"]:
        lines = sources[anchor["sourceKey"]].decode().splitlines()
        assert anchor["literal"] in lines[anchor["line"] - 1], anchor
    facts = derive(report, sources)
    assert facts == report["verifiedFacts"]
    baseline = json.loads(sources["baseline"])
    assert report["gapClosure"] == [x for x in baseline["gapClosure"] if "ecuId" in x]
    assert len(report["gapClosure"]) == 9
    assert report["brakeGap"] == [x for x in baseline["gapClosure"] if "gap" in x][0]
    assert report["brakeGap"]["qualifiedByThisReport"] is False
    if args.rehash_archives:
        for archive in json.loads(sources["archiveInventories"]):
            value = hashlib.sha256()
            with Path(archive["archivePath"]).open("rb") as file:
                for block in iter(lambda: file.read(8 * 1024 * 1024), b""):
                    value.update(block)
            assert value.hexdigest() == archive["archiveSha256"]
    print(json.dumps({"result": "pass", "pinnedSources": len(sources),
                      "outerMembers": sum(x["members"] for x in facts["outerArchives"]),
                      "componentPackages": facts["readableIviManifest"]["packageCount"],
                      "remainingNamedChildRoles": 9, "namedChildRolesClosed": 0,
                      "brakePhysicalRelationship": None,
                      "completeArchiveHashesRechecked": args.rehash_archives}, indent=2))


if __name__ == "__main__":
    main()
