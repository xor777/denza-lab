"""Host checks for the stock ODD calculator and its real response fixture."""
from pathlib import Path
import unittest

from tools.adas_bridge import feed
from tools.adas_bridge.odd_bench import MAX, distance

ROOT = Path(__file__).resolve().parents[3]


def route(urids=(372, 372, 1), lengths=(100, 200, 300)):
    return [[{'urid': urid, 'length': length}] for urid, length in zip(urids, lengths)]


class OddBenchTest(unittest.TestCase):
    def test_stock_boundaries(self):
        segments = route()
        self.assertEqual(MAX, distance(segments, 0, 0, 100, []))
        self.assertEqual(300, distance(segments, 0, 0, 100, [372]))
        self.assertEqual(MAX, distance(segments, 0, 0, 100, [1, 372]))
        self.assertEqual(0, distance(segments, 0, 0, 100, [1]))
        self.assertEqual(0, distance(segments, 2, 0, 300, [372]))
        self.assertEqual(MAX, distance(segments, 2, 0, 300, [1]))

    def test_transition_precedes_length_and_lookahead(self):
        segments = route(urids=(372, 1, 1), lengths=(100, 200, 300))
        self.assertEqual(75, distance(segments, 0, 0, 75, [372]))
        covered = route(urids=(1, 1, 1), lengths=(100, 2000, 300))
        self.assertEqual(MAX, distance(covered, 0, 0, 100, [999]))

    def test_real_adas_response_contains_stock_urid(self):
        raw = (ROOT / 'captures/adas-h0-listen-20261024/H0-odd-response.bin').read_bytes()
        decoded = feed.decode('ODDRegionCodeResp', raw)
        cities = [row['oDDRegionCodeValue'] for row in decoded['oDDRegionCodeRspInfo']]
        self.assertEqual(374, len(cities))
        self.assertIn(372, cities)


if __name__ == '__main__':
    unittest.main()
