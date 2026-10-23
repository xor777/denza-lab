"""The Yandex route on our OSM roads (tools/adas_bridge/roads.py, road_store.py) and its Java mirror.

Synthetic networks in the identity frame near (0, 0) - no real place - each built so that a wrong
matcher gives a different answer: junction and attribute cuts on a matched road, a route that runs
off the network, a divided road whose nearer carriageway runs the other way, a parallel service road
nearer to the route than the road it is on. The Java RoadStore + RoadMatcher + RouteConverter give
the same MessageSend bytes and the same match to the bit (ConvertTool), when a JDK is present.
"""
import math
import shutil
import struct
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.adas_bridge import roads, stock
from tools.adas_bridge.road_store import encode

LAT0 = 0.01
M_LAT = 6371008.8 * math.pi / 180
M_LON = M_LAT * math.cos(math.radians(LAT0))


def deg(x, y):
    """Synthetic planar metres -> degrees near (0, LAT0)."""
    return (0.01 + x / M_LON, LAT0 + y / M_LAT)


def attrs(road_class=9, formway=15, link_type=0, lanes=1, forward=True, backward=True, ownership=0, speed=30, legal=(0, 0)):
    flags = int(forward) | int(backward) << 1 | (0 if forward and backward else 1) << 2 | ownership << 3
    return dict(roadClass=road_class, formway=formway, linkType=link_type, laneNum=lanes, flags=flags, speed=speed,
                legalForward=legal[0], legalBackward=legal[1])


def frag(a, b, points, name=None, **kw):
    return (a, b, [deg(*p) for p in points], attrs(**kw), name)


def snapshot(points, route_id="synthetic"):
    edges = len(points) - 1
    return {"schema": "denza-yandex-driving-route-v1", "coordinateFrame": "wgs84", "pointOrder": "lon,lat",
            "routeId": route_id, "points": [list(deg(*p)) for p in points],
            "sections": [{"geometry": {"begin": {"segmentIndex": 0, "segmentPosition": 0.0},
                                       "end": {"segmentIndex": edges - 1, "segmentPosition": 1.0}},
                          "metadata": {"annotation": {"action": "STRAIGHT", "toponym": "Yandex name"}}}],
            "trafficLights": [], "tunnels": [], "highways": [], "laneSigns": []}


def convert(network, points):
    store = roads.RoadStore(encode(network, frame="wgs84")[0])
    route = stock.route_from_snapshot(snapshot(points))
    return stock.convert(route, "wgs84", store), store


def links(converted):
    out, begin = [], converted["linkBeginM"]
    index = 0
    for segment in converted["path"]["segments"]:
        for link in segment["links"]:
            out.append(dict(link, beginM=begin[index], endM=converted["linkEndM"][index]))
            index += 1
    return out


MAIN = [  # a two-way main road west to east, side roads at 300 and 600, four lanes from 800
    frag("w", "j300", [(0, 0), (150, 0), (300, 0)], "Main", road_class=7, formway=1, lanes=2),
    frag("j300", "j600", [(300, 0), (600, 0)], "Main", road_class=7, formway=1, lanes=2),
    frag("j600", "c800", [(600, 0), (800, 0)], "Main", road_class=7, formway=1, lanes=2),
    frag("c800", "e", [(800, 0), (1000, 0)], "Main", road_class=7, formway=1, lanes=4, legal=(60, 40)),
    frag("j300", "s1", [(300, 0), (300, 150)], "Side", road_class=9),
    frag("s2", "j600", [(600, -150), (600, 0)], "Side", road_class=9),
]


class MatchedCorridorTests(unittest.TestCase):
    def test_links_end_at_junctions_and_attribute_changes_and_carry_the_roads(self):
        converted, _ = convert(MAIN, [(0, 3), (500, 3), (1000, 3)])
        rows = links(converted)
        ends = [round(r["endM"]) for r in rows]
        self.assertEqual(ends, [300, 600, 800, 1000])
        self.assertEqual([(r["roadClass"], r["formway"], r["laneNum"], r["direction"], r["ownership"], r["roadName"])
                          for r in rows], [(7, 1, 2, 0, 0, "Main")] * 3 + [(7, 1, 4, 0, 0, "Main")])
        self.assertEqual(converted["linkSpeedKmh"], [30.0] * 4)
        self.assertEqual(converted["linkLegalKmh"], [0, 0, 0, 60])          # eastbound: the forward sign

    def test_the_legal_limit_is_the_one_for_the_direction_travelled(self):
        converted, _ = convert(MAIN, [(1000, -3), (0, -3)])
        self.assertEqual(converted["linkLegalKmh"], [40, 0, 0, 0])
        self.assertAlmostEqual(converted["roadMatch"]["matchedM"], converted["totalM"], delta=1e-6)

    def test_the_geometry_stays_yandex(self):
        points = [(0, 3), (500, 3), (1000, 3)]
        with_roads, _ = convert(MAIN, points)
        alone = stock.convert(stock.route_from_snapshot(snapshot(points)), "wgs84")
        def vertices(c):
            out = []
            for s in c["path"]["segments"]:
                for link in s["links"]:
                    for p in zip(link["lon"], link["lat"]):
                        if not out or out[-1] != p:
                            out.append(p)
            return out
        a, b = vertices(with_roads), vertices(alone)
        self.assertEqual(a[0], b[0])
        self.assertEqual(a[-1], b[-1])
        self.assertTrue(set(b) <= set(a))                       # every Yandex vertex kept
        self.assertEqual(len(a) - len(b), 3)                     # plus the three cut points, on the line
        lat = {p[1] for p in a}
        self.assertEqual(len(lat), 1)

    def test_a_route_off_the_network_keeps_the_stock_unknowns_there(self):
        converted, _ = convert(MAIN, [(0, 3), (1000, 3), (1400, 3)])
        rows = links(converted)
        self.assertEqual([round(r["endM"]) for r in rows], [300, 600, 800, 1400])
        last = rows[-1]
        # The last link straddles the network's end; its middle (1100 m) is off it: Yandex-only values.
        self.assertEqual((last["roadClass"], last["formway"], last["ownership"], last["laneNum"], last["roadName"]),
                         (-1, -1, -1, 0, "Yandex name"))
        self.assertTrue(math.isnan(converted["linkSpeedKmh"][-1]))
        self.assertLess(converted["roadMatch"]["matchedM"], 1000 + roads.STEP_M + 1)   # at most one sample past the end
        self.assertGreater(converted["roadMatch"]["matchedM"], 990)

    def test_a_route_entirely_off_the_network_is_unchanged(self):
        points = [(0, 300), (1000, 300)]
        converted, _ = convert(MAIN, points)
        alone = stock.convert(stock.route_from_snapshot(snapshot(points)), "wgs84")
        self.assertEqual(converted["contentKey"], alone["contentKey"])
        self.assertEqual(converted["roadMatch"]["matchedM"], 0.0)


DIVIDED = [  # eastbound carriageway at y = +8 (class 0), westbound at y = -8 (class 6), one way each
    frag("e0", "e1", [(0, 8), (500, 8)], "East", road_class=0, formway=1, lanes=3, backward=False),
    frag("e1", "e2", [(500, 8), (1000, 8)], "East", road_class=0, formway=1, lanes=3, backward=False),
    frag("w2", "w1", [(1000, -8), (500, -8)], "West", road_class=6, formway=1, lanes=2, backward=False),
    frag("w1", "w0", [(500, -8), (0, -8)], "West", road_class=6, formway=1, lanes=2, backward=False),
]


class DividedRoadTests(unittest.TestCase):
    def test_the_carriageway_in_the_direction_of_travel_wins_over_the_nearer_one(self):
        converted, _ = convert(DIVIDED, [(0, -2), (1000, -2)])    # 6 m from West, 10 m from East
        rows = links(converted)
        self.assertEqual({(r["roadClass"], r["laneNum"], r["direction"], r["roadName"]) for r in rows}, {(0, 3, 1, "East")})

    def test_the_other_way_takes_the_other_carriageway(self):
        converted, _ = convert(DIVIDED, [(1000, 2), (0, 2)])      # westbound, nearer to East
        rows = links(converted)
        self.assertEqual({(r["roadClass"], r["laneNum"], r["roadName"]) for r in rows}, {(6, 2, "West")})


PARALLEL = [  # Main west to east, a service road 12 m north of it from 200 to 800 joined only at its ends
    frag("w", "j200", [(0, 0), (200, 0)], "Main", road_class=7, formway=1, lanes=2),
    frag("j200", "m300", [(200, 0), (300, 0)], "Main", road_class=7, formway=1, lanes=2),
    frag("m300", "m600", [(300, 0), (600, 0)], "Main", road_class=7, formway=1, lanes=2),
    frag("m600", "j800", [(600, 0), (800, 0)], "Main", road_class=7, formway=1, lanes=2),
    frag("j800", "e", [(800, 0), (1000, 0)], "Main", road_class=7, formway=1, lanes=4),
    frag("j200", "p200", [(200, 0), (200, 12)], "Service", road_class=9, ownership=1),
    frag("p200", "p800", [(200, 12), (800, 12)], "Service", road_class=9, ownership=1),
    frag("p800", "j800", [(800, 12), (800, 0)], "Service", road_class=9, ownership=1),
]


class ParallelRoadTests(unittest.TestCase):
    def test_a_parallel_service_road_nearer_the_line_does_not_capture_the_route(self):
        # Yandex's line drifts 8 m north over 350..650 m: 4 m from the service road, 8 m from Main.
        converted, _ = convert(PARALLEL, [(0, 0), (300, 0), (350, 8), (650, 8), (700, 0), (1000, 0)])
        rows = links(converted)
        self.assertTrue(rows)
        self.assertEqual({r["roadName"] for r in rows}, {"Main"})
        self.assertEqual({r["ownership"] for r in rows}, {0})

    def test_a_route_that_does_take_the_service_road_gets_it(self):
        converted, _ = convert(PARALLEL, [(0, 0), (200, 0), (200, 12), (800, 12), (800, 0), (1000, 0)])
        names = [(r["roadName"], r["ownership"]) for r in links(converted) if 250 < (r["beginM"] + r["endM"]) / 2 < 750]
        self.assertTrue(names)
        self.assertEqual(set(names), {("Service", 1)})


LOOP = [  # Main west to east and a 40 m service loop 12 m north of it between 400 and 440
    frag("w", "j400", [(0, 0), (400, 0)], "Main", road_class=7, formway=1, lanes=2),
    frag("j400", "j440", [(400, 0), (440, 0)], "Main", road_class=7, formway=1, lanes=2),
    frag("j440", "e", [(440, 0), (1000, 0)], "Main", road_class=7, formway=1, lanes=2),
    frag("j400", "l400", [(400, 0), (400, 12)], "Loop", road_class=9, ownership=1),
    frag("l400", "l440", [(400, 12), (440, 12)], "Loop", road_class=9, ownership=1),
    frag("l440", "j440", [(440, 12), (440, 0)], "Loop", road_class=9, ownership=1),
]

STUB = [  # a 20 m road on its own, and a road far away
    frag("q0", "q1", [(0, 2), (20, 2)], "Stub", road_class=9),
    frag("f0", "f1", [(0, 500), (1000, 500)], "Far", road_class=9),
]


class ShortRunTests(unittest.TestCase):
    def test_a_short_loop_nearer_the_line_costs_more_than_it_saves(self):
        # Over 380..460 m the line runs 8 m north: 4 m from the loop, 8 m from Main; the loop is
        # reachable, but its 12 m legs make every step through it longer than the route's.
        converted, _ = convert(LOOP, [(0, 0), (370, 0), (380, 8), (460, 8), (470, 0), (1000, 0)])
        self.assertEqual({r["roadName"] for r in links(converted)}, {"Main"})

    def test_a_route_that_starts_on_a_short_isolated_road_is_not_matched_there(self):
        points = [(0, 0), (400, 0)]
        converted, _ = convert(STUB, points)
        alone = stock.convert(stock.route_from_snapshot(snapshot(points)), "wgs84")
        self.assertEqual(converted["roadMatch"]["matchedM"], 0.0)
        self.assertEqual(converted["contentKey"], alone["contentKey"])


class StoreTests(unittest.TestCase):
    def test_store_roundtrip(self):
        data, summary = encode(MAIN, frame="wgs84")
        store = roads.RoadStore(data)
        self.assertEqual((store.node_count, store.fragment_count, summary["junctionNodes"]), (7, 6, 2))
        f = store.fragment(3)
        self.assertEqual((f["roadClass"], f["laneNum"], f["name"], f["forward"], f["backward"]), (7, 4, "Main", True, True))
        self.assertAlmostEqual(f["cum"][-1], 200.0, delta=0.05)
        self.assertEqual(store.degree(f["a"]), 2)
        x, y = (store.node_x[0] - store.origin_x) * store.mx, (store.node_y[0] - store.origin_y) * store.my
        self.assertEqual(store.near(x, y, 5.0), [0])

    def test_a_damaged_store_is_refused(self):
        data = bytearray(encode(MAIN, frame="wgs84")[0])
        with self.assertRaises(roads.StoreError):
            roads.RoadStore(bytes(data[:-1]))
        data[0:4] = b"XXXX"
        with self.assertRaises(roads.StoreError):
            roads.RoadStore(bytes(data))


class JavaMirrorTests(unittest.TestCase):
    """RoadStore.java + RoadMatcher.java + RouteConverter.java give the oracle's bytes and match."""

    @classmethod
    def setUpClass(cls):
        from tools.adas_bridge.tests.test_stock_equivalence import JDK, LIB
        if JDK is None:
            raise unittest.SkipTest("no JDK")
        cls.tmp = Path(tempfile.mkdtemp(prefix="adas-roads-"))
        sources = [p for p in sorted((LIB / "main/java").rglob("*.java")) if p.name not in ("AndroidScheduler.java", "SomeIpPublisher.java")]
        sources += [p for p in sorted((LIB / "test/java").rglob("*.java")) if p.parent.name != "oracle"]
        subprocess.run([str(JDK / "javac"), "--release", "8", "-encoding", "UTF-8", "-nowarn", "-Xlint:-options",
                        "-d", str(cls.tmp / "classes"), *map(str, sources)], check=True, capture_output=True)
        cls.java = str(JDK / "java")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def check(self, network, points, name):
        import json
        data = encode(network, frame="wgs84")[0]
        (self.tmp / (name + ".bin")).write_bytes(data)
        (self.tmp / (name + ".json")).write_text(json.dumps(snapshot(points)))
        subprocess.run([self.java, "-cp", str(self.tmp / "classes"), "dev.denza.adaslink.ConvertTool",
                        str(self.tmp / (name + ".json")), str(self.tmp / (name + ".out.json")), "wgs84", "100002",
                        str(self.tmp / (name + ".bin")), str(self.tmp / (name + ".match.txt"))], check=True, capture_output=True)
        expected_json, expected_match = oracle_outputs(snapshot(points), "wgs84", roads.RoadStore(data))
        self.assertEqual((self.tmp / (name + ".out.json")).read_text(encoding="utf-8"), expected_json)
        self.assertEqual((self.tmp / (name + ".match.txt")).read_text(encoding="utf-8").splitlines(), expected_match)

    def test_every_synthetic_case(self):
        self.check(MAIN, [(0, 3), (500, 3), (1000, 3)], "main")
        self.check(MAIN, [(1000, -3), (0, -3)], "main-west")
        self.check(MAIN, [(0, 3), (1000, 3), (1400, 3)], "off")
        self.check(DIVIDED, [(0, -2), (1000, -2)], "divided")
        self.check(DIVIDED, [(1000, 2), (0, 2)], "divided-west")
        self.check(PARALLEL, [(0, 0), (300, 0), (350, 8), (650, 8), (700, 0), (1000, 0)], "parallel")
        self.check(PARALLEL, [(0, 0), (200, 0), (200, 12), (800, 12), (800, 0), (1000, 0)], "service")
        self.check(LOOP, [(0, 0), (370, 0), (380, 8), (460, 8), (470, 0), (1000, 0)], "loop")
        self.check(STUB, [(0, 0), (400, 0)], "stub")


def bits(x):
    return "%x" % struct.unpack(">Q", struct.pack(">d", x))[0]


def oracle_outputs(snap, frame, store, path_id=100002):
    """What ConvertTool writes: the MessageSend JSON and the match lines."""
    route = stock.route_from_snapshot(snap)
    converted = stock.convert(route, frame, store)
    converted["path"]["pathId"] = path_id
    payload = stock.message_send(converted["path"], 1)
    lines = []
    match = converted["roadMatch"]
    if match is not None:
        m = roads.match(route, converted["cumulative"], store)
        lines.append("stats %d %d %d %d %s" % (m.stats["samples"], m.stats["matchedSamples"], m.stats["runs"],
                                               m.stats["keptRuns"], bits(m.stats["matchedM"])))
        lines += ["cut " + bits(s) for s in m.cuts]
        lines += ["piece %s %s %d %d" % (bits(p[0]), bits(p[1]), p[2], int(p[3])) for p in m.pieces]
    lines += ["speed " + bits(s) for s in converted["linkSpeedKmh"]]
    lines += ["legal %d" % v for v in converted["linkLegalKmh"]]
    return payload, lines


if __name__ == "__main__":
    unittest.main()
