package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Partial road metadata must survive conversion without inventing the other stock attributes. */
public final class KnownRoadAttributesTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final class Network implements RoadNetwork {
        final RoadStore geometry = new RoadStore(RoadTests.store(Arrays.asList(
                RoadTests.two("a", "b", "Graph name", 9, 15, 2, 0, 0, 200, 0).legal(54, 36))));
        final Fragment road;
        Network(KnownAttributes attributes, boolean complete) {
            Fragment base = geometry.fragment(0);
            road = complete ? base : Fragment.partial(base.index, base.from, base.to, base.legalForwardKmh,
                    base.legalBackwardKmh, base.forward, base.backward, base.name, base.x, base.y, base.cum)
                    .withKnownAttributes(attributes);
        }
        @Override public Coordinates.Frame frame() { return geometry.frame(); }
        @Override public double planarX(double lon) { return geometry.planarX(lon); }
        @Override public double planarY(double lat) { return geometry.planarY(lat); }
        @Override public double nodeX(int node) { return geometry.nodeX(node); }
        @Override public double nodeY(int node) { return geometry.nodeY(node); }
        @Override public int[] near(double x, double y, double radius) { return geometry.near(x, y, radius); }
        @Override public Fragment fragment(int index) { return road; }
        @Override public int adjacencyStart(int node) { return geometry.adjacencyStart(node); }
        @Override public int adjacencyEnd(int node) { return geometry.adjacencyEnd(node); }
        @Override public int adjacency(int k) { return geometry.adjacency(k); }
    }

    private static YandexRoute route(boolean annotations) {
        YandexRoute geometry = RoadTests.route(0, 0, 100, 0, 200, 0);
        List<YandexRoute.Section> sections = Arrays.asList(
                new YandexRoute.Section(0, 2, "STRAIGHT", "Route name", 10, 72));
        YandexRoute route = new YandexRoute("known-fields", geometry.lon, geometry.lat, sections,
                annotations ? new Double[]{25.0, 25.0} : null, null, new ArrayList<double[]>(),
                new ArrayList<double[]>(), null, null);
        route.highways = new ArrayList<>();
        if (annotations) {
            route.highways.add(new double[]{0, 2});
            route.laneSigns = Arrays.asList(new double[]{2, 3});
            route.tunnels.add(new double[]{0, 2});
        }
        return route;
    }

    private static RouteConverter.Converted convert(YandexRoute route, RoadNetwork.KnownAttributes attrs) {
        RouteConverter.Converted converted = RouteConverter.convert(route, Coordinates.Frame.WGS84, new Network(attrs, false));
        check(converted.roadMatch != null && converted.roadMatch.matchedM > 190, "fixture is matched before attribute assertions");
        return converted;
    }

    private static void independentFields() {
        YandexRoute route = route(true);
        RouteConverter.Converted classOnly = convert(route, new RoadNetwork.KnownAttributes(7, null, null, null, null, null));
        for (StockPath.Link link : RoadTests.links(classOnly)) {
            check(link.roadClass == 7, "confirmed road class survives a partial source");
            check(link.direction == 0 && link.formway == -1 && link.ownership == -1, "highway range and speed cannot fill absent fields");
            check(link.linkType == StockPath.LINK_TYPE_TUNNEL && link.laneNum == 0, "tunnel stays known; sign width is not a continuous lane count");
            check("Route name".equals(link.roadName), "partial metadata preserves source route name");
        }
        RouteConverter.Converted directionOnly = convert(route, new RoadNetwork.KnownAttributes(null, null, null, null, 0, null));
        for (StockPath.Link link : RoadTests.links(directionOnly)) {
            check(link.direction == 0, "confirmed two-way zero survives partial source");
            check(link.roadClass == -1 && link.linkType == 2 && link.laneNum == 0, "direction-only source cannot invent highway class or lanes");
        }
        RouteConverter.Converted zeroFields = convert(route, new RoadNetwork.KnownAttributes(null, 0, 0, 0, null, null));
        for (StockPath.Link link : RoadTests.links(zeroFields)) {
            check(link.formway == 0 && link.linkType == 0 && link.laneNum == 0, "explicit known zeros apply independently");
            check(link.direction == 0 && link.roadClass == -1, "known zeros do not invent other fields");
        }
        for (double speed : classOnly.linkSpeedKmh) check(Double.isNaN(speed), "partial fields do not enable historical speed");
        check(classOnly.segmentFreeFlowKmh[0] == 72, "route historical-speed context remains available independently");
        RouteConverter.Converted legacy = RouteConverter.convert(route, Coordinates.Frame.WGS84, new Network(null, true));
        for (StockPath.Link link : RoadTests.links(legacy))
            check(link.roadClass == 9 && link.formway == 15 && link.laneNum == 2 && link.direction == 0,
                    "legacy complete source still replaces its full attribute set");
        for (double speed : legacy.linkSpeedKmh) check(speed == 30, "legacy complete-source historical speed unchanged");
        for (StockPath.Link link : RoadTests.links(convert(route(false), RoadNetwork.KnownAttributes.NONE)))
            check(link.direction == 0 && link.roadClass == -1, "unknown partial direction -1 never reaches wire model");
        for (StockPath.Link link : RoadTests.links(convert(route(false), new RoadNetwork.KnownAttributes(null, null, null, null, 1, null))))
            check(link.direction == 1 && link.roadClass == -1 && link.ownership == -1,
                    "confirmed one-way survives without inventing class or ownership");
        for (StockPath.Link link : RoadTests.links(convert(route(false), new RoadNetwork.KnownAttributes(0, null, null, null, null, null))))
            check(link.roadClass == 0 && link.formway == -1 && link.laneNum == 0,
                    "an independently confirmed class zero is present without implying road form or lanes");
        for (int knownClass : new int[]{0, 6})
            for (StockPath.Link link : RoadTests.links(convert(route(true), new RoadNetwork.KnownAttributes(knownClass, null, null, null, null, null))))
                check(link.roadClass == knownClass && link.formway == -1 && link.laneNum == 0 && link.ownership == -1,
                        "explicitly known class 0/6 is allowed without highway-derived form, lanes or ownership");
        for (StockPath.Link link : RoadTests.links(convert(route(true), RoadNetwork.KnownAttributes.NONE)))
            check(link.roadClass == -1 && link.formway == -1 && link.laneNum == 0 && link.ownership == -1,
                    "highway at 90 km/h with an endpoint sign cannot replace missing class or continuous attributes");
    }

    private static void immutablePresence() {
        RoadNetwork.Fragment base = new Network(RoadNetwork.KnownAttributes.NONE, false).road;
        RoadNetwork.Fragment direction = base.withKnownAttributes(new RoadNetwork.KnownAttributes(null, null, null, null, 0, null));
        RoadNetwork.Fragment richer = direction.withKnownAttributes(new RoadNetwork.KnownAttributes(7, null, null, null, null, 0));
        check(base.direction == -1 && base.knownAttributes.direction == null, "overlay does not mutate original fragment");
        check(direction.roadClass == -1 && direction.knownAttributes.roadClass == null, "second overlay does not mutate previous copy");
        check(richer.direction == 0 && richer.roadClass == 7 && richer.ownership == 0, "null overlay preserves prior known zero");
        check(!richer.completeAttributes && richer.speedKmh == base.speedKmh, "metadata does not promote historical source completeness");
        check(richer.from == base.from && richer.to == base.to && richer.x == base.x && richer.forward == base.forward
                && richer.backward == base.backward && richer.legalForwardKmh == 54 && richer.legalBackwardKmh == 36,
                "metadata does not alter geometry, traversal or legal-speed evidence");
        boolean rejected = false;
        try { new RoadNetwork.KnownAttributes(null, null, null, null, -1, null); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "unknown direction must use absence rather than negative wire value");
        rejected = false;
        try { new RoadNetwork.KnownAttributes(null, null, null, null, 2, null); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "raw forward/reverse enum is not an exported direction boolean");
        rejected = false;
        try { new RoadNetwork.KnownAttributes(-1, null, null, null, null, null); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "class sentinel is absence, not a confirmed value");
    }

    private static Map<String,Object> last(DynamicTests.Rig rig, long topic) {
        List<Map<String,Object>> fires = rig.fires(topic);
        check(!fires.isEmpty(), "dynamic topic emitted");
        return fires.get(fires.size() - 1);
    }

    private static void dynamicLookup() {
        YandexRoute route = route(false);
        StockFeed.Config config = DynamicTests.config(); config.frame = Coordinates.Frame.WGS84;
        DynamicTests.Rig rig = new DynamicTests.Rig(1000000, config, true, true);
        rig.driver.setRoads(new Network(new RoadNetwork.KnownAttributes(7, null, null, null, 1, null), false));
        rig.observe(DynamicTests.guidance(1000100, route.routeId, route, .25, 10, 1));
        rig.observe(DynamicTests.guidance(1001100, route.routeId, route, .5, 10, 2));
        check(DynamicTests.field(last(rig, Topics.TRAFFIC_INFO), "roadClass") == 7, "partial confirmed class reaches TrafficInfo");
        check(DynamicTests.field(last(rig, Topics.SD_VEHICLE_LOCATION), "currentRoadOwnership") == 4294967295L, "class presence does not invent ownership");
        int routeSends = rig.fires(Topics.ROUTE).size();
        rig.driver.setRoads(new Network(new RoadNetwork.KnownAttributes(null, null, null, null, null, 0), false));
        rig.observe(DynamicTests.guidance(1002100, route.routeId, route, .75, 10, 3));
        rig.observe(DynamicTests.guidance(1003100, route.routeId, route, 1, 10, 4));
        check(DynamicTests.field(last(rig, Topics.TRAFFIC_INFO), "roadClass") == 4294967295L, "new partial source cannot retain stale old class");
        check(DynamicTests.field(last(rig, Topics.SD_VEHICLE_LOCATION), "currentRoadOwnership") == 0, "independently confirmed public ownership zero reaches position");
        check(rig.fires(Topics.ROUTE).size() == routeSends, "metadata refresh preserves frozen route publication");
    }

    public static void main(String[] args) {
        independentFields(); immutablePresence(); dynamicLookup();
        System.out.println("KnownRoadAttributesTest: " + checks + " checks, 0 failures");
    }
}
