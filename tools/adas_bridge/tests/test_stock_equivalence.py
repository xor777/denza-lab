"""Java libraries/adas-link and its Python oracle tools/adas_bridge/stock.py agree byte for byte.

Compiles the library (plain-Java sources, no Android) and its test tools with the local JDK into a
temporary directory; skipped only when no JDK is available. Golden: the car's own stock route
capture (git-ignored corpus) is reproduced from the PathInfo it describes.
"""
import json
import os
from pathlib import Path
import random
import shutil
import subprocess
import tempfile
import unittest

from tools.adas_bridge import stock

ROOT = Path(__file__).resolve().parents[3]
LIB = ROOT / "libraries/adas-link/src"
ROUTES = ["route-final-m4", "route-final-city", "highway-02", "route-01"]
GOLDEN = ROOT / "captures/stock-map-car-lanes-20261023/reports/route-wire"


def jdk():
    home = os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home")
    javac = Path(home) / "bin/javac"
    if javac.exists():
        return Path(home) / "bin"
    found = shutil.which("javac")
    return Path(found).parent if found else None


JDK = jdk()

HELPER = r"""
import dev.denza.adaslink.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
public final class OracleHelper {
    public static void main(String[] a) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(a[0]), StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(new FileOutputStream(a[1]), true, "UTF-8");
        String line;
        while ((line = in.readLine()) != null) {
            String[] f = line.split(" ");
            switch (f[0]) {
                case "d": out.println(Double.toString(Double.longBitsToDouble(Long.parseLong(f[1])))); break;
                case "f": out.println(Float.toString(Float.intBitsToFloat(Integer.parseInt(f[1])))); break;
                case "pos": out.println(Pb.hex(StockMessages.vehicleLocation(d(f[1]), d(f[2]), d(f[3]), d(f[4]), i(f[5]), i(f[6]), i(f[7]),
                        Long.parseLong(f[8]), i(f[9]), i(f[10]), i(f[11]), i(f[12]), i(f[13])))); break;
                case "traffic": out.println(Pb.hex(StockMessages.trafficInfo(i(f[1]), i(f[2]), i(f[3]), i(f[4]), i(f[5]), d(f[6]), d(f[7]),
                        i(f[8]), i(f[9]), i(f[10]), d(f[11]), d(f[12]), d(f[13]), d(f[14]), i(f[15]), f[16].equals("1"), i(f[17]), i(f[18]), i(f[19])))); break;
                case "status": out.println(Pb.hex(StockMessages.navigationStatus(i(f[1])))); break;
                case "region": out.println(Pb.hex(StockMessages.region(i(f[1]), Long.parseLong(f[2]), i(f[3]), i(f[4]), i(f[5])))); break;
                case "tunnel": out.println(Pb.hex(StockMessages.tunnel(i(f[1]), i(f[2]), d(f[3]), d(f[4]), d(f[5]), d(f[6])))); break;
                case "light": out.println(Pb.hex(StockMessages.trafficLight(i(f[1]), d(f[2]), d(f[3]), i(f[4]), Long.parseLong(f[5]), Long.parseLong(f[6]), i(f[7]), i(f[8])))); break;
                case "tidal": out.println(Pb.hex(StockMessages.tidalLane(i(f[1]), i(f[2])))); break;
                case "action": out.println(Pb.hex(StockMessages.actionAndCamera(i(f[1]), i(f[2]), i(f[3]), i(f[4]), i(f[5]), i(f[6])))); break;
                case "speed": out.println(Pb.hex(StockMessages.speedIntersection(i(f[1]), i(f[2]), i(f[3]), i(f[4]), i(f[5]), i(f[6]), f.length > 7 ? f[7] : ""))); break;
                case "odd": out.println(Pb.hex(StockMessages.noOddRegionDistance(i(f[1])))); break;
                case "general": out.println(Pb.hex(StockMessages.generalNavigation(i(f[1]), i(f[2])))); break;
                case "select": out.println(Pb.hex(StockMessages.pathSelected(Long.parseLong(f[1])))); break;
                case "confirm": out.println(Pb.hex(StockMessages.pathSelectConfirm(Long.parseLong(f[1]), i(f[2])))); break;
                case "route": out.println(Pb.hex(StockMessages.route(f[1], i(f[2])))); break;
                default: throw new IllegalArgumentException(f[0]);
            }
        }
    }
    static double d(String s) { return Double.longBitsToDouble(Long.parseLong(s)); }
    static int i(String s) { return Integer.parseInt(s); }
}
"""


TRACE_DEMO = r"""
import dev.denza.adaslink.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
public final class TraceDemo {
    public static void main(String[] a) throws Exception {
        @SuppressWarnings("unchecked") Map<String, Object> snap = (Map<String, Object>) Json.parse(new String(Files.readAllBytes(Paths.get(a[0])), StandardCharsets.UTF_8));
        YandexRoute route = YandexRoute.fromSnapshot(snap);
        try (Writer out = new OutputStreamWriter(new FileOutputStream(a[1]), StandardCharsets.UTF_8)) {
            ManualScheduler clock = new ManualScheduler(1_000_000);
            StockFeed.Config config = new StockFeed.Config();
            config.emulatedAckDelayMs = 110;
            DryRunRecorder recorder = new DryRunRecorder(out, false);
            StockFeed feed = new StockFeed(clock, recorder, config);
            NavigatorDriver driver = new NavigatorDriver(feed, config.frame, clock);
            feed.start(true, true, true);
            clock.advanceTo(1_003_000);
            long stamp = 1;
            double s = 1.0;
            for (int i = 0; i < 60; i++) {
                NavigatorDriver.Observation o = new NavigatorDriver.Observation();
                o.monotonicMs = 1_004_000 + i * 1000L;
                o.mode = i >= 30 && i < 35 ? "rerouting" : "guidance";
                o.routeId = route.routeId;
                o.route = i == 0 ? route : null;
                o.positionOnRoute = !o.mode.equals("rerouting");
                o.scalar = s;
                o.timeToFinishS = 2000 - i;
                o.locationClass = "FINE";
                double[] p = RouteConverter.wgs(route, s);
                o.lon = p[0]; o.lat = p[1]; o.speedMps = i % 20 == 19 ? 0 : 12; o.heading = 180;
                o.stamp = stamp++; o.fixAgeMs = 30;
                clock.advanceTo(o.monotonicMs);
                driver.observe(o);
                clock.drain();
                s += 0.4;
            }
            NavigatorDriver.Observation end = new NavigatorDriver.Observation();
            end.monotonicMs = 1_065_000;
            end.finished = true;
            clock.advanceTo(end.monotonicMs);
            driver.observe(end);
            clock.advanceTo(1_070_000);
        }
    }
}
"""


def bits(x):
    import struct
    return str(struct.unpack("<q", struct.pack("<d", x))[0])


@unittest.skipIf(JDK is None, "no JDK: the Java side of the equivalence cannot be built here")
class StockEquivalenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix="adas-link-oracle-"))
        sources = [p for p in sorted((LIB / "main/java").rglob("*.java")) if "import android." not in p.read_text()]
        # The JUnit oracles (oracle/) need Gson, protobuf-java and JUnit: Gradle runs them, not this test.
        sources += [p for p in sorted((LIB / "test/java").rglob("*.java")) if p.parent.name != "oracle"]
        (cls.tmp / "OracleHelper.java").write_text(HELPER)
        (cls.tmp / "TraceDemo.java").write_text(TRACE_DEMO)
        sources += [cls.tmp / "OracleHelper.java", cls.tmp / "TraceDemo.java"]
        subprocess.run([str(JDK / "javac"), "--release", "8", "-encoding", "UTF-8", "-nowarn", "-Xlint:-options",
                        "-d", str(cls.tmp / "cls")] + [str(p) for p in sources], check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def java(self, *args):
        return subprocess.run([str(JDK / "java"), "-cp", str(self.tmp / "cls")] + [str(a) for a in args],
                              check=True, capture_output=True, cwd=ROOT).stdout

    def helper(self, lines):
        source, target = self.tmp / "in.txt", self.tmp / "out.txt"
        source.write_text("\n".join(lines) + "\n", encoding="utf-8")
        self.java("OracleHelper", source, target)
        return target.read_text(encoding="utf-8").splitlines()

    def test_recorded_routes_message_send_byte_for_byte(self):
        missing = [r for r in ROUTES if not (ROOT / f"captures/yandex-adas-prototype-20261023/{r}/denza-hud-native-route.json").exists()]
        if missing:
            self.skipTest("untracked recorded routes unavailable: " + ", ".join(missing))
        for name in ROUTES:
            for frame in ("native-gcj-unrestricted-candidate", "wgs84"):
                with self.subTest(route=name, frame=frame):
                    source = ROOT / f"captures/yandex-adas-prototype-20261023/{name}/denza-hud-native-route.json"
                    out = self.tmp / f"{name}.{frame}.json"
                    self.java("dev.denza.adaslink.ConvertTool", source, out, "wgs84" if frame == "wgs84" else "gcj", 100002)
                    converted = stock.convert(stock.route_from_snapshot(json.loads(source.read_text())), frame)
                    converted["path"]["pathId"] = 100002
                    python = stock.message_send(converted["path"], 1)
                    self.assertEqual(out.read_bytes().decode("utf-8"), python)

    def test_java_dry_run_trace_decodes_and_follows_the_stock_lifecycle(self):
        from tools.adas_bridge import trace_check
        source = ROOT / "captures/yandex-adas-prototype-20261023/route-final-m4/denza-hud-native-route.json"
        if not source.exists():
            self.skipTest("untracked recorded route unavailable")
        trace = self.tmp / "trace.jsonl"
        self.java("TraceDemo", source, trace)
        report, rows = trace_check.check(trace_check.read_trace(trace), interleave=True, stock_phase_ms=693)
        self.assertEqual(report["problems"], [])
        self.assertEqual(report["counts"]["naviSDRoute"], 1)
        self.assertEqual(report["counts"]["pathSelected"], 1)
        self.assertEqual(report["cadence"]["NavigationStatus"]["medianIntervalMs"], 1000)
        self.assertEqual(report["cadence"]["sdVehicleLocation"]["medianIntervalMs"], 1000)
        states = [(r["state"], r["count"]) for r in report["statusRuns"]]
        self.assertEqual([s for s, _ in states], [100, 101, 110, 100])       # reroute keeps 101
        self.assertEqual(dict(states)[110], 3)
        self.assertEqual([p["pathId"] for p in report["positionPaths"]], [100002.0, -1.0])
        self.assertGreater(report["interleave"]["secondsBoth101And100"], 50)
        # A mutated trace is caught: the recorder's decoded no longer matches the bytes.
        records = trace_check.read_trace(trace)
        fire = next(r for r in records if r.get("name") == "sdVehicleLocation")
        fire["payloadHex"] = fire["payloadHex"][:-2] + "02"
        self.assertTrue(trace_check.check(records)[0]["problems"])

    def test_java_double_and_float_formatting(self):
        rng = random.Random(7)
        values = [37.07063083333333, 55.517721944444446, 1.0, 0.001, 0.00099999, 1e7, 9999999.999, 1e-5, 123456789.0,
                  -0.5, 0.1, 100.0, 3.0e-3, 9007199254740991.0, 2.2250738585072014e-308, -0.0, 0.0]
        values += [rng.randrange(100_000_000, 200_000_000) / 3600000.0 for _ in range(3000)]
        values += [rng.uniform(-1e9, 1e9) for _ in range(500)] + [rng.uniform(-1, 1) * 10 ** rng.randint(-20, 15) for _ in range(500)]
        java = self.helper(["d " + bits(v) for v in values])
        self.assertEqual(java, [stock.java_double(v) for v in values])
        # Where JDK's FloatingDecimal is not shortest-digit the oracle refuses rather than guesses.
        for outside in (2e23, 5e-324, 1.7976931348623157e308, 2.0 ** 53):
            with self.assertRaises(stock.StockError):
                stock.java_double(outside)
        import struct
        floats = [38.0, 0.5, 1e7, 123.25, 7.0, 0.0, 16777216.0, 3.4e38] + [float(rng.randrange(0, 100000)) for _ in range(500)]
        fbits = [str(struct.unpack("<i", struct.pack("<f", v))[0]) for v in floats]
        self.assertEqual(self.helper(["f " + b for b in fbits]), [stock.java_float(v) for v in floats])

    def test_message_bytes_match_java(self):
        rng = random.Random(11)
        lines, expected = [], []
        for _ in range(300):
            v = [rng.uniform(37, 38), rng.uniform(55, 56), rng.uniform(37, 38), rng.uniform(55, 56)]
            ints = [rng.choice([0, 1, -1, 30, 2**31 - 1, rng.randint(-5, 500)]) for _ in range(9)]
            path = rng.choice([-1, 100002, 0, 123456789])
            lines.append("pos " + " ".join(bits(x) for x in v) + " " + " ".join(map(str, ints[:3])) + f" {path} " + " ".join(map(str, ints[3:8])))
            expected.append(stock.vehicle_location(*v, *ints[:3], path, *ints[3:8]).hex())
            t = [rng.choice([0, -1, 7, 255]) for _ in range(5)] + [rng.choice([0.0, 37.5])] * 2 + [rng.choice([0, 60, -1]) for _ in range(3)] \
                + [rng.choice([0.0, -0.0, 1.5])] * 4 + [rng.choice([0, 80])] + [rng.choice([0, 1])] + [rng.randint(0, 99999), rng.randint(0, 9999), rng.choice([-1, 0, 6])]
            lines.append("traffic " + " ".join(bits(x) if isinstance(x, float) else str(x) for x in t))
            expected.append(stock.traffic_info(*t[:15], bool(t[15]), *t[16:]).hex())
        for state in (100, 101, 104, 105, 106, 110):
            lines.append(f"status {state}"); expected.append(stock.navigation_status(state).hex())
        for args in ((0, 1, 0, 0, 0), (990001, 1, 990001, 990001, 3), (-1, -1, -1, -1, -1)):
            lines.append("region " + " ".join(map(str, args))); expected.append(stock.region(*args).hex())
        for args in ((0, 0, 0.0, 0.0, 0.0, 0.0), (120, 1, 37.1, 55.2, 0.0, 0.0), (0, 4, 0.0, 0.0, 37.3, 55.4)):
            lines.append("tunnel " + " ".join(str(x) if isinstance(x, int) else bits(x) for x in args)); expected.append(stock.tunnel(*args).hex())
        for args in ((1, 0.0, 0.0, 0, 0, 0, 0, 0), (2, 37.5, 55.5, 1, 1792780000, 1792780030, 2, 3)):
            lines.append("light " + " ".join(str(x) if isinstance(x, int) else bits(x) for x in args)); expected.append(stock.traffic_light(*args).hex())
        for args in ((-1, 1), (-1, 0), (250, 2)):
            lines.append("tidal " + " ".join(map(str, args))); expected.append(stock.tidal_lane(*args).hex())
        for args in ((3, 0, 0, 120, 0, -1), (15, 0, 36, 0, 7, 450)):
            lines.append("action " + " ".join(map(str, args))); expected.append(stock.action_and_camera(*args).hex())
        for args in ((0, 40, 2, 2, 0, -1), (5, 0, 1, 1, 1, 300)):
            lines.append("speed " + " ".join(map(str, args))); expected.append(stock.speed_intersection(*args, "").hex())
        lines.append("odd 2147483647"); expected.append(stock.no_odd_region_distance(2**31 - 1).hex())
        lines.append("general 0 0"); expected.append(stock.general_navigation(0, 0).hex())
        for p in (100002, -1, 2**53 + 1):
            lines.append(f"select {p}"); expected.append(stock.path_selected(p).hex())
        lines.append("confirm 100002 200"); expected.append(stock.path_select_confirm(100002, 200).hex())
        lines.append("confirm 100003 -1"); expected.append(stock.path_select_confirm(100003, -1).hex())
        lines.append('route {"x":"Москва"} 1'); expected.append(stock.route('{"x":"Москва"}', 1).hex())
        self.assertEqual(self.helper(lines), expected)


class GoldenTests(unittest.TestCase):
    def test_car_stock_route_reproduced(self):
        for capture in ("first-full", "resend-full"):
            path = GOLDEN / capture / "route-01.someip.bin"
            if not path.exists():
                self.skipTest("untracked car golden unavailable")
            with self.subTest(capture=capture):
                data = path.read_bytes()
                header, wire = stock.parse_someip_route(data)
                envelope = json.loads(wire)
                parsed, route_type = stock.path_from_payload(envelope["payload"])
                payload = stock.message_send(parsed, route_type, "EN")
                self.assertEqual(payload, envelope["payload"])
                details = stock.chunks(payload, 1, envelope["time"])
                self.assertEqual(details, [wire])
                self.assertEqual(stock.someip_frame(header, details[0], 1), data)
                binder = stock.route(details[0], 1)
                inner = stock.NAVI_SD_ROUTE_NOTIFY.decode(binder)["naviSDRouteStruct"]
                self.assertEqual(inner, {"navigationSDLink2Num": 1, "navigationSDLink2": wire})
                for segment in parsed["segments"]:
                    self.assertEqual(segment["length"], sum(l["length"] for l in segment["links"]))

    def test_chunking_follows_stock_arithmetic(self):
        text = "ab" * 300000
        detail = [json.loads(c) for c in stock.chunks(text, 1, 5)]
        self.assertEqual([d["length"] for d in detail], [2, 2])
        self.assertEqual(len(detail[0]["payload"]), 524288)
        self.assertEqual(len(stock.chunks("a" * (524288 - 25), 1, 5)), 1)
        with self.assertRaises(IndexError):
            stock.chunks("a" * (524264 * 2 + 1), 1, 5)
        # Java counts UTF-16 units: an astral character is two.
        self.assertEqual(stock.java_length("😎"), 2)


if __name__ == "__main__":
    unittest.main()
