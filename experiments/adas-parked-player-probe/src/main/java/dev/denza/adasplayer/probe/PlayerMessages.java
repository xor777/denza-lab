package dev.denza.adasplayer.probe;

import dev.denza.adaslink.Json;
import dev.denza.adaslink.StockMessages;
import dev.denza.adaslink.Topics;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds each parked-feed SOME/IP payload exactly as the stock map's senders do, from a scenario's
 * semantic fields, through the byte-exact {@code adas-link} encoders ({@link StockMessages},
 * {@link dev.denza.adaslink.Pb}). Plain Java: no Android, so the host harness compiles and tests it.
 *
 * <p>The route body arrives in the scenario as the stock-gson inner MessageSend JSON with a pathId
 * token; here only the fresh pathId and a fresh envelope {@code time} are substituted, so the route
 * stays byte-identical to the stock recording apart from those two values. The feed is small enough
 * for one chunk (the stock splits only above 512 KiB); the single-chunk envelope is reproduced.
 */
final class PlayerMessages {
    /** The stock map splits the route string only when its UTF-16 length + 24 reaches this. */
    private static final int CHUNK = 524288;

    private PlayerMessages() {}

    /** {@code numberOf(map, "key")} as a long; a missing key is an error (scenarios are complete). */
    static long num(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) return ((Number) value).longValue();
        throw new IllegalArgumentException("missing numeric field: " + key);
    }

    static int i(Map<String, Object> map, String key) { return (int) num(map, key); }

    static double d(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) return ((Number) value).doubleValue();
        throw new IllegalArgumentException("missing numeric field: " + key);
    }

    static boolean b(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Boolean && (Boolean) value;
    }

    static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString();
    }

    /** Substitute the fresh pathId into the stock inner payload, wrap in the single-chunk envelope. */
    @SuppressWarnings("unchecked")
    static byte[] route(String payloadJson, long pathIdToken, long freshPathId, long wallMs, int pathCount) {
        String token = "\"pathID\":" + pathIdToken;
        int at = payloadJson.indexOf(token);
        if (at < 0 || payloadJson.indexOf(token, at + token.length()) >= 0)
            throw new IllegalArgumentException("route payload must contain exactly one pathID token");
        String inner = payloadJson.substring(0, at) + "\"pathID\":" + freshPathId
                + payloadJson.substring(at + token.length());
        if (inner.length() + 24 >= CHUNK)
            throw new IllegalArgumentException("multi-chunk route not expected for the parked feed");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("currentIndex", 0L);
        envelope.put("length", 1L);
        envelope.put("pathCount", (long) pathCount);
        envelope.put("payload", inner);
        envelope.put("time", wallMs);
        return StockMessages.route(Json.write(envelope), pathCount);
    }

    static byte[] selection(long pathId) { return StockMessages.pathSelected(pathId); }

    static byte[] navigationStatus(int state) { return StockMessages.navigationStatus(state); }

    static byte[] position(Map<String, Object> p, long pathId) {
        return StockMessages.vehicleLocation(d(p, "lon"), d(p, "lat"), d(p, "roadLon"), d(p, "roadLat"),
                i(p, "vehicleSpeed"), i(p, "gpsSpeed"), i(p, "course"), pathId,
                i(p, "segIdx"), i(p, "linkIdx"), i(p, "segDist"), i(p, "linkRemain"), i(p, "ownership"));
    }

    static byte[] trafficInfo(Map<String, Object> t) {
        return StockMessages.trafficInfo(i(t, "jamLength"), i(t, "distToJam"), i(t, "jamStatus"),
                i(t, "passTime"), i(t, "construction"), d(t, "cLon"), d(t, "cLat"),
                i(t, "spdLmtSpeedValue"), i(t, "eyeSpeed"), i(t, "eyeDist"),
                d(t, "iStartLon"), d(t, "iStartLat"), d(t, "iEndLon"), d(t, "iEndLat"), i(t, "iSpeed"),
                b(t, "offCourse"), i(t, "distToDest"), i(t, "timeToDest"), i(t, "roadClass"));
    }

    static byte[] region(Map<String, Object> r) {
        return StockMessages.region(i(r, "regionId"), num(r, "country"), i(r, "province"), i(r, "city"), i(r, "weather"));
    }

    static byte[] light(Map<String, Object> l) {
        return StockMessages.trafficLight(i(l, "exist"), d(l, "lon"), d(l, "lat"), i(l, "state"),
                num(l, "start"), num(l, "end"), i(l, "dir"), i(l, "wait"));
    }

    static byte[] tunnel(Map<String, Object> t) {
        return StockMessages.tunnel(i(t, "dist"), i(t, "state"), d(t, "startLon"), d(t, "startLat"),
                d(t, "endLon"), d(t, "endLat"));
    }

    static byte[] speedIntersection(Map<String, Object> s) {
        return StockMessages.speedIntersection(i(s, "flow"), i(s, "history"), i(s, "parallel"),
                i(s, "mix"), i(s, "solid"), i(s, "dist"), str(s, "voice"));
    }

    static byte[] advAutoFunInfo(Map<String, Object> a) {
        return StockMessages.noOddRegionDistance(i(a, "noOddRegionDist"));
    }

    static byte[] tidalLane(Map<String, Object> t) {
        return StockMessages.tidalLane(i(t, "ramp"), i(t, "tidal"));
    }

    static byte[] action(Map<String, Object> a) {
        return StockMessages.actionAndCamera(i(a, "icon"), i(a, "main"), i(a, "assistant"),
                i(a, "distance"), i(a, "cameraType"), i(a, "cameraDistance"));
    }

    /** The server service topics started for the feed: 7, 43, 45, 0x8202. */
    static long[] services() { return Topics.SERVICES; }
}
