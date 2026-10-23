package dev.denza.hud.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Diagnostic copy of guidance, before HUD hold/symbol conversion. No vehicle output. */
final class GuidanceSnapshot {
    private final Reflect api;
    GuidanceSnapshot(Reflect api) { this.api = api; }

    Map<String, Object> read(NavigatorSource.Frame frame, long uptime, long wallTime) {
        Read read = new Read(api);
        Map<String, Object> out = map();
        out.put("schema", "denza-yandex-guidance-v1");
        out.put("coordinateFrame", "wgs84");
        out.put("capturedAtMs", wallTime);
        out.put("capturedUptimeMs", uptime);
        out.put("mode", frame.mode);
        out.put("resumed", frame.resumed);
        out.put("backgroundEnabled", frame.backgroundEnabled);
        // The HUD deliberately holds an old fix. Export its real age, never that visual hold as fresh data.
        Long age = frame.fixAt >= 0 && uptime >= frame.fixAt ? uptime - frame.fixAt : null;
        out.put("fixAgeMs", age);
        String id = null;
        if (frame.active() && frame.route != null) {
            Object value = read.call("routeId", frame.route, "getRouteId");
            if (value instanceof String) id = (String) value;
        }
        out.put("routeId", id);
        boolean positionMatchesRoute = id != null && id.equals(frame.positionRouteId);
        out.put("positionRouteId", id == null ? null : frame.positionRouteId);
        out.put("positionMatchesRoute", positionMatchesRoute);
        out.put("freshLocation", positionMatchesRoute && age != null && age <= 2500 && "guidance".equals(frame.mode));
        if (positionMatchesRoute && frame.position != null)
            out.put("sourcePolylinePosition", read.position("sourcePolylinePosition", frame.position));
        else {
            out.put("sourcePolylinePosition", null);
            if (id != null && frame.position != null)
                read.issues.put("sourcePolylinePosition", "Retained position belongs to another or unknown route");
        }
        if (id != null && frame.location != null) {
            Map<String, Object> location = map();
            Object point = read.call("location.point", frame.location, "getPosition");
            location.put("longitude", read.number("location.longitude", point, "getLongitude"));
            location.put("latitude", read.number("location.latitude", point, "getLatitude"));
            for (String key : Arrays.asList("accuracy", "altitude", "heading", "speed", "confidence", "absoluteTimestamp", "relativeTimestamp"))
                location.put(key, read.number("location." + key, frame.location, getter(key)));
            out.put("location", location);
            Object position = read.call("routePosition", frame.route, "getRoutePosition");
            out.put("distanceToFinishM", read.number("distanceToFinishM", position, "distanceToFinish"));
            out.put("timeToFinishS", read.number("timeToFinishS", position, "timeToFinish"));
        } else {
            out.put("location", null);
            out.put("distanceToFinishM", null);
            out.put("timeToFinishS", null);
        }
        out.put("speedLimitMps", id != null && frame.hint != null && Double.isFinite(frame.hint.limitValue)
                ? frame.hint.limitValue : null);
        // These are semantic Yandex keys, NOT the different HUD/ADAS numeric manoeuvre enums.
        Map<String, Object> hint = null;
        if (id != null && frame.hint != null) {
            hint = map();
            hint.put("action", frame.hint.action);
            hint.put("road", frame.hint.road);
            hint.put("distanceM", frame.hint.distance >= 0 ? frame.hint.distance : null);
            hint.put("position", read.onRoute("nextManoeuvre.position", frame.hint.position, id));
        }
        out.put("nextManoeuvre", hint);
        List<Object> lanes = new ArrayList<>();
        if (id != null) for (NavigatorSource.Lane lane : frame.lanes) {
            Map<String, Object> item = map();
            item.put("kind", lane.kind);
            item.put("directions", new ArrayList<>(lane.directions));
            item.put("highlightedDirection", lane.highlight);
            lanes.add(item);
        }
        out.put("hudLaneHint", lanes);
        out.put("hudLaneHintScope", "Nearest sign within 800 m, at most 6 lanes; empty does not establish no lanes");
        out.put("upcomingTrafficSignals", TrafficSignalSnapshot.read(api, id == null ? null : frame.windshield, id));
        out.put("issues", read.issues);
        return out;
    }

    private static String getter(String key) { return "get" + Character.toUpperCase(key.charAt(0)) + key.substring(1); }
    private static Map<String, Object> map() { return new LinkedHashMap<>(); }

    private static final class Read {
        final Reflect api;
        final Map<String, Object> issues = map();
        Read(Reflect api) { this.api = api; }
        Object call(String path, Object owner, String name, Object... args) {
            if (owner == null) return null;
            try { return api.call(owner, name, args); }
            catch (RuntimeException failure) {
                issues.put(path, "Unavailable " + name + ": " + failure.getClass().getSimpleName());
                return null;
            }
        }
        Object number(String path, Object owner, String name) {
            Object value = call(path, owner, name);
            if (value == null) return null;
            if (value instanceof Number && Double.isFinite(((Number) value).doubleValue())) return value;
            issues.put(path, "Expected finite number");
            return null;
        }
        Object position(String path, Object source) {
            Object index = number(path + ".segmentIndex", source, "getSegmentIndex");
            Object fraction = number(path + ".segmentPosition", source, "getSegmentPosition");
            if (!(index instanceof Number) || !(fraction instanceof Number)) return null;
            double i = ((Number) index).doubleValue(), f = ((Number) fraction).doubleValue();
            if (i < 0 || i != Math.rint(i) || i > Integer.MAX_VALUE || f < 0 || f > 1) {
                issues.put(path, "Invalid polyline position");
                return null;
            }
            Map<String, Object> value = map();
            value.put("segmentIndex", (int) i); value.put("segmentPosition", f);
            return value;
        }
        Object onRoute(String path, Object source, String id) {
            if (source == null || !Boolean.TRUE.equals(call(path, source, "onRoute", id))) return null;
            return position(path, call(path, source, "positionOnRoute", id));
        }
    }
}
