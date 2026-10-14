package dev.denza.hud.probe;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns only the offscreen map and its route. Never changes the main map or global location provider. */
final class HudMap implements AutoCloseable {
    /** Something on the route ahead, already in frame pixels; nearest first. */
    static final class Mark {
        final String kind; final float x, y; final int limit;
        Mark(String kind, float x, float y, int limit) { this.kind = kind; this.x = x; this.y = y; this.limit = limit; }
    }
    /** Map pixels, or null while MapKit still shows its light loading grid; route = screen polyline ahead. */
    static final class Shot {
        final Bitmap bitmap; final List<Mark> marks; final float[] finish, route;
        Shot(Bitmap bitmap, List<Mark> marks, float[] finish, float[] route) { this.bitmap = bitmap; this.marks = marks; this.finish = finish; this.route = route; }
    }
    /** Route event, speed bump or railway crossing at a fixed place of the current geometry. */
    private static final class Spot {
        final String kind; final int index; final double fraction; final int limit;
        Spot(String kind, int index, double fraction, int limit) { this.kind = kind; this.index = index; this.fraction = fraction; this.limit = limit; }
        boolean after(int i, double f) { return index > i || (index == i && fraction > f); }
    }
    private static final int CANDIDATES = 12, ROUTE_POINTS = 240;
    private final Reflect api;
    private final JSONObject theme, routeStyle;
    private final Object offscreen, window, map, objects;
    private Object line, route, geometry, conditionsListener;
    private String routeId;
    private int segmentCount;
    private double[] latitudes, longitudes;
    private List<Integer> colors;
    private List<Spot> spots = Collections.emptyList();
    private final Map<String, Object> arrows = new HashMap<>();
    private int arrowIndex = -1;
    private double arrowFraction;
    private boolean trafficDirty = true, trafficOutdated;
    private long trafficAt, cameraAt;
    private double zoom = Double.NaN;
    int geometryBuilds, trafficUploads, spotReads;

    HudMap(Reflect api, JSONObject theme) throws Exception {
        this.api = api;
        this.theme = theme;
        routeStyle = theme.getJSONObject("route");
        Object kit = api.call(api.type("com.yandex.mapkit.MapKitFactory"), "getInstance");
        offscreen = api.call(kit, "createOffscreenMapWindow", 300, 180, 1f);
        window = api.call(offscreen, "getMapWindow");
        map = api.call(window, "getMap");
        objects = api.call(map, "getMapObjects");
        api.call(map, "setNightModeEnabled", true);
        api.call(map, "set2DMode", true);
        api.call(map, "setModelsEnabled", false);
        api.call(map, "setIndoorEnabled", false);
        if (!Boolean.TRUE.equals(api.call(map, "setMapStyle", theme.getJSONArray("mapStyle").toString()))) throw new IllegalStateException("Map style rejected");
        api.call(window, "setMaxFps", 10);
        JSONArray focus = theme.getJSONObject("size").getJSONArray("focus");
        api.call(window, "setFocusPoint", api.make("com.yandex.mapkit.ScreenPoint", (float) focus.getDouble(0), (float) focus.getDouble(1)));
    }

    Shot capture(NavigatorSource.Frame frame, long now) throws Exception {
        String id = String.valueOf(api.call(frame.route, "getRouteId"));
        if (!id.equals(routeId)) install(frame.route, id);
        int index = (Integer) api.call(frame.position, "getSegmentIndex");
        double fraction = (Double) api.call(frame.position, "getSegmentPosition");
        if (index < 0 || index >= segmentCount || !Double.isFinite(fraction) || fraction < 0 || fraction > 1) throw new IllegalStateException("Invalid route position");
        refreshTraffic(now);
        arrows(frame.windshield, index, fraction);
        Object start = api.make("com.yandex.mapkit.geometry.PolylinePosition", 0, 0d);
        api.call(line, "hide", api.make("com.yandex.mapkit.geometry.Subpolyline", start, frame.position));
        double ahead = Math.max(130, Math.min(600, Double.isFinite(frame.cameraDistance) ? frame.cameraDistance * 1.2 : 350));
        double target = Math.max(14.6, Math.min(16.7, Math.log(156543.03392 * Math.cos(Math.toRadians(frame.latitude)) * 118 / ahead) / Math.log(2)));
        double step = Math.min(250, Math.max(0, now - cameraAt)) * .0008;
        zoom = Double.isNaN(zoom) ? target : zoom + Math.max(-step, Math.min(step, target - zoom));
        cameraAt = now;
        api.call(map, "move", api.make("com.yandex.mapkit.map.CameraPosition", frame.point, (float) zoom, (float) frame.heading, 0f));
        // Project after the move so marks and the screenshot share one camera.
        List<Mark> marks = new ArrayList<>();
        for (Spot spot : spots) {
            if (marks.size() >= CANDIDATES) break;
            if (!spot.after(index, fraction)) continue;
            float[] p = inside(project(spot.index, spot.fraction));
            if (p != null) marks.add(new Mark(spot.kind, p[0], p[1], spot.limit));
        }
        float[] finish = inside(project(segmentCount - 1, 1));
        // The lane badge chooses its corner from where the route really goes on screen. Dense suburban
        // geometry needs the walk bounded by screen length, not by a vertex count.
        float[] screenRoute = null;
        if (!frame.lanes.isEmpty()) {
            double reach = theme.getJSONObject("lanes").getDouble("reach") + 20, walked = 0;
            float[] buffer = new float[ROUTE_POINTS * 2], last = project(index, fraction);
            int n = 0;
            if (last != null) { buffer[n++] = last[0]; buffer[n++] = last[1]; }
            for (int i = index + 1; i <= segmentCount && n < buffer.length && walked <= reach; i++) {
                float[] p = project(i, 0);
                if (p == null) continue;
                if (last != null) walked += Math.hypot(p[0] - last[0], p[1] - last[1]);
                buffer[n++] = p[0]; buffer[n++] = p[1];
                last = p;
            }
            screenRoute = java.util.Arrays.copyOf(buffer, n);
        }
        Bitmap screenshot = (Bitmap) api.call(offscreen, "captureScreenshot");
        if (screenshot != null && loading(screenshot)) {
            screenshot.recycle();
            return new Shot(null, Collections.<Mark>emptyList(), null, screenRoute);
        }
        return new Shot(screenshot, marks, finish, screenRoute);
    }

    /**
     * Until style and tiles arrive MapKit paints a placeholder: a light grid (a white flash on the glass)
     * or its night clear colour (13,13,13) with grid lines. The styled map has neither: land is black.
     */
    private static boolean loading(Bitmap bitmap) {
        int light = 0, clear = 0, total = 0;
        for (int y = 5; y < 180; y += 15) for (int x = 5; x < 300; x += 15) {
            int c = bitmap.getPixel(x, y), r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            total++;
            if (r >= 0xC0 && g >= 0xC0 && b >= 0xC0) light++;
            if (r == g && g == b && r >= 8 && r <= 18) clear++;
        }
        return light * 4 > total || clear * 10 > total * 3;
    }

    private void install(Object next, String id) throws Exception {
        clearRoute();
        geometry = api.call(next, "getGeometry");
        List<?> points = (List<?>) api.call(geometry, "getPoints");
        segmentCount = points.size() - 1;
        if (segmentCount < 1) throw new IllegalStateException("Empty route");
        latitudes = new double[points.size()];
        longitudes = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            latitudes[i] = (Double) api.call(points.get(i), "getLatitude");
            longitudes[i] = (Double) api.call(points.get(i), "getLongitude");
        }
        makeLine();
        route = next;
        Class<?> listener = api.type("com.yandex.mapkit.directions.driving.ConditionsListener");
        conditionsListener = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
                return "DenzaHudConditions";
            }
            trafficDirty = true;
            return null;
        });
        api.call(route, "addConditionsListener", conditionsListener);
        routeId = id;
        geometryBuilds++;
    }

    /** The route polyline with our style; current traffic colours are restored on a rebuilt line. */
    private void makeLine() throws Exception {
        line = api.call(objects, "addPolyline", geometry);
        JSONArray types = theme.getJSONArray("jamTypes");
        for (int i = 0; i < types.length(); i++) api.call(line, "setPaletteColor", i,
                Color.parseColor(theme.getJSONObject("traffic").getString(types.getString(i))));
        api.call(line, "setStrokeWidth", (float) routeStyle.getDouble("width"));
        api.call(line, "setOutlineColor", Color.BLACK);
        api.call(line, "setOutlineWidth", (float) routeStyle.getDouble("outline"));
        api.call(line, "setGradientLength", (float) routeStyle.getDouble("gradient"));
        api.call(line, "setTurnRadius", (float) routeStyle.getDouble("turnRadius"));
        if (colors != null) api.call(line, "setStrokeColors", colors);
        arrows.clear();
        arrowIndex = -1;
        arrowFraction = 0;
    }

    private void refreshTraffic(long now) throws Exception {
        boolean outdated = (Boolean) api.call(route, "isAreConditionsOutdated");
        if (!trafficDirty && outdated == trafficOutdated && now - trafficAt < 30000) return;
        List<?> jams = (List<?>) api.call(route, "getJamSegments");
        boolean valid = !outdated && jams.size() == segmentCount;
        JSONArray types = theme.getJSONArray("jamTypes");
        List<Integer> next = new ArrayList<>(segmentCount);
        for (int i = 0; i < segmentCount; i++) {
            String type = valid ? String.valueOf(api.call(jams.get(i), "getJamType")) : "UNKNOWN";
            int color = 0;
            for (int j = 0; j < types.length(); j++) if (type.equals(types.getString(j))) color = j;
            next.add(color);
        }
        if (!next.equals(colors)) { api.call(line, "setStrokeColors", next); colors = next; trafficUploads++; }
        // Road events arrive with the same conditions update; read them on the same cadence.
        spots = readSpots();
        trafficDirty = false;
        trafficOutdated = outdated;
        trafficAt = now;
    }

    private List<Spot> readSpots() throws Exception {
        List<Spot> result = new ArrayList<>();
        JSONObject tags = theme.getJSONObject("eventTags");
        JSONArray priority = theme.getJSONArray("eventPriority");
        for (Object event : (List<?>) api.call(route, "getEvents")) {
            String kind = null;
            int rank = Integer.MAX_VALUE;
            for (Object tag : (List<?>) api.call(event, "getTags")) {
                String value = tags.optString(String.valueOf(tag), null);
                if (value == null) continue;
                for (int i = 0; i < priority.length() && i < rank; i++) if (priority.getString(i).equals(value)) { rank = i; kind = value; }
            }
            if (kind == null) continue;
            Float limit = (Float) api.call(event, "getSpeedLimit");
            add(result, kind, api.call(event, "getPolylinePosition"), kind.equals("camera") ? NavigatorSource.speedLimit(limit) : 0);
        }
        for (Object bump : (List<?>) api.call(route, "getSpeedBumps")) add(result, "bump", api.call(bump, "getPosition"), 0);
        for (Object crossing : (List<?>) api.call(route, "getRailwayCrossings")) add(result, "railway", api.call(crossing, "getPosition"), 0);
        Collections.sort(result, (a, b) -> a.index != b.index ? Integer.compare(a.index, b.index) : Double.compare(a.fraction, b.fraction));
        if (spotReads++ == 0) {
            Map<String, Integer> kinds = new java.util.TreeMap<>();
            for (Spot spot : result) kinds.put(spot.kind, kinds.containsKey(spot.kind) ? kinds.get(spot.kind) + 1 : 1);
            Log.i("DenzaYandexHud", "route spots=" + kinds);
        }
        return result;
    }

    private void add(List<Spot> result, String kind, Object position, int limit) {
        if (position == null) return;
        int index = (Integer) api.call(position, "getSegmentIndex");
        double fraction = (Double) api.call(position, "getSegmentPosition");
        if (index >= 0 && index < segmentCount && Double.isFinite(fraction)) result.add(new Spot(kind, index, fraction, limit));
    }

    /**
     * White Yandex manoeuvre arrows on the next manoeuvres only; passed ones are hidden. MapKit accepts
     * arrows only in order along the line, so a manoeuvre announced before an existing arrow rebuilds it.
     */
    private void arrows(Object windshield, int index, double fraction) throws Exception {
        if (windshield == null) return;
        JSONObject style = routeStyle.getJSONObject("arrow");
        List<Object[]> wanted = new ArrayList<>();
        for (Object manoeuvre : (List<?>) api.call(windshield, "getManoeuvres")) {
            if (wanted.size() >= style.getInt("ahead")) break;
            Object position = api.call(api.call(manoeuvre, "getPosition"), "positionOnRoute", routeId);
            if (position == null) continue;
            int i = (Integer) api.call(position, "getSegmentIndex");
            double f = (Double) api.call(position, "getSegmentPosition");
            if (i < index || (i == index && f <= fraction)) continue;
            wanted.add(new Object[]{i + ":" + Math.round(f * 1000), position, i, f});
        }
        Collections.sort(wanted, (a, b) -> {
            int order = Integer.compare((Integer) a[2], (Integer) b[2]);
            return order != 0 ? order : Double.compare((Double) a[3], (Double) b[3]);
        });
        for (Object[] w : wanted) {
            int i = (Integer) w[2];
            double f = (Double) w[3];
            if (!arrows.containsKey(w[0]) && (i < arrowIndex || (i == arrowIndex && f <= arrowFraction))) {
                api.call(objects, "remove", line);
                makeLine();
                break;
            }
        }
        Map<String, Object> keep = new HashMap<>();
        for (Object[] w : wanted) {
            Object arrow = arrows.remove(w[0]);
            if (arrow == null) {
                arrow = api.call(line, "addArrow", w[1], (float) style.getDouble("length"), Color.parseColor(style.getString("fill")));
                api.call(arrow, "setOutlineColor", Color.parseColor(style.getString("outline")));
                api.call(arrow, "setOutlineWidth", (float) style.getDouble("outlineWidth"));
                api.call(arrow, "setTriangleHeight", (float) style.getDouble("triangleHeight"));
                arrowIndex = (Integer) w[2];
                arrowFraction = (Double) w[3];
            }
            keep.put((String) w[0], arrow);
        }
        for (Object stale : arrows.values()) api.call(stale, "setVisible", false);
        arrows.clear();
        arrows.putAll(keep);
    }

    private float[] project(int index, double fraction) {
        int next = Math.min(index + 1, latitudes.length - 1);
        double lat = latitudes[index] + (latitudes[next] - latitudes[index]) * fraction;
        double lon = longitudes[index] + (longitudes[next] - longitudes[index]) * fraction;
        Object screen = api.call(window, "worldToScreen", api.make("com.yandex.mapkit.geometry.Point", lat, lon));
        if (screen == null) return null;
        return new float[]{(Float) api.call(screen, "getX"), (Float) api.call(screen, "getY")};
    }

    private static float[] inside(float[] p) { return p != null && p[0] >= 0 && p[0] <= 300 && p[1] >= 0 && p[1] <= 180 ? p : null; }

    void clearRoute() {
        try { if (route != null && conditionsListener != null) api.call(route, "removeConditionsListener", conditionsListener); }
        finally {
            route = null; conditionsListener = null; routeId = null; colors = null; trafficDirty = true;
            spots = Collections.emptyList(); arrows.clear(); arrowIndex = -1; latitudes = longitudes = null; geometry = null;
            try { if (line != null) api.call(objects, "remove", line); }
            finally { line = null; zoom = Double.NaN; }
        }
    }
    @Override public void close() { try { clearRoute(); } finally { api.call(objects, "clear"); } }
}
