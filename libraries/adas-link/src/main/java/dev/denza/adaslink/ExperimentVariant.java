package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime experiment switches of one live session: the {@code variant} object of the host's arm
 * ({@code tools/adas_bridge/live_session.py arm --variant FILE.json}), so a parked experiment's
 * result can be applied to the same day's drive without rebuilding Navigator. The keys are the
 * variables of the parked chain ({@code tools/adas_bridge/scenario.py}, research/adas-bridge
 * "Parked experiment kit"); an absent key keeps today's {@link FieldDefaults} behaviour.
 *
 * <p>Strict: an unknown key, a wrong type or a value outside the stock's own set rejects the
 * whole arm. Road-semantics overrides fill only what Yandex leaves without a value (a field a
 * graph road, the offline maps' class and one-way or a roundabout gave keeps its value); every value a
 * switch puts on the wire is attributed to it in the trace ({@code variantApplied} notes) and the
 * effective variant heads every trace. {@code tools/adas_bridge/live_session.py} validates with
 * the same rules before it writes the arm.
 */
public final class ExperimentVariant {
    /** The arm's key. */
    public static final String KEY = "variant";

    public enum Frame {
        /** Today: WGS-84 on the stock grid. */
        WGS84(Coordinates.Frame.WGS84),
        /** The stock's own SDK frame (Moscow envelope): {@link Coordinates.Frame#NATIVE_GCJ_UNRESTRICTED_CANDIDATE}. */
        STOCK_GCJ(Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE);
        public final Coordinates.Frame coordinates;
        Frame(Coordinates.Frame coordinates) { this.coordinates = coordinates; }
    }
    public enum HistoryMode { YANDEX, FIXED, ALTERNATE }
    public enum StartProtocol {
        /** Today: 101 directly, no ODD request. */
        BRIDGE,
        /** The stock's start: the ODD region request, then NavigationStatus 100 and 106 before the route. */
        STOCK
    }

    // The stock's values on our roads (route-01 of first-full, 55 links; the parked chain's H0).
    static final List<Long> ROAD_CLASS = list(9), FORMWAY = list(15), OWNERSHIP = list(0), DIRECTION = list(0, 1),
            LANE_NUM = list(1, 2), ADMIN_CODE = list(990001), REGION = list(990001), POSITION_OWNERSHIP = list(0),
            TRAFFIC_ROAD_CLASS = list(9);
    /** historySpeed in km/h: the factory bench sent 10-91, the car's stock 25. */
    public static final int HISTORY_MIN = 1, HISTORY_MAX = 120, ALTERNATE_MAX = 8;
    public static final String TRAFFIC_CLASS_LINK = "link";
    /**
     * The stock's start before the route, {status, hold ms}, the same as {@code scenario.py}'s
     * STOCK_PRE_ROUTE_PHASE. The order (ODD request, 100, 106, route, 101) is the stock's; the holds
     * are chosen, not measured: in first-full the stock held 106 for about 11 s, the time its route
     * result stayed on screen before the driver started guidance.
     */
    public static final int[][] STOCK_PRE_ROUTE_PHASE = {{100, 2000}, {106, 3000}};
    /** The stock's 1 s status loop through that phase: one status per tick (100, 100, 106, 106, 106). */
    public static int[] stockStartStatuses() {
        int n = 0;
        for (int[] step : STOCK_PRE_ROUTE_PHASE) n += step[1] / 1000;
        int[] out = new int[n];
        int i = 0;
        for (int[] step : STOCK_PRE_ROUTE_PHASE) for (int k = 0; k < step[1] / 1000; k++) out[i++] = step[0];
        return out;
    }
    /** The route follows the phase: its total hold, ms. */
    public static long stockStartMs() {
        long ms = 0;
        for (int[] step : STOCK_PRE_ROUTE_PHASE) ms += step[1];
        return ms;
    }
    private static final String[] KEYS = {"id", "frame", "link.roadclass", "link.formway", "link.ownership", "link.direction",
            "link.laneNum", "link.adminCode", "region", "position.ownership", "traffic.roadClass", "historySpeed", "speedLimit",
            "startProtocol", "naviSt", "instrumentNaviStatus"};

    public static final ExperimentVariant NONE = new ExperimentVariant(new LinkedHashMap<String, Object>());

    /** A short label for logs ("H1"); null when the file gives none. */
    public final String id;
    public final Frame frame;
    public final Integer linkRoadClass, linkFormway, linkOwnership, linkDirection, linkLaneNum, region, positionOwnership,
            trafficRoadClass;
    public final Long linkAdminCode;
    /** traffic.roadClass "link": the class of the published link under the car. */
    public final boolean trafficRoadClassFromLink;
    public final HistoryMode historyMode;
    /** FIXED: the one value; ALTERNATE: per Yandex route ID in order, null = Yandex's own. */
    public final List<Integer> historyValues;
    public final boolean speedLimitZero, naviSt, instrumentNaviStatus;
    public final StartProtocol startProtocol;
    private final Map<String, Object> given;

    @SuppressWarnings("unchecked")
    private ExperimentVariant(Map<String, Object> m) {
        for (String key : m.keySet()) if (!Arrays.asList(KEYS).contains(key)) throw bad("unknown key " + key);
        Object idValue = m.get("id");
        if (m.containsKey("id") && !(idValue instanceof String && ((String) idValue).matches("[A-Za-z0-9_.+-]{1,32}")))
            throw bad("id must be 1-32 of [A-Za-z0-9_.+-]");
        id = (String) idValue;
        String f = m.containsKey("frame") ? string(m, "frame") : null;
        if (f != null && !f.equals("WGS84") && !f.equals("STOCK_GCJ")) throw bad("frame WGS84 or STOCK_GCJ");
        frame = f == null ? null : Frame.valueOf(f);
        linkRoadClass = member(m, "link.roadclass", ROAD_CLASS);
        linkFormway = member(m, "link.formway", FORMWAY);
        linkOwnership = member(m, "link.ownership", OWNERSHIP);
        linkDirection = member(m, "link.direction", DIRECTION);
        linkLaneNum = member(m, "link.laneNum", LANE_NUM);
        Integer admin = member(m, "link.adminCode", ADMIN_CODE);
        linkAdminCode = admin == null ? null : Long.valueOf(admin);
        region = member(m, "region", REGION);
        positionOwnership = member(m, "position.ownership", POSITION_OWNERSHIP);
        Object traffic = m.get("traffic.roadClass");
        if (TRAFFIC_CLASS_LINK.equals(traffic)) { trafficRoadClassFromLink = true; trafficRoadClass = null; }
        else { trafficRoadClassFromLink = false; trafficRoadClass = member(m, "traffic.roadClass", TRAFFIC_ROAD_CLASS); }
        if (m.containsKey("historySpeed")) {
            Object h = m.get("historySpeed");
            if (!(h instanceof Map)) throw bad("historySpeed must be an object");
            Map<String, Object> hm = (Map<String, Object>) h;
            String mode = string(hm, "mode");
            if (mode.equals("yandex")) {
                exactKeys(hm, "historySpeed", "mode");
                historyMode = HistoryMode.YANDEX; historyValues = Collections.emptyList();
            } else if (mode.equals("fixed")) {
                exactKeys(hm, "historySpeed", "mode", "value");
                historyMode = HistoryMode.FIXED; historyValues = Collections.singletonList(speed(hm.get("value")));
            } else if (mode.equals("alternate")) {
                exactKeys(hm, "historySpeed", "mode", "values");
                Object values = hm.get("values");
                if (!(values instanceof List) || ((List<?>) values).size() < 2 || ((List<?>) values).size() > ALTERNATE_MAX)
                    throw bad("historySpeed.values must list 2-" + ALTERNATE_MAX + " entries");
                List<Integer> out = new ArrayList<>();
                boolean fixed = false;
                for (Object v : (List<?>) values) { Integer s = v == null ? null : speed(v); fixed |= s != null; out.add(s); }
                if (!fixed) throw bad("historySpeed.values needs at least one fixed value");
                historyMode = HistoryMode.ALTERNATE; historyValues = Collections.unmodifiableList(out);
            } else throw bad("historySpeed.mode yandex, fixed or alternate");
        } else { historyMode = HistoryMode.YANDEX; historyValues = Collections.emptyList(); }
        String limit = m.containsKey("speedLimit") ? string(m, "speedLimit") : "yandex";
        if (!limit.equals("yandex") && !limit.equals("zero")) throw bad("speedLimit yandex or zero");
        speedLimitZero = limit.equals("zero");
        String start = m.containsKey("startProtocol") ? string(m, "startProtocol") : "bridge";
        if (!start.equals("bridge") && !start.equals("stock")) throw bad("startProtocol bridge or stock");
        startProtocol = start.equals("stock") ? StartProtocol.STOCK : StartProtocol.BRIDGE;
        if (m.containsKey("naviSt") && !(m.get("naviSt") instanceof Boolean)) throw bad("naviSt must be true or false");
        naviSt = Boolean.TRUE.equals(m.get("naviSt"));
        if (m.containsKey("instrumentNaviStatus") && !(m.get("instrumentNaviStatus") instanceof Boolean))
            throw bad("instrumentNaviStatus must be true or false");
        instrumentNaviStatus = Boolean.TRUE.equals(m.get("instrumentNaviStatus"));
        given = Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }

    /** The arm's {@code variant} object; IllegalArgumentException on anything the schema does not allow. */
    @SuppressWarnings("unchecked")
    public static ExperimentVariant parse(Object value) {
        if (!(value instanceof Map)) throw bad("variant must be an object");
        return new ExperimentVariant(new LinkedHashMap<>((Map<String, Object>) value));
    }

    /** True when no switch changes anything: today's build. */
    public boolean isDefault() {
        return frame == null && linkRoadClass == null && linkFormway == null && linkOwnership == null && linkDirection == null
                && linkLaneNum == null && linkAdminCode == null && region == null && positionOwnership == null
                && trafficRoadClass == null && !trafficRoadClassFromLink && historyMode == HistoryMode.YANDEX && !speedLimitZero
                && startProtocol == StartProtocol.BRIDGE && !naviSt && !instrumentNaviStatus;
    }

    /** The coordinate frame this variant sends in; {@code fallback} when it names none. */
    public Coordinates.Frame frame(Coordinates.Frame fallback) { return frame == null ? fallback : frame.coordinates; }

    /** True when a link attribute is overridden: the converter must apply this variant. */
    public boolean overridesLinks() {
        return linkRoadClass != null || linkFormway != null || linkOwnership != null || linkDirection != null
                || linkLaneNum != null || linkAdminCode != null;
    }

    /**
     * historySpeed for the {@code ordinal}-th Yandex route ID of the session (0 first); null: Yandex's
     * own value (the section's free-flow speed, today's rule).
     */
    public Integer historySpeed(int ordinal) {
        if (historyMode == HistoryMode.FIXED) return historyValues.get(0);
        if (historyMode == HistoryMode.ALTERNATE) return historyValues.get(Math.floorMod(ordinal, historyValues.size()));
        return null;
    }

    /** Every switch with its effective value (absent keys at today's), and which keys the file set. */
    public Map<String, Object> effective() {
        Map<String, Object> e = Json.object();
        e.put("id", id);
        e.put("frame", frame == null ? FieldDefaults.FRAME == Coordinates.Frame.WGS84 ? "WGS84" : "STOCK_GCJ" : frame.name());
        e.put("link.roadclass", linkRoadClass == null ? "yandex" : (Object) (long) linkRoadClass);
        e.put("link.formway", linkFormway == null ? "yandex" : (Object) (long) linkFormway);
        e.put("link.ownership", linkOwnership == null ? "yandex" : (Object) (long) linkOwnership);
        e.put("link.direction", linkDirection == null ? "yandex" : (Object) (long) linkDirection);
        e.put("link.laneNum", linkLaneNum == null ? "yandex" : (Object) (long) linkLaneNum);
        e.put("link.adminCode", linkAdminCode == null ? "yandex" : (Object) linkAdminCode);
        e.put("region", region == null ? "default" : (Object) (long) region);
        e.put("position.ownership", positionOwnership == null ? "yandex" : (Object) (long) positionOwnership);
        e.put("traffic.roadClass", trafficRoadClassFromLink ? TRAFFIC_CLASS_LINK : trafficRoadClass == null ? "yandex" : (Object) (long) trafficRoadClass);
        Map<String, Object> h = Json.object();
        h.put("mode", historyMode.name().toLowerCase(java.util.Locale.ROOT));
        if (historyMode == HistoryMode.FIXED) h.put("value", (long) historyValues.get(0));
        if (historyMode == HistoryMode.ALTERNATE) {
            List<Object> values = new ArrayList<>();
            for (Integer v : historyValues) values.add(v == null ? null : (Object) (long) v);
            h.put("values", values);
        }
        e.put("historySpeed", h);
        e.put("speedLimit", speedLimitZero ? "zero" : "yandex");
        e.put("startProtocol", startProtocol == StartProtocol.STOCK ? "stock" : "bridge");
        e.put("naviSt", naviSt);
        e.put("instrumentNaviStatus", instrumentNaviStatus);
        List<Object> set = new ArrayList<>();
        for (String key : given.keySet()) if (!key.equals("id")) set.add(key);
        e.put("overrides", set);
        return e;
    }

    /** The object as the arm carried it. */
    public Map<String, Object> given() { return given; }

    @Override public String toString() { return Json.write(effective()); }

    private static Integer member(Map<String, Object> m, String key, List<Long> allowed) {
        if (!m.containsKey(key)) return null;
        Object v = m.get(key);
        if (!(v instanceof Long) || !allowed.contains(v)) throw bad(key + " must be one of " + allowed);
        return (int) (long) (Long) v;
    }
    private static Integer speed(Object v) {
        if (!(v instanceof Long) || (Long) v < HISTORY_MIN || (Long) v > HISTORY_MAX)
            throw bad("historySpeed values are whole km/h " + HISTORY_MIN + "-" + HISTORY_MAX);
        return (int) (long) (Long) v;
    }
    private static String string(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof String)) throw bad(key + " must be a string");
        return (String) v;
    }
    private static void exactKeys(Map<String, Object> m, String what, String... keys) {
        if (!new java.util.HashSet<>(Arrays.asList(keys)).equals(m.keySet())) throw bad(what + " keys must be " + Arrays.toString(keys));
    }
    private static List<Long> list(long... values) {
        List<Long> out = new ArrayList<>();
        for (long v : values) out.add(v);
        return Collections.unmodifiableList(out);
    }
    private static IllegalArgumentException bad(String why) { return new IllegalArgumentException("variant: " + why); }
}
