from pathlib import Path
import stat
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import filter_ota_route_stream as subject
from parse_ota_route_logs import parse_bytes

P = "10-18 14:38:38.100  4228  4301 D OTA_GCY_UPGRADE_DiagnosePresenter: "
C = "10-18 14:38:38.100  4228  4301 D OTA_GCY_UPGRADE_CanDiagnostic: "
CYCLE = P + "requestEcuListInfo: called.\n"
INDEX = P + "loopRequestEcuItemInfo called, mEcuListInfo index : 0\n"
ITEM = P + "loopRequestEcuItemInfo item.ecuId: ESP_BOSCH_00100001\n"
RESET = C + "loopRequestEcuItemInfo reset mReceiveDid array\n"
ROUTE = C + "sendEcuMainVersionCanInfo ecuDiaId: 782/78A , ecuDId: F195/F189\n"
CLOSE = P + "loopRequestEcuItemInfo called, mEcuListInfo index : 1\n"


class StreamTests(unittest.TestCase):
    def apply(self, data, chunks=4096, **kwargs):
        output = []
        stream = subject.RouteLogFilter(output.append, **kwargs)
        for pos in range(0, len(data), chunks):
            stream.feed(data[pos:pos + chunks])
        stream.finish()
        return "".join(output), stream.report()

    def test_fragmentation_byte_by_byte_is_identical(self):
        data = (CYCLE + INDEX + ITEM + RESET + ROUTE + CLOSE).encode()
        a, ar = self.apply(data)
        b, br = self.apply(data, chunks=1)
        self.assertEqual((a, ar), (b, br))
        self.assertEqual(6, ar["acceptedRecords"])
        self.assertEqual(0, ar["gapMarkers"])

    def test_unknown_private_data_never_persisted(self):
        secret = "VIN_LC012345678901234 token=TESTSECRET access_token=TESTSECRET"
        hostile = [P + secret, C + "sendEcuMainVersionCanInfo " + secret,
                   P.replace("DiagnosePresenter", "HttpApiService") + secret,
                   C.replace("GCY", "GHY") + "sendEcuDiagnoseAvailableDiag ecuDiaId: 782/78A",
                   "unframed " + secret]
        output, report = self.apply(("\n".join(hostile) + "\n").encode())
        self.assertEqual(subject.GAP_MARKER + "\n", output)
        self.assertEqual(0, report["acceptedRecords"])
        self.assertNotIn("TESTSECRET", str(report))
        self.assertNotIn("LC012", output)

    def test_oversize_record_discards_until_newline(self):
        data = CYCLE.encode() + b"x" * 200000 + b"\n" + ROUTE.encode()
        output, report = self.apply(data, chunks=127)
        self.assertEqual(2, report["acceptedRecords"])
        self.assertEqual(1, report["rejectedRecordsByReason"]["overlong_record"])
        self.assertLess(len(output), 500)

    def test_utf8_error_and_partial_final_record_are_gaps(self):
        output, report = self.apply(CYCLE.encode() + b"\xff\n" + ROUTE.rstrip("\n").encode())
        self.assertEqual(1, report["acceptedRecords"])
        self.assertFalse(report["completeInput"])
        self.assertIn("invalid_utf8", report["rejectedRecordsByReason"])
        self.assertIn("incomplete_final_record", report["rejectedRecordsByReason"])
        self.assertNotIn("782/78A", output)

    def test_gap_prevents_parser_from_joining_candidate(self):
        clean = (CYCLE + INDEX + ITEM + RESET + ROUTE + CLOSE).encode()
        filtered, _ = self.apply(clean)
        self.assertEqual("ESP_BOSCH_00100001", parse_bytes(filtered.encode())["routeObservations"][0]["assignedEcuId"])
        broken = (CYCLE + INDEX + ITEM + P + "loopRequestEcuItemInfo item.ecuId: unknown_private_content?\n" + RESET + ROUTE + CLOSE).encode()
        filtered, _ = self.apply(broken)
        result = parse_bytes(filtered.encode())
        self.assertIsNone(result["routeObservations"][0]["assignedEcuId"])
        self.assertTrue(result["rejectedRecords"])
        self.assertNotIn("unknown_private_content", filtered)

    def test_routine_lines_and_other_tags_do_not_break_valid_sequence(self):
        routine = C + "sendCanMessage: Create a OtaDiagnoseEcuInfoListener instance.\n"
        other = P.replace("DiagnosePresenter", "HttpApiService") + "PRIVATE_TOKEN\n"
        data = (CYCLE + INDEX + ITEM + RESET + routine + other + ROUTE + CLOSE).encode()
        output, report = self.apply(data, chunks=1)
        result = parse_bytes(output.encode())
        self.assertEqual(0, report["gapMarkers"])
        self.assertEqual(2, sum(report["ignoredRecordsByReason"].values()))
        self.assertEqual("ESP_BOSCH_00100001", result["routeObservations"][0]["assignedEcuId"])
        self.assertNotIn("PRIVATE_TOKEN", output)
        self.assertNotIn("sendCanMessage", output)

    def test_malformed_route_and_timestamp_create_gap(self):
        for broken in [C + "sendEcuMainVersionCanInfo ecuDiaId: 782/78A , ecuDId: F1\n",
                       ROUTE.replace("14:38:38.100", "99:99:99.100")]:
            output, report = self.apply((CYCLE + INDEX + ITEM + RESET + broken + ROUTE + CLOSE).encode())
            self.assertEqual(1, report["gapMarkers"])
            self.assertIsNone(parse_bytes(output.encode())["routeObservations"][0]["assignedEcuId"])

    def test_transport_gap_hook_is_content_free_and_drops_partial_fragment(self):
        output = []
        stream = subject.RouteLogFilter(output.append)
        stream.feed((CYCLE + INDEX + ITEM + RESET).encode())
        stream.feed(b"PRIVATE_PARTIAL_DATA")
        stream.mark_gap("logcat_drop")
        stream.feed((ROUTE + CLOSE).encode())
        with self.assertRaisesRegex(ValueError, "unknown_transport_gap_reason"):
            stream.mark_gap("PRIVATE_ERROR_DETAIL")
        stream.finish()
        text = "".join(output)
        self.assertNotIn("PRIVATE", text + str(stream.report()))
        self.assertIsNone(parse_bytes(text.encode())["routeObservations"][0]["assignedEcuId"])
        self.assertEqual(1, stream.report()["rejectedRecordsByReason"]["logcat_drop"])

    def test_input_and_event_limits_fail_closed(self):
        for kwargs, chunks in [({"max_input_bytes": 10}, [b"0123456789", b"x"]),
                               ({"max_events": 1}, [CYCLE.encode(), ROUTE.encode()])]:
            output = []
            stream = subject.RouteLogFilter(output.append, **kwargs)
            stream.feed(chunks[0])
            with self.assertRaises(subject.FilterLimitError):
                stream.feed(chunks[1])
            with self.assertRaises(subject.FilterLimitError):
                stream.feed(b"")
            stream.finish()
            self.assertEqual("processed_prefix", stream.report()["fingerprintScope"])
            self.assertFalse(stream.report()["completeInput"])
            self.assertIn(subject.GAP_MARKER, "".join(output))

    def test_private_files_fresh_directory_and_no_source_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "PRIVATE_SOURCE_NAME.log"
            source.write_text(CYCLE)
            destination = Path(temporary) / "evidence"
            report = subject.filter_file(source, destination)
            self.assertEqual(0o700, stat.S_IMODE(destination.stat().st_mode))
            for file in destination.iterdir():
                self.assertEqual(0o600, stat.S_IMODE(file.stat().st_mode))
                self.assertNotIn("PRIVATE_SOURCE_NAME", file.read_text())
            self.assertTrue(report["offlineOnly"])
            self.assertFalse(report["observerStarted"])
            with self.assertRaises(FileExistsError):
                subject.filter_file(source, destination)


if __name__ == "__main__":
    unittest.main()
