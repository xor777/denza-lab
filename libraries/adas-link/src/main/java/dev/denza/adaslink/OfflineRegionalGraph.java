package dev.denza.adaslink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Bounded reader of the captured Yandex regional driving.fb layout, not a routing/access API.
 * Layout transcription: decode_package_arrays.py and package_spatial.py in the retained corpus.
 * No vendor enum conversion, legal one-way inference or online/stock ID equivalence is made.
 *
 * <p>ByteBuffer input may be a read-only mapping. The caller must keep its backing data unchanged
 * for this reader's lifetime. decode(byte[], String) instead takes a defensive copy. All buffer
 * accesses are absolute; the supplied buffer's position, limit and byte order are unchanged.
 * Index storage is bounded; full shapes are decoded only for requested edges. Source SHA identifies
 * this package, not a supported-package allowlist. Other packages must satisfy the same layout.
 */
public final class OfflineRegionalGraph {
    public static final String FORMAT = "yandex-regional-driving-flatbuffer-v1";
    public static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    public static final int MAX_ENTRIES = 1000000, MAX_QUERY_EDGES = 10000;
    public static final int MAX_EDGE_POINTS = 4096, MAX_QUERY_POINTS = 200000;
    private static final int MAX_BLOB = 4 * 1024 * 1024, MAX_NODES = 200000;
    private static final int MAX_ROUTE_BOXES = 1024, MAX_BOX_TESTS = 2000000;
    private static final long MAX_INDEX_BYTES = 96L * 1024 * 1024;
    private static final double QUANTUM = 1e-6;

    public static final class Bounds {
        public final double minLon, minLat, maxLon, maxLat;
        public Bounds(double minLon, double minLat, double maxLon, double maxLat) {
            if (!finite(minLon) || !finite(minLat) || !finite(maxLon) || !finite(maxLat)
                    || minLon < -180 || maxLon > 180 || minLat < -90 || maxLat > 90
                    || minLon > maxLon || minLat > maxLat) throw bad("WGS84 bounds");
            this.minLon = minLon; this.minLat = minLat; this.maxLon = maxLon; this.maxLat = maxLat;
        }
        public boolean intersects(Bounds b) {
            return !(maxLon < b.minLon || minLon > b.maxLon || maxLat < b.minLat || minLat > b.maxLat);
        }
    }

    /** Full shape in the directed index's source-to-target order. Direction/access remains raw. */
    public static final class Edge {
        public final int index, baseIndex, attributeIndex, sourceVertex, targetVertex;
        public final boolean attributeGeometryReversed;
        public final long rawType, rawStructType, rawCategory;
        public final int rawAccessMask;
        public final String source, packageSha256, localId;
        public final Bounds bounds;
        private final double[] coordinates;
        private Edge(OfflineRegionalGraph graph, int index, int base, int attribute, boolean reversed, double[] points) {
            this.index = index; baseIndex = base; attributeIndex = attribute;
            sourceVertex = graph.sources[index]; targetVertex = graph.targets[index];
            attributeGeometryReversed = reversed; rawType = graph.types[attribute];
            rawStructType = graph.structures[attribute]; rawCategory = graph.categories.get(attribute);
            rawAccessMask = graph.u8(graph.accessDictionary + (int) graph.accessIndices[attribute]);
            source = graph.source; packageSha256 = graph.sha; localId = packageSha256 + ":" + index;
            coordinates = points; bounds = pointBounds(points);
        }
        public int pointCount() { return coordinates.length / 2; }
        public double lon(int point) { point(point); return coordinates[point * 2]; }
        public double lat(int point) { point(point); return coordinates[point * 2 + 1]; }
        public double[] lonLat() { return coordinates.clone(); }
        private void point(int point) { if (point < 0 || point >= pointCount()) throw new IndexOutOfBoundsException("shape point"); }
    }

    /** Box refinement only. A result is not a route match, permission or coverage assertion. */
    public static final class Query {
        public final List<Edge> edges;
        public final boolean truncated;
        public final int visitedNodes, skippedNonBase;
        public final boolean exactRouteMatch = false;
        private Query(List<Edge> edges, boolean truncated, int visitedNodes, int skippedNonBase) {
            this.edges = Collections.unmodifiableList(new ArrayList<>(edges));
            this.truncated = truncated; this.visitedNodes = visitedNodes; this.skippedNonBase = skippedNonBase;
        }
    }

    private final ByteBuffer data;
    private final String source, sha, version;
    private final int[] regions, sources, targets;
    private final Bounds bounds;
    private final RankBits isBase, hasData;
    private final Packed reverseBase, coordinates, categories, spatialIds;
    private final long[] types, structures, accessIndices, shapeOffsets;
    private final Bits shapeBits;
    private final int accessDictionary, accessDictionarySize, admitted;
    private final int boxes, boxCount, fanout;
    private final int[] levels;
    private final long[] gaps;
    private long indexBytes;

    public static OfflineRegionalGraph decode(byte[] bytes, String source) {
        if (bytes == null || bytes.length < 8 || bytes.length > MAX_FILE_BYTES) throw bad("file byte budget");
        return decode(ByteBuffer.wrap(bytes.clone()), source);
    }
    public static OfflineRegionalGraph decode(ByteBuffer bytes, String source) {
        if (bytes == null || bytes.remaining() < 8 || bytes.remaining() > MAX_FILE_BYTES) throw bad("file byte budget");
        if (source == null || source.isEmpty() || source.length() > 4096) throw bad("source label");
        return new OfflineRegionalGraph(bytes.slice().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN), source);
    }

    private OfflineRegionalGraph(ByteBuffer bytes, String source) {
        data = bytes; this.source = source; sha = digest(data); charge(data.limit());
        int root = offset(u32(0)); table(root);
        int graph = reference(root, 1), rtree = reference(root, 3), edge = reference(graph, 7);
        version = text(root, 6); regions = intVector(root, 7, 1024);
        if (version.isEmpty() || regions.length == 0) throw bad("regional package identity");
        Bounds graphBounds = readBounds(graph, 2), treeBounds = readBounds(rtree, 0);
        if (graphBounds.minLon != treeBounds.minLon || graphBounds.minLat != treeBounds.minLat
                || graphBounds.maxLon != treeBounds.maxLon || graphBounds.maxLat != treeBounds.maxLat)
            throw bad("graph/RTree bounds disagree");
        bounds = graphBounds;
        isBase = rankBits(reference(graph, 4)); hasData = rankBits(reference(graph, 6));
        reverseBase = packed(reference(graph, 5));
        if (isBase.bits.length > MAX_ENTRIES || isBase.ones != hasData.bits.length || reverseBase.size() != hasData.bits.length)
            throw bad("base map sizes");
        admitted = isBase.ones;
        types = compact(reference(edge, 3)); structures = compact(reference(edge, 4));
        categories = packed(reference(edge, 5));
        if (hasData.ones != types.length || types.length != structures.length || types.length != categories.size())
            throw bad("attribute map sizes");
        accessIndices = compact(reference(edge, 15));
        int dictionary = reference(edge, 16); accessDictionarySize = count(u32(dictionary), 256, "access dictionary");
        accessDictionary = dictionary + 4; span(accessDictionary, accessDictionarySize);
        if (accessIndices.length != types.length) throw bad("access index cardinality");
        for (long index : accessIndices) if (index < 0 || index >= accessDictionarySize) throw bad("access dictionary index");

        int adjacency = reference(graph, 0);
        RankBits begins = rankBits(reference(reference(adjacency, 0), 0));
        long[] difference = compact(reference(adjacency, 1));
        coordinates = packed(reference(graph, 3));
        if ((coordinates.size() & 1) != 0 || difference.length != isBase.bits.length) throw bad("topology sizes");
        int vertices = coordinates.size() / 2;
        if (vertices == 0 || begins.ones != vertices + 1 || begins.bits.length - begins.ones != difference.length)
            throw bad("unary adjacency sizes");
        sources = ints(difference.length); targets = ints(difference.length);
        int vertex = -1, at = 0;
        for (int i = 0; i < begins.bits.length; i++) {
            if (begins.bits.get(i, 1) == 1) { vertex++; continue; }
            if (vertex < 0 || vertex >= vertices || at >= sources.length) throw bad("adjacency source");
            long encoded = difference[at], delta = (encoded >>> 1) ^ -(encoded & 1);
            long target = vertex + delta;
            if (target < 0 || target >= vertices) throw bad("adjacency target");
            sources[at] = vertex; targets[at++] = (int) target;
        }
        if (at != sources.length || vertex != vertices) throw bad("unary adjacency tail");
        for (int i = 0; i < vertices; i++) coordinate(vertexCoordinate(i, 0), vertexCoordinate(i, 1));
        // Validate every admitted edge's canonical attribute mapping without decoding all shapes.
        for (int i = 0; i < hasData.bits.length; i++) canonical(i);
        int geometry = reference(edge, 0);
        shapeOffsets = sequence(reference(geometry, 0));
        Cursor shape = blob(reference(geometry, 1), 0);
        shape.zeroFlags(); shapeBits = shape.bits(); shape.end();
        if (shapeOffsets.length != types.length + 1) throw bad("shape index cardinality");
        for (int i = 0; i < shapeOffsets.length; i++) {
            shapeOffsets[i] -= i;
            if (shapeOffsets[i] < 0 || i > 0 && shapeOffsets[i] < shapeOffsets[i - 1]) throw bad("shape offsets");
        }
        if (shapeOffsets[0] != 0 || shapeOffsets[shapeOffsets.length - 1] != shapeBits.length) throw bad("shape payload extent");

        fanout = u8(requiredField(rtree, 1, 1));
        if (fanout < 1) throw bad("RTree fanout");
        int boxVector = reference(rtree, 2); boxCount = count(u32(boxVector), MAX_NODES, "RTree nodes");
        boxes = boxVector + 4; span(boxes, (long) boxCount * 4);
        levels = intVector(rtree, 3, 33); gaps = longVector(rtree, 4, 32);
        if (levels.length < 2 || levels[0] != 0 || levels[1] != 1 || levels[levels.length - 1] != boxCount
                || gaps.length != levels.length - 1) throw bad("RTree levels");
        spatialIds = packed(reference(rtree, 5));
        if (spatialIds.size() != sources.length) throw bad("RTree edge cardinality");
        charge((sources.length + 7L) / 8);
        BitSet seen = new BitSet(sources.length);
        for (int i = 0; i < spatialIds.size(); i++) {
            long id = spatialIds.get(i);
            if (id < 0 || id >= sources.length || seen.get((int) id)) throw bad("RTree edge permutation");
            seen.set((int) id);
        }
        for (int level = 0; level < gaps.length; level++) {
            int n = levels[level + 1] - levels[level];
            if (n <= 0) throw bad("RTree level ordering");
            int total = level + 1 < gaps.length ? levels[level + 2] - levels[level + 1] : spatialIds.size();
            int previous = 0;
            for (int i = 0; i < n; i++) {
                long range = childRange(level, i, total);
                if ((int) (range >>> 32) != previous) throw bad("RTree child partition");
                previous = (int) range;
            }
            if (previous != total) throw bad("RTree unindexed children");
        }
    }

    public String source() { return source; }
    public String sha256() { return sha; }
    public String format() { return FORMAT; }
    public String version() { return version; }
    public int[] regionIds() { return regions.clone(); }
    public Bounds bounds() { return bounds; }
    public int directedEdgeCount() { return sources.length; }
    public int admittedEdgeCount() { return admitted; }
    public boolean isBaseEdge(int index) { edgeIndex(index); return isBase.bits.get(index, 1) == 1; }
    public Edge edge(int index) { return edge(index, MAX_EDGE_POINTS); }
    private Edge edge(int index, int availablePoints) {
        edgeIndex(index);
        if (!isBaseEdge(index)) throw new UnsupportedOperationException("non-base directed edge has no admitted shape/attribute mapping");
        int base = isBase.rank(index + 1) - 1, canonical = canonical(base);
        int attribute = hasData.rank(canonical); boolean reverse = canonical != base;
        int pointCount = shapePointCount(attribute);
        if (pointCount > availablePoints) return null;
        double[] start = vertexPoint(reverse ? targets[index] : sources[index]);
        double[] end = vertexPoint(reverse ? sources[index] : targets[index]);
        double[] points = new double[pointCount * 2]; points[0] = start[0]; points[1] = start[1];
        Rice rice = new Rice((int) shapeOffsets[attribute], (int) shapeOffsets[attribute + 1]);
        long lon = 0, lat = 0; int p = 2;
        while (rice.position < rice.end) {
            try { lon = Math.addExact(lon, rice.next()); lat = Math.addExact(lat, rice.next()); }
            catch (ArithmeticException corrupt) { throw bad("shape delta overflow"); }
            double x = start[0] + lon * QUANTUM, y = start[1] + lat * QUANTUM;
            coordinate(x, y); points[p++] = x; points[p++] = y;
        }
        points[p++] = end[0]; points[p] = end[1];
        if (reverse) for (int a = 0, z = pointCount - 1; a < z; a++, z--) {
            double x = points[a * 2], y = points[a * 2 + 1];
            points[a * 2] = points[z * 2]; points[a * 2 + 1] = points[z * 2 + 1];
            points[z * 2] = x; points[z * 2 + 1] = y;
        }
        return new Edge(this, index, base, attribute, reverse, points);
    }

    public Query query(Bounds box, int maxEdges) {
        return query(box, maxEdges, MAX_QUERY_POINTS);
    }
    Query query(Bounds box, int maxEdges, int maxPoints) {
        if (box == null || maxEdges < 1 || maxEdges > MAX_QUERY_EDGES || maxPoints < 2 || maxPoints > MAX_QUERY_POINTS)
            throw bad("query bounds/edge/point budget");
        return search(Collections.singletonList(box), box, maxEdges, maxPoints);
    }
    private Query search(List<Bounds> corridor, Bounds box, int maxEdges, int maxPoints) {
        Search search = new Search(corridor, box, maxEdges, maxPoints); search.visit(0, 0, bounds);
        search.edges.sort(Comparator.comparingInt(e -> e.index));
        return new Query(search.edges, search.truncated, search.visited, search.skipped);
    }

    /** Padded boxes of consecutive route blocks, independent of progress. Refine exact association later. */
    public Query queryRoute(double[] lon, double[] lat, double paddingM, int maxEdges) {
        if (lon == null || lat == null || lon.length != lat.length || lon.length < 2 || lon.length > 200000
                || !finite(paddingM) || paddingM < 0 || paddingM > 10000
                || maxEdges < 1 || maxEdges > MAX_QUERY_EDGES) throw bad("route query budget");
        double xmin = 180, xmax = -180, ymin = 90, ymax = -90;
        for (int i = 0; i < lon.length; i++) {
            coordinate(lon[i], lat[i]); xmin = Math.min(xmin, lon[i]); xmax = Math.max(xmax, lon[i]);
            ymin = Math.min(ymin, lat[i]); ymax = Math.max(ymax, lat[i]);
        }
        Bounds whole = padded(xmin, ymin, xmax, ymax, paddingM);
        int chunk = Math.max(32, (lon.length - 1 + MAX_ROUTE_BOXES - 1) / MAX_ROUTE_BOXES);
        List<Bounds> corridor = new ArrayList<>();
        for (int begin = 0; begin < lon.length - 1; begin += chunk) {
            int end = Math.min(lon.length - 1, begin + chunk);
            double a = 180, b = 90, c = -180, d = -90;
            for (int i = begin; i <= end; i++) {
                a = Math.min(a, lon[i]); b = Math.min(b, lat[i]); c = Math.max(c, lon[i]); d = Math.max(d, lat[i]);
            }
            corridor.add(padded(a, b, c, d, paddingM));
        }
        return search(corridor, whole, maxEdges, MAX_QUERY_POINTS);
    }
    private static Bounds padded(double a, double b, double c, double d, double paddingM) {
        double dy = paddingM / 111000, cosine = Math.cos(Math.toRadians(Math.max(Math.abs(b), Math.abs(d))));
        double dx = paddingM == 0 ? 0 : paddingM / (111000 * Math.max(cosine, 1e-6));
        return new Bounds(Math.max(-180, a - dx), Math.max(-90, b - dy), Math.min(180, c + dx), Math.min(90, d + dy));
    }

    private final class Search {
        final Bounds query; final List<Bounds> corridor; final int max, maxPoints; final List<Edge> edges = new ArrayList<>();
        int visited, skipped, points, boxTests; boolean truncated;
        Search(List<Bounds> corridor, Bounds query, int max, int maxPoints) {
            this.corridor = corridor; this.query = query; this.max = max; this.maxPoints = maxPoints;
        }
        boolean intersects(Bounds box) {
            if (!query.intersects(box)) return false;
            for (Bounds part : corridor) {
                if (++boxTests > MAX_BOX_TESTS) { truncated = true; return false; }
                if (part.intersects(box)) return true;
            }
            return false;
        }
        void visit(int level, int node, Bounds box) {
            if (truncated || !intersects(box)) return;
            if (++visited > MAX_NODES) { truncated = true; return; }
            int total = level + 1 < gaps.length ? levels[level + 2] - levels[level + 1] : spatialIds.size();
            long range = childRange(level, node, total);
            int begin = (int) (range >>> 32), end = (int) range;
            if (level + 1 == gaps.length) {
                for (int i = begin; i < end; i++) {
                    int index = (int) spatialIds.get(i);
                    if (!isBaseEdge(index)) { skipped++; continue; }
                    Edge candidate = edge(index, maxPoints - points);
                    if (candidate == null) { truncated = true; return; }
                    if (!intersects(candidate.bounds)) { if (truncated) return; continue; }
                    if (edges.size() == max) { truncated = true; return; }
                    edges.add(candidate); points += candidate.pointCount();
                }
            } else for (int child = begin; child < end && !truncated; child++)
                visit(level + 1, child, childBounds(level + 1, child, box));
        }
    }

    private int canonical(int base) {
        if (hasData.bits.get(base, 1) == 1) return base;
        long reverse = reverseBase.get(base);
        if (reverse < 0 || reverse >= hasData.bits.length || hasData.bits.get((int) reverse, 1) != 1)
            throw bad("reverse attribute mapping");
        return (int) reverse;
    }
    private int shapePointCount(int attribute) {
        Rice rice = new Rice((int) shapeOffsets[attribute], (int) shapeOffsets[attribute + 1]); int points = 2;
        while (rice.position < rice.end) {
            rice.next(); rice.next();
            if (++points > MAX_EDGE_POINTS) throw bad("edge shape point budget");
        }
        return points;
    }
    private final class Rice {
        int position; final int end;
        Rice(int position, int end) { this.position = position; this.end = end; }
        long next() {
            int quotient = 0;
            while (position < end && shapeBits.get(position, 1) == 0) {
                if (++quotient > 65535) throw bad("Rice quotient budget"); position++;
            }
            if (end - position < 16) throw bad("Rice code crosses shape boundary");
            long value = ((long) quotient << 15) | shapeBits.get(position + 1, 15); position += 16;
            return (value & 1) != 0 ? (value + 1) / 2 : -(value / 2);
        }
    }
    private double[] vertexPoint(int index) {
        double lon = vertexCoordinate(index, 0), lat = vertexCoordinate(index, 1);
        coordinate(lon, lat); return new double[]{lon, lat};
    }
    private double vertexCoordinate(int index, int axis) {
        long raw = coordinates.get(index * 2 + axis);
        if (raw < 0) throw bad("vertex coordinate overflow");
        return (axis == 0 ? bounds.minLon : bounds.minLat) + raw * QUANTUM;
    }
    private Bounds childBounds(int level, int node, Bounds parent) {
        long code = u32(boxes + (levels[level] + node) * 4);
        double a = parent.minLon + (parent.maxLon - parent.minLon) * (code & 255) / 255;
        double b = parent.minLon + (parent.maxLon - parent.minLon) * ((code >>> 8) & 255) / 255;
        double c = parent.minLat + (parent.maxLat - parent.minLat) * ((code >>> 16) & 255) / 255;
        double d = parent.minLat + (parent.maxLat - parent.minLat) * ((code >>> 24) & 255) / 255;
        return new Bounds(Math.min(a, b), Math.min(c, d), Math.max(a, b), Math.max(c, d));
    }
    private long childRange(int level, int parent, int total) {
        if (total < 0 || total > MAX_ENTRIES) throw bad("RTree next level size");
        long begin = (long) parent * fanout, end = begin + fanout;
        long omittedBegin = gaps[level] & 0xffffffffL, omittedEnd = omittedBegin + (gaps[level] >>> 32);
        if (omittedBegin < begin) {
            if (omittedEnd > begin) throw bad("RTree padding crosses range start");
            begin -= omittedEnd - omittedBegin; end -= omittedEnd - omittedBegin;
        } else if (omittedBegin < end) {
            if (omittedEnd != end) throw bad("RTree padding crosses range end");
            end = omittedBegin;
        }
        begin = Math.min(begin, total); end = Math.min(end, total);
        if (begin < 0 || end < begin) throw bad("RTree child range");
        return (begin << 32) | end;
    }

    private final class Bits {
        final int offset, length, words;
        Bits(int offset, int length, int words) { this.offset = offset; this.length = length; this.words = words; }
        long get(int at, int width) {
            if (width < 0 || width > 64 || at < 0 || at > length || width > length - at) throw bad("bit access");
            long value = 0; int read = 0;
            while (read < width) {
                int bit = at + read, take = Math.min(width - read, 8 - (bit & 7));
                value |= (long) ((u8(offset + (bit >>> 3)) >>> (bit & 7)) & ((1 << take) - 1)) << read;
                read += take;
            }
            return value;
        }
        long word(int word) {
            int at = word * 64, width = Math.min(64, length - at);
            if (width <= 0) return 0;
            long value = data.getLong(offset + word * 8);
            return width == 64 ? value : value & ((1L << width) - 1);
        }
    }
    private final class RankBits {
        final Bits bits; final int[] prefix; final int ones;
        RankBits(Bits bits) {
            this.bits = bits; int words = (bits.length + 63) / 64; prefix = ints(words + 1);
            for (int i = 0; i < words; i++) prefix[i + 1] = prefix[i] + Long.bitCount(bits.word(i));
            ones = prefix[words];
        }
        int rank(int end) {
            if (end < 0 || end > bits.length) throw bad("rank index");
            int word = end / 64, width = end & 63;
            return prefix[word] + (width == 0 ? 0 : Long.bitCount(bits.word(word) & ((1L << width) - 1)));
        }
    }
    private final class Packed {
        final Bits bits; final int width;
        Packed(Bits bits, int width) { this.bits = bits; this.width = width; }
        int size() { return bits.length / width; }
        long get(int index) {
            if (index < 0 || index >= size()) throw bad("packed index");
            return bits.get(index * width, width);
        }
    }
    private final class Cursor {
        int position; final int end;
        Cursor(int position, int end) { this.position = position; this.end = end; }
        void align() { position = (position + 3) & ~3; if (position > end) throw bad("serialized alignment"); }
        long number() {
            if (end - position < 8) throw bad("serialized integer");
            long value = data.getLong(position); position += 8; align();
            if (value < 0) throw bad("serialized integer range"); return value;
        }
        int octet() { if (position >= end) throw bad("serialized byte"); int value = u8(position++); align(); return value; }
        int vector(int width) {
            int n = count(number(), MAX_ENTRIES, "serialized vector"); int start = position;
            if ((long) n * width > end - position) throw bad("serialized vector bounds");
            position += n * width; align(); return start;
        }
        Bits bits() {
            int length = count(number(), MAX_BLOB * 8, "bit vector length");
            int n = count(number(), MAX_ENTRIES, "bit vector words"), start = position;
            if ((long) n * 8 > end - position || (long) n * 64 < length) throw bad("bit vector capacity");
            position += n * 8; align(); return new Bits(start, length, n);
        }
        void select() { number(); vector(8); vector(2); vector(8); }
        void zeroFlags() { if (number() != 0) throw bad("unsupported serialization flags"); }
        void end() { if (position != end) throw bad("unsupported serialized trailing bytes"); }
    }
    private Cursor blob(int table, int field) {
        int vector = reference(table, field), size = count(u32(vector), MAX_BLOB, "blob byte budget");
        span(vector + 4, size); return new Cursor(vector + 4, vector + 4 + size);
    }
    private RankBits rankBits(int table) {
        Cursor cursor = blob(table, 0); cursor.zeroFlags(); Bits bits = cursor.bits();
        // Native rank-support auxiliary bytes remain bounded inside this blob and are not used.
        if (bits.length > MAX_ENTRIES * 2 + 1) throw bad("rank bit budget");
        return new RankBits(bits);
    }
    private Packed packed(int table) {
        int width = u8(requiredField(table, 0, 1)); Cursor cursor = blob(table, 1);
        cursor.zeroFlags(); Bits bits = cursor.bits(); cursor.end();
        if (width < 1 || width > 64 || bits.length % width != 0 || bits.length / width > MAX_ENTRIES)
            throw bad("packed width/cardinality");
        return new Packed(bits, width);
    }
    private long[] ef(Cursor cursor) {
        cursor.number(); Bits high = cursor.bits(); cursor.select(); cursor.select(); Bits low = cursor.bits();
        int width = cursor.octet(); if (width > 63) throw bad("EF low width");
        int n = 0, words = (high.length + 63) / 64;
        for (int i = 0; i < words; i++) {
            n += Long.bitCount(high.word(i)); if (n > MAX_ENTRIES + 1) throw bad("EF value budget");
        }
        if ((long) n * width != low.length) throw bad("EF low cardinality");
        long[] values = longs(n); int index = 0;
        for (int i = 0; i < words; i++) {
            long word = high.word(i);
            while (word != 0) {
                int bit = Long.numberOfTrailingZeros(word); long upper = (long) i * 64 + bit - index;
                if (upper < 0 || upper > (Long.MAX_VALUE >>> width)) throw bad("EF integer overflow");
                long value = (upper << width) | low.get(index * width, width);
                if (index > 0 && value < values[index - 1]) throw bad("EF monotonicity");
                values[index++] = value; word &= word - 1;
            }
        }
        return values;
    }
    private long[] sequence(int table) {
        Cursor cursor = blob(table, 0); cursor.zeroFlags(); long[] values = ef(cursor); cursor.end(); return values;
    }
    private long[] compact(int table) {
        Cursor cursor = blob(table, 0); cursor.zeroFlags(); long[] offsets = ef(cursor); Bits payload = cursor.bits(); cursor.end();
        if (offsets.length == 0 || offsets[0] != 0 || offsets[offsets.length - 1] != payload.length)
            throw bad("compact payload extent");
        long[] values = longs(offsets.length - 1);
        for (int i = 0; i < values.length; i++) {
            long width = offsets[i + 1] - offsets[i];
            if (width < 0 || width > 63) throw bad("compact integer width");
            long tail = payload.get((int) offsets[i], (int) width), base = (1L << (int) width) - 1;
            if (tail > Long.MAX_VALUE - base) throw bad("compact integer overflow");
            values[i] = base + tail;
        }
        return values;
    }

    private int table(int at) {
        span(at, 4); int displacement = data.getInt(at);
        long vtLong = (long) at - displacement; int vt = offset(vtLong); span(vt, 4);
        int length = u16(vt), object = u16(vt + 2);
        if (length < 4 || length > 128 || (length & 1) != 0 || object < 4 || object > 4096) throw bad("table header");
        span(vt, length); span(at, object);
        for (int i = 4; i < length; i += 2) { int value = u16(vt + i); if (value != 0 && (value < 4 || value >= object)) throw bad("table field offset"); }
        return vt;
    }
    private int field(int at, int index) {
        int vt = table(at), length = u16(vt); int slot = 4 + index * 2;
        if (index < 0 || slot >= length) return -1;
        int value = u16(vt + slot); return value == 0 ? -1 : at + value;
    }
    private int requiredField(int at, int index, int bytes) {
        int position = field(at, index); if (position < 0) throw bad("required field " + index);
        int object = u16(table(at) + 2);
        if ((long) position + bytes > (long) at + object) throw bad("field extends beyond object");
        span(position, bytes); return position;
    }
    private int reference(int at, int index) {
        int position = requiredField(at, index, 4); long relative = u32(position);
        if (relative == 0) throw bad("null relative offset"); return offset(position + relative);
    }
    private int[] intVector(int at, int field, int maximum) {
        int vector = reference(at, field), n = count(u32(vector), maximum, "uint32 vector"); span(vector + 4, n * 4L);
        int[] values = ints(n); for (int i = 0; i < n; i++) values[i] = count(u32(vector + 4 + i * 4), Integer.MAX_VALUE, "uint32 value"); return values;
    }
    private long[] longVector(int at, int field, int maximum) {
        int vector = reference(at, field), n = count(u32(vector), maximum, "uint64 vector"); span(vector + 4, n * 8L);
        long[] values = longs(n); for (int i = 0; i < n; i++) values[i] = data.getLong(vector + 4 + i * 8); return values;
    }
    private String text(int at, int field) {
        int vector = reference(at, field), n = count(u32(vector), 256, "package version"); span(vector + 4, n + 1L);
        if (u8(vector + 4 + n) != 0) throw bad("string terminator");
        ByteBuffer value = data.duplicate(); value.position(vector + 4); value.limit(vector + 4 + n);
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(value).toString(); }
        catch (CharacterCodingException invalid) { throw bad("UTF-8 package version"); }
    }
    private Bounds readBounds(int at, int field) {
        int position = requiredField(at, field, 32);
        double a = data.getDouble(position), b = data.getDouble(position + 8), c = data.getDouble(position + 16), d = data.getDouble(position + 24);
        return new Bounds(a, c, b, d);
    }
    private int u8(int at) { span(at, 1); return data.get(at) & 255; }
    private int u16(int at) { span(at, 2); return data.getShort(at) & 65535; }
    private long u32(int at) { span(at, 4); return data.getInt(at) & 0xffffffffL; }
    private void span(int at, long bytes) { if (at < 0 || bytes < 0 || at > data.limit() || bytes > data.limit() - (long) at) throw bad("file offset/length"); }
    private int offset(long at) { if (at < 0 || at >= data.limit()) throw bad("file reference"); return (int) at; }
    private static int count(long n, int max, String why) { if (n < 0 || n > max) throw bad(why); return (int) n; }
    private void charge(long bytes) { if (bytes < 0 || bytes > MAX_INDEX_BYTES - indexBytes) throw bad("index memory budget"); indexBytes += bytes; }
    private int[] ints(int n) { charge(n * 4L + 24); return new int[n]; }
    private long[] longs(int n) { charge(n * 8L + 24); return new long[n]; }
    private void edgeIndex(int index) { if (index < 0 || index >= sources.length) throw new IndexOutOfBoundsException("directed edge index"); }
    private static boolean finite(double x) { return !Double.isNaN(x) && !Double.isInfinite(x); }
    private static void coordinate(double lon, double lat) { if (!finite(lon) || !finite(lat) || Math.abs(lon) > 180 || Math.abs(lat) > 90) throw bad("WGS84 coordinate"); }
    private static Bounds pointBounds(double[] points) {
        double a = 180, b = 90, c = -180, d = -90;
        for (int i = 0; i < points.length; i += 2) { a = Math.min(a, points[i]); b = Math.min(b, points[i + 1]); c = Math.max(c, points[i]); d = Math.max(d, points[i + 1]); }
        return new Bounds(a, b, c, d);
    }
    private static String digest(ByteBuffer data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); ByteBuffer view = data.duplicate(); byte[] chunk = new byte[8192];
            while (view.hasRemaining()) { int n = Math.min(view.remaining(), chunk.length); view.get(chunk, 0, n); digest.update(chunk, 0, n); }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) { result.append(Character.forDigit((value >>> 4) & 15, 16)); result.append(Character.forDigit(value & 15, 16)); }
            return result.toString();
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static IllegalArgumentException bad(String reason) { return new IllegalArgumentException("regional graph: " + reason); }
}
