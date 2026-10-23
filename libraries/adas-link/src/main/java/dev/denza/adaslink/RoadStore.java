package dev.denza.adaslink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The road attribute store file ({@code tools/adas_bridge/road_store.py}, format in its docstring)
 * over a {@link ByteBuffer}: the host hands in the bytes (a memory-mapped file inside Navigator),
 * nothing here touches the file system. Fragments are decoded on first use and kept while the
 * cache is small. Mirrored by {@code tools/adas_bridge/roads.py} RoadStore.
 */
public final class RoadStore implements RoadNetwork {
    static final int SCALE = 3600000;
    static final int FRAGMENT_BYTES = 25;
    static final int NO_NAME = 0xFFFFFF;
    private static final int CACHE_LIMIT = 20_000;

    private final ByteBuffer b;
    private final Coordinates.Frame frame;
    private final int originX, originY, cellX, cellY, cols, rows, nodes, fragments, names;
    private final double mx, my;
    private final int oNodes, oAdj, oFrag, oGeom, oCells, oItems, oNames;
    private final Map<Integer, Fragment> cache = new HashMap<>();
    private final String[] nameCache;

    public RoadStore(ByteBuffer source) {
        b = source.duplicate().order(ByteOrder.BIG_ENDIAN);
        b.position(0);
        if (b.limit() < 92 || b.get(0) != 'D' || b.get(1) != 'Z' || b.get(2) != 'R' || b.get(3) != 'N')
            throw new IllegalArgumentException("not a road store");
        if ((b.getShort(4) & 0xFFFF) != 1) throw new IllegalArgumentException("road store version");
        int code = b.getShort(6) & 0xFFFF;
        if (code == 0) frame = Coordinates.Frame.WGS84;
        else if (code == 1) frame = Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE;
        else throw new IllegalArgumentException("road store frame");
        originX = b.getInt(8); originY = b.getInt(12);
        mx = b.getDouble(16); my = b.getDouble(24);
        cellX = b.getInt(32); cellY = b.getInt(36); cols = b.getInt(40); rows = b.getInt(44);
        nodes = b.getInt(48); fragments = b.getInt(52); names = b.getInt(56);
        oNodes = b.getInt(60); oAdj = b.getInt(64); oFrag = b.getInt(68); oGeom = b.getInt(72);
        oCells = b.getInt(76); oItems = b.getInt(80); oNames = b.getInt(84);
        if (b.getInt(88) != b.limit()) throw new IllegalArgumentException("road store length");
        nameCache = new String[names];
    }

    public int nodeCount() { return nodes; }
    public int fragmentCount() { return fragments; }

    @Override public Coordinates.Frame frame() { return frame; }
    @Override public double planarX(double lon) { return (lon * SCALE - originX) * mx; }
    @Override public double planarY(double lat) { return (lat * SCALE - originY) * my; }
    @Override public double nodeX(int node) { return (b.getInt(oNodes + 8 * node) - originX) * mx; }
    @Override public double nodeY(int node) { return (b.getInt(oNodes + 8 * node + 4) - originY) * my; }
    @Override public int adjacencyStart(int node) { return b.getInt(oAdj + 4 * node); }
    @Override public int adjacencyEnd(int node) { return b.getInt(oAdj + 4 * (node + 1)); }
    @Override public int adjacency(int k) { return b.getInt(oAdj + 4 * (nodes + 1) + 4 * k); }

    @Override public int[] near(double x, double y, double radius) {
        double wx = cellX * mx, wy = cellY * my, r = radius + 1.0;
        int cx0 = Math.max(0, (int) Math.floor((x - r) / wx)), cx1 = Math.min(cols - 1, (int) Math.floor((x + r) / wx));
        int cy0 = Math.max(0, (int) Math.floor((y - r) / wy)), cy1 = Math.min(rows - 1, (int) Math.floor((y + r) / wy));
        int count = 0;
        for (int cy = cy0; cy <= cy1; cy++) for (int cx = cx0; cx <= cx1; cx++) {
            int c = cy * cols + cx;
            count += b.getInt(oCells + 4 * (c + 1)) - b.getInt(oCells + 4 * c);
        }
        if (count == 0) return new int[0];
        int[] all = new int[count];
        int n = 0;
        for (int cy = cy0; cy <= cy1; cy++) for (int cx = cx0; cx <= cx1; cx++) {
            int c = cy * cols + cx;
            for (int k = b.getInt(oCells + 4 * c), end = b.getInt(oCells + 4 * (c + 1)); k < end; k++) all[n++] = b.getInt(oItems + 4 * k);
        }
        Arrays.sort(all);
        int unique = 0;
        for (int i = 0; i < n; i++) if (i == 0 || all[i] != all[i - 1]) all[unique++] = all[i];
        return Arrays.copyOf(all, unique);
    }

    @Override public Fragment fragment(int index) {
        Fragment f = cache.get(index);
        if (f != null) return f;
        if (index < 0 || index >= fragments) throw new IndexOutOfBoundsException("fragment " + index);
        int at = oFrag + FRAGMENT_BYTES * index;
        int a = b.getInt(at), to = b.getInt(at + 4), offset = b.getInt(at + 8), count = b.getShort(at + 12) & 0xFFFF;
        int rc = b.get(at + 14) & 255, fw = b.get(at + 15) & 255, lt = b.get(at + 16) & 255, lanes = b.get(at + 17) & 255;
        int flags = b.get(at + 18) & 255, speed = b.get(at + 19) & 255, legalF = b.get(at + 20) & 255, legalB = b.get(at + 21) & 255;
        int ni = ((b.get(at + 22) & 255) << 16) | ((b.get(at + 23) & 255) << 8) | (b.get(at + 24) & 255);
        double[] xs = new double[count], ys = new double[count], cum = new double[count];
        int ux = b.getInt(oNodes + 8 * a), uy = b.getInt(oNodes + 8 * a + 4);
        xs[0] = (ux - originX) * mx;
        ys[0] = (uy - originY) * my;
        int p = oGeom + offset;
        for (int k = 1; k < count; k++) {
            long[] value = new long[2];
            for (int v = 0; v < 2; v++) {
                long result = 0;
                int shift = 0;
                while (true) {
                    int octet = b.get(p++) & 255;
                    result |= (long) (octet & 0x7F) << shift;
                    shift += 7;
                    if (octet < 0x80) break;
                }
                value[v] = (result >>> 1) ^ -(result & 1);
            }
            ux += (int) value[0];
            uy += (int) value[1];
            xs[k] = (ux - originX) * mx;
            ys[k] = (uy - originY) * my;
        }
        for (int k = 0; k + 1 < count; k++) {
            double dx = xs[k + 1] - xs[k], dy = ys[k + 1] - ys[k];
            cum[k + 1] = cum[k] + Math.sqrt(dx * dx + dy * dy);
        }
        f = new Fragment(index, a, to, rc, fw, lt, lanes, (flags >> 2) & 1, (flags >> 3) & 1, speed, legalF, legalB,
                (flags & 1) != 0, (flags & 2) != 0, ni == NO_NAME ? "" : name(ni), xs, ys, cum);
        if (cache.size() >= CACHE_LIMIT) cache.clear();
        cache.put(index, f);
        return f;
    }

    private String name(int i) {
        String s = nameCache[i];
        if (s != null) return s;
        int begin = b.getInt(oNames + 4 * i), end = b.getInt(oNames + 4 * (i + 1));
        int base = oNames + 4 * (names + 1);
        byte[] bytes = new byte[end - begin];
        for (int k = 0; k < bytes.length; k++) bytes[k] = b.get(base + begin + k);
        s = new String(bytes, StandardCharsets.UTF_8);
        nameCache[i] = s;
        return s;
    }
}
