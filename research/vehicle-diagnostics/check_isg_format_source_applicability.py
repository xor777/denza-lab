#!/usr/bin/env python3
"""Offline reproduction of the original-script dependency and its boundaries."""
import hashlib
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "research/vehicle-diagnostics/isg-format-source-applicability-20261021.json"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def exact_key_counts(data, keys):
    counts = dict.fromkeys(keys, 0)

    def walk(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key in counts:
                    counts[key] += 1
                walk(item)
        elif isinstance(value, list):
            for item in value:
                walk(item)

    walk(data)
    return counts


def main():
    report = json.loads(REPORT.read_text())
    require(report["schema"] == "denza-lab.isg-format-source-applicability.v1", "schema")
    for key in ("vehicleOperations", "adbOperations", "networkRequests", "newExtractions", "newDecompilations"):
        require(report[key] == 0, key)
    require(not report["sharedCodeChanged"], "no shared code mutation")
    require(report["qualifiedOemDefinitions"] == [] and report["qualifiedFormat01RawDisplayMappings"] == [], "no dictionary promotion")
    require(not report["applicableOriginalISGScriptRecovered"], "missing original ISG scenario")
    require(report["answer"]["scriptSelectionTuple"] == dict.fromkeys(("scriptCode", "scriptVersion", "forStation")), "no invented script tuple")

    sources = {source["id"]: source for source in report["sources"]}
    require(len(sources) == len(report["sources"]), "unique source IDs")
    for source in sources.values():
        raw = (ROOT / source["path"]).read_bytes()
        require(len(raw) == source["bytes"] and hashlib.sha256(raw).hexdigest() == source["sha256"], "source pin: " + source["id"])

    def source_text(key):
        return (ROOT / sources[key]["path"]).read_text()

    anchors = json.loads((ROOT / report["anchorsPath"]).read_text())
    for anchor in anchors:
        require(anchor["path"] == sources[anchor["source"]]["path"], "anchor source identity")
        lines = (ROOT / anchor["path"]).read_text().splitlines(keepends=True)
        text = "".join(lines[anchor["startLine"] - 1:anchor["endLine"]])
        require(text == anchor["text"] and hashlib.sha256(text.encode()).hexdigest() == anchor["sha256"], "source anchor")

    prior = json.loads(source_text("prior_full_isg_dictionary_candidates"))
    require(report["target"] == prior["target"], "exact retained ISG identity")
    require(report["target"]["measuredSoftwareCode"] == "48370011E0EA000000", "exact F194 software")
    require(report["target"]["dtcFormatIdentifier"] == 1, "observed format01 stays explicit")

    text = source_text("formatter_and_lookup")
    require("getSubFunc().intValue() == 2" in text and "getDtcList()" in text, "1902 gate and original dictionary")
    require("Objects.equals(Long.valueOf(dtc.getCode()), Long.valueOf(jLongValue))" in text, "numeric raw-code equality")
    require('}).findFirst().orElse("")' in text and "getDesc()" in text, "missing description remains empty")
    require('new String[]{"P", "C", "B", "U"}' in text and "& 63" in text and ">> 6" in text, "OEM notation arithmetic")
    formatter = report["formatter"]
    for key in ("formatIdentifierArgument", "formatIdentifierBranchInInspectedFunction", "format01SpecialCaseFound", "actualStockISGOutputObserved", "190AHandlerUsesThisRoutineProved"):
        require(not formatter[key], "conditional applicability: " + key)
    require(formatter["applicabilityVerdict"] == "UNPROVEN_FOR_ISG_48370011E0EA000000_FORMAT_01", "explicit verdict")
    for example in formatter["staticConditionalExamples"]:
        raw = bytes.fromhex(example["raw24Hex"])
        output = "PCBU"[raw[0] >> 6] + f"{raw[0] & 0x3F:02X}" + raw[1:].hex().upper()
        require(output == example["sourceRendererOutput"], "conditional source-renderer example")

    require("getContent(scriptRequest.getScriptCode(), scriptRequest.getScriptVersion(), scriptRequest.getForStation())" in source_text("run_binding"), "exact selected-script load")
    require("getSystemIds()" in source_text("run_binding") and "getSystemId()" in source_text("run_binding"), "module selection uses provided system IDs")
    require("getEcuAbbrName()" in source_text("case_selection") and ").findFirst();" in source_text("case_selection"), "attachment selection")
    require("getValidStartBit()" in source_text("step_execution") and "MatchUtils.log1902(attach, stepStatus, cellValue)" in source_text("step_execution"), "script response layout reaches formatter")
    require("attach.getCan().getReqCanId(), attach.getCan().getRspCanId()" in source_text("uds_route"), "attachment CAN route")
    attach_fields = re.findall(r"^    private (?:[\w<>]+) (\w+);$", source_text("attach_schema"), flags=re.M)
    require(attach_fields == report["originalSchema"]["attach"], "actual top-level attachment fields")
    require(not report["originalSchema"]["explicitSupplierOrF194SelectorInTheseBeans"], "no common bean supplier claim")

    storage = source_text("script_storage")
    for fragment in ('String.format("%s-%s-script.txt"', "getExternalFilesDir(str2)", "getFilesDir(), str2", "scriptDir.mkdir()", 'object.remove("data")', "JSON.parseObject(textFromFile, ScriptContent.class)"):
        require(fragment in storage, "actual persistence: " + fragment)
    persistence = report["persistence"]
    require(persistence["expectedUser0ExternalScript"] == "/sdcard/Android/data/com.byd.spotinspection/files/Scripts/<forStation>/<scriptCode>-<scriptVersion>-script.txt", "external path template")
    require(not persistence["javaGetterStrictlyReadOnly"] and not persistence["metadataSiblingContainsOriginalDtcList"], "getter and metadata boundaries")
    require(not persistence["futureMetadataOnlyCandidate"]["performed"], "no live existence check")

    provider = source_text("provider")
    require("openFile(" not in provider, "provider has no openFile override")
    provider_data = report["publicReadRoutes"]["spotInspectionExportedProvider"]
    require(provider_data["queryRow"] == [False, True, True, True, True], "constant provider row")
    for column in provider_data["queryColumns"]:
        require('"' + column + '"' in provider, "provider columns")
    require(not provider_data["scriptOrDtcRead"] and not provider_data["openFileOverride"], "provider cannot supply scripts")
    require(not report["publicReadRoutes"]["sourceProvenPassiveDictionaryApiFound"], "no passive API promotion")
    require(all(not item["dictionaryReadMethodFound"] for item in report["publicReadRoutes"]["exportedServices"]), "binder scope")

    projection = json.loads((ROOT / report["retainedMetadataJoin"]["projectionPath"]).read_text())
    total_hits = 0
    for item in projection["records"]:
        data = json.loads((ROOT / item["path"]).read_text())
        counts = exact_key_counts(data, projection["keys"])
        require(counts == item["exactKeyCounts"], "small metadata key projection")
        total_hits += sum(counts.values())
    ecu_map = json.loads(source_text("full_ecu_map"))
    require(len(ecu_map["ecus"]) == ecu_map["recordCount"] == projection["fullEcuMapRecordCount"] == 225, "full map scope")
    require(total_hits == report["retainedMetadataJoin"]["exactIdentityOrDtcListKeyHits"] == 0, "no selected-script identity in three metadata artifacts")
    require(not report["retainedMetadataJoin"]["applicableTupleRecovered"], "no fabricated identity join")

    low = json.loads(source_text("low_power_resource"))
    wrong = report["knownButWrongScenario"]
    require(low["scriptCode"] == wrong["scriptCode"] and low["scriptVer"] == wrong["scriptVersion"], "known wrong script tuple")
    require('setForStation("超低功耗")' in source_text("low_power_loader"), "station assigned in Java")
    attachments = [{"ecuAbbrName": item["ecuAbbrName"], "reqCanId": item["can"]["reqCanId"], "rspCanId": item["can"]["rspCanId"], "dtcListRows": len(item.get("dtcList", []))} for item in low["data"]["attach"]]
    require(attachments == wrong["attachProjection"], "wrong-scenario route projection")
    require({item["ecuAbbrName"] for item in attachments} == {"LA_BCM", "LBMS"}, "only LA_BCM/LBMS")
    require(all(item["reqCanId"] != 0x7E6 and 0x7EE not in item["rspCanId"] for item in attachments), "not ISG route")
    require(not wrong["ISGPresent"] and not wrong["ISGDictionaryQualified"], "wrong scenario rejected")

    public = json.loads(source_text("public_heading_index"))
    expected = [row for row in public["records"] if row[1] in ("P183800", "P183F00")]
    require(len(expected) == len(report["supplierVariantCandidates"]) == 2, "two supplier ambiguities")
    for original, row in zip(expected, report["supplierVariantCandidates"]):
        require([row["publicDisplayCode"], row["outlineId"], row["publicTitleZh"]] == original[1:], "unaltered public candidate title")
        require(row["sourceUrl"] == public["sourceUrl"] and row["sourceType"] == public["sourceType"], "public source boundary")
        require(row["qualifiedMeaning"] is None and row["supplierSelected"] is None and not row["format01MappingQualified"], "supplier/mapping unqualified")
    require(not report["upstreamAcquisitionBoundary"]["requestPerformed"] and not report["upstreamAcquisitionBoundary"]["publicUnauthenticatedDictionaryRouteEstablished"], "download route is not a public API")
    print(f"PASS sources={len(sources)} anchors={len(anchors)} metadata_artifacts=3 ecu_rows=225 script_identity_hits=0 applicable_ISG_scripts=0 qualified_definitions=0 qualified_format01_mappings=0")


if __name__ == "__main__":
    main()
