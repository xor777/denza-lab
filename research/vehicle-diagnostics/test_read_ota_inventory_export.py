"""Synthetic streams and temporary files only; never contacts an export server."""
import hashlib
import json
from pathlib import Path
import stat
import struct
import tempfile
import unittest
from unittest.mock import patch
from xml.sax.saxutils import escape

import read_ota_inventory_export as reader
from test_read_ecu_cache_export import FragmentedSocket, download, listing


def prefs(value, key=reader.PREF_KEY, extra=""):
    return ('<?xml version="1.0" encoding="utf-8"?><map><string name="' + key + '">' +
            escape(json.dumps(value)) + '</string>' + extra + '</map>').encode()


ECU = {"ecuId": "HTH-2021_ECM_UAES_01200001", "ecuDiaId": "123/456",
       "logicAddress": None, "ecuSoftVer": "4.00.10", "ecuIp": "PRIVATE-IP",
       "ecuMac": "PRIVATE-MAC", "token": "PRIVATE-TOKEN"}
SCRIPT = {"scriptCode": "ECM", "scriptVersion": "1", "scriptFormatVer": "2",
          "forStation": "1", "scriptName": None, "url": "PRIVATE-URL",
          "headers": {"auth": "PRIVATE-TOKEN"}}


class OtaInventoryExportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.output = Path(self.tmp.name) / "export"
        self.sockets = []

    def run_reader(self, replies):
        replies = iter(replies)
        def connect(endpoint, timeout):
            self.assertEqual(endpoint, ("127.0.0.1", 42424))
            self.assertTrue(0 < timeout <= 2)
            sock = FragmentedSocket(next(replies), fragment=17)
            self.sockets.append(sock)
            return sock
        with patch.object(reader.socket, "create_connection", side_effect=connect):
            return reader.run_export("localhost", 42424, self.output)

    def test_four_exact_targets_only_projected_data_private_files_and_hashes(self):
        raw = [prefs([ECU], extra='<string name="session">PRIVATE-SESSION</string>'),
               prefs([ECU]),
               json.dumps({"ecuModel": [ECU], "normalModel": {"vin": "PRIVATE-VIN"}}).encode(),
               prefs({"header": {"token": "PRIVATE-TOKEN"}, "taskId": "PRIVATE-TASK",
                      "downloadCommand": {"scriptList": [SCRIPT], "url": "PRIVATE-URL"}},
                     key="RUNNING_TASK")]
        replies = []
        for path, data in zip(reader.ALLOWED_FILES, raw):
            name = Path(path).name
            replies.extend([listing((name, len(data))), download(data, name=name)])
        result = self.run_reader(replies)
        self.assertEqual(result["status"], "complete")
        self.assertEqual([r["status"] for r in result["files"]], ["saved"] * 4)
        self.assertEqual(len(self.sockets), 8)
        self.assertEqual(stat.S_IMODE(self.output.stat().st_mode), 0o700)
        for path in self.output.iterdir():
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertNotIn("PRIVATE", path.read_text())
            self.assertNotIn("<map>", path.read_text())
        for i, row in enumerate(result["files"]):
            doc = json.loads((self.output / row["savedAs"]).read_text())
            self.assertEqual(doc["rawSha256"], hashlib.sha256(raw[i]).hexdigest())
            self.assertEqual(doc["rawSizeBytes"], len(raw[i]))
            self.assertEqual(doc["source"], reader.ALLOWED_FILES[i])
            fields = reader.ECU_FIELDS if i < 3 else reader.SCRIPT_FIELDS
            rows = doc["ecus" if i < 3 else "scripts"]
            self.assertEqual(len(rows), 1)
            self.assertTrue(set(rows[0]) <= set(fields))
        for index, sock in enumerate(self.sockets):
            self.assertTrue(sock.closed)
            magic, action, size = struct.unpack(">III", sock.sent[:12])
            self.assertEqual((magic, action, size),
                             (reader.wire.MAGIC, 2 if index % 2 == 0 else 1, len(sock.sent) - 12))
            sent_path = next(iter(json.loads(sock.sent[12:]).values()))
            expected = reader.ALLOWED_FILES[index // 2]
            if action == 2:
                expected = str(Path(expected).parent)
            self.assertEqual(sent_path, reader.wire.EXPORT_PREFIX + expected.lstrip("/"))

    def test_original_client_allowlist_unchanged_and_other_paths_rejected_before_connect(self):
        self.assertEqual(len(reader.wire.ALLOWED_FILES), 8)
        self.assertTrue(set(reader.ALLOWED_FILES).isdisjoint(reader.wire.ALLOWED_FILES))
        with patch.object(reader.socket, "create_connection") as connect:
            for action, path in [(1, "/etc/passwd"), (2, "/"),
                                 (1, reader.wire.ALLOWED_FILES[0]),
                                 (3, reader.ALLOWED_FILES[0]), (True, reader.ALLOWED_FILES[0])]:
                with self.subTest(action=action, path=path), self.assertRaises(reader.ExportError):
                    reader._exchange(("127.0.0.1", 42424), action, path, reader.wire.Deadline())
            connect.assert_not_called()

    def test_xml_dtd_entities_utf16_malformed_and_duplicate_keys_fail_closed(self):
        samples = [
            b'<!DOCTYPE map [<!ENTITY x "PRIVATE">]><map/>',
            b'<!DOCTYPE map SYSTEM "file:///PRIVATE"><map/>',
            '<!DOCTYPE map [<!ENTITY x "PRIVATE">]><map/>'.encode("utf-16"),
            b'<map><string name="x">a</string><string name="x">b</string></map>',
            b'<map><string name="ecuDiagnosisResultListInfo">[]</string>'
            b'<string name="ecuDiagnosisResultListInfo">[]</string></map>',
            b'<map><int name="ecuDiagnosisResultListInfo" value="1"/></map>',
            b'<map><string name="ecuDiagnosisResultListInfo"><x/></string></map>',
            b'<map><string name="x">', b'<not-map/>',
        ]
        for raw in samples:
            with self.subTest(raw=raw[:20]), self.assertRaises(reader.ExportError) as caught:
                reader.project_source(reader.ALLOWED_FILES[0], raw)
            self.assertNotIn("PRIVATE", str(caught.exception))

    def test_invalid_json_duplicate_fields_nonfinite_and_row_types_fail_closed(self):
        for value in [{}, [None], ["ECM"], [{"ecuId": 1}],
                      [{"ecuId": "ECM", "ecuDiaId": 123}],
                      [{"ecuId": "ECM", "ecuSoftVer": True}]]:
            with self.subTest(value=value), self.assertRaises(reader.ExportError):
                reader.project_source(reader.ALLOWED_FILES[0], prefs(value))
        for payload in ['[{"ecuId":"ECM","ecuId":"OTHER"}]', '[NaN]', '[Infinity]', 'PRIVATE']:
            raw = ('<map><string name="' + reader.PREF_KEY + '">' +
                   escape(payload) + '</string></map>').encode()
            with self.subTest(payload=payload), self.assertRaises(reader.ExportError):
                reader.project_source(reader.ALLOWED_FILES[0], raw)
        for obj in [{}, {"downloadCommand": None}, {"downloadCommand": {"scriptList": None}},
                    {"downloadCommand": {"scriptList": [{"scriptCode": "X", "scriptVersion": 1}]}}]:
            with self.subTest(obj=obj), self.assertRaises(reader.ExportError):
                reader.project_source(reader.ALLOWED_FILES[3], prefs(obj, key="RUNNING_TASK"))

    def test_missing_key_distinct_and_no_fallback_to_other_preferences(self):
        raw = prefs([ECU], key="other")
        name = Path(reader.ALLOWED_FILES[0]).name
        result = self.run_reader([listing((name, len(raw))), download(raw, name=name)] + [listing()] * 3)
        self.assertEqual(result["status"], "complete")
        self.assertEqual([r["status"] for r in result["files"]],
                         ["key_missing", "missing", "missing", "missing"])
        self.assertEqual([p.name for p in self.output.iterdir()], ["report.json"])

    def test_parse_failure_saves_only_sanitized_report_not_raw_or_partial_projection(self):
        raw = prefs([ECU, {"ecuId": "ECM", "ecuDiaId": ["PRIVATE"]}])
        name = Path(reader.ALLOWED_FILES[0]).name
        result = self.run_reader([listing((name, len(raw))), download(raw, name=name)])
        self.assertEqual(result["status"], "aborted")
        self.assertEqual(result["error"], "invalid_inventory_field_type")
        self.assertEqual(len(self.sockets), 2)
        self.assertEqual([p.name for p in self.output.iterdir()], ["report.json"])
        self.assertNotIn("PRIVATE", (self.output / "report.json").read_text())

    def test_empty_null_selected_preferences_are_no_data_and_continue_all_sources(self):
        empty = b'<map><string name="ecuDiagnosisResultListInfo">  </string></map>'
        raw = [empty, prefs(None), b'{"ecuModel":[]}', prefs(None, key="RUNNING_TASK")]
        replies = []
        for path, data in zip(reader.ALLOWED_FILES, raw):
            name = Path(path).name
            replies.extend([listing((name, len(data))), download(data, name=name)])
        result = self.run_reader(replies)
        self.assertEqual(result["status"], "complete")
        self.assertEqual([r["status"] for r in result["files"]],
                         ["key_no_data", "key_no_data", "saved", "key_no_data"])
        self.assertEqual(len(self.sockets), 8)
        self.assertEqual(sorted(p.name for p in self.output.iterdir()),
                         [reader.OUTPUT_NAMES[2], "report.json"])
        for row in (result["files"][0], result["files"][1], result["files"][3]):
            self.assertNotIn("recordCount", row)

    def test_oversized_source_is_not_requested_and_malformed_upgrade_rejected(self):
        name = Path(reader.ALLOWED_FILES[0]).name
        result = self.run_reader([listing((name, reader.wire.MAX_FILE + 1))])
        self.assertEqual(result["error"], "file_size_out_of_bounds")
        self.assertEqual(len(self.sockets), 1)
        for raw in [b'{}', b'{"ecuModel":{}}', b'{"ecuModel":null}', b'{"ecuModel":[],"ecuModel":[]}']:
            with self.subTest(raw=raw), self.assertRaises(reader.ExportError):
                reader.project_source(reader.ALLOWED_FILES[2], raw)

    def test_existing_output_rejected_without_connecting(self):
        self.output.mkdir()
        with patch.object(reader.socket, "create_connection") as connect:
            with self.assertRaisesRegex(reader.ExportError, "output_already_exists"):
                reader.run_export("localhost", 42424, self.output)
            connect.assert_not_called()


if __name__ == "__main__":
    unittest.main()
