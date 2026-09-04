#!/usr/bin/env python3

import unittest

import analyze_can_turn_capture as analyzer


def frame(sequence: int, time_ns: int, payload: int) -> str:
    return (
        "[RawCanTurnProbe] FRAME "
        f"seq={sequence} t_ns={time_ns} id=0x123 sub=0x00 ch=1 "
        f"cnt={sequence} len=1 payload={payload:02x}\n"
    )


class AnalyzeCanTurnCaptureTest(unittest.TestCase):
    def test_ranks_two_blinking_turn_bits_on_one_frame(self):
        lines = []
        sequence = 0
        time_ns = 0
        for label, values in (
            ("neutral_1", [0x00] * 10),
            ("left_1", [0x01, 0x00] * 5),
            ("neutral_2", [0x00] * 10),
            ("right_1", [0x02, 0x00] * 5),
            ("hazard_1", [0x03, 0x00] * 5),
        ):
            time_ns += 2_000_000_000
            lines.append(f"[RawCanTurnProbe] MARK t_ns={time_ns} label={label}\n")
            for value in values:
                sequence += 1
                time_ns += 100_000_000
                lines.append(frame(sequence, time_ns, value))

        capture = analyzer.parse_capture(lines)
        labelled = analyzer.labelled_frames(capture, settle_ms=0)
        candidates = analyzer.rank_bits(labelled)
        pairs = analyzer.rank_pairs(candidates)

        self.assertTrue(pairs)
        score, left, right = pairs[0]
        self.assertEqual(0, left.bit_index)
        self.assertEqual(1, right.bit_index)
        self.assertGreaterEqual(score, 0.74)

    def test_ignores_frames_before_marker_and_settling_window(self):
        lines = [
            frame(1, 1_000_000_000, 0),
            "[RawCanTurnProbe] MARK t_ns=2000000000 label=left_1\n",
            frame(2, 2_200_000_000, 1),
            frame(3, 3_600_000_000, 1),
        ]
        capture = analyzer.parse_capture(lines)
        labelled = analyzer.labelled_frames(capture, settle_ms=1500)
        self.assertEqual([("left", capture.frames[2])], labelled)

    def test_reads_device_drop_summary_and_sequence_gaps(self):
        lines = [
            frame(4, 1_000_000_000, 0),
            frame(7, 2_000_000_000, 0),
            "[RawCanTurnProbe] DONE received=9 dropped=2 callback_errors=1 queued=0\n",
        ]
        capture = analyzer.parse_capture(lines)
        self.assertEqual(2, analyzer.sequence_gaps(capture.frames))
        self.assertEqual(9, capture.received)
        self.assertEqual(2, capture.dropped)
        self.assertEqual(1, capture.callback_errors)


if __name__ == "__main__":
    unittest.main()
