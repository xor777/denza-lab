package dev.denza.adaslink;

import java.util.*;
import static dev.denza.adaslink.VectorRoadTileTest.*;

/** Independent synthetic contexts, candidate ambiguity and stock-field propagation. */
public final class VectorRoadEnrichmentTest {
    static int assertions;
    static void expect(boolean value, String why) { assertions++; if (!value) throw new AssertionError(why); }
    static VectorRoadTile vector(int x, int y, String oneWay, String structure, boolean reverse, boolean parallel) {
        return typedVector(x, y, oneWay, structure, reverse, parallel);
    }
    static VectorRoadTile typedVector(int x, int y, Object oneWay, String structure, boolean reverse, boolean parallel) {
        return attributedVector(x, y, oneWay, structure, reverse, parallel, "4", true);
    }
    static VectorRoadTile attributedVector(int x, int y, Object oneWay, String structure, boolean reverse,
                                          boolean parallel, String roadClass, boolean levels) {
        byte[] keys = msg(4, join(str(1, "class"), str(1, "fc"), str(1, "oneway"), str(1, "struct"),
                levels ? join(str(1, "zlev"), str(1, "min_zlev"), str(1, "max_zlev"), str(1, "z_level")) : new byte[0]));
        byte[] oneWayValue = oneWay instanceof String ? str(1, (String) oneWay) : v(3, ((Number) oneWay).longValue());
        byte[] values = join(msg(5, str(1, roadClass)), msg(5, v(3, 4)), msg(5, oneWayValue), msg(5, str(1, structure)),
                levels ? msg(5, v(3, 0)) : new byte[0]);
        byte[] attributes = msg(6, join(v(1, 0), levels ? packed(2, 0, 1, 2, 3, 4, 4, 4, 4) : packed(2, 0, 1, 2, 3)));
        byte[] line = join(feature(line(1), 55), attributes);
        int a = reverse ? 5000 : -5000, delta = reverse ? -10000 : 10000;
        byte[] coordinates = parallel ? coords(a, 0, delta, 0, a, 0, delta, 0) : coords(a, 0, delta, 0);
        byte[] lengths = parallel ? packed(3, 2, 2) : packed(3, 2);
        byte[] roads = parallel ? layer("road", line, join(feature(line(1), 56), attributes)) : layer("road", line);
        VectorRoadTile.TileId id = new VectorRoadTile.TileId(x, y, 15);
        return VectorRoadTile.decode(join(v(1, 14), coordinates, lengths, keys, values, roads), id, id, id);
    }
    static YandexRoute route(VectorRoadTile tile, boolean reverse) {
        VectorRoadTile.RoadFeature feature = tile.features().get(0);
        VectorRoadTile.Point a = feature.points.get(reverse ? 1 : 0), b = feature.points.get(reverse ? 0 : 1);
        return new YandexRoute("generic-vector-test", new double[]{a.lon, b.lon}, new double[]{a.lat, b.lat},
                Arrays.asList(new YandexRoute.Section(0, 1, "STRAIGHT", "Route", 20, 50)), new Double[]{null},
                new double[0], Collections.<double[]>emptyList(), Collections.<double[]>emptyList(), new double[0],
                Collections.<YandexRoute.Camera>emptyList());
    }
    static GraphRoadNetwork roads(VectorRoadTile vector, boolean twoWay, boolean reverse, Integer level, List<VectorRoadTile> vectors) {
        YandexRoute route = route(vector, reverse);
        double[][] points = {{route.lon[0], route.lat[0]}, {route.lon[1], route.lat[1]}};
        List<GraphTile> graph = new ArrayList<>();
        graph.add(GraphRoadNetworkTest.tile(11, 100, 1, 2, 0, 0, false, points, "Road", 15f, level, level, 0));
        if (twoWay) graph.add(GraphRoadNetworkTest.tile(12, 100, 2, 1, 0, 0, true, points, "Road", 15f, level, level, 0));
        return new GraphRoadNetwork(graph, route, vectors);
    }
    static String state(GraphRoadNetwork roads) { return (String) roads.vectorEvidence().get(0).get("state"); }
    static void core() {
        for (int[] location : new int[][]{{20000, 10000}, {10000, 20000}, {30000, 24000}}) {
            VectorRoadTile one = vector(location[0], location[1], "1", "none", false, false);
            GraphRoadNetwork known = roads(one, false, false, 0, Arrays.asList(one));
            expect("direction_supported".equals(state(known)), "oneway match in arbitrary TileId");
            expect(known.fragment(0).knownAttributes.direction == 1 && !known.fragment(0).completeAttributes, "individual fields do not claim complete attributes");
            expect(known.fragment(0).knownAttributes.roadClass == 7, "renderer class4 maps to functional main road, never Amap class4");
            expect("functional_approximation".equals(((Map<?,?>) known.vectorEvidence().get(0).get("roadClassMapping")).get("quality")),
                    "mapped class carries its approximation evidence");
            RouteConverter.Converted converted = RouteConverter.convert(route(one, false), Coordinates.Frame.WGS84, known);
            expect(GraphRoadNetworkTest.links(converted).get(0).direction == 1, "one-way reaches stock route with unknown lane count");
            expect(GraphRoadNetworkTest.links(converted).get(0).roadClass == 7, "functional class reaches stock route independently of lanes");
            expect(GraphRoadNetworkTest.links(converted).get(0).laneNum == 0, "oneway does not invent lane count");
            GraphRoadNetwork reversed = roads(one, false, true, 0, Arrays.asList(one));
            expect(reversed.fragment(0).knownAttributes.direction == 1, "renderer vertex order does not change one-way boolean");
            VectorRoadTile two = vector(location[0], location[1], "0", "none", false, false);
            GraphRoadNetwork paired = roads(two, true, false, 0, Arrays.asList(two));
            expect(paired.fragment(0).knownAttributes.direction == 0, "explicit two-way is retained as known zero");
            expect("graph_direction_conflict_or_incomplete".equals(state(roads(two, false, false, 0, Arrays.asList(two)))), "missing reverse graph edge is not one-way proof");
            expect("graph_direction_conflict_or_incomplete".equals(state(roads(one, true, false, 0, Arrays.asList(one)))), "contradictory graph blocks transfer");
            expect("direction_supported".equals(state(roads(one, false, false, 0, Arrays.asList(one, one)))), "identical repeated tile not ambiguous");
            VectorRoadTile parallel = vector(location[0], location[1], "1", "none", false, true);
            expect("ambiguous_geometry".equals(state(roads(one, false, false, 0, Arrays.asList(parallel)))), "overlapping distinct feature identities remain ambiguous");
            VectorRoadTile bridge = vector(location[0], location[1], "1", "bridge", false, false);
            expect("level_or_structure_unresolved".equals(state(roads(bridge, false, false, 1, Arrays.asList(bridge)))), "structure not silently matched in 2D");
            expect("vector_tiles_unavailable".equals(state(roads(one, false, false, 0, Collections.<VectorRoadTile>emptyList()))), "missing tiles explicit");
            VectorRoadTile distant = vector(location[0] + 10, location[1], "1", "none", false, false);
            expect("unmatched".equals(state(roads(one, false, false, 0, Arrays.asList(distant)))), "old geographic data does not enrich new route");
            VectorRoadTile invalidLiteral = vector(location[0], location[1], "unknown", "none", false, false);
            expect("oneway_literal_unresolved".equals(state(roads(invalidLiteral, false, false, 0, Arrays.asList(invalidLiteral)))), "unrecognized literal never coerced to false");
            expect(roads(invalidLiteral, false, false, 0, Arrays.asList(invalidLiteral)).fragment(0).knownAttributes.roadClass == 7,
                    "missing one-way knowledge does not discard independently mapped road class");
        }
        VectorRoadTile literal = vector(20000, 10000, "1", "none", false, false);
        VectorRoadTile numeric = typedVector(20000, 10000, 1, "none", false, false);
        for (List<VectorRoadTile> order : Arrays.asList(Arrays.asList(literal, numeric), Arrays.asList(numeric, literal))) {
            GraphRoadNetwork conflicting = roads(literal, false, false, 0, order);
            expect("ambiguous_geometry".equals(state(conflicting)), "different literal types remain competing features in either order");
            expect(conflicting.fragment(0).knownAttributes.direction == null, "typed conflict cannot publish a direction");
        }
        GraphRoadNetwork missingGraphLevel = roads(literal, false, false, null, Arrays.asList(literal));
        expect("graph_levels_unavailable".equals(state(missingGraphLevel)), "missing graph levels are not zero");
        expect(missingGraphLevel.fragment(0).knownAttributes.direction == null, "unknown graph level cannot assign direction");
        VectorRoadTile noVectorLevel = attributedVector(20000, 10000, "1", "none", false, false, "4", false);
        expect("vector_levels_unavailable".equals(state(roads(noVectorLevel, false, false, 0, Arrays.asList(noVectorLevel)))),
                "missing vector levels are not ground-level evidence");
        for (String nonDriving : Arrays.asList("10_bike", "10_pedestrian", "7_pedestrian", "stairs", "crosswalk", "10_park", "construction")) {
            VectorRoadTile source = attributedVector(20000, 10000, "1", "none", false, false, nonDriving, true);
            GraphRoadNetwork rejected = roads(source, false, false, 0, Arrays.asList(source));
            expect("non_driving_road_class".equals(state(rejected)), "non-driving feature cannot classify motor traversal: " + nonDriving);
            expect(rejected.fragment(0).knownAttributes.direction == null, "non-driving one-way remains raw evidence");
        }
        VectorRoadTile unknownClass = attributedVector(20000, 10000, "1", "none", false, false, "future_kind", true);
        expect("road_class_mode_unresolved".equals(state(roads(unknownClass, false, false, 0, Arrays.asList(unknownClass)))),
                "new class vocabulary waits for mode semantics");
    }
    public static void main(String[] args) {
        core(); System.out.println("VectorRoadEnrichmentTest: " + assertions + " checks");
    }
}
