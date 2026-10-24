package dev.denza.adaslink;

import java.util.*;

/** Associates captured vector roads with routing fragments, independently of region.
 *
 * The renderer's class/fc retain their literal evidence and an explicit functional
 * approximation where supported; the numbers are never copied into Amap. Direction is
 * the stock one-way boolean, never the order of renderer vertices. A single nearby
 * midpoint is insufficient: the whole captured routing fragment is sampled, with
 * explicit coverage/ambiguity and graph-direction consistency checks.
 */
public final class VectorRoadEnrichment {
    private static final double CELL = 100, RADIUS = 3, STEP = 5;
    private static final double HEADING_COS = Math.cos(Math.toRadians(15));
    private static final int MAX_SEGMENTS = 200000, MAX_CELL_ENTRIES = 1000000, MAX_SAMPLES = 500000;
    private static final String[] LEVEL_KEYS = {"zlev", "min_zlev", "max_zlev", "z_level"};
    private static final Set<String> NON_DRIVING_CLASSES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "10_bike", "10_pedestrian", "7_pedestrian", "stairs", "crosswalk", "10_park", "construction")));
    private final RoadNetwork roads;
    private final List<Candidate> candidates = new ArrayList<>();
    private final Map<Long, List<Segment>> cells = new HashMap<>();
    private final Map<String, Integer> states = new TreeMap<>();
    private int samples, directionKnown, roadClassMapped;

    private static final class Candidate {
        final VectorRoadTile.RoadFeature feature;
        final String tileHash;
        Candidate(VectorRoadTile.RoadFeature feature, String tileHash) {
            this.feature = feature; this.tileHash = tileHash;
        }
    }
    private static final class CandidateKey {
        final boolean hasId;
        final long id;
        final String geometry;
        final Map<String, Object> attributes;
        final Map<String, String> attributeTypes;
        CandidateKey(VectorRoadTile.RoadFeature feature, String geometry) {
            hasId = feature.hasId; id = feature.id; this.geometry = geometry;
            attributes = feature.attributes; attributeTypes = feature.attributeTypes;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof CandidateKey)) return false;
            CandidateKey key = (CandidateKey) other;
            return hasId == key.hasId && (!hasId || id == key.id) && geometry.equals(key.geometry)
                    && attributes.equals(key.attributes) && attributeTypes.equals(key.attributeTypes);
        }
        @Override public int hashCode() { return Objects.hash(hasId, hasId ? id : 0L, geometry, attributes, attributeTypes); }
    }
    private static final class Segment {
        final int candidate;
        final double x, y, dx, dy, squared;
        Segment(int candidate, double x, double y, double dx, double dy) {
            this.candidate = candidate; this.x = x; this.y = y; this.dx = dx; this.dy = dy;
            squared = dx * dx + dy * dy;
        }
    }

    public static final class Result {
        public final String state;
        public final Integer direction, roadClass;
        public final Map<String, Object> evidence;
        private Result(String state, Integer direction, Map<String, Object> evidence) {
            this.state = state; this.direction = direction;
            this.roadClass = (Integer) evidence.get("amapRoadClass");
            this.evidence = Collections.unmodifiableMap(evidence);
        }
        public RoadNetwork.KnownAttributes attributes() {
            return new RoadNetwork.KnownAttributes(roadClass, null, null, null, direction, null);
        }
    }

    public VectorRoadEnrichment(RoadNetwork roads, List<VectorRoadTile> tiles) {
        if (roads == null || tiles == null) throw new IllegalArgumentException("road and vector snapshots required");
        this.roads = roads;
        // Only byte-identical geometry/attributes of the same renderer feature deduplicate.
        // Clipped variants, different IDs and disagreeing attributes remain competitors.
        Set<CandidateKey> seen = new HashSet<>();
        int segmentCount = 0, cellEntries = 0;
        for (VectorRoadTile tile : tiles) for (VectorRoadTile.RoadFeature feature : tile.features()) {
            if (feature.points.size() < 2) continue;
            String forward = pointsKey(feature.points, false), reverse = pointsKey(feature.points, true);
            CandidateKey key = new CandidateKey(feature, forward.compareTo(reverse) <= 0 ? forward : reverse);
            if (!seen.add(key)) continue;
            int id = candidates.size();
            candidates.add(new Candidate(feature, tile.sha256()));
            for (int p = 1; p < feature.points.size(); p++) {
                VectorRoadTile.Point a = feature.points.get(p - 1), b = feature.points.get(p);
                double x = roads.planarX(a.lon), y = roads.planarY(a.lat);
                Segment segment = new Segment(id, x, y, roads.planarX(b.lon) - x, roads.planarY(b.lat) - y);
                if (segment.squared < 1e-10) continue;
                if (++segmentCount > MAX_SEGMENTS) throw new IllegalArgumentException("vector segment budget exceeded");
                int minX = cell(Math.min(x, x + segment.dx) - RADIUS);
                int maxX = cell(Math.max(x, x + segment.dx) + RADIUS);
                int minY = cell(Math.min(y, y + segment.dy) - RADIUS);
                int maxY = cell(Math.max(y, y + segment.dy) + RADIUS);
                long count = ((long) maxX - minX + 1) * ((long) maxY - minY + 1);
                if (count < 0 || count > 4096 || count > MAX_CELL_ENTRIES - cellEntries)
                    throw new IllegalArgumentException("vector spatial index budget exceeded");
                cellEntries += (int) count;
                for (int cx = minX; cx <= maxX; cx++) for (int cy = minY; cy <= maxY; cy++)
                    cells.computeIfAbsent(cellKey(cx, cy), ignored -> new ArrayList<>()).add(segment);
            }
        }
    }

    public Result match(RoadNetwork.Fragment fragment, Integer graphBeginLevel, Integer graphEndLevel) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("fragment", fragment.index);
        evidence.put("fragmentLengthM", fragment.length());
        evidence.put("graphBeginLevel", graphBeginLevel);
        evidence.put("graphEndLevel", graphEndLevel);
        if (candidates.isEmpty()) return result("vector_tiles_unavailable", null, evidence);
        if (fragment.length() < 15) return result("insufficient_extent", null, evidence);
        Map<Integer, Double> weights = new HashMap<>();
        double total = 0, ambiguous = 0;
        int count = 0;
        for (int p = 1; p < fragment.x.length; p++) {
            double dx = fragment.x[p] - fragment.x[p - 1], dy = fragment.y[p] - fragment.y[p - 1];
            double length = Math.hypot(dx, dy);
            if (length < 1e-6) continue;
            int n = (int) Math.ceil(length / STEP);
            if (n > MAX_SAMPLES - samples) throw new IllegalArgumentException("vector matching sample budget exceeded");
            samples += n; count += n;
            double weight = length / n;
            for (int i = 0; i < n; i++) {
                double t = (i + .5) / n;
                double x = fragment.x[p - 1] + dx * t, y = fragment.y[p - 1] + dy * t;
                Set<Integer> matches = new HashSet<>();
                List<Segment> nearby = cells.get(cellKey(cell(x), cell(y)));
                if (nearby != null) for (Segment s : nearby) {
                    double u = Math.max(0, Math.min(1, ((x - s.x) * s.dx + (y - s.y) * s.dy) / s.squared));
                    if (Math.hypot(x - s.x - u * s.dx, y - s.y - u * s.dy) > RADIUS) continue;
                    if (Math.abs(dx * s.dx + dy * s.dy) / (length * Math.sqrt(s.squared)) < HEADING_COS) continue;
                    matches.add(s.candidate);
                }
                total += weight;
                if (matches.size() > 1) ambiguous += weight;
                for (int id : matches) weights.put(id, weights.getOrDefault(id, 0.0) + weight);
            }
        }
        evidence.put("sampleCount", count);
        evidence.put("candidateCount", weights.size());
        evidence.put("ambiguityFraction", total > 0 ? ambiguous / total : 0);
        if (weights.isEmpty()) return result("unmatched", null, evidence);
        int best = -1;
        for (int id : weights.keySet())
            if (best < 0 || weights.get(id) > weights.get(best) || (weights.get(id).equals(weights.get(best)) && id < best)) best = id;
        Candidate candidate = candidates.get(best);
        evidence.put("sampledCoverage", weights.get(best) / total);
        evidence.put("vectorTileSha256", candidate.tileHash);
        evidence.put("vectorFeatureId", candidate.feature.hasId ? Long.toUnsignedString(candidate.feature.id) : null);
        evidence.put("vectorFeatureIndex", candidate.feature.featureIndex);
        evidence.put("vectorPartIndex", candidate.feature.partIndex);
        evidence.put("literalAttributes", candidate.feature.attributes);
        if (ambiguous / total > .01) return result("ambiguous_geometry", null, evidence);
        if (weights.get(best) / total < .95) return result("partial_geometry", null, evidence);
        if (count < 3) return result("insufficient_extent", null, evidence);
        // Level namespaces are not equated. Structured/non-ground features await
        // separate evidence even when their 2D geometry is the nearest candidate.
        Map<String, Object> attributes = candidate.feature.attributes;
        Object roadClass = attributes.get("class");
        if (NON_DRIVING_CLASSES.contains(roadClass)) return result("non_driving_road_class", null, evidence);
        if (!drivingClass(roadClass)) return result("road_class_mode_unresolved", null, evidence);
        if (graphBeginLevel == null || graphEndLevel == null) return result("graph_levels_unavailable", null, evidence);
        for (String key : LEVEL_KEYS) if (!attributes.containsKey(key) || attributes.get(key) == null)
            return result("vector_levels_unavailable", null, evidence);
        if (!"none".equals(attributes.get("struct")) || nonzero(graphBeginLevel) || nonzero(graphEndLevel)
                || nonzeroLiteral(attributes.get("zlev")) || nonzeroLiteral(attributes.get("min_zlev"))
                || nonzeroLiteral(attributes.get("max_zlev")) || nonzeroLiteral(attributes.get("z_level")))
            return result("level_or_structure_unresolved", null, evidence);
        YandexRoadClass.Decision mappedClass = YandexRoadClass.map(attributes);
        evidence.put("roadClassMapping", mappedClass.evidence());
        evidence.put("amapRoadClass", mappedClass.roadClass);
        Object literal = attributes.get("oneway");
        Integer direction = "0".equals(literal) ? Integer.valueOf(0)
                : "1".equals(literal) ? Integer.valueOf(1) : null;
        if (direction == null) return result("oneway_literal_unresolved", null, evidence);
        boolean graphOneWay = fragment.forward != fragment.backward;
        boolean graphTwoWay = fragment.forward && fragment.backward;
        if ((direction == 1 && !graphOneWay) || (direction == 0 && !graphTwoWay))
            return result("graph_direction_conflict_or_incomplete", null, evidence);
        // Class and direction are independent: an unresolved oneway literal above
        // does not discard an otherwise supported functional approximation.
        return result("direction_supported", direction, evidence);
    }

    private Result result(String state, Integer direction, Map<String, Object> evidence) {
        states.put(state, states.getOrDefault(state, 0) + 1);
        if (direction != null) directionKnown++;
        if (evidence.get("amapRoadClass") != null) roadClassMapped++;
        evidence.put("state", state); evidence.put("direction", direction);
        return new Result(state, direction, evidence);
    }
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "yandex-vector-and-routing-graph");
        out.put("vectorCandidates", candidates.size()); out.put("states", new TreeMap<>(states));
        out.put("directionKnown", directionKnown); out.put("roadClassMapped", roadClassMapped);
        out.put("roadClassMapping", YandexRoadClass.VERSION);
        out.put("roadClassQuality", "functional_approximation");
        out.put("maximumDistanceM", RADIUS); out.put("maximumUndirectedHeadingDegrees", 15);
        out.put("sampleStepM", STEP); out.put("minimumCoverage", .95); out.put("maximumAmbiguity", .01);
        return out;
    }
    private static boolean nonzero(Integer value) { return value != null && value != 0; }
    private static boolean drivingClass(Object value) {
        if (!(value instanceof String)) return false;
        String road = (String) value;
        if (road.endsWith("_link")) road = road.substring(0, road.length() - 5);
        return road.length() == 1 && road.charAt(0) >= '1' && road.charAt(0) <= '9';
    }
    private static boolean nonzeroLiteral(Object value) {
        if (value == null) return false;
        return !(value instanceof Number && ((Number) value).doubleValue() == 0) && !"0".equals(value);
    }
    private static String pointsKey(List<VectorRoadTile.Point> points, boolean reverse) {
        StringBuilder out = new StringBuilder();
        for (int n = 0; n < points.size(); n++) {
            VectorRoadTile.Point p = points.get(reverse ? points.size() - 1 - n : n);
            out.append(Double.toHexString(p.lon)).append(',').append(Double.toHexString(p.lat)).append(';');
        }
        return out.toString();
    }
    private static int cell(double coordinate) {
        double value = Math.floor(coordinate / CELL);
        if (!Double.isFinite(value) || value <= Integer.MIN_VALUE || value >= Integer.MAX_VALUE)
            throw new IllegalArgumentException("vector spatial coordinate bounds");
        return (int) value;
    }
    private static long cellKey(int x, int y) { return ((long) x << 32) | (y & 0xffffffffL); }
}
