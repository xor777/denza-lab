#!/usr/bin/env python3
"""Offline declared-payload, source-renderer and unqualified heading comparison."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[2]
PATH = ROOT / "research/vehicle-diagnostics/isg-supported-dictionary-candidates-20261021.json"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    data = json.loads(PATH.read_text())
    require(data["schema"] == "denza-lab.isg-supported-dictionary-candidates.v1", "schema")
    for key in ("vehicleOperations", "adbOperations", "networkRequests"):
        require(data[key] == 0, key)
    for key in ("qualifiedOemDefinitions", "qualifiedFormat01RawDisplayMappings", "qualifiedWarningDtcLinks"):
        require(data[key] == [], key)
    for source in data["sources"]:
        raw = (ROOT / source["path"]).read_bytes()
        require(len(raw) == source["bytes"] and hashlib.sha256(raw).hexdigest() == source["sha256"], "source: " + source["path"])

    text = (ROOT / "captures/diagnostics-can-visibility-20261021/live-01/probe.txt").read_text()

    def event(tag, seq):
        matches = [(number, line) for number, line in enumerate(text.splitlines(), 1)
                   if line.startswith(tag + " ") and re.search(r"\bseq=" + str(seq) + r"\b", line)]
        require(len(matches) == 1, "one event: " + tag)
        number, line = matches[0]
        return number, dict(field.split("=", 1) for field in line.split()[1:])

    _, rejected = event("REJECTED_FRAME", 6)
    raw = bytes.fromhex(rejected["raw"])
    require(len(raw) == 240 and raw[:7] == bytes.fromhex("0A07EEE7010101"), "complete successful gateway envelope")
    payload = raw[7:7 + raw[3]]
    tail = raw[7 + raw[3]:]
    _, frame = event("FRAME", 6)
    _, response = event("RESPONSE", 6)
    require(bytes.fromhex(frame["raw"]) == raw[:238], "FRAME is only the declared frame")
    require(bytes.fromhex(response["uds"]) == payload, "RESPONSE agrees with declared payload")
    require(payload[:3] == bytes.fromhex("590A09") and len(payload) == 231, "positive complete231-byte payload")
    require(tail == bytes.fromhex("0000"), "two bytes excluded outside declared payload")
    require(data["outerResponse"]["payloadHex"] == payload.hex().upper(), "payload retained")
    require(data["outerResponse"]["payloadSha256"] == hashlib.sha256(payload).hexdigest(), "payload hash")
    _, identity = event("RESPONSE", 4)
    _, count = event("RESPONSE", 5)
    require(identity["uds"] == "62F19448370011E0EA000000", "exact F194")
    require(count["uds"] == "590109010000" and data["target"]["dtcFormatIdentifier"] == 1, "format01 from separate1901")

    tuples = [(payload[offset:offset + 3].hex().upper(), f"{payload[offset + 3]:02X}")
              for offset in range(3, len(payload), 4)]
    observation = data["rawRegistryObservation"]
    require([(row["raw24Hex"], row["statusHex"]) for row in observation["records"]] == tuples, "ordered raw/status tuples")
    require(len(tuples) == observation["recordCount"] == 57, "57 records")
    codes = {code for code, _ in tuples}
    require(len(codes) == observation["uniqueRawCodeCount"] == 56, "56 distinct codes")
    require(all(status == "00" for _, status in tuples), "all statuses00")
    require(Counter(code for code, _ in tuples)["C1D487"] == 2, "duplicate retained")
    require([i for i, (code, _) in enumerate(tuples, 1) if code == "C1D487"] == [28, 40], "duplicate ordinals")
    require(observation["duplicates"] == [{"raw24Hex": "C1D487", "ordinals": [28, 40], "statuses": ["00", "00"]}], "exact duplicate projection")
    require(all(row["qualifiedDisplayCode"] is None and row["qualifiedFactoryMeaning"] is None for row in observation["records"]), "main representation stays raw")
    require(not data["readerOutcome"]["acceptedRegistryByExecutedReader"] and not data["readerOutcome"]["planComplete"], "failed reader outcome preserved")
    require(rejected["reason"] == data["readerOutcome"]["rejectedReason"] == "DUPLICATE_DTC_CODE", "reader rejection reason")
    require(data["readerOutcome"]["endExact"] in text, "original END retained")

    index = json.loads((ROOT / "research/vehicle-diagnostics/oem-dtc-public-index-20261020.json").read_text())
    generator = [row for row in index["records"] if row[0] == "generator"]
    hypothesis = data["UNPROVEN_reverseNotationHypothesis"]
    require(not hypothesis["format01MappingQualified"] and not hypothesis["factoryMeaningQualified"], "hypothesis never promoted")

    def reverse(code):
        value = ("PCBU".index(code[0]) << 14) | (int(code[1]) << 12) | int(code[2:5], 16)
        return value.to_bytes(2, "big").hex().upper() + code[5:]

    mapped = {reverse(row[1]) for row in generator}
    require(len(generator) == 32 and mapped <= codes, "32/32 conditional heading inclusion")
    require(hypothesis["matchedPublicHeadingRows"] == hypothesis["matchedDistinctRawCodes"] == 32, "hypothetical match counts")
    require(sorted(codes - mapped) == hypothesis["rawCodesWithoutHypotheticalHeading"], "exact raw complement")
    require(len(codes - mapped) == hypothesis["rawCodesWithoutHypotheticalHeadingCount"] == 24, "24 raw codes without candidate heading")
    for original, projected in zip(generator, hypothesis["rows"]):
        require((projected["subsystem"], projected["publicDisplayCode"], projected["outlineId"], projected["publicTitleZh"]) == tuple(original), "original heading identity")
        require(projected["hypotheticalRaw24Hex"] == reverse(original[1]), "explicit reverse-notation assumption")
        require(projected["matchingOrdinals"] == [i for i, (code, _) in enumerate(tuples, 1) if code == reverse(original[1])], "matching positions")
        require(not projected["factoryMeaningQualified"] and not projected["format01MappingQualified"], "per-heading boundary")

    prior = json.loads((ROOT / "research/vehicle-diagnostics/isg-gateway-trace-20261021.json").read_text())["reconstruction"]
    prefix = bytes.fromhex(prior["udsPrefixHex"])
    require(len(prefix) == 107 and payload.startswith(prefix), "old partial prefix matches")
    require(len(payload) - len(prefix) == data["comparisonWithEarlierPartialTrace"]["additionalDeclaredPayloadBytesNowObserved"] == 124, "124 newly retained bytes")
    require(57 - prior["completeRecordTuplesRetained"] == data["comparisonWithEarlierPartialTrace"]["additionalOrderedRecordTuplesNowObserved"] == 31, "31 additional ordered tuples")

    renderer = data["stockRendererEvidence"]
    anchors = json.loads((ROOT / renderer["primarySourceAnchors"]).read_text())
    for anchor in anchors:
        lines = (ROOT / anchor["path"]).read_text().splitlines(keepends=True)
        raw_anchor = "".join(lines[anchor["startLine"] - 1:anchor["endLine"]]).encode()
        require(raw_anchor.decode() == anchor["text"] and hashlib.sha256(raw_anchor).hexdigest() == anchor["sha256"], "renderer source anchor")

    def stock_render(raw_hex):
        raw_bytes = bytes.fromhex(raw_hex)
        return "PCBU"[raw_bytes[0] >> 6] + f"{raw_bytes[0] & 0x3F:02X}" + raw_bytes[1:].hex().upper()

    for example in renderer["staticExamples"]:
        require(stock_render(example["raw24Hex"]) == example["sourceRendererOutput"], "source-renderer arithmetic")
    require(not renderer["formatIdentifierArgument"] and not renderer["qualifiedFormat01Mapping"]
            and not renderer["executedISGScriptApplicabilityProved"] and not renderer["liveSourceRendererOutputObserved"], "conditional source-renderer scope")
    require(not data["dictionarySearchDelta"]["genericDefinitionsImported"] and not data["dictionarySearchDelta"]["productDictionaryUpdated"], "no generic/product promotion")
    print(f"PASS sources={len(data['sources'])} declared_uds_bytes=231 records=57 unique_raw=56 duplicated_C1D487=2 hypothesis_matches=32 raw_without_heading=24 qualified_definitions=0")


if __name__ == "__main__":
    main()
