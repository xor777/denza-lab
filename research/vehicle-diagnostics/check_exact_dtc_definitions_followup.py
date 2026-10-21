#!/usr/bin/env python3
"""Offline provenance and qualification checks; no networking or vehicle access."""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "research/vehicle-diagnostics/exact-dtc-definitions-followup-20261021.json"


def load(relative: str):
    return json.loads((ROOT / relative).read_text())


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> None:
    evidence = json.loads(EVIDENCE.read_text())
    require(evidence["schema"] == "denza-lab.exact-dtc-definitions-followup.v1", "schema")
    for key in ("vehicleOperations", "authenticatedRequests", "privateApiRequests", "formSubmissions"):
        require(evidence[key] == 0, key)
    for key in ("qualifiedOemDefinitions", "qualifiedSoftwareBindings", "qualifiedWarningDtcLinks"):
        require(evidence[key] == [], key)

    sources = evidence["sources"]
    require(len({source["path"] for source in sources}) == len(sources), "duplicate source path")
    for source in sources:
        raw = (ROOT / source["path"]).read_bytes()
        require(len(raw) == source["bytes"], "source size: " + source["path"])
        require(hashlib.sha256(raw).hexdigest() == source["sha256"], "source hash: " + source["path"])

    supported = load(evidence["target"]["identityEvidenceRef"])
    bms = next(module for module in supported["modules"] if module["name"] == "BMS")
    require(bms["identityMatched"] and bms["targetCompleted"], "completed identity-bound BMS observation")
    require(bms["softwareCode"] == evidence["target"]["softwareCode"] == "4A520011E00D00047B", "BMS identity")
    require(bms["formatRead"]["formatId"] == evidence["target"]["dtcFormatIdentifier"] == 0, "format00")
    require(bms["supportedCount"] == len(bms["records"]) == evidence["target"]["supportedCodeCount"] == 115, "supported count")
    registry = {record["displayCode"]: record for record in bms["records"]}
    public_index = load("research/vehicle-diagnostics/oem-dtc-public-index-20261020.json")
    headings = [row for row in public_index["records"] if row[0] == "battery_management"]
    public_codes = {row[1] for row in headings}
    actual_missing = set(registry) - public_codes
    expected_missing = {"P1A8E73", "P2BF900", "P2BFF0B", "P2BAA00", "P2BF600", "P2BFF07"}
    require(actual_missing == expected_missing, "exact six-code membership/catalog join")
    rows = evidence["exactMissingBmsDefinitions"]
    require({row["displayCode"] for row in rows} == expected_missing and len(rows) == 6, "six-code coverage")
    for row in rows:
        observed = registry[row["displayCode"]]
        require(row["raw24Hex"] == observed["raw24Hex"], "raw code preserved")
        require(row["observedStatusHex"] == observed["statusHex"] == "00", "status is not an active fault")
        require(row["supportedByExactSoftware"] and not row["presentInInspectedBmsHeadings"], "membership boundary")
        require(row["definitionStatus"] == "UNKNOWN", "definition unknown")
        for key in ("qualifiedMeaningZh", "qualifiedMeaningRu", "primaryPerCodeDocumentId"):
            require(row[key] is None, "no inferred missing-code field: " + key)

    conflict = evidence["unresolvedConflict"]
    conflict_source = [dict(subsystem=row[0], displayCode=row[1], outlineId=row[2], publicTitleZh=row[3], primaryDefinition=None)
                       for row in headings if row[1] == "P2BAE00"]
    require(conflict["originalPublicHeadings"] == conflict_source and len(conflict_source) == 2, "conflicting original headings")
    require({row["outlineId"] for row in conflict_source} == {"outline_921_a", "outline_925_a"}, "original locators")
    require(conflict["resolution"] is None, "no conflict resolution guessed")
    require(conflict["raw24Hex"] == registry["P2BAE00"]["raw24Hex"], "supported conflict code")

    for row in evidence["temperatureCandidates"]:
        require(row["raw24Hex"] == registry[row["displayCode"]]["raw24Hex"], "temperature-code membership")
        require(row["observedStatusHex"] == registry[row["displayCode"]]["statusHex"] == "00", "temperature status")
        matching = next(source for source in headings if source[1] == row["displayCode"])
        require((row["outlineId"], row["publicTitleZh"]) == tuple(matching[2:]), "temperature original title")
        require(row["qualifiedDefinition"] is None and row["warning0x358Link"] is None and not row["originalPageRetrieved"], "no warning inference")

    base = ROOT / evidence["captureBase"]
    profile = (base / "iautocloud-denza-profile.html").read_text()
    lead = evidence["newAcquisitionLead"]
    require(lead["platformOrganizationId"] == "174", "exact platform selector")
    for flag in ("manufacturerAffiliationIndependentlyVerified", "z9gtCoverageVerified", "targetSoftwareCoverageVerified"):
        require(lead[flag] is False, "unproved platform gate: " + flag)
    for page in lead["exactProfileLinkedPages"]:
        require(('href="' + page["relativeHref"] + '"') in profile, "page is explicitly linked from the observed profile")
    require("未经过汽车主机厂审核认证的个人用户" in profile, "ordinary-individual access claim")
    require("最小目录单元内容" in profile, "smallest-unit print claim")
    for key, notice_id in (("latestObservedVersionNotice", "15759"), ("latestObservedManualNotice", "15760")):
        notice = lead[key]
        require(("href=\"/notice/detail.html?id=" + notice_id + "\"") in profile, "notice explicitly linked")
        require(notice["titleZh"] in profile and notice["platformTimestamp"] in profile, "notice title and timestamp")
    for name in ("iautocloud-denza-ecu-version-shell.html", "iautocloud-denza-manual-shell.html"):
        shell = (base / name).read_text()
        require(lead["accessBoundary"]["publicShellLoginNoticeZh"] in shell, "published login boundary")
        require(lead["accessBoundary"]["publicShellPaidContentNoticeZh"] in shell, "published entitlement boundary")
        require("Z9GT" not in shell and "4A520011E00D00047B" not in shell, "no model/software row claimed")

    schema = evidence["primaryPublicTisSchemaObservation"]
    raw = (ROOT / schema["retainedSourcePath"]).read_bytes()
    anchors = load(schema["anchorProjection"])
    for anchor in anchors:
        selected = raw[anchor["start"]:anchor["endExclusive"]]
        require(selected.decode() == anchor["literal"], "source byte anchor: " + anchor["name"])
        require(hashlib.sha256(selected).hexdigest() == anchor["sha256"], "anchor hash")
    require(schema["observedHeaderFields"] == ["id", "dtcCode", "dtcName"], "observed client header fields")
    require(not schema["dataRetrieved"] and not schema["endpointCalled"] and not schema["clientExecuted"], "static client only")

    concern = evidence["additionalCodeLabelAcquisitionConcern"]
    original = next(row for row in headings if row[1] == "P1A8F73")
    require(concern["z9gt"]["publicTitleZh"] == original[3], "Z9GT contactor title preserved")
    tang = (base / "wrong-model-tang-dmi-public-catalog.html").read_text()
    for title in concern["otherModelPublicHeadings"]:
        require(title in tang, "other-model observation is present in captured public catalog")
    require(len(concern["otherModelPublicHeadings"]) == 2, "other-model paired headings")
    require(all(not row["meaningImported"] for row in evidence["rejectedMatches"]), "wrong-model rejection")
    require(not evidence["originalAcquisitionPacket"]["packetSent"], "packet prepared only")
    print(f"PASS sources={len(sources)} missing_bms_codes=6 unresolved_conflicts=1 qualified_definitions=0 vehicle_operations=0")


if __name__ == "__main__":
    main()
