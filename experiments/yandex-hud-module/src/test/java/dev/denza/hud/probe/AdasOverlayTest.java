package dev.denza.hud.probe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host checks of the ADAS overlay: the wire (real car packets, the stock map's formats), the rules
 * that turn observations into the board's states, the one-third layout and the anchor, the
 * figures against spec.json. Arguments: the probe's native fixture, Luminofor's spec.json.
 */
public final class AdasOverlayTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static byte[] hex(String text) {
        byte[] b = new byte[text.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
    private static void reject(long topic, String hex) {
        checks++;
        try { AdasWire.decode(topic, hex(hex)); throw new AssertionError("Accepted " + hex); }
        catch (IllegalArgumentException expected) { }
    }

    static final long T = 100_000;   // "now"
    static final long PATH = 7;

    /** A session up long ago and a pilot status [ageMs] old. */
    static AdasObservations seen(int dnp, int icc, long pilotAge) {
        AdasObservations o = new AdasObservations();
        o.session(true, 0);
        o.apply(AdasWire.decode(AdasWire.PILOT, AdasWire.pilot(dnp, icc, false)), T - pilotAge);
        o.apply(AdasWire.decode(AdasWire.MODE, AdasWire.mode(2)), T - pilotAge);
        return o;
    }
    static AdasFeed.Snapshot feed(long sendAge) { return new AdasFeed.Snapshot(AdasFeed.Mode.DRY_RUN, T - sendAge, PATH, true); }
    static void select(AdasObservations o, long path, int code) { o.apply(AdasWire.decode(AdasWire.SELECTED, AdasWire.selected(path, code)), T - 5000); }
    static void match(AdasObservations o, long path) { o.apply(AdasWire.decode(AdasWire.PATH_MATCH, AdasWire.match(200, path)), T - 4000); }
    static void stockEmpty(AdasObservations o, long age) {
        o.apply(AdasWire.decode(AdasWire.NAV_STATUS, AdasWire.navStatus(100)), T - age);
    }

    public static void main(String[] args) throws Exception {
        wire(args[0]);
        states();
        layout();
        figures(args[1]);
        System.out.println(checks + " ADAS overlay assertions passed");
    }

    static void wire(String fixture) throws Exception {
        int frames = 0, pilots = 0;
        for (String line : Files.readAllLines(Paths.get(fixture))) {
            if (line.startsWith("#") || line.trim().isEmpty()) continue;
            String[] parts = line.split(" ");
            AdasWire.Event e = AdasWire.decode(Long.decode(parts[0]), hex(parts[1]));
            frames++;
            if (e.kind == AdasWire.Kind.PILOT) { pilots++; check(e.dnp == 0 && e.icc == 0, "Car 23.10: DNP/ICC 0"); }
            if (e.kind == AdasWire.Kind.MODE) check(e.noaMode == 2, "Car 23.10: NOA family 2 (city)");
        }
        check(frames == 384 && pilots == 128, "All 384 real callbacks decode");
        AdasWire.Event p = AdasWire.decode(AdasWire.PILOT, hex("0a06200228013000"));
        check(p.icc == 2 && p.dnp == 1 && !p.takeover, "Pilot fields 4/5, ICC 2 DNP 1");
        check(AdasWire.decode(AdasWire.PILOT, hex("0a023001")).takeover, "takeoverStatus field 6");
        reject(AdasWire.PILOT, "091802200228023000");  // wrong outer fixed64 impersonated availability once
        reject(0x0004000700078006L, "0a00");           // generalNavigation is not subscribed
        // The stock map's generated protobuf, byte for byte: NavigationStatus 100, uint32 field 4.
        AdasWire.Event idle = AdasWire.decode(AdasWire.NAV_STATUS, hex("0a022064"));
        check(idle.navigationStatus == 100 && idle.stockEmpty(), "NavigationStatus 100 is the stock map's no-route");
        check(!AdasWire.decode(AdasWire.NAV_STATUS, AdasWire.navStatus(101)).stockEmpty(), "101 (navigating) is not empty");
        // TrafficInfo roadClass -1 travels as uint32 0xFFFFFFFF in field 19 (tag 0x98 0x01).
        AdasWire.Event tr = AdasWire.decode(AdasWire.TRAFFIC, hex("0a079801ffffffff0f"));
        check(tr.roadClass == -1 && tr.stockEmpty(), "roadClass -1 from uint32");
        check(Arrays.equals(AdasWire.traffic(-1), hex("0a079801ffffffff0f")), "Encoder writes what the stock map writes");
        check(!AdasWire.decode(AdasWire.TRAFFIC, AdasWire.traffic(3)).stockEmpty(), "A road class is not empty");
        // sdVehicleLocation.curSDRouteID is a DOUBLE, field 7: -1.0 when not navigating.
        AdasWire.Event loc = AdasWire.decode(AdasWire.LOCATION, hex("0a09390000000000 00f0bf".replace(" ", "")));
        check(loc.routeId == -1.0 && loc.stockEmpty(), "pathId -1 as double");
        check(!AdasWire.decode(AdasWire.LOCATION, AdasWire.location(42)).stockEmpty(), "A pathId is not empty");
        reject(AdasWire.LOCATION, "0a023801");          // a varint is not curSDRouteID
        AdasWire.Event sel = AdasWire.decode(AdasWire.SELECTED, hex("0a0d18ffffffffffffffff7f20c801"));
        check(sel.pathId == Long.MAX_VALUE && sel.statusCode == 200, "Exact int64 selection");
        AdasWire.Event m = AdasWire.decode(AdasWire.PATH_MATCH, hex("0a1d18c801221408ffffffffffffffff7f10b9601803200728c8012a026f6b"));
        check(m.matchedPaths.length == 1 && m.matchedPaths[0] == Long.MAX_VALUE, "PathMatch RouteInfo pathID");
        check(!AdasWire.allowed(0x0004000D000D0001L), "No method topics");
        check(AdasPassiveClient_topics().length == 8, "Five answers and three stock topics, nothing else");
    }

    /** AdasPassiveClient is Android; its topic list is AdasWire's. */
    static long[] AdasPassiveClient_topics() {
        long[] all = new long[AdasWire.ANSWERS.length + AdasWire.STOCK.length];
        System.arraycopy(AdasWire.ANSWERS, 0, all, 0, AdasWire.ANSWERS.length);
        System.arraycopy(AdasWire.STOCK, 0, all, AdasWire.ANSWERS.length, AdasWire.STOCK.length);
        for (long t : all) check(AdasWire.allowed(t), "Every subscribed topic decodes");
        return all;
    }

    static void states() {
        // idle: no route of ours - the dim row, ICC available, NOA off with its family
        AdasOverlayState s = AdasOverlayState.of(seen(0, 2, 100), AdasFeed.DISABLED, T, false);
        check(!s.stale && s.noa == AdasOverlayState.Fn.OFF && s.icc == AdasOverlayState.Fn.AVAILABLE, "idle " + s);
        check(s.mode == AdasOverlayState.Mode.CITY && s.route == null && s.alarm == null && s.details == null, "idle quiet " + s);
        check(s.plates().equals(Arrays.asList(AdasOverlayState.Plate.ROW)), "idle: one plate");
        // waiting: our route sent, no selection yet
        s = AdasOverlayState.of(seen(0, 2, 100), feed(400), T, false);
        check(s.route == AdasOverlayState.Route.WAITING && s.alarm == null && s.details == null, "waiting " + s);
        // available + matched
        AdasObservations o = seen(2, 2, 100); select(o, PATH, 200); match(o, PATH);
        s = AdasOverlayState.of(o, feed(400), T, false);
        check(s.noa == AdasOverlayState.Fn.AVAILABLE && s.route == AdasOverlayState.Route.MATCHED, "available " + s);
        // active
        o = seen(1, 1, 100); select(o, PATH, 200); match(o, PATH);
        s = AdasOverlayState.of(o, feed(400), T, false);
        check(s.noa == AdasOverlayState.Fn.ACTIVE && !s.iccLeads() && s.takeover == null, "active " + s);
        // ICC only leads
        o = seen(0, 1, 100); select(o, PATH, 200); match(o, PATH);
        s = AdasOverlayState.of(o, feed(400), T, false);
        check(s.iccLeads(), "ICC only leads");
        // selection without a path match: one tick; a refusal: a cross; another path: still waiting
        o = seen(0, 1, 100); select(o, PATH, 200);
        check(AdasOverlayState.of(o, feed(400), T, false).route == AdasOverlayState.Route.SELECTED, "one tick");
        match(o, 99);
        check(AdasOverlayState.of(o, feed(400), T, false).route == AdasOverlayState.Route.SELECTED, "a match of another path is no second tick");
        select(o, PATH, 500);
        check(AdasOverlayState.of(o, feed(400), T, false).route == AdasOverlayState.Route.REJECTED, "refusal is a cross");
        select(o, -1, 200);
        check(AdasOverlayState.of(o, feed(400), T, false).route == AdasOverlayState.Route.WAITING, "the stock map's own selection is not ours");
        // the stock map says "no route" over ours: board stockEmpty, details only now
        o = seen(0, 1, 100); select(o, PATH, 200); stockEmpty(o, 900);
        s = AdasOverlayState.of(o, feed(400), T, false);
        check(s.alarm == AdasOverlayState.Alarm.STOCK_EMPTY, "stock empty alarm " + s);
        check(String.valueOf(s.details).equals("[Яндекс 0,4, штатная карта 0,9!]"), "stock details " + s.details);
        check(s.plates().equals(Arrays.asList(AdasOverlayState.Plate.ROW, AdasOverlayState.Plate.ALARM)), "alarm stacks above the row");
        check(AdasOverlayState.of(o, AdasFeed.DISABLED, T, false).alarm == null, "no route of ours, no stock alarm");
        check(AdasOverlayState.of(o, feed(-2600), T + 3000, false).alarm == null, "a no-route 3.9 s old has passed");
        o.apply(AdasWire.decode(AdasWire.LOCATION, AdasWire.location(PATH)), T - 100);
        check(AdasOverlayState.of(o, feed(400), T, false).alarm == AdasOverlayState.Alarm.STOCK_EMPTY, "our own position does not clear the stock map's 'no route'");
        AdasObservations ours = seen(0, 1, 100); select(ours, PATH, 200);
        ours.apply(AdasWire.decode(AdasWire.NAV_STATUS, AdasWire.navStatus(101)), T - 200);
        ours.apply(AdasWire.decode(AdasWire.TRAFFIC, AdasWire.traffic(2)), T - 200);
        check(AdasOverlayState.of(ours, feed(400), T, false).alarm == null, "a navigating feed is not empty");
        // Yandex silent: our bridge stopped sending
        o = seen(2, 2, 600); select(o, PATH, 200);
        s = AdasOverlayState.of(o, feed(7000), T, false);
        check(s.alarm == AdasOverlayState.Alarm.YANDEX_SILENT, "yandex silent " + s);
        check(String.valueOf(s.details).equals("[Яндекс 7!, ответ ADAS 0,6]"), "silent details " + s.details);
        stockEmpty(o, 200);
        check(AdasOverlayState.of(o, feed(7000), T, false).alarm == AdasOverlayState.Alarm.YANDEX_SILENT, "silence outranks the stock map");
        check(AdasOverlayState.of(o, new AdasFeed.Snapshot(AdasFeed.Mode.DRY_RUN, -1, PATH, true), T, false).alarm
                == AdasOverlayState.Alarm.YANDEX_SILENT, "a route never sent is silent");
        // stale ADAS: the whole row greys, the age is named, never active, never a takeover
        o = seen(1, 1, 12_300); select(o, PATH, 200); match(o, PATH);
        o.apply(AdasWire.decode(AdasWire.PILOT, AdasWire.pilot(1, 1, true)), T - 12_300);
        s = AdasOverlayState.of(o, feed(400), T, false);
        check(s.stale && s.staleSeconds == 12 && s.takeover == null, "stale " + s);
        check(String.valueOf(s.details).equals("[Яндекс 0,4, ответ ADAS 12!]"), "stale details " + s.details);
        check(AdasOverlayState.of(seen(1, 1, 1900), AdasFeed.DISABLED, T, false).stale == false, "1.9 s is fresh");
        check(AdasOverlayState.of(seen(1, 1, 2100), AdasFeed.DISABLED, T, false).stale, "2.1 s is stale");
        AdasObservations never = new AdasObservations();
        s = AdasOverlayState.of(never, AdasFeed.DISABLED, T, false);
        check(s.stale && s.staleSeconds == -1 && s.details == null, "never heard: stale without an age");
        o = seen(1, 1, 100); o.session(false, T - 50);
        check(AdasOverlayState.of(o, AdasFeed.DISABLED, T, false).stale, "a dropped session is stale at once");
        o.session(true, T - 20);
        check(AdasOverlayState.of(o, AdasFeed.DISABLED, T, false).stale, "a new session must be heard again");
        // takeover: the pilot's flag or TOR 1302, fresh only
        o = seen(1, 1, 100); o.apply(AdasWire.decode(AdasWire.PILOT, AdasWire.pilot(1, 1, true)), T - 100);
        check(HANDS(AdasOverlayState.of(o, feed(400), T, false)), "takeoverStatus");
        o = seen(1, 1, 100); o.apply(AdasWire.decode(AdasWire.ALARM, AdasWire.alarm(0, 1302)), T - 300);
        check(HANDS(AdasOverlayState.of(o, feed(400), T, false)), "TOR 1302 notice");
        o = seen(1, 1, 100); o.apply(AdasWire.decode(AdasWire.ALARM, AdasWire.alarm(1302, 0)), T - 3000);
        check(!HANDS(AdasOverlayState.of(o, feed(400), T, false)), "an old TOR is gone");
        o = seen(1, 1, 100); o.apply(AdasWire.decode(AdasWire.ALARM, AdasWire.alarm(521, 0)), T - 100);
        check(!HANDS(AdasOverlayState.of(o, feed(400), T, false)), "521 (degraded to ICC) is not hands-on");
        // ages as the board prints them
        check(AdasOverlayState.age(400).equals("0,4") && AdasOverlayState.age(950).equals("0,9")
                && AdasOverlayState.age(7000).equals("7") && AdasOverlayState.age(12_999).equals("12"), "ages");
    }

    static boolean HANDS(AdasOverlayState s) { return AdasOverlayState.HANDS.equals(s.takeover); }

    static void layout() {
        AdasOverlayState s = new AdasOverlayState();
        s.alarm = AdasOverlayState.Alarm.STOCK_EMPTY; s.takeover = AdasOverlayState.HANDS;
        check(s.plates().equals(Arrays.asList(AdasOverlayState.Plate.ROW, AdasOverlayState.Plate.ALARM, AdasOverlayState.Plate.TAKEOVER)), "full width stacks");
        s.narrow = true;
        check(s.plates().equals(Arrays.asList(AdasOverlayState.Plate.TAKEOVER)), "one third: red replaces everything");
        s.takeover = null;
        check(s.plates().equals(Arrays.asList(AdasOverlayState.Plate.ALARM)), "one third: the alarm replaces the row");
        s.alarm = null;
        check(s.plates().equals(Arrays.asList(AdasOverlayState.Plate.ROW)), "one third: the row alone");
        check(AdasOverlayState.isNarrow(832, 2) && !AdasOverlayState.isNarrow(1656, 2) && !AdasOverlayState.isNarrow(2560, 2), "one third is 832 px");
        int[] a = AdasOverlayState.anchor(2560, 1600, 0, 64, 2, true);
        check(a[0] == 2536 && a[1] == 1516, "guidance: 12 dp from the right, 10 dp above the bars " + Arrays.toString(a));
        a = AdasOverlayState.anchor(2560, 1600, 0, 64, 2, false);
        check(a[0] == 2376, "browsing: left of Navigator's toolbar (board car_idle 2376)");
        a = AdasOverlayState.anchor(1656, 1356, 0, 0, 2, true);
        check(a[0] == 1632 && a[1] == 1336, "two thirds (board car_two: 24 px, 20 px)");
        a = AdasOverlayState.anchor(832, 1358, 0, 0, 2, true);
        check(a[0] == 808 && a[1] == 1194, "one third (board car_one 808, 1194): above the route panel");
    }

    static void figures(String spec) throws Exception {
        String json = new String(Files.readAllBytes(Paths.get(spec)), StandardCharsets.UTF_8);
        for (int i = 0; i < AdasDigits.CHARS.length(); i++) {
            String ch = String.valueOf(AdasDigits.CHARS.charAt(i));
            Matcher m = Pattern.compile("\"" + Pattern.quote(ch) + "\"\\s*:\\s*\\[\\s*([0-9.]+)\\s*,\\s*\"([^\"]*)\"").matcher(json);
            check(m.find(), "spec.json has " + ch);
            check(Float.parseFloat(m.group(1)) == AdasDigits.ADVANCE[i] && m.group(2).equals(AdasDigits.GLYPHS[i]), "figure " + ch + " is spec.json's");
        }
        check(json.contains("\"capRatio\": 0.71") && json.contains("\"track\": 18") && json.contains("\"strokePerSize\": 0.075")
                && json.contains("\"strokeMin\": 2.2"), "figure metrics are spec.json's");
        check(Math.abs(AdasDigits.width("0,4", 34) - (70 + 18 + 14 + 18 + 70) * 34 * 0.71f / 100) < 1e-3, "figure width");
        // Arcs: the 0's corners end where the path data says, inside its 70 x 100 box.
        final float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        final float[] last = new float[2];
        final int[] cubics = {0};
        AdasSvgPath.Sink sink = new AdasSvgPath.Sink() {
            void at(float x, float y) { box[0] = Math.min(box[0], x); box[1] = Math.min(box[1], y); box[2] = Math.max(box[2], x); box[3] = Math.max(box[3], y); last[0] = x; last[1] = y; }
            public void moveTo(float x, float y) { at(x, y); }
            public void lineTo(float x, float y) { at(x, y); }
            public void quadTo(float x1, float y1, float x, float y) { at(x, y); }
            public void cubicTo(float x1, float y1, float x2, float y2, float x, float y) { cubics[0]++; at(x1, y1); at(x2, y2); at(x, y); }
            public void close() { }
        };
        AdasSvgPath.parse(AdasDigits.GLYPHS[0], sink);
        check(cubics[0] == 4 && box[0] > -0.01f && box[1] > -0.01f && box[2] < 70.01f && box[3] < 100.01f, "0: four quarter arcs in its box");
        check(Math.abs(last[0] - 22) < 1e-4 && Math.abs(last[1]) < 1e-4, "0 ends at its start");
        cubics[0] = 0;
        AdasSvgPath.parse("M4 14A12 12 0 1 1 28 14A12 12 0 1 1 4 14Z", sink);
        check(cubics[0] == 4, "a circle of two half arcs is four quarters");
        cubics[0] = 0;
        AdasSvgPath.arc(sink, 0, 0, 10, 10, 0, false, true, 10, 10);
        check(cubics[0] == 1 && Math.abs(last[0] - 10) < 1e-4 && Math.abs(last[1] - 10) < 1e-4, "quarter arc");
    }
}
