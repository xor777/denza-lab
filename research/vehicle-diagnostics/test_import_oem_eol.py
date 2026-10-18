"""Synthetic documents only: no car, OEM network, credentials or actual dictionary."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from import_oem_eol import ImportError as EolImportError, import_files


def documents():
    script = {
        "business": "MOTA", "formatVer": "3.0.0", "protocol": "UDS-FULL",
        "sender": "DOTA", "receiver": "PAD", "scriptCode": "SYNTHETIC",
        "scriptName": "Synthetic fixture", "scriptVer": "test-1",
        "vin": "SYNTHETIC-DO-NOT-EXPORT",
        "data": {
            "attach": [{
                "ecuAbbrName": "SYN_ECU", "ecuName": "Synthetic ECU",
                "bus": {"net": 0, "type": "CANFD"},
                "can": {"arbBaud": 500000, "datBaud": 2000000,
                        "reqCanId": 0x601, "rspCanId": [0x609, 0x60A]},
                "dtcList": [{"code": 0x123456, "desc": "Synthetic description"}],
                "dtcStatusList": [{"mask": 8, "status": "Synthetic status"}],
                "security": [{"secret": "DO-NOT-EXPORT-SECURITY"}],
            }],
            "systems": [{"systemId": "SYN_SYS", "cases": [{
                "caseId": "SYN_CASE", "ecuAbbrName": "SYN_ECU",
                "steps": [{"sid": 0x14, "marker": "DO-NOT-EXPORT-COMMAND"}],
            }]}],
        },
    }
    metadata = {"code": 200, "scriptVersion": "test-1",
                "scriptName": "Synthetic fixture", "forStations": ["SYN_STATION"],
                "systemIds": [{"systemId": "SYN_SYS", "priority": 1, "funcType": "test"}],
                "MD5Code": "0" * 32, "encryPolicyId": 49}
    return script, metadata


class ImportEolTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.script_path = Path(self.tmp.name) / "script.txt"
        self.metadata_path = Path(self.tmp.name) / "metadata.json"
        self.script, self.metadata = documents()

    def write(self):
        self.script_path.write_text(json.dumps(self.script), encoding="utf-8")
        self.metadata_path.write_text(json.dumps(self.metadata), encoding="utf-8")

    def run_import(self, **kwargs):
        self.write()
        return import_files(self.script_path, self.metadata_path, **kwargs)

    def test_preserves_exact_ecu_scoped_codes_routes_and_provenance(self):
        result = self.run_import()
        ecu = result["ecus"][0]
        self.assertEqual(ecu["ecuAbbrName"], "SYN_ECU")
        self.assertEqual(ecu["can"]["rspCanId"], [0x609, 0x60A])
        self.assertEqual(ecu["dtcList"][0], {"code": 0x123456, "rawHex": "123456",
                                          "desc": "Synthetic description"})
        self.assertEqual(ecu["dtcStatusList"], [{"mask": 8, "status": "Synthetic status"}])
        self.assertEqual(ecu["systemIds"], ["SYN_SYS"])
        self.assertEqual(result["script"]["station"], "SYN_STATION")
        self.assertEqual(result["provenance"]["plaintext"]["sha256"],
                         hashlib.sha256(self.script_path.read_bytes()).hexdigest())
        self.assertEqual(result["applicability"]["profile"], None)
        self.assertEqual(result["applicability"]["status"], "unverified")
        self.assertFalse(result["eligibleForProductAllowlist"])
        self.assertEqual(result["integrity"]["ciphertextMd5Verified"], False)

    def test_omits_vehicle_ids_tokens_security_commands_and_unknown_values(self):
        self.metadata["token"] = "DO-NOT-EXPORT-TOKEN"
        self.script["modelProfile"] = "DO-NOT-EXPORT-PROFILE"
        self.script["data"]["attach"][0]["can"]["frameFormat"] = 0
        result = self.run_import()
        serialized = json.dumps(result)
        for secret in ["DO-NOT-EXPORT", "SYNTHETIC-DO-NOT-EXPORT"]:
            self.assertNotIn(secret, serialized)
        self.assertIn("script.data.attach[0].can.frameFormat", result["unknownFields"])
        self.assertIn("script.modelProfile", result["unknownFields"])

    def test_metadata_must_match_successful_exact_script_version_and_systems(self):
        for field, value in [("code", 401), ("scriptVersion", "other"),
                             ("scriptName", "other"),
                             ("systemIds", [{"systemId": "OTHER"}])]:
            with self.subTest(field=field):
                self.script, self.metadata = documents()
                self.metadata[field] = value
                with self.assertRaises(EolImportError): self.run_import()

    def test_multiple_stations_need_explicit_choice_and_cannot_be_guessed(self):
        self.metadata["forStations"] = ["A", "B"]
        with self.assertRaises(EolImportError): self.run_import()
        self.assertEqual(self.run_import(station="B")["script"]["station"], "B")
        with self.assertRaises(EolImportError): self.run_import(station="C")

    def test_absent_optional_information_is_unknown_not_invented(self):
        del self.metadata["forStations"]
        attach = self.script["data"]["attach"][0]
        del attach["bus"]
        del attach["dtcList"]
        del attach["dtcStatusList"]
        result = self.run_import()
        self.assertIsNone(result["script"]["station"])
        self.assertIsNone(result["ecus"][0]["dtcList"])
        self.assertIsNone(result["ecus"][0]["dtcStatusList"])
        self.assertIsNone(result["ecus"][0]["bus"])
        self.assertIn("station", result["unknowns"])
        self.assertIn("ecus[0].dtcList", result["unknowns"])

    def test_frame_size_is_preserved_without_inferring_transport(self):
        attach = self.script["data"]["attach"][0]
        attach["frameSize"] = {"size": 8, "spanMs": 200, "future": "not-copied"}
        result = self.run_import()
        self.assertEqual(result["ecus"][0]["frameSize"], {"size": 8, "spanMs": 200})
        self.assertEqual(result["ecus"][0]["bus"]["type"], "CANFD")
        self.assertIn("script.data.attach[0].frameSize.future", result["unknownFields"])
        self.assertIn("ecus[0].frameSizeSemantics", result["unknowns"])
        del attach["bus"]
        attach["frameSize"] = {"size": 64, "spanMs": 0}
        result = self.run_import()
        self.assertIsNone(result["ecus"][0]["bus"])
        self.assertEqual(result["ecus"][0]["frameSize"]["size"], 64)

    def test_frame_size_missing_or_partial_remains_unknown_not_zero(self):
        result = self.run_import()
        self.assertIsNone(result["ecus"][0]["frameSize"])
        self.assertIn("ecus[0].frameSize", result["unknowns"])
        self.script["data"]["attach"][0]["frameSize"] = {"size": 8}
        result = self.run_import()
        self.assertEqual(result["ecus"][0]["frameSize"], {"size": 8, "spanMs": None})
        self.assertIn("ecus[0].frameSize.spanMs", result["unknowns"])

    def test_frame_size_follows_source_int32_types_not_an_invented_dlc_enum(self):
        attach = self.script["data"]["attach"][0]
        for field in ["size", "spanMs"]:
            for invalid in [True, "8", 8.0, -(2 ** 31) - 1, 2 ** 31]:
                attach["frameSize"] = {"size": 8, "spanMs": 20, field: invalid}
                with self.subTest(field=field, invalid=invalid), self.assertRaises(EolImportError):
                    self.run_import()
        # The source bean has int setters only: preserve unusual raw values while
        # frameSizeSemantics stays unknown, rather than implying transport validity.
        attach["frameSize"] = {"size": 7, "spanMs": -1}
        result = self.run_import()
        self.assertEqual(result["ecus"][0]["frameSize"], {"size": 7, "spanMs": -1})
        self.assertFalse(result["eligibleForProductAllowlist"])

    def test_invalid_numeric_values_are_never_coerced(self):
        cases = [("reqCanId", x) for x in [True, "0x601", 0, -1, 1.5, 0x20000000]]
        cases += [("rspCanId", x) for x in [[True], ["1545"], [-1], [0x20000000], []]]
        for field, value in cases:
            with self.subTest(field=field, value=value):
                self.script, self.metadata = documents()
                self.script["data"]["attach"][0]["can"][field] = value
                with self.assertRaises(EolImportError): self.run_import()
        for field, values in [("code", [True, "1193046", -1, 0x1000000, 1.0]),
                              ("mask", [True, "8", -1, 256, 8.0])]:
            for value in values:
                self.script, self.metadata = documents()
                key = "dtcList" if field == "code" else "dtcStatusList"
                self.script["data"]["attach"][0][key][0][field] = value
                with self.subTest(field=field, value=value), self.assertRaises(EolImportError):
                    self.run_import()

    def test_conflicting_or_ambiguous_ecu_routes_codes_masks_fail_closed(self):
        for change in ["ecu", "route", "dtc", "status", "response"]:
            with self.subTest(change=change):
                self.script, self.metadata = documents()
                attach = self.script["data"]["attach"][0]
                if change in ("ecu", "route"):
                    second = copy.deepcopy(attach)
                    if change == "route": second["ecuAbbrName"] = "SECOND"
                    self.script["data"]["attach"].append(second)
                elif change == "response": attach["can"]["rspCanId"].append(0x609)
                else:
                    key = "dtcList" if change == "dtc" else "dtcStatusList"
                    attach[key].append(copy.deepcopy(attach[key][0]))
                with self.assertRaises(EolImportError): self.run_import()

    def test_missing_attachment_unknown_case_link_and_wrong_format_fail_closed(self):
        for change in ["missing", "link", "format"]:
            self.script, self.metadata = documents()
            if change == "missing": self.script["data"]["attach"] = []
            elif change == "link":
                self.script["data"]["systems"][0]["cases"][0]["ecuAbbrName"] = "OTHER"
            else: self.script["formatVer"] = "2.0.0"
            with self.subTest(change=change), self.assertRaises(EolImportError): self.run_import()

    def test_same_raw_code_stays_scoped_to_ecu_and_bus_ambiguity_is_rejected(self):
        second = copy.deepcopy(self.script["data"]["attach"][0])
        second["ecuAbbrName"] = "SECOND"
        second["bus"]["net"] = 1
        second["dtcList"][0]["desc"] = "Different ECU meaning"
        self.script["data"]["attach"].append(second)
        self.script["data"]["systems"][0]["cases"].append({
            "caseId": "SECOND_CASE", "ecuAbbrName": "SECOND", "steps": []})
        result = self.run_import()
        self.assertEqual([x["dtcList"][0]["desc"] for x in result["ecus"]],
                         ["Synthetic description", "Different ECU meaning"])
        del second["bus"]
        with self.assertRaises(EolImportError): self.run_import()

    def test_functional_address_without_responses_is_explicitly_unresolved(self):
        can = self.script["data"]["attach"][0]["can"]
        can.update(reqCanId=0x7DF, rspCanId=[])
        result = self.run_import()
        self.assertEqual(result["ecus"][0]["can"]["rspCanId"], [])
        self.assertIn("ecus[0].responseRouting", result["unknowns"])

    def test_duplicate_json_keys_and_nonfinite_numbers_are_rejected(self):
        self.write()
        for raw in ['{"code":200,"code":401}', '{"code":NaN}', '{"code":Infinity}']:
            self.metadata_path.write_text(raw)
            with self.subTest(raw=raw), self.assertRaises(EolImportError):
                import_files(self.script_path, self.metadata_path)

    def test_cli_failure_writes_nothing_and_success_cannot_overwrite_input(self):
        self.metadata["scriptVersion"] = "bad"
        self.write()
        output = Path(self.tmp.name) / "output.json"
        cmd = [sys.executable, str(Path(__file__).with_name("import_oem_eol.py")),
               str(self.script_path), str(self.metadata_path), "--output", str(output)]
        failed = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(failed.returncode, 2)
        self.assertFalse(output.exists())
        self.assertEqual(failed.stdout, "")
        self.script, self.metadata = documents()
        self.write()
        original = self.script_path.read_bytes()
        cmd[-1] = str(self.script_path)
        failed = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(failed.returncode, 2)
        self.assertEqual(self.script_path.read_bytes(), original)

    def test_cli_success_produces_only_research_evidence(self):
        self.write()
        output = Path(self.tmp.name) / "output.json"
        result = subprocess.run([
            sys.executable, str(Path(__file__).with_name("import_oem_eol.py")),
            str(self.script_path), str(self.metadata_path), "--output", str(output)],
            capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "")
        evidence = json.loads(output.read_text())
        self.assertFalse(evidence["eligibleForProductAllowlist"])
        self.assertIn("plaintextMetadataBinding", evidence["unknowns"])


if __name__ == "__main__":
    unittest.main()
