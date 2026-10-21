"""Offline fixtures and fragmented fake sockets only: no TCP, ADB or vehicle."""
import copy
import hashlib
import json
import socket
import struct
import unittest
from unittest.mock import patch

import read_vhds_config_export as reader


def encode(doc):
    return json.dumps(doc, ensure_ascii=False).encode("utf-8")


def envelope(doc, payload=b"", status=0):
    raw = encode(doc)
    return struct.pack(">III", reader.wire.MAGIC, status, len(raw)) + raw + payload


def listing(*rows):
    return envelope({"code": 0, "files": [{"name": name, "size": size} for name, size in rows]})


def download(raw, **overrides):
    doc = {"code": 0, "fileName": "allone.txt", "fileSize": len(raw),
           "md5": hashlib.md5(raw).hexdigest()}
    doc.update(overrides)
    return envelope(doc, raw)


def config_row(**overrides):
    # Synthetic source-shaped configuration; none of these bytes names a
    # qualified physical ECU, warning, service or current vehicle task.
    row = {"signal": "00000261", "childrenId": "0x00", "values": "221",
           "startBit": 0, "endBit": 9, "logic": "0", "checkId": "0720",
           "checkType": "19", "checkDidList": ["02FF", "01020304", "A", ""]}
    row.update(overrides)
    return row


def config(*rows, code="0", **extra):
    return encode({"resultCode": code, "resultData": list(rows), **extra})


class FragmentedSocket:
    def __init__(self, raw, fragment=1):
        self.raw = raw
        self.fragment = fragment
        self.sent = b""
        self.timeouts = []
        self.closed = False

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.closed = True

    def settimeout(self, value):
        self.timeouts.append(value)

    def sendall(self, raw):
        self.sent += raw

    def recv(self, count):
        if isinstance(self.raw, Exception):
            raise self.raw
        count = min(count, self.fragment)
        result, self.raw = self.raw[:count], self.raw[count:]
        return result


class ReadConfigTest(unittest.TestCase):
    def setUp(self):
        self.sockets = []

    def read(self, replies, fragment=1, host="localhost"):
        replies = iter(replies)

        def connect(endpoint, timeout):
            self.assertEqual(endpoint, ("127.0.0.1", 42424))
            self.assertGreater(timeout, 0)
            self.assertLessEqual(timeout, 2)
            sock = FragmentedSocket(next(replies), fragment)
            self.sockets.append(sock)
            return sock

        with patch.object(reader.socket, "create_connection", side_effect=connect), \
                patch.object(reader.wire, "run_export", side_effect=AssertionError("not allowed")), \
                patch.object(reader.wire, "_request", side_effect=AssertionError("not allowed")):
            return reader.read_config(host, 42424)

    def assert_failure(self, replies, code, **kwargs):
        with self.assertRaises(reader.ExportError) as caught:
            self.read(replies, **kwargs)
        self.assertEqual(str(caught.exception), code)
        self.assertTrue(all(sock.closed for sock in self.sockets))

    def test_fragmented_wire_only_one_list_one_download_and_raw_unchanged(self):
        raw = config(config_row(), vin="PRIVATE-VIN", token="PRIVATE-TOKEN")
        original_allowlist = reader.wire.ALLOWED_FILES
        result = self.read([listing(("allone.txt", len(raw)), ("never-download.txt", 999)), download(raw)])
        self.assertIs(type(result), bytes)
        self.assertEqual(result, raw)
        self.assertEqual(reader.wire.ALLOWED_FILES, original_allowlist)
        self.assertEqual(len(self.sockets), 2)
        for sock, (action, key, path) in zip(self.sockets, (
                (2, "directory", reader.CONFIG_DIRECTORY), (1, "filePath", reader.CONFIG_FILE))):
            self.assertTrue(sock.closed)
            self.assertTrue(all(0 < timeout <= 2 for timeout in sock.timeouts))
            magic, actual_action, length = struct.unpack(">III", sock.sent[:12])
            self.assertEqual((magic, actual_action), (reader.wire.MAGIC, action))
            self.assertEqual(length, len(sock.sent) - 12)
            self.assertEqual(json.loads(sock.sent[12:]),
                             {key: reader.wire.EXPORT_PREFIX + path.lstrip("/")})

    def test_zero_bytes_and_exact_cap_are_verified(self):
        for raw in (b"", b"x" * reader.MAX_FILE):
            with self.subTest(size=len(raw)):
                self.assertEqual(self.read([listing(("allone.txt", len(raw))), download(raw)],
                                           fragment=8192), raw)

    def test_read_returns_even_malformed_source_bytes_without_projecting_them(self):
        raw = b'\xff{"resultCode":NaN,"token":"PRIVATE-TOKEN"}'
        with patch.object(reader, "project_config", side_effect=AssertionError("not while reading")):
            self.assertEqual(self.read([listing(("allone.txt", len(raw))), download(raw)]), raw)

    def test_no_matching_file_means_one_listing_and_no_retry(self):
        self.assert_failure([listing(("other.txt", 20))], "missing")
        self.assertEqual(len(self.sockets), 1)

    def test_missing_directory_and_racing_missing_file_do_not_retry(self):
        missing = envelope({"code": 1, "message": "PRIVATE-VIN"}, status=1)
        self.assert_failure([missing], "missing")
        self.assertEqual(len(self.sockets), 1)
        self.sockets.clear()
        self.assert_failure([listing(("allone.txt", 20)), missing], "missing")
        self.assertEqual(len(self.sockets), 2)

    def test_remote_error_does_not_echo_response_or_retry(self):
        self.assert_failure([envelope({"code": 5, "message": "PRIVATE-VIN-TOKEN"}, status=5)],
                            "remote_error_5")
        self.assertEqual(len(self.sockets), 1)

    def test_all_other_actions_paths_and_boolean_types_rejected_before_connect(self):
        bad_requests = [(action, reader.CONFIG_FILE) for action in
                        (0, 2, 3, 4, 5, 6, -1, True, False, 1.0, None, "1")]
        bad_requests += [(1, path) for path in (
            reader.CONFIG_DIRECTORY, reader.CONFIG_FILE + "/", reader.CONFIG_FILE + "\x00",
            reader.CONFIG_DIRECTORY + "/triggerlistconfig.txt", "/data/vhds/other/allone.txt",
            reader.CONFIG_DIRECTORY + "/../trigger_list_cfg/allone.txt",
            reader.wire.EXPORT_PREFIX + reader.CONFIG_FILE.lstrip("/"),
            "allone.txt", True, None, [], {})]
        bad_requests += [(2, reader.CONFIG_DIRECTORY + "/"), (2, "/data/vhds"),
                         (1, reader.wire.ALLOWED_FILES[0]), (2, True)]
        with patch.object(reader.socket, "create_connection") as connect:
            for action, path in bad_requests:
                with self.subTest(action=action, path=path):
                    with self.assertRaisesRegex(reader.ExportError, "^request_not_allowed$"):
                        reader._exchange(("127.0.0.1", 42424), action, path, reader.wire.Deadline())
            connect.assert_not_called()

    def test_nonloopback_or_illtyped_endpoint_rejected_before_connect(self):
        with patch.object(reader.socket, "create_connection") as connect:
            for host, port in (("example.com", 42), ("127.0.0.2", 42), ("0.0.0.0", 42),
                               ("localhost.", 42), ("localhost", 0), ("localhost", 65536),
                               ("localhost", True), ("localhost", 42.0), ([], 42), (True, 42)):
                with self.subTest(host=host, port=port):
                    with self.assertRaisesRegex(reader.ExportError, "^invalid_loopback_endpoint$"):
                        reader.read_config(host, port)
            with self.assertRaisesRegex(reader.ExportError, "^invalid_loopback_endpoint$"):
                reader._exchange(("example.com", 42), 2, reader.CONFIG_DIRECTORY,
                                 reader.wire.Deadline())
            connect.assert_not_called()

    def test_ipv6_loopback_is_literal_and_localhost_does_not_resolve(self):
        for host, expected in (("localhost", "127.0.0.1"), ("127.0.0.1", "127.0.0.1"), ("::1", "::1")):
            self.assertEqual(reader._endpoint(host, 42424), (expected, 42424))

    def test_oversized_listing_prevents_download(self):
        self.assert_failure([listing(("allone.txt", reader.MAX_FILE + 1))], "file_size_out_of_bounds")
        self.assertEqual(len(self.sockets), 1)

    def test_bad_listings_abort(self):
        cases = [({"code": 0, "files": [{"name": "allone.txt", "size": True}]}, "invalid_file_size"),
                 ({"code": 0, "files": [{"name": "allone.txt", "size": -1}]}, "invalid_file_size"),
                 ({"code": 0, "files": [{"name": "../allone.txt", "size": 1}]}, "invalid_listing_name"),
                 ({"code": 0, "files": [{"name": "allone.txt", "size": 1}] * 2}, "duplicate_listing_name"),
                 ({"code": 0, "files": {}}, "invalid_directory_listing")]
        for doc, error in cases:
            with self.subTest(error=error):
                self.assert_failure([envelope(doc)], error)

    def test_download_conflicts_integrity_and_truncation_abort(self):
        raw = b"fixture"
        for reply, code in (
                (download(raw, fileName="PRIVATE-VIN"), "file_name_conflict"),
                (download(raw, fileSize=8), "file_size_conflict"),
                (download(raw, fileSize=True), "invalid_file_size"),
                (download(raw, fileSize=reader.MAX_FILE + 1), "file_size_out_of_bounds"),
                (download(raw, md5="PRIVATE-TOKEN"), "invalid_md5"),
                (download(raw, md5="0" * 32), "md5_mismatch"),
                (download(raw)[:-1], "truncated_response"),
                (download(raw) + b"x", "unexpected_trailing_bytes")):
            with self.subTest(error=code):
                self.assert_failure([listing(("allone.txt", len(raw))), reply], code)

    def test_invalid_envelope_header_status_json_and_trailing_bytes_abort(self):
        for reply, code in (
                (struct.pack(">III", 0, 0, 1), "invalid_response_header"),
                (struct.pack(">III", reader.wire.MAGIC, 6, 1), "invalid_response_header"),
                (struct.pack(">III", reader.wire.MAGIC, 0, reader.wire.MAX_JSON + 1), "json_length_out_of_bounds"),
                (envelope({"code": True}), "response_status_conflict"),
                (envelope({"code": 1}), "response_status_conflict"),
                (listing() + b"extra", "unexpected_trailing_bytes")):
            with self.subTest(error=code):
                self.assert_failure([reply], code)
        for raw, code in ((b'{"code":0,"code":0,"files":[]}', "duplicate_json_key"),
                          (b'{"code":0,"PRIVATE":NaN}', "invalid_json_number"),
                          (b'{"code":0,"PRIVATE":1e999}', "invalid_json_number"),
                          (b'{"code":0,"PRIVATE":"\xff"}', "invalid_json")):
            with self.subTest(error=code):
                self.assert_failure([struct.pack(">III", reader.wire.MAGIC, 0, len(raw)) + raw], code)

    def test_socket_failures_are_fixed_codes(self):
        self.assert_failure([socket.timeout("PRIVATE-TOKEN")], "socket_timeout")
        self.assert_failure([OSError("PRIVATE-VIN")], "socket_error")

    def test_one_total_deadline_covers_both_connections(self):
        now = [0.0]
        raw = config(config_row())
        replies = iter([listing(("allone.txt", len(raw))), download(raw)])

        def connect(_endpoint, timeout):
            self.assertLessEqual(timeout, 2)
            now[0] += 11
            sock = FragmentedSocket(next(replies), 8192)
            self.sockets.append(sock)
            return sock

        with patch.object(reader.wire.time, "monotonic", side_effect=lambda: now[0]), \
                patch.object(reader.socket, "create_connection", side_effect=connect):
            with self.assertRaisesRegex(reader.ExportError, "^total_timeout$"):
                reader.read_config("localhost", 42424)
        self.assertEqual(len(self.sockets), 2)
        self.assertEqual(self.sockets[1].sent, b"")
        self.assertTrue(all(sock.closed for sock in self.sockets))

    def test_fragmented_receive_deadline_expires_without_retry(self):
        with patch.object(reader.wire.time, "monotonic", side_effect=range(0, 1000)):
            self.assert_failure([listing(("allone.txt", 1))], "total_timeout")
        self.assertEqual(len(self.sockets), 1)


class ProjectConfigTest(unittest.TestCase):
    def assert_projection_failure(self, raw, error, status="invalid_config"):
        result = reader.project_config(raw)
        self.assertEqual(result["status"], status)
        self.assertEqual(result["error"], error)
        self.assertEqual(result["resultData"], [])
        self.assertEqual(result["rawSizeBytes"], len(raw))
        self.assertEqual(result["rawSha256"], hashlib.sha256(raw).hexdigest())
        self.assertNotIn("PRIVATE", json.dumps(result))
        return result

    def test_known_fields_preserved_private_fields_and_messages_stripped(self):
        row = config_row(vin="PRIVATE-VIN", token="PRIVATE-TOKEN", description="PRIVATE-MESSAGE",
                         nested={"error": "PRIVATE-SERVER-ERROR"})
        raw = config(row, vin="PRIVATE-VIN", token="PRIVATE-TOKEN", resultMsg="PRIVATE-MESSAGE",
                     nested={"resultData": ["PRIVATE-DATA"]})
        original = raw[:]
        result = reader.project_config(raw)
        self.assertEqual(raw, original)
        self.assertEqual(result["status"], "complete")
        self.assertIsNone(result["error"])
        self.assertEqual(result["resultCode"], "0")
        self.assertEqual(result["resultData"], [config_row()])
        self.assertEqual(set(result), {"schemaVersion", "status", "error", "rawSizeBytes",
                                      "rawSha256", "resultCode", "resultData"})
        self.assertEqual(result["rawSizeBytes"], len(raw))
        self.assertEqual(result["rawSha256"], hashlib.sha256(raw).hexdigest())
        self.assertNotIn("PRIVATE", json.dumps(result))
        self.assertNotIn("didBytes", json.dumps(result))

    def test_result_code_int_or_decimal_string_preserves_source_type(self):
        for code in (0, "000", "-0"):
            with self.subTest(code=code):
                result = reader.project_config(config(config_row(), code=code))
                self.assertEqual(result["status"], "complete")
                self.assertEqual(result["resultCode"], code)
                self.assertIs(type(result["resultCode"]), type(code))

    def test_nonzero_result_never_projects_rows_or_server_error(self):
        for code in (1, "20004", -1, "-2147483648", 2147483647):
            with self.subTest(code=code):
                raw = config(config_row(), {"signal": "PRIVATE-ROW"}, code=code,
                             resultMsg="PRIVATE-VIN-TOKEN-SERVER-ERROR")
                result = reader.project_config(raw)
                self.assertEqual(result["status"], "remote_result")
                self.assertIsNone(result["error"])
                self.assertEqual(result["resultCode"], code)
                self.assertEqual(result["resultData"], [])
                self.assertNotIn("PRIVATE", json.dumps(result))
        # A code-only error response is a valid observed non-data state.
        self.assertEqual(reader.project_config(b'{"resultCode":"7"}')["status"], "remote_result")

    def test_bad_result_code_never_leaks_source_value(self):
        for code in (True, False, None, 0.0, [], {}, "", "0x0", " 0", "+0", "0 trailing",
                     "PRIVATE-VIN", "2147483648", -2147483649, "999999999999999999999999"):
            with self.subTest(code=code):
                self.assert_projection_failure(config(config_row(), code=code), "invalid_result_code")

    def test_check_did_list_is_optional_and_strings_are_never_decoded_or_normalized(self):
        row = config_row()
        del row["checkDidList"]
        result = reader.project_config(config(row))
        self.assertEqual(result["status"], "complete")
        self.assertNotIn("checkDidList", result["resultData"][0])
        for parameters in ([""], ["A"], ["abc"], ["00112233"], ["00", "00"]):
            with self.subTest(parameters=parameters):
                result = reader.project_config(config(config_row(checkDidList=parameters)))
                self.assertEqual(result["status"], "complete")
                self.assertEqual(result["resultData"][0]["checkDidList"], parameters)

    def test_known_field_boundary_values_keep_source_spellings(self):
        row = config_row(signal="FFFFFFFF", childrenId="00fF", values="000255", startBit=0,
                         endBit=63, logic="02", checkId="0Xffff", checkType="0xff")
        result = reader.project_config(config(row))
        self.assertEqual(result["status"], "complete")
        self.assertEqual(result["resultData"], [row])

    def test_native_string_fields_reject_json_int_without_ambiguous_base_conversion(self):
        for key in ("signal", "childrenId", "values", "logic", "checkId", "checkType"):
            with self.subTest(key=key):
                self.assert_projection_failure(config(config_row(**{key: 1})),
                                               "unsupported_config_field_type", "unsupported_config")

    def test_invalid_native_string_types_and_lexical_numbers_are_rejected(self):
        for key in ("signal", "childrenId", "values", "logic", "checkId", "checkType"):
            for value in (True, None, 1.0, [], {}):
                with self.subTest(key=key, value=value):
                    self.assert_projection_failure(config(config_row(**{key: value})),
                                                   "invalid_config_field_type")
            for value in ("", "PRIVATE-VIN", "-1", "+1", " 1", "1 ", "1\x00", "1g", "1.0"):
                with self.subTest(key=key, value=value):
                    self.assert_projection_failure(config(config_row(**{key: value})), "invalid_config_number")
        self.assert_projection_failure(config(config_row(values="FF")), "invalid_config_number")

    def test_narrowing_or_unknown_comparison_is_unsupported(self):
        for key, value in (("signal", "100000000"), ("childrenId", "100"), ("values", "256"),
                           ("logic", "3"), ("checkId", "10000"), ("checkType", "100"),
                           ("startBit", -1), ("endBit", 64)):
            with self.subTest(key=key):
                self.assert_projection_failure(config(config_row(**{key: value})),
                                               "unsupported_config_value", "unsupported_config")

    def test_bit_numbers_require_native_integer_type_and_supported_order(self):
        for key in ("startBit", "endBit"):
            for value in (True, None, 1.0, [], {}, "PRIVATE-VIN"):
                with self.subTest(key=key, value=value):
                    self.assert_projection_failure(config(config_row(**{key: value})),
                                                   "invalid_config_field_type")
            self.assert_projection_failure(config(config_row(**{key: "1"})),
                                           "unsupported_config_field_type", "unsupported_config")
        self.assert_projection_failure(config(config_row(startBit=9, endBit=0)),
                                       "unsupported_config_bit_range", "unsupported_config")

    def test_bad_parameter_list_rejected_without_native_hex_coercion(self):
        for parameters in (None, "F194", {}, True):
            with self.subTest(parameters=parameters):
                self.assert_projection_failure(config(config_row(checkDidList=parameters)),
                                               "invalid_config_parameter_list")
        self.assert_projection_failure(config(config_row(checkDidList=[])),
                                       "unsupported_config_parameter_list", "unsupported_config")
        for parameter in (True, 194, None, {}, [], "0xF194", "123456789", "FG", " A", "PRIVATE-VIN"):
            with self.subTest(parameter=parameter):
                self.assert_projection_failure(config(config_row(checkDidList=[parameter])),
                                               "invalid_config_parameter")

    def test_missing_required_field_or_invalid_row_discards_all_rows(self):
        for key in reader._REQUIRED_FIELDS:
            row = config_row()
            del row[key]
            with self.subTest(key=key):
                result = self.assert_projection_failure(config(config_row(), row), "invalid_config_schema")
                self.assertEqual(result["resultCode"], "0")
        for row in (None, [], "PRIVATE-ROW", 0, True):
            with self.subTest(row=row):
                self.assert_projection_failure(config(config_row(), row), "invalid_config_schema")

    def test_strict_json_for_private_fields_too(self):
        for raw, code in (
                (b'{"resultCode":0,"resultCode":0,"resultData":[]}', "duplicate_json_key"),
                (b'{"resultCode":0,"PRIVATE":{"key":1,"key":2},"resultData":[]}', "duplicate_json_key"),
                (b'{"resultCode":0,"PRIVATE":NaN,"resultData":[]}', "invalid_json_number"),
                (b'{"resultCode":0,"PRIVATE":Infinity,"resultData":[]}', "invalid_json_number"),
                (b'{"resultCode":0,"PRIVATE":-Infinity,"resultData":[]}', "invalid_json_number"),
                (b'{"resultCode":0,"PRIVATE":1e999,"resultData":[]}', "invalid_json_number"),
                (b'{"resultCode":0,"PRIVATE":"\xff","resultData":[]}', "invalid_json"),
                (b'{"resultCode":0,"PRIVATE":"\\ud800","resultData":[]}', "invalid_json"),
                ('{"resultCode":0,"resultData":[]}'.encode("utf-16"), "invalid_json"),
                (b'{"resultCode":0,"resultData":[]} trailing', "invalid_json"),
                (b'{"resultCode":0,"resultData":[],}', "invalid_json"),
                (b"", "invalid_json"),
                (b"[" * 1200 + b"]" * 1200, "invalid_json")):
            with self.subTest(error=code, raw=raw[:80]):
                self.assert_projection_failure(raw, code)

    def test_nonzero_code_still_requires_strict_json(self):
        self.assert_projection_failure(b'{"resultCode":7,"PRIVATE":NaN}', "invalid_json_number")

    def test_root_schema_and_empty_success(self):
        for doc in (None, [], True, {}, {"resultData": []}, {"resultCode": 0},
                    {"resultCode": 0, "resultData": None}, {"resultCode": 0, "resultData": {}},
                    {"resultCode": 0, "resultData": "PRIVATE-ROWS"}):
            with self.subTest(doc=doc):
                self.assert_projection_failure(encode(doc), "invalid_config_schema")
        result = reader.project_config(config())
        self.assertEqual(result["status"], "complete")
        self.assertEqual(result["resultData"], [])

    def test_projection_cap_and_input_type(self):
        self.assert_projection_failure(b"x" * (reader.MAX_FILE + 1), "file_size_out_of_bounds")
        for raw in ("PRIVATE-VIN", bytearray(b"PRIVATE-TOKEN"), None, True):
            with self.subTest(type=type(raw)):
                with self.assertRaisesRegex(reader.ExportError, "^invalid_config_bytes$"):
                    reader.project_config(raw)

    def test_projection_does_not_connect_or_mutate_source_document(self):
        source = {"resultCode": 0, "resultData": [config_row()]}
        before = copy.deepcopy(source)
        with patch.object(reader, "_json", return_value=source), \
                patch.object(reader.socket, "create_connection") as connect:
            result = reader.project_config(b"fixture")
            result["resultData"][0]["checkDidList"].append("00")
            connect.assert_not_called()
        self.assertEqual(source, before)


if __name__ == "__main__":
    unittest.main()
