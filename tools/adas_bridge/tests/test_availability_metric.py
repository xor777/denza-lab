"""Temporal metric contracts: actual elapsed time, uncertainty and censoring."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from tools.adas_bridge.availability_metric import analyze, main


def dataset(end=1000, *, start=0, guidance=None, path=None, pilot=None):
    return dict(window=dict(startMs=start, endMs=end, pathId=7),
                guidance=guidance if guidance is not None else [dict(tMs=0, speedMps=5)],
                path=path if path is not None else [dict(tMs=0, pathId=7)],
                pilot=pilot if pilot is not None else [])


def samples(field, *pairs):
    return [dict(tMs=t, **{field: value}) for t, value in pairs]


class AvailabilityMetricTest(unittest.TestCase):
    def test_irregular_samples_weight_elapsed_time_not_packet_counts(self):
        # Three of four packets offer NOA, yet only 90 / 1000 ms offer it.
        report = analyze(dataset(pilot=samples("dnp", (0, 2), (30, 2), (60, 2), (90, 0))))
        self.assertEqual(report["durationsMs"]["offered"], 90)
        self.assertEqual(report["ratios"]["offeredOrActiveAmongKnown"], .09)
        self.assertEqual(report["classification"], "atMost10Percent")

    def test_stale_dropout_creates_exact_unknown_interval(self):
        report = analyze(dataset(3000, guidance=samples("speedMps", (0, 4), (1400, 4), (2800, 4)),
                                 pilot=samples("dnp", (0, 2), (2000, 0))))
        self.assertEqual(report["durationsMs"]["moving"], 3000)
        self.assertEqual(report["durationsMs"]["pilotUnknownInvalid"], 1000)
        self.assertEqual(report["durationsMs"]["offered"], 1000)
        self.assertEqual(report["ratios"]["offeredOrActiveLowerBound"], 1 / 3)
        self.assertEqual(report["ratios"]["offeredOrActiveUpperBound"], 2 / 3)
        self.assertEqual(report["classification"], "insufficientCoverage")

    def test_speed_expiry_and_route_change_have_independent_denominators(self):
        report = analyze(dataset(3000, path=samples("pathId", (0, 7), (1200, 8), (2000, 7)),
                                 pilot=samples("dnp", (0, 0), (1000, 0), (2000, 0))))
        self.assertEqual(report["durationsMs"]["moving"], 1200)
        self.assertEqual(report["durationsMs"]["otherPath"], 800)
        self.assertEqual(report["durationsMs"]["unknownSpeed"], 1000)
        self.assertEqual(report["durationsMs"]["selectedPath"], 2200)

    def test_pre_window_samples_are_baselines_without_future_fill(self):
        report = analyze(dataset(2000, start=1000,
                                 guidance=samples("speedMps", (900, 4)),
                                 path=samples("pathId", (100, 7)),
                                 pilot=samples("dnp", (500, 2), (1700, 0))))
        self.assertEqual(report["durationsMs"]["offered"], 500)
        self.assertEqual(report["durationsMs"]["pilotUnknownInvalid"], 200)
        self.assertEqual(report["durationsMs"]["unavailable"], 300)

    def test_future_sample_cannot_fill_earlier_unknown_speed_or_path(self):
        report = analyze(dataset(guidance=samples("speedMps", (400, 4)),
                                 path=samples("pathId", (200, 7)),
                                 pilot=samples("dnp", (500, 2))))
        self.assertEqual(report["durationsMs"]["unknownPath"], 200)
        self.assertEqual(report["durationsMs"]["unknownSpeed"], 200)
        self.assertEqual(report["durationsMs"]["moving"], 600)
        self.assertEqual(report["durationsMs"]["pilotUnknownInvalid"], 100)

    def test_zero_is_unavailable_and_invalid_or_absent_is_unknown(self):
        report = analyze(dataset(pilot=samples("dnp", (0, 0), (200, None), (400, 9), (600, 2))))
        self.assertEqual(report["durationsMs"]["unavailable"], 200)
        self.assertEqual(report["durationsMs"]["pilotUnknownInvalid"], 400)
        self.assertEqual(report["durationsMs"]["offered"], 400)
        absent = analyze(dataset())
        self.assertIsNone(absent["ratios"]["offeredOrActiveAmongKnown"])
        self.assertEqual(absent["ratios"]["offeredOrActiveLowerBound"], 0)
        self.assertEqual(absent["ratios"]["offeredOrActiveUpperBound"], 1)
        self.assertEqual(absent["classification"], "insufficientCoverage")

    def test_negative_unknown_dnp_is_not_guessed_unavailable(self):
        report = analyze(dataset(pilot=samples("dnp", (0, -1), (500, 0))))
        self.assertEqual(report["durationsMs"]["pilotUnknownInvalid"], 500)
        self.assertEqual(report["durationsMs"]["unavailable"], 500)
        self.assertEqual(report["durationsMs"]["pilotKnown"], 500)
        self.assertEqual(report["ratios"]["offeredOrActiveUpperBound"], .5)

    def test_explicit_invalid_observation_breaks_previous_hold(self):
        report = analyze(dataset(guidance=[dict(tMs=0, speedMps=5), dict(tMs=400, valid=False)],
                                 pilot=[dict(tMs=0, dnp=2), dict(tMs=200, valid=False)]))
        self.assertEqual(report["durationsMs"]["offered"], 200)
        self.assertEqual(report["durationsMs"]["pilotUnknownInvalid"], 200)
        self.assertEqual(report["durationsMs"]["unknownSpeed"], 600)

    def test_half_open_window_and_strict_moving_threshold(self):
        report = analyze(dataset(guidance=samples("speedMps", (0, 1.5), (500, 1.5001)),
                                 pilot=samples("dnp", (0, 0), (1000, 2))))
        self.assertEqual(report["durationsMs"]["moving"], 500)
        self.assertEqual(report["durationsMs"]["stationary"], 500)
        self.assertEqual(report["durationsMs"]["offered"], 0)

    def test_sample_at_expiry_refreshes_without_gap(self):
        report = analyze(dataset(2000, guidance=samples("speedMps", (0, 5), (1500, 5)),
                                 pilot=samples("dnp", (0, 2), (1000, 2))))
        self.assertEqual(report["durationsMs"]["offered"], 2000)
        self.assertEqual(report["durationsMs"]["pilotUnknownInvalid"], 0)

    def test_bounds_must_agree_with_inclusive_category_thresholds(self):
        high = analyze(dataset(pilot=samples("dnp", (0, 2), (500, None))), min_pilot_coverage=0)
        self.assertEqual(high["classification"], "atLeast50Percent")
        low = analyze(dataset(pilot=samples("dnp", (0, 0), (900, None))), min_pilot_coverage=0)
        self.assertEqual(low["classification"], "atMost10Percent")
        unsure = analyze(dataset(pilot=samples("dnp", (0, 2), (400, 0), (900, None))), min_pilot_coverage=0)
        self.assertEqual(unsure["classification"], "inconclusive")

    def test_default_coverage_gate_exposes_missing_guidance_and_pilot(self):
        # A high known-pilot ratio cannot classify a mostly unobserved drive.
        report = analyze(dataset(2000, pilot=samples("dnp", (0, 2), (1000, 2))))
        self.assertEqual(report["ratios"]["guidanceCoverage"], .75)
        self.assertEqual(report["ratios"]["offeredOrActiveAmongKnown"], 1)
        self.assertEqual(report["classification"], "insufficientCoverage")
        report = analyze(dataset(pilot=samples("dnp", (0, 2), (940, None))))
        self.assertEqual(report["ratios"]["pilotCoverage"], .94)
        self.assertEqual(report["classification"], "insufficientCoverage")
        report = analyze(dataset(pilot=samples("dnp", (0, 2), (950, None))))
        self.assertEqual(report["classification"], "atLeast50Percent")

    def test_no_moving_denominator_has_null_ratios(self):
        report = analyze(dataset(guidance=samples("speedMps", (0, 0)), pilot=samples("dnp", (0, 2))))
        self.assertEqual(report["ratios"].pop("guidanceCoverage"), 1)
        self.assertTrue(all(v is None for v in report["ratios"].values()))
        self.assertEqual(report["classification"], "insufficientCoverage")

    def test_episodes_retain_stationary_active_time_and_observed_samples(self):
        report = analyze(dataset(2000, guidance=samples("speedMps", (0, 4), (500, 0), (1500, 4)),
                                 pilot=samples("dnp", (0, 0), (100, 1), (900, 1), (1700, 2))))
        episode, = report["activeEpisodes"]
        self.assertEqual(episode["heldActiveMs"], 1600)
        self.assertEqual((episode["firstActiveSampleMs"], episode["lastActiveSampleMs"]), (100, 900))
        self.assertEqual(episode["nextDifferentSampleMs"], 1700)
        self.assertFalse(episode["leftCensored"])
        self.assertFalse(episode["rightCensored"])
        self.assertEqual(report["durationsMs"]["active"], 600)

    def test_gaps_and_window_censor_episodes_without_inventing_exit(self):
        report = analyze(dataset(3000, pilot=samples("dnp", (0, 1), (2000, 1))))
        first, second = report["activeEpisodes"]
        self.assertEqual((first["endMs"], first["endReason"]), (1000, "pilotExpired"))
        self.assertTrue(first["leftCensored"])
        self.assertTrue(first["rightCensored"])
        self.assertIsNone(first["nextDifferentSampleMs"])
        self.assertTrue(second["leftCensored"])
        self.assertEqual(second["endReason"], "windowEnd")

    def test_route_exit_censors_active_episode_and_reentry_does_not_assert_onset(self):
        report = analyze(dataset(path=samples("pathId", (0, 7), (300, 8), (700, 7)),
                                 pilot=samples("dnp", (0, 1))))
        first, second = report["activeEpisodes"]
        self.assertEqual(first["endReason"], "pathExit")
        self.assertTrue(first["rightCensored"])
        self.assertTrue(second["leftCensored"])
        self.assertEqual(second["heldActiveMs"], 300)

    def test_identical_duplicates_are_deduplicated_but_conflicts_fail(self):
        rows = samples("dnp", (0, 2), (0, 2))
        self.assertEqual(analyze(dataset(pilot=rows))["durationsMs"]["offered"], 1000)
        for name, field, values in (("pilot", "dnp", [0, 2]), ("guidance", "speedMps", [3, 4]),
                                    ("path", "pathId", [7, 8])):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "conflicting"):
                analyze(dataset(**{name: samples(field, (0, values[0]), (0, values[1]))}))

    def test_malformed_time_and_nonfinite_speed_fail(self):
        for value in (None, True, 1.5, -1, "100"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                analyze(dataset(pilot=[dict(tMs=value, dnp=2)]))
        for value in (float("nan"), float("inf"), -1, True):
            with self.subTest(speed=value), self.assertRaises(ValueError):
                analyze(dataset(guidance=[dict(tMs=0, speedMps=value)]))

    def test_configurable_ttls_and_cli_json(self):
        data = dataset(pilot=samples("dnp", (0, 2)))
        report = analyze(data, guidance_ttl_ms=800, pilot_ttl_ms=200)
        self.assertEqual(report["durationsMs"]["moving"], 800)
        self.assertEqual(report["durationsMs"]["offered"], 200)
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory) / "in.json", Path(directory) / "out.json"
            source.write_text(json.dumps(data))
            self.assertEqual(main([str(source), "--output", str(target)]), 0)
            self.assertEqual(json.loads(target.read_text())["durationsMs"]["offered"], 1000)
            with contextlib.redirect_stdout(io.StringIO()) as output:
                main([str(source)])
            self.assertEqual(json.loads(output.getvalue())["schema"], "denza-noa-availability-v1")


if __name__ == "__main__":
    unittest.main()
