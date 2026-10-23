package dev.denza.adaslink;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static dev.denza.adaslink.AdasLinkTest.check;
import static dev.denza.adaslink.AdasLinkTest.equal;

/**
 * RoadStore + RoadMatcher + RouteConverter on synthetic networks near (0, 0) in the identity frame,
 * each built so a wrong matcher answers differently (tools/adas_bridge/tests/test_roads.py has the
 * same cases against the Python oracle, and checks both give the same bytes). The store is written
 * here by a small encoder of the format in tools/adas_bridge/road_store.py.
 */
final class RoadTests {
    static final double LAT0 = 0.01;
    static final double M_LAT = 6371008.8 * Math.PI / 180, M_LON = M_LAT * Math.cos(Math.toRadians(LAT0));

    static final class F {
        final String a, b, name;
        final double[][] points;
        final int roadClass, formway, lanes, ownership;
        final boolean forward, backward;
        int legalForward, legalBackward;
        F legal(int forward, int backward) { legalForward = forward; legalBackward = backward; return this; }
        F(String a, String b, double[][] points, String name, int roadClass, int formway, int lanes, boolean forward, boolean backward, int ownership) {
            this.a = a; this.b = b; this.points = points; this.name = name; this.roadClass = roadClass; this.formway = formway;
            this.lanes = lanes; this.forward = forward; this.backward = backward; this.ownership = ownership;
        }
    }

    static F two(String a, String b, String name, int rc, int fw, int lanes, double... xy) { return new F(a, b, pts(xy), name, rc, fw, lanes, true, true, 0); }
    static F one(String a, String b, String name, int rc, int fw, int lanes, double... xy) { return new F(a, b, pts(xy), name, rc, fw, lanes, true, false, 0); }
    static F own(String a, String b, String name, double... xy) { return new F(a, b, pts(xy), name, 9, 15, 1, true, true, 1); }

    static double[][] pts(double... xy) {
        double[][] p = new double[xy.length / 2][];
        for (int i = 0; i < p.length; i++) p[i] = new double[]{xy[2 * i], xy[2 * i + 1]};
        return p;
    }

    static double[] deg(double x, double y) { return new double[]{0.01 + x / M_LON, LAT0 + y / M_LAT}; }

    /** The store file of a synthetic network (tools/adas_bridge/road_store.py encode, identity frame). */
    static ByteBuffer store(List<F> fragments) {
        Map<String, Integer> nodeIndex = new LinkedHashMap<>();
        List<int[]> nodes = new ArrayList<>();
        List<int[][]> grids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        int[][] ends = new int[fragments.size()][2];
        for (int i = 0; i < fragments.size(); i++) {
            F f = fragments.get(i);
            int[][] g = new int[f.points.length][];
            for (int k = 0; k < g.length; k++) {
                double[] d = deg(f.points[k][0], f.points[k][1]);
                g[k] = new int[]{(int) Math.round(d[0] * RoadStore.SCALE), (int) Math.round(d[1] * RoadStore.SCALE)};
                minX = Math.min(minX, g[k][0]); minY = Math.min(minY, g[k][1]); maxX = Math.max(maxX, g[k][0]); maxY = Math.max(maxY, g[k][1]);
            }
            grids.add(g);
            ends[i][0] = node(nodeIndex, nodes, f.a, g[0]);
            ends[i][1] = node(nodeIndex, nodes, f.b, g[g.length - 1]);
            if (f.name != null && !names.contains(f.name)) names.add(f.name);
        }
        double k = 6371008.8 * Math.PI / 180.0 / RoadStore.SCALE;
        double mx = k * Math.cos(Math.toRadians((minY + maxY) / 2.0 / RoadStore.SCALE)), my = k;
        int cellX = (int) Math.max(1, Math.round(200.0 / mx)), cellY = (int) Math.max(1, Math.round(200.0 / my));
        int cols = (maxX - minX) / cellX + 1, rows = (maxY - minY) / cellY + 1;
        List<Set<Integer>> cells = new ArrayList<>();
        for (int c = 0; c < cols * rows; c++) cells.add(new TreeSet<Integer>());
        for (int i = 0; i < grids.size(); i++) {
            int[][] g = grids.get(i);
            for (int s = 0; s + 1 < g.length; s++)
                for (int cx = (Math.min(g[s][0], g[s + 1][0]) - minX) / cellX; cx <= (Math.max(g[s][0], g[s + 1][0]) - minX) / cellX; cx++)
                    for (int cy = (Math.min(g[s][1], g[s + 1][1]) - minY) / cellY; cy <= (Math.max(g[s][1], g[s + 1][1]) - minY) / cellY; cy++)
                        cells.get(cy * cols + cx).add(i);
        }
        List<List<Integer>> adjacency = new ArrayList<>();
        for (int n = 0; n < nodes.size(); n++) adjacency.add(new ArrayList<Integer>());
        for (int i = 0; i < ends.length; i++) { adjacency.get(ends[i][0]).add(i << 1); adjacency.get(ends[i][1]).add(i << 1 | 1); }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] offsets = new int[7];
        ByteBuffer nodeBytes = ByteBuffer.allocate(8 * nodes.size());
        for (int[] n : nodes) { nodeBytes.putInt(n[0]); nodeBytes.putInt(n[1]); }
        offsets[0] = section(body, nodeBytes.array());
        int refs = 0;
        for (List<Integer> a : adjacency) refs += a.size();
        ByteBuffer adj = ByteBuffer.allocate(4 * (nodes.size() + 1 + refs));
        int at = 0;
        for (List<Integer> a : adjacency) { adj.putInt(at); at += a.size(); }
        adj.putInt(at);
        for (List<Integer> a : adjacency) for (int r : a) adj.putInt(r);
        offsets[1] = section(body, adj.array());
        ByteArrayOutputStream geometry = new ByteArrayOutputStream();
        ByteBuffer frag = ByteBuffer.allocate(RoadStore.FRAGMENT_BYTES * fragments.size());
        for (int i = 0; i < fragments.size(); i++) {
            F f = fragments.get(i);
            int[][] g = grids.get(i);
            frag.putInt(ends[i][0]).putInt(ends[i][1]).putInt(geometry.size()).putShort((short) g.length);
            int flags = (f.forward ? 1 : 0) | (f.backward ? 2 : 0) | ((f.forward && f.backward) ? 0 : 4) | f.ownership << 3;
            frag.put((byte) f.roadClass).put((byte) f.formway).put((byte) 0).put((byte) f.lanes).put((byte) flags).put((byte) 30)
                    .put((byte) f.legalForward).put((byte) f.legalBackward);
            int ni = f.name == null ? RoadStore.NO_NAME : names.indexOf(f.name);
            frag.put((byte) (ni >> 16)).put((byte) (ni >> 8)).put((byte) ni);
            for (int s = 1; s < g.length; s++) { varint(geometry, g[s][0] - g[s - 1][0]); varint(geometry, g[s][1] - g[s - 1][1]); }
        }
        offsets[2] = section(body, frag.array());
        offsets[3] = section(body, geometry.toByteArray());
        int items = 0;
        for (Set<Integer> c : cells) items += c.size();
        ByteBuffer starts = ByteBuffer.allocate(4 * (cells.size() + 1)), list = ByteBuffer.allocate(4 * items);
        at = 0;
        for (Set<Integer> c : cells) { starts.putInt(at); at += c.size(); for (int i : c) list.putInt(i); }
        starts.putInt(at);
        offsets[4] = section(body, starts.array());
        offsets[5] = section(body, list.array());
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        ByteBuffer nameStarts = ByteBuffer.allocate(4 * (names.size() + 1));
        nameStarts.putInt(0);
        for (String n : names) { byte[] b = n.getBytes(StandardCharsets.UTF_8); text.write(b, 0, b.length); nameStarts.putInt(text.size()); }
        byte[] nameSection = new byte[nameStarts.capacity() + text.size()];
        System.arraycopy(nameStarts.array(), 0, nameSection, 0, nameStarts.capacity());
        System.arraycopy(text.toByteArray(), 0, nameSection, nameStarts.capacity(), text.size());
        offsets[6] = section(body, nameSection);
        ByteBuffer out = ByteBuffer.allocate(92 + body.size());
        out.put(new byte[]{'D', 'Z', 'R', 'N'}).putShort((short) 1).putShort((short) 0).putInt(minX).putInt(minY).putDouble(mx).putDouble(my)
                .putInt(cellX).putInt(cellY).putInt(cols).putInt(rows).putInt(nodes.size()).putInt(fragments.size()).putInt(names.size());
        for (int o : offsets) out.putInt(o);
        out.putInt(92 + body.size());
        out.put(body.toByteArray());
        out.flip();
        return out;
    }

    static int node(Map<String, Integer> index, List<int[]> nodes, String key, int[] xy) {
        Integer i = index.get(key);
        if (i == null) { index.put(key, nodes.size()); nodes.add(xy); return nodes.size() - 1; }
        if (!Arrays.equals(nodes.get(i), xy)) throw new IllegalStateException("node " + key + " has two positions");
        return i;
    }

    static int section(ByteArrayOutputStream body, byte[] data) {
        while (body.size() % 4 != 0) body.write(0);
        int offset = 92 + body.size();
        body.write(data, 0, data.length);
        return offset;
    }

    static void varint(ByteArrayOutputStream out, long v) {
        long z = (v << 1) ^ (v >> 63);
        while ((z & ~0x7FL) != 0) { out.write((int) ((z & 0x7F) | 0x80)); z >>>= 7; }
        out.write((int) z);
    }

    static YandexRoute route(double... xy) {
        double[] lon = new double[xy.length / 2], lat = new double[xy.length / 2];
        for (int i = 0; i < lon.length; i++) { double[] d = deg(xy[2 * i], xy[2 * i + 1]); lon[i] = d[0]; lat[i] = d[1]; }
        List<YandexRoute.Section> sections = new ArrayList<>();
        sections.add(new YandexRoute.Section(0, lon.length - 1, "STRAIGHT", "Yandex name", Double.NaN));
        YandexRoute r = new YandexRoute("synthetic", lon, lat, sections, null, new double[0], new ArrayList<double[]>(),
                new ArrayList<double[]>(), null, null);
        r.highways = new ArrayList<>();
        return r;
    }

    static List<StockPath.Link> links(RouteConverter.Converted c) {
        List<StockPath.Link> out = new ArrayList<>();
        for (StockPath.Segment s : c.path.segments) out.addAll(s.links);
        return out;
    }

    static RouteConverter.Converted convert(List<F> network, double... xy) {
        return RouteConverter.convert(route(xy), Coordinates.Frame.WGS84, new RoadStore(store(network)));
    }

    static final List<F> MAIN = Arrays.asList(
            two("w", "j300", "Main", 7, 1, 2, 0, 0, 150, 0, 300, 0),
            two("j300", "j600", "Main", 7, 1, 2, 300, 0, 600, 0),
            two("j600", "c800", "Main", 7, 1, 2, 600, 0, 800, 0),
            two("c800", "e", "Main", 7, 1, 4, 800, 0, 1000, 0).legal(60, 40),
            two("j300", "s1", "Side", 9, 15, 1, 300, 0, 300, 150),
            two("s2", "j600", "Side", 9, 15, 1, 600, -150, 600, 0));

    static final List<F> DIVIDED = Arrays.asList(
            one("e0", "e1", "East", 0, 1, 3, 0, 8, 500, 8), one("e1", "e2", "East", 0, 1, 3, 500, 8, 1000, 8),
            one("w2", "w1", "West", 6, 1, 2, 1000, -8, 500, -8), one("w1", "w0", "West", 6, 1, 2, 500, -8, 0, -8));

    static final List<F> PARALLEL = Arrays.asList(
            two("w", "j200", "Main", 7, 1, 2, 0, 0, 200, 0), two("j200", "m300", "Main", 7, 1, 2, 200, 0, 300, 0),
            two("m300", "m600", "Main", 7, 1, 2, 300, 0, 600, 0), two("m600", "j800", "Main", 7, 1, 2, 600, 0, 800, 0),
            two("j800", "e", "Main", 7, 1, 4, 800, 0, 1000, 0),
            own("j200", "p200", "Service", 200, 0, 200, 12), own("p200", "p800", "Service", 200, 12, 800, 12),
            own("p800", "j800", "Service", 800, 12, 800, 0));

    static final List<F> LOOP = Arrays.asList(
            two("w", "j400", "Main", 7, 1, 2, 0, 0, 400, 0), two("j400", "j440", "Main", 7, 1, 2, 400, 0, 440, 0),
            two("j440", "e", "Main", 7, 1, 2, 440, 0, 1000, 0),
            own("j400", "l400", "Loop", 400, 0, 400, 12), own("l400", "l440", "Loop", 400, 12, 440, 12), own("l440", "j440", "Loop", 440, 12, 440, 0));

    static final List<F> STUB = Arrays.asList(two("q0", "q1", "Stub", 9, 15, 1, 0, 2, 20, 2), two("f0", "f1", "Far", 9, 15, 1, 0, 500, 1000, 500));

    static void run() {
        RouteConverter.Converted c = convert(MAIN, 0, 3, 500, 3, 1000, 3);
        List<StockPath.Link> l = links(c);
        List<Long> ends = new ArrayList<>();
        for (double e : c.linkEndM) ends.add(Math.round(e));
        equal(Arrays.asList(300L, 600L, 800L, 1000L), ends, "roads: links end at the junctions and the lane change");
        Set<String> seen = new HashSet<>();
        for (StockPath.Link x : l) seen.add(x.roadClass + "/" + x.formway + "/" + x.direction + "/" + x.ownership + "/" + x.roadName);
        equal(new HashSet<>(Arrays.asList("7/1/0/0/Main")), seen, "roads: matched links carry the road's attributes");
        equal(4, l.get(3).laneNum, "roads: the last link carries four lanes");
        equal(30.0, c.linkSpeedKmh[0], "roads: the link speed is the road's ERAT speed");
        equal("[0, 0, 0, 60]", Arrays.toString(c.linkLegalKmh), "roads: eastbound the forward speed-limit sign");
        equal("[40, 0, 0, 0]", Arrays.toString(convert(MAIN, 1000, -3, 0, -3).linkLegalKmh), "roads: westbound the backward one");

        c = convert(MAIN, 0, 3, 1000, 3, 1400, 3);
        l = links(c);
        StockPath.Link last = l.get(l.size() - 1);
        check(last.roadClass == -1 && last.formway == -1 && last.ownership == -1 && last.roadName.equals("Yandex name"),
                "roads: off the network a link keeps the Yandex-only values");
        check(Double.isNaN(c.linkSpeedKmh[l.size() - 1]), "roads: off the network no link speed");
        check(c.roadMatch.matchedM > 990 && c.roadMatch.matchedM < 1000 + RoadMatcher.STEP_M + 1, "roads: matched metres end at the network's end");

        RouteConverter.Converted far = convert(MAIN, 0, 300, 1000, 300);
        RouteConverter.Converted alone = RouteConverter.convert(route(0, 300, 1000, 300), Coordinates.Frame.WGS84);
        equal(alone.contentKey, far.contentKey, "roads: a route away from the network is converted as without it");

        seen.clear();
        for (StockPath.Link x : links(convert(DIVIDED, 0, -2, 1000, -2))) seen.add(x.roadName + "/" + x.roadClass + "/" + x.direction);
        equal(new HashSet<>(Arrays.asList("East/0/1")), seen, "roads: the carriageway in the direction of travel wins over the nearer one");
        seen.clear();
        for (StockPath.Link x : links(convert(DIVIDED, 1000, 2, 0, 2))) seen.add(x.roadName + "/" + x.roadClass);
        equal(new HashSet<>(Arrays.asList("West/6")), seen, "roads: westbound takes the westbound carriageway");

        seen.clear();
        for (StockPath.Link x : links(convert(PARALLEL, 0, 0, 300, 0, 350, 8, 650, 8, 700, 0, 1000, 0))) seen.add(x.roadName + "/" + x.ownership);
        equal(new HashSet<>(Arrays.asList("Main/0")), seen, "roads: a nearer parallel service road does not capture the route");
        c = convert(PARALLEL, 0, 0, 200, 0, 200, 12, 800, 12, 800, 0, 1000, 0);
        seen.clear();
        l = links(c);
        for (int i = 0; i < l.size(); i++) {
            double middle = (c.linkBeginM[i] + c.linkEndM[i]) / 2;
            if (middle > 250 && middle < 750) seen.add(l.get(i).roadName + "/" + l.get(i).ownership);
        }
        equal(new HashSet<>(Arrays.asList("Service/1")), seen, "roads: a route on the service road gets the service road");

        seen.clear();
        for (StockPath.Link x : links(convert(LOOP, 0, 0, 370, 0, 380, 8, 460, 8, 470, 0, 1000, 0))) seen.add(x.roadName);
        equal(new HashSet<>(Arrays.asList("Main")), seen, "roads: a short loop nearer the line costs more than it saves");
        c = convert(STUB, 0, 0, 400, 0);
        equal(0.0, c.roadMatch.matchedM, "roads: a short isolated road at the start is not a match");
        equal(RouteConverter.convert(route(0, 0, 400, 0), Coordinates.Frame.WGS84).contentKey, c.contentKey,
                "roads: ... and the route converts as without roads");

        boolean refused = false;
        ByteBuffer broken = store(MAIN);
        broken.put(0, (byte) 'X');
        try { new RoadStore(broken); } catch (IllegalArgumentException e) { refused = true; }
        check(refused, "roads: a damaged store is refused");
    }

    private RoadTests() {}
}
