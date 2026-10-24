"""The live bridge's experiment variant against the parked chain it mirrors (scenario.py)."""
from pathlib import Path
import re
import unittest

from tools.adas_bridge import scenario

ROOT = Path(__file__).resolve().parents[3]
VARIANT_JAVA = ROOT / "libraries/adas-link/src/main/java/dev/denza/adaslink/ExperimentVariant.java"


class StockStartTests(unittest.TestCase):
    def test_java_stock_start_is_scenarios_pre_route_phase(self):
        text = VARIANT_JAVA.read_text(encoding="utf-8")
        match = re.search(r"STOCK_PRE_ROUTE_PHASE = \{(.*?)\};", text)
        self.assertIsNotNone(match)
        pairs = [tuple(int(x) for x in p.split(",")) for p in re.findall(r"\{\s*(\d+\s*,\s*\d+)\s*\}", match.group(1))]
        expected = [(step["status"], int(step["holdS"] * 1000)) for step in scenario.STOCK_PRE_ROUTE_PHASE]
        self.assertEqual(expected, pairs)

    def test_h3_is_the_variants_stock_start(self):
        # H3 drops exactly the ODD request and the pre-route phase; the variant's switch restores both.
        self.assertEqual({"oddRequest", "navStatus.preRoutePhase"}, scenario.ALLOWED["H3"])


if __name__ == "__main__":
    unittest.main()
