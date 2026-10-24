package dev.denza.adaslink;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Independent small serialized package plus retained host-oracle shapes. Source tests, no SDK. */
public final class OfflineRegionalGraphTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void near(double a, double b, String message) { check(Math.abs(a - b) < 1e-12, message); }
    private interface Action { void run() throws Exception; }
    private static void rejected(Action action, String message) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(message);
    }

    private static final class Bits {
        final List<Integer> bits = new ArrayList<>();
        void write(long value, int width) { for (int i = 0; i < width; i++) bits.add((int) (value >>> i) & 1); }
        void rice(long signed) {
            long value = signed > 0 ? signed * 2 - 1 : -signed * 2;
            for (long i = 0; i < (value >>> 15); i++) write(0, 1);
            write(1, 1); write(value & 32767, 15);
        }
    }
    private static final class Serialized {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        void number(long value) { for (int i = 0; i < 8; i++) bytes.write((int) (value >>> (i * 8)) & 255); }
        void octet(int value) { bytes.write(value); while ((bytes.size() & 3) != 0) bytes.write(0); }
        void bits(Bits value) {
            number(value.bits.size()); int words = (value.bits.size() + 63) / 64; number(words);
            for (int word = 0; word < words; word++) {
                long data = 0; for (int i = 0; i < 64 && word * 64 + i < value.bits.size(); i++)
                    data |= (long) value.bits.get(word * 64 + i) << i;
                number(data);
            }
        }
        void select() { number(0); number(0); number(0); number(0); }
        void ef(long[] values) {
            number(values.length == 0 ? 0 : values[values.length - 1] + 1);
            Bits high = new Bits(); int size = values.length == 0 ? 0 : (int) values[values.length - 1] + values.length;
            for (int i = 0; i < size; i++) high.write(0, 1);
            for (int i = 0; i < values.length; i++) high.bits.set((int) values[i] + i, 1);
            bits(high); select(); select(); bits(new Bits()); octet(0);
        }
        byte[] result() { return bytes.toByteArray(); }
    }

    /** FlatBuffer writer is independent of the reader, including inline bounds and relative pointers. */
    private static final class Fixture {
        final ByteBuffer b = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
        final Map<Integer,int[]> fields = new HashMap<>();
        int typeFlags, reverseWord, beginsWord, levelsVector, gapsVector, dictionary, shapeWord;
        Fixture() { b.position(4); }
        void align() { while ((b.position() & 3) != 0) b.put((byte) 0); }
        int table(int[] offsets, int size) {
            align(); int vt = b.position(); b.putShort((short) (4 + offsets.length * 2)); b.putShort((short) size);
            for (int value : offsets) b.putShort((short) value);
            align(); int object = b.position(); for (int i = 0; i < size; i++) b.put((byte) 0);
            b.putInt(object, object - vt); fields.put(object, offsets); return object;
        }
        int slot(int table, int index) { return table + fields.get(table)[index]; }
        void ref(int table, int index, int target) { int at = slot(table, index); b.putInt(at, target - at); }
        int vector(byte[] bytes) { align(); int at = b.position(); b.putInt(bytes.length); b.put(bytes); align(); return at; }
        int ints(int... values) { align(); int at = b.position(); b.putInt(values.length); for (int value : values) b.putInt(value); return at; }
        int longs(long... values) { align(); int at = b.position(); b.putInt(values.length); for (long value : values) b.putLong(value); return at; }
        int string(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8); align(); int at = b.position();
            b.putInt(bytes.length); b.put(bytes); b.put((byte) 0); align(); return at;
        }
        int bitset(long... values) {
            int table = table(new int[]{4}, 8); Bits bits = new Bits(); for (long value : values) bits.write(value, 1);
            Serialized s = new Serialized(); s.number(0); s.bits(bits); int blob = vector(s.result());
            beginsWord = blob + 4 + 24; ref(table, 0, blob); return table;
        }
        int packed(int width, long... values) {
            int table = table(new int[]{4,8}, 12); b.put(slot(table, 0), (byte) width);
            Bits bits = new Bits(); for (long value : values) bits.write(value, width);
            Serialized s = new Serialized(); s.number(0); s.bits(bits); int blob = vector(s.result());
            reverseWord = blob + 4 + 24; ref(table, 1, blob); return table;
        }
        int compact(long... values) {
            int table = table(new int[]{4}, 8); Serialized s = new Serialized(); s.number(0);
            long[] offsets = new long[values.length + 1]; Bits bits = new Bits();
            for (int i = 0; i < values.length; i++) {
                int width = 63 - Long.numberOfLeadingZeros(values[i] + 1);
                bits.write(values[i] + 1 - (1L << width), width); offsets[i + 1] = bits.bits.size();
            }
            s.ef(offsets); s.bits(bits); int blob = vector(s.result()); typeFlags = blob + 4; ref(table, 0, blob); return table;
        }
        void bounds(int table, int field) {
            int at = slot(table, field); b.putDouble(at, 1); b.putDouble(at + 8, 1.002);
            b.putDouble(at + 16, 2); b.putDouble(at + 24, 2.002);
        }
        byte[] build() {
            int root = table(new int[]{0,4,0,8,0,0,12,16}, 20); b.putInt(0, root);
            int graph = table(new int[]{4,0,8,40,44,48,52,56}, 60); ref(root, 1, graph); bounds(graph, 2);
            int adjacency = table(new int[]{4,8}, 12); ref(graph, 0, adjacency);
            int unary = table(new int[]{4}, 8); ref(adjacency, 0, unary);
            int starts = bitset(1,0,1,0,0,1,0,1), startsWord = beginsWord; ref(unary, 0, starts);
            ref(adjacency, 1, compact(2,1,2,1));
            ref(graph, 3, packed(11,0,0,1000,1000,2000,2000));
            ref(graph, 4, bitset(1,1,1,0));
            int reversed = packed(2,0,0,0), reversedWord = reverseWord; ref(graph, 5, reversed);
            ref(graph, 6, bitset(1,0,1));
            int edge = table(new int[]{4,0,0,8,12,16,0,0,0,0,0,0,0,0,0,20,24}, 28); ref(graph, 7, edge);
            int type = compact(9,2), typeAt = typeFlags; ref(edge, 3, type);
            ref(edge, 4, compact(0,3)); ref(edge, 5, packed(4,7,6)); ref(edge, 15, compact(0,1));
            dictionary = vector(new byte[]{(byte) 0x81,5}); ref(edge, 16, dictionary);
            int geometry = table(new int[]{4,8}, 12); ref(edge, 0, geometry);
            int sequence = table(new int[]{4}, 8); ref(geometry, 0, sequence);
            Serialized ef = new Serialized(); ef.number(0); ef.ef(new long[]{0,33,34}); ref(sequence, 0, vector(ef.result()));
            int shape = table(new int[]{4}, 8); ref(geometry, 1, shape);
            Bits point = new Bits(); point.rice(500); point.rice(600);
            Serialized shapeBytes = new Serialized(); shapeBytes.number(0); shapeBytes.bits(point);
            int shapeBlob = vector(shapeBytes.result()); shapeWord = shapeBlob + 4 + 24; ref(shape, 0, shapeBlob);
            int rtree = table(new int[]{4,36,40,44,48,52}, 56); ref(root, 3, rtree); bounds(rtree, 0);
            b.put(slot(rtree, 1), (byte) 32); ref(rtree, 2, ints(0));
            levelsVector = ints(0,1); ref(rtree, 3, levelsVector);
            // Low uint32 is the first omitted slot, high uint32 its count.
            // Four children in one 32-slot parent omit [4,32), not [4,4).
            gapsVector = longs(4 | (28L << 32)); ref(rtree, 4, gapsVector);
            ref(rtree, 5, packed(2,0,1,2,3)); ref(root, 6, string("26.10.fixture")); ref(root, 7, ints(43));
            typeFlags = typeAt; reverseWord = reversedWord; beginsWord = startsWord;
            return Arrays.copyOf(b.array(), b.position());
        }
    }

    private static void synthetic() throws Exception {
        Fixture fixture = new Fixture(); byte[] bytes = fixture.build(), original = bytes.clone();
        OfflineRegionalGraph graph = OfflineRegionalGraph.decode(bytes, "independent-fixture");
        check(graph.directedEdgeCount() == 4 && graph.admittedEdgeCount() == 3, "unadmitted non-base index retained in denominator");
        check(graph.regionIds()[0] == 43 && graph.version().equals("26.10.fixture"), "identity not tied to one Kazan hash/version");
        check(graph.format().equals(OfflineRegionalGraph.FORMAT), "explicit supported layout");
        OfflineRegionalGraph.Edge forward = graph.edge(0), reverse = graph.edge(1);
        check(forward.pointCount() == 3 && !forward.attributeGeometryReversed && reverse.attributeGeometryReversed,
                "full intermediate shape and canonical reversal are distinct from access");
        check(forward.rawType == 9 && forward.rawStructType == 0 && forward.rawCategory == 7 && forward.rawAccessMask == 129,
                "raw type 9 never clamped; unsigned access byte retained without labels");
        check(forward.sourceVertex == 0 && forward.targetVertex == 1 && reverse.sourceVertex == 1 && reverse.targetVertex == 0,
                "directed index source and target preserved");
        near(forward.lon(1), 1.0005, "Rice longitude"); near(forward.lat(1), 2.0006, "Rice latitude");
        for (int i = 0; i < forward.pointCount(); i++) {
            near(forward.lon(i), reverse.lon(reverse.pointCount() - 1 - i), "reverse full longitude");
            near(forward.lat(i), reverse.lat(reverse.pointCount() - 1 - i), "reverse full latitude");
        }
        check(forward.attributeIndex == reverse.attributeIndex && !forward.localId.equals(reverse.localId), "canonical attribute ID is not directed ID");
        check(forward.localId.equals(graph.sha256() + ":0") && forward.source.equals("independent-fixture"), "package-scoped stable provenance");
        double[] detached = forward.lonLat(); detached[0] = 99; near(forward.lon(0), 1, "shape array exposure is cloned");
        int[] detachedRegions = graph.regionIds(); detachedRegions[0] = 99; check(graph.regionIds()[0] == 43, "region list cloned");
        bytes[0] = 0; check(graph.edge(0).rawType == 9, "byte-array input defensively copied");
        try { graph.edge(3); throw new AssertionError("unproved non-base mapping admitted"); }
        catch (UnsupportedOperationException expected) { checks++; }
        OfflineRegionalGraph.Query all = graph.query(graph.bounds(), 100);
        check(all.edges.size() == 3 && all.skippedNonBase == 1 && !all.truncated && !all.exactRouteMatch, "query reports base gate and honest broad phase");
        check(graph.query(graph.bounds(), 1).truncated, "edge result budget is explicit truncation");
        OfflineRegionalGraph.Query pointLimited = graph.query(graph.bounds(), 100, 4);
        check(pointLimited.truncated && pointLimited.edges.size() == 1, "aggregate shape point budget is explicit truncation");
        check(graph.query(new OfflineRegionalGraph.Bounds(10,10,11,11), 1).edges.isEmpty(), "other geographic extent not selected");
        OfflineRegionalGraph.Query route = graph.queryRoute(new double[]{1,1.001}, new double[]{2,2.001}, 3, 100);
        check(!route.truncated && route.edges.size() >= 2, "whole route queried independently of current progress");
        double[] bendLon = new double[65], bendLat = new double[65];
        for (int i = 0; i < 65; i++) {
            bendLon[i] = i <= 32 ? i / 16.0 : 2; bendLat[i] = i <= 32 ? 1 : 1 + (i - 32) / 16.0;
        }
        check(graph.query(new OfflineRegionalGraph.Bounds(0,1,2,3), 100).edges.size() == 3,
                "test graph is inside the bend's whole-route rectangle");
        check(graph.queryRoute(bendLon, bendLat, 0, 100).edges.isEmpty(),
                "route blocks exclude remote inside-rectangle edges before materialization");
        try { all.edges.clear(); throw new AssertionError("mutable query results"); } catch (UnsupportedOperationException expected) { checks++; }
        ByteBuffer sliced = ByteBuffer.allocate(bytes.length + 4).order(ByteOrder.BIG_ENDIAN);
        sliced.position(2); sliced.put(original); sliced.limit(sliced.position()); sliced.position(2);
        int before = sliced.position(); ByteOrder order = sliced.order();
        check(OfflineRegionalGraph.decode(sliced, "slice").sha256().equals(graph.sha256()), "ByteBuffer remaining slice has canonical SHA");
        check(sliced.position() == before && sliced.order() == order, "caller buffer state unchanged");
    }

    private static void malformed() throws Exception {
        rejected(() -> OfflineRegionalGraph.decode(new byte[7], "short"), "short file");
        rejected(() -> OfflineRegionalGraph.decode(new byte[OfflineRegionalGraph.MAX_FILE_BYTES + 1], "large"), "file allocation budget");
        Fixture f = new Fixture(); byte[] bytes = f.build();
        rejected(() -> OfflineRegionalGraph.decode(bytes, ""), "source required");
        byte[] corrupt = bytes.clone(); ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN).putInt(0, Integer.MAX_VALUE);
        rejected(() -> OfflineRegionalGraph.decode(corrupt, "bad-root"), "root offset");
        byte[] flags = bytes.clone(); ByteBuffer.wrap(flags).order(ByteOrder.LITTLE_ENDIAN).putLong(f.typeFlags, 1);
        rejected(() -> OfflineRegionalGraph.decode(flags, "future-format"), "unsupported flags");
        byte[] reverse = bytes.clone(); ByteBuffer.wrap(reverse).order(ByteOrder.LITTLE_ENDIAN).putLong(f.reverseWord, 3L << 2);
        rejected(() -> OfflineRegionalGraph.decode(reverse, "bad-reverse"), "canonical attribute reference");
        byte[] unary = bytes.clone(); ByteBuffer.wrap(unary).order(ByteOrder.LITTLE_ENDIAN).putLong(f.beginsWord, 164);
        rejected(() -> OfflineRegionalGraph.decode(unary, "bad-adjacency"), "unary topology must account for every directed index");
        byte[] levels = bytes.clone(); ByteBuffer.wrap(levels).order(ByteOrder.LITTLE_ENDIAN).putInt(f.levelsVector + 8, 2);
        rejected(() -> OfflineRegionalGraph.decode(levels, "bad-tree"), "RTree cardinality");
        byte[] zeroGap = bytes.clone(); ByteBuffer.wrap(zeroGap).order(ByteOrder.LITTLE_ENDIAN).putLong(f.gapsVector + 4, 4);
        rejected(() -> OfflineRegionalGraph.decode(zeroGap, "zero-padding-count"), "interior zero-length gap cannot stand for 28 omitted slots");
        byte[] shortGap = bytes.clone(); ByteBuffer.wrap(shortGap).order(ByteOrder.LITTLE_ENDIAN).putLong(f.gapsVector + 4, 4 | (27L << 32));
        rejected(() -> OfflineRegionalGraph.decode(shortGap, "padding-before-parent-end"), "padding must end at the parent boundary");
        byte[] longGap = bytes.clone(); ByteBuffer.wrap(longGap).order(ByteOrder.LITTLE_ENDIAN).putLong(f.gapsVector + 4, 4 | (29L << 32));
        rejected(() -> OfflineRegionalGraph.decode(longGap, "padding-after-parent-end"), "padding cannot cross the parent boundary");
        byte[] dictionary = bytes.clone(); ByteBuffer.wrap(dictionary).order(ByteOrder.LITTLE_ENDIAN).putInt(f.dictionary, 300);
        rejected(() -> OfflineRegionalGraph.decode(dictionary, "bad-mask"), "mask dictionary byte bound");
        byte[] shape = bytes.clone(); ByteBuffer.wrap(shape).order(ByteOrder.LITTLE_ENDIAN).putLong(f.shapeWord, 0);
        rejected(() -> OfflineRegionalGraph.decode(shape, "bad-shape").edge(0), "Rice code cannot escape its shape slice");
        OfflineRegionalGraph graph = OfflineRegionalGraph.decode(bytes, "query-errors");
        rejected(() -> graph.query(graph.bounds(), 0), "zero result budget");
        rejected(() -> graph.query(graph.bounds(), OfflineRegionalGraph.MAX_QUERY_EDGES + 1), "large result budget");
        rejected(() -> graph.queryRoute(new double[]{1,2}, new double[]{1}, 3, 1), "route axes mismatch");
        rejected(() -> graph.queryRoute(new double[]{1,Double.NaN}, new double[]{1,2}, 3, 1), "nonfinite route coordinate");
        rejected(() -> graph.queryRoute(new double[]{1,2}, new double[]{1,2}, -1, 1), "negative padding");
        rejected(() -> new OfflineRegionalGraph.Bounds(2,1,1,2), "reversed box");
    }

    @SuppressWarnings("unchecked")
    private static void corpus(File root) throws Exception {
        File directory = new File(root, "captures/yandex-offline-region-20261024");
        File file = new File(directory, "region-43/driving.fb");
        OfflineRegionalGraph graph;
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            graph = OfflineRegionalGraph.decode(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()), "retained-region-43");
        }
        check(graph.sha256().equals("58747160de74c00d508231a49f20b8bb94144e267fe926653d5fbe853cc5a2b4"), "retained package SHA golden only, not parser allowlist");
        check(graph.directedEdgeCount() == 298104 && graph.admittedEdgeCount() == 297181, "independent host cardinality");
        Map<String,Object> oracle = (Map<String,Object>) Json.parse(new String(Files.readAllBytes(
                new File(directory, "native-reader/package-full-shape-summary.json").toPath()), StandardCharsets.UTF_8));
        for (Object value : (List<?>) oracle.get("examples")) {
            Map<String,Object> example = (Map<String,Object>) value, row = (Map<String,Object>) example.get("edge");
            OfflineRegionalGraph.Edge edge = graph.edge(((Number) row.get("directedEdgeIndex")).intValue());
            List<?> points = (List<?>) example.get("shapeLonLat");
            check(edge.pointCount() == points.size(), "host full-shape point count");
            check(edge.rawType == ((Number) row.get("rawType")).longValue()
                    && edge.rawStructType == ((Number) row.get("rawStructType")).longValue()
                    && edge.rawCategory == ((Number) row.get("rawCategory")).longValue(), "host raw enum bytes retained");
            check(edge.attributeGeometryReversed == (Boolean) row.get("attributeGeometryReversed"), "host reversal evidence");
            for (int i = 0; i < points.size(); i++) {
                List<?> pair = (List<?>) points.get(i);
                near(edge.lon(i), ((Number) pair.get(0)).doubleValue(), "host full longitude");
                near(edge.lat(i), ((Number) pair.get(1)).doubleValue(), "host full latitude");
            }
        }
        OfflineRegionalGraph.Query bounded = graph.query(graph.bounds(), 10);
        check(bounded.truncated && bounded.edges.size() == 10, "whole-region query remains explicitly bounded");
    }
    public static void main(String[] args) throws Exception {
        synthetic(); malformed(); corpus(new File(args.length == 0 ? "." : args[0]));
        System.out.println("OfflineRegionalGraphTest: " + checks + " checks");
    }
}
