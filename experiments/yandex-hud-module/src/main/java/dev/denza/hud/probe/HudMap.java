package dev.denza.hud.probe;

import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Owns only the offscreen map and its route. Never changes the main map or global location provider. */
final class HudMap implements AutoCloseable {
    private final Reflect api;
    private final JSONObject theme;
    private final Object offscreen, map, objects;
    private Object line, route, conditionsListener;
    private String routeId;
    private int segmentCount;
    private List<Integer> colors;
    private boolean trafficDirty = true, trafficOutdated;
    private long trafficAt, cameraAt;
    private double zoom = Double.NaN;
    int geometryBuilds, trafficUploads;

    HudMap(Reflect api, JSONObject theme) throws Exception {
        this.api = api;
        this.theme = theme;
        Object kit = api.call(api.type("com.yandex.mapkit.MapKitFactory"), "getInstance");
        offscreen = api.call(kit, "createOffscreenMapWindow", 300, 180, 1f);
        Object window = api.call(offscreen, "getMapWindow");
        map = api.call(window, "getMap");
        objects = api.call(map, "getMapObjects");
        api.call(map, "setNightModeEnabled", true);
        api.call(map, "set2DMode", true);
        api.call(map, "setModelsEnabled", false);
        api.call(map, "setIndoorEnabled", false);
        if (!Boolean.TRUE.equals(api.call(map, "setMapStyle", theme.getJSONArray("mapStyle").toString()))) throw new IllegalStateException("Map style rejected");
        api.call(window, "setMaxFps", 10);
        api.call(window, "setFocusPoint", api.make("com.yandex.mapkit.ScreenPoint", 150f, 143f));
    }

    Bitmap capture(NavigatorSource.Frame frame, long now) throws Exception {
        String id = String.valueOf(api.call(frame.route, "getRouteId"));
        if (!id.equals(routeId)) {
            clearRoute();
            Object geometry = api.call(frame.route, "getGeometry");
            segmentCount = ((List<?>) api.call(geometry, "getPoints")).size() - 1;
            if (segmentCount < 1) throw new IllegalStateException("Empty route");
            line = api.call(objects, "addPolyline", geometry);
            JSONArray types = theme.getJSONArray("jamTypes");
            for (int i = 0; i < types.length(); i++) api.call(line, "setPaletteColor", i,
                    android.graphics.Color.parseColor(theme.getJSONObject("traffic").getString(types.getString(i))));
            api.call(line, "setStrokeWidth", 5f);
            api.call(line, "setOutlineColor", 0xff000000);
            api.call(line, "setOutlineWidth", 1f);
            route = frame.route;
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
        int index = (Integer) api.call(frame.position, "getSegmentIndex");
        double fraction = (Double) api.call(frame.position, "getSegmentPosition");
        if (index < 0 || index >= segmentCount || !Double.isFinite(fraction) || fraction < 0 || fraction > 1) throw new IllegalStateException("Invalid route position");
        refreshTraffic(now);
        Object start = api.make("com.yandex.mapkit.geometry.PolylinePosition", 0, 0d);
        api.call(line, "hide", api.make("com.yandex.mapkit.geometry.Subpolyline", start, frame.position));
        double ahead = Math.max(130, Math.min(600, Double.isFinite(frame.cameraDistance) ? frame.cameraDistance * 1.2 : 350));
        double target = Math.max(14.6, Math.min(16.7, Math.log(156543.03392 * Math.cos(Math.toRadians(frame.latitude)) * 118 / ahead) / Math.log(2)));
        double step = Math.min(250, Math.max(0, now - cameraAt)) * .0008;
        zoom = Double.isNaN(zoom) ? target : zoom + Math.max(-step, Math.min(step, target - zoom));
        cameraAt = now;
        api.call(map, "move", api.make("com.yandex.mapkit.map.CameraPosition", frame.point, (float) zoom, (float) frame.heading, 0f));
        return (Bitmap) api.call(offscreen, "captureScreenshot");
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
        trafficDirty = false;
        trafficOutdated = outdated;
        trafficAt = now;
    }

    void clearRoute() {
        try { if (route != null && conditionsListener != null) api.call(route, "removeConditionsListener", conditionsListener); }
        finally {
            route = null; conditionsListener = null; routeId = null; colors = null; trafficDirty = true;
            try { if (line != null) api.call(objects, "remove", line); }
            finally { line = null; zoom = Double.NaN; }
        }
    }
    @Override public void close() { try { clearRoute(); } finally { api.call(objects, "clear"); } }
}
