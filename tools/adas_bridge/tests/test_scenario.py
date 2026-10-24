"""Generator tests: H0 route equals the recorded stock payload (bar pathId/time), and every later
scenario differs from its predecessor only in its declared variable. No device, no build."""
import json
import tempfile
import unittest
from pathlib import Path

from tools.adas_bridge import scenario, stock


def _have_capture():
    return scenario.find_capture(scenario.STOCK_ROUTE_REL) is not None


@unittest.skipUnless(_have_capture(), "stock route capture not present")
class ScenarioChainTest(unittest.TestCase):
    def setUp(self):
        self.scenarios = scenario.build_scenarios()
        self.by_id = {s["id"]: s for s in self.scenarios}

    def test_chain_ids_and_parents(self):
        ids = [s["id"] for s in self.scenarios]
        self.assertEqual(ids[0], "H0")
        self.assertEqual(ids, ["H0", "H1", "H2a", "H2b", "H2c", "H2d", "H2e", "H2f", "H2g",
                               "H3", "H4", "H5", "H6", "H7", "LISTEN"])
        self.assertIsNone(self.by_id["H0"]["changedVariable"])
        self.assertEqual(self.by_id["H6"]["derivedFrom"], "H0")

    def test_h0_route_equals_recorded_except_pathid_and_time(self):
        recorded_inner = json.loads(
            scenario.stock_route_payload_path().read_text(encoding="utf-8-sig"))["payload"]
        emitted = scenario.route_payload_json(self.by_id["H0"])
        # Both are stock-gson MessageSend strings; only the pathID differs (token vs recorded).
        recorded_obj = json.loads(recorded_inner)
        emitted_obj = json.loads(emitted)
        self.assertEqual(recorded_obj["data"][0]["pathID"], 100002)
        self.assertEqual(emitted_obj["data"][0]["pathID"], scenario.PATH_ID_TOKEN)
        # Normalise pathID, then the bodies must be byte-identical: no time field lives in the
        # inner payload (time is in the outer envelope the player builds), so pathID is the only
        # permitted difference here.
        emitted_obj["data"][0]["pathID"] = recorded_obj["data"][0]["pathID"]
        self.assertEqual(stock.gson(emitted_obj), stock.gson(recorded_obj))

    def test_h0_route_byte_identical_when_pathid_restored(self):
        recorded_inner = json.loads(
            scenario.stock_route_payload_path().read_text(encoding="utf-8-sig"))["payload"]
        h0 = scenario.build_scenarios()[0]
        h0["route"]["pathIdToken"] = 100002
        self.assertEqual(scenario.route_payload_json(h0), recorded_inner)

    # H2c (link direction -> 0) is a no-op: the recorded stock feed already sends direction 0 on
    # every link (ANALYSIS R04, "CAR 0" on two-way OSM), so the step changes nothing. That is a
    # finding, not a defect; the step stays in the chain so the live session can still run it.
    NO_OP = {"H2c"}

    def test_each_scenario_changes_only_its_declared_variable(self):
        for scn in self.scenarios[1:]:
            if scn.get("listenOnly"):
                continue
            parent = self.by_id[scn["derivedFrom"]]
            changed, violations = scenario.diff_variable_ok(parent, scn)
            self.assertEqual(violations, set(),
                             f"{scn['id']} changed disallowed fields vs {scn['derivedFrom']}: {violations}")
            if scn["id"] not in self.NO_OP:
                self.assertTrue(changed, f"{scn['id']} changed nothing vs {scn['derivedFrom']}")

    def test_direction_is_already_stock_zero(self):
        link = self.by_id["H0"]["route"]["path"]["segments"][0]["links"][0]
        self.assertEqual(link["direction"], 0)

    def test_h1_is_the_frame_flip_on_identical_geometry(self):
        h0, h1 = self.by_id["H0"], self.by_id["H1"]
        self.assertEqual(h0["route"]["frame"], scenario.GCJ)
        self.assertEqual(h1["route"]["frame"], scenario.WGS84)
        # Same number of links and points; only the coordinate values move.
        h0_links = [l for s in h0["route"]["path"]["segments"] for l in s["links"]]
        h1_links = [l for s in h1["route"]["path"]["segments"] for l in s["links"]]
        self.assertEqual(len(h0_links), len(h1_links))
        self.assertNotEqual(h0_links[0]["lon"], h1_links[0]["lon"])
        self.assertEqual(h0_links[0]["roadClass"], h1_links[0]["roadClass"])

    def test_h2f_touches_position_and_trafficinfo_only(self):
        changed, violations = scenario.diff_variable_ok(self.by_id["H2e"], self.by_id["H2f"])
        self.assertEqual(violations, set())
        self.assertIn("position.ownership", changed)
        self.assertIn("trafficInfo.roadClass", changed)

    def test_h3_drops_odd_and_106(self):
        h3 = self.by_id["H3"]
        self.assertFalse(h3["oddRequest"])
        self.assertEqual(h3["navStatus"]["preRoutePhase"], [])

    def test_h4_is_five_hz(self):
        self.assertEqual(self.by_id["H4"]["rates"]["position"], 5.0)
        self.assertEqual(self.by_id["H3"]["rates"]["position"], 1.0)

    def test_h7_is_h0_with_only_instrument_status_and_safe_rehearsal(self):
        changed, violations = scenario.diff_variable_ok(self.by_id["H0"], self.by_id["H7"])
        self.assertFalse(violations)
        self.assertEqual(self.by_id["H7"]["derivedFrom"], "H0")
        self.assertEqual(self.by_id["H7"]["instrumentNaviStatus"], {"value": 2})
        self.assertIsNone(self.by_id["H7"]["naviSt"])
        self.assertIsNone(self.by_id["H0"]["instrumentNaviStatus"])
        rows = scenario.rehearse(self.by_id["H7"])
        kinds = [r["kind"] for r in rows]
        self.assertEqual(kinds[0], "instrumentNaviStatus.read(host)")
        self.assertEqual(kinds.index("instrumentNaviStatus.write(host)") + 1, kinds.index("route"))
        self.assertEqual(kinds[-2:], ["instrumentNaviStatus.restore(host)", "instrumentNaviStatus.verify(host)"])
        self.assertFalse(any(k.startswith("naviSt.") for k in kinds))

    def test_h6_adds_navi_st_only(self):
        changed, violations = scenario.diff_variable_ok(self.by_id["H0"], self.by_id["H6"])
        self.assertEqual(violations, set())
        self.assertEqual(self.by_id["H6"]["naviSt"], {"value": 2})
        self.assertIsNone(self.by_id["H0"]["naviSt"])

    def test_end_sequence_is_110_then_100(self):
        for scn in self.scenarios:
            if scn.get("listenOnly"):
                continue
            self.assertEqual([s["status"] for s in scn["endSequence"]], [110, 100])
            self.assertEqual(scn["endSequence"][0]["holdS"], 3)

    def test_rehearsal_has_route_then_selection_then_end(self):
        rows = scenario.rehearse(self.by_id["H0"])
        kinds = [r["kind"] for r in rows]
        self.assertIn("route", kinds)
        self.assertLess(kinds.index("route"), kinds.index("pathSelected"))
        self.assertEqual(kinds[-1], "NavigationStatus(end)")
        self.assertEqual(rows[-1]["detail"]["status"], 100)  # H0: no host NaviSt rows
        self.assertEqual(rows[-2]["detail"]["status"], 110)

    def test_rehearsal_h6_navi_st_follows_the_stock_order(self):
        rows = scenario.rehearse(self.by_id["H6"])
        kinds = [r["kind"] for r in rows]
        self.assertEqual(kinds[0], "naviSt.read(host)")
        self.assertEqual(kinds.index("naviSt.write(host)") + 1, kinds.index("route"))
        self.assertEqual(kinds[-2:], ["naviSt.restore(host)", "naviSt.verify(host)"])
        self.assertLess(max(i for i, k in enumerate(kinds) if k == "NavigationStatus(end)"),
                        kinds.index("naviSt.restore(host)"))

    def test_write_scenarios_emits_payload_and_drops_structure(self):
        with tempfile.TemporaryDirectory() as tmp:
            written = scenario.write_scenarios(tmp)
            self.assertEqual(len(written), len(self.scenarios))
            h0 = json.loads((Path(tmp) / "scenario-H0.json").read_text())
            self.assertIn("payloadJson", h0["route"])
            self.assertNotIn("path", h0["route"])
            self.assertEqual(h0["route"]["pathIdToken"], scenario.PATH_ID_TOKEN)

    def test_listen_has_no_publication_or_request_plan(self):
        listen = self.by_id["LISTEN"]
        self.assertTrue(listen["listenOnly"])
        self.assertEqual(listen["durationS"], 120)
        self.assertFalse(listen["oddRequest"])
        self.assertIsNone(listen["naviSt"])
        self.assertIsNone(listen["instrumentNaviStatus"])
        self.assertNotIn("route", listen)
        self.assertNotIn("endSequence", listen)
        self.assertEqual(scenario.rehearse(listen), [])
        with tempfile.TemporaryDirectory() as tmp:
            scenario.write_scenarios(tmp)
            emitted = json.loads((Path(tmp) / "scenario-LISTEN.json").read_text())
            self.assertEqual(emitted, listen)


if __name__ == "__main__":
    unittest.main()
