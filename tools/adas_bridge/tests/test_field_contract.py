"""research/adas-bridge/field-contract-20261024.json against the Python oracle.

Every protobuf field stock.py can encode for an outbound topic and every key its route serializer
writes has a contract row; every row names code that still exists and evidence files that exist;
a row that differs from the stock is DECIDED only with a reason and evidence, else OPEN with a
named experiment. The Java side (FieldContractTest) also covers the 3 km lane beans. No capture
corpus is needed.
"""
import json
from pathlib import Path
import unittest

from tools.adas_bridge import stock

ROOT = Path(__file__).resolve().parents[3]
CONTRACT = ROOT / "research/adas-bridge/field-contract-20261024.json"
INBOUND = {"MMTPathSelected"}
REPO_DIRS = ("docs/", "research/", "tools/", "libraries/", "experiments/")


def load():
    return json.loads(CONTRACT.read_text(encoding="utf-8"))


def walk(msg, out):
    for _number, kind, name, sub in msg.fields:
        if kind == stock.MESSAGE:
            walk(sub, out)
            continue
        out.add(f"pb:{msg.name}.{name}")
        if kind == stock.MESSAGE_LIST:
            walk(sub, out)


def keys(path, value, out):
    if isinstance(value, dict):
        for k, v in value.items():
            keys(f"{path}.{k}", v, out)
    elif isinstance(value, list) and value and isinstance(value[0], dict):
        for item in value:
            keys(path + "[]", item, out)
    else:
        out.add("json:" + path)


def sample_path(groups):
    segments = []
    for s in range(2):
        link = stock.new_link()
        link.update(topoId=7 + s, length=120, roadName="Road", lon=[135000000 + s, 135000100 + s], lat=[199800000, 199800100])
        segments.append({"links": [link], "length": 120, "travelTime": 10, "mainAction": 2 if s == 0 else 0,
                         "assistantAction": 0 if s == 0 else 36, "trafficLightNum": 0})
    return {"pathId": 100002, "segments": segments, "trafficLights": [(37.5, 55.5)],
            "groups": [(0, 2, "Road")] if groups else []}


def outbound():
    return {name: msg for _topic, (name, msg) in stock.TOPICS.items() if name not in INBOUND}


def encoder_keys():
    out = set()
    for msg in outbound().values():
        walk(msg, out)
    for groups in (True, False):
        payload = stock.message_send(sample_path(groups), 1)
        keys("MessageSend", json.loads(payload), out)
        for chunk in stock.chunks(payload, 1, 1):
            keys("NaviDataDetail", json.loads(chunk), out)
    return out


def resolve(contract, file):
    head, _, rest = file.partition("/")
    prefix = contract["pathPrefixes"].get(head)
    return ROOT / (prefix + rest if prefix else file)


class FieldContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.contract = load()
        cls.rows = cls.contract["rows"]
        cls.covered = {key for r in cls.rows for key in r["wire"]}

    def test_every_oracle_field_has_a_row(self):
        missing = sorted(k for k in encoder_keys() if k not in self.covered)
        missing += sorted(f"topic:{n}" for n in outbound() if f"topic:{n}" not in self.covered)
        self.assertEqual([], missing)

    def test_rows_name_fields_the_encoders_write(self):
        # Lane beans (json:Lanes3km...) have no Python encoder; the Java test owns them.
        known = encoder_keys() | {f"topic:{n}" for n in outbound()}
        stale = [f"{r['id']} -> {k}" for r in self.rows for k in r["wire"]
                 if not k.startswith(("side:", "json:Lanes3km")) and k not in known]
        self.assertEqual([], stale)

    def test_code_locations_exist(self):
        broken = []
        for r in self.rows:
            if not r["code"] and r["provenance"]["class"] != "NOT_SENT":
                broken.append(f"{r['id']}: no code location")
            for at in r["code"]:
                path = resolve(self.contract, at["file"])
                if not path.is_file():
                    broken.append(f"{r['id']}: {at['file']} missing")
                    continue
                text = path.read_text(encoding="utf-8")
                if len(at["anchor"]) < 10 or at["anchor"] not in text:
                    broken.append(f"{r['id']}: {at['file']} no longer contains {at['anchor']!r}")
                if not 1 <= at["line"] <= len(text.splitlines()):
                    broken.append(f"{r['id']}: {at['file']}:{at['line']} outside the file")
        self.assertEqual([], broken)

    def test_decisions_carry_evidence(self):
        bad, ids = [], set()
        experiments = self.contract["experiments"]
        contradictions = {c["id"] for c in self.contract["contradictions"]}
        for r in self.rows:
            rid = r["id"]
            if rid in ids:
                bad.append(f"{rid}: duplicate id")
            ids.add(rid)
            if r["mark"] not in self.contract["enums"]["mark"]:
                bad.append(f"{rid}: mark {r['mark']}")
            if r["provenance"]["class"] not in self.contract["enums"]["provenance"]:
                bad.append(f"{rid}: provenance {r['provenance']['class']}")
            if r["noaRisk"]["level"] not in ("high", "medium", "low") or not r["noaRisk"]["why"]:
                bad.append(f"{rid}: noaRisk")
            if r["status"] == "DECIDED":
                decision = r.get("decision") or {}
                if not (decision.get("reason") or "").strip():
                    bad.append(f"{rid}: DECIDED without a reason")
                if r["mark"] == "DIFFERS_FROM_STOCK" and not decision.get("evidence"):
                    bad.append(f"{rid}: DIFFERS_FROM_STOCK DECIDED without evidence")
            elif r["status"] == "OPEN":
                if r.get("experiment") not in experiments:
                    bad.append(f"{rid}: OPEN without a known experiment")
            else:
                bad.append(f"{rid}: status {r['status']}")
            for c in r.get("contradictions", []):
                if c not in contradictions:
                    bad.append(f"{rid}: unknown contradiction {c}")
        for c in self.contract["contradictions"]:
            bad += [f"{c['id']}: unknown row {row}" for row in c["rows"] if row not in ids]
        self.assertEqual([], bad)

    def test_evidence_files_in_the_repository_exist(self):
        missing = []
        pending = set(self.contract.get("untrackedEvidence", {}))
        for r in self.rows:
            pointers = list(r["stock"]["evidence"]) + list((r.get("decision") or {}).get("evidence", []))
            for p in pointers:
                path = p.split("#")[0].split(" ")[0].split(":")[0]
                if path in pending:
                    continue
                if path.startswith(tuple(self.contract["pathPrefixes"])) and "/" in path:
                    target = resolve(self.contract, path)
                elif path.startswith(REPO_DIRS):
                    target = ROOT / path
                else:
                    continue
                if not target.exists():
                    missing.append(f"{r['id']}: {p}")
        self.assertEqual([], missing)


if __name__ == "__main__":
    unittest.main()
