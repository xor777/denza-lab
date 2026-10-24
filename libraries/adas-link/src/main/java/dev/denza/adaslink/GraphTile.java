package dev.denza.adaslink;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Bounded decoder of Navigator's captured road_graph_layer.tile. Plain Java 8;
 * the independent oracle is tools/adas_bridge/yandex_graph_tile.py. Stored
 * directed edges, WGS84 geometry, names, legal speed and graph levels are decoded.
 * Anonymous/clipped endpoints stay unknown, and unnamed attributes stay raw.
 * No tile fragments are joined, and no class/lane/ownership semantics are guessed.
 */
public final class GraphTile {
    public static final String SCHEMA = "denza-yandex-graph-tile-v1";
    public static final String NATIVE_EVIDENCE = "research/adas-bridge/yandex-graph-tile-protobuf-evidence-20261024.json";

    public static final class Limits {
        public final int maxBytes, maxFields, maxEntities, maxPoints, maxStringBytes;
        public Limits() { this(8 * 1024 * 1024, 500000, 100000, 500000, 65536); }
        public Limits(int bytes, int fields, int entities, int points, int strings) {
            if (bytes <= 0 || fields <= 0 || entities <= 0 || points <= 0 || strings <= 0)
                throw error("parser limits must be positive integers");
            maxBytes = bytes; maxFields = fields; maxEntities = entities; maxPoints = points; maxStringBytes = strings;
        }
    }

    public static final class TileException extends IllegalArgumentException {
        public TileException(String message) { super(message); }
    }

    public static final class Point {
        public final double lon, lat;
        private Point(double lon, double lat) { this.lon = lon; this.lat = lat; }
    }

    public static final class Edge {
        /** Unsigned uint64 identity bits; use Long.toUnsignedString/hex for display. */
        public final long id, edgeDataId;
        public final String tileSha256;
        public final Long from, to;
        public final boolean reverseGeometry, hasGeometry, completeStoredEndpoints;
        public final List<Point> points;
        public final List<String> names;
        public final Double speedLimitMps;
        public final Integer beginZLevel, endZLevel;
        public final Long rawField7, rawField8, rawField11;
        public final Map<String, String> unresolvedEndpoints;

        @SuppressWarnings("unchecked") private Edge(Map<String, Object> row, String hash) {
            id = id(row.get("edgeIdHex")); edgeDataId = id(row.get("edgeDataIdHex")); tileSha256 = hash;
            from = nullableId(row.get("from")); to = nullableId(row.get("to"));
            reverseGeometry = (Boolean) row.get("reverseGeometry");
            Map<String, Object> geometry = (Map<String, Object>) row.get("geometry");
            hasGeometry = geometry != null;
            List<Point> ps = new ArrayList<>();
            if (geometry != null) for (Object value : (List<Object>) geometry.get("points")) {
                List<Number> p = (List<Number>) value;
                ps.add(new Point(p.get(0).doubleValue(), p.get(1).doubleValue()));
            }
            points = Collections.unmodifiableList(ps);
            names = Collections.unmodifiableList(new ArrayList<>((List<String>) row.get("names")));
            Number speed = (Number) row.get("speedLimitMps");
            speedLimitMps = speed == null ? null : speed.doubleValue();
            Map<String, Object> levels = (Map<String, Object>) row.get("graphLevels");
            beginZLevel = integer(levels.get("begin")); endZLevel = integer(levels.get("end"));
            Map<String, Object> topology = (Map<String, Object>) row.get("topology");
            completeStoredEndpoints = (Boolean) topology.get("completeStoredEndpoints");
            unresolvedEndpoints = Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, String>) topology.get("unresolvedEndpoints")));
            Map<String, Object> raw = (Map<String, Object>) row.get("raw");
            rawField7 = nullableLong(raw.get("field7")); rawField8 = nullableLong(raw.get("field8")); rawField11 = nullableLong(raw.get("field11"));
        }
    }

    public static final class Vertex {
        public final long id;
        public final Integer zLevel;
        public final List<Long> incomingEdgeIds, outgoingEdgeIds;
        @SuppressWarnings("unchecked") private Vertex(Map<String, Object> row) {
            id = id(row.get("id")); zLevel = integer(row.get("zLevel"));
            incomingEdgeIds = typedIds((List<Object>) row.get("incomingEdgeIds"));
            outgoingEdgeIds = typedIds((List<Object>) row.get("outgoingEdgeIds"));
        }
    }

    private final String sha256;
    private final int sourceBytes;
    private final byte[] encoded;
    private final String source;
    private final Limits limits;
    private final Map<String, Object> summary;
    private final List<Edge> edges;
    private final List<Vertex> vertices;
    private final Map<Long, Edge> edgeById;
    private final long retainedBytes;

    @SuppressWarnings("unchecked") private GraphTile(Map<String, Object> result, String hash, byte[] raw, String source, Limits limits) {
        sha256 = hash; sourceBytes = raw.length; encoded = raw;
        this.source = source; this.limits = limits;
        summary = (Map<String, Object>) freeze(result.get("summary"), new IdentityHashMap<Object, Object>());
        List<Edge> es = new ArrayList<>(); Map<Long, Edge> byId = new LinkedHashMap<>();
        for (Object row : (List<Object>) result.get("edges")) {
            Edge edge = new Edge((Map<String, Object>) row, hash); es.add(edge); byId.put(edge.id, edge);
        }
        edges = Collections.unmodifiableList(es); edgeById = Collections.unmodifiableMap(byId);
        List<Vertex> vs = new ArrayList<>();
        for (Object row : (List<Object>) result.get("vertices")) vs.add(new Vertex((Map<String, Object>) row));
        vertices = Collections.unmodifiableList(vs);
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        long typedBytes = 512L + vs.size() * 128L + estimate(summary, seen) + estimate(source, seen);
        for (Vertex vertex : vs) typedBytes += (vertex.incomingEdgeIds.size() + vertex.outgoingEdgeIds.size()) * 40L;
        for (Edge edge : es) typedBytes += 256L + edge.points.size() * 48L + estimate(edge.names, seen) + estimate(edge.unresolvedEndpoints, seen);
        retainedBytes = typedBytes + sourceBytes;
    }

    public String sha256() { return sha256; }
    public int sourceBytes() { return sourceBytes; }
    /** Conservative cache charge, not a measurement or a hard bound on VM heap usage. */
    public long estimatedRetainedBytes() { return retainedBytes; }
    public List<Edge> edges() { return edges; }
    public List<Vertex> vertices() { return vertices; }
    public Set<Long> edgeIds() { return edgeById.keySet(); }
    public Edge edge(long unsignedIdBits) { return edgeById.get(unsignedIdBits); }
    /** Canonical evidence is rebuilt on demand; the cache does not retain the bulky JSON tree. */
    @SuppressWarnings("unchecked") public Map<String, Object> toMap() {
        return (Map<String, Object>) freeze(parseDocument(encoded, source, limits, sha256), new IdentityHashMap<Object, Object>());
    }
    public Map<String, Object> summary() { return summary; }
    public String toJson() { return Json.write(toMap()); }

    public static GraphTile decode(byte[] raw, String source) { return decode(raw, source, new Limits()); }

    public static GraphTile decode(byte[] input, String source, Limits limits) {
        if (input == null || limits == null) throw error("tile input and limits are required");
        if (input.length > limits.maxBytes) throw error("tile byte budget exceeded");
        byte[] raw = input.clone();
        String digest = sha256(raw);
        return new GraphTile(parseDocument(raw, source, limits, digest), digest, raw, source, limits);
    }

    private static Map<String, Object> parseDocument(byte[] raw, String source, Limits limits, String digest) {
        Reader reader = new Reader(limits);
        Fields root = reader.fields(new Bytes(raw, 0, raw.length));
        int entities = 0;
        for (int n = 1; n <= 4; n++) {
            if (root.values(n).size() > limits.maxEntities - entities) throw error("tile entity budget exceeded");
            entities += root.values(n).size();
        }
        if (entities == 0) throw error("tile has no confirmed graph entities");
        Map<Integer, List<Fields>> rows = new LinkedHashMap<>();
        for (int n = 1; n <= 4; n++) {
            List<Fields> list = new ArrayList<>();
            for (Value value : root.values(n)) {
                if (value.wire != 2) throw error("graph entity must be a length-delimited message");
                list.add(reader.fields(value.bytes));
            }
            rows.put(n, list);
        }
        Map<Long, Map<String, Object>> roads = new LinkedHashMap<>(), vertices = new LinkedHashMap<>(), data = new LinkedHashMap<>(), edges = new LinkedHashMap<>();
        for (Fields row : rows.get(4)) {
            long key = uint32(one(row, 1, 0, true).integer);
            Bytes nameBytes = one(row, 2, 2, true).bytes;
            if (nameBytes.size() > limits.maxStringBytes) throw error("road name byte budget exceeded");
            String name;
            try {
                name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(nameBytes.raw, nameBytes.begin, nameBytes.size())).toString();
            } catch (CharacterCodingException e) { throw error("invalid UTF-8 road name"); }
            insert(roads, key, map("id", hex(key), "name", name, "unknownFields", unknown(row, 1, 2)), "Road");
        }
        Map<Long, Set<Long>> incoming = new LinkedHashMap<>(), outgoing = new LinkedHashMap<>();
        for (Fields row : rows.get(1)) {
            long key = one(row, 1, 0, true).integer;
            List<Long> ins = reader.integers(row, 3), outs = reader.integers(row, 4);
            Value level = one(row, 5, 0, false);
            insert(vertices, key, map("id", hex(key), "zLevel", level == null ? null : (int) level.integer,
                "rawIncomingEdgeIds", hexes(ins), "rawOutgoingEdgeIds", hexes(outs),
                "incomingEdgeIds", new ArrayList<>(), "outgoingEdgeIds", new ArrayList<>(), "unknownFields", unknown(row, 1, 3, 4, 5)), "Vertex");
            for (long edge : ins) set(incoming, edge).add(key);
            for (long edge : outs) set(outgoing, edge).add(key);
        }
        for (Fields row : rows.get(3)) {
            long key = one(row, 1, 0, true).integer;
            List<Object> points = reader.polyline(one(row, 6, 2, true).bytes);
            long field7 = uint32(one(row, 7, 0, true).integer), field8 = uint32(one(row, 8, 0, true).integer);
            Value v11 = one(row, 11, 0, false);
            Long field11 = v11 == null ? null : uint32(v11.integer);
            List<Long> roadIds = reader.integers(row, 9);
            List<String> names = new ArrayList<>(), missing = new ArrayList<>();
            for (long roadId : roadIds) {
                uint32(roadId);
                if (roads.containsKey(roadId)) names.add((String) roads.get(roadId).get("name")); else missing.add(hex(roadId));
            }
            Value speedRaw = one(row, 10, 5, false);
            Double speed = null;
            if (speedRaw != null) {
                int bits = 0;
                for (int i = 0; i < 4; i++) bits |= (speedRaw.bytes.raw[speedRaw.bytes.begin + i] & 255) << (8 * i);
                speed = (double) Float.intBitsToFloat(bits);
                if (!Double.isFinite(speed) || speed < 0) throw error("invalid speed limit");
            }
            insert(data, key, map("id", hex(key), "geometry", map("points", points, "complete", true,
                "scope", "full_stored_tile_fragment", "lengthM", length(points)),
                "roadIds", hexes(roadIds), "names", names, "missingRoadIds", missing, "speedLimitMps", speed,
                "raw", map("field7", field7, "field8", field8, "field11", field11, "field11Present", row.containsKey(11),
                    "unknownFields", unknown(row, 1, 6, 7, 8, 9, 10, 11))), "EdgeData");
        }
        List<Object> issues = new ArrayList<>();
        for (Fields row : rows.get(2)) {
            long key = one(row, 1, 0, true).integer, dataKey = one(row, 2, 0, true).integer;
            long reverse = one(row, 3, 0, true).integer;
            if (reverse != 0 && reverse != 1) throw error("invalid reverse-geometry boolean");
            insert(edges, key, null, "Edge");
            Map<String, Object> record = data.get(dataKey);
            @SuppressWarnings("unchecked") Map<String, Object> recordRaw = record == null ? null : (Map<String, Object>) record.get("raw");
            @SuppressWarnings("unchecked") Map<String, Object> geometry = record == null ? null : (Map<String, Object>) record.get("geometry");
            @SuppressWarnings("unchecked") List<Object> points = geometry == null ? new ArrayList<>() : (List<Object>) geometry.get("points");
            List<Long> fromCandidates = sortedUnsigned(set(outgoing, key)), toCandidates = sortedUnsigned(set(incoming, key));
            Map<String, Object> reasons = new LinkedHashMap<>();
            Long from = endpoint(recordRaw, points, fromCandidates, reverse == 1 ? "field8" : "field7", "from", reasons);
            Long to = endpoint(recordRaw, points, toCandidates, reverse == 1 ? "field7" : "field8", "to", reasons);
            if (reverse == 1) { points = new ArrayList<>(points); Collections.reverse(points); }
            Map<String, Object> edgeGeometry = null;
            if (geometry != null) { edgeGeometry = new LinkedHashMap<>(geometry); edgeGeometry.put("points", points); }
            Object beginLevel = fromCandidates.size() == 1 ? vertices.get(fromCandidates.get(0)).get("zLevel") : null;
            Object endLevel = toCandidates.size() == 1 ? vertices.get(toCandidates.get(0)).get("zLevel") : null;
            Map<String, Object> edgeRaw = map("fromVertexCandidates", hexes(fromCandidates), "toVertexCandidates", hexes(toCandidates),
                "edgeUnknownFields", unknown(row, 1, 2, 3));
            Map<String, Object> edge = map("id", digest.substring(0, 16) + "/edge" + hex(key), "edgeIdHex", hex(key), "edgeDataIdHex", hex(dataKey),
                "from", from == null ? null : hex(from), "to", to == null ? null : hex(to), "reverseGeometry", reverse == 1,
                "geometry", edgeGeometry, "names", record == null ? new ArrayList<>() : record.get("names"),
                "speedLimitMps", record == null ? null : record.get("speedLimitMps"), "graphLevels", map("begin", beginLevel, "end", endLevel),
                "topology", map("completeStoredEndpoints", reasons.isEmpty(), "unresolvedEndpoints", reasons), "raw", edgeRaw);
            if (record != null) {
                edgeRaw.putAll(recordRaw); edge.put("roadIds", record.get("roadIds")); edge.put("missingRoadIds", record.get("missingRoadIds"));
            }
            edges.put(key, edge);
            for (Map.Entry<String, Object> reason : reasons.entrySet()) issues.add(map("edgeIdHex", hex(key), "endpoint", reason.getKey(), "reason", reason.getValue()));
            if (from != null) add(vertices.get(from).get("outgoingEdgeIds"), hex(key));
            if (to != null) add(vertices.get(to).get("incomingEdgeIds"), hex(key));
        }
        int resolved = 0, missingData = 0, present11 = 0;
        Map<String, Object> counts7 = new LinkedHashMap<>(), counts8 = new LinkedHashMap<>(), counts11 = new LinkedHashMap<>(), countsLevel = new LinkedHashMap<>();
        for (Map<String, Object> edge : edges.values()) {
            @SuppressWarnings("unchecked") Map<String, Object> topology = (Map<String, Object>) edge.get("topology");
            if ((Boolean) topology.get("completeStoredEndpoints")) resolved++;
            if (edge.get("geometry") == null) missingData++;
        }
        for (Map<String, Object> value : data.values()) {
            @SuppressWarnings("unchecked") Map<String, Object> attributes = (Map<String, Object>) value.get("raw");
            if ((Boolean) attributes.get("field11Present")) present11++;
            count(counts7, pythonString(attributes.get("field7"))); count(counts8, pythonString(attributes.get("field8"))); count(counts11, pythonString(attributes.get("field11")));
        }
        for (Map<String, Object> vertex : vertices.values()) count(countsLevel, pythonString(vertex.get("zLevel")));
        Map<String, Object> summary = map("vertices", vertices.size(), "directedEdges", edges.size(), "edgeData", data.size(), "roads", roads.size(),
            "geometryPoints", reader.pointsUsed, "edgesWithResolvedEndpoints", resolved, "unresolvedEndpoints", issues.size(), "missingEdgeData", missingData,
            "field11Present", present11, "rawField7Values", counts7, "rawField8Values", counts8, "rawField11Values", counts11, "zLevelValues", countsLevel);
        Map<String, Object> document = map("schema", SCHEMA, "source", map("name", source == null ? "in-memory" : source, "sha256", digest, "bytes", raw.length),
            "coordinateFrame", "wgs84", "pointOrder", "lon,lat", "topologyScope", "captured_tile_only",
            "identityScope", "vendor_ids_within_unidentified_map_release; tile_fragments_may_differ", "nativeEvidence", NATIVE_EVIDENCE, "summary", summary,
            "vertices", new ArrayList<>(vertices.values()), "edges", new ArrayList<>(edges.values()), "edgeData", new ArrayList<>(data.values()),
            "roads", new ArrayList<>(roads.values()), "issues", issues, "unknownRootFields", unknown(root, 1, 2, 3, 4));
        return document;
    }

    private static final class Bytes {
        final byte[] raw; final int begin, end;
        Bytes(byte[] raw, int begin, int end) { this.raw = raw; this.begin = begin; this.end = end; }
        int size() { return end - begin; }
    }
    private static final class Cursor {
        final Bytes bytes; int at;
        Cursor(Bytes bytes) { this.bytes = bytes; at = bytes.begin; }
        long varint() {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                if (at >= bytes.end) throw error("truncated protobuf varint");
                int b = bytes.raw[at++] & 255;
                if (i == 9 && b > 1) throw error("protobuf varint exceeds uint64");
                value |= (long) (b & 127) << (7 * i);
                if (b < 128) return value;
            }
            throw error("protobuf varint exceeds uint64");
        }
    }
    private static final class Value {
        final int wire; final long integer; final Bytes bytes;
        Value(int wire, long integer, Bytes bytes) { this.wire = wire; this.integer = integer; this.bytes = bytes; }
    }
    private static final class Fields extends LinkedHashMap<Integer, List<Value>> {
        List<Value> values(int n) { List<Value> values = get(n); return values == null ? Collections.<Value>emptyList() : values; }
    }
    private static final class Reader {
        final Limits limits; int fieldsUsed, pointsUsed;
        Reader(Limits limits) { this.limits = limits; }
        void fieldBudget(String message) { if (fieldsUsed >= limits.maxFields) throw error(message); fieldsUsed++; }
        Fields fields(Bytes bytes) {
            Fields result = new Fields(); Cursor cursor = new Cursor(bytes);
            while (cursor.at < bytes.end) {
                fieldBudget("protobuf field budget exceeded");
                long tag = cursor.varint(), number = tag >>> 3; int wire = (int) tag & 7;
                if (number <= 0 || number >= (1 << 29)) throw error("invalid protobuf field number");
                Value value;
                if (wire == 0) value = new Value(wire, cursor.varint(), null);
                else if (wire == 1 || wire == 2 || wire == 5) {
                    long size = wire == 1 ? 8 : wire == 5 ? 4 : cursor.varint();
                    if (size < 0 || size > bytes.end - cursor.at) throw error("protobuf field exceeds message boundary");
                    value = new Value(wire, 0, new Bytes(bytes.raw, cursor.at, cursor.at + (int) size)); cursor.at += (int) size;
                } else throw error("unsupported protobuf wire type " + wire);
                List<Value> values = result.get((int) number);
                if (values == null) { values = new ArrayList<>(); result.put((int) number, values); }
                values.add(value);
            }
            return result;
        }
        List<Long> integers(Fields fields, int number) {
            List<Long> result = new ArrayList<>();
            for (Value value : fields.values(number)) {
                if (value.wire == 0) result.add(value.integer);
                else if (value.wire == 2) {
                    Cursor cursor = new Cursor(value.bytes);
                    while (cursor.at < value.bytes.end) { fieldBudget("packed integer budget exceeded"); result.add(cursor.varint()); }
                } else throw error("invalid repeated integer wire type");
            }
            return result;
        }
        List<Long> axis(Bytes bytes) {
            Fields fields = fields(bytes);
            long coordinate = unzig(one(fields, 1, 0, true).integer);
            Cursor cursor = new Cursor(one(fields, 2, 2, true).bytes);
            List<Long> values = new ArrayList<>(); values.add(coordinate);
            while (cursor.at < cursor.bytes.end) {
                if (values.size() >= limits.maxPoints) throw error("polyline point budget exceeded");
                try { coordinate = Math.addExact(coordinate, unzig(cursor.varint())); }
                catch (ArithmeticException e) { throw error("polyline coordinate overflow"); }
                values.add(coordinate);
            }
            return values;
        }
        List<Object> polyline(Bytes bytes) {
            Fields fields = fields(bytes);
            List<Long> lon = axis(one(fields, 1, 2, true).bytes), lat = axis(one(fields, 2, 2, true).bytes);
            if (lon.size() != lat.size()) throw error("polyline coordinate axes have different lengths");
            if (lon.size() > limits.maxPoints - pointsUsed) throw error("tile point budget exceeded");
            pointsUsed += lon.size();
            List<Object> points = new ArrayList<>();
            for (int i = 0; i < lon.size(); i++) {
                double x = lon.get(i) / 1e6, y = lat.get(i) / 1e6;
                if (Math.abs(x) > 180 || Math.abs(y) > 90) throw error("polyline contains invalid WGS84 coordinate");
                List<Object> point = new ArrayList<>(); point.add(x); point.add(y); points.add(point);
            }
            return points;
        }
    }

    private static Value one(Fields fields, int number, int wire, boolean required) {
        List<Value> values = fields.values(number);
        if (values.isEmpty()) { if (required) throw error("missing required field " + number); return null; }
        if (values.size() != 1 || values.get(0).wire != wire) throw error("duplicate or wrong wire type for field " + number);
        return values.get(0);
    }
    private static List<Object> unknown(Fields fields, int... known) {
        Set<Integer> names = new LinkedHashSet<>(); for (int number : known) names.add(number);
        List<Object> result = new ArrayList<>();
        for (Map.Entry<Integer, List<Value>> entry : new TreeMap<>(fields).entrySet()) if (!names.contains(entry.getKey()))
            for (Value value : entry.getValue()) result.add(value.bytes == null ? map("field", entry.getKey(), "wire", value.wire, "unsignedDecimal", Long.toUnsignedString(value.integer)) :
                map("field", entry.getKey(), "wire", value.wire, "hex", bytesHex(value.bytes)));
        return result;
    }
    private static Long endpoint(Map<String, Object> recordRaw, List<Object> points, List<Long> candidates, String marker, String end, Map<String, Object> reasons) {
        String reason = recordRaw == null ? "missing_edge_data" : ((Number) recordRaw.get(marker)).longValue() != 0 ?
            "native_converter_uses_anonymous_endpoint_for_nonzero_raw_field" : points.size() < 2 ? "insufficient_geometry_points" :
            candidates.isEmpty() ? "vertex_adjacency_missing_in_captured_tile" : candidates.size() != 1 ? "conflicting_vertex_adjacency" : null;
        if (reason != null) { reasons.put(end, reason); return null; }
        return candidates.get(0);
    }
    private static long uint32(long value) { if (value < 0 || value > 0xFFFFFFFFL) throw error("uint32 field exceeds bounds"); return value; }
    private static long unzig(long value) { return (value >>> 1) ^ -(value & 1); }
    private static String hex(long value) { return "0x" + Long.toUnsignedString(value, 16); }
    private static long id(Object hex) { return Long.parseUnsignedLong(((String) hex).substring(2), 16); }
    private static Long nullableId(Object hex) { return hex == null ? null : id(hex); }
    private static Integer integer(Object value) { return value == null ? null : ((Number) value).intValue(); }
    private static Long nullableLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static List<Long> typedIds(List<Object> values) { List<Long> result = new ArrayList<>(); for (Object value : values) result.add(id(value)); return Collections.unmodifiableList(result); }
    private static List<String> hexes(List<Long> values) { List<String> result = new ArrayList<>(); for (long value : values) result.add(hex(value)); return result; }
    private static Set<Long> set(Map<Long, Set<Long>> map, long key) { Set<Long> result = map.get(key); if (result == null) { result = new LinkedHashSet<>(); map.put(key, result); } return result; }
    private static List<Long> sortedUnsigned(Set<Long> values) { List<Long> result = new ArrayList<>(values); Collections.sort(result, Long::compareUnsigned); return result; }
    private static <T> void insert(Map<Long, T> table, long key, T value, String kind) { if (table.containsKey(key)) throw error("duplicate " + kind + " ID " + hex(key)); table.put(key, value); }
    private static Map<String, Object> map(Object... pairs) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result; }
    @SuppressWarnings("unchecked") private static void add(Object list, Object value) { ((List<Object>) list).add(value); }
    private static void count(Map<String, Object> counts, String key) { Number n = (Number) counts.get(key); counts.put(key, n == null ? 1 : n.intValue() + 1); }
    private static String pythonString(Object value) { return value == null ? "None" : value.toString(); }
    @SuppressWarnings("unchecked") private static double length(List<Object> points) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            List<Number> a = (List<Number>) points.get(i - 1), b = (List<Number>) points.get(i);
            // The tile oracle converts each coordinate to radians before subtraction.
            double scale = Math.PI / 180;
            double lon1 = a.get(0).doubleValue() * scale, lat1 = a.get(1).doubleValue() * scale;
            double lon2 = b.get(0).doubleValue() * scale, lat2 = b.get(1).doubleValue() * scale;
            double s = Math.sin((lat2 - lat1) / 2), t = Math.sin((lon2 - lon1) / 2);
            double h = s * s + Math.cos(lat1) * Math.cos(lat2) * t * t;
            total += 2 * 6371008.8 * Math.asin(Math.min(1, Math.sqrt(h)));
        }
        return Math.rint(total * 1e6) / 1e6;
    }
    public static String sha256(byte[] bytes) {
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); return bytesHex(new Bytes(digest, 0, digest.length)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String bytesHex(Bytes bytes) { char[] chars = new char[bytes.size() * 2]; String digits = "0123456789abcdef"; for (int i = 0; i < bytes.size(); i++) { int b = bytes.raw[bytes.begin + i] & 255; chars[i * 2] = digits.charAt(b >>> 4); chars[i * 2 + 1] = digits.charAt(b & 15); } return new String(chars); }
    @SuppressWarnings("unchecked") private static Object freeze(Object value, IdentityHashMap<Object, Object> seen) {
        if (!(value instanceof Map) && !(value instanceof List)) return value;
        if (seen.containsKey(value)) return seen.get(value);
        if (value instanceof Map) {
            Map<String, Object> backing = new LinkedHashMap<>(); Map<String, Object> result = Collections.unmodifiableMap(backing); seen.put(value, result);
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) backing.put(entry.getKey(), freeze(entry.getValue(), seen));
            return result;
        }
        List<Object> backing = new ArrayList<>(); List<Object> result = Collections.unmodifiableList(backing); seen.put(value, result);
        for (Object item : (List<Object>) value) backing.add(freeze(item, seen)); return result;
    }
    @SuppressWarnings("unchecked") private static long estimate(Object value, IdentityHashMap<Object, Boolean> seen) {
        if (value == null || seen.put(value, true) != null) return 0;
        if (value instanceof String) return 64L + ((String) value).length() * 2L;
        if (value instanceof Map) { long bytes = 256L + ((Map<?, ?>) value).size() * 80L; for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) bytes += estimate(entry.getKey(), seen) + estimate(entry.getValue(), seen); return bytes; }
        if (value instanceof List) { long bytes = 96L + ((List<?>) value).size() * 16L; for (Object item : (List<?>) value) bytes += estimate(item, seen); return bytes; }
        return 32;
    }
    private static TileException error(String message) { return new TileException(message); }
}
