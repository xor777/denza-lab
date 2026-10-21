import json
import unittest
from unittest.mock import patch
from xml.sax.saxutils import escape

import read_ota_history as reader


class HistoryTests(unittest.TestCase):
    def test_projection_keeps_versions_and_excludes_identity_secrets(self):
        row = {"ecuId": "ZCU", "ecuOriginSVer": "1", "ecuSoftVer": "2",
               "ecuIp": "private", "ecuReason": "private", "isRepairEcu": "1",
               "ecuNewestInfo": {"ecuSoftVer": "3", "secret": "private"},
               "subModel": [{"subModelId": "A", "ecuOriginSVer": "4", "secret": "private"}]}
        raw = json.dumps({"uuid": "private", "ecuModel": [row]}).encode()
        result = reader.project(reader.RESULTS[0], raw)
        self.assertNotIn("private", json.dumps(result))
        self.assertEqual(result["ecus"][0]["ecuOriginSVer"], "1")
        self.assertEqual(result["ecus"][0]["ecuNewestInfo"], {"ecuSoftVer": "3"})

    def test_preferences_are_selected_by_exact_key(self):
        row = [{"ecuId": "ZCU", "ecuOriginSVer": "1", "isRepairEcu": "1"}]
        raw = ('<map><string name="token">private</string><string name="repairUpgradeEcuListInfo">'
               + escape(json.dumps(row)) + '</string></map>').encode()
        result = reader.project(reader.PREFS[0], raw)
        self.assertNotIn("private", json.dumps(result))
        self.assertEqual(result["preferences"][reader.PREF_KEYS[0]]["ecus"], row)
        self.assertEqual(result["preferences"][reader.PREF_KEYS[1]]["status"], "key_missing")

    def test_null_and_missing_preferences_are_distinct(self):
        raw = b'<map><string name="repairUpgradeEcuListInfo">null</string></map>'
        result = reader.project(reader.PREFS[0], raw)["preferences"]
        self.assertEqual(result[reader.PREF_KEYS[0]]["status"], "key_no_data")
        self.assertEqual(result[reader.PREF_KEYS[1]]["status"], "key_missing")

    def test_unsafe_or_ambiguous_input_rejected(self):
        for raw in (b'<!DOCTYPE map [<!ENTITY e "x">]><map/>',
                    b'<map><string name="repairUpgradeEcuListInfo">[]</string>'
                    b'<string name="repairUpgradeEcuListInfo">[]</string></map>'):
            with self.assertRaises(reader.wire.ExportError):
                reader.project(reader.PREFS[0], raw)
        with self.assertRaises(reader.wire.ExportError):
            reader.project(reader.RESULTS[0], b'{"ecuModel":[],"ecuModel":[]}')

    def test_fixed_read_only_requests(self):
        for action in (0, 3, 4, True, "1"):
            with self.assertRaises(reader.wire.ExportError):
                reader.request(action, reader.FILES[0])
        with self.assertRaises(reader.wire.ExportError):
            reader.request(1, "/data/secret")
        self.assertTrue(reader.request(1, reader.FILES[0]))
        self.assertTrue(reader.request(2, next(iter(reader.DIRECTORIES))))

    def test_export_closes_before_forward_and_lease_cleanup_on_failure(self):
        events = []

        class Device:
            def preflight(self): return "123"
            def command(self, args):
                if args == ["forward", "--list"]: return "existing"
                if args == ["forward", "tcp:0", "tcp:46321"]: return "48123"
                if args == ["forward", "--remove", "tcp:48123"]:
                    events.append("forward_removed")
                    return ""
                raise AssertionError(args)
            def shell(self, _): return ""
            def claim(self, *_): events.append("claimed")
            def stage_history(self, *_): pass
            def arm(self): pass
            def start(self): pass
            def close_export(self, _): events.append("closed")
            def release_history(self, _): events.append("released")
            def identity(self): pass
            def ports(self): return set()

        with patch.object(reader, "read_history", side_effect=reader.wire.ExportError("socket_timeout")):
            result = reader.run(Device())
        self.assertEqual(result["error"], "socket_timeout")
        self.assertTrue(result["leaseReleased"] and result["forwardRemoved"])
        self.assertEqual(events, ["claimed", "closed", "forward_removed", "released"])


if __name__ == "__main__":
    unittest.main()
