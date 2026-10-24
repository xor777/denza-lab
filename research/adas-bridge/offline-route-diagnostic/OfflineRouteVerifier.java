package dev.denza.hud.probe;

import android.os.Handler;
import android.os.Looper;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * RESEARCH DIAGNOSTIC ONLY; never use route equality as a mandatory map-readiness gate.
 * Separate OFFLINE requests only: never starts, replaces, or modifies guidance.
 * A ready regional inventory and sampled region lookups are not route coverage.
 * Positive proof requires builtOffline and the complete same ordered polyline.
 * Every method, guard and callback belongs to the supplied main-thread Handler.
 */
final class OfflineRouteVerifier {
    static final int MAX_POINTS = 50000, MAX_REQUEST_POINTS = 256, MAX_ROUTES = 3;
    static final long TIMEOUT_MS = 30000;
    interface Guard {
        boolean isCurrent(String routeId, long epoch, String inventoryFingerprint);
    }
    interface Callback { void onResult(Result result); }
    static final class Result {
        final String routeId, inventoryFingerprint, status, detail, sourceGeometrySha256, offlineRouteId;
        final long routeEpoch;
        final boolean proven;
        final int sourcePoints, offlinePoints;
        Result(Attempt a, String status, String detail, String offlineId, int offlinePoints) {
            this.routeId = a.id; this.routeEpoch = a.epoch; this.inventoryFingerprint = a.inventory;
            this.status = status; this.detail = detail; this.proven = "VERIFIED".equals(status);
            this.sourceGeometrySha256 = a.geometry == null ? null : a.geometry.sha256;
            this.offlineRouteId = offlineId; this.sourcePoints = a.geometry == null ? 0 : a.geometry.points;
            this.offlinePoints = offlinePoints;
        }
    }
    private static final class Geometry {
        final double[] raw, coordinates;
        final int points;
        final String sha256;
        Geometry(double[] raw, double[] coordinates, int points, String sha256) {
            this.raw = raw; this.coordinates = coordinates; this.points = points; this.sha256 = sha256;
        }
    }
    private static final class Attempt {
        Object source;
        final String id, inventory;
        final long epoch;
        Guard guard;
        Callback callback;
        Geometry geometry;
        Object session;
        OfflineDrivingRouteListener listener;
        Runnable timeout;
        boolean done;
        Attempt(Object source, String id, long epoch, String inventory, Guard guard, Callback callback) {
            this.source = source; this.id = id; this.epoch = epoch; this.inventory = inventory;
            this.guard = guard; this.callback = callback;
        }
    }
    private final Reflect api;
    private final Handler main;
    private Object router;
    private Attempt active;
    private String lastAttemptKey;
    private Result lastResult;
    private boolean closed;

    OfflineRouteVerifier(Reflect api, Handler main) {
        if (api == null || main == null) throw new IllegalArgumentException("Verifier dependencies missing");
        this.api = api; this.main = main;
    }

    /** Same hashing and duplicate normalization used by positive verification. */
    String geometrySha256(Object sourceRoute) { requireMain(); return geometry(sourceRoute).sha256; }
    Result result() { requireMain(); return lastResult; }

    /** One attempt per exact route epoch/ID and inventory fingerprint, including failures. */
    void verify(Object sourceRoute, String routeId, long epoch, String inventoryFingerprint,
                Guard guard, Callback callback) {
        requireMain();
        if (closed) return;
        if (routeId == null || routeId.isEmpty() || inventoryFingerprint == null
                || inventoryFingerprint.isEmpty() || guard == null || callback == null)
            throw new IllegalArgumentException("Verifier subject/inventory missing");
        String key = epoch + ":" + routeId.length() + ":" + routeId + ":" + inventoryFingerprint;
        if (key.equals(lastAttemptKey)) return;
        cancel();
        lastAttemptKey = key;
        Attempt a = new Attempt(sourceRoute, routeId, epoch, inventoryFingerprint, guard, callback);
        active = a;
        if (!current(a)) { finish(a, "STALE", "subject_or_inventory_changed", null, 0); return; }
        try {
            a.geometry = geometry(sourceRoute);
            if (!routeId.equals(String.valueOf(api.call(sourceRoute, "getRouteId"))))
                throw new IllegalStateException("source_route_id_mismatch");
            Object options = drivingOptions(sourceRoute);
            Object vehicleOptions = api.call(sourceRoute, "getVehicleOptions");
            if (vehicleOptions == null) throw new IllegalStateException("source_vehicle_options_missing");
            List<?> points = requestPoints(sourceRoute, a.geometry);
            if (router == null) {
                Object directions = api.call(api.type("com.yandex.mapkit.directions.DirectionsFactory"), "getInstance");
                Object offline = api.type("com.yandex.mapkit.directions.driving.DrivingRouterType").getField("OFFLINE").get(null);
                router = api.call(directions, "createDrivingRouter", offline);
                if (router == null) throw new IllegalStateException("offline_router_missing");
            }
            a.listener = new OfflineDrivingRouteListener(new OfflineDrivingRouteListener.Sink() {
                @Override public void routes(List<?> routes) { dispatch(() -> accept(a, routes)); }
                @Override public void error(Object error) {
                    dispatch(() -> {
                        if (active != a || a.done) return;
                        finish(a, current(a) ? "REQUEST_ERROR" : "STALE",
                                error == null ? "null_native_error" : error.getClass().getName(), null, 0);
                    });
                }
            });
            a.timeout = () -> {
                if (active == a && !a.done)
                    finish(a, current(a) ? "TIMEOUT" : "STALE", "offline_request_timeout", null, 0);
            };
            if (!main.postDelayed(a.timeout, TIMEOUT_MS))
                throw new IllegalStateException("timeout_dispatch_failed");
            if (!current(a)) { finish(a, "STALE", "inventory_changed_before_request", null, 0); return; }
            Object session = api.call(router, "requestRoutes", points, options, vehicleOptions, a.listener);
            if (active == a && !a.done) {
                a.session = session;
                if (session == null) finish(a, "REQUEST_ERROR", "offline_session_missing", null, 0);
            } else cancelSession(session); // Also handles a synchronous native callback.
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (active == a && !a.done)
                finish(a, current(a) ? "UNSUPPORTED" : "STALE", failure.getClass().getSimpleName()
                        + ":" + String.valueOf(failure.getMessage()), null, 0);
        }
    }

    /** Cancels the separate verification session, never the Navigator guidance route. */
    void cancel() {
        requireMain();
        if (active != null) finish(active, "CANCELLED", "verification_cancelled", null, 0);
    }
    void close() { requireMain(); cancel(); closed = true; router = null; }

    private void accept(Attempt a, List<?> routes) {
        requireMain();
        if (active != a || a.done) return;
        try {
            if (!current(a) || !same(a.geometry, geometry(a.source))) {
                finish(a, "STALE", "source_geometry_or_inventory_changed", null, 0); return;
            }
            if (routes == null || routes.isEmpty()) { finish(a, "NO_ROUTE", "no_offline_candidate", null, 0); return; }
            if (routes.size() > MAX_ROUTES) throw new IllegalStateException("offline_candidate_budget_exceeded");
            boolean sawOffline = false;
            for (Object candidate : routes) {
                Object flags = api.call(api.call(candidate, "getMetadata"), "getFlags");
                if (!Boolean.TRUE.equals(api.call(flags, "getBuiltOffline"))) continue;
                sawOffline = true;
                Geometry offline = geometry(candidate);
                if (!same(a.geometry, offline)) continue;
                String offlineId = String.valueOf(api.call(candidate, "getRouteId"));
                if (!current(a) || !same(a.geometry, geometry(a.source))) {
                    finish(a, "STALE", "subject_changed_during_comparison", null, 0); return;
                }
                finish(a, "VERIFIED", "native_built_offline_exact_full_geometry", offlineId, offline.points);
                return;
            }
            finish(a, sawOffline ? "DIFFERENT_PATH" : "REQUEST_ERROR",
                    sawOffline ? "offline_route_differs_coverage_unproved" : "native_built_offline_not_true", null, 0);
        } catch (RuntimeException failure) {
            finish(a, current(a) ? "REQUEST_ERROR" : "STALE", failure.getClass().getSimpleName()
                    + ":" + String.valueOf(failure.getMessage()), null, 0);
        }
    }
    private boolean current(Attempt a) {
        try { return !closed && a.guard.isCurrent(a.id, a.epoch, a.inventory); }
        catch (RuntimeException ignored) { return false; }
    }
    private void finish(Attempt a, String status, String detail, String offlineId, int offlinePoints) {
        if (a.done || active != a) return;
        a.done = true; active = null;
        if (a.timeout != null) main.removeCallbacks(a.timeout);
        Object session = a.session; a.session = null;
        Result result = new Result(a, status, detail, offlineId, offlinePoints);
        Callback callback = a.callback;
        a.source = null; a.geometry = null; a.guard = null; a.callback = null;
        a.listener = null; a.timeout = null;
        lastResult = result;
        cancelSession(session);
        try { callback.onResult(result); }
        catch (RuntimeException ignored) { /* Never throw an application callback into JNI. */ }
    }
    private void cancelSession(Object session) {
        if (session != null) try { api.call(session, "cancel"); } catch (RuntimeException ignored) { }
    }
    private void dispatch(Runnable task) {
        if (Looper.myLooper() == main.getLooper()) task.run();
        else main.post(task); // The already-armed timeout bounds a dropped delivery.
    }
    private void requireMain() {
        if (Looper.myLooper() != main.getLooper()) throw new IllegalStateException("Offline verifier requires main thread");
    }
    private Object optional(Object target, String name) {
        for (java.lang.reflect.Method method : target.getClass().getMethods())
            if (method.getName().equals(name) && method.getParameterTypes().length == 0) return api.call(target, name);
        return null;
    }

    private Object drivingOptions(Object source) {
        Object original = optional(source, "getDrivingOptions");
        Object copy = api.make("com.yandex.mapkit.directions.driving.DrivingOptions");
        if (original != null) {
            // Never mutate options retained by the actual guidance router.
            String[] fields = {"AnnotationLanguage", "AvoidanceFlags", "AvoidedZones", "DepartureTime",
                    "HdRoute", "InitialAzimuth"};
            for (String field : fields) {
                Object value = api.call(original, "get" + field);
                if (value != null) api.call(copy, "set" + field, value);
            }
        }
        api.call(copy, "setRoutesCount", MAX_ROUTES);
        return copy;
    }

    private List<?> requestPoints(Object source, Geometry geometry) throws ReflectiveOperationException {
        Object original = optional(source, "getRequestPoints");
        if (original instanceof List && ((List<?>) original).size() >= 2) {
            List<?> points = (List<?>) original;
            if (points.size() > MAX_REQUEST_POINTS) throw new IllegalStateException("request_point_budget_exceeded");
            Class<?> expected = api.type("com.yandex.mapkit.RequestPoint");
            for (Object point : points) if (point == null || !expected.isInstance(point))
                throw new IllegalStateException("invalid_original_request_point");
            return new ArrayList<Object>(points);
        }
        List<Object> result = new ArrayList<>();
        Object waypoint = api.type("com.yandex.mapkit.RequestPointType").getField("WAYPOINT").get(null);
        Object via = api.type("com.yandex.mapkit.RequestPointType").getField("VIAPOINT").get(null);
        addRequestPoint(result, geometry.raw[0], geometry.raw[1], waypoint);
        Object saved = optional(source, "getWayPoints");
        if (saved instanceof List) {
            List<?> positions = (List<?>) saved;
            if (positions.size() > MAX_REQUEST_POINTS - 2) throw new IllegalStateException("via_point_budget_exceeded");
            for (Object position : positions) {
                int index = ((Number) api.call(position, "getSegmentIndex")).intValue();
                double fraction = ((Number) api.call(position, "getSegmentPosition")).doubleValue();
                int rawPoints = geometry.raw.length / 2;
                if (index < 0 || index >= rawPoints || !Double.isFinite(fraction) || fraction < 0 || fraction > 1
                        || (index == rawPoints - 1 && fraction != 0))
                    throw new IllegalStateException("invalid_saved_waypoint");
                int next = Math.min(index + 1, rawPoints - 1);
                double lat = interpolate(geometry.raw[index * 2], geometry.raw[next * 2], fraction);
                double lon = interpolate(geometry.raw[index * 2 + 1], geometry.raw[next * 2 + 1], fraction);
                if (equal(lat, geometry.raw[0]) && equal(lon, geometry.raw[1])) continue;
                int end = geometry.raw.length - 2;
                if (equal(lat, geometry.raw[end]) && equal(lon, geometry.raw[end + 1])) continue;
                addRequestPoint(result, lat, lon, via);
            }
        }
        int end = geometry.raw.length - 2;
        addRequestPoint(result, geometry.raw[end], geometry.raw[end + 1], waypoint);
        return result;
    }
    private void addRequestPoint(List<Object> result, double lat, double lon, Object kind) {
        Object point = api.make("com.yandex.mapkit.geometry.Point", lat, lon);
        result.add(api.make("com.yandex.mapkit.RequestPoint", point, kind, null, null, null));
    }
    private static double interpolate(double a, double b, double fraction) {
        return fraction == 0 ? a : fraction == 1 ? b : a + (b - a) * fraction;
    }
    private Geometry geometry(Object route) {
        if (route == null) throw new IllegalStateException("source_route_missing");
        Object value = api.call(api.call(route, "getGeometry"), "getPoints");
        if (!(value instanceof List)) throw new IllegalStateException("geometry_points_missing");
        List<?> points = (List<?>) value;
        if (points.size() < 2 || points.size() > MAX_POINTS) throw new IllegalStateException("geometry_point_budget");
        double[] raw = new double[points.size() * 2], coordinates = new double[raw.length];
        int used = 0;
        for (int i = 0; i < points.size(); ++i) {
            double lat = ((Number) api.call(points.get(i), "getLatitude")).doubleValue();
            double lon = ((Number) api.call(points.get(i), "getLongitude")).doubleValue();
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180)
                throw new IllegalStateException("invalid_geometry_coordinate");
            raw[i * 2] = lat; raw[i * 2 + 1] = lon;
            if (used != 0 && equal(coordinates[used - 2], lat) && equal(coordinates[used - 1], lon)) continue;
            coordinates[used++] = lat; coordinates[used++] = lon;
        }
        if (used < 4) throw new IllegalStateException("zero_extent_geometry");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < used; ++i) {
                long bits = Double.doubleToLongBits(coordinates[i]);
                for (int byteIndex = 0; byteIndex < 8; ++byteIndex) digest.update((byte) (bits >>> (byteIndex * 8)));
            }
            StringBuilder hash = new StringBuilder(64);
            for (byte v : digest.digest()) {
                hash.append(Character.forDigit((v & 255) >>> 4, 16));
                hash.append(Character.forDigit(v & 15, 16));
            }
            return new Geometry(raw, coordinates, used / 2, hash.toString());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean equal(double a, double b) { return Double.doubleToLongBits(a) == Double.doubleToLongBits(b); }
    private static boolean same(Geometry a, Geometry b) {
        if (a.points != b.points) return false;
        for (int i = 0; i < a.points * 2; ++i) if (!equal(a.coordinates[i], b.coordinates[i])) return false;
        return true;
    }
}
