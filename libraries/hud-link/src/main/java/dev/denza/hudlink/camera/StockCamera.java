package dev.denza.hudlink.camera;

import java.util.List;

/**
 * The stock HUD's guided camera, driven by another navigator's route: what zoom and pitch the car's
 * own navigator would ask for, smoothed as it smooths them (research/yandex-hud-renderer/README.md,
 * «Stock camera»). Plain Java and plain numbers, so whoever draws a HUD map can use it.
 *
 * <p>Recovered from the stock code and checked against its original functions: the new-3D rule
 * tables and their first-match selection ({@link StockCameraRules}), the three strategies' targets -
 * a base road's zoom and pitch, a keypoint's pitch 30 - the scalar transition queue
 * ({@link StockCameraQueue}), the curvature measures ({@link StockCameraCurvature}), the projection
 * ({@link StockCameraProjection}) and the HUD's bounds: zoom 14..17, pitch under the profile's
 * ceiling. Targets are set on a fresh fix, as the stock controller does on its location update;
 * the queues advance every frame.
 *
 * <p>What the stock code leaves to an adapter, here explicit and named in {@link Policy}:
 * <ul>
 * <li>Road class. Another navigator gives no functional class; a highway range with its speed limit
 *     stands in: freeway (0) or city expressway (6) on a highway, main road (7) or ordinary road
 *     (9) off it.
 * <li>Curvature: the stock density measure over the route's vertices {@link Policy#curvatureWindow}
 *     metres ahead, taken as one link. Formway is unknown, so the angle measure, ramps and the
 *     ComplexRoad strategy are not used.
 * <li>Records: an Action record from {@code effectDistance} before a manoeuvre to the manoeuvre, an
 *     EndPoint record within its distance of the finish; of the live ones the earliest end wins
 *     (all have the stock priority 10000). The stock overlap and 200 m hysteresis rules were not
 *     recovered.
 * <li>The fits. A manoeuvre is fitted to the display rectangle's top (PointHeight), the remaining
 *     route into the rectangle (InRect, never zooming out within one record), both with the
 *     recovered projection; the stock geometry helpers were not recovered. The rectangle itself
 *     is unset in the stock HUD; here it is the part of the view the glass shows.
 * </ul>
 * Course, roll and the dynamic centre are not this class's: the caller turns the map.
 */
public final class StockCamera {
    public static final int BASE = 0x1000000, ACTION = 1, END = 4;
    private static final float MISSING = -9999f;
    private static final double EARTH = 6371008.8;

    /** Adapter choices the recovered code leaves open. */
    public static final class Policy {
        /** BestDisplayRect in the 800×800 view. The glass shows (100,290)-(700,650); a manoeuvre is fitted a little below its top edge. */
        public float left = 100, top = 330, right = 700, bottom = 650;
        /** The stock selector's screen type: 2 unless the view is wider than tall, and the HUD view is square. */
        public float screenType = 2;
        public float minZoom = 14, maxZoom = 17;
        /** DynamicLevelParam maxDeltaZoomLevel / maxDeltaPitchAngle of the stock HUD. */
        public float maxDeltaZoom = .7f, maxDeltaPitch = 15f;
        /** The stock HUD camera before any target: zoom 16, pitch 33.741604. */
        public float initialZoom = 16, initialPitch = 33.741604f;
        /** Route ahead of the car the curvature is measured over, metres. */
        public double curvatureWindow = 500;
        /** On a highway, a limit at or above this (or none) is a freeway, below it a city expressway; km/h. */
        public int freewayLimit = 100;
        /** Off a highway, a limit at or above this is a main road, below it an ordinary road; km/h. */
        public int mainRoadLimit = 60;
    }

    /** The route: its vertices, metres from its start to each, and whether each segment is a highway. */
    public static final class Route {
        final double[] latitude, longitude, metres;
        final boolean[] highway;

        public Route(double[] latitude, double[] longitude, double[] metres, boolean[] highway) {
            if (latitude.length < 2 || longitude.length != latitude.length || metres.length != latitude.length
                    || highway.length != latitude.length - 1) throw new IllegalArgumentException("route arrays");
            this.latitude = latitude; this.longitude = longitude; this.metres = metres; this.highway = highway;
        }

        double length() { return metres[metres.length - 1]; }

        /** The segment holding {@code at} metres, its last for the end. */
        int segment(double at) {
            int low = 0, high = metres.length - 2;
            while (low < high) {
                int mid = (low + high + 1) >>> 1;
                if (metres[mid] <= at) low = mid; else high = mid - 1;
            }
            return low;
        }

        /** Latitude and longitude at {@code at} metres along the route. */
        double[] point(double at) {
            double clamped = Math.max(0, Math.min(length(), at));
            int i = segment(clamped);
            double span = metres[i + 1] - metres[i];
            double t = span > 0 ? (clamped - metres[i]) / span : 0;
            return new double[]{latitude[i] + (latitude[i + 1] - latitude[i]) * t, longitude[i] + (longitude[i + 1] - longitude[i]) * t};
        }
    }

    /** Where the camera turns its attention: a manoeuvre ({@link #ACTION}) or the finish ({@link #END}), metres from the route's start. */
    public static final class Keypoint {
        public final int type;
        public final double metres;
        public Keypoint(int type, double metres) { this.type = type; this.metres = metres; }
    }

    /** The stock camera's zoom and pitch for a frame, and the strategy that set their targets. */
    public static final class Pose {
        public final float zoom, pitch;
        public final int strategy, roadClass;
        Pose(float zoom, float pitch, int strategy, int roadClass) { this.zoom = zoom; this.pitch = pitch; this.strategy = strategy; this.roadClass = roadClass; }
    }

    private final Policy policy;
    private StockCameraQueue zoomQueue, pitchQueue;
    private float zoom, pitch;
    private int strategy = BASE, roadClass = -1;
    /** The live record: its strategy and its keypoint; InRect's growing zoom belongs to it. */
    private int recordType = BASE;
    private double recordEnd = Double.NaN;
    private float recordZoom = Float.NaN;

    public StockCamera(Policy policy) {
        this.policy = policy;
        reset();
    }

    /** A new route or none: the stock camera's starting pose, no record. */
    public void reset() {
        zoom = policy.initialZoom;
        pitch = clampPitch(policy.initialPitch, zoom);
        zoomQueue = new StockCameraQueue();
        pitchQueue = new StockCameraQueue();
        strategy = recordType = BASE;
        roadClass = -1;
        recordEnd = Double.NaN;
        recordZoom = Float.NaN;
    }

    /**
     * A fresh fix on the route: chooses the record and sets the zoom and pitch targets.
     * {@code at}: the car's metres along the route; {@code course}: degrees from north;
     * {@code speedLimit}: km/h, 0 when unknown; {@code keypoints} ahead in route order.
     */
    public void locate(Route route, List<Keypoint> keypoints, double at, double course, int speedLimit, long now) {
        roadClass = roadClass(route.highway[route.segment(at)], speedLimit);
        StockCameraRules.Input input = new StockCameraRules.Input(roadClass, Float.NaN, curvature(route, at, roadClass), zoom, policy.screenType);
        // A keypoint record lives from its effect distance before the keypoint to the keypoint; the
        // earliest end of the live ones wins.
        Keypoint chosen = null;
        StockCameraRules.Rule chosenRule = null;
        for (Keypoint keypoint : keypoints) {
            double ahead = keypoint.metres - at;
            if (!(ahead > 0)) continue;
            StockCameraRules.Rule rule = StockCameraRules.select(keypoint.type == END ? StockCameraRules.Table.END_ZOOM
                    : StockCameraRules.Table.ACTION_ZOOM, input);
            if (rule == null || rule.effectDistance <= 0 || ahead > rule.effectDistance) continue;
            if (chosen == null || keypoint.metres < chosen.metres) { chosen = keypoint; chosenRule = rule; }
        }
        int type = chosen == null ? BASE : chosen.type;
        double end = chosen == null ? Double.NaN : chosen.metres;
        if (type != recordType || !(end == recordEnd || (Double.isNaN(end) && Double.isNaN(recordEnd)))) {
            recordType = type;
            recordEnd = end;
            recordZoom = Float.NaN;
        }
        strategy = type;

        StockCameraRules.Target zoomTarget = new StockCameraRules.Target(MISSING, 0);
        StockCameraRules.Target pitchTarget = new StockCameraRules.Target(MISSING, 0);
        // Pitch is looked up with the current zoom, before the new zoom target.
        if (type == BASE) {
            StockCameraRules.simplePitch(StockCameraRules.select(StockCameraRules.Table.BASE_PITCH, input), pitchTarget);
            StockCameraRules.simpleZoom(StockCameraRules.select(StockCameraRules.Table.BASE_ZOOM, input), zoomTarget);
        } else {
            StockCameraRules.simplePitch(StockCameraRules.select(StockCameraRules.Table.KEYPOINT_PITCH, input), pitchTarget);
            double[] car = route.point(at);
            float fitted = type == ACTION ? pointHeight(route.point(end), car, course) : inRect(route, at, end, car, course);
            if (fitted != MISSING) {
                if (type == END) {
                    if (!Float.isNaN(recordZoom)) fitted = Math.max(fitted, recordZoom);
                    fitted = bound(fitted, chosenRule);
                    recordZoom = fitted;
                } else fitted = bound(fitted, chosenRule);
                zoomTarget.value = fitted;
                zoomTarget.count = 10;
            }
        }
        zoomQueue.target(zoom, zoomTarget.value, zoomTarget.count, policy.maxDeltaZoom, now);
        pitchQueue.target(pitch, pitchTarget.value, pitchTarget.count, policy.maxDeltaPitch, now);
    }

    /** The pose for a frame at {@code now}, the queues advanced and the HUD's bounds applied. */
    public Pose frame(long now) {
        Float nextZoom = zoomQueue.pop(now);
        if (nextZoom != null) zoom = nextZoom;
        zoom = Math.max(policy.minZoom, Math.min(policy.maxZoom, zoom));
        Float nextPitch = pitchQueue.pop(now);
        if (nextPitch != null) pitch = nextPitch;
        pitch = clampPitch(pitch, zoom);
        return new Pose(zoom, pitch, strategy, roadClass);
    }

    int roadClass(boolean highway, int speedLimit) {
        if (highway) return speedLimit <= 0 || speedLimit >= policy.freewayLimit ? 0 : 6;
        return speedLimit >= policy.mainRoadLimit ? 7 : 9;
    }

    /** The stock density measure over the route's vertices ahead, as one link: quick for 0/6/7, segment otherwise. */
    float curvature(Route route, double at, int roadClass) {
        double end = Math.min(route.length(), at + policy.curvatureWindow);
        long length = Math.round(end - at);
        int count = 0;
        for (int i = route.segment(at) + 1; i < route.metres.length && route.metres[i] <= end; i++) count++;
        count += 2; // the car's and the window's end points bound the stretch like a link's own
        if (roadClass == 0 || roadClass == 6 || roadClass == 7)
            return StockCameraCurvature.calculate(roadClass, 0, length, new double[count][2]).curvature;
        return StockCameraCurvature.segment(length, new long[]{length}, new int[]{count});
    }

    /**
     * PointHeight: the zoom that puts the manoeuvre at the display rectangle's top, or none when it
     * lies 90° or more off the course (the stock angle test).
     */
    private float pointHeight(double[] keypoint, double[] car, double course) {
        double north = Math.toRadians(keypoint[0] - car[0]) * EARTH;
        double east = Math.toRadians(keypoint[1] - car[1]) * EARTH * Math.cos(Math.toRadians(car[0]));
        double bearing = Math.toDegrees(Math.atan2(east, north));
        double off = ((bearing - course) % 360 + 540) % 360 - 180;
        if (Math.abs(off) >= 90 || Math.hypot(north, east) < 1e-3) return MISSING;
        // Higher zoom carries the keypoint up the view: the largest zoom that keeps it at or below the top.
        return largestZoom(z -> {
            double[] p = StockCameraProjection.project(keypoint[0], keypoint[1], car[0], car[1], z, pitch, course);
            return p != null && p[1] >= policy.top;
        });
    }

    /** InRect: the largest zoom that keeps the route ahead, up to {@code end}, inside the display rectangle above the car. */
    private float inRect(Route route, double at, double end, double[] car, double course) {
        int from = route.segment(at) + 1, to = route.segment(end);
        double[] last = route.point(end);
        return largestZoom(z -> {
            double[] carScreen = StockCameraProjection.project(car[0], car[1], car[0], car[1], z, pitch, course);
            double bottom = carScreen != null && carScreen[1] > policy.top ? (int) carScreen[1] : policy.bottom;
            if (policy.right <= policy.left || bottom - policy.top < 1) return false;
            for (int i = from; i <= to + 1; i++) {
                double[] p = i <= to ? StockCameraProjection.project(route.latitude[i], route.longitude[i], car[0], car[1], z, pitch, course)
                        : StockCameraProjection.project(last[0], last[1], car[0], car[1], z, pitch, course);
                if (p == null || p[0] < policy.left || p[0] > policy.right || p[1] < policy.top || p[1] > bottom) return false;
            }
            return true;
        });
    }

    private interface Fits { boolean at(double zoom); }

    /** The largest zoom in the projection's range 14..17 that fits; 14 when none does. */
    private static float largestZoom(Fits fits) {
        if (fits.at(17)) return 17;
        if (!fits.at(14)) return 14;
        double low = 14, high = 17;
        for (int i = 0; i < 24; i++) {
            double mid = (low + high) / 2;
            if (fits.at(mid)) low = mid; else high = mid;
        }
        return (float) low;
    }

    private static float bound(float zoom, StockCameraRules.Rule rule) {
        if (!Float.isNaN(rule.lower)) zoom = Math.max(rule.lower, zoom);
        if (!Float.isNaN(rule.upper)) zoom = Math.min(rule.upper, zoom);
        return zoom;
    }

    private static float clampPitch(float pitch, float zoom) {
        return (float) Math.max(0, Math.min(StockCameraProjection.maxPitch(zoom), pitch));
    }
}
