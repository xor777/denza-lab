package dev.denza.adaslink;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Independent synthetic protobuf tests plus all seven captured Python-oracle tiles. */
public final class GraphTileTest {
    static int checks;
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    interface Action { void run() throws Exception; }
    static void fails(Action action, String message) throws Exception {
        boolean failed = false;
        try { action.run(); } catch (GraphTile.TileException expected) { failed = true; }
        check(failed, message);
    }
    static void immutable(Action action, String message) throws Exception {
        boolean failed = false;
        try { action.run(); } catch (UnsupportedOperationException expected) { failed = true; }
        check(failed, message);
    }
    static byte[] concat(byte[]... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) out.write(chunk, 0, chunk.length);
        return out.toByteArray();
    }
    static byte[] varint(long bits) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do { int b = (int) bits & 127; bits >>>= 7; out.write(b | (bits != 0 ? 128 : 0)); } while (bits != 0);
        return out.toByteArray();
    }
    static byte[] field(int number, long value) { return concat(varint((long) number << 3), varint(value)); }
    static byte[] message(int number, byte[] body) { return concat(varint(((long) number << 3) | 2), varint(body.length), body); }
    static byte[] packed(int number, long... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (long value : values) { byte[] encoded = varint(value); out.write(encoded, 0, encoded.length); }
        return message(number, out.toByteArray());
    }
    static byte[] axis(double[][] points, int axis) {
        long first = Math.round(points[0][axis] * 1e6), previous = first;
        ByteArrayOutputStream deltas = new ByteArrayOutputStream();
        for (int i = 1; i < points.length; i++) {
            long next = Math.round(points[i][axis] * 1e6), delta = next - previous; previous = next;
            byte[] value = varint((delta << 1) ^ (delta >> 63)); deltas.write(value, 0, value.length);
        }
        return concat(field(1, (first << 1) ^ (first >> 63)), message(2, deltas.toByteArray()));
    }
    static byte[] polyline(double[][] points) { return concat(message(1, axis(points, 0)), message(2, axis(points, 1))); }
    static byte[] data(long id, long field7, long field8, double[][] points, Float speed) {
        byte[] extra = new byte[0];
        if (speed != null) {
            int bits = Float.floatToRawIntBits(speed);
            extra = concat(varint((10L << 3) | 5), new byte[]{(byte) bits, (byte) (bits >>> 8), (byte) (bits >>> 16), (byte) (bits >>> 24)});
        }
        return concat(field(1, id), message(6, polyline(points)), field(7, field7), field(8, field8), packed(9, 9), extra);
    }
    /** Package-visible fixture helper for adapters: actual directed IDs, explicit endpoints. */
    static byte[] tile(long edgeId, long dataId, long from, long to, int field7, int field8, boolean reverse,
                       double[][] points, String name, Float speed) {
        return concat(message(1, concat(field(1, from), packed(4, edgeId), field(5, -1))),
            message(1, concat(field(1, to), packed(3, edgeId), field(5, 2))),
            message(2, concat(field(1, edgeId), field(2, dataId), field(3, reverse ? 1 : 0))),
            message(3, data(dataId, field7, field8, points, speed)),
            message(4, concat(field(1, 9), message(2, name.getBytes(StandardCharsets.UTF_8)))));
    }
    static byte[] fixture(long edgeId) {
        return tile(edgeId, 77, 1, 2, 0, 0, false, new double[][]{{0, 0}, {.001, 0}}, "Synthetic Road", 12.5f);
    }

    @SuppressWarnings("unchecked") static void synthetic() throws Exception {
        long unsigned = Long.MIN_VALUE + 23;
        byte[] raw = fixture(unsigned);
        GraphTile tile = GraphTile.decode(raw, "synthetic");
        GraphTile.Edge edge = tile.edge(unsigned);
        check(edge != null && edge.id == unsigned && tile.edgeIds().contains(unsigned), "uint64 directed ID bits preserved");
        check(edge.from == 1L && edge.to == 2L && edge.completeStoredEndpoints, "explicit directed adjacency endpoints");
        check(edge.points.size() == 2 && edge.points.get(1).lon == .001 && edge.points.get(1).lat == 0, "WGS84 lon,lat delta geometry");
        check(edge.names.equals(Arrays.asList("Synthetic Road")) && edge.speedLimitMps == 12.5, "name and legal speed decoded");
        check(edge.beginZLevel == -1 && edge.endZLevel == 2, "signed int32 z levels");
        check(edge.rawField11 == null, "absent field11 remains null");
        check(!tile.toJson().contains("roadClass") && !tile.toJson().contains("laneNum"), "unknown attributes not assigned class or lanes");
        immutable(() -> tile.toMap().put("changed", true), "root map immutable");
        immutable(() -> tile.edges().clear(), "typed edges immutable");
        immutable(() -> edge.points.clear(), "typed geometry immutable");
        immutable(() -> edge.names.add("changed"), "typed names immutable");
        immutable(() -> ((Map<String, Object>) tile.toMap().get("summary")).clear(), "nested canonical map immutable");
        String before = tile.toJson(); raw[0] = 0; check(before.equals(tile.toJson()), "input buffer not retained");

        GraphTile reversed = GraphTile.decode(tile(5, 77, 1, 2, 1, 0, true,
            new double[][]{{0, 0}, {.001, 0}}, "R", null), "reverse");
        check(reversed.edge(5).from == 1L && reversed.edge(5).to == null, "reverse swaps native anonymous endpoint markers");
        check(reversed.edge(5).points.get(0).lon == .001 && reversed.edge(5).reverseGeometry, "reverse flag changes point order");
        check(reversed.edge(5).speedLimitMps == null, "missing speed is not a default");
        GraphTile missing = GraphTile.decode(message(2, concat(field(1, 4), field(2, 77), field(3, 0))), "missing");
        check(missing.edge(4).from == null && missing.edge(4).to == null && !missing.edge(4).hasGeometry, "missing data retains unknown endpoints and geometry");
        GraphTile unknown = GraphTile.decode(concat(fixture(1), field(99, -1L)), "unknown");
        List<Object> unknowns = (List<Object>) unknown.toMap().get("unknownRootFields");
        check("18446744073709551615".equals(((Map<?, ?>) unknowns.get(0)).get("unsignedDecimal")), "unknown uint64 exported unsigned exactly");
        check(tile.estimatedRetainedBytes() > tile.sourceBytes(), "cache charge includes decoded structure");

        byte[][] malformed = {
            new byte[]{(byte) 0x80}, new byte[]{0}, new byte[]{11}, new byte[]{10, 5, 1},
            new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 2},
            message(2, concat(field(1, 1), field(2, 77), field(3, 2))),
            message(2, concat(field(1, 1), field(1, 2), field(2, 77), field(3, 0))),
            message(4, concat(field(1, 9), message(2, new byte[]{(byte) 255}))),
            message(3, data(77, 1L << 32, 0, new double[][]{{0, 0}, {.001, 0}}, null)),
            message(3, data(77, 0, 0, new double[][]{{181, 0}, {181, 0}}, null)),
            message(3, data(77, 0, 0, new double[][]{{0, 0}, {.001, 0}}, Float.NaN)),
            message(3, data(77, 0, 0, new double[][]{{0, 0}, {.001, 0}}, -1f)),
            concat(message(1, field(1, 1)), message(1, field(1, 1))),
            message(3, concat(field(1, 77), message(6, concat(message(1, axis(new double[][]{{0, 0}, {1, 1}}, 0)),
                message(2, axis(new double[][]{{0, 0}}, 1)))), field(7, 0), field(8, 0)))
        };
        for (int i = 0; i < malformed.length; i++) { final byte[] bad = malformed[i]; fails(() -> GraphTile.decode(bad, "bad"), "malformed case " + i); }
        final byte[] valid = fixture(1);
        fails(() -> GraphTile.decode(valid, "bytes", new GraphTile.Limits(valid.length - 1, 1000, 1000, 1000, 1000)), "byte budget");
        fails(() -> GraphTile.decode(valid, "fields", new GraphTile.Limits(10000, 4, 1000, 1000, 1000)), "field budget");
        fails(() -> GraphTile.decode(valid, "entities", new GraphTile.Limits(10000, 1000, 4, 1000, 1000)), "entity budget");
        fails(() -> GraphTile.decode(valid, "points", new GraphTile.Limits(10000, 1000, 1000, 1, 1000)), "point budget");
        fails(() -> GraphTile.decode(valid, "strings", new GraphTile.Limits(10000, 1000, 1000, 1000, 2)), "name byte budget");
        long[] packed = new long[100]; Arrays.fill(packed, 3);
        fails(() -> GraphTile.decode(message(1, concat(field(1, 1), packed(3, packed))), "packed",
            new GraphTile.Limits(10000, 40, 1000, 1000, 1000)), "packed integer budget");
    }

    @SuppressWarnings("unchecked") static void compare(Object expected, Object actual, String path) {
        if (expected instanceof Number && actual instanceof Number) {
            check(new java.math.BigDecimal(expected.toString()).compareTo(new java.math.BigDecimal(actual.toString())) == 0, "oracle number " + path + ": " + expected + " != " + actual);
        } else if (expected instanceof Map && actual instanceof Map) {
            Map<String, Object> a = (Map<String, Object>) expected, b = (Map<String, Object>) actual;
            check(a.keySet().equals(b.keySet()), "oracle keys " + path);
            for (String key : a.keySet()) compare(a.get(key), b.get(key), path + "." + key);
        } else if (expected instanceof List && actual instanceof List) {
            List<?> a = (List<?>) expected, b = (List<?>) actual;
            check(a.size() == b.size(), "oracle list size " + path);
            for (int i = 0; i < a.size(); i++) compare(a.get(i), b.get(i), path + "[" + i + "]");
        } else check(expected == null ? actual == null : expected.equals(actual), "oracle value " + path + ": " + expected + " != " + actual);
    }

    @SuppressWarnings("unchecked") static void captured(File root) throws Exception {
        File directory = new File(root, "captures/yandex-java-graph-20261024/oracle");
        Map<String, Object> manifest = (Map<String, Object>) Json.parse(new String(Files.readAllBytes(new File(directory, "manifest.json").toPath()), StandardCharsets.UTF_8));
        List<Object> entries = (List<Object>) manifest.get("tiles");
        check(entries.size() == 7, "seven independent captured tiles required");
        for (Object value : entries) {
            Map<String, Object> entry = (Map<String, Object>) value;
            String input = (String) entry.get("input"), expectedPath = (String) entry.get("expected");
            GraphTile tile = GraphTile.decode(Files.readAllBytes(new File(root, input).toPath()), input);
            Object expected = Json.parse(new String(Files.readAllBytes(new File(root, expectedPath).toPath()), StandardCharsets.UTF_8));
            compare(expected, tile.toMap(), input);
            Files.write(new File(directory, new File(input).getName() + ".java.json").toPath(), (tile.toJson() + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--decode".equals(args[0])) {
            String source = args[1]; GraphTile tile = GraphTile.decode(Files.readAllBytes(new File(source).toPath()), source);
            Files.write(new File(args[2]).toPath(), (tile.toJson() + "\n").getBytes(StandardCharsets.UTF_8)); return;
        }
        synthetic();
        if (args.length > 0 && !"--synthetic".equals(args[0])) captured(new File(args[0]));
        System.out.println("GraphTileTest: " + checks + " checks, 0 failures");
    }
}
