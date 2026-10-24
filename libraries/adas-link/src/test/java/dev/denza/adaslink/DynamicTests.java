package dev.denza.adaslink;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.denza.adaslink.AdasLinkTest.check;
import static dev.denza.adaslink.AdasLinkTest.equal;

/**
 * The feed against the stock state machine: lifecycle, timing, ACK handling, gates, and the
 * review's adversarial sequences (captures/adas-bridge-review/dynamics/sequences).
 */
final class DynamicTests {
    static final String M4 = "captures/yandex-adas-prototype-20261023/route-final-m4/denza-hud-native-route.json";
    static final String SEQ = "captures/adas-bridge-review/dynamics/sequences/";

    static final class Rig {
        final ManualScheduler clock;
        final DryRunRecorder recorder = new DryRunRecorder(null, true);
        final StockFeed feed;
        final NavigatorDriver driver;
        Rig(long start, StockFeed.Config config, boolean dnp, boolean noSleep) {
            clock = new ManualScheduler(start);
            feed = new StockFeed(clock, recorder, config);
            driver = new NavigatorDriver(feed, config.frame, clock);
            feed.start(dnp, dnp, noSleep);
            clock.drain();
        }
        Rig(long start) { this(start, config(), true, true); }
        List<Map<String, Object>> fires(long topic) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : recorder.records()) if ("fire".equals(r.get("kind")) && ((Number) r.get("topic")).longValue() == topic) out.add(r);
            return out;
        }
        List<Map<String, Object>> fires() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : recorder.records()) if ("fire".equals(r.get("kind"))) out.add(r);
            return out;
        }
        void observe(NavigatorDriver.Observation o) {
            clock.advanceTo(o.monotonicMs);
            driver.observe(o);
            clock.drain();
        }
    }

    static StockFeed.Config config() {
        StockFeed.Config c = new StockFeed.Config();
        c.emulatedAckDelayMs = 110;
        return c;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> inner(Map<String, Object> fire) {
        Map<String, Object> decoded = (Map<String, Object>) fire.get("decoded");
        return (Map<String, Object>) decoded.values().iterator().next();
    }

    static long statusOf(Map<String, Object> fire) {
        Object v = inner(fire).get("navigationStatus");
        return v == null ? 0 : ((Number) v).longValue();
    }

    static double pathOf(Map<String, Object> fire) {
        Object v = inner(fire).get("curSDRouteID");
        return v == null ? 0 : ((Number) v).doubleValue();
    }

    static long field(Map<String, Object> fire, String name) {
        Object v = inner(fire).get(name);
        return v == null ? 0 : ((Number) v).longValue();
    }

    static NavigatorDriver.Observation guidance(long t, String routeId, YandexRoute route, double scalar, double speed, long stamp) {
        NavigatorDriver.Observation o = new NavigatorDriver.Observation();
        o.monotonicMs = t;
        o.mode = "guidance";
        o.routeId = routeId;
        o.route = route;
        o.positionOnRoute = true;
        o.scalar = scalar;
        o.timeToFinishS = 1000;
        o.locationClass = "FINE";
        double[] p = RouteConverter.wgs(route != null ? route : lastRoute, scalar);
        o.lon = p[0]; o.lat = p[1];
        o.speedMps = speed;
        o.heading = 90;
        o.stamp = stamp;
        o.fixAgeMs = 20;
        return o;
    }

    static YandexRoute lastRoute;

    static void run(File root) throws Exception {
        YandexRoute m4 = YandexRoute.fromSnapshot(ConverterTests.read(new File(root, M4)));
        lastRoute = m4;
        lifecycle(m4);
        ackHandling(m4);
        gates(m4);
        timing(m4);
        wireBytes();
        chunking();
        sequences(root, m4);
        vanishing(m4);
        roadSpeedLimit(m4);
        TrafficLightDynamicsTest.main(new String[0]);
    }

    /** TrafficInfo.SpdLmtSpeedValue: 0 by default even where Yandex posts a limit; the posted km/h only when enabled. */
    static void roadSpeedLimit(YandexRoute m4) {
        for (boolean enabled : new boolean[] {false, true}) {
            StockFeed.Config c = config();
            c.roadSpeedLimit = enabled;
            Rig rig = new Rig(9_000_000, c, true, true);
            for (int i = 0; i < 5; i++) {
                NavigatorDriver.Observation o = guidance(9_000_100 + i * 1000L, m4.routeId, m4, 1.0 + i, 10, i + 1);
                o.speedLimitMps = 60 / 3.6;
                rig.observe(o);
            }
            List<Map<String, Object>> traffic = rig.fires(Topics.TRAFFIC_INFO);
            check(!traffic.isEmpty(), "roadSpeedLimit: TrafficInfo sent");
            equal(enabled ? 60L : 0L, field(traffic.get(traffic.size() - 1), "spdLmtSpeedValue"),
                    enabled ? "roadSpeedLimit on: the posted limit" : "roadSpeedLimit default: 0, as the stock on our roads");
        }
    }

    /** Idle 100s, route + selection, 101, positions on the path, arrival 110 x3 then 100 and pathId -1. */
    static void lifecycle(YandexRoute m4) {
        Rig rig = new Rig(1_000_000);
        rig.clock.advanceTo(1_003_500);
        List<Map<String, Object>> status = rig.fires(Topics.NAVIGATION_STATUS);
        equal(4, status.size(), "idle: NavigationStatus every second from start");
        for (Map<String, Object> s : status) equal(100L, statusOf(s), "idle: state 100");
        equal(4L, rig.recorder.records().stream().filter(r -> "startService".equals(r.get("input"))).count(), "four IVI services started");
        long t = 1_004_000, stamp = 1;
        rig.observe(guidance(t, m4.routeId, m4, 1.0, 10, stamp++));
        List<Map<String, Object>> route = rig.fires(Topics.ROUTE), select = rig.fires(Topics.PATH_SELECTED);
        equal(1, route.size(), "route sent once at guidance start");
        equal(1, select.size(), "selection sent once");
        check(rig.recorder.records().indexOf(route.get(0)) < rig.recorder.records().indexOf(select.get(0)), "route before selection");
        equal(100002L, field(select.get(0), "pathIDReq"), "first pathId is the stock's first, 100002");
        @SuppressWarnings("unchecked") String chunk = (String) inner(route.get(0)).get("navigationSDLink2");
        @SuppressWarnings("unchecked") Map<String, Object> detail = (Map<String, Object>) Json.parse(chunk);
        @SuppressWarnings("unchecked") Map<String, Object> send = (Map<String, Object>) Json.parse((String) detail.get("payload"));
        equal(1L, send.get("RouteType"), "first route RouteType 1 (Common)");
        for (int i = 1; i < 30; i++) rig.observe(guidance(t + i * 1000, m4.routeId, null, 1.0 + i * 0.3, 10, stamp++));
        List<Map<String, Object>> positions = rig.fires(Topics.SD_VEHICLE_LOCATION);
        check(positions.size() >= 28, "a position per second while moving");
        long statuses101 = rig.fires(Topics.NAVIGATION_STATUS).stream().filter(s -> statusOf(s) == 101).count();
        check(statuses101 >= 29, "101 every second while navigating");
        Map<String, Object> lastPos = positions.get(positions.size() - 1);
        equal(100002.0, pathOf(lastPos), "position carries the pathId");
        equal(4294967295L, field(lastPos, "currentRoadOwnership"), "ownership -1 on the wire as uint32 (F5)");
        equal(36L, field(lastPos, "GPSspeed"), "GPS speed in km/h");
        check(rig.fires(Topics.TRAFFIC_INFO).size() >= 28, "TrafficInfo with every position");
        equal(4294967295L, field(rig.fires(Topics.TRAFFIC_INFO).get(0), "roadClass"), "TrafficInfo roadClass -1");
        check(!rig.fires(Topics.SD_ACTION_CAMERA).isEmpty() && !rig.fires(Topics.SD_SPEED_INTERSECTION).isEmpty()
                && !rig.fires(Topics.SD_TIDAL_LANE).isEmpty(), "NaviInfo bundle sent");
        check(!rig.fires(Topics.ADV_AUTO_FUN_INFO).isEmpty() && field(rig.fires(Topics.ADV_AUTO_FUN_INFO).get(0), "noODDRegionDist") == Integer.MAX_VALUE,
                "noODDRegionDist MAX as the car sends");
        check(!rig.fires(Topics.SD_AHEAD_LANES).isEmpty(), "3 km lanes [] after the path changes");
        // Arrival.
        int before = rig.fires(Topics.NAVIGATION_STATUS).size();
        NavigatorDriver.Observation end = new NavigatorDriver.Observation();
        end.monotonicMs = t + 30_000;
        end.finished = true;
        rig.observe(end);
        rig.clock.advanceTo(t + 36_000);
        List<Map<String, Object>> after = rig.fires(Topics.NAVIGATION_STATUS).subList(before, rig.fires(Topics.NAVIGATION_STATUS).size());
        List<Long> states = new ArrayList<>();
        for (Map<String, Object> s : after) if (!states.isEmpty() || statusOf(s) != 101) states.add(statusOf(s));
        check(states.size() >= 6 && states.subList(0, 4).equals(java.util.Arrays.asList(110L, 110L, 110L, 100L)),
                "arrival: 110 three times, then 100 (" + states + ")");
        // After the stop, positions are the idle stock's: pathId -1.
        NavigatorDriver.Observation idle = new NavigatorDriver.Observation();
        idle.monotonicMs = t + 37_000; idle.locationClass = "FINE"; idle.lon = 37.7; idle.lat = 55.6; idle.speedMps = 5; idle.stamp = stamp++; idle.fixAgeMs = 10;
        rig.observe(idle);
        List<Map<String, Object>> ps = rig.fires(Topics.SD_VEHICLE_LOCATION);
        equal(-1.0, pathOf(ps.get(ps.size() - 1)), "idle position pathId -1");
        equal(4294967295L, field(ps.get(ps.size() - 1), "curStepId"), "idle segment -1");
        // DYN-13: a late sample of the finished route resurrects nothing.
        int routes = rig.fires(Topics.ROUTE).size();
        rig.observe(guidance(t + 38_000, m4.routeId, m4, 900, 5, stamp++));
        equal(routes, rig.fires(Topics.ROUTE).size(), "finished route is retired: no resend");
    }

    /** MMTPathSelected: own path confirms; foreign or failed answers resend route (RouteType 14) and selection. */
    static void ackHandling(YandexRoute m4) {
        StockFeed.Config c = config();
        c.emulatedAckDelayMs = -1;
        Rig rig = new Rig(2_000_000, c, true, true);
        rig.observe(guidance(2_000_100, m4.routeId, m4, 1.0, 10, 1));
        equal(1, rig.fires(Topics.ROUTE).size(), "ack: route once");
        rig.clock.advanceTo(2_010_000);
        equal(1, rig.fires(Topics.ROUTE).size(), "no answer: the stock waits, no timeout resend");
        rig.feed.onPathSelectConfirm(StockMessages.pathSelectConfirm(100002, 200));
        rig.clock.drain();
        equal(1, rig.fires(Topics.ROUTE).size(), "own confirm: nothing more");
        rig.feed.onPathSelectConfirm(StockMessages.pathSelectConfirm(123456, 200));
        rig.clock.drain();
        equal(2, rig.fires(Topics.ROUTE).size(), "foreign confirm: route resent");
        equal(2, rig.fires(Topics.PATH_SELECTED).size(), "foreign confirm: selection resent");
        @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) Json.parse((String) inner(rig.fires(Topics.ROUTE).get(1)).get("navigationSDLink2"));
        @SuppressWarnings("unchecked") Map<String, Object> send = (Map<String, Object>) Json.parse((String) d.get("payload"));
        equal(14L, send.get("RouteType"), "resend RouteType 14");
        rig.feed.onPathSelectConfirm(StockMessages.pathSelectConfirm(100002, 500));
        rig.clock.drain();
        equal(3, rig.fires(Topics.ROUTE).size(), "error confirm: route resent");
    }

    /** DNP off: no route; ADAS asleep: nothing dynamic; switching DNP on resends 300 ms later. */
    static void gates(YandexRoute m4) {
        Rig off = new Rig(3_000_000, config(), false, true);
        off.observe(guidance(3_000_100, m4.routeId, m4, 1.0, 10, 1));
        equal(0, off.fires(Topics.ROUTE).size(), "DNP off: no route");
        check(!off.fires(Topics.SD_VEHICLE_LOCATION).isEmpty(), "DNP off: positions still go");
        off.feed.setGates(true, true, true);
        off.clock.advanceTo(3_000_500);
        equal(1, off.fires(Topics.ROUTE).size(), "DNP switched on: route 300 ms later");
        Rig asleep = new Rig(4_000_000, config(), true, false);
        asleep.observe(guidance(4_000_100, m4.routeId, m4, 1.0, 10, 1));
        asleep.clock.advanceTo(4_005_000);
        equal(0, asleep.fires(Topics.SD_VEHICLE_LOCATION).size() + asleep.fires(Topics.NAVIGATION_STATUS).size(), "asleep: no dynamic send");
        equal(0, asleep.fires(Topics.ROUTE).size(), "asleep: the DNP switch counts as off (a = switch && noSleep), no route");
    }

    /** Throttle to one bundle a second; the stationary loop repeats without fixes. */
    static void timing(YandexRoute m4) {
        Rig rig = new Rig(5_000_000);
        long stamp = 1;
        for (int i = 0; i < 50; i++) rig.observe(guidance(5_000_100 + i * 100, m4.routeId, i == 0 ? m4 : null, 1.0 + i * 0.03, 10, stamp++));
        int n = rig.fires(Topics.SD_VEHICLE_LOCATION).size();
        check(n >= 4 && n <= 6, "10 Hz fixes: positions at most once a second (" + n + ")");
        int ni = rig.fires(Topics.SD_TIDAL_LANE).size(), odd = rig.fires(Topics.ADV_AUTO_FUN_INFO).size();
        check(ni >= 4 && ni <= 6 && odd <= 6, "10 Hz fixes: NaviInfo bundle once a second, as Amap's guidance (" + ni + "/" + odd + ")");
        // Stopped: the loop sends every second with no new fix.
        rig.observe(guidance(5_006_000, m4.routeId, null, 3.0, 0, stamp++));
        int before = rig.fires(Topics.SD_VEHICLE_LOCATION).size();
        rig.clock.advanceTo(5_010_500);
        equal(before + 4, rig.fires(Topics.SD_VEHICLE_LOCATION).size(), "stationary: a position every second without fixes");
        // Moving again cancels the loop.
        rig.observe(guidance(5_011_000, m4.routeId, null, 3.1, 10, stamp++));
        int moving = rig.fires(Topics.SD_VEHICLE_LOCATION).size();
        rig.clock.advanceTo(5_015_000);
        equal(moving, rig.fires(Topics.SD_VEHICLE_LOCATION).size(), "moving: no position without a fix");
    }

    /** Protobuf bytes as protobuf-java lite writes them (the oracle test repeats these with the real runtime). */
    static void wireBytes() {
        equal("0a079801ffffffff0f", Pb.hex(StockMessages.trafficInfo(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, 0, 0, -1)),
                "TrafficInfo roadClass -1 is five varint bytes, as uint32 (F10)");
        equal("0a022065", Pb.hex(StockMessages.navigationStatus(101)), "NavigationStatus 101");
        equal("0a0418a28d06", Pb.hex(StockMessages.pathSelected(100002)), "selection pathIDReq uint64 field 3");
        equal("0a00", Pb.hex(StockMessages.tunnel(0, 0, 0, 0, 0, 0)), "an all-default sub-message still goes out, empty");
        equal("", Pb.hex(StockMessages.incidents(new int[0], new int[0], new double[0], new double[0])), "an empty incident list is an empty message");
        byte[] pos = StockMessages.vehicleLocation(37.0, 55.0, 37.0, 55.0, 30, 31, 90, -1, -1, -1, -1, -1, -1);
        @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) Pb.SD_VEHICLE_LOCATION_NOTIFY.decode(pos).get("sdVehicleLocation");
        equal(-1.0, d.get("curSDRouteID"), "pathId -1 as the double -1.0");
        equal(4294967295L, d.get("linkOffset"), "linkOffset -1 as uint32");
        equal(55.0, d.get("locationLatitude"), "field 1 is latitude");
        equal(37.0, d.get("locationLongitude"), "field 2 is longitude");
    }

    /** n.a(): chunk count from 524264, cuts at 524288, the stock's exception past the end. */
    static void chunking() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 600_000; i++) b.append((char) ('a' + i % 26));
        List<String> two = StockRouteJson.chunks(b.toString(), 1, 7);
        equal(2, two.size(), "600000 chars: two chunks");
        @SuppressWarnings("unchecked") Map<String, Object> c0 = (Map<String, Object>) Json.parse(two.get(0));
        equal(524288, ((String) c0.get("payload")).length(), "first chunk is 524288 chars");
        equal(2L, c0.get("length"), "length is the chunk count");
        String small = b.substring(0, 524288 - 25);
        equal(1, StockRouteJson.chunks(small, 1, 7).size(), "length + 24 < 524288: one");
        equal(1, StockRouteJson.chunks(b.substring(0, 524288 - 24), 1, 7).size(), "length + 24 == 524288: the chunk loop, one chunk");
        StringBuilder big = new StringBuilder(b);
        while (big.length() < 524264 * 2 + 1) big.append('z');
        boolean threw = false;
        try { StockRouteJson.chunks(big.substring(0, 524264 * 2 + 1), 1, 7); }
        catch (StringIndexOutOfBoundsException e) { threw = true; }
        check(threw, "the stock's own substring past the end at 3 chunks of 1048529 chars");
    }

    // ---------------------------------------------------------------- review sequences

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> sequence(File root, String name) throws Exception {
        return (List<Map<String, Object>>) Json.parse(new String(Files.readAllBytes(new File(root, SEQ + name).toPath()), StandardCharsets.UTF_8));
    }

    /** Runs a sequence; returns the rig. finishedAt: index of a sample that is Navigator's ROUTE_FINISHED. */
    @SuppressWarnings("unchecked")
    static Rig replay(List<Map<String, Object>> steps, YandexRoute route, int finishedAt) {
        long start = ((Number) steps.get(0).get("nowMs")).longValue() - 5000;
        Rig rig = new Rig(start);
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            Map<String, Object> sample = (Map<String, Object>) step.get("sample");
            long now = ((Number) step.get("nowMs")).longValue();
            if (i == finishedAt) sample.put("routeStatus", "ROUTE_FINISHED");
            YandexRoute r = step.get("snapshotRouteId") != null ? route : null;
            rig.observe(NavigatorDriver.fromSample(sample, now, r));
        }
        rig.clock.advanceTo(rig.clock.now() + 1500);
        return rig;
    }

    static List<Long> statuses(Rig rig) {
        List<Long> out = new ArrayList<>();
        for (Map<String, Object> s : rig.fires(Topics.NAVIGATION_STATUS)) out.add(statusOf(s));
        return out;
    }

    /** Navigation progress never moves back on one path: segment, link and remaining distances. */
    static void monotonic(Rig rig, String name) {
        long seg = -1, link = -1;
        double path = Double.NaN;
        long remain = Long.MAX_VALUE;
        boolean ok = true;
        for (Map<String, Object> p : rig.fires(Topics.SD_VEHICLE_LOCATION)) {
            double id = pathOf(p);
            long s = (int) field(p, "curStepId"), l = (int) field(p, "curLinkId");
            long r = field(p, "distanceToNextStep");
            if (id != path) { path = id; seg = s; link = l; remain = r; continue; }
            if (s < seg || (s == seg && l < link) || (s == seg && r > remain)) ok = false;
            seg = s; link = l; remain = r;
        }
        check(ok, name + ": progress never moves back on one pathId (DYN-01)");
    }

    static void sequences(File root, YandexRoute m4) throws Exception {
        // S1: two one-sample fix gaps. Stock: 101 throughout, one route publication.
        Rig s1 = replay(sequence(root, "S1_transient_fix_gap.json"), m4, -1);
        check(!statuses(s1).contains(110L), "S1: a fix gap never says navigation ended (DYN-02)");
        equal(1, s1.fires(Topics.ROUTE).size(), "S1: route sent once (DYN-02)");
        monotonic(s1, "S1");
        // S2: a duplicate read after a writer stall is not a new fix and changes nothing.
        Rig s2 = replay(sequence(root, "S2_duplicate_read_after_writer_stall.json"), m4, -1);
        check(!statuses(s2).contains(110L), "S2: no 110 on a duplicate read");
        equal(1, s2.fires(Topics.ROUTE).size(), "S2: no resend on a duplicate read");
        // S3: backward jitter across a link boundary.
        Rig s3 = replay(sequence(root, "S3_backward_jitter_across_link_boundary.json"), m4, -1);
        monotonic(s3, "S3");
        check(s3.recorder.records().stream().anyMatch(r -> "progressBehind".equals(r.get("input"))), "S3: backward samples are held, not sent");
        // S4: reroute gap with the same route coming back: 101 throughout, no end, frozen position.
        Rig s4 = replay(sequence(root, "S4_reroute_gap.json"), m4, -1);
        List<Long> st4 = statuses(s4);
        check(!st4.contains(110L) && st4.subList(st4.indexOf(101L), st4.size()).stream().allMatch(v -> v == 101L), "S4: reroute keeps 101, never 110/100 (DYN-04): " + st4);
        equal(1, s4.fires(Topics.ROUTE).size(), "S4: the same route back is not resent");
        monotonic(s4, "S4");
        check(s4.recorder.records().stream().filter(r -> "reroute".equals(r.get("input"))).count() == 1, "S4: one yaw reroute event");
        // S5: same routeId, refreshed snapshot: one Yandex route is one path for its life, whatever the
        // refreshed table (the 2026-10-24 drive republished one route as 15 paths); a new path needs a new route.
        Rig s5 = new Rig(6_000_000);
        s5.observe(guidance(6_000_100, m4.routeId, m4, 1.0, 10, 1));
        s5.observe(guidance(6_001_100, m4.routeId, m4, 1.2, 10, 2));
        equal(1, s5.fires(Topics.ROUTE).size(), "S5: refreshed identical route, no resend (DYN-03)");
        Map<String, Object> changed = ConverterTests.read(new File(root, M4));
        @SuppressWarnings("unchecked") List<Object> limits = (List<Object>) changed.get("speedLimits");
        for (int i = 300; i < 320; i++) limits.set(i, 8.3);
        YandexRoute m4b = YandexRoute.fromSnapshot(changed);
        s5.observe(guidance(6_002_100, m4.routeId, m4b, 1.4, 10, 3));
        equal(1, s5.fires(Topics.ROUTE).size(), "S5: a changed table under the same routeId keeps the published path");
        check(s5.recorder.records().stream().anyMatch(r -> "conditionsSnapshot".equals(r.get("input")) && "table_change_frozen".equals(r.get("decision"))),
                "S5: the refused table change is recorded as frozen");
        s5.observe(guidance(6_003_100, m4.routeId, m4, 1.6, 10, 4));
        equal(1, s5.fires(Topics.ROUTE).size(), "S5: the original table again is still the same path");
        YandexRoute m4c = renamed(m4b, m4.routeId + "-rerouted");
        s5.observe(guidance(6_004_100, m4c.routeId, m4c, 1.8, 10, 5));
        equal(2, s5.fires(Topics.ROUTE).size(), "S5: a new Yandex route is a new route");
        equal(100003L, field(s5.fires(Topics.PATH_SELECTED).get(1), "pathIDReq"), "S5: and a new pathId");
        // CAP-1: an extrapolated location is Navigator's prediction, not a fix.
        Rig ex = new Rig(6_500_000);
        NavigatorDriver.Observation predicted = guidance(6_500_100, m4.routeId, m4, 1.0, 10, 1);
        predicted.locationClass = "EXTRAPOLATED";
        ex.observe(predicted);
        equal(0, ex.fires(Topics.SD_VEHICLE_LOCATION).size(), "CAP-1: EXTRAPOLATED is not sent as a position");
        // S6: Navigator idle from start: 100 only.
        Rig s6 = replay(sequence(root, "S6_status_before_any_route.json"), m4, -1);
        check(statuses(s6).stream().allMatch(v -> v == 100L), "S6: idle start is 100 only, never 110 (DYN-09)");
        // S7: a fix older than one interval is not sent as current (DYN-07).
        Rig s7 = new Rig(7_000_000);
        NavigatorDriver.Observation old = guidance(7_000_100, m4.routeId, m4, 1.0, 10, 1);
        old.fixAgeMs = 2499;
        s7.observe(old);
        equal(0, s7.fires(Topics.SD_VEHICLE_LOCATION).size(), "S7: a 2.5 s old fix is not a position");
        // S9: arrival, then a late sample of the same route.
        Rig s9 = replay(sequence(root, "S9_arrival_then_late_same_route_sample.json"), m4, 3);
        equal(1, s9.fires(Topics.ROUTE).size(), "S9: finished route is not resurrected (DYN-13)");
        // Out-of-order fix (S8's timestamps): the older stamp is ignored, the run continues (DYN-10).
        Rig s8 = new Rig(8_000_000);
        s8.observe(guidance(8_000_100, m4.routeId, m4, 1.0, 10, 1000));
        s8.observe(guidance(8_001_100, m4.routeId, null, 1.3, 10, 2000));
        s8.observe(guidance(8_002_100, m4.routeId, null, 1.1, 10, 1500));
        s8.observe(guidance(8_003_100, m4.routeId, null, 1.6, 10, 3000));
        equal(3, s8.fires(Topics.SD_VEHICLE_LOCATION).size(), "S8: an out-of-order fix is skipped, the next is sent");
        // Rerouting to a new route: 101 throughout, new path RouteType 2 (yaw).
        Rig rr = new Rig(9_000_000);
        rr.observe(guidance(9_000_100, m4.routeId, m4, 1.0, 10, 1));
        NavigatorDriver.Observation off = guidance(9_001_100, m4.routeId, null, 1.0, 10, 2);
        off.mode = "rerouting"; off.positionOnRoute = false;
        rr.observe(off);
        rr.observe(guidance(9_005_100, "another", renamed(m4b, "another"), 0.5, 10, 3));
        rr.clock.advanceTo(9_008_000);
        @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) Json.parse((String) inner(rr.fires(Topics.ROUTE).get(1)).get("navigationSDLink2"));
        @SuppressWarnings("unchecked") Map<String, Object> send = (Map<String, Object>) Json.parse((String) d.get("payload"));
        equal(2L, send.get("RouteType"), "reroute: RouteType 2 (yaw)");
        check(statuses(rr).stream().skip(1).allMatch(v -> v == 101L), "reroute: 101 throughout");
        List<Map<String, Object>> traffic = rr.fires(Topics.TRAFFIC_INFO);
        check(traffic.stream().anyMatch(t -> Boolean.TRUE.equals(inner(t).get("offCourse"))), "reroute: offCourse once after the yaw");
    }

    /** Navigator drops a finished route without a ROUTE_FINISHED: near the end it is an arrival, far from it a cancel. */
    static void vanishing(YandexRoute m4) {
        for (boolean nearEnd : new boolean[]{true, false}) {
            Rig rig = new Rig(11_000_000);
            double end = m4.edges() - 0.05;
            rig.observe(guidance(11_000_100, m4.routeId, m4, nearEnd ? end - 0.5 : 1.0, 10, 1));
            rig.observe(guidance(11_001_200, m4.routeId, null, nearEnd ? end : 1.2, 10, 2));
            NavigatorDriver.Observation gone = new NavigatorDriver.Observation();
            gone.monotonicMs = 11_002_200;
            rig.observe(gone);
            rig.clock.advanceTo(11_007_000);
            boolean arrived = statuses(rig).contains(110L);
            equal(nearEnd, arrived, nearEnd ? "route gone within 50 m of its end: arrival, 110" : "route gone mid-way: a stop, no 110");
            check(rig.fires(Topics.SD_TRAFFIC_INCIDENT).size() >= 3, "incident list at start, at the first NaviInfo and at the stop");
        }
    }

    static YandexRoute renamed(YandexRoute r, String id) {
        return new YandexRoute(id, r.lon, r.lat, r.sections, r.speedLimits, r.trafficLights, r.tunnels, r.ferries, r.wayPoints, r.cameras);
    }
}
