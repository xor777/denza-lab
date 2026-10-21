#!/usr/bin/env python3
"""Bounded, offline inventory of retained CDC/INS image candidates; no extraction/network.

Run from the repository root. --write records only this investigation's outputs.
The archive directory inventory does not certify the contents of opaque/encrypted blobs.
"""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import tarfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "captures/washer-diagnostics-20261021/producer-image-inventory"
REPORT = ROOT / "research/vehicle-diagnostics/washer-producer-image-inventory-20261021.json"
FW = pathlib.Path("/Users/dmitry/Dev/denza/firmware")
TARGET = re.compile(r"CDC720|INS723|4B310011E0019A0002|4B450011E0250037B1|(?:^|[/_.-])(?:CDC|INS)(?:[/_.-]|$)", re.I)
EXACT_TARGET = re.compile(r"CDC720|INS723|4B310011E0019A0002|4B450011E0250037B1", re.I)
IMAGE = re.compile(r"\.(?:xcd|bin|img|srec|s19|hex|pkg|ota|enc|update|dat|dlt)$", re.I)
CONTEXT = re.compile(r"firmware|ota|factory|update|download|cache", re.I)
TEXT_EXT = {".json", ".xml", ".txt", ".log", ".md", ".java", ".kt", ".py", ".c", ".h", ".cpp", ".html", ".js", ".ts", ".csv", ".tsv", ".yaml", ".yml", ".png", ".jpg", ".jpeg", ".webp", ".svg", ".mp4", ".wav", ".ogg", ".class", ".dex", ".so", ".o", ".odex", ".vdex", ".pck", ".gd", ".gdscript", ".import"}
SKIP_PARTS = {"node_modules", "analyzer-deps", ".gradle", "build", "__pycache__", ".git"}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def rel(path):
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def fingerprint(path):
    return {"path": rel(path), "bytes": path.stat().st_size, "sha256": digest(path)}


def collect():
    names = subprocess.check_output(["rg", "--files", "--no-ignore", "captures", "reverse"], cwd=ROOT, text=True).splitlines()
    paths = [ROOT / n for n in names if not (set(pathlib.Path(n).parts) & SKIP_PARTS) and not n.startswith("captures/washer-diagnostics-20261021/producer-image-inventory/")]
    paths += [p for p in FW.iterdir() if p.is_file()]
    paths = sorted(set(paths))
    archives, blobs, named = [], [], []
    archive_errors = []
    for path in paths:
        name = rel(path)
        size = path.stat().st_size
        if TARGET.search(name):
            named.append({"path": name, "bytes": size})
        archive_kind = path.suffix.lower() in {".zip", ".apk", ".jar", ".7z", ".rar"} or name.endswith((".tar.gz", ".tgz", ".tar"))
        opaque_context = CONTEXT.search(name) and path.suffix.lower() not in TEXT_EXT and not archive_kind
        if IMAGE.search(name) or opaque_context:
            with path.open("rb") as f:
                head = f.read(16)
            magic = "ELF" if head.startswith(b"\x7fELF") else "ZIP" if head.startswith(b"PK\x03\x04") else "GZIP" if head.startswith(b"\x1f\x8b") else "other"
            blob = {"path": name, "bytes": size, "headerClass": magic}
            if size <= 8 * 1024 * 1024:
                blob["sha256"] = digest(path)
            blobs.append(blob)
            archive_kind = archive_kind or head.startswith(b"PK\x03\x04")
        if not archive_kind:
            continue
        try:
            if zipfile.is_zipfile(path):
                with zipfile.ZipFile(path) as z:
                    members = [{"name": m.filename, "bytes": m.file_size, "compressedBytes": m.compress_size, "crc32": f"{m.CRC:08x}", "method": m.compress_type} for m in z.infolist()]
                kind = "zip-directory"
            elif name.endswith((".tar.gz", ".tgz", ".tar")):
                with tarfile.open(path, "r:*") as t:
                    members = [{"name": m.name, "bytes": m.size} for m in t]
                kind = "tar-directory"
            else:
                archive_errors.append({"path": name, "reason": "not a supported ZIP/TAR directory"})
                continue
            # The directory itself is evidence, not a hash of full image bytes.
            canonical = json.dumps(members, sort_keys=True, separators=(",", ":")).encode()
            archives.append({"path": name, "bytes": size, "kind": kind, "memberCount": len(members), "directorySha256": hashlib.sha256(canonical).hexdigest(), "targetNamedMembers": [m for m in members if TARGET.search(m["name"])], "imageNamedMembers": [m for m in members if IMAGE.search(m["name"])], "members": members if path.parent == FW else None})
        except (OSError, ValueError, zipfile.BadZipFile, tarfile.TarError) as exc:
            archive_errors.append({"path": name, "reason": type(exc).__name__})

    config = ROOT / "captures/telematics-20260923/readable-firmware/Config-readable.xml"
    tree = ET.parse(config).getroot()
    # Never copy Package.text: the retained XML also contains package crypto keys.
    modules = [e.attrib["ModuleName"] for e in tree.iter("Module") if "ModuleName" in e.attrib]
    packages = [{k: e.attrib[k] for k in ("ModuleName", "FileName") if k in e.attrib} for e in tree.iter("Package")]
    cache_dir = ROOT / "captures/diagnostics-discovery-20261018/approved-export-1/files"
    ecus = json.loads((cache_dir / "04-ECUVersion.json").read_text())["ecuInfo"]
    identities = [r for r in ecus if r.get("ecuId") in {"HTH-2021_CDC_BYD_00100001", "HTH-2021_INS_BYD_00100001"}]
    check = json.loads((cache_dir / "03-needCheckEcuList.json").read_text())
    metadata = {
        "iviConfig": {**fingerprint(config), "moduleNames": modules, "packageAttributesOnly": packages},
        "ecuVersion": {**fingerprint(cache_dir / "04-ECUVersion.json"), "recordCount": len(ecus), "targetIdentityRecords": identities},
        "needCheckEcuList": {**fingerprint(cache_dir / "03-needCheckEcuList.json"), "fieldNames": sorted(check), "hasCdnUrl": bool(check.get("cdnUrl")), "ecuListCount": len(check.get("ecuList", [])), "classification": "CDN/inventory metadata; URL deliberately not copied or fetched"},
        "shortVersionMarkers": [fingerprint(cache_dir / n) for n in ("05-VehicleVersion2", "06-SourceVehicleVersion2")],
    }
    sample = ROOT / "captures/firmware/ivi-34.1.33.2605218.1/jadx/UpgradeServer/resources/res/raw/update.zip"
    with zipfile.ZipFile(sample) as z:
        metadata["embeddedUpgradeServerPackage"] = {
            **fingerprint(sample),
            "config": z.read("update/config").decode().strip(),
            "metadata": z.read("update/metadata").decode().splitlines(),
            "mcuMembers": [{"name": n, "bytes": z.getinfo(n).file_size, "sha256": hashlib.sha256(z.read(n)).hexdigest()} for n in ("update/mcu.xcd", "update/mcu_b.xcd")],
            "classification": "Real retained MCU containers, but package metadata identifies Android 7.1.2/msm8953_64; no binding to the target CDC/INS codes or versions. Contents were not decoded or executed.",
        }
    prior_names = ("ota-history-read-20261021.json", "ota-core-fields-live-20261021.json", "ota-remaining-fields-live-20261021.json", "washer-producer-routing-20261021.json")
    prior = [fingerprint(ROOT / "research/vehicle-diagnostics" / n) for n in prior_names]
    evidence = {"scope": {"fileRoots": ["captures", "reverse", str(FW)], "namesConsidered": len(paths), "skippedDirectoryNames": sorted(SKIP_PARTS), "method": "retained file-name inventory, image/opaque-context headers, ZIP/APK/JAR/TAR member directories; no extraction or firmware execution"}, "archives": archives, "archiveErrors": archive_errors, "blobCandidates": blobs, "targetNamedPaths": named, "metadata": metadata, "priorEvidence": prior}
    return evidence


def make_report(e):
    targets = e["metadata"]["ecuVersion"]["targetIdentityRecords"]
    assert len(targets) == 2
    assert {r["ecuSoftCode"] for r in targets} == {"4B310011E0019A0002", "4B450011E0250037B1"}
    assert not any(TARGET.search(p.get("FileName", "")) for p in e["metadata"]["iviConfig"]["packageAttributesOnly"])
    return {
        "schema": 1, "date": "2026-10-21", "status": "no_identified_CDC720_or_INS723_image_in_bounded_retained_corpus",
        "question": "Do already-retained local files contain the producer-side firmware bytes for CDC720 or INS723?",
        "safety": {"offlineOnly": True, "networkCalls": 0, "liveOperations": 0, "extractions": 0, "firmwareExecuted": False, "secretValuesCopied": False},
        "targets": targets,
        "summary": {"filesConsidered": e["scope"]["namesConsidered"], "archiveDirectoriesRead": len(e["archives"]), "archiveMembersCounted": sum(a["memberCount"] for a in e["archives"]), "blobCandidatesInspected": len(e["blobCandidates"]), "broadNameMatchesPaths": len(e["targetNamedPaths"]), "broadNameMatchesArchiveMembers": sum(len(a["targetNamedMembers"]) for a in e["archives"]), "exactCodeOrControllerNameMatchesArchiveMembers": sum(bool(EXACT_TARGET.search(m["name"])) for a in e["archives"] for m in a["targetNamedMembers"]), "actualTargetImagesIdentified": 0},
        "findings": [
            "Both retained top-level OTA packages are IVI/FSE packages. Their current ZIP directories are preserved in raw evidence; full multi-GB archive SHA-256 was not recomputed this pass.",
            "The retained plaintext IVI Config module list is Android, Mcu, Dsp, Anc, Screen, Camera. Package attributes contain Android, IVI Mcu, DSP, ANC and Screen updates, not a package identified as CDC720 or INS723.",
            "The FSE outer package has update.zip, mcu_42_2_3.xcd and dspres_e_0.xcd; no member is identified as either target. FSE/IVI MCU binaries are not thereby images of the separately identified CDC/INS controllers.",
            "ECUVersion holds the exact CDC/INS software identities but no firmware bytes. needCheckEcuList is 358-byte CDN/inventory metadata. VehicleVersion2 and SourceVehicleVersion2 are 4/6-byte markers, not ECU images.",
            "Prior retained OTA history evidence found no saved applicable strategy/result package; subsequent retained read-only identity replies establish identities, not downloaded images. Those historical reports are inputs only; no car or OTA request was made by this check.",
            "Broad CDC/INS filename matches are SomeipInsService/obfuscated Java names and obfuscated Android PNG/XML/JSON resources (e.g. res/InS.png, res/CDC.json), not exact controller/software-code matches. Their names do not identify ECU images.",
            "UpgradeServer includes a previously retained res/raw/update.zip with real update/mcu.xcd and mcu_b.xcd containers. Its own config is qc+mcu and metadata says Android 7.1.2 msm8953_64, timestamp 1602674794. These opaque MCU members are not identified as CDC720/INS723, and no target software-code binding was found in their package metadata.",
        ],
        "evidence": {"rawInventory": str(OUT.relative_to(ROOT) / "inventory.json"), "metadata": e["metadata"], "priorReports": e["priorEvidence"]},
        "bounds": ["No recursive decryption or blind signature carving of opaque blobs was attempted. Absence of an identified image is not mathematical proof against an arbitrarily renamed/encrypted embedded image.", "ZIP/APK/JAR/TAR directories were read. The already-retained UpgradeServer update.zip metadata and two MCU members were read in memory for hashes only; nested members were not written or decoded. The encrypted IVI Android member and partition payloads were not newly decrypted or recursively searched.", "The snapshot is limited to the documented local corpus roots, not arbitrary home directories or secrets. Existing source/worktree backup archives are not assumed to be ECU packages. Two files with APK/JAR extensions were not valid supported archive directories and are listed as such in the raw inventory."],
        "nearestMissingArtifacts": [
            {"ecu": "CDC720", "required": "Actual application/firmware container bytes bound by a manifest to HTH-2021_CDC_BYD_00100001, software code 4B310011E0019A0002 v4.00.53 (or a documented applicable variant)."},
            {"ecu": "INS723", "required": "Actual application/firmware container bytes bound by a manifest to HTH-2021_INS_BYD_00100001, software code 4B450011E0250037B1 v4.00.04 (or a documented applicable variant)."},
            {"requiredForEither": "Package manifest/checksums plus load map or decoding description, and N9 profile 168 versus Z9GT profile 170 applicability/coding. A version reply, CDN URL, HTML release note or unfulfilled download strategy is insufficient."},
        ],
        "ownershipCaveat": "CDC and INS remain acquisition candidates, not attributed CAN261 transmitters, warning 1DD selectors, or washer sensor input owners. An image inventory alone cannot settle those three roles.",
        "reproduce": "python3 research/vehicle-diagnostics/check_washer_producer_image_inventory.py",
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args()
    e = collect()
    report = make_report(e)
    if args.write:
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / "inventory.json").write_text(json.dumps(e, ensure_ascii=False, indent=2) + "\n")
        REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    else:
        saved = json.loads(REPORT.read_text())
        assert saved["evidence"]["metadata"] == report["evidence"]["metadata"], "source metadata changed"
        recorded = json.loads((OUT / "inventory.json").read_text())
        current = {a["path"]: a for a in e["archives"]}
        for a in recorded["archives"]:
            assert a["path"] in current and a["directorySha256"] == current[a["path"]]["directorySha256"], a["path"]
    print(json.dumps({"result": "PASS", **report["summary"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
