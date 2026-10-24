package dev.denza.adaslink;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.denza.adaslink.AdasLinkTest.check;
import static dev.denza.adaslink.AdasLinkTest.equal;

/** Converter invariants on the recorded Navigator routes and on adversarial synthetic ones. */
final class ConverterTests {
    static final String[] ROUTES = {
            "captures/yandex-adas-prototype-20261023/route-final-m4/denza-hud-native-route.json",
            "captures/yandex-adas-prototype-20261023/route-final-city/denza-hud-native-route.json",
            "captures/yandex-adas-prototype-20261023/highway-02/denza-hud-native-route.json"};

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(File file) throws Exception {
        return (Map<String, Object>) Json.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    static void run(File root) throws Exception {
        for (String name : ROUTES) {
            Map<String, Object> snapshot = read(new File(root, name));
            YandexRoute route = YandexRoute.fromSnapshot(snapshot);
            RouteConverter.Converted c = RouteConverter.convert(route, Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE);
            invariants(name, route, c);
            // F1/DYN-03: the same route converts to the same table; jams changing do not touch it.
            Map<String, Object> jammed = read(new File(root, name));
            List<Object> jams = (List<Object>) jammed.get("jamSegments");
            if (jams != null) for (int i = 0; i < jams.size(); i++) if (jams.get(i) instanceof Map)
                ((Map<String, Object>) jams.get(i)).put("jamType", i % 7 == 0 ? "HARD" : "FREE");
            RouteConverter.Converted again = RouteConverter.convert(YandexRoute.fromSnapshot(jammed), Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE);
            equal(c.contentKey, again.contentKey, name + ": jam changes leave the link table identical");
            String json = StockRouteJson.messageSend(withId(c.path, 100002), 1, StockRouteJson.Strings.EN);
            check(!json.contains(":null"), name + ": no JSON null anywhere in the payload (F4)");
        }
        synthetic();
    }

    static StockPath withId(StockPath p, long id) { p.pathId = id; return p; }

    static void invariants(String name, YandexRoute route, RouteConverter.Converted c) {
        StockPath p = c.path;
        equal(route.sections.size(), p.segments.size(), name + ": one segment per Yandex section");
        long groupLights = 0, total = 0;
        int flagged = 0;
        for (int si = 0; si < p.segments.size(); si++) {
            StockPath.Segment s = p.segments.get(si);
            long sum = 0, lights = 0;
            for (StockPath.Link l : s.links) {
                sum += l.length;
                if (l.hasTrafficLight) { lights++; flagged++; }
                check(l.roadClass == -1 && l.ownership == -1 && (l.formway == -1 || l.formway == 4) && l.laneNum == 0,
                        name + ": route-only highway/limit/sign annotations do not invent continuous road attributes");
                check(l.mainAction == 0, name + ": link MainAction 0, as on every stock segment-end link");
                check(l.linkType == 0 || l.linkType == 1 || l.linkType == 2, name + ": linktype common/ferry/tunnel, never NULL");
                check(l.pointCount() >= 2, name + ": a link has at least two points");
                check(l.topoId > 0, name + ": positive link id");
            }
            equal(s.length, sum, name + ": sum of whole-metre link lengths == navigationLen (F3) seg " + si);
            check(lights <= s.trafficLightNum, name + ": segment light count covers its flagged links");
            groupLights += s.trafficLightNum;
            total += s.length;
            double geometry = c.segmentEndM[si] - c.segmentBeginM[si];
            check(s.length <= geometry + 1e-9 && s.length > geometry - s.links.size(), name + ": segment length is its links' whole metres cut down");
        }
        check(total <= c.totalM + 1e-9 && total > c.totalM - p.linkCount(), name + ": route length within truncation");
        // F2: every flagged link ends at a light; every light past the start is owned by the link ending at it.
        int link = 0;
        for (StockPath.Segment s : p.segments) for (StockPath.Link l : s.links) {
            if (l.hasTrafficLight) {
                double best = Double.MAX_VALUE;
                for (double d : c.lightM) best = Math.min(best, Math.abs(d - c.linkEndM[link]));
                check(best <= RouteConverter.MIN_LINK_M, name + ": flagged link " + link + " ends at its light (gap " + best + " m)");
            }
            link++;
        }
        if (c.lightM != null) {
            int owned = 0;
            for (double d : c.lightM) if (d >= RouteConverter.MIN_LINK_M) owned++;
            equal((long) owned, groupLights, name + ": every light past the start is owned exactly once (F8)");
        }
        // Speed limit changes are link boundaries.
        if (route.speedLimits != null) {
            Set<Double> ends = new HashSet<>();
            for (double e : c.linkEndScalar) ends.add(e);
            for (int i = 1; i < route.speedLimits.length; i++)
                if (!java.util.Objects.equals(route.speedLimits[i], route.speedLimits[i - 1])) {
                    double best = Double.MAX_VALUE;
                    for (double e : c.linkEndM) best = Math.min(best, Math.abs(e - c.cumulative[i]));
                    check(best <= RouteConverter.MIN_LINK_M, name + ": limit change at edge " + i + " is a cut");
                }
        }
        // The last segment arrives at the destination, as the stock's.
        StockPath.Segment last = p.segments.get(p.segments.size() - 1);
        check(last.mainAction == 0 && last.assistantAction == 36, name + ": last segment is MainAction 0 / AssistantAction 36");
        // Points are on the stock grid (F9).
        String json = StockRouteJson.messageSend(withId(p, 100002), 1, StockRouteJson.Strings.EN);
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) ((List<Object>) ((Map<String, Object>) Json.parse(json)).get("data")).get(0);
        @SuppressWarnings("unchecked") List<Object> pnts = (List<Object>) data.get("pnts");
        boolean grid = true;
        for (Object o : pnts) {
            @SuppressWarnings("unchecked") Map<String, Object> pt = (Map<String, Object>) o;
            double x = ((Number) pt.get("x")).doubleValue();
            if (Math.round(x * 3600000) / 3600000.0 != x) grid = false;
        }
        check(grid, name + ": every point on the 1/3600000 grid (F9)");
        check(json.matches("(?s).*\"navigationLen\":\\d+,.*") && !json.matches("(?s).*\"(navigationLen|groupLen|groupTime)\":\\d+\\.\\d.*"),
                name + ": navigationLen/groupLen/groupTime are JSON integers (F3)");
    }

    /** Two lights in one Yandex edge, a light on a section boundary, a light at the very start. */
    static void synthetic() {
        double[] lon = new double[21], lat = new double[21];
        for (int i = 0; i <= 20; i++) { lon[i] = 37.6 + i * 0.001; lat[i] = 55.7; }
        List<YandexRoute.Section> sections = Arrays.asList(
                new YandexRoute.Section(0, 10, "STRAIGHT", "Первая", 60),
                new YandexRoute.Section(10, 20, "LEFT", null, 70));
        double[] lights = {0, 3.25, 3.75, 10, 15.5};
        Double[] limits = new Double[20];
        for (int i = 0; i < 20; i++) if (i < 6) limits[i] = 16.6; else if (i >= 12) limits[i] = 22.2;
        YandexRoute route = new YandexRoute("synthetic", lon, lat, sections, limits, lights,
                Collections.singletonList(new double[]{16, 18}), Collections.<double[]>emptyList(), new double[]{0, 20}, null);
        RouteConverter.Converted c = RouteConverter.convert(route, Coordinates.Frame.WGS84);
        invariants("synthetic", route, c);
        int flagged = 0;
        for (StockPath.Segment s : c.path.segments) for (StockPath.Link l : s.links) if (l.hasTrafficLight) flagged++;
        equal(4, flagged, "synthetic: four lights past the start, each its own link (two in one edge are cut apart)");
        equal(1L, c.path.segments.get(0).links.get(0).hasTrafficLight ? 1L : 0L, "synthetic: first link ends at the 3.25 light");
        equal(5, c.path.trafficLights.size(), "synthetic: all five lights listed, the start one too");
        check(c.path.segments.get(0).mainAction == 1, "synthetic: segment 0 ends with the next section's LEFT (MainAction 1)");
        equal("Первая", c.path.segments.get(0).links.get(0).roadName, "synthetic: road name from the section toponym");
        StockPath.Segment s0 = c.path.segments.get(0);
        equal(0, s0.links.get(s0.links.size() - 1).mainAction, "synthetic: the link ending at the LEFT keeps MainAction 0 (stock)");
        equal(1, s0.mainAction, "synthetic: the segment carries the LEFT");
        // Highway annotations and legal speed retain their boundaries, never road class or sign-width LaneNum.
        Double[] hwLimits = new Double[20];
        for (int i = 0; i < 20; i++) hwLimits[i] = i < 10 ? 16.6 : 27.8;
        YandexRoute hw = new YandexRoute("hw", lon, lat, Arrays.asList(new YandexRoute.Section(0, 20, "STRAIGHT", "М4", 600, 95)),
                hwLimits, new double[]{15.5}, null, null, null, null);
        hw.highways = Collections.singletonList(new double[]{2, 18});
        hw.laneSigns = Collections.singletonList(new double[]{4, 3});
        RouteConverter.Converted h = RouteConverter.convert(hw, Coordinates.Frame.WGS84);
        invariants("highway", hw, h);
        int lit = 0;
        for (StockPath.Link l : h.path.segments.get(0).links) {
            check(l.roadClass == -1 && l.formway == -1 && l.direction == 0 && l.ownership == -1 && l.laneNum == 0,
                    "highway: neither 59.8 nor 100 km/h proves class, form, direction, ownership or lanes");
            if (l.hasTrafficLight) lit++;
        }
        for (double boundary : new double[]{2, 4, 18}) {
            boolean found = false;
            for (double end : h.linkEndScalar) if (end == boundary) found = true;
            check(found, "highway/sign boundary retained at " + boundary);
        }
        equal(1, lit, "highway: the light still ends its link");
        equal(95.0, h.segmentFreeFlowKmh[0], "free-flow speed carried per segment");
        // Roundabout section.
        YandexRoute ring = new YandexRoute("ring", lon, lat, Arrays.asList(new YandexRoute.Section(0, 5, "STRAIGHT", null, 10),
                new YandexRoute.Section(5, 8, "ENTER_ROUNDABOUT", null, 10), new YandexRoute.Section(8, 20, "LEAVE_ROUNDABOUT", null, 10)),
                null, null, null, null, null, null);
        ring.highways = Collections.singletonList(new double[]{0, 20});
        RouteConverter.Converted r = RouteConverter.convert(ring, Coordinates.Frame.WGS84);
        check(r.path.segments.get(1).links.get(0).formway == 4 && r.path.segments.get(1).links.get(0).direction == 1
                && r.path.segments.get(2).links.get(0).formway == -1, "roundabout section is formway 4, direction 1");
        equal(11, r.path.segments.get(0).mainAction, "segment before the roundabout ends with EntryRing");
        boolean tunnel = false;
        for (StockPath.Link l : c.path.segments.get(1).links) tunnel |= l.linkType == 2;
        check(tunnel, "synthetic: tunnel range is linktype 2");
        // The same physical link has the same id in another route that shares it.
        double[] lon2 = Arrays.copyOfRange(lon, 0, 11), lat2 = Arrays.copyOfRange(lat, 0, 11);
        YandexRoute shorter = new YandexRoute("other", lon2, lat2,
                Collections.singletonList(new YandexRoute.Section(0, 10, "STRAIGHT", "Первая", 60)),
                Arrays.copyOfRange(limits, 0, 10), new double[]{3.25, 3.75}, null, null, null, null);
        RouteConverter.Converted d = RouteConverter.convert(shorter, Coordinates.Frame.WGS84);
        equal(c.path.segments.get(0).links.get(1).topoId, d.path.segments.get(0).links.get(1).topoId, "link id depends on geometry, not route");
        // Whole-metre rounding keeps sums exact.
        int[] whole = RouteConverter.wholeMetres(new double[]{10.4, 10.4, 10.4, 0.6});
        equal(32, whole[0] + whole[1] + whole[2] + whole[3], "largest remainder: sum is the rounded total");
        // Progress placement.
        int[] info = c.naviInfo(c.metres(5.0));
        check(info[0] == 0 && info[5] == RouteConverter.round(c.totalM - c.metres(5.0)), "naviInfo: segment and route remain");
        check(info[3] == RouteConverter.round(c.linkEndM[info[2]] - c.metres(5.0)), "naviInfo: link remain");
    }
}
