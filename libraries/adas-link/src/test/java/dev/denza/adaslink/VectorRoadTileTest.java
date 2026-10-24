package dev.denza.adaslink;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Shared geometry cursors, hostile protobuf bounds, typed literals, and the pinned native-success corpus. */
public final class VectorRoadTileTest {
    static int checks;
    static final VectorRoadTile.TileId ID = new VectorRoadTile.TileId(10922, 16384, 15);
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    static void fails(Runnable action, String message) {
        try { action.run(); } catch (VectorRoadTile.TileException e) { checks++; return; }
        throw new AssertionError(message);
    }
    static void immutable(Runnable action) {
        try { action.run(); } catch (UnsupportedOperationException e) { checks++; return; }
        throw new AssertionError("immutable collection mutated");
    }
    static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length); return out.toByteArray();
    }
    static byte[] vi(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do { int b = (int) (value & 127); value >>>= 7; out.write(b | (value == 0 ? 0 : 128)); } while (value != 0);
        return out.toByteArray();
    }
    static byte[] v(int f, long n) { return join(vi((long) f << 3), vi(n)); }
    static byte[] msg(int f, byte[] raw) { return join(vi((long) f << 3 | 2), vi(raw.length), raw); }
    static byte[] str(int f, String s) { return msg(f, s.getBytes(StandardCharsets.UTF_8)); }
    static byte[] float32(int f, float n) {
        int bits = Float.floatToRawIntBits(n);
        return join(vi((long) f << 3 | 5), new byte[]{(byte) bits, (byte) (bits >>> 8), (byte) (bits >>> 16), (byte) (bits >>> 24)});
    }
    static byte[] packed(int f, long... n) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); for (long a : n) { byte[] b = vi(a); out.write(b, 0, b.length); }
        return msg(f, out.toByteArray());
    }
    static byte[] coords(int... n) { long[] p = new long[n.length]; for (int i = 0; i < n.length; i++) p[i] = ((long) n[i] << 1) ^ (n[i] >> 31); return packed(2, p); }
    static byte[] feature(byte[] geometry, long id) { return join(geometry, v(5, id)); }
    static byte[] line(int parts) { return msg(2, v(1, parts)); }
    static byte[] layer(String name, byte[]... features) {
        byte[] raw = str(1, name); for (byte[] f : features) raw = join(raw, msg(2, f)); return msg(6, raw);
    }
    static byte[] fixture(long id) {
        byte[] keys = msg(4, join(str(1, "class"), str(1, "fc"), str(1, "oneway")));
        byte[] values = join(msg(5, str(1, "4_link")), msg(5, v(3, 4)), msg(5, str(1, "1")));
        return join(v(1, 14), coords(-8192, -8192, 16383, 16383), packed(3, 2), keys, values,
                layer("road", join(feature(line(1), id), msg(6, join(v(1, 0), packed(2, 0, 1, 2))))));
    }
    static VectorRoadTile decode(byte[] raw) { return VectorRoadTile.decode(raw, ID, ID, ID); }
    static void synthetic() {
        byte[] raw = fixture(-1); VectorRoadTile tile = decode(raw);
        check(tile.tileId().equals(ID) && tile.sourceBytes() == raw.length, "observed context and source length");
        check(tile.sha256().equals(VectorRoadTile.digest(raw)), "source SHA exact bytes");
        VectorRoadTile.RoadFeature f = tile.features().get(0);
        check(f.hasId && f.unsignedId().equals("18446744073709551615"), "uint64 identity bits preserved");
        check(f.points.size() == 2 && f.partIndex == 0 && f.featureIndex == 0, "one retained road line");
        check(f.attributes.get("class").equals("4_link") && f.attributes.get("fc").equals(4L) && f.attributes.get("oneway").equals("1"), "literal types remain distinct");
        check(f.attributeTypes.get("class").equals("string") && f.attributeTypes.get("fc").equals("uint32"), "protobuf scalar types retained");
        double size = 1L << (30 - ID.z);
        double x0 = ID.x * size, x1 = (ID.x + 1) * size;
        check(Math.abs(f.points.get(0).lon - (360 * x0 / (1L << 30) - 180)) < 1e-12, "inclusive grid minimum");
        check(Math.abs(f.points.get(1).lon - (360 * x1 / (1L << 30) - 180)) < 1e-12, "inclusive grid maximum denominator16383");
        immutable(() -> tile.features().clear()); immutable(() -> f.points.clear()); immutable(() -> f.attributes.clear()); immutable(() -> f.attributeTypes.clear());
        String hash = tile.sha256(); raw[0] = 0; check(tile.sha256().equals(hash) && f.attributes.get("class").equals("4_link"), "input mutation cannot alter retained tile");

        byte[] all = join(v(1, 14), coords(0, 0, Integer.MAX_VALUE, Integer.MIN_VALUE, 10, 20, 1, 2, -3, -5, 0, 0, 1, 2,
                2, 3, 1, 1, 4, 5, 1, 1), packed(3, 2, 2, 3, 2, 2),
                layer("poi", feature(msg(1, new byte[0]), 3)),
                layer("road", feature(line(2), 4)),
                layer("area", feature(msg(3, join(packed(1, 1), v(2, 1))), 5)));
        VectorRoadTile mixed = decode(all);
        check(mixed.features().size() == 2, "only road MultiLineString parts retained");
        check(mixed.features().get(0).points.size() == 2 && mixed.features().get(1).points.size() == 2, "per-component delta reset and zero delta collapse");
        check(mixed.features().get(0).points.get(0).lon > mixed.features().get(1).points.get(0).lon, "point tail skipped; road starts at its own absolute coordinate");
        check(((Number) mixed.summary().get("sourcePaths")).intValue() == 5, "polygon outer and auxiliary consumed after roads");
        byte[] empty = join(v(1, 14), coords(), packed(3), layer("road", feature(line(0), 1)));
        check(decode(empty).features().isEmpty(), "empty valid line has no parts");
        byte[] noId = join(v(1, 14), coords(0, 0), packed(3, 1), layer("road", line(1)));
        check(!decode(noId).features().get(0).hasId && decode(noId).features().get(0).unsignedId() == null, "absent vendor ID distinct fromzero");
        for (int z : new int[]{0, 1, 14, 30}) {
            VectorRoadTile.TileId t = new VectorRoadTile.TileId((int) ((1L << z) - 1), 0, z);
            VectorRoadTile g = VectorRoadTile.decode(fixture(1), t, t, t);
            check(Double.isFinite(g.features().get(0).points.get(0).lat), "global pyramid latitude finite atzoom" + z);
        }
        typedValues();
        bad();
    }
    @SuppressWarnings("unchecked") static void typedValues() {
        byte[] keys = msg(4, join(str(1, "class"), str(1, "fc"), str(1, "oneway"), str(1, "struct"), str(1, "zlev"),
                str(1, "min_zlev"), str(1, "max_zlev"), str(1, "unrelated")));
        byte[] values = join(msg(5, v(2, 5)), msg(5, v(3, 0xffffffffL)), msg(5, v(6, 1)), msg(5, v(4, -1)),
                msg(5, float32(5, 1.25f)), msg(5, msg(7, join(float32(1, 2.5f), msg(1, Arrays.copyOfRange(float32(1, 3.5f), 1, 5))))),
                msg(5, msg(8, join(str(1, "upper"), str(1, "lower")))), msg(5, str(1, "drop")));
        byte[] raw = join(v(1, 14), coords(0, 0), packed(3, 1), keys, values,
                layer("road", join(line(1), msg(6, join(v(1, 0), packed(2, 0, 1, 2, 3, 4, 5, 6, 7))))));
        VectorRoadTile.RoadFeature f = decode(raw).features().get(0);
        check(f.attributes.get("class").equals(-3L) && f.attributeTypes.get("class").equals("sint32"), "zigzag value preserves signed type");
        check(f.attributes.get("fc").equals(0xffffffffL), "uint32 upper bound remains positive long");
        check(f.attributes.get("oneway").equals(Boolean.TRUE), "bool is not string one");
        check(f.attributes.get("struct").equals("18446744073709551615") && f.attributeTypes.get("struct").equals("uint64"), "uint64 decimal with explicit type");
        check(f.attributes.get("zlev").equals(1.25f), "float32 remains float");
        check(f.attributes.get("min_zlev").equals(Arrays.asList(2.5f, 3.5f)), "packed/unpacked float array support");
        check(f.attributes.get("max_zlev").equals(Arrays.asList("upper", "lower")), "string array preserves order");
        check(!f.attributes.containsKey("unrelated"), "unrelated attributes not retained");
        immutable(() -> ((List<Object>) f.attributes.get("max_zlev")).clear());
        immutable(() -> ((List<Object>) f.attributes.get("min_zlev")).clear());
        byte[] unpacked = join(v(1, 14), v(2, 0), v(2, 0), v(3, 1), layer("road", line(1)));
        check(decode(unpacked).features().get(0).points.size() == 1, "unpacked shared buffers supported");
        byte[] unknown = join(unpacked, v(123, -1), msg(124, new byte[]{(byte) 255}));
        check(decode(unknown).features().size() == 1, "unknown fields skipped without interpreting binary");
        byte[] defaults = join(v(1, 14), coords(0, 0), packed(3, 1), layer("road", msg(2, new byte[0])));
        check(decode(defaults).features().size() == 1, "missing line count means one native component");
    }
    static void bad() {
        fails(() -> new VectorRoadTile.TileId(1, 0, 0), "invalid TileId");
        fails(() -> new VectorRoadTile.TileId(0, 0, 31), "unsupported zoom");
        fails(() -> VectorRoadTile.decode(fixture(1), ID, new VectorRoadTile.TileId(0, 0, 0), ID), "overzoom mismatch rejected");
        fails(() -> VectorRoadTile.decode(fixture(1), ID, null, ID), "missing context rejected");
        fails(() -> decode(new byte[0]), "empty input"); fails(() -> decode(new byte[]{(byte) 0x80}), "truncated varint");
        fails(() -> decode(join(v(1, 14), v(1, 14))), "duplicate grid field");
        fails(() -> decode(join(v(1, 14), coords(1), packed(3, 1))), "unpaired coordinate");
        fails(() -> decode(join(v(1, 14), coords(1, 2), packed(3, 2))), "shared buffer underflow");
        fails(() -> decode(join(v(1, 14), coords(1, 2), packed(3, 1))), "unconsumed shared path");
        fails(() -> decode(join(v(1, 14), coords(0, 0), packed(3, 1), layer("road", join(line(1), line(1))))), "duplicate feature geometry");
        fails(() -> decode(join(v(1, 14), coords(), packed(3, 0), layer("poi", msg(1, new byte[0])))), "empty Point cross-path peek");
        fails(() -> decode(join(v(1, 14), coords(8192, 0), packed(3, 1), layer("road", line(1)))), "grid maximum inclusive");
        fails(() -> decode(join(v(1, 31), coords((1 << 30) - 1, 0, Integer.MAX_VALUE, 0), packed(3, 2), layer("road", line(1)))), "int32 accumulated overflow");
        fails(() -> decode(join(fixture(1), msg(7, new byte[0]), msg(7, new byte[0]))), "duplicate root opaque storage");
        byte[] glb = join(fixture(1), msg(7, new byte[]{(byte) 255, (byte) 254, 0}));
        check(decode(glb).features().size() == 1, "binary root storage is opaque, not text");
        byte[] attr = join(v(1, 14), coords(0, 0), packed(3, 1), msg(4, str(1, "class")), msg(5, str(1, "7")));
        fails(() -> decode(join(attr, layer("road", join(line(1), msg(6, join(v(1, 1), packed(2, 0))))))), "out of range keyset");
        fails(() -> decode(join(attr, layer("road", join(line(1), msg(6, join(v(1, 0), packed(2, 1))))))), "out of range dictionary value");
        fails(() -> decode(join(attr, layer("road", join(line(1), msg(6, v(1, 0)))))), "dictionary cardinality");
        fails(() -> decode(join(attr, msg(5, join(str(1, "7"), v(3, 7))))), "multiple attribute oneof cases");
        fails(() -> decode(join(attr, msg(5, msg(1, new byte[]{(byte) 255})))), "UTF-8 invalid dictionary string");
        byte[] nan = join(vi(5L << 3 | 5), new byte[]{0, 0, (byte) 192, 127});
        byte[] nf = join(v(1, 14), coords(0, 0), packed(3, 1), msg(4, str(1, "fc")), msg(5, nan),
                layer("road", join(line(1), msg(6, join(v(1, 0), packed(2, 0))))));
        fails(() -> decode(nf), "retained nonfinite literal rejected");
        fails(() -> VectorRoadTile.decode(fixture(1), ID, ID, ID, new VectorRoadTile.Limits(10, 500000, 100000, 65536, 100000, 200000, 500000, 100000)), "input byte limit");
        fails(() -> VectorRoadTile.decode(fixture(1), ID, ID, ID, new VectorRoadTile.Limits(10000, 2, 100000, 65536, 100000, 200000, 500000, 100000)), "field budget");
        fails(() -> VectorRoadTile.decode(fixture(1), ID, ID, ID, new VectorRoadTile.Limits(10000, 500000, 1, 65536, 100000, 200000, 500000, 100000)), "entity budget");
        fails(() -> VectorRoadTile.decode(fixture(1), ID, ID, ID, new VectorRoadTile.Limits(10000, 500000, 100000, 2, 100000, 200000, 500000, 100000)), "string budget");
        fails(() -> VectorRoadTile.decode(fixture(1), ID, ID, ID, new VectorRoadTile.Limits(10000, 500000, 100000, 65536, 100000, 200000, 1, 100000)), "point budget");
    }
    @SuppressWarnings("unchecked") static List<VectorRoadTile> corpus(Path root) throws Exception {
        Path dir = root.resolve("captures/yandex-vector-world-20261024/runtime");
        if (!Files.isRegularFile(dir.resolve("events.jsonl"))) throw new AssertionError("required native-success corpus absent: " + dir);
        List<VectorRoadTile> result = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("events.jsonl"), StandardCharsets.UTF_8)) {
            Map<String, Object> event = (Map<String, Object>) Json.parse(line);
            if (!event.containsKey("binaryFile")) continue;
            Map<String, Object> p = (Map<String, Object>) event.get("payload");
            check(Boolean.TRUE.equals(p.get("nativeParseSucceeded")), "corpus native parse succeeded");
            check(((Number) p.get("threadId")).longValue() > 0, "corpus positive thread ID");
            byte[] raw = Files.readAllBytes(dir.resolve((String) event.get("binaryFile")));
            check(VectorRoadTile.digest(raw).equals(event.get("sha256")) && raw.length == ((Number) event.get("binarySize")).intValue(), "corpus pinned raw bytes");
            VectorRoadTile tile = VectorRoadTile.decode(raw, observed(p.get("tileId")), observed(p.get("renderTileId")), observed(p.get("layerSourceTileId")));
            result.add(tile);
        }
        check(result.size() == 8, "all eight original native-success captures required"); return result;
    }
    @SuppressWarnings("unchecked") static VectorRoadTile.TileId observed(Object object) {
        Map<String, Object> m = (Map<String, Object>) object;
        return new VectorRoadTile.TileId(((Number) m.get("x")).intValue(), ((Number) m.get("y")).intValue(), ((Number) m.get("z")).intValue());
    }
    static void dump(List<VectorRoadTile> tiles) {
        for (VectorRoadTile tile : tiles) for (VectorRoadTile.RoadFeature f : tile.features()) {
            Map<String, Object> row = Json.object(); row.put("sha256", tile.sha256()); row.put("featureId", f.unsignedId());
            row.put("featureIndex", f.featureIndex); row.put("partIndex", f.partIndex); row.put("attributes", f.attributes); row.put("attributeTypes", f.attributeTypes);
            List<Object> points = new ArrayList<>(); for (VectorRoadTile.Point p : f.points) points.add(Arrays.asList(p.lon, p.lat));
            row.put("points", points); System.out.println(Json.write(row));
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length == 0 ? "." : args[0]); boolean dump = args.length > 1 && args[1].equals("--dump-corpus");
        if (!dump) synthetic(); List<VectorRoadTile> tiles = corpus(root);
        if (dump) dump(tiles); else System.out.println("VectorRoadTileTest: " + checks + " checks passed; corpus=" + tiles.size());
    }
}
