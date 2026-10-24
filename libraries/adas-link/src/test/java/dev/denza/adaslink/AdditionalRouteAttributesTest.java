package dev.denza.adaslink;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Contract tests for provider-neutral attributes, merged boundaries and dynamic parity. */
public final class AdditionalRouteAttributesTest {
    private static int checks;
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    private static YandexRoute route() {
        YandexRoute base = RoadTests.route(0, 0, 100, 0, 200, 0);
        return new YandexRoute("additional-fields", base.lon.clone(), base.lat.clone(),
                Arrays.asList(new YandexRoute.Section(0, 2, "STRAIGHT", "Selected route", 10)),
                new Double[]{15.0, 15.0}, null, null, null, null, null);
    }
    private static final class Source implements RouteAttributes {
        final String geometry;
        final double split;
        Source(YandexRoute route, double split) { geometry = RouteVectorAttributes.geometryFingerprint(route); this.split = split; }
        @Override public boolean matches(YandexRoute route) { return geometry.equals(RouteVectorAttributes.geometryFingerprint(route)); }
        @Override public double[] cuts() { return new double[]{split}; }
        @Override public RoadNetwork.KnownAttributes attributesBetween(double begin, double end) {
            // A merged cut covers conflicting class values: preserve independent direction.
            Integer roadClass = null;
            if (end <= split) roadClass = 7;
            else if (begin >= split) roadClass = 9;
            return new RoadNetwork.KnownAttributes(roadClass, null, null, null, 1, null);
        }
        @Override public Map<String,Object> summary() { return Collections.emptyMap(); }
    }
    public static int run() {
        checks = 0; YandexRoute route = route(); route.additionalAttributes = new Source(route, 1);
        RouteConverter.Converted converted = RouteConverter.convert(route, Coordinates.Frame.WGS84);
        List<StockPath.Link> links = RoadTests.links(converted);
        check(links.size() == 2, "provider boundary splits the initial table");
        check(links.get(0).roadClass == 7 && links.get(1).roadClass == 9, "independent source fields applied by interval");
        for (StockPath.Link link : links) {
            check(link.direction == 1, "literal one-way retained");
            check(link.laneNum == 0 && link.ownership == -1, "class and direction never invent lanes or ownership");
            check("Selected route".equals(link.roadName), "route metadata retained");
        }
        YandexRoute changed = route(); changed.lon[1] += .00001; changed.additionalAttributes = route.additionalAttributes;
        boolean rejected = false;
        try { RouteConverter.convert(changed, Coordinates.Frame.WGS84); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "same route ID with different geometry rejects stale attributes");
        YandexRoute tiny = route(); tiny.additionalAttributes = new Source(tiny, .001);
        List<StockPath.Link> merged = RoadTests.links(RouteConverter.convert(tiny, Coordinates.Frame.WGS84));
        check(merged.size() == 1 && merged.get(0).roadClass == -1 && merged.get(0).direction == 1,
                "sub-metre cut merge keeps only whole-interval unanimous fields");
        StockFeed.Config config = DynamicTests.config(); config.frame = Coordinates.Frame.WGS84;
        DynamicTests.Rig rig = new DynamicTests.Rig(1000000, config, true, true);
        rig.observe(DynamicTests.guidance(1000100, route.routeId, route, .25, 10, 1));
        rig.observe(DynamicTests.guidance(1001100, route.routeId, route, .5, 10, 2));
        List<Map<String,Object>> fires = rig.fires(Topics.TRAFFIC_INFO);
        check(!fires.isEmpty() && DynamicTests.field(fires.get(fires.size()-1), "roadClass") == 7,
                "dynamic class agrees with prepared static interval without a local graph");
        RoadStore complete = new RoadStore(RoadTests.store(Arrays.asList(
                RoadTests.two("a", "b", "Complete source", 9, 15, 2, 0, 0, 200, 0))));
        RouteConverter.Converted completeTable = RouteConverter.convert(route, Coordinates.Frame.WGS84, complete);
        check(completeTable.roadMatch != null && completeTable.roadMatch.matchedM > 190,
                "precedence fixture actually matches complete source");
        check(RoadTests.links(completeTable).get(0).roadClass == 9, "complete source takes static precedence over partial enrichment");
        DynamicTests.Rig completeRig = new DynamicTests.Rig(2000000, config, true, true);
        completeRig.driver.setRoads(complete);
        completeRig.observe(DynamicTests.guidance(2000100, route.routeId, route, .25, 10, 1));
        completeRig.observe(DynamicTests.guidance(2001100, route.routeId, route, .5, 10, 2));
        fires = completeRig.fires(Topics.TRAFFIC_INFO);
        check(!fires.isEmpty() && DynamicTests.field(fires.get(fires.size()-1), "roadClass") == 9,
                "complete source takes the same precedence dynamically");
        return checks;
    }
    public static void main(String[] args) { System.out.println("additional-route-attributes checks=" + run()); }
}
