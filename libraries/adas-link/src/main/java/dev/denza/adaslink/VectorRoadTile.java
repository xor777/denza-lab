package dev.denza.adaslink;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Bounded pinned-vmap3 reader. Renderer literals are not stock enums or legal traffic directions. */
public final class VectorRoadTile {
    public static final class TileId {
        public final int x, y, z;
        public TileId(int x, int y, int z) {
            if (z < 0 || z > 30 || x < 0 || y < 0 || x >= (1L << z) || y >= (1L << z))
                throw error("TileId outside 30-bit pyramid");
            this.x = x; this.y = y; this.z = z;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof TileId)) return false;
            TileId t = (TileId) o; return x == t.x && y == t.y && z == t.z;
        }
        @Override public int hashCode() { return (x * 31 + y) * 31 + z; }
        @Override public String toString() { return z + "/" + x + "/" + y; }
    }
    public static final class Point {
        public final double lon, lat;
        private Point(double lon, double lat) { this.lon = lon; this.lat = lat; }
    }
    public static final class RoadFeature {
        /** Unsigned uint64 bits; hasId distinguishes an absent ID from zero. Not a graph edge ID. */
        public final long id;
        public final boolean hasId;
        public final int featureIndex, partIndex;
        public final String sourceSha256;
        public final List<Point> points;
        public final Map<String, Object> attributes;
        public final Map<String, String> attributeTypes;
        private final long attributeBytes;
        private RoadFeature(Value id, int index, int part, String hash, List<Point> points, Attributes attributes) {
            hasId = id != null; this.id = id == null ? 0 : id.integer;
            featureIndex = index; partIndex = part; sourceSha256 = hash;
            this.points = Collections.unmodifiableList(points);
            this.attributes = attributes.values; attributeTypes = attributes.types; attributeBytes = attributes.retainedBytes;
        }
        public String unsignedId() { return hasId ? Long.toUnsignedString(id) : null; }
    }
    public static final class Limits {
        public final int maxBytes, maxFields, maxEntities, maxStringBytes, maxFeatures, maxPaths, maxPoints, maxPointsPerPath;
        public Limits() { this(2 << 20, 500000, 100000, 65536, 100000, 200000, 500000, 100000); }
        public Limits(int bytes, int fields, int entities, int strings, int features, int paths, int points, int perPath) {
            if (bytes <= 0 || fields <= 0 || entities <= 0 || strings <= 0 || features <= 0 || paths <= 0 || points <= 0 || perPath <= 0)
                throw error("positive decoder limits required");
            maxBytes = bytes; maxFields = fields; maxEntities = entities; maxStringBytes = strings;
            maxFeatures = features; maxPaths = paths; maxPoints = points; maxPointsPerPath = perPath;
        }
    }
    public static final class TileException extends IllegalArgumentException {
        public TileException(String message) { super(message); }
    }
    private static final Set<String> KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "class", "fc", "oneway", "struct", "struct:toll", "toll", "zlev", "min_zlev", "max_zlev", "z_level")));
    private final TileId tileId;
    private final String sha256;
    private final List<RoadFeature> features;
    private final int sourceBytes, sourcePointCount, sourceFeatureCount, sourcePathCount, gridBits;
    private final long estimatedBytes;
    private VectorRoadTile(TileId id, String hash, List<RoadFeature> roads, int bytes, Paths paths, int features) {
        tileId = id; sha256 = hash; this.features = Collections.unmodifiableList(roads); sourceBytes = bytes;
        sourcePointCount = paths.coords.length / 2; sourcePathCount = paths.lengths.length;
        sourceFeatureCount = features; gridBits = paths.bits;
        long charge = 512;
        // Charge each immutable per-feature map once per part, conservatively including shared maps.
        for (RoadFeature road : roads) {
            charge += 192L + 40L * road.points.size();
            charge += road.attributeBytes;
        }
        estimatedBytes = charge;
    }
    public TileId tileId() { return tileId; }
    public String sha256() { return sha256; }
    public String fingerprint() { return tileId + "/" + sha256; }
    public int sourceBytes() { return sourceBytes; }
    public long estimatedRetainedBytes() { return estimatedBytes; }
    public List<RoadFeature> features() { return features; }
    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", "denza-yandex-vector-road-tile-v1"); m.put("tileId", tileId.toString()); m.put("sha256", sha256);
        m.put("sourceBytes", sourceBytes); m.put("sourcePoints", sourcePointCount); m.put("sourceFeatures", sourceFeatureCount);
        m.put("sourcePaths", sourcePathCount); m.put("gridBits", gridBits); m.put("roadParts", features.size());
        return Collections.unmodifiableMap(m);
    }
    public static VectorRoadTile decode(byte[] raw, TileId wrapper, TileId render, TileId source) {
        return decode(raw, wrapper, render, source, new Limits());
    }
    public static VectorRoadTile decode(byte[] input, TileId wrapper, TileId render, TileId source, Limits limits) {
        contexts(wrapper, render, source);
        if (limits == null || input == null || input.length == 0 || input.length > limits.maxBytes) throw error("empty tile or byte budget exceeded");
        byte[] raw = input.clone(); String hash = digest(raw);
        Reader r = new Reader(limits);
        Fields root = r.fields(new Bytes(raw, 0, raw.length));
        int bits = bounded(one(root, 1, 0, true).integer, 1, 31, "grid bits");
        one(root, 7, 2, false); // Opaque storage may contain GLB, not UTF-8.
        List<Long> coordinates = r.integers(root, 2, 32), lengths = r.integers(root, 3, 32);
        if ((coordinates.size() & 1) != 0 || coordinates.size() / 2 > limits.maxPoints || lengths.size() > limits.maxPaths)
            throw error("shared geometry budget or coordinate pair count");
        int[] coords = new int[coordinates.size()], lens = new int[lengths.size()];
        for (int i = 0; i < coords.length; i++) coords[i] = (int) unzig(coordinates.get(i));
        long sum = 0;
        for (int i = 0; i < lens.length; i++) { lens[i] = bounded(lengths.get(i), 0, limits.maxPointsPerPath, "path length"); sum += lens[i]; }
        if (sum * 2 != coords.length) throw error("path lengths do not consume coordinate buffer");
        Paths paths = new Paths(coords, lens, bits, wrapper);
        List<List<String>> keys = new ArrayList<>();
        for (Value v : root.values(4)) {
            Fields row = r.message(v); List<String> names = new ArrayList<>();
            for (Value key : row.values(1)) { requireWire(key, 2); names.add(r.text(key.bytes)); }
            if (new HashSet<>(names).size() != names.size()) throw error("duplicate dictionary attribute key");
            keys.add(names);
        }
        List<AttributeValue> values = new ArrayList<>();
        for (Value v : root.values(5)) values.add(r.attributeValue(r.message(v)));
        List<RoadFeature> roads = new ArrayList<>(); int featureCount = 0;
        for (Value lv : root.values(6)) {
            Fields layer = r.message(lv); Value name = one(layer, 1, 2, false);
            boolean roadLayer = name != null && r.text(name.bytes).equals("road"); int featureIndex = 0;
            for (Value fv : layer.values(2)) {
                if (++featureCount > limits.maxFeatures) throw error("feature budget exceeded");
                Fields f = r.message(fv); int kind = 0; Fields geometry = null;
                for (int k = 1; k <= 3; k++) for (Value gv : f.values(k)) {
                    if (kind != 0) throw error("each feature must have exactly one geometry");
                    kind = k; geometry = r.message(gv);
                }
                if (kind == 0) throw error("missing feature geometry");
                r.integers(f, 4, 32); Value id = one(f, 5, 0, false); one(f, 7, 2, false);
                Value av = one(f, 6, 2, false);
                Attributes attrs = av == null ? Attributes.EMPTY : r.attributes(r.message(av), keys, values);
                if (kind == 1) paths.point();
                else if (kind == 2) {
                    Value count = one(geometry, 1, 0, false);
                    int n = count == null ? 1 : bounded(count.integer, 0, limits.maxPaths, "line count");
                    List<Long> before = r.integers(geometry, 2, 64); r.integers(geometry, 3, 64);
                    if (!before.isEmpty() && before.size() < n) throw error("line distance metadata cardinality mismatch");
                    for (int part = 0; part < n; part++) {
                        List<Point> points = paths.next(roadLayer);
                        if (roadLayer) roads.add(new RoadFeature(id, featureIndex, part, hash, points, attrs));
                    }
                } else {
                    List<Long> rings = r.integers(geometry, 1, 32); r.integers(geometry, 3, 64);
                    Value aux = one(geometry, 2, 0, false);
                    long n = aux == null ? 0 : bounded(aux.integer, 0, limits.maxPaths, "polygon auxiliary count");
                    for (long count : rings) n += bounded(count, 0, limits.maxPaths, "polygon ring count");
                    if (n > limits.maxPaths) throw error("polygon path budget exceeded");
                    for (int i = 0; i < n; i++) paths.next(false);
                }
                featureIndex++;
            }
        }
        if (paths.path != lens.length || paths.point * 2 != coords.length) throw error("geometry cursors did not consume shared buffers");
        return new VectorRoadTile(wrapper, hash, roads, raw.length, paths, featureCount);
    }
    static void contexts(TileId wrapper, TileId render, TileId source) {
        if (wrapper == null || !wrapper.equals(render) || !wrapper.equals(source)) throw error("missing or different native TileId contexts");
    }
    static String digest(byte[] raw) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(raw); StringBuilder s = new StringBuilder(64);
            for (byte b : bytes) { s.append(Character.forDigit((b >>> 4) & 15, 16)); s.append(Character.forDigit(b & 15, 16)); }
            return s.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static long valueBytes(Object o) {
        if (o instanceof String) return 48L + ((String) o).length() * 2L;
        if (o instanceof List) { long n = 32; for (Object x : (List<?>) o) n += 8 + valueBytes(x); return n; }
        return 24;
    }
    private static final class Paths {
        final int[] coords, lengths; final int bits; final long minimum, maximum, size; final TileId id;
        int point, path;
        Paths(int[] coords, int[] lengths, int bits, TileId id) {
            this.coords = coords; this.lengths = lengths; this.bits = bits; this.id = id;
            minimum = -(1L << (bits - 1)); maximum = (1L << (bits - 1)) - 1; size = 1L << (30 - id.z);
        }
        int count() { if (path >= lengths.length) throw error("path cursor exceeds shared buffer"); return lengths[path++]; }
        void point() {
            int n = count(); if (n == 0) throw error("empty Point cross-path peek unsupported");
            grid(coords[point * 2]); grid(coords[point * 2 + 1]); point += n;
        }
        void grid(long v) { if (v < minimum || v > maximum) throw error("accumulated coordinate outside centered grid"); }
        List<Point> next(boolean retain) {
            int n = count(); List<Point> result = retain ? new ArrayList<Point>() : null; long x = 0, y = 0;
            for (int i = 0; i < n; i++, point++) {
                int dx = coords[point * 2], dy = coords[point * 2 + 1];
                if (i == 0) { x = dx; y = dy; } else { x += dx; y += dy; if (dx == 0 && dy == 0) continue; }
                if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE)
                    throw error("int32 accumulated coordinate overflow");
                grid(x); grid(y);
                if (retain) {
                    double extent = (1L << bits) - 1;
                    double wx = id.x * (double) size + size * (x - minimum) / extent;
                    double wy = id.y * (double) size + size * (y - minimum) / extent;
                    result.add(inverse(wx, wy));
                }
            }
            return result;
        }
    }
    // Native1abfad8 and1b9fb4c: 30-bit ellipsoidal WGS84 inverse; no spherical substitute.
    private static Point inverse(double x, double y) {
        double u = Math.PI - 2 * Math.PI * y / (1L << 30);
        double chi = Math.PI / 2 - 2 * Math.atan(Math.exp(-u));
        double[] c = {0.003356551468879694, 6.57187271079536e-6, 1.764564338702e-8, 5.328478445e-11};
        double correction = 0;
        for (int i = 0; i < 4; i++) correction += c[i] * Math.sin(2 * (i + 1) * chi);
        return new Point(360 * x / (1L << 30) - 180, Math.toDegrees(chi + correction));
    }
    private static final class Attributes {
        static final Attributes EMPTY = new Attributes(Collections.<String, Object>emptyMap(), Collections.<String, String>emptyMap());
        final Map<String, Object> values; final Map<String, String> types;
        final long retainedBytes;
        Attributes(Map<String, Object> values, Map<String, String> types) {
            this(values, types, 0);
        }
        Attributes(Map<String, Object> values, Map<String, String> types, long bytes) {
            this.values = Collections.unmodifiableMap(values); this.types = Collections.unmodifiableMap(types); retainedBytes = bytes;
        }
    }
    private static final class AttributeValue {
        final String type; final Object value; final long retainedBytes; final boolean finite;
        AttributeValue(String type, Object value) {
            this.type = type; this.value = value; retainedBytes = valueBytes(value); finite = isFinite(value);
        }
    }
    private static final class Bytes {
        final byte[] raw; final int start, end;
        Bytes(byte[] raw, int start, int end) { this.raw = raw; this.start = start; this.end = end; }
        int length() { return end - start; }
    }
    private static final class Cursor {
        final Bytes b; int at;
        Cursor(Bytes b) { this.b = b; at = b.start; }
        long varint() {
            long v = 0;
            for (int i = 0; i < 10; i++) {
                if (at >= b.end) throw error("truncated varint"); int c = b.raw[at++] & 255;
                if (i == 9 && c > 1) throw error("varint exceeds uint64");
                v |= (long) (c & 127) << (i * 7); if (c < 128) return v;
            }
            throw error("varint exceeds uint64");
        }
    }
    private static final class Value {
        final int wire; final long integer; final Bytes bytes;
        Value(int wire, long integer, Bytes bytes) { this.wire = wire; this.integer = integer; this.bytes = bytes; }
    }
    private static final class Fields extends LinkedHashMap<Integer, List<Value>> {
        List<Value> values(int n) { List<Value> v = get(n); return v == null ? Collections.<Value>emptyList() : v; }
    }
    private static final class Reader {
        final Limits limits; int fields, entities;
        Reader(Limits limits) { this.limits = limits; }
        void charge() { if (++fields > limits.maxFields) throw error("protobuf field budget exceeded"); }
        Fields message(Value v) { requireWire(v, 2); return fields(v.bytes); }
        Fields fields(Bytes b) {
            if (++entities > limits.maxEntities) throw error("message budget exceeded");
            Fields f = new Fields(); Cursor c = new Cursor(b);
            while (c.at < b.end) {
                charge(); long tag = c.varint(), n = tag >>> 3; int w = (int) (tag & 7);
                if (n <= 0 || n >= 1 << 29) throw error("invalid protobuf field number");
                Value v;
                if (w == 0) v = new Value(w, c.varint(), null);
                else if (w == 1 || w == 2 || w == 5) {
                    long size = w == 2 ? c.varint() : w == 1 ? 8 : 4;
                    if (size < 0 || size > b.end - c.at) throw error("field exceeds message boundary");
                    v = new Value(w, 0, new Bytes(b.raw, c.at, c.at + (int) size)); c.at += (int) size;
                } else throw error("unsupported wire type");
                f.computeIfAbsent((int) n, k -> new ArrayList<>()).add(v);
            }
            return f;
        }
        List<Long> integers(Fields f, int n, int bits) {
            List<Long> result = new ArrayList<>();
            for (Value v : f.values(n)) {
                if (v.wire == 0) addInteger(result, v.integer, bits);
                else if (v.wire == 2) {
                    Cursor c = new Cursor(v.bytes);
                    while (c.at < v.bytes.end) { charge(); addInteger(result, c.varint(), bits); }
                } else throw error("wrong repeated integer wire type");
            }
            return result;
        }
        void addInteger(List<Long> out, long v, int bits) { if (bits == 32) uint32(v); out.add(v); }
        String text(Bytes b) {
            if (b.length() > limits.maxStringBytes) throw error("string byte budget exceeded");
            try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b.raw, b.start, b.length())).toString(); }
            catch (CharacterCodingException e) { throw error("invalid UTF-8"); }
        }
        AttributeValue attributeValue(Fields f) {
            int kind = 0;
            for (int n = 1; n <= 8; n++) if (f.containsKey(n)) {
                if (kind != 0) throw error("multiple AttributeValue oneof cases"); kind = n;
            }
            if (kind == 0) return new AttributeValue("unset", null);
            Value v = one(f, kind, kind == 1 || kind == 7 || kind == 8 ? 2 : kind == 5 ? 5 : 0, true);
            String[] types = {"unset", "string", "sint32", "uint32", "uint64", "float32", "bool", "float32_array", "string_array"};
            Object value;
            if (kind == 1) value = text(v.bytes);
            else if (kind == 2) value = unzig(uint32(v.integer));
            else if (kind == 3) value = uint32(v.integer);
            else if (kind == 4) value = Long.toUnsignedString(v.integer);
            else if (kind == 5) value = float32(v.bytes, v.bytes.start);
            else if (kind == 6) value = v.integer != 0;
            else {
                List<Object> array = new ArrayList<>(); Fields a = fields(v.bytes);
                for (Value item : a.values(1)) {
                    if (kind == 8) { requireWire(item, 2); array.add(text(item.bytes)); }
                    else {
                        if ((item.wire != 2 && item.wire != 5) || item.bytes.length() % 4 != 0) throw error("wrong FloatArray wire type/size");
                        for (int at = item.bytes.start; at < item.bytes.end; at += 4) { charge(); array.add(float32(item.bytes, at)); }
                    }
                }
                value = Collections.unmodifiableList(array);
            }
            return new AttributeValue(types[kind], value);
        }
        Attributes attributes(Fields f, List<List<String>> sets, List<AttributeValue> values) {
            Value set = one(f, 1, 0, false); List<Long> refs = integers(f, 2, 32);
            if (set == null || set.integer < 0 || set.integer >= sets.size()) throw error("missing/out-of-range attribute key set");
            List<String> keys = sets.get((int) set.integer);
            if (keys.size() != refs.size()) throw error("attribute key/value count mismatch");
            Map<String, Object> attrs = new LinkedHashMap<>(); Map<String, String> types = new LinkedHashMap<>(); long bytes = 0;
            for (int i = 0; i < keys.size(); i++) {
                charge(); long ref = refs.get(i);
                if (ref >= values.size()) throw error("out-of-range attribute value index");
                if (KEYS.contains(keys.get(i))) {
                    AttributeValue a = values.get((int) ref);
                    if (!a.finite) throw error("nonfinite retained road attribute");
                    attrs.put(keys.get(i), a.value); types.put(keys.get(i), a.type);
                    bytes += 128 + 2L * keys.get(i).length() + a.retainedBytes;
                }
            }
            return new Attributes(attrs, types, bytes);
        }
    }
    private static Float float32(Bytes b, int at) {
        int v = 0; for (int i = 0; i < 4; i++) v |= (b.raw[at + i] & 255) << (i * 8);
        return Float.intBitsToFloat(v);
    }
    private static boolean isFinite(Object v) {
        if (v instanceof Float && !Float.isFinite((Float) v)) return false;
        if (v instanceof List) for (Object x : (List<?>) v) if (!isFinite(x)) return false;
        return true;
    }
    private static Value one(Fields f, int n, int wire, boolean required) {
        List<Value> values = f.values(n);
        if (values.isEmpty()) { if (required) throw error("missing required field " + n); return null; }
        if (values.size() != 1 || values.get(0).wire != wire) throw error("duplicate/wrong wire for singular field " + n);
        return values.get(0);
    }
    private static void requireWire(Value v, int wire) { if (v.wire != wire) throw error("wrong message/string wire type"); }
    private static long uint32(long v) { if (v < 0 || v > 0xffffffffL) throw error("integer exceeds uint32"); return v; }
    private static long unzig(long v) { return (v >>> 1) ^ -(v & 1); }
    private static int bounded(long v, int minimum, int maximum, String name) {
        if (v < minimum || v > maximum) throw error(name + " outside bounds"); return (int) v;
    }
    private static TileException error(String message) { return new TileException(message); }
}
