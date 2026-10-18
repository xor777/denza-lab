import json
import unittest

from extract_ota_ecu_prefix import extract, extract_prefix


ROW = {"ecuId": "HTH-2021_TEST_001", "ecuDiaId": "720/728",
       "ecuReprogramType": "canfd", "ecuDId": "F195/F194"}
HEAD = '{"status":"100","ecuList":['


class PrefixTests(unittest.TestCase):
    def test_truncated_object_is_not_repaired(self):
        rows, state = extract_prefix(HEAD + json.dumps(ROW) + ',{"ecuId":"INCOMPLETE"')
        self.assertEqual(1, len(rows))
        self.assertEqual("incomplete_or_invalid_object", state)
        self.assertFalse(rows[0]["eligibleForProductAllowlist"])

    def test_missing_member_delimiter_is_not_repaired(self):
        self.assertEqual([], extract_prefix(HEAD + json.dumps(ROW))[0])

    def test_no_recovery_after_invalid_member(self):
        rows, state = extract_prefix(HEAD + 'null,' + json.dumps(ROW) + ']}')
        self.assertEqual([], rows)
        self.assertEqual("invalid_tail", state)

    def test_duplicate_keys_rejected(self):
        rows, state = extract_prefix(HEAD + '{"ecuId":"A","ecuId":"B"}]}')
        self.assertEqual([], rows)
        self.assertEqual("invalid_object", state)

    def test_only_success_exact_schema(self):
        self.assertIsNone(extract_prefix('{"status":"111","ecuList":[]}'))
        self.assertIsNone(extract_prefix('{"unrelated":{"status":"100","ecuList":[]}}'))

    def test_ethernet_is_not_can(self):
        row = dict(ROW, ecuReprogramType="ethernet", ecuDiaId="010/001", logicAddress="0012")
        route = extract_prefix(HEAD + json.dumps(row) + ']}')[0][0]["route"]
        self.assertEqual({"kind": "ethernet", "pairTarget": 16, "pairClient": 1, "target": 18}, route)

    def test_does_not_assume_response_plus_eight(self):
        row = dict(ROW, ecuDiaId="123/456")
        self.assertEqual(0x456, extract_prefix(HEAD + json.dumps(row) + ']}')[0][0]["route"]["responseId"])

    def test_privacy_and_provenance(self):
        row = dict(ROW, token="PRIVATE", vin="PRIVATE", ecuIp="PRIVATE", ecuMac="PRIVATE")
        line = '10-18 14:38:38.004 4228 13861 I OTA_GCY_UPGRADE_HttpApiService: responseStr :'
        raw = (line + HEAD + json.dumps(row) + ']}').encode()
        report = extract(raw, "saved.txt")
        self.assertNotIn("PRIVATE", json.dumps(report))
        self.assertEqual(1, report["responses"][0]["line"])
        self.assertIsNone(report["profile"])
        self.assertEqual("complete_response", report["responses"][0]["responseState"])

    def test_no_shared_address_deduplication(self):
        rows, _ = extract_prefix(HEAD + json.dumps(ROW) + ',' + json.dumps(dict(ROW, ecuId="OTHER", ecuDId="C495/C494")) + ']}')
        self.assertEqual(2, len(rows))
        self.assertNotEqual(rows[0]["ecuDId"], rows[1]["ecuDId"])

    def test_trailing_comma_not_complete(self):
        rows, state = extract_prefix(HEAD + json.dumps(ROW) + ',]}')
        self.assertEqual(1, len(rows))
        self.assertEqual("invalid_tail", state)


if __name__ == "__main__":
    unittest.main()
