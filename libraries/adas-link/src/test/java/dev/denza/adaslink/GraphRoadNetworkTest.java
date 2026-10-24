package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Synthetic native graph regression tests: physical degree, source conflicts and partial attributes. */
public final class GraphRoadNetworkTest {
    static int checks;
    static final double METRES = 6371008.8 * Math.PI / 180;
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    static double[][] points(double... xy) {
        double[][] out = new double[xy.length / 2][];
        for (int i = 0; i < out.length; i++) out[i] = new double[]{xy[i * 2] / METRES, xy[i * 2 + 1] / METRES};
        return out;
    }
    static GraphTile tile(long edge, long data, long from, long to, int f7, int f8, boolean reverse,
                          double[][] points, String name, Float speed, Integer zFrom, Integer zTo, long nonce) {
        byte[] fromLevel = zFrom == null ? new byte[0] : GraphTileTest.field(5, zFrom);
        byte[] toLevel = zTo == null ? new byte[0] : GraphTileTest.field(5, zTo);
        byte[] raw = GraphTileTest.concat(
            GraphTileTest.message(1, GraphTileTest.concat(GraphTileTest.field(1, from), GraphTileTest.packed(4, edge), fromLevel)),
            GraphTileTest.message(1, GraphTileTest.concat(GraphTileTest.field(1, to), GraphTileTest.packed(3, edge), toLevel)),
            GraphTileTest.message(2, GraphTileTest.concat(GraphTileTest.field(1, edge), GraphTileTest.field(2, data), GraphTileTest.field(3, reverse ? 1 : 0))),
            GraphTileTest.message(3, GraphTileTest.data(data, f7, f8, points, speed)),
            GraphTileTest.message(4, GraphTileTest.concat(GraphTileTest.field(1, 9), GraphTileTest.message(2, name.getBytes(java.nio.charset.StandardCharsets.UTF_8)))),
            GraphTileTest.field(99, nonce));
        return GraphTile.decode(raw, "synthetic-network");
    }
    static GraphTile line(long edge, long data, long from, long to, double... xy) {
        return tile(edge, data, from, to, 0, 0, false, points(xy), "Graph road", 15f, 0, 0, 0);
    }
    static YandexRoute route(String action, String name, Double limit) {
        return new YandexRoute("synthetic-native", new double[]{0, 200 / METRES}, new double[]{0, 0},
            Arrays.asList(new YandexRoute.Section(0, 1, action, name, 10, 72)), new Double[]{limit}, new double[0],
            Collections.<double[]>emptyList(), Collections.<double[]>emptyList(), new double[0], Collections.<YandexRoute.Camera>emptyList());
    }
    static GraphRoadNetwork network(YandexRoute route, GraphTile... tiles) { return new GraphRoadNetwork(Arrays.asList(tiles), route); }
    static int degree(GraphRoadNetwork roads, int node) { return roads.adjacencyEnd(node) - roads.adjacencyStart(node); }
    static RoadNetwork.Fragment fromX(GraphRoadNetwork roads, double x) {
        for (int i = 0; i < roads.fragmentCount(); i++) if (Math.abs(roads.fragment(i).x[0] - x) < 1) return roads.fragment(i);
        throw new AssertionError("no fragment at " + x);
    }
    static List<StockPath.Link> links(RouteConverter.Converted converted) {
        List<StockPath.Link> all = new ArrayList<>();
        for (StockPath.Segment segment : converted.path.segments) all.addAll(segment.links);
        return all;
    }
    static StockPath.Link at(RouteConverter.Converted converted, double scalar) {
        List<StockPath.Link> all = links(converted);
        for (int i = 0; i < all.size(); i++) if (converted.linkBeginScalar[i] <= scalar && scalar <= converted.linkEndScalar[i]) return all.get(i);
        throw new AssertionError("no converted link at " + scalar);
    }

    static void degreeAndCuts() {
        YandexRoute route = route("STRAIGHT", "Route name", 25.0);
        GraphTile forward = line(11, 100, 1, 2, 0, 0, 100, 0);
        GraphTile reverse = tile(12, 100, 2, 1, 0, 0, true, points(0, 0, 100, 0), "Graph road", 15f, 0, 0, 0);
        GraphTile onward = line(13, 101, 2, 3, 100, 0, 200, 0);
        GraphRoadNetwork chain = network(route, forward, reverse, onward);
        check(chain.fragmentCount() == 2, "reverse directed pair collapses to one physical fragment");
        RoadNetwork.Fragment first = fromX(chain, 0);
        check(first.forward && first.backward && degree(chain, first.to) == 2, "reverse edge does not double physical vertex degree");
        RouteConverter.Converted plain = RouteConverter.convert(route, Coordinates.Frame.WGS84, chain);
        check(plain.roadMatch != null && plain.roadMatch.matchedM > 190, "native graph chain matches source route");
        check(links(plain).size() == 1, "degree-two native geometry vertex is not a junction cut");
        GraphRoadNetwork junction = network(route, forward, reverse, onward, line(14, 102, 2, 4, 100, 0, 100, 100));
        check(degree(junction, fromX(junction, 0).to) == 3, "actual side road raises physical degree to three");
        RouteConverter.Converted cut = RouteConverter.convert(route, Coordinates.Frame.WGS84, junction);
        check(links(cut).size() == 2 && Math.abs(cut.linkEndM[0] - 100) < 1, "actual graph junction cuts source route at correct distance");
    }

    static void unknownAndConflicts() {
        YandexRoute route = route("STRAIGHT", null, null);
        GraphTile a = line(11, 100, 1, 2, 0, 0, 100, 0);
        GraphTile clipped = tile(11, 100, 1, 2, 0, 1, false, points(0, 0, 100, 0), "Graph road", null, 0, 0, 0);
        GraphTile b = line(12, 101, 2, 3, 100, 0, 200, 0);
        GraphRoadNetwork missing = network(route, clipped, b);
        check(fromX(missing, 0).to != fromX(missing, 100).from, "anonymous endpoint never joins known vertex at same coordinates");
        GraphRoadNetwork differentId = network(route, a, line(12, 101, 222, 3, 100, 0, 200, 0));
        check(fromX(differentId, 0).to != fromX(differentId, 100).from, "different vendor IDs do not join by coordinates");
        GraphRoadNetwork coordinateConflict = network(route, a, line(12, 101, 2, 3, 110, 0, 200, 0));
        check(fromX(coordinateConflict, 0).to != fromX(coordinateConflict, 110).from, "inconsistent coordinates isolate reused vertex ID");
        check(((Number) coordinateConflict.summary().get("conflictingVertices")).intValue() == 1, "coordinate conflict reported");
        GraphTile zDifferent = tile(12, 101, 2, 3, 0, 0, false, points(100, 0, 200, 0), "Graph road", 15f, 1, 0, 0);
        GraphRoadNetwork zConflict = network(route, a, zDifferent);
        check(fromX(zConflict, 0).to != fromX(zConflict, 100).from, "inconsistent known z levels isolate reused vertex ID");

        GraphTile known0 = tile(20, 888, 1, 2, 0, 0, false, points(0, 0, 100, 0), "Graph road", 15f, 0, 0, 0);
        GraphTile known1 = tile(20, 888, 1, 2, 0, 0, false, points(0, 0, 100, 0), "Graph road", 15f, 1, 0, 0);
        String firstKnown = known0.sha256().compareTo(known1.sha256()) < 0 ? known0.sha256() : known1.sha256();
        GraphTile unknownFirst = null;
        for (long n = 0; n < 10000; n++) {
            GraphTile candidate = tile(20, 888, 1, 2, 0, 0, false, points(0, 0, 100, 0), "Graph road", 15f, null, 0, n);
            if (candidate.sha256().compareTo(firstKnown) < 0) { unknownFirst = candidate; break; }
        }
        check(unknownFirst != null, "unknown z fixture sorts before two known levels");
        GraphRoadNetwork groupConflict = network(route, unknownFirst, known0, known1);
        check(groupConflict.fragmentCount() == 0, "group accumulates unknown→known z and rejects later contradictory known z");
        GraphTile limitA = tile(21, 889, 1, 2, 0, 0, false, points(0, 0, 100, 0), "Graph road", 15f, 0, 0, 0);
        GraphTile limitB = tile(21, 889, 1, 2, 0, 0, false, points(0, 0, 100, 0), "Graph road", 20f, 0, 0, 0);
        check(network(route, limitA, limitB).fragmentCount() == 0, "conflicting known limits for same direction reject physical group");
        GraphTile unknownLimit = tile(21, 889, 1, 2, 0, 0, false, points(0, 0, 100, 0), "Graph road", null, 0, 0, 0);
        GraphRoadNetwork supplemented = network(route, limitA, unknownLimit);
        check(supplemented.fragmentCount() == 1 && supplemented.fragment(0).legalForwardKmh == 54, "unknown limit is supplemented by consistent known evidence");
        GraphTile extreme = tile(30, 890, 1, 2, 0, 0, false, points(0, 0, 100, 0), "", Float.MAX_VALUE, 0, 0, 0);
        check(network(route, extreme).fragment(0).legalForwardKmh == 0, "extreme speed cannot overflow into a negative legal limit");
    }

    static void sourceAnnotations() {
        YandexRoute route = route("STRAIGHT", "Route name", 25.0);
        route.highways = Arrays.asList(new double[]{0, 1}); route.laneSigns = Arrays.asList(new double[]{1, 4});
        GraphTile a = tile(11, 100, 1, 2, 0, 0, false, points(0, 0, 100, 0), "Native name", 15f, 0, 0, 0);
        GraphTile b = tile(12, 101, 2, 3, 0, 0, false, points(100, 0, 200, 0), "Native name", 15f, 0, 0, 0);
        GraphRoadNetwork roads = network(route, a, b);
        RouteConverter.Converted base = RouteConverter.convert(route, Coordinates.Frame.WGS84);
        RouteConverter.Converted graph = RouteConverter.convert(route, Coordinates.Frame.WGS84, roads);
        check(graph.roadMatch != null && graph.roadMatch.matchedM > 190, "partial native graph actually matched before preservation assertions");
        List<StockPath.Link> all = links(graph);
        for (int i = 0; i < all.size(); i++) {
            StockPath.Link got = all.get(i), wanted = at(base, (graph.linkBeginScalar[i] + graph.linkEndScalar[i]) / 2);
            check(got.roadClass == wanted.roadClass && got.formway == wanted.formway && got.linkType == wanted.linkType, "partial graph preserves highway and route type annotations");
            check(got.laneNum == wanted.laneNum && got.direction == wanted.direction && got.ownership == wanted.ownership, "partial graph preserves lane-sign and direction source values");
            check("Route name".equals(got.roadName), "native name does not overwrite source section name");
            check(Double.isNaN(graph.linkSpeedKmh[i]), "native legal speed never becomes historical link speed");
        }
        check(route.speedLimits[0] == 25 && graph.roadMatch.legalKmhAt(.5) == 54, "source posted limit preserved separately from native legal limit");
        check(graph.segmentFreeFlowKmh[0] == 72, "source free-flow context survives native graph");
        YandexRoute roundabout = route("ENTER_ROUNDABOUT", "Ring", null);
        RouteConverter.Converted ring = RouteConverter.convert(roundabout, Coordinates.Frame.WGS84, network(roundabout, a, b));
        check(links(ring).get(0).formway == 4, "partial graph preserves source roundabout formway");
        YandexRoute unnamed = route("STRAIGHT", null, null);
        RouteConverter.Converted named = RouteConverter.convert(unnamed, Coordinates.Frame.WGS84, network(unnamed, a, b));
        check("Native name".equals(links(named).get(0).roadName), "native name fills only missing section name");
        GraphTile emptyA = tile(11, 100, 1, 2, 0, 0, false, points(0, 0, 100, 0), "", null, 0, 0, 0);
        GraphTile emptyB = tile(12, 101, 2, 3, 0, 0, false, points(100, 0, 200, 0), "", null, 0, 0, 0);
        RouteConverter.Converted empty = RouteConverter.convert(unnamed, Coordinates.Frame.WGS84, network(unnamed, emptyA, emptyB));
        check(empty.roadMatch != null && empty.roadMatch.matchedM > 190 && links(empty).get(0).roadName.equals(""), "missing graph names cannot crash attribute comparison");
    }

    static void driverRefreshWithoutRepublish() {
        YandexRoute route = route("STRAIGHT", "Route name", null);
        GraphTile graph = tile(1, 77, 1, 2, 0, 0, false, points(0, 0, 200, 0), "Route name", 15f, 0, 0, 0);
        GraphRoadNetwork roads = network(route, graph);
        RouteConverter.Converted alone = RouteConverter.convert(route, Coordinates.Frame.WGS84);
        RouteConverter.Converted withGraph = RouteConverter.convert(route, Coordinates.Frame.WGS84, roads);
        check(alone.contentKey.equals(withGraph.contentKey), "refresh regression has identical serialized path before/after graph");
        StockFeed.Config config = DynamicTests.config(); config.frame = Coordinates.Frame.WGS84; config.roadSpeedLimit = true;
        long start = 500000;
        DynamicTests.Rig rig = new DynamicTests.Rig(start, config, true, true);
        rig.observe(DynamicTests.guidance(start + 100, route.routeId, route, .25, 10, 1));
        rig.observe(DynamicTests.guidance(start + 1100, route.routeId, route, .3, 10, 2));
        int sends = rig.fires(Topics.ROUTE).size(), selections = rig.fires(Topics.PATH_SELECTED).size();
        check(sends > 0, "initial route published in local dry-run harness");
        rig.driver.setRoads(roads);
        rig.observe(DynamicTests.guidance(start + 2100, route.routeId, route, .4, 10, 3));
        rig.observe(DynamicTests.guidance(start + 3100, route.routeId, route, .5, 10, 4));
        List<Map<String, Object>> traffic = rig.fires(Topics.TRAFFIC_INFO);
        check(DynamicTests.field(traffic.get(traffic.size() - 1), "spdLmtSpeedValue") == 54, "same-path graph refresh updates current legal-limit fallback");
        check(((Number) rig.driver.conversionSummary().get("graphMatchedMetres")).doubleValue() > 190, "fresh graph matching reflected in conversion evidence");
        check(rig.fires(Topics.ROUTE).size() == sends && rig.fires(Topics.PATH_SELECTED).size() == selections, "graph-only refresh causes no route resend or reselection");
        YandexRoute posted = route("STRAIGHT", "Route name", 25.0);
        rig.observe(DynamicTests.guidance(start + 4100, posted.routeId, posted, .5, 10, 5));
        rig.observe(DynamicTests.guidance(start + 5100, posted.routeId, posted, .5, 10, 6));
        traffic = rig.fires(Topics.TRAFFIC_INFO);
        check(DynamicTests.field(traffic.get(traffic.size() - 1), "spdLmtSpeedValue") == 90, "fresh source per-edge limit takes priority over native graph fallback");
        rig.driver.setRoads(null);
        rig.observe(DynamicTests.guidance(start + 6100, route.routeId, route, .5, 10, 7));
        rig.observe(DynamicTests.guidance(start + 7100, route.routeId, route, .5, 10, 8));
        traffic = rig.fires(Topics.TRAFFIC_INFO);
        check(DynamicTests.field(traffic.get(traffic.size() - 1), "spdLmtSpeedValue") == 0, "removing graph refreshes same-path matching and clears old limit");
        check(rig.fires(Topics.ROUTE).size() == sends && rig.driver.naving(), "graph removal keeps selected route and guidance");
    }

    public static void main(String[] args) { degreeAndCuts(); unknownAndConflicts(); sourceAnnotations(); driverRefreshWithoutRepublish(); System.out.println("GraphRoadNetworkTest: " + checks + " checks, 0 failures"); }
}
