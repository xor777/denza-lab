package dev.denza.hud.probe;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.util.Log;
import dev.denza.hudlink.camera.StockCamera;
import dev.denza.hudlink.camera.StockCameraOptics;
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

/**
 * Owns only the offscreen map and its route. Never changes the main map or global location provider.
 *
 * <p>Two cameras. By default a 300×180 window, 2D or a fixed tilt, zoomed by the distance to the
 * next manoeuvre. With the theme's {@code camera.law = "stock"} the stock HUD's: the shared
 * {@link StockCamera} sets zoom and pitch as the car's own navigator would, {@link StockCameraOptics}
 * turns them into MapKit's zoom, tilt and field of view for a 600×540 window whose top 600×360 is
 * halved into the frame - the stock HUD's own crop. Everything MapKit draws in window pixels (the
 * route, its arrows, the roads' width, the event icons) is drawn twice as large there, so the frame
 * keeps the theme's sizes.
 */
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
    private final JSONObject theme, routeStyle, camera;
    private final Object offscreen, window, map, objects;
    /** The stock HUD camera, or null for the default one; window pixels per frame pixel. */
    private final StockCamera stock;
    private final float pixel;
    private StockCamera.Route stockRoute;
    private long stockFixAt = -1;
    private float fov = Float.NaN;
    private String cameraKey;
    private Object line, route, geometry, conditionsListener;
    private String routeId;
    private int segmentCount;
    private double[] latitudes, longitudes, metres;
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
        camera = theme.optJSONObject("camera") == null ? new JSONObject() : theme.getJSONObject("camera");
        stock = "stock".equals(camera.optString("law")) ? new StockCamera(policy(camera.optJSONObject("policy"))) : null;
        pixel = stock != null ? 1 / StockCameraOptics.SCALE : 1;
        Object kit = api.call(api.type("com.yandex.mapkit.MapKitFactory"), "getInstance");
        offscreen = stock != null ? api.call(kit, "createOffscreenMapWindow", StockCameraOptics.WINDOW_WIDTH, StockCameraOptics.WINDOW_HEIGHT, 1f)
                : api.call(kit, "createOffscreenMapWindow", 300, 180, 1f);
        window = api.call(offscreen, "getMapWindow");
        map = api.call(window, "getMap");
        objects = api.call(map, "getMapObjects");
        api.call(map, "setNightModeEnabled", true);
        api.call(map, "set2DMode", stock == null && camera.optBoolean("twoD", true));
        api.call(map, "setModelsEnabled", false);
        api.call(map, "setIndoorEnabled", false);
        if (!Boolean.TRUE.equals(api.call(map, "setMapStyle", style(theme.getJSONArray("mapStyle"), pixel).toString()))) throw new IllegalStateException("Map style rejected");
        api.call(window, "setMaxFps", Math.max(1, Math.min(10, camera.optInt("maxFps", 10))));
        JSONArray focus = theme.getJSONObject("size").getJSONArray("focus");
        api.call(window, "setFocusPoint", stock != null ? api.make("com.yandex.mapkit.ScreenPoint", StockCameraOptics.FOCUS_X, StockCameraOptics.FOCUS_Y)
                : api.make("com.yandex.mapkit.ScreenPoint", (float) focus.getDouble(0), (float) focus.getDouble(1)));
    }

    /** The theme's choices for what the stock code leaves open; absent keys keep StockCamera's. */
    private static StockCamera.Policy policy(JSONObject value) {
        StockCamera.Policy policy = new StockCamera.Policy();
        if (value == null) return policy;
        JSONArray rect = value.optJSONArray("displayRect");
        if (rect != null) {
            policy.left = (float) rect.optDouble(0, policy.left); policy.top = (float) rect.optDouble(1, policy.top);
            policy.right = (float) rect.optDouble(2, policy.right); policy.bottom = (float) rect.optDouble(3, policy.bottom);
        }
        policy.naviPointRow = (float) value.optDouble("naviPointRow", policy.naviPointRow);
        policy.curvatureWindow = value.optDouble("curvatureWindow", policy.curvatureWindow);
        policy.freewayLimit = value.optInt("freewayLimit", policy.freewayLimit);
        policy.mainRoadLimit = value.optInt("mainRoadLimit", policy.mainRoadLimit);
        return policy;
    }

    /** The style with every width scale multiplied by {@code k}: roads keep their width in the halved frame. */
    private static JSONArray style(JSONArray source, float k) throws Exception {
        if (k == 1) return source;
        JSONArray result = new JSONArray(source.toString());
        for (int i = 0; i < result.length(); i++) {
            JSONObject stylers = result.getJSONObject(i).optJSONObject("stylers");
            if (stylers != null && stylers.has("scale")) stylers.put("scale", stylers.getDouble("scale") * k);
        }
        return result;
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
        smoothHeading(frame.heading, now);
        if (stock != null) moveStock(frame, onRoute, index, fraction, now);
        else {
            double ahead = Math.max(130, Math.min(600, Double.isFinite(frame.cameraDistance) ? frame.cameraDistance * 1.2 : 350));
            double target = Math.max(camera.optDouble("minZoom", 14.6), Math.min(camera.optDouble("maxZoom", 16.7),
                    Math.log(156543.03392 * Math.cos(Math.toRadians(frame.latitude)) * camera.optDouble("reachPixels", 118) / ahead) / Math.log(2)));
            double step = Math.min(250, Math.max(0, now - cameraAt)) * camera.optDouble("zoomPerSecond", .8) / 1000;
            zoom = Double.isNaN(zoom) ? target : zoom + Math.max(-step, Math.min(step, target - zoom));
            cameraAt = now;
            api.call(map, "move", api.make("com.yandex.mapkit.map.CameraPosition", frame.point, (float) zoom, (float) heading,
                    (float) camera.optDouble("tilt", 0)));
        }
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
                marks.add(new HudEvents.Mark(spot.key(), spot.kind, p[0], p[1], spot.limit, along(spot.index, spot.fraction) - along(index, fraction)));
                byKey.put(spot.key(), spot);
            }
            List<HudEvents.Placed> chosen = painter.placement.place(marks, routeAhead(index, fraction));
            showPlacemarks(chosen, byKey, painter);
            placed = chosen.size();
        } else hidePlacemarks(Collections.<String>emptySet());
        float[] finish = onRoute ? inside(project(segmentCount - 1, 1)) : null;
        Bitmap screenshot = (Bitmap) api.call(offscreen, "captureScreenshot");
        if (screenshot != null && stock != null) screenshot = frameOf(screenshot);
        if (screenshot != null && loading(screenshot)) {
            screenshot.recycle();
            return new Shot(null, null, 0);
        }
        return new Shot(screenshot, finish, placed);
    }

    /**
     * The stock camera: a fresh fix on the route sets its targets - the car's metres along the route,
     * the manoeuvres ahead, the finish, the posted limit -, every frame advances it. Off the route
     * (a rebuild, a position from the old route) no targets are set and the pose holds.
     */
    private void moveStock(NavigatorSource.Frame frame, boolean onRoute, int index, double fraction, long now) throws Exception {
        if (onRoute && frame.fixAt != stockFixAt && stockRoute != null) {
            stockFixAt = frame.fixAt;
            List<StockCamera.Keypoint> keypoints = new ArrayList<>();
            if (frame.windshield != null) {
                for (Object manoeuvre : (List<?>) api.call(frame.windshield, "getManoeuvres")) {
                    Object annotation = api.call(manoeuvre, "getAnnotation");
                    Object action = annotation == null ? null : api.call(annotation, "getAction");
                    if (action == null || "FINISH".equals(action.toString())) continue;
                    Object position = api.call(api.call(manoeuvre, "getPosition"), "positionOnRoute", routeId);
                    if (position == null) continue;
                    keypoints.add(new StockCamera.Keypoint(StockCamera.ACTION,
                            along((Integer) api.call(position, "getSegmentIndex"), (Double) api.call(position, "getSegmentPosition"))));
                }
            }
            keypoints.add(new StockCamera.Keypoint(StockCamera.END, metres[metres.length - 1]));
            int limit = frame.hint != null ? frame.hint.speedLimit : 0;
            stock.locate(stockRoute, keypoints, along(index, fraction), heading, limit, now);
        }
        StockCamera.Pose pose = stock.frame(now);
        StockCameraOptics.Pose mapkit = StockCameraOptics.mapkit(pose.zoom, pose.pitch);
        if (mapkit.fov != fov) {
            api.call(window, "setFieldOfViewY", (double) mapkit.fov);
            fov = mapkit.fov;
        }
        api.call(map, "move", api.make("com.yandex.mapkit.map.CameraPosition", frame.point, mapkit.zoom, (float) heading, mapkit.tilt));
        String key = pose.strategy + ":" + pose.roadClass;
        if (!key.equals(cameraKey)) {
            cameraKey = key;
            Log.i("DenzaYandexHud", "camera " + (pose.strategy == StockCamera.ACTION ? "action" : pose.strategy == StockCamera.END ? "end" : "base")
                    + " roadClass=" + pose.roadClass + " zoom=" + pose.zoom + " pitch=" + pose.pitch);
        }
    }

    /** The window's top 600×360, halved: the stock HUD's crop of its view, the 300×180 frame. */
    private static Bitmap frameOf(Bitmap window) {
        Bitmap crop = Bitmap.createBitmap(window, 0, 0, StockCameraOptics.CROP_WIDTH, StockCameraOptics.CROP_HEIGHT);
        Bitmap frame = Bitmap.createScaledBitmap(crop, 300, 180, true);
        if (crop != window && crop != frame) crop.recycle();
        if (window != frame) window.recycle();
        return frame;
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
        metres = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            latitudes[i] = (Double) api.call(points.get(i), "getLatitude");
            longitudes[i] = (Double) api.call(points.get(i), "getLongitude");
            if (i > 0) metres[i] = metres[i - 1] + metres(latitudes[i - 1], longitudes[i - 1], latitudes[i], longitudes[i]);
        }
        makeLine();
        if (stock != null) {
            stockRoute = new StockCamera.Route(latitudes, longitudes, metres, highways(next));
            stock.reset();
            stockFixAt = -1;
        }
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
        api.call(line, "setStrokeWidth", (float) routeStyle.getDouble("width") * pixel);
        api.call(line, "setOutlineColor", Color.BLACK);
        api.call(line, "setOutlineWidth", (float) routeStyle.getDouble("outline") * pixel);
        api.call(line, "setGradientLength", (float) routeStyle.getDouble("gradient") * pixel);
        api.call(line, "setTurnRadius", (float) routeStyle.getDouble("turnRadius") * pixel);
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
                arrow = api.call(line, "addArrow", w[1], (float) style.getDouble("length") * pixel, Color.parseColor(style.getString("fill")));
                api.call(arrow, "setOutlineColor", Color.parseColor(style.getString("outline")));
                api.call(arrow, "setOutlineWidth", (float) style.getDouble("outlineWidth") * pixel);
                api.call(arrow, "setTriangleHeight", (float) style.getDouble("triangleHeight") * pixel);
                arrowIndex = (Integer) w[2];
                arrowFraction = (Double) w[3];
            }
            keep.put((String) w[0], arrow);
        }
        for (Object stale : arrows.values()) api.call(stale, "setVisible", false);
        arrows.clear();
        arrows.putAll(keep);
    }

    /** Metres from the route's start to a polyline position, along the route. */
    private double along(int index, double fraction) {
        int next = Math.min(index + 1, metres.length - 1);
        return metres[index] + (metres[next] - metres[index]) * fraction;
    }

    private static double metres(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6371008.8 * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    private Object point(int index, double fraction) {
        int next = Math.min(index + 1, latitudes.length - 1);
        double lat = latitudes[index] + (latitudes[next] - latitudes[index]) * fraction;
        double lon = longitudes[index] + (longitudes[next] - longitudes[index]) * fraction;
        return api.make("com.yandex.mapkit.geometry.Point", lat, lon);
    }

    /** A route position in frame pixels: window pixels, through the stock crop when there is one. */
    private float[] project(int index, double fraction) {
        Object screen = api.call(window, "worldToScreen", point(index, fraction));
        if (screen == null) return null;
        return new float[]{(Float) api.call(screen, "getX") / pixel, (Float) api.call(screen, "getY") / pixel};
    }

    /**
     * Which segments are Yandex highways, for the stock camera's road class: a segment any highway
     * range touches. A build without highway ranges gives none.
     */
    private boolean[] highways(Object next) {
        boolean[] result = new boolean[segmentCount];
        try {
            for (Object highway : (List<?>) api.call(next, "getHighways")) {
                Object range = api.call(highway, "getPosition");
                Object begin = api.call(range, "getBegin"), end = api.call(range, "getEnd");
                int first = (Integer) api.call(begin, "getSegmentIndex"), last = (Integer) api.call(end, "getSegmentIndex");
                if ((Double) api.call(end, "getSegmentPosition") <= 0 && last > first) last--;
                for (int i = Math.max(0, first); i <= Math.min(segmentCount - 1, last); i++) result[i] = true;
            }
        } catch (RuntimeException unavailable) {
            Log.w("DenzaYandexHud", "highway ranges unavailable", unavailable);
        }
        return result;
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
            spots = Collections.emptyList(); arrows.clear(); arrowIndex = -1; latitudes = longitudes = metres = null; geometry = null;
            stockRoute = null; stockFixAt = -1; cameraKey = null;
            if (stock != null) stock.reset();
            try { if (line != null) api.call(objects, "remove", line); }
            finally { line = null; zoom = Double.NaN; }
        }
    }
    @Override public void close() { try { clearRoute(); } finally { api.call(objects, "clear"); } }
}
