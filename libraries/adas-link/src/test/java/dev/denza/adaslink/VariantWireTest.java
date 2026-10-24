package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * What each experiment switch does to the emitted bytes: the frame on every coordinate the feed
 * sends, each road-semantics override (and what it must leave alone), historySpeed per Yandex
 * route ID, the zero legal limit, and the variant in the trace header and its attribution notes.
 * Dry-run feed through the real driver and converter; the live guard's part is in LiveVariantTest.
 */
public final class VariantWireTest {
    private static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    /**
     * Near the parked start position (Moscow envelope): a light, a tunnel, a Yandex highway range
     * (it only cuts links) and, over that range, class 0 and one-way from Yandex's offline maps.
     */
    static YandexRoute route(String id) { return route(id, 0); }
    static YandexRoute route(String id, int shift) {
        double[] lon = new double[6], lat = new double[6];
        for (int i = 0; i < 6; i++) { lon[i] = 37.060 + 0.004 * i; lat[i] = 55.518 + 0.0004 * i + 0.0002 * shift; }
        Double[] limits = new Double[5];
        Arrays.fill(limits, 60 / 3.6);
        limits[3] = 100 / 3.6;
        YandexRoute r = new YandexRoute(id, lon, lat,
                Arrays.asList(new YandexRoute.Section(0, 3, null, "Road", 90, 40), new YandexRoute.Section(3, 5, "RIGHT", "Road", 60, 40)),
                limits, new double[]{1.5}, Arrays.asList(new double[]{2.2, 2.7}), null, null, null);
        r.highways = new ArrayList<>(Arrays.asList(new double[]{3.0, 4.0}));
        r.additionalAttributes = new OfflineClass(r, 3.0, 4.0, 0, 1);
        return r;
    }

    /** Yandex's offline class and one-way (null: unknown) between two scalars; unknown elsewhere. */
    static final class OfflineClass implements RouteAttributes {
        final String geometry; final double begin, end; final int edges; final Integer roadClass, direction;
        OfflineClass(YandexRoute route, double begin, double end, Integer roadClass, Integer direction) {
            geometry = RouteVectorAttributes.geometryFingerprint(route); this.begin = begin; this.end = end; edges = route.edges();
            this.roadClass = roadClass; this.direction = direction;
        }
        @Override public boolean matches(YandexRoute route) { return geometry.equals(RouteVectorAttributes.geometryFingerprint(route)); }
        @Override public double[] cuts() {
            List<Double> out = new ArrayList<>();
            for (double c : new double[]{begin, end}) if (c > 0 && c < edges) out.add(c);
            double[] cuts = new double[out.size()];
            for (int i = 0; i < cuts.length; i++) cuts[i] = out.get(i);
            return cuts;
        }
        @Override public RoadNetwork.KnownAttributes attributesBetween(double a, double b) {
            return a >= begin - 1e-9 && b <= end + 1e-9 ? new RoadNetwork.KnownAttributes(roadClass, null, null, null, direction, null)
                    : RoadNetwork.KnownAttributes.NONE;
        }
        @Override public Map<String, Object> summary() { return java.util.Collections.emptyMap(); }
    }

    static final class Rig {
        final ManualScheduler clock = new ManualScheduler(5_000_000);
        final DryRunRecorder recorder = new DryRunRecorder(null, true);
        final StockFeed feed;
        final NavigatorDriver driver;
        long stamp;
        Rig(ExperimentVariant variant) {
            StockFeed.Config config = FieldDefaults.navigator(new StockFeed.Config(), variant);
            config.emulatedAckDelayMs = 110;
            feed = new StockFeed(clock, recorder, config);
            driver = new NavigatorDriver(feed, config.frame, config.variant, clock);
            feed.start(true, true, true);
            clock.drain();
        }
        void drive(YandexRoute r, double from, double to, double step) {
            for (double s = from; s <= to + 1e-9; s += step) {
                NavigatorDriver.Observation o = new NavigatorDriver.Observation();
                o.monotonicMs = clock.now() + 1000; o.mode = "guidance"; o.routeId = r.routeId; o.route = s == from ? r : null;
                o.positionOnRoute = true; o.scalar = s; o.timeToFinishS = 300; o.locationClass = "FINE";
                double[] p = RouteConverter.wgs(r, s);
                o.lon = p[0]; o.lat = p[1]; o.rawLon = p[0] + 0.00002; o.rawLat = p[1] + 0.00001;
                o.speedMps = 10; o.heading = 90; o.stamp = ++stamp; o.fixAgeMs = 20; o.speedLimitMps = 60 / 3.6;
                clock.advanceTo(o.monotonicMs); driver.observe(o); clock.drain();
            }
        }
        List<Map<String, Object>> fires(long topic) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : recorder.records())
                if ("fire".equals(r.get("kind")) && ((Number) r.get("topic")).longValue() == topic) out.add(r);
            return out;
        }
        List<Map<String, Object>> notes(String input) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : recorder.records()) if (input.equals(r.get("input"))) out.add(r);
            return out;
        }
        Map<String, Object> header() {
            for (Map<String, Object> r : recorder.records()) if ("header".equals(r.get("kind"))) return r;
            throw new AssertionError("no header");
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> inner(Map<String, Object> fire) {
        return (Map<String, Object>) ((Map<String, Object>) fire.get("decoded")).values().iterator().next();
    }
    static double d(Map<String, Object> m, String key) { Object v = m.get(key); return v == null ? 0 : ((Number) v).doubleValue(); }
    static long l(Map<String, Object> m, String key) { Object v = m.get(key); return v == null ? 0 : ((Number) v).longValue(); }

    /** The route body (MessageSend) of the n-th route fire. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> routeBody(Map<String, Object> fire) {
        String chunk = (String) inner(fire).get("navigationSDLink2");
        Map<String, Object> detail = (Map<String, Object>) Json.parse(chunk);
        Map<String, Object> send = (Map<String, Object>) Json.parse((String) detail.get("payload"));
        return (Map<String, Object>) ((List<Object>) send.get("data")).get(0);
    }
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> links(Map<String, Object> body) { return (List<Map<String, Object>>) (List<?>) body.get("links"); }

    /** Every {lon, lat} the feed sent, in order, per kind. */
    @SuppressWarnings("unchecked")
    static Map<String, List<double[]>> coordinates(Rig rig) {
        Map<String, List<double[]>> out = new java.util.LinkedHashMap<>();
        for (String k : new String[]{"route.pnts", "route.trafficLights", "position", "position.road", "light", "tunnel"}) out.put(k, new ArrayList<double[]>());
        for (Map<String, Object> fire : rig.fires(Topics.ROUTE)) {
            Map<String, Object> body = routeBody(fire);
            for (Object p : (List<Object>) body.get("pnts")) out.get("route.pnts").add(new double[]{d((Map<String, Object>) p, "x"), d((Map<String, Object>) p, "y")});
            for (Object p : (List<Object>) body.get("trafficLights"))
                out.get("route.trafficLights").add(new double[]{d((Map<String, Object>) p, "x"), d((Map<String, Object>) p, "y")});
        }
        for (Map<String, Object> f : rig.fires(Topics.SD_VEHICLE_LOCATION)) {
            Map<String, Object> m = inner(f);
            out.get("position").add(new double[]{d(m, "locationLongitude"), d(m, "locationLatitude")});
            out.get("position.road").add(new double[]{d(m, "locationLongitudeAssociateRoad"), d(m, "locationLatitudeAssociateRoad")});
        }
        for (Map<String, Object> f : rig.fires(Topics.SD_TRAFFIC_LIGHT)) {
            Map<String, Object> m = inner(f);
            if (d(m, "trafficLightLon") != 0) out.get("light").add(new double[]{d(m, "trafficLightLon"), d(m, "trafficLightLat")});
        }
        for (Map<String, Object> f : rig.fires(Topics.SD_TUNNEL)) {
            Map<String, Object> m = inner(f);
            if (d(m, "tunnelStartPiointLon") != 0) out.get("tunnel").add(new double[]{d(m, "tunnelStartPiointLon"), d(m, "tunnelStartPiointLat")});
            if (d(m, "tunnelEndPiointLon") != 0) out.get("tunnel").add(new double[]{d(m, "tunnelEndPiointLon"), d(m, "tunnelEndPiointLat")});
        }
        return out;
    }

    public static void main(String[] args) {
        frame();
        preparedFrame();
        linkOverrides();
        dynamicOverrides();
        partialRoad();
        historySpeed();
        speedLimit();
        defaults();
        System.out.println("VariantWireTest: " + checks + " checks passed");
    }

    static ExperimentVariant v(String json) { return ExperimentVariant.parse(Json.parse(json)); }

    /** STOCK_GCJ moves every coordinate the feed sends by the stock's forward transform, consistently. */
    static void frame() {
        Rig wgs = new Rig(ExperimentVariant.NONE), gcj = new Rig(v("{\"frame\":\"STOCK_GCJ\"}"));
        YandexRoute r = route("frame-route");
        wgs.drive(r, 0.2, 3.4, 0.2);
        gcj.drive(route("frame-route"), 0.2, 3.4, 0.2);
        Map<String, List<double[]>> a = coordinates(wgs), b = coordinates(gcj);
        for (String kind : a.keySet()) {
            List<double[]> x = a.get(kind), y = b.get(kind);
            check(!x.isEmpty(), "the drive sends " + kind + " coordinates");
            check(x.size() == y.size(), kind + ": the same messages in both frames " + x.size() + "/" + y.size());
            for (int i = 0; i < x.size(); i++) {
                double[] expected = Coordinates.toSdk(x.get(i)[0], x.get(i)[1], Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE);
                double off = Coordinates.distance(expected[0], expected[1], y.get(i)[0], y.get(i)[1]);
                double moved = Coordinates.distance(x.get(i)[0], x.get(i)[1], y.get(i)[0], y.get(i)[1]);
                check(off < 0.5, kind + "[" + i + "] is the stock frame of the WGS-84 value (" + off + " m off)");
                check(moved > 300 && moved < 700, kind + "[" + i + "] moved by the GCJ offset (" + moved + " m)");
            }
        }
        check("STOCK_GCJ".equals(((Map<?, ?>) gcj.header().get("variant")).get("frame"))
                && "NATIVE_GCJ_UNRESTRICTED_CANDIDATE".equals(gcj.header().get("frame")), "the header names the variant's frame");
        // Road facilities: placed points in the converted frame.
        RouteConverter.Converted w = RouteConverter.convert(r, Coordinates.Frame.WGS84, null, ExperimentVariant.NONE);
        RouteConverter.Converted g = RouteConverter.convert(r, Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE, null, v("{\"frame\":\"STOCK_GCJ\"}"));
        check(w.tunnelEnds.size() == 1 && g.tunnelEnds.get(0)[0] != w.tunnelEnds.get(0)[0], "tunnel ends in the variant's frame");
    }

    /** A conversion prepared in another frame or variant is not reused: the driver converts in its own. */
    static void preparedFrame() {
        Rig gcj = new Rig(v("{\"frame\":\"STOCK_GCJ\"}"));
        YandexRoute r = route("prepared-route");
        NavigatorDriver.Observation o = new NavigatorDriver.Observation();
        o.monotonicMs = gcj.clock.now() + 1000; o.mode = "guidance"; o.routeId = r.routeId; o.route = r; o.positionOnRoute = true;
        o.scalar = 0.2; o.locationClass = "FINE"; double[] p = RouteConverter.wgs(r, 0.2); o.lon = p[0]; o.lat = p[1];
        o.stamp = 1; o.fixAgeMs = 20; o.speedMps = 10;
        NavigatorDriver.prepare(o, Coordinates.Frame.WGS84, null, null);
        gcj.clock.advanceTo(o.monotonicMs); gcj.driver.observe(o); gcj.clock.drain();
        List<double[]> pnts = coordinates(gcj).get("route.pnts");
        double[] wgs = Coordinates.toSdk(r.lon[0], r.lat[0], Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE);
        check(!pnts.isEmpty() && Coordinates.distance(wgs[0], wgs[1], pnts.get(0)[0], pnts.get(0)[1]) < 0.5,
                "a WGS-84 preparation is converted again in the variant's frame");
        check(Boolean.FALSE.equals(gcj.driver.conversionSummary().get("preparedAhead")), "the stale preparation is not used");
    }

    /**
     * Each link override fills only what Yandex left unknown: the offline maps' class and one-way
     * survive link.roadclass and link.direction; formway, ownership, lanes and adminCode have no
     * Yandex value here (a highway range gives none), so they are set on every link.
     */
    @SuppressWarnings("unchecked")
    static void linkOverrides() {
        Rig base = new Rig(ExperimentVariant.NONE);
        base.drive(route("links"), 0.2, 0.4, 0.2);
        List<Map<String, Object>> plain = links(routeBody(base.fires(Topics.ROUTE).get(0)));
        String[][] cases = {{"link.roadclass", "9", "roadclass"}, {"link.formway", "15", "formway"}, {"link.ownership", "0", "ownership"},
                {"link.direction", "0", "direction"}, {"link.laneNum", "2", "laneNum"}, {"link.adminCode", "990001", "adminCode"}};
        for (String[] c : cases) {
            Rig rig = new Rig(v("{\"" + c[0] + "\":" + c[1] + "}"));
            rig.drive(route("links"), 0.2, 0.4, 0.2);
            List<Map<String, Object>> got = links(routeBody(rig.fires(Topics.ROUTE).get(0)));
            check(got.size() == plain.size(), c[0] + ": the same link table");
            int set = 0, kept = 0;
            for (int i = 0; i < got.size(); i++) {
                boolean offline = l(plain.get(i), "roadclass") == 0;
                long before = l(plain.get(i), c[2]), after = l(got.get(i), c[2]);
                boolean sourced = offline && (c[0].equals("link.roadclass") || c[0].equals("link.direction"));
                if (sourced) { check(after == before, c[0] + ": Yandex's offline value stays on link " + i); kept++; }
                else { check(after == Long.parseLong(c[1]), c[0] + " set on link " + i + ": " + after); set++; }
                for (String other : new String[]{"roadclass", "formway", "ownership", "direction", "laneNum", "adminCode", "linkID", "len"})
                    if (!other.equals(c[2])) check(l(got.get(i), other) == l(plain.get(i), other), c[0] + " leaves " + other + " alone");
            }
            check(set > 0 && kept + set == got.size(), c[0] + ": set " + set + ", kept " + kept);
            check(!c[0].equals("link.roadclass") && !c[0].equals("link.direction") || kept > 0, "the route has an offline-classed link to keep");
            check(c[0].equals("link.roadclass") || c[0].equals("link.direction") || kept == 0,
                    c[0] + ": a highway range alone gives no value to keep");
            List<Map<String, Object>> notes = rig.notes("variantApplied");
            check(!notes.isEmpty(), c[0] + ": the trace says the override was applied");
            Map<String, Object> row = (Map<String, Object>) ((Map<String, Object>) notes.get(0).get("links")).get(c[0]);
            check(row != null && l(row, "links") == set && l(row, "keptSourced") == kept && l(row, "value") == Long.parseLong(c[1]),
                    c[0] + ": attribution counts the links " + row);
        }
        check(base.notes("variantApplied").isEmpty(), "no variant, no attribution");
        // Both switches at once, against the value they would overwrite: Yandex's class 0 and one-way survive.
        Rig both = new Rig(v("{\"link.roadclass\":9,\"link.direction\":0}"));
        both.drive(route("links"), 0.2, 0.4, 0.2);
        List<Map<String, Object>> got = links(routeBody(both.fires(Topics.ROUTE).get(0)));
        int offline = 0;
        for (int i = 0; i < got.size(); i++) {
            if (l(plain.get(i), "roadclass") == 0) {
                offline++;
                check(l(got.get(i), "roadclass") == 0 && l(got.get(i), "direction") == 1,
                        "a Yandex-known class and one-way are never overwritten by the variant: link " + i);
            } else check(l(got.get(i), "roadclass") == 9 && l(got.get(i), "direction") == 0, "unknown fields filled: link " + i);
        }
        check(offline > 0 && plain.stream().anyMatch(x -> l(x, "direction") == 1), "the fixture has an offline one-way link");
        for (Map<String, Object> x : plain) check(l(x, "roadclass") != 6, "no class from the posted speed (the highway rule is gone)");
    }

    /** Region, position ownership and TrafficInfo class: on matched positions where no graph road names them. */
    static void dynamicOverrides() {
        Rig base = new Rig(ExperimentVariant.NONE), rig = new Rig(v("{\"region\":990001,\"position.ownership\":0,"
                + "\"traffic.roadClass\":\"link\",\"link.roadclass\":9}"));
        base.drive(route("dyn"), 0.2, 3.6, 0.2);
        rig.drive(route("dyn"), 0.2, 3.6, 0.2);
        for (Map<String, Object> f : base.fires(Topics.SD_REGION)) check(l(inner(f), "regionId") == 0, "no variant: region 0");
        check(!rig.fires(Topics.SD_REGION).isEmpty(), "region sent");
        for (Map<String, Object> f : rig.fires(Topics.SD_REGION)) {
            Map<String, Object> m = inner(f);
            check(l(m, "regionId") == 990001 && l(m, "provinceCode") == 990001 && l(m, "cityCode") == 990001 && l(m, "countryCode") == 1,
                    "region: the stock's 990001 ids, country 1: " + m);
        }
        List<Map<String, Object>> bp = base.fires(Topics.SD_VEHICLE_LOCATION), vp = rig.fires(Topics.SD_VEHICLE_LOCATION);
        check(bp.size() == vp.size() && !vp.isEmpty(), "same positions");
        int matched = 0;
        for (int i = 0; i < vp.size(); i++) {
            long path = (long) d(inner(vp.get(i)), "curSDRouteID");
            if (path <= 0) continue;
            matched++;
            check((int) l(inner(bp.get(i)), "currentRoadOwnership") == -1, "no variant: the unmatched ownership (uint32 -1)");
            check(l(inner(vp.get(i)), "currentRoadOwnership") == 0, "position.ownership: the stock's 0 on a matched position");
        }
        check(matched > 0, "matched positions exist");
        boolean sawOffline = false, sawCity = false;
        List<Map<String, Object>> bt = base.fires(Topics.TRAFFIC_INFO), vt = rig.fires(Topics.TRAFFIC_INFO);
        check(bt.size() == vt.size(), "same TrafficInfo");
        for (int i = 0; i < vt.size(); i++) {
            long before = l(inner(bt.get(i)), "roadClass"), after = l(inner(vt.get(i)), "roadClass");
            if (before == 0) { check(after == 0, "Yandex's offline class under the car is never overwritten"); sawOffline = true; continue; }
            if (before == after) continue; // before the first NaviInfo there is no link under the car
            check(after == 9, "traffic.roadClass link: the published link's class (9): " + after);
            sawCity |= after == 9;
        }
        check(sawCity && sawOffline, "TrafficInfo follows the link under the car; the offline-classed link keeps Yandex's class");
        Rig unclassed = new Rig(v("{\"traffic.roadClass\":\"link\"}"));
        unclassed.drive(route("dyn"), 0.2, 1.0, 0.2);
        for (Map<String, Object> f : unclassed.fires(Topics.TRAFFIC_INFO))
            check(l(inner(f), "roadClass") != 9, "link class -1 stays unmatched: nothing invented");
        Rig fixed = new Rig(v("{\"traffic.roadClass\":9}"));
        fixed.drive(route("dyn"), 0.2, 1.0, 0.2);
        List<Map<String, Object>> ft = fixed.fires(Topics.TRAFFIC_INFO);
        check(l(inner(ft.get(ft.size() - 1)), "roadClass") == 9, "traffic.roadClass 9 on a matched position");
        boolean region = false, own = false, cls = false;
        for (Map<String, Object> n : rig.notes("variantApplied")) {
            region |= "region".equals(n.get("field")); own |= "position.ownership".equals(n.get("field"));
            cls |= "traffic.roadClass".equals(n.get("field"));
        }
        check(region && own && cls, "each dynamic override is attributed in the trace");
    }

    /**
     * Per field, not per road: on a road whose class Yandex knows (7) but whose ownership no Yandex
     * source gives, position.ownership=0 fills the ownership and traffic.roadClass=9 leaves the 7;
     * on a road with no Yandex class both fill. The trace attributes only what the switch put on the wire.
     */
    static void partialRoad() {
        ExperimentVariant variant = v("{\"position.ownership\":0,\"traffic.roadClass\":9}");
        YandexRoute known = route("partial-known"), unknown = route("partial-unknown");
        known.additionalAttributes = new OfflineClass(known, 0, known.edges(), 7, null);
        unknown.additionalAttributes = null;
        Rig a = new Rig(variant), b = new Rig(variant);
        a.drive(known, 0.2, 2.0, 0.2);
        b.drive(unknown, 0.2, 2.0, 0.2);
        Object[][] cases = {{a, 7L, "a Yandex class 7"}, {b, 9L, "no Yandex class"}};
        for (Object[] c : cases) {
            Rig rig = (Rig) c[0]; long expected = (Long) c[1];
            List<Map<String, Object>> t = rig.fires(Topics.TRAFFIC_INFO);
            check(!t.isEmpty() && l(inner(t.get(t.size() - 1)), "roadClass") == expected,
                    c[2] + ": TrafficInfo.roadClass " + expected + ", got " + (t.isEmpty() ? "none" : l(inner(t.get(t.size() - 1)), "roadClass")));
            int matched = 0;
            for (Map<String, Object> f : rig.fires(Topics.SD_VEHICLE_LOCATION)) {
                if (d(inner(f), "curSDRouteID") <= 0) continue;
                matched++;
                check(l(inner(f), "currentRoadOwnership") == 0, c[2] + ": ownership has no Yandex source, the variant's 0 fills it");
            }
            check(matched > 0, c[2] + ": matched positions exist");
            boolean classNoted = false, ownNoted = false;
            for (Map<String, Object> n : rig.notes("variantApplied")) {
                classNoted |= "traffic.roadClass".equals(n.get("field"));
                ownNoted |= "position.ownership".equals(n.get("field"));
            }
            check(ownNoted, c[2] + ": the ownership fill-in is attributed");
            check(classNoted == (expected == 9), c[2] + ": traffic.roadClass is attributed only when the switch put it on the wire");
        }
    }

    /** historySpeed: fixed on every route; alternate by Yandex route ID, so there and back is an A/B. */
    static void historySpeed() {
        Rig ab = new Rig(v("{\"historySpeed\":{\"mode\":\"alternate\",\"values\":[null,25]}}"));
        long[] seen = new long[3];
        String[] ids = {"ab-1", "ab-2", "ab-3"};
        for (int k = 0; k < 3; k++) {
            ab.drive(route(ids[k], k), 0.2, 1.2, 0.2);
            List<Map<String, Object>> s = ab.fires(Topics.SD_SPEED_INTERSECTION);
            seen[k] = l(inner(s.get(s.size() - 1)), "historySpeed");
        }
        check(seen[0] == 40 && seen[1] == 25 && seen[2] == 40, "alternate [null,25]: Yandex's 40, then 25, then 40: " + Arrays.toString(seen));
        List<Map<String, Object>> notes = ab.notes("variantApplied");
        check(notes.size() == 3, "one attribution per path");
        check("yandex".equals(notes.get(0).get("historySpeed")) && Long.valueOf(25).equals(notes.get(1).get("historySpeed"))
                && "yandex".equals(notes.get(2).get("historySpeed")), "the trace says which value each path used");
        check(Long.valueOf(1).equals(notes.get(1).get("routeOrdinal")) && "ab-2".equals(notes.get(1).get("routeId")), "attributed to its route");
        Rig fixed = new Rig(v("{\"historySpeed\":{\"mode\":\"fixed\",\"value\":33}}"));
        fixed.drive(route("fixed"), 0.2, 1.2, 0.2);
        List<Map<String, Object>> s = fixed.fires(Topics.SD_SPEED_INTERSECTION);
        check(l(inner(s.get(s.size() - 1)), "historySpeed") == 33, "fixed historySpeed");
    }

    /** speedLimit zero: SpdLmtSpeedValue 0 although Yandex posts 60, attributed in the input note. */
    static void speedLimit() {
        Rig base = new Rig(ExperimentVariant.NONE), zero = new Rig(v("{\"speedLimit\":\"zero\"}"));
        base.drive(route("limit"), 0.2, 1.2, 0.2);
        zero.drive(route("limit"), 0.2, 1.2, 0.2);
        List<Map<String, Object>> b = base.fires(Topics.TRAFFIC_INFO), z = zero.fires(Topics.TRAFFIC_INFO);
        check(l(inner(b.get(b.size() - 1)), "spdLmtSpeedValue") == 60, "build behaviour: Yandex's 60");
        for (Map<String, Object> f : z) check(l(inner(f), "spdLmtSpeedValue") == 0, "speedLimit zero: 0 on every TrafficInfo");
        boolean noted = false;
        for (Map<String, Object> n : zero.notes("currentRoadSpeed")) noted |= "speedLimit=zero".equals(n.get("variant"));
        check(noted, "the limit input says the variant zeroed it");
    }

    /** No variant: the header names the defaults and the bytes are the build's. */
    @SuppressWarnings("unchecked")
    static void defaults() {
        Rig a = new Rig(ExperimentVariant.NONE), b = new Rig(v("{}"));
        a.drive(route("same"), 0.2, 2.0, 0.2);
        b.drive(route("same"), 0.2, 2.0, 0.2);
        Map<String, Object> h = (Map<String, Object>) a.header().get("variant");
        check("WGS84".equals(h.get("frame")) && ((List<?>) h.get("overrides")).isEmpty(), "the header names the effective variant: " + h);
        List<String> x = new ArrayList<>(), y = new ArrayList<>();
        // The route envelope, the lane beans and next lanes carry wall-clock times; everything else must be equal.
        List<Long> timed = Arrays.asList(Topics.ROUTE, Topics.SD_AHEAD_LANES, Topics.SD_NEXT_LANES);
        for (Map<String, Object> r : a.recorder.records()) if ("fire".equals(r.get("kind")) && !timed.contains(((Number) r.get("topic")).longValue())) x.add((String) r.get("payloadHex"));
        for (Map<String, Object> r : b.recorder.records()) if ("fire".equals(r.get("kind")) && !timed.contains(((Number) r.get("topic")).longValue())) y.add((String) r.get("payloadHex"));
        check(x.equals(y) && !x.isEmpty(), "an empty variant sends the build's bytes");
    }
}
