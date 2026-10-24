package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Whole selected route to renderer literals, independent of tile delivery, files or routing cache.
 * Association is sampled geometry, never native edge identity or continuous coverage proof.
 * Each nonzero original edge includes both endpoints, with sample spacing at most five metres.
 * Field consensus includes every matching feature; an unknown candidate prevents that field
 * becoming known. Different fields may remain independently known across ambiguous geometry.
 * A work/resource truncation invalidates all attributes because an unseen competitor can disagree.
 */
public final class RouteVectorAttributes {
    public static final String VERSION = "yandex-route-vector-attributes-v1";
    private static final double R = 6371008.8, DEG = Math.PI / 180, STEP = 5, DISTANCE = 3;
    private static final double CELL = 100, CHUNK = 50, HEADING_COS = Math.cos(15 * DEG);
    private static final int CLASS_UNKNOWN = 1, CLASS_CONFLICT = 2, DIR_UNKNOWN = 4, DIR_CONFLICT = 8;
    private static final int LEVEL_CONFLICT = 16, LITERAL_CONFLICT = 32;
    private static final String[] LEVEL_KEYS = {"struct", "zlev", "min_zlev", "max_zlev", "z_level"};

    public static final class Limits {
        public final int maxEdges, maxSamples, maxTiles, maxFeatures, maxSegments, maxSpans, maxEvidence;
        public final long maxCandidateTests, maxCellVisits, maxLiteralBytes;
        public Limits() { this(100000, 500000, 4096, 1000000, 2000000, 20000000L, 20000000L, 100000, 128, 64000000L); }
        public Limits(int edges, int samples, int tiles, int features, int segments,
                      long tests, long cells, int spans, int evidence, long literalBytes) {
            if (edges < 1 || edges > 100000 || samples < 1 || samples > 500000 || tiles < 1 || tiles > 4096
                    || features < 1 || features > 1000000 || segments < 1 || segments > 2000000
                    || tests < 1 || tests > 20000000L || cells < 1 || cells > 20000000L
                    || spans < 1 || spans > 100000 || evidence < 0 || evidence > 128
                    || literalBytes < 1 || literalBytes > 64000000L)
                throw new IllegalArgumentException("bounded route vector limits required");
            maxEdges = edges; maxSamples = samples; maxTiles = tiles; maxFeatures = features;
            maxSegments = segments; maxCandidateTests = tests; maxCellVisits = cells;
            maxSpans = spans; maxEvidence = evidence; maxLiteralBytes = literalBytes;
        }
    }
    public static final class Edge {
        public final int index, samples, matchedSamples, ambiguousSamples, conflictSamples;
        public final double lengthM;
        public final String state;
        public final RoadNetwork.KnownAttributes attributes;
        public final boolean exactNativeId = false;
        private Edge(int index, double length, int samples, int matched, int ambiguous, int conflicts,
                     String state, RoadNetwork.KnownAttributes attributes) {
            this.index = index; lengthM = length; this.samples = samples; matchedSamples = matched;
            ambiguousSamples = ambiguous; conflictSamples = conflicts; this.state = state; this.attributes = attributes;
        }
    }
    public static final class Span {
        /** SDK scalar convention: original edge index plus fraction. Transition bins stay unknown. */
        public final double begin, end;
        public final int originalEdge, maximumCandidates;
        public final String state;
        public final RoadNetwork.KnownAttributes attributes;
        public final boolean ambiguous, levelStructureConflict, literalConflict, exactNativeId = false;
        private Span(double begin, double end, int edge, int candidates, String state,
                     RoadNetwork.KnownAttributes attributes, boolean ambiguous, boolean levelConflict, boolean literalConflict) {
            this.begin = begin; this.end = end; originalEdge = edge; maximumCandidates = candidates;
            this.state = state; this.attributes = attributes; this.ambiguous = ambiguous;
            levelStructureConflict = levelConflict; this.literalConflict = literalConflict;
        }
    }
    public static final class Result implements RouteAttributes {
        public final String routeId, geometryFingerprint;
        public final List<Edge> edges;
        public final List<Span> spans;
        public final List<Map<String,Object>> evidence;
        public final boolean truncated, exactNativeId = false;
        public final int knownEdges, unmatchedEdges, conflictEdges, knownSamples, unmatchedSamples, conflictSamples;
        private final double[] cuts;
        private final Map<String,Object> summary;
        private Result(Builder b, List<Edge> edges, List<Span> spans, int known, int unmatched, int conflict,
                       int knownSamples, int unmatchedSamples, int conflictSamples) {
            routeId = b.routeId; geometryFingerprint = b.geometryIdentity; this.edges = Collections.unmodifiableList(edges);
            this.spans = Collections.unmodifiableList(spans); truncated = b.truncated;
            evidence = Collections.unmodifiableList(new ArrayList<>(b.evidence));
            knownEdges = known; unmatchedEdges = unmatched; conflictEdges = conflict;
            this.knownSamples = knownSamples; this.unmatchedSamples = unmatchedSamples; this.conflictSamples = conflictSamples;
            List<Double> boundaries = new ArrayList<>();
            for (int i = 1; i < spans.size(); i++) {
                Span before = spans.get(i - 1), after = spans.get(i);
                if (!same(before.attributes, after.attributes) && before.end == after.begin) boundaries.add(after.begin);
            }
            cuts = new double[boundaries.size()]; for (int i = 0; i < cuts.length; i++) cuts[i] = boundaries.get(i);
            Map<String,Object> s = new LinkedHashMap<>();
            s.put("schema", VERSION); s.put("routeId", routeId); s.put("routeGeometry", b.geometryIdentity);
            s.put("edges", edges.size()); s.put("spans", spans.size()); s.put("cuts", cuts.length);
            s.put("samples", b.samples.size()); s.put("knownEdges", knownEdges); s.put("unmatchedEdges", unmatchedEdges);
            s.put("conflictEdges", conflictEdges); s.put("knownSamples", knownSamples);
            s.put("unmatchedSamples", unmatchedSamples); s.put("conflictSamples", conflictSamples);
            int ambiguous = 0, levels = 0, literals = 0, fields = 0, partial = 0, unsampled = 0;
            for (Edge edge : edges) { ambiguous += edge.ambiguousSamples; if (edge.samples == 0) unsampled++;
                else if (edge.matchedSamples > 0 && edge.matchedSamples < edge.samples) partial++; }
            for (Sample sample : b.samples) {
                if ((sample.flags & LEVEL_CONFLICT) != 0) levels++;
                if ((sample.flags & LITERAL_CONFLICT) != 0) literals++;
                if ((sample.flags & (CLASS_CONFLICT | DIR_CONFLICT)) != 0) fields++;
            }
            s.put("ambiguousSamples", ambiguous); s.put("levelStructureConflictSamples", levels);
            s.put("literalConflictSamples", literals); s.put("fieldConflictSamples", fields);
            s.put("partialEdges", partial); s.put("unsampledEdges", unsampled);
            s.put("truncatedEdges", truncated ? edges.size() : 0);
            s.put("truncated", truncated); s.put("truncationReason", b.truncationReason);
            s.put("tiles", b.tiles.size()); s.put("features", b.features); s.put("segments", b.segments);
            s.put("candidateTests", b.tests); s.put("cellVisits", b.visits);
            s.put("maximumDistanceM", DISTANCE); s.put("maximumUndirectedHeadingDegrees", 15);
            s.put("maximumSampleStepM", STEP); s.put("quality", "sampled_renderer_field_consensus");
            s.put("exactNativeId", false); s.put("continuousCoverageProved", false);
            s.put("levelNamespaceEquivalence", false); s.put("routeLevelAssociationKnown", false);
            s.put("continuousLaneCountKnown", false); s.put("laneDecision", "no_continuous_lane_source");
            s.put("formwayOwnershipAccessDecision", "not_inferred_from_renderer_levels_or_structure");
            s.put("roadClassMapping", YandexRoadClass.VERSION);
            s.put("decisions", Collections.unmodifiableMap(new LinkedHashMap<>(b.decisions)));
            s.put("retainedEvidence", evidence.size()); s.put("droppedEvidence", b.droppedEvidence);
            summary = Collections.unmodifiableMap(s);
        }
        public RoadNetwork.KnownAttributes attributes(int originalEdge) { return edges.get(originalEdge).attributes; }
        /** Piecewise sampled attributes. A route end position uses the preceding final span. */
        public RoadNetwork.KnownAttributes attributesAt(double scalar) {
            if (!Double.isFinite(scalar) || scalar < 0 || scalar > edges.size()) throw new IllegalArgumentException("route scalar");
            if (scalar == edges.size()) return spans.isEmpty() ? RoadNetwork.KnownAttributes.NONE : spans.get(spans.size() - 1).attributes;
            int low = 0, high = spans.size() - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1; Span span = spans.get(mid);
                if (scalar < span.begin) high = mid - 1;
                else if (scalar >= span.end) low = mid + 1;
                else return span.attributes;
            }
            return RoadNetwork.KnownAttributes.NONE;
        }
        /** Consensus over every overlapping span; a gap or field change keeps that field unknown. */
        public RoadNetwork.KnownAttributes attributesBetween(double begin, double end) {
            if (!Double.isFinite(begin) || !Double.isFinite(end) || begin < 0 || end <= begin || end > edges.size())
                throw new IllegalArgumentException("nonempty route scalar interval");
            int low = 0, high = spans.size();
            while (low < high) { int mid = (low + high) >>> 1; if (spans.get(mid).end <= begin) low = mid + 1; else high = mid; }
            RoadNetwork.KnownAttributes common = null; double through = begin;
            for (int i = low; i < spans.size() && spans.get(i).begin < end; i++) {
                Span span = spans.get(i);
                if (span.begin > through) return RoadNetwork.KnownAttributes.NONE;
                common = common == null ? span.attributes : consensus(common, span.attributes);
                through = Math.max(through, span.end); if (through >= end) break;
            }
            return through < end || common == null ? RoadNetwork.KnownAttributes.NONE : common;
        }
        public boolean matches(YandexRoute route) {
            if (route == null || !routeId.equals(route.routeId)) return false;
            try { return geometryFingerprint.equals(RouteVectorAttributes.geometryFingerprint(route)); }
            catch (IllegalArgumentException invalid) { return false; }
        }
        public double[] cuts() { return cuts.clone(); }
        public Map<String,Object> summary() { return summary; }
    }

    private static final class Sample {
        final double lon, lat, dx, dy;
        int count, stamp, flags, roadClass = -1, direction = -1, levelId = -1, literalId = -1;
        Sample(double lon, double lat, double dx, double dy) { this.lon = lon; this.lat = lat; this.dx = dx; this.dy = dy; }
        void add(int roadClass, int direction, int level, int literal, int stamp) {
            if (this.stamp == stamp) return;
            this.stamp = stamp; count++;
            if (roadClass < 0) flags |= CLASS_UNKNOWN;
            else if (this.roadClass < 0) this.roadClass = roadClass;
            else if (this.roadClass != roadClass) flags |= CLASS_CONFLICT;
            if (direction < 0) flags |= DIR_UNKNOWN;
            else if (this.direction < 0) this.direction = direction;
            else if (this.direction != direction) flags |= DIR_CONFLICT;
            if (levelId < 0) levelId = level; else if (levelId != level) flags |= LEVEL_CONFLICT;
            if (literalId < 0) literalId = literal; else if (literalId != literal) flags |= LITERAL_CONFLICT;
        }
        RoadNetwork.KnownAttributes attributes() {
            return known(count == 0 || (flags & (CLASS_UNKNOWN | CLASS_CONFLICT)) != 0 ? null : roadClass,
                    count == 0 || (flags & (DIR_UNKNOWN | DIR_CONFLICT)) != 0 ? null : direction);
        }
    }
    private static final class Ints {
        int[] values = new int[4]; int size;
        void add(int value) { if (size == values.length) values = Arrays.copyOf(values, size * 2); values[size++] = value; }
    }
    private static final class Budget extends RuntimeException {
        final String reason;
        Budget(String reason) { this.reason = reason; }
    }

    public static final class Builder {
        private final String routeId, geometryIdentity;
        private final Limits limits;
        private final double[] lon, lat, lengths;
        private final int[] starts;
        private final List<Sample> samples = new ArrayList<>();
        private final Map<Long,Ints> cells = new HashMap<>();
        private final Set<String> tiles = new HashSet<>();
        private final Map<String,Integer> levels = new HashMap<>(), literals = new HashMap<>();
        private final Map<String,Long> decisions = new LinkedHashMap<>();
        private final List<Map<String,Object>> evidence = new ArrayList<>();
        private int features, segments, droppedEvidence;
        private long tests, visits, literalBytes;
        private boolean truncated;
        private String truncationReason;
        private Result result;

        public Builder(YandexRoute route) { this(route, new Limits()); }
        public Builder(YandexRoute route, Limits limits) {
            if (route == null || limits == null || route.lon == null || route.lat == null
                    || route.lon.length != route.lat.length || route.edges() < 1 || route.edges() > limits.maxEdges)
                throw new IllegalArgumentException("bounded complete route geometry required");
            this.limits = limits; routeId = route.routeId;
            lon = route.lon.clone(); lat = route.lat.clone(); lengths = new double[route.edges()]; starts = new int[route.edges() + 1];
            long count = 0;
            for (int i = 0; i < lon.length; i++) {
                coordinate(lon[i], lat[i]);
                if (i == 0) continue;
                double length = Coordinates.distance(lon[i - 1], lat[i - 1], lon[i], lat[i]); lengths[i - 1] = length;
                if (length > 1e-6) count += (long)Math.ceil(samplingLength(lon[i - 1], lat[i - 1], lon[i], lat[i]) / STEP) + 1;
                if (count > limits.maxSamples) truncate("route_sample_budget");
            }
            geometryIdentity = geometryFingerprint(lon, lat);
            if (truncated) return;
            for (int edge = 0; edge < lengths.length; edge++) {
                starts[edge] = samples.size(); double length = lengths[edge];
                if (length <= 1e-6) continue;
                int n = (int)Math.ceil(samplingLength(lon[edge], lat[edge], lon[edge + 1], lat[edge + 1]) / STEP);
                double longitudeDelta = delta(lon[edge], lon[edge + 1]);
                for (int i = 0; i <= n; i++) {
                    double t = (double)i / n, latitude = lat[edge] + (lat[edge + 1] - lat[edge]) * t;
                    Sample sample = new Sample(wrap(lon[edge] + longitudeDelta * t), latitude,
                            longitudeDelta * DEG * R * Math.cos(latitude * DEG), (lat[edge + 1] - lat[edge]) * DEG * R);
                    int index = samples.size(); samples.add(sample);
                    double[] xyz = xyz(sample.lon, sample.lat); long key = key(cell(xyz[0]), cell(xyz[1]), cell(xyz[2]));
                    Ints values = cells.get(key); if (values == null) { values = new Ints(); cells.put(key, values); } values.add(index);
                }
            }
            starts[lengths.length] = samples.size();
        }

        /** Consumes the tile immediately; no tile, feature or path object is retained. */
        public boolean accept(VectorRoadTile tile) {
            if (result != null) throw new IllegalStateException("route vector builder already finished");
            if (tile == null) throw new IllegalArgumentException("vector tile required");
            if (truncated) return false;
            String fingerprint = tile.fingerprint(); if (tiles.contains(fingerprint)) return true;
            if (tiles.size() >= limits.maxTiles) { truncate("tile_budget"); return false; }
            tiles.add(fingerprint);
            try {
                for (VectorRoadTile.RoadFeature feature : tile.features()) {
                    if (++features > limits.maxFeatures) throw new Budget("feature_budget");
                    if (feature.points.size() < 2) { decision("feature_without_line"); continue; }
                    long cost = cost(feature.attributes, 0);
                    if (cost > 4096 || cost > limits.maxLiteralBytes - literalBytes) throw new Budget("literal_budget");
                    literalBytes += cost;
                    YandexRoadClass.Decision mapped = YandexRoadClass.map(feature.attributes);
                    int roadClass = mapped.roadClass == null ? -1 : mapped.roadClass;
                    Object rawDirection = feature.attributes.get("oneway");
                    int direction = driving(feature.attributes.get("class"))
                            ? "0".equals(rawDirection) ? 0 : "1".equals(rawDirection) ? 1 : -1 : -1;
                    int level = intern(levels, signature(feature, LEVEL_KEYS));
                    int literal = intern(literals, signature(feature, new String[]{"class", "fc", "oneway"}));
                    decision("class:" + mapped.rule); decision(direction < 0 ? "direction_unresolved" : "direction_literal_supported");
                    int matched = 0;
                    for (int point = 1; point < feature.points.size(); point++) {
                        if (++segments > limits.maxSegments) throw new Budget("segment_budget");
                        matched += associate(feature.points.get(point - 1), feature.points.get(point), roadClass, direction, level, literal, features);
                    }
                    if (matched > 0) retain(tile, feature, mapped, direction, matched);
                }
                return true;
            } catch (Budget budget) { truncate(budget.reason); return false; }
        }

        public Result finish() {
            if (result != null) return result;
            List<Edge> edges = new ArrayList<>(); List<Span> spans = new ArrayList<>();
            int knownEdges = 0, unmatchedEdges = 0, conflictEdges = 0, knownSamples = 0, unmatchedSamples = 0, conflictSamples = 0;
            for (int edge = 0; edge < lengths.length; edge++) {
                int begin = starts[edge], end = starts[edge + 1], matched = 0, ambiguous = 0, conflicts = 0;
                RoadNetwork.KnownAttributes common = null;
                for (int i = begin; i < end; i++) {
                    Sample sample = samples.get(i); RoadNetwork.KnownAttributes a = sample.attributes();
                    if (sample.count > 0) matched++; else unmatchedSamples++;
                    if (sample.count > 1) ambiguous++;
                    if (conflict(sample)) { conflicts++; conflictSamples++; }
                    if (!truncated && any(a)) knownSamples++;
                    common = common == null ? a : consensus(common, a);
                }
                RoadNetwork.KnownAttributes a = truncated || common == null ? RoadNetwork.KnownAttributes.NONE : common;
                String state = truncated ? "truncated" : end == begin ? "zero_length" : matched == 0 ? "unmatched"
                        : matched < end - begin ? "partial_unmatched" : any(a) ? ambiguous > 0 ? "ambiguous_unanimous_fields" : "known_fields"
                        : conflicts > 0 ? "conflict" : "fields_unresolved_or_change";
                edges.add(new Edge(edge, lengths[edge], end - begin, matched, ambiguous, conflicts, state, a));
                if (any(a)) knownEdges++; if (matched == 0 && lengths[edge] > 1e-6) unmatchedEdges++; if (conflicts > 0) conflictEdges++;
                if (!truncated && end - begin < 2) append(spans, new Span(edge, edge + 1, edge, 0, state,
                        RoadNetwork.KnownAttributes.NONE, false, false, false));
                else if (!truncated) for (int i = begin; i < end - 1; i++) {
                    Sample left = samples.get(i), right = samples.get(i + 1);
                    RoadNetwork.KnownAttributes spanAttributes = consensus(left.attributes(), right.attributes());
                    String spanState = left.count == 0 || right.count == 0 ? "unmatched" : any(spanAttributes) ? "known_fields" : "unresolved_or_change";
                    append(spans, new Span(edge + (double)(i - begin) / (end - begin - 1),
                            edge + (double)(i - begin + 1) / (end - begin - 1), edge, Math.max(left.count, right.count), spanState,
                            spanAttributes, left.count > 1 || right.count > 1,
                            ((left.flags | right.flags) & LEVEL_CONFLICT) != 0 || left.count > 0 && right.count > 0 && left.levelId != right.levelId,
                            ((left.flags | right.flags) & LITERAL_CONFLICT) != 0 || left.count > 0 && right.count > 0 && left.literalId != right.literalId));
                    if (spans.size() > limits.maxSpans) { truncate("span_budget"); break; }
                }
                if (spans.size() > limits.maxSpans) truncate("span_budget");
            }
            if (truncated) {
                // A later budget failure must retract all earlier fields, including already built edges/spans.
                List<Edge> unknown = new ArrayList<>();
                for (Edge edge : edges) unknown.add(new Edge(edge.index, edge.lengthM, edge.samples, edge.matchedSamples,
                        edge.ambiguousSamples, edge.conflictSamples, "truncated", RoadNetwork.KnownAttributes.NONE));
                edges = unknown; spans.clear();
                // One all-route unknown span keeps the result bounded even with a tiny maxSpans.
                spans.add(new Span(0, lengths.length, -1, 0, "truncated", RoadNetwork.KnownAttributes.NONE, false, false, false));
                knownEdges = 0; knownSamples = 0;
            }
            return result = new Result(this, edges, spans, knownEdges, unmatchedEdges, conflictEdges, knownSamples, unmatchedSamples, conflictSamples);
        }

        private int associate(VectorRoadTile.Point a, VectorRoadTile.Point b, int roadClass, int direction,
                              int level, int literal, int stamp) {
            double length = Coordinates.distance(a.lon, a.lat, b.lon, b.lat);
            if (length <= 1e-6) return 0;
            double longitudeDelta = delta(a.lon, b.lon), latitudeDelta = b.lat - a.lat;
            // Bound both physical and angular arc curvature, including polar/date-line geometry.
            long chunks = Math.max((long)Math.ceil(samplingLength(a.lon, a.lat, b.lon, b.lat) / CHUNK),
                    (long)Math.ceil((Math.abs(longitudeDelta) + Math.abs(latitudeDelta)) * DEG / .00005));
            if (chunks > limits.maxCellVisits - visits) throw new Budget("spatial_chunk_budget");
            int matched = 0;
            for (long part = 0; part < chunks; part++) {
                double[] left = xyz(wrap(a.lon + longitudeDelta * part / chunks), a.lat + latitudeDelta * part / chunks);
                double[] right = xyz(wrap(a.lon + longitudeDelta * (part + 1) / chunks), a.lat + latitudeDelta * (part + 1) / chunks);
                int minX = cell(Math.min(left[0], right[0]) - DISTANCE - .01), maxX = cell(Math.max(left[0], right[0]) + DISTANCE + .01);
                int minY = cell(Math.min(left[1], right[1]) - DISTANCE - .01), maxY = cell(Math.max(left[1], right[1]) + DISTANCE + .01);
                int minZ = cell(Math.min(left[2], right[2]) - DISTANCE - .01), maxZ = cell(Math.max(left[2], right[2]) + DISTANCE + .01);
                long count = (long)(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
                if (count < 0 || count > limits.maxCellVisits - visits) throw new Budget("cell_visit_budget");
                visits += count;
                for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
                    Ints nearby = cells.get(key(x, y, z)); if (nearby == null) continue;
                    for (int i = 0; i < nearby.size; i++) {
                        Sample sample = samples.get(nearby.values[i]); if (sample.stamp == stamp) continue;
                        if (++tests > limits.maxCandidateTests) throw new Budget("candidate_test_budget");
                        double mx = DEG * R * Math.cos(sample.lat * DEG);
                        double dx = longitudeDelta * mx, dy = latitudeDelta * DEG * R;
                        double squared = dx * dx + dy * dy, routeLength = Math.hypot(sample.dx, sample.dy);
                        if (squared < 1e-12 || routeLength < 1e-6
                                || Math.abs(dx * sample.dx + dy * sample.dy) < HEADING_COS * Math.sqrt(squared) * routeLength) continue;
                        double sx = delta(a.lon, sample.lon) * mx, sy = (sample.lat - a.lat) * DEG * R;
                        double t = Math.max(0, Math.min(1, (sx * dx + sy * dy) / squared));
                        double distance = Coordinates.distance(sample.lon, sample.lat, wrap(a.lon + longitudeDelta * t), a.lat + latitudeDelta * t);
                        if (distance > DISTANCE) continue;
                        sample.add(roadClass, direction, level, literal, stamp); matched++;
                    }
                }
            }
            return matched;
        }
        private void retain(VectorRoadTile tile, VectorRoadTile.RoadFeature feature, YandexRoadClass.Decision mapping, int direction, int matched) {
            if (evidence.size() >= limits.maxEvidence) { droppedEvidence++; return; }
            Map<String,Object> e = new LinkedHashMap<>(); e.put("tile", tile.tileId().toString()); e.put("tileSha256", tile.sha256());
            e.put("rendererFeatureId", feature.unsignedId()); e.put("featureIndex", feature.featureIndex); e.put("partIndex", feature.partIndex);
            e.put("literalAttributes", feature.attributes); e.put("literalTypes", feature.attributeTypes);
            e.put("roadClassMapping", mapping.evidence()); e.put("direction", direction < 0 ? null : direction);
            e.put("matchingSamples", matched); e.put("exactNativeId", false); e.put("levelNamespaceEquivalence", false);
            evidence.add(Collections.unmodifiableMap(e));
        }
        private String signature(VectorRoadTile.RoadFeature feature, String[] keys) {
            Map<String,Object> values = new LinkedHashMap<>();
            for (String key : keys) {
                values.put(key + ":present", feature.attributes.containsKey(key));
                values.put(key, feature.attributes.get(key));
                values.put(key + ":type", feature.attributeTypes.get(key));
            }
            return Json.write(values);
        }
        private int intern(Map<String,Integer> map, String value) {
            Integer old = map.get(value); if (old != null) return old;
            if (map.size() >= 4096) throw new Budget("literal_signature_budget");
            int id = map.size(); map.put(value, id); return id;
        }
        private void decision(String decision) { decisions.put(decision, decisions.getOrDefault(decision, 0L) + 1); }
        private void truncate(String why) { if (!truncated) { truncated = true; truncationReason = why; } }
    }

    private static void append(List<Span> spans, Span next) {
        if (!spans.isEmpty()) {
            Span before = spans.get(spans.size() - 1);
            if (before.originalEdge == next.originalEdge && before.end == next.begin && same(before.attributes, next.attributes)
                    && before.state.equals(next.state) && before.ambiguous == next.ambiguous
                    && before.levelStructureConflict == next.levelStructureConflict && before.literalConflict == next.literalConflict) {
                spans.set(spans.size() - 1, new Span(before.begin, next.end, next.originalEdge,
                        Math.max(before.maximumCandidates, next.maximumCandidates), next.state, next.attributes,
                        next.ambiguous, next.levelStructureConflict, next.literalConflict)); return;
            }
        }
        spans.add(next);
    }
    private static RoadNetwork.KnownAttributes known(Integer roadClass, Integer direction) {
        return new RoadNetwork.KnownAttributes(roadClass, null, null, null, direction, null);
    }
    private static RoadNetwork.KnownAttributes consensus(RoadNetwork.KnownAttributes a, RoadNetwork.KnownAttributes b) {
        return known(equal(a.roadClass, b.roadClass) ? a.roadClass : null, equal(a.direction, b.direction) ? a.direction : null);
    }
    private static boolean equal(Object a, Object b) { return a == null ? b == null : a.equals(b); }
    private static boolean same(RoadNetwork.KnownAttributes a, RoadNetwork.KnownAttributes b) { return equal(a.roadClass, b.roadClass) && equal(a.direction, b.direction); }
    private static boolean any(RoadNetwork.KnownAttributes a) { return a.roadClass != null || a.direction != null; }
    private static boolean conflict(Sample sample) { return (sample.flags & (CLASS_CONFLICT | DIR_CONFLICT | LEVEL_CONFLICT | LITERAL_CONFLICT)) != 0; }
    private static boolean driving(Object raw) {
        if (!(raw instanceof String)) return false; String value = (String)raw;
        if (value.endsWith("_link")) value = value.substring(0, value.length() - 5);
        return value.length() == 1 && value.charAt(0) >= '1' && value.charAt(0) <= '9';
    }
    private static long cost(Object value, int depth) {
        if (depth > 3) return 4097;
        if (value instanceof String) return ((String)value).length() * 2L + 16;
        if (value instanceof Map) {
            long n = 16;
            for (Map.Entry<?,?> e : ((Map<?,?>)value).entrySet()) { n += cost(e.getKey(), depth + 1) + cost(e.getValue(), depth + 1); if (n > 4096) return n; }
            return n;
        }
        if (value instanceof List) { long n = 16; for (Object item : (List<?>)value) { n += cost(item, depth + 1); if (n > 4096) return n; } return n; }
        return 16;
    }
    private static double[] xyz(double lon, double lat) {
        double p = lat * DEG, l = lon * DEG, q = R * Math.cos(p);
        return new double[]{q * Math.cos(l), q * Math.sin(l), R * Math.sin(p)};
    }
    private static int cell(double value) { return (int)Math.floor(value / CELL); }
    private static long key(int x, int y, int z) {
        if (x < -65536 || x > 65535 || y < -65536 || y > 65535 || z < -65536 || z > 65535)
            throw new IllegalArgumentException("spherical cell bounds");
        return ((long)(x + 65536) << 34) | ((long)(y + 65536) << 17) | (z + 65536L);
    }
    private static double delta(double a, double b) { double d = b - a; return d > 180 ? d - 360 : d < -180 ? d + 360 : d; }
    private static double samplingLength(double lon1, double lat1, double lon2, double lat2) {
        double nearestEquator = lat1 * lat2 <= 0 ? 0 : Math.min(Math.abs(lat1), Math.abs(lat2));
        return Math.hypot(delta(lon1, lon2) * DEG * R * Math.cos(nearestEquator * DEG), (lat2 - lat1) * DEG * R);
    }
    public static String geometryFingerprint(YandexRoute route) {
        if (route == null || route.lon == null || route.lat == null || route.lon.length != route.lat.length
                || route.lon.length < 2 || route.lon.length > 200000) throw new IllegalArgumentException("bounded route geometry required");
        return geometryFingerprint(route.lon, route.lat);
    }
    /** Same identity as the SDK copy: SHA-256 of consecutive big-endian lon/lat double pairs. */
    private static String geometryFingerprint(double[] lon, double[] lat) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256"); ByteBuffer pair = ByteBuffer.allocate(16);
            for (int i = 0; i < lon.length; i++) {
                coordinate(lon[i], lat[i]); pair.clear(); pair.putDouble(lon[i]); pair.putDouble(lat[i]); sha.update(pair.array());
            }
            StringBuilder out = new StringBuilder(64);
            for (byte b : sha.digest()) out.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static double wrap(double lon) { return lon > 180 ? lon - 360 : lon < -180 ? lon + 360 : lon; }
    private static void coordinate(double lon, double lat) {
        if (!Double.isFinite(lon) || !Double.isFinite(lat) || lon < -180 || lon > 180 || lat < -90 || lat > 90)
            throw new IllegalArgumentException("finite WGS84 route required");
    }
    private RouteVectorAttributes() { }
}
