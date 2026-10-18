package dev.denza.hud.probe;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns only the offscreen map and its route. Never changes the main map or global location provider. */
final class HudMap implements AutoCloseable {
    /** Map pixels with the event icons MapKit drew, or null while it still shows its light loading grid. */
    static final class Shot {
        final Bitmap bitmap; final float[] finish; final int placed;
        Shot(Bitmap bitmap, float[] finish, int placed) { this.bitmap = bitmap; this.finish = finish; this.placed = placed; }
    }
    /** Route event, speed bump or railway crossing at a fixed place of the current geometry. */
    private static final class Spot {
        final String kind; final int index; final double fraction; final int limit;
        Spot(String kind, int index, double fraction, int limit) { this.kind = kind; this.index = index; this.fraction = fraction; this.limit = limit; }
        boolean after(int i, double f) { return index > i || (index == i && fraction > f); }
        String key() { return index + ":" + Math.round(fraction * 1e6) + ":" + kind; }
    }
    private static final int CANDIDATES = 12;
    /** How far ahead the route is projected for icons to keep off it, in screen pixels. */
    private static final float ROUTE_REACH = 700;
    /** Heading settles over about this long; a raw GPS course shakes the whole map. */
    private static final double HEADING_MS = 300;
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
    private long trafficAt, cameraAt, headingAt;
    private double zoom = Double.NaN, heading = Double.NaN;
    private boolean routeDimmed;
    // Event icons are MapKit placemarks, one per spot, so they turn with the map in its own frame.
    private final Map<String, Object> placemarks = new HashMap<>(), providers = new HashMap<>();
    private final Map<String, String> placemarkIcons = new HashMap<>();
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

    Shot capture(NavigatorSource.Frame frame, long now, HudPainter painter) throws Exception {
        String id = String.valueOf(api.call(frame.route, "getRouteId"));
        if (!id.equals(routeId)) install(frame.route, id);
        int index = -1;
        double fraction = Double.NaN;
        if (frame.position != null) {
            index = (Integer) api.call(frame.position, "getSegmentIndex");
            fraction = (Double) api.call(frame.position, "getSegmentPosition");
        }
        // A position from the route before a rebuild, or Navigator off its route: the map and the car
        // stay, the line dims and nothing on it is drawn until a position on this route returns.
        boolean onRoute = frame.onRoute() && index >= 0 && index < segmentCount && Double.isFinite(fraction) && fraction >= 0 && fraction <= 1;
        refreshTraffic(now);
        dimRoute(!onRoute);
        if (onRoute) {
            arrows(frame.windshield, index, fraction);
            Object start = api.make("com.yandex.mapkit.geometry.PolylinePosition", 0, 0d);
            api.call(line, "hide", api.make("com.yandex.mapkit.geometry.Subpolyline", start, frame.position));
        } else hideArrows();
        double ahead = Math.max(130, Math.min(600, Double.isFinite(frame.cameraDistance) ? frame.cameraDistance * 1.2 : 350));
        double target = Math.max(14.6, Math.min(16.7, Math.log(156543.03392 * Math.cos(Math.toRadians(frame.latitude)) * 118 / ahead) / Math.log(2)));
        double step = Math.min(250, Math.max(0, now - cameraAt)) * .0008;
        zoom = Double.isNaN(zoom) ? target : zoom + Math.max(-step, Math.min(step, target - zoom));
        cameraAt = now;
        smoothHeading(frame.heading, now);
        api.call(map, "move", api.make("com.yandex.mapkit.map.CameraPosition", frame.point, (float) zoom, (float) heading, 0f));
        // Placement reads the camera just set; MapKit draws the placemarks with that same camera.
        int placed = 0;
        if (onRoute && frame.mode.equals("guidance")) {
            List<HudEvents.Mark> marks = new ArrayList<>();
            Map<String, Spot> byKey = new HashMap<>();
            for (Spot spot : spots) {
                if (marks.size() >= CANDIDATES) break;
                if (!spot.after(index, fraction)) continue;
                float[] p = inside(project(spot.index, spot.fraction));
                if (p == null) continue;
                marks.add(new HudEvents.Mark(spot.key(), spot.kind, p[0], p[1], spot.limit));
                byKey.put(spot.key(), spot);
            }
            List<HudEvents.Placed> chosen = painter.placement.place(marks, routeAhead(index, fraction));
            showPlacemarks(chosen, byKey, painter);
            placed = chosen.size();
        } else hidePlacemarks(Collections.<String>emptySet());
        float[] finish = onRoute ? inside(project(segmentCount - 1, 1)) : null;
        Bitmap screenshot = (Bitmap) api.call(offscreen, "captureScreenshot");
        if (screenshot != null && loading(screenshot)) {
            screenshot.recycle();
            return new Shot(null, null, 0);
        }
        return new Shot(screenshot, finish, placed);
    }

    /** Eases the camera's course towards the fix's, the short way round; a stop keeps the last. */
    private void smoothHeading(double raw, long now) {
        if (Double.isNaN(heading)) heading = raw;
        else {
            double dt = Math.min(500, Math.max(0, now - headingAt));
            double turn = ((raw - heading) % 360 + 540) % 360 - 180;
            heading = ((heading + turn * (1 - Math.exp(-dt / HEADING_MS))) % 360 + 360) % 360;
        }
        headingAt = now;
    }

    /** The route ahead of the car on screen, as samples every 3 px, for icons to keep off it. */
    private float[] routeAhead(int index, double fraction) {
        List<float[]> polyline = new ArrayList<>();
        float[] here = project(index, fraction);
        if (here != null) polyline.add(here);
        float travelled = 0;
        for (int i = index + 1; i < latitudes.length && polyline.size() < 240; i++) {
            float[] p = project(i, 0);
            if (p == null) break;
            if (!polyline.isEmpty()) {
                float[] q = polyline.get(polyline.size() - 1);
                travelled += (float) Math.hypot(p[0] - q[0], p[1] - q[1]);
            }
            polyline.add(p);
            if (travelled > ROUTE_REACH) break;
        }
        return HudEvents.samples(polyline, 3);
    }

    private void showPlacemarks(List<HudEvents.Placed> chosen, Map<String, Spot> spotsByKey, HudPainter painter) throws Exception {
        Set<String> shown = new HashSet<>();
        for (HudEvents.Placed p : chosen) {
            String key = p.mark.key;
            Object placemark = placemarks.get(key);
            if (placemark == null) {
                Spot spot = spotsByKey.get(key);
                placemark = newPlacemark(point(spot.index, spot.fraction));
                api.call(placemark, "setZIndex", 100f);
                placemarks.put(key, placemark);
            }
            HudPainter.EventIcon icon = painter.icon(p);
            if (!icon.key.equals(placemarkIcons.get(key))) {
                Object provider = providers.get(icon.key);
                if (provider == null) {
                    provider = api.call(api.type("com.yandex.runtime.image.ImageProvider"), "fromBitmap", icon.bitmap);
                    providers.put(icon.key, provider);
                }
                Object style = api.make("com.yandex.mapkit.map.IconStyle");
                api.call(style, "setAnchor", new PointF(icon.anchorX, icon.anchorY));
                api.call(placemark, "setIcon", provider, style);
                placemarkIcons.put(key, icon.key);
            }
            api.call(placemark, "setVisible", true);
            shown.add(key);
        }
        hidePlacemarks(shown);
    }

    /** addPlacemark() then its point, or the older addPlacemark(Point) where MapKit has only that. */
    private Object newPlacemark(Object point) {
        Object placemark;
        try { placemark = api.call(objects, "addPlacemark"); }
        catch (IllegalStateException missing) {
            if (!String.valueOf(missing.getMessage()).startsWith("Missing method")) throw missing;
            return api.call(objects, "addPlacemark", point);
        }
        api.call(placemark, "setGeometry", point);
        return placemark;
    }

    private void hidePlacemarks(Set<String> keep) {
        for (Map.Entry<String, Object> entry : placemarks.entrySet())
            if (!keep.contains(entry.getKey())) api.call(entry.getValue(), "setVisible", false);
    }

    /** The line at a third of its colour while Navigator is off it; black is the glass, so dimmer is fainter. */
    private void dimRoute(boolean dim) throws Exception {
        if (dim == routeDimmed) return;
        float k = dim ? (float) theme.getJSONObject("states").getDouble("routeDim") : 1f;
        JSONArray types = theme.getJSONArray("jamTypes");
        for (int i = 0; i < types.length(); i++) {
            int c = Color.parseColor(theme.getJSONObject("traffic").getString(types.getString(i)));
            api.call(line, "setPaletteColor", i, Color.rgb(Math.round(Color.red(c) * k), Math.round(Color.green(c) * k), Math.round(Color.blue(c) * k)));
        }
        routeDimmed = dim;
    }

    private void hideArrows() {
        for (Object arrow : arrows.values()) api.call(arrow, "setVisible", false);
        arrows.clear();
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
        routeDimmed = false;
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

    private Object point(int index, double fraction) {
        int next = Math.min(index + 1, latitudes.length - 1);
        double lat = latitudes[index] + (latitudes[next] - latitudes[index]) * fraction;
        double lon = longitudes[index] + (longitudes[next] - longitudes[index]) * fraction;
        return api.make("com.yandex.mapkit.geometry.Point", lat, lon);
    }

    private float[] project(int index, double fraction) {
        Object screen = api.call(window, "worldToScreen", point(index, fraction));
        if (screen == null) return null;
        return new float[]{(Float) api.call(screen, "getX"), (Float) api.call(screen, "getY")};
    }

    private static float[] inside(float[] p) { return p != null && p[0] >= 0 && p[0] <= 300 && p[1] >= 0 && p[1] <= 180 ? p : null; }

    void clearRoute() {
        try { if (route != null && conditionsListener != null) api.call(route, "removeConditionsListener", conditionsListener); }
        finally {
            route = null; conditionsListener = null; routeId = null; colors = null; trafficDirty = true; routeDimmed = false;
            for (Object placemark : placemarks.values()) {
                try { api.call(objects, "remove", placemark); } catch (RuntimeException error) { Log.w("DenzaYandexHud", "placemark removal failed", error); }
            }
            placemarks.clear(); placemarkIcons.clear();
            spots = Collections.emptyList(); arrows.clear(); arrowIndex = -1; latitudes = longitudes = null; geometry = null;
            try { if (line != null) api.call(objects, "remove", line); }
            finally { line = null; zoom = Double.NaN; }
        }
    }
    @Override public void close() { try { clearRoute(); } finally { api.call(objects, "clear"); } }
}
