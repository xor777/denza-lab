"""Synthetic saved-log tests only: no car, capture command or network."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from parse_ota_route_logs import (GAP_MARKER, needs_gap_marker, parse_bytes,
                                  parse_saved_logs, sanitize_threadtime_line)


def line(body, role="DiagnosePresenter", pid=101, tid=101, family="GCY", stamp="10-18 12:00:00.000"):
    return f"{stamp} {pid} {tid} I OTA_{family}_UPGRADE_{role}: {body}\n"


def index(n, **kw):
    return line(f"loopRequestEcuItemInfo called, mEcuListInfo index : {n}", **kw)


def item(name="BMS_BYD_SYNTHETIC", **kw):
    return line(f"loopRequestEcuItemInfo item.ecuId: {name}", **kw)


def reset(**kw):
    return line("loopRequestEcuItemInfo reset mReceiveDid array", "CanDiagnostic", **kw)


def route(pair="601/609", method="sendEcuMainVersionCanInfo", did="F195/F194", **kw):
    suffix = "" if method == "sendEcuDiagnoseAvailableDiag" else f" , {'ecuHardDid' if method == 'sendEcuHardVersionCanInfo' else 'ecuDId'}: {did}"
    return line(f"{method} ecuDiaId: {pair}{suffix}", "CanDiagnostic", **kw)


def cycle(**kw):
    return line("requestEcuListInfo: called.", **kw)


def parse(*parts):
    return parse_bytes("".join(parts).encode())


class OtaRouteLogTest(unittest.TestCase):
    def test_complete_explicit_same_thread_sequence_keeps_numeric_and_source_evidence(self):
        data = cycle() + index(0) + item() + reset() + route() + index(1)
        result = parse_bytes(data.encode())
        o = result["routeObservations"][0]
        self.assertEqual(o["assignedEcuId"], "BMS_BYD_SYNTHETIC")
        self.assertEqual(o["route"], {"printed": "601/609", "request": 0x601, "response": 0x609})
        self.assertEqual(o["dids"], [0xF195, 0xF194])
        self.assertEqual(o["event"]["line"], 5)
        self.assertEqual(result["sha256"], hashlib.sha256(data.encode()).hexdigest())
        self.assertEqual(result["candidates"][0]["closingEvent"]["line"], 6)
        self.assertFalse(o["eligibleForProductAllowlist"])
        self.assertEqual(o["responseStatus"], "not_observed")

    def test_realistic_main_to_worker_handoff_is_context_only_not_ecu_assignment(self):
        r = parse(cycle(), index(0), item(), reset(tid=202), route(tid=202), index(1))
        o, c = r["routeObservations"][0], r["candidates"][0]
        self.assertIsNone(o["assignedEcuId"])
        self.assertEqual(o["correlation"], "ordered_stock_candidate_unverified")
        self.assertIn("cross_thread_identity_route_unverified", o["reasons"])
        self.assertEqual(c["ecuId"], "BMS_BYD_SYNTHETIC")
        self.assertEqual(c["resetEvents"][0]["tid"], 202)
        self.assertEqual(c["indexEvent"]["tid"], 101)

    def test_callback_on_another_thread_prevents_all_block_assignments(self):
        r = parse(cycle(), index(0), item(), reset(), route(),
                  route(method="sendEcuMainSoftCodeCanInfo", tid=303), index(1))
        self.assertTrue(all(o["assignedEcuId"] is None for o in r["routeObservations"]))

    def test_pid_proximity_without_reset_or_index_never_qualifies(self):
        for parts in [(cycle(), item(), route(), index(1)),
                      (cycle(), index(0), item(), route(), index(1)),
                      (index(0), item(), reset(), route(), index(1))]:
            with self.subTest(parts=parts):
                self.assertIsNone(parse(*parts)["routeObservations"][0]["assignedEcuId"])

    def test_post_loop_e_identity_is_not_an_anchor(self):
        r = parse(cycle(), index(0), line("loopRequestEcuItemInfo e.ecuId: ECM_BYD_SYNTHETIC"),
                  reset(), route(), index(1))
        self.assertIsNone(r["routeObservations"][0]["assignedEcuId"])
        self.assertIsNone(r["candidates"][0]["ecuId"])

    def test_pair_conflict_revokes_even_earlier_route_in_the_candidate(self):
        r = parse(cycle(), index(0), item(), reset(), route(), route("611/619"), index(1))
        self.assertEqual(len(r["routeObservations"]), 2)
        for o in r["routeObservations"]:
            self.assertIsNone(o["assignedEcuId"])
            self.assertIn("conflicting_route_pairs", o["reasons"])

    def test_reset_overlap_and_conflicting_identity_are_rejected(self):
        for middle in [reset(tid=202), item("ECM_BYD_SYNTHETIC")]:
            r = parse(cycle(), index(0), item(), reset(), middle, route(), index(1))
            self.assertIsNone(r["routeObservations"][0]["assignedEcuId"])

    def test_incomplete_previous_candidate_cannot_lend_worker_route_to_next(self):
        r = parse(cycle(), index(0), item(), reset(), index(1), item("ECM_BYD_SYNTHETIC"),
                  reset(), route(), index(2))
        self.assertIn("preceding_candidate_incomplete_overlap", r["routeObservations"][0]["reasons"])
        self.assertIsNone(r["routeObservations"][0]["assignedEcuId"])

    def test_process_restart_with_same_pid_and_pid_change_break_association(self):
        for restart in [line(">>>>>--------App Application onCreate Start--------", "App"), cycle(pid=999)]:
            r = parse(cycle(), index(0), item(), reset(), route(), restart, index(1))
            self.assertIsNone(r["routeObservations"][0]["assignedEcuId"])

    def test_pid_switch_invalidates_even_an_idle_old_cycle(self):
        r = parse(cycle(), cycle(pid=999), index(0), item(), reset(), route(), index(1))
        self.assertIsNone(r["routeObservations"][0]["assignedEcuId"])
        self.assertIn("missing_cycle_start", r["routeObservations"][0]["reasons"])

    def test_overlapping_cycle_poison_is_not_cleared_by_new_index(self):
        r = parse(cycle(), index(0), item(), reset(), cycle(), index(0), item(), reset(), route(), index(1))
        self.assertIsNone(r["routeObservations"][0]["assignedEcuId"])
        self.assertIn("overlapping_or_restarted_cycle", r["routeObservations"][0]["reasons"])

    def test_cancel_clear_and_eof_leave_last_block_unqualified(self):
        for end in ["", line("clear: called."), line("cancelLoopRequestEcuListInfo: called.")]:
            r = parse(cycle(), index(0), item(), reset(), route(), end)
            self.assertIsNone(r["routeObservations"][0]["assignedEcuId"])

    def test_unterminated_closing_line_is_not_accepted_as_complete_boundary(self):
        r = parse(cycle(), index(0), item(), reset(), route(), index(1).rstrip("\n"))
        self.assertIn("unterminated_final_log_line", r["routeObservations"][0]["reasons"])
        self.assertIsNone(r["routeObservations"][0]["assignedEcuId"])

    def test_truncated_route_and_bad_threadtime_invalidate_previous_route(self):
        for malformed in [route().replace("F195/F194", "F195/"),
                          "broken OTA_GCY_UPGRADE_CanDiagnostic: sendEcuMainVersionCanInfo\n",
                          route(stamp="99-99 99:99:99.000")]:
            r = parse(cycle(), index(0), item(), reset(), route(), malformed, index(1))
            self.assertIsNone(r["routeObservations"][0]["assignedEcuId"])
            self.assertTrue(r["rejectedRecords"])

    def test_time_regression_does_not_bridge_rotated_or_restarted_capture(self):
        r = parse(cycle(), index(0), item(), reset(), route(), index(1, stamp="10-18 11:59:59.000"))
        self.assertIn("timestamp_regression", r["routeObservations"][0]["reasons"])

    def test_did_fields_config_ambiguity_and_exact_non_plus8_response(self):
        r = parse(cycle(), index(0), item(), reset(), route("601/620"),
                  route("601/620", method="sendEcuHardVersionCanInfo", did="F193"),
                  route("601/620", method="sendEcuConfigSoftCodeCanInfo", did="F101/F102"), index(1))
        self.assertEqual(r["routeObservations"][0]["route"]["response"], 0x620)
        self.assertEqual(r["routeObservations"][1]["didField"], "ecuHardDid")
        self.assertEqual(r["routeObservations"][2]["operationAmbiguity"], "config_version_or_code")

    def test_privacy_whitelist_excludes_http_credentials_unknown_props_and_filenames(self):
        secret = "PRIVATE_SECRET"
        r = parse(cycle(), index(0), item(), reset(), route(),
                  line('Http response {"token":"' + secret + '"}'),
                  line("loopRequestEcuItemInfo item.vin: " + secret),
                  "10-18 12:00:00.000 101 101 I OTA_GCY_UPGRADE_HttpApiService: " + secret + "\n", index(1))
        self.assertNotIn(secret, json.dumps(r))
        self.assertNotIn("canonicalBody", json.dumps(r))
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / (secret + ".log")
            p.write_text(cycle() + index(0) + item() + reset() + route() + index(1))
            output = parse_saved_logs([p])
            self.assertNotIn(secret, json.dumps(output))
            self.assertIsNone(output["applicability"]["profile"])

    def test_files_are_independent_and_never_merged_into_a_sequence(self):
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / "a", Path(tmp) / "b"
            a.write_text(cycle() + index(0) + item())
            b.write_text(reset() + route() + index(1))
            out = parse_saved_logs([a, b])
            self.assertIsNone(out["files"][1]["routeObservations"][0]["assignedEcuId"])

    def test_sanitizer_is_strict_and_canonical(self):
        self.assertIsNotNone(sanitize_threadtime_line(route()))
        self.assertIsNotNone(sanitize_threadtime_line(item()))
        for value in [route("000/609"), route("601/FFFFFFFF"), route()[:-1] + " token=secret",
                      line("loopRequestEcuItemInfo e.ecuId: ECM_BYD_SYNTHETIC"),
                      line("loopRequestEcuItemInfo itemXecuId: ECM_BYD_SYNTHETIC"),
                      line("loopRequestEcuItemInfo item.ecuId: https://secret"),
                      route(stamp="99-99 99:99:99.000")]:
            self.assertIsNone(sanitize_threadtime_line(value))

    def test_sanitized_stream_preserves_invalid_record_gap_without_private_body(self):
        invalid = route().replace("F195/F194", "F195/ PRIVATE_SECRET")
        raw = cycle() + index(0) + item() + reset() + route() + invalid + index(1)
        kept = []
        for source_line in raw.splitlines():
            clean = sanitize_threadtime_line(source_line)
            if clean is not None:
                kept.append(clean)
            elif needs_gap_marker(source_line):
                kept.append(GAP_MARKER)
        sanitized = "\n".join(kept) + "\n"
        self.assertNotIn("PRIVATE_SECRET", sanitized)
        result = parse(sanitized)
        self.assertIsNone(result["routeObservations"][0]["assignedEcuId"])
        self.assertIn("observer_gap", result["routeObservations"][0]["reasons"])
        self.assertIn({"line": 6, "reason": "observer_gap"}, result["rejectedRecords"])

    def test_routine_unknown_and_post_loop_records_do_not_create_false_gaps(self):
        for routine in [line("onDiagnosticComplete: check failed"),
                        line("loopRequestEcuItemInfo e.ecuId: BMS_BYD_SYNTHETIC"),
                        line("parseEcuSoftCodeInfo ecuSoftCode: 0000", "CanDiagnostic"),
                        "10-18 12:00:00.000 101 101 I OTA_GCY_UPGRADE_HttpApiService: PRIVATE_SECRET\n"]:
            self.assertIsNone(sanitize_threadtime_line(routine))
            self.assertFalse(needs_gap_marker(routine))
        self.assertTrue(needs_gap_marker(route(stamp="99-99 99:99:99.000")))
        self.assertTrue(needs_gap_marker(GAP_MARKER))

    def test_empty_initial_version_metadata_is_safe_and_does_not_break_sequence(self):
        empty = line("loopRequestEcuItemInfo item.ecuSoftVer: ")
        self.assertIsNotNone(sanitize_threadtime_line(empty))
        result = parse(cycle(), index(0), item(), empty, reset(), route(), index(1))
        self.assertEqual(result["candidates"][0]["initialItemMetadata"]["ecuSoftVer"]["value"], "")
        self.assertEqual(result["routeObservations"][0]["assignedEcuId"], "BMS_BYD_SYNTHETIC")


if __name__ == "__main__":
    unittest.main()
