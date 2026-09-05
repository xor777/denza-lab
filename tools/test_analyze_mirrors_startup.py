#!/usr/bin/env python3

from __future__ import annotations

import unittest

import analyze_mirrors_startup as analyzer


def log(pid: int, tag: str, message: str, *, tid: int | None = None) -> str:
    return f"09-05 12:00:00.000 {pid} {tid or pid} I {tag}: {message}\n"


def window(pid: int, side: str, observed_ms: int, *, ambiguous: bool = False) -> str:
    return log(
        pid,
        "DenzaMirrorMonitor",
        f"window timing; side={side} ambiguous={str(ambiguous).lower()} "
        f"read_started_ms={observed_ms - 20} observed_ms={observed_ms} "
        f"previous_read_started_ms={observed_ms - 120}",
    )


def transition(pid: int, old: str, side: str) -> str:
    return log(
        pid,
        "DenzaMirrorMonitor",
        f"transition {old} -> STARTING (synthetic); requested={side} ambiguous=false runtime=IDLE",
    )


def command(pid: int, side: str, observed_ms: int, command_ms: int) -> str:
    return log(
        pid,
        "DenzaMirrorMonitor",
        f"command: show {side}; observed_ms={observed_ms} command_ms={command_ms}",
    )


def request(pid: int, generation: int, side: str, at_ms: int) -> str:
    return log(
        pid,
        "DenzaClusterScene",
        f"camera request; generation={generation} side={side} at_ms={at_ms}",
    )


def dispatch(pid: int, generation: int, side: str, at_ms: int) -> str:
    return log(
        pid,
        "DenzaClusterScene",
        f"showCamera {side}; generation={generation} at_ms={at_ms}",
    )


def ready(pid: int, generation: int, side: str) -> str:
    return log(
        pid,
        "DenzaClusterScene",
        f"AVC ready; generation={generation} side={side} details=synthetic",
    )


def frame(
    pid: int,
    generation: int,
    renderer_start_ms: int,
    *,
    first_update_ms: int | None = None,
    bind_ready_ms: int = 20,
    surface_ready_ms: int = 10,
    get_name_ms: int = 1,
    buffer_type_ms: int = 1,
    init_display_ms: int = 60,
    viewpoint_ms: int = 1,
    ready_to_frame_ms: int = 8,
    renderer_to_frame_ms: int | None = None,
) -> str:
    sequential = bind_ready_ms + get_name_ms + buffer_type_ms + init_display_ms + viewpoint_ms
    total = renderer_to_frame_ms if renderer_to_frame_ms is not None else sequential + ready_to_frame_ms
    first_update_ms = first_update_ms if first_update_ms is not None else renderer_start_ms + total
    return log(
        pid,
        "DenzaClusterScene",
        f"AVC first texture update; generation={generation} "
        f"renderer_start_ms={renderer_start_ms} first_update_ms={first_update_ms} "
        f"renderer_to_frame_ms={total} bind_ready_ms={bind_ready_ms} "
        f"surface_ready_ms={surface_ready_ms} get_name_ms={get_name_ms} "
        f"buffer_type_ms={buffer_type_ms} init_display_ms={init_display_ms} "
        f"viewpoint_ms={viewpoint_ms} ready_to_frame_ms={ready_to_frame_ms}",
    )


def successful_start(
    pid: int,
    generation: int,
    side: str,
    window_ms: int,
    command_observed_ms: int,
    command_ms: int,
    request_ms: int,
    *,
    old_phase: str = "IDLE",
    include_window: bool = True,
) -> list[str]:
    lines = []
    if include_window:
        lines.append(window(pid, side, window_ms))
    lines.extend(
        [
            transition(pid, old_phase, side),
            command(pid, side, command_observed_ms, command_ms),
            request(pid, generation, side, request_ms),
            dispatch(pid, generation, side, request_ms + 4),
            ready(pid, generation, side),
            frame(pid, generation, request_ms + 24),
        ]
    )
    return lines


class AnalyzeMirrorsStartupTest(unittest.TestCase):
    def test_normal_start_keeps_two_observation_origins_separate(self):
        capture = analyzer.analyze_lines(
            successful_start(101, 1, "LEFT", 1_000, 1_002, 1_003, 1_004)
        )

        self.assertEqual(1, len(capture.attempts))
        attempt = capture.attempts[0]
        self.assertEqual("success", attempt.outcome)
        self.assertEqual("ordinary_idle", attempt.origin)
        self.assertEqual("first_observed", attempt.cohort)
        self.assertEqual(4, attempt.metrics()["window_first_observed_to_request_ms"])
        self.assertEqual(2, attempt.metrics()["command_poll_observed_to_request_ms"])
        self.assertEqual(4, attempt.metrics()["request_to_dispatch_ms"])
        self.assertEqual(20, attempt.metrics()["renderer_to_bind_ready_ms"])
        self.assertEqual(71, attempt.metrics()["bind_ready_to_first_update_ms"])

    def test_lookalike_messages_from_other_tags_are_ignored(self):
        lines = [
            log(
                100,
                "UnrelatedTag",
                "camera request; generation=1 side=LEFT at_ms=900",
            )
        ]
        lines.extend(successful_start(101, 1, "LEFT", 1_000, 1_000, 1_001, 1_001))
        capture = analyzer.analyze_lines(lines)
        self.assertEqual(1, len(capture.attempts))
        self.assertFalse(capture.orphan_events)

    def test_explicit_first_after_install_is_a_distinct_cohort(self):
        lines = [
            transition(110, "IDLE", "LEFT"),
            command(110, "LEFT", 900, 901),
        ]
        lines.extend(successful_start(110, 3, "RIGHT", 1_000, 1_000, 1_001, 1_001))
        capture = analyzer.analyze_lines(
            lines,
            metadata={"first_after_install": "110:3"},
        )
        cold = next(item for item in capture.attempts if item.generation == 3)
        self.assertTrue(cold.first_after_install)
        self.assertEqual("first_after_install", cold.cohort)

    def test_recovery_uses_first_window_change_not_command_poll(self):
        capture = analyzer.analyze_lines(
            successful_start(
                102,
                3,
                "RIGHT",
                2_000,
                2_129,
                2_130,
                2_131,
                old_phase="QUARANTINED",
            )
        )
        attempt = capture.attempts[0]
        self.assertEqual("recovery", attempt.origin)
        self.assertEqual(131, attempt.metrics()["window_first_observed_to_request_ms"])
        self.assertEqual(2, attempt.metrics()["command_poll_observed_to_request_ms"])

    def test_missing_failure_and_teardown_attempts_are_not_dropped(self):
        missing = successful_start(201, 1, "LEFT", 1_000, 1_000, 1_001, 1_001)[:-2]
        failed = successful_start(202, 1, "RIGHT", 2_000, 2_000, 2_001, 2_001)[:-2]
        failed.append(
            log(202, "DenzaClusterScene", "AVC failure; generation=1 side=RIGHT details=synthetic")
        )
        torn_down = successful_start(203, 1, "LEFT", 3_000, 3_000, 3_001, 3_001)[:-2]
        torn_down.append(log(203, "DenzaClusterScene", "hideCamera: releasing surface"))

        capture = analyzer.analyze_lines(missing + failed + torn_down)
        self.assertEqual(
            [
                "missing_first_update_at_capture_end",
                "explicit_failure",
                "teardown_before_first_update",
            ],
            [item.outcome for item in capture.attempts],
        )

    def test_truncated_and_out_of_order_capture_is_inconclusive(self):
        lines = [frame(301, 9, 500)]
        lines.extend(
            [
                window(302, "LEFT", 1_000),
                transition(302, "IDLE", "LEFT"),
                command(302, "LEFT", 1_000, 1_001),
                request(302, 1, "LEFT", 1_002),
                dispatch(302, 1, "LEFT", 999),
                frame(302, 1, 1_020),
            ]
        )
        capture = analyzer.analyze_lines(lines)

        self.assertEqual(1, len(capture.orphan_events))
        self.assertEqual("inconclusive_segment_order", capture.attempts[0].outcome)
        self.assertTrue(any("monotonic event rollback" in item for item in capture.warnings))

    def test_multiple_pids_and_generation_restart_do_not_cross_correlate(self):
        lines = successful_start(401, 3, "LEFT", 1_000, 1_000, 1_001, 1_001)
        lines.extend(successful_start(402, 1, "RIGHT", 2_000, 2_000, 2_001, 2_001))
        lines.extend(successful_start(401, 1, "RIGHT", 3_000, 3_000, 3_001, 3_001))
        capture = analyzer.analyze_lines(lines)

        pid_402 = [item for item in capture.attempts if item.pid == 402]
        self.assertEqual(1, len(pid_402))
        self.assertEqual("success", pid_402[0].outcome)
        restarted = [item for item in capture.attempts if item.pid == 401 and item.segment == 2]
        self.assertEqual(1, len(restarted))
        self.assertIsNone(restarted[0].command_ms)
        self.assertIsNone(restarted[0].window_first_observed_ms)
        self.assertTrue(any("generation restarted" in item for item in capture.warnings))

    def test_late_old_generation_frame_after_restart_is_orphan(self):
        lines = successful_start(450, 5, "LEFT", 1_000, 1_000, 1_001, 1_001)[:-1]
        lines.extend(
            [
                command(450, "RIGHT", 2_000, 2_001),
                request(450, 1, "RIGHT", 2_001),
                frame(450, 5, 1_025),
                dispatch(450, 1, "RIGHT", 2_005),
                frame(450, 1, 2_025),
            ]
        )
        capture = analyzer.analyze_lines(lines)

        self.assertTrue(any("orphan first-update" in item for item in capture.orphan_events))
        old = next(item for item in capture.attempts if item.generation == 5)
        new = next(item for item in capture.attempts if item.generation == 1)
        self.assertEqual("missing_first_update_at_capture_end", old.outcome)
        self.assertEqual("success", new.outcome)

    def test_surface_order_and_unmeasured_gaps_are_not_summed_as_exact(self):
        positive = successful_start(460, 1, "LEFT", 1_000, 1_000, 1_001, 1_001)[:-1]
        positive.append(
            frame(
                460,
                1,
                1_025,
                bind_ready_ms=20,
                surface_ready_ms=70,
                get_name_ms=1,
                buffer_type_ms=0,
                init_display_ms=50,
                viewpoint_ms=1,
                ready_to_frame_ms=8,
                renderer_to_frame_ms=140,
            )
        )
        negative = successful_start(461, 1, "RIGHT", 2_000, 2_000, 2_001, 2_001)[:-1]
        negative.append(
            frame(
                461,
                1,
                2_025,
                bind_ready_ms=20,
                surface_ready_ms=70,
                get_name_ms=1,
                buffer_type_ms=0,
                init_display_ms=50,
                viewpoint_ms=1,
                ready_to_frame_ms=8,
                renderer_to_frame_ms=120,
            )
        )

        capture = analyzer.analyze_lines(positive + negative)
        self.assertEqual("success", capture.attempts[0].outcome)
        self.assertEqual("invalid_timing", capture.attempts[1].outcome)
        self.assertTrue(any("overlapping readiness" in issue for issue in capture.attempts[1].issues))

    def test_stale_window_and_negative_stage_arithmetic_are_explicit(self):
        stale = successful_start(
            501,
            1,
            "LEFT",
            1_000,
            12_000,
            12_001,
            12_001,
        )
        negative = successful_start(502, 1, "RIGHT", 20_000, 20_000, 20_001, 20_001)[:-1]
        negative.append(frame(502, 1, 20_025, bind_ready_ms=-1))
        capture = analyzer.analyze_lines(stale + negative)

        self.assertIsNone(capture.attempts[0].window_first_observed_ms)
        self.assertTrue(any("old" in item for item in capture.attempts[0].issues))
        self.assertEqual("invalid_timing", capture.attempts[1].outcome)
        self.assertTrue(any("negative" in item for item in capture.attempts[1].issues))

    def test_ambiguous_window_invalidates_latency_origin_until_clear(self):
        lines = [window(550, "LEFT", 1_000)]
        lines.append(window(550, "LEFT", 1_100, ambiguous=True))
        lines.extend(
            successful_start(
                550,
                1,
                "LEFT",
                1_000,
                1_200,
                1_201,
                1_201,
                include_window=False,
            )
        )
        capture = analyzer.analyze_lines(lines)
        self.assertIsNone(capture.attempts[0].window_first_observed_ms)
        self.assertTrue(any("ambiguous" in item for item in capture.attempts[0].issues))

    def test_late_frame_after_teardown_or_failure_is_not_success(self):
        teardown_lines = successful_start(560, 1, "LEFT", 1_000, 1_000, 1_001, 1_001)[:-2]
        teardown_lines.extend(
            [
                log(560, "DenzaClusterScene", "hideCamera: releasing surface"),
                frame(560, 1, 1_025),
            ]
        )
        failure_lines = successful_start(561, 1, "RIGHT", 2_000, 2_000, 2_001, 2_001)[:-2]
        failure_lines.extend(
            [
                log(561, "DenzaClusterScene", "AVC failure; generation=1 side=RIGHT details=synthetic"),
                frame(561, 1, 2_025),
            ]
        )
        capture = analyzer.analyze_lines(teardown_lines + failure_lines)
        self.assertEqual(
            [
                "inconclusive_first_update_after_teardown",
                "inconclusive_first_update_after_failure",
            ],
            [item.outcome for item in capture.attempts],
        )

    def test_failure_after_first_update_remains_visible_without_dropping_timing(self):
        lines = successful_start(565, 1, "LEFT", 1_000, 1_000, 1_001, 1_001)
        lines.append(
            log(565, "DenzaClusterScene", "AVC failure; generation=1 side=LEFT details=late")
        )
        capture = analyzer.analyze_lines(lines)
        attempt = capture.attempts[0]

        self.assertEqual("success", attempt.outcome)
        self.assertTrue(attempt.post_first_update_failure)
        self.assertIsNotNone(attempt.metrics()["request_to_first_update_ms"])
        self.assertTrue(any("stability failed" in item for item in capture.warnings))
        self.assertTrue(analyzer.attempt_dict(attempt)["post_first_update_failure"])

    def test_duplicate_or_side_conflicting_generation_events_are_excluded(self):
        duplicate = successful_start(570, 1, "LEFT", 1_000, 1_000, 1_001, 1_001)
        duplicate.append(frame(570, 1, 1_025))
        mismatch = successful_start(571, 1, "RIGHT", 2_000, 2_000, 2_001, 2_001)[:-3]
        mismatch.extend(
            [
                dispatch(571, 1, "LEFT", 2_005),
                ready(571, 1, "RIGHT"),
                frame(571, 1, 2_025),
            ]
        )
        capture = analyzer.analyze_lines(duplicate + mismatch)
        self.assertEqual(
            ["inconclusive_correlation", "inconclusive_correlation"],
            [item.outcome for item in capture.attempts],
        )

    def test_off_tail_repeat_is_potential_reopen_with_separate_evidence(self):
        lines = successful_start(601, 1, "LEFT", 1_000, 1_000, 1_001, 1_001)
        lines.extend(
            [
                log(
                    601,
                    "DenzaMirrorMonitor",
                    "CAN preempt accepted; reason=lever moved right age=2ms commandGeneration=2 runtime=READY",
                ),
                log(601, "DenzaClusterScene", "hideCamera: releasing surface"),
                log(
                    601,
                    "DenzaMirrorMonitor",
                    "turn-signal shadow: state=off; window=left; agreement=window-without-directional-signal",
                ),
            ]
        )
        lines.extend(
            successful_start(
                601,
                3,
                "LEFT",
                1_000,
                4_000,
                4_001,
                4_001,
                old_phase="QUARANTINED",
                include_window=False,
            )
        )
        capture = analyzer.analyze_lines(lines)
        repeat = capture.attempts[1]

        self.assertTrue(repeat.repeated_show_same_window)
        self.assertTrue(repeat.potential_cancellation_reopen)
        self.assertTrue(repeat.mode_off_evidence)
        self.assertTrue(repeat.preempt_evidence)
        self.assertTrue(repeat.teardown_evidence)

    def test_long_continuous_window_still_flags_repeat_but_not_latency_origin(self):
        lines = successful_start(650, 1, "LEFT", 1_000, 1_000, 1_001, 1_001)
        lines.extend(
            successful_start(
                650,
                3,
                "LEFT",
                1_000,
                20_000,
                20_001,
                20_001,
                old_phase="QUARANTINED",
                include_window=False,
            )
        )
        capture = analyzer.analyze_lines(lines)
        repeat = capture.attempts[1]
        self.assertTrue(repeat.potential_cancellation_reopen)
        self.assertIsNone(repeat.window_first_observed_ms)

    def test_real_window_close_and_reopen_is_not_a_repeat(self):
        lines = successful_start(701, 1, "RIGHT", 1_000, 1_000, 1_001, 1_001)
        lines.append(window(701, "null", 2_000))
        lines.extend(successful_start(701, 3, "RIGHT", 3_000, 3_000, 3_001, 3_001))
        capture = analyzer.analyze_lines(lines)

        self.assertFalse(capture.attempts[1].repeated_show_same_window)
        self.assertNotEqual(
            capture.attempts[0].window_cycle_id,
            capture.attempts[1].window_cycle_id,
        )

    def test_compare_matches_origin_side_and_first_observed_cohort_only(self):
        control = analyzer.analyze_lines(
            successful_start(801, 1, "LEFT", 1_000, 1_000, 1_001, 1_001),
            label="control",
        )
        candidate = analyzer.analyze_lines(
            successful_start(802, 1, "LEFT", 2_000, 2_000, 2_001, 2_001),
            label="candidate",
        )
        result = analyzer.compare_captures(control, candidate)
        comparison = result["comparison"]

        self.assertEqual(
            ["ordinary_idle/LEFT/first_observed/new_window_cycle"],
            list(comparison["categories"]),
        )
        self.assertTrue(any("small sample" in item for item in comparison["warnings"]))

    def test_human_compare_shows_excluded_non_success_outcomes(self):
        control_lines = successful_start(901, 1, "LEFT", 1_000, 1_000, 1_001, 1_001)
        control_lines.extend(
            successful_start(902, 1, "LEFT", 2_000, 2_000, 2_001, 2_001)[:-2]
        )
        control = analyzer.analyze_lines(control_lines, label="control")
        candidate = analyzer.analyze_lines(
            successful_start(903, 1, "LEFT", 3_000, 3_000, 3_001, 3_001),
            label="candidate",
        )
        result = analyzer.compare_captures(control, candidate)
        rendered = analyzer.render_comparison(result)

        self.assertIn("missing_first_update_at_capture_end:1", rendered)
        self.assertTrue(
            any(
                "non-success attempts excluded" in item
                for item in result["comparison"]["warnings"]
            )
        )


if __name__ == "__main__":
    unittest.main()
