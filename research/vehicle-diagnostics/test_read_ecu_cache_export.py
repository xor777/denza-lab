"""Synthetic fragmented streams only. No server, TCP connection, ADB or car."""
import hashlib
import json
from pathlib import Path
import socket
import stat
import struct
import tempfile
import unittest
from unittest.mock import patch

import read_ecu_cache_export as reader


def envelope(doc, payload=b"", status=0):
    raw = json.dumps(doc).encode()
    return struct.pack(">III", reader.MAGIC, status, len(raw)) + raw + payload


def listing(*rows):
    return envelope({"code": 0, "files": [{"name": name, "size": size} for name, size in rows]})


def download(raw=b"fixture", name="strategy.json", **overrides):
    doc = {"code": 0, "fileName": name, "fileSize": len(raw),
           "md5": hashlib.md5(raw).hexdigest()}
    doc.update(overrides)
    return envelope(doc, raw)


class FragmentedSocket:
    def __init__(self, raw, fragment=1):
        self.raw = raw
        self.fragment = fragment
        self.sent = b""
        self.timeouts = []
        self.closed = False

    def __enter__(self): return self
    def __exit__(self, *_): self.closed = True
    def settimeout(self, value): self.timeouts.append(value)
    def sendall(self, raw): self.sent += raw
    def recv(self, count):
        if isinstance(self.raw, Exception): raise self.raw
        result, self.raw = self.raw[:min(count, self.fragment)], self.raw[min(count, self.fragment):]
        return result


class ReadEcuCacheExportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.output = Path(self.tmp.name) / "export"
        self.sockets = []

    def run_reader(self, replies, **kwargs):
        replies = iter(replies)
        def connect(endpoint, timeout):
            self.assertEqual(endpoint, ("127.0.0.1", 42424))
            self.assertGreater(timeout, 0)
            self.assertLessEqual(timeout, 2)
            sock = FragmentedSocket(next(replies), **kwargs)
            self.sockets.append(sock)
            return sock
        with patch.object(reader.socket, "create_connection", side_effect=connect):
            return reader.run_export("localhost", 42424, self.output)

    def test_fragmentation_exact_paths_list_before_download_and_private_output(self):
        raw = b'{"vin":"PRIVATE-FIXTURE","token":"PRIVATE-TOKEN"}'
        result = self.run_reader([listing(("strategy.json", len(raw))), download(raw)]
                                 + [listing()] * 4)
        self.assertEqual(result["status"], "complete")
        self.assertEqual([r["status"] for r in result["files"]], ["saved"] + ["missing"] * 7)
        self.assertEqual((self.output / "01-strategy.json").read_bytes(), raw)
        self.assertNotIn("PRIVATE", json.dumps(result))
        self.assertEqual(stat.S_IMODE(self.output.stat().st_mode), 0o700)
        for path in self.output.iterdir():
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        for sock in self.sockets:
            self.assertTrue(sock.closed)
            self.assertTrue(all(0 < t <= 2 for t in sock.timeouts))
            magic, action, length = struct.unpack(">III", sock.sent[:12])
            self.assertEqual(magic, reader.MAGIC)
            self.assertIn(action, (1, 2))
            self.assertEqual(length, len(sock.sent) - 12)
            doc = json.loads(sock.sent[12:])
            path = next(iter(doc.values()))
            allowed = reader.ALLOWED_FILES if action == 1 else reader.ALLOWED_DIRECTORIES
            self.assertIn(path, [reader.EXPORT_PREFIX + p.lstrip("/") for p in allowed])
        self.assertEqual([struct.unpack(">III", s.sent[:12])[1] for s in self.sockets],
                         [2, 1, 2, 2, 2, 2])

    def test_directory_and_download_missing_continue_without_saving_remote_messages(self):
        missing = envelope({"code": 1, "message": "PRIVATE-VIN"}, status=1)
        result = self.run_reader([listing(("strategy.json", 7)), missing] + [missing] * 4)
        self.assertEqual(result["status"], "complete")
        self.assertTrue(all(r["status"] == "missing" for r in result["files"]))
        self.assertNotIn("PRIVATE", (self.output / "report.json").read_text())
        self.assertEqual(len(list(self.output.iterdir())), 1)

    def assert_abort(self, replies, code):
        result = self.run_reader(replies)
        self.assertEqual(result["status"], "aborted")
        self.assertEqual(result["error"], code)
        self.assertTrue(all(s.closed for s in self.sockets))
        self.assertFalse((self.output / "01-strategy.json").exists())
        return result

    def test_remote_errors_abort_without_echoing_server_data(self):
        result = self.assert_abort([envelope({"code": 5, "message": "PRIVATE-TOKEN"}, status=5)],
                                   "remote_error_5")
        self.assertNotIn("PRIVATE", json.dumps(result))
        self.assertEqual(len(self.sockets), 1)

    def test_json_length_bound_checked_before_payload_read(self):
        self.assert_abort([struct.pack(">III", reader.MAGIC, 0, reader.MAX_JSON + 1)],
                          "json_length_out_of_bounds")

    def test_oversized_listed_file_never_downloaded(self):
        self.assert_abort([listing(("strategy.json", reader.MAX_FILE + 1))],
                          "file_size_out_of_bounds")
        self.assertEqual(len(self.sockets), 1)

    def test_oversized_download_metadata_aborts_before_body(self):
        self.assert_abort([listing(("strategy.json", 7)),
                           download(fileSize=reader.MAX_FILE + 1)], "file_size_out_of_bounds")
        self.assertEqual(self.sockets[1].raw, b"fixture")

    def test_exact_file_limit_is_accepted(self):
        raw = b"x" * reader.MAX_FILE
        result = self.run_reader([listing(("strategy.json", len(raw))), download(raw)]
                                 + [listing()] * 4, fragment=8192)
        self.assertEqual(result["status"], "complete")
        self.assertEqual((self.output / "01-strategy.json").stat().st_size, reader.MAX_FILE)

    def test_zero_byte_file_is_verified_and_saved(self):
        result = self.run_reader([listing(("strategy.json", 0)), download(b"")]
                                 + [listing()] * 4)
        self.assertEqual(result["status"], "complete")
        self.assertEqual((self.output / "01-strategy.json").read_bytes(), b"")

    def test_conflicting_file_size_and_name_abort(self):
        self.assert_abort([listing(("strategy.json", 9)), download()], "file_size_conflict")

    def test_conflicting_download_name_aborts(self):
        self.assert_abort([listing(("strategy.json", 7)), download(name="PRIVATE-NAME")],
                          "file_name_conflict")

    def test_duplicate_listing_names_abort(self):
        self.assert_abort([listing(("strategy.json", 7), ("strategy.json", 8))],
                          "duplicate_listing_name")

    def test_truncated_file_aborts(self):
        self.assert_abort([listing(("strategy.json", 8)), download(fileSize=8)],
                          "truncated_response")

    def test_extra_file_bytes_abort(self):
        self.assert_abort([listing(("strategy.json", 7)), download() + b"x"],
                          "unexpected_trailing_bytes")

    def test_checksum_mismatch_aborts_before_save(self):
        self.assert_abort([listing(("strategy.json", 7)), download(md5="0" * 32)],
                          "md5_mismatch")

    def test_invalid_checksum_aborts(self):
        self.assert_abort([listing(("strategy.json", 7)), download(md5="PRIVATE-TOKEN")],
                          "invalid_md5")

    def test_header_and_json_status_conflict_aborts(self):
        self.assert_abort([envelope({"code": 1, "files": []})], "response_status_conflict")

    def test_bad_magic_aborts(self):
        self.assert_abort([struct.pack(">III", 0, 0, 1)], "invalid_response_header")

    def test_invalid_listing_size_type_aborts(self):
        self.assert_abort([listing(("strategy.json", True))], "invalid_file_size")

    def test_invalid_utf8_json_is_sanitized(self):
        raw = b'{"PRIVATE-TOKEN": "\xff"}'
        result = self.assert_abort([struct.pack(">III", reader.MAGIC, 0, len(raw)) + raw],
                                   "invalid_json")
        self.assertNotIn("PRIVATE", json.dumps(result))

    def test_duplicate_json_keys_abort(self):
        raw = b'{"code":0,"code":0,"files":[]}'
        self.assert_abort([struct.pack(">III", reader.MAGIC, 0, len(raw)) + raw],
                          "duplicate_json_key")

    def test_socket_timeout_aborts_without_exception_text(self):
        result = self.assert_abort([socket.timeout("PRIVATE-TOKEN")], "socket_timeout")
        self.assertNotIn("PRIVATE", json.dumps(result))

    def test_total_deadline_terminates_fragmented_input(self):
        with patch.object(reader.time, "monotonic", side_effect=range(0, 1000, 5)):
            self.assert_abort([listing()], "total_timeout")

    def test_output_conflict_does_not_connect_or_overwrite(self):
        self.output.mkdir()
        protected = self.output / "report.json"
        protected.write_text("preserve")
        with patch.object(reader.socket, "create_connection") as connect:
            with self.assertRaisesRegex(reader.ExportError, "output_already_exists"):
                reader.run_export("localhost", 42424, self.output)
            connect.assert_not_called()
        self.assertEqual(protected.read_text(), "preserve")

    def test_later_failure_retains_only_previously_verified_file(self):
        result = self.run_reader([listing(("strategy.json", 7)), download(),
                                 listing(("strategy.json", 7)), download(md5="0" * 32)])
        self.assertEqual(result["status"], "aborted")
        self.assertEqual(result["files"][0]["status"], "saved")
        self.assertEqual(result["files"][1]["status"], "failed")
        self.assertEqual((self.output / "01-strategy.json").read_bytes(), b"fixture")
        self.assertFalse((self.output / "02-strategy.json").exists())
        self.assertEqual(len(self.sockets), 4)

    def test_rejects_nonloopback_ports_actions_and_arbitrary_paths_before_connect(self):
        with patch.object(reader.socket, "create_connection") as connect:
            for host, port in [("example.com", 42), ("127.0.0.2", 42),
                               ("localhost", 0), ("localhost", 65536), ("localhost", True)]:
                with self.subTest(host=host, port=port), self.assertRaises(reader.ExportError):
                    reader.run_export(host, port, self.output)
            for action, path in [(3, reader.ALLOWED_FILES[0]), (True, reader.ALLOWED_FILES[0]), (1, "/etc/passwd"),
                                 (2, "/"), (1, reader.ALLOWED_FILES[0] + "/../other")]:
                with self.subTest(action=action, path=path), self.assertRaises(reader.ExportError):
                    reader._exchange(("127.0.0.1", 42), action, path, reader.Deadline())
            connect.assert_not_called()


if __name__ == "__main__":
    unittest.main()
