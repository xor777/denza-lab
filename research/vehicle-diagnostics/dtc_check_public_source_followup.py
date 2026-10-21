#!/usr/bin/env python3
"""Verify the retained 21 October public-source follow-up; never use the network."""

import hashlib
import json
import re
from html.parser import HTMLParser
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "research/vehicle-diagnostics/oem-dtc-public-source-followup-20261021.json"


class Nodes(HTMLParser):
    def __init__(self):
        super().__init__()
        self.active = False
        self.parts = []
        self.nodes = []

    def handle_starttag(self, tag, attrs):
        if tag == "span" and dict(attrs).get("class") == "node-text":
            self.active, self.parts = True, []

    def handle_data(self, data):
        if self.active:
            self.parts.append(data)

    def handle_endtag(self, tag):
        if self.active and tag == "span":
            self.nodes.append("".join(self.parts))
            self.active = False


def read(path):
    return (ROOT / path).read_bytes()


def main():
    report = json.loads(REPORT.read_text())
    for source in report["retained_sources"]:
        payload = read(source["path"])
        assert len(payload) == source["bytes"], source["path"]
        assert hashlib.sha256(payload).hexdigest() == source["sha256"], source["path"]

    live = json.loads(read(report["ecm_calibration_anchor"]["source"]))
    responses = {row["requestHex"]: bytes.fromhex(row["responseHex"]) for row in live["responses"]}
    assert responses["22F194"].hex().upper() == "62F19432440011E004000000"
    cal = responses["0904"]
    assert cal[:3] == bytes.fromhex("490401") and len(cal[3:]) == 16
    assert cal[3:].rstrip(b"\0").decode("ascii") == "Z31L07AB01"
    assert cal[3:].endswith(b"\0" * 6)
    assert set(live["fixedPlan"]) == {"22F194", "010C", "0900", "0904", "090A"}

    parser = Nodes()
    parser.feed(read(report["cross_model_catalog_observation"]["source"]).decode())
    other_codes = {
        match.group(0)
        for node in parser.nodes
        if (match := re.match(r"[PCBU][0-9A-F]{6}\b", node))
    }
    candidates = json.loads(read(report["cross_model_catalog_observation"]["comparison_index"]))
    overlap = {}
    for section, expected in report["cross_model_catalog_observation"]["overlap"].items():
        target = {row[1] for row in candidates["records"] if row[0] == section}
        overlap[section] = len(target & other_codes)
        assert len(target) == expected["z9gt_distinct_codes"]
        assert overlap[section] == expected["exact_code_overlap"]
    rear_right = {row[1] for row in candidates["records"] if row[0] == "rear_right"}
    assert sorted(rear_right - other_codes) == ["P1B0419"]
    assert "P1B0429" in other_codes

    capture = "captures/diagnostics-followup-20261021/oem/"
    assert "/cn/auto-environmental-information" in read(capture + "byd-home.html").decode()
    emissions = read(capture + "byd-emissions-public.html").decode()
    assert "请输入信息公开编号" in emissions and "请输入VIN号进行查询" in emissions
    entry = read(capture + "byd-emissions-public-entry.js").decode()
    assert "getCarType" in entry and "downloadBatteryInfo" in entry
    public_manual = read(capture + "tis-public-hv-example.html").decode()
    assert "Power-off and power-on of high-voltage electrical system" in public_manual
    assert "DTC" in public_manual
    assert report["qualified_oem_dtc_definitions"] == []
    assert report["qualified_software_bindings"] == []
    print(json.dumps({"verified_sources": len(report["retained_sources"]),
                      "calibration_id": "Z31L07AB01", "overlap": overlap,
                      "qualified_oem_dtc_definitions": 0}, ensure_ascii=False))


if __name__ == "__main__":
    main()
