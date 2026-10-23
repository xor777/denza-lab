package dev.denza.adaslink;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Navigator's state, observed every module tick, as the Amap events the stock feed listens to:
 * guidance start and stop, arrival, route results, yaw reroute, NaviInfo and LocInfo. Navigation
 * is Navigator's own guidance state, not the freshness of a fix: a lost fix, a stale or duplicate
 * read, or Navigator rerouting never ends navigation (DYN-02, DYN-04); the position simply stays
 * where it was, as the stock's does off route. A fix is new only when its timestamp moves forward,
 * its class is FINE and it is at most {@link #MAX_FIX_AGE_MS} old (CAP-1, DYN-07, DYN-10). A
 * finished route is retired for the process (DYN-13). Single-threaded, on the feed's scheduler.
 */
public final class NavigatorDriver {
    /** A fix older than one fix interval is not a new fix: the stock sends each fix as it comes. */
    public static final long MAX_FIX_AGE_MS = 1000;
    /** A route that disappears this close to its end has been finished. */
    public static final double ARRIVAL_M = 50;
    /** Without any observation this long the module is gone: navigation ends as on a stop. */
    public static final long WATCHDOG_MS = 10000;

    /** One look at Navigator; immutable, built on the main thread. */
    public static final class Observation {
        public long monotonicMs;
        /** off, waiting, guidance, lost, rerouting (NavigatorSource modes). */
        public String mode = "off";
        public String routeId;
        /** A fresh copy of the route, only when the module re-read it; null otherwise. */
        public YandexRoute route;
        /** Navigator's route status is ROUTE_FINISHED. */
        public boolean finished;
        /** The route position belongs to {@link #routeId}. */
        public boolean positionOnRoute;
        public double scalar = Double.NaN;
        public double timeToFinishS = Double.NaN, trafficSpeedMps = Double.NaN;
        /** Posted limit, m/s; NaN when none. */
        public double speedLimitMps = Double.NaN;
        // The classified location.
        public String locationClass;
        public double lon = Double.NaN, lat = Double.NaN, speedMps = Double.NaN, heading = Double.NaN;
        public long stamp = Long.MIN_VALUE, fixAgeMs = Long.MAX_VALUE;
        public boolean simulated;
        // The raw platform GNSS fix (passive provider), NaN when none fresh.
        public double rawLon = Double.NaN, rawLat = Double.NaN;
        /** Vehicle speed from the car, km/h; null when unknown (GNSS speed stands in, flagged). */
        public Double vehicleSpeedKmh;
    }

    private final StockFeed feed;
    private final Coordinates.Frame frame;
    private final Scheduler clock;
    private boolean naving, sawReroute;
    private String mode = "off", currentRouteId, currentContent;
    private long lastStamp = Long.MIN_VALUE, lastObservation;
    private int lastLimit = Integer.MIN_VALUE;
    private final Set<String> retired = new HashSet<>();
    private RouteConverter.Converted current;
    private YandexRoute currentRoute;
    private double lastRemainM = Double.MAX_VALUE;
    private boolean firstNaviInfo;
    private final Object watchdog = "navigator-watchdog";

    public NavigatorDriver(StockFeed feed, Coordinates.Frame frame, Scheduler clock) {
        this.feed = feed; this.frame = frame; this.clock = clock;
    }

    private volatile RoadNetwork roads;

    /** Our OSM roads to match each new route onto (cuts and attributes, {@link RoadMatcher}); null: Yandex only. */
    public void setRoads(RoadNetwork roads) { this.roads = roads; }

    public void observe(Observation o) {
        lastObservation = o.monotonicMs;
        clock.cancel(watchdog);
        clock.post(watchdog, WATCHDOG_MS, () -> {
            if (naving) { naving = false; current = null; currentRouteId = null; currentContent = null; feed.onNavStop(); }
        });
        boolean active = o.routeId != null && !o.mode.equals("off") && !retired.contains(o.routeId);
        if (o.finished && naving) {
            retired.add(currentRouteId);
            naving = false;
            feed.onTrafficEvents();
            feed.onNaviArrive();
            feed.onNavStop();
            current = null; currentRouteId = null; currentContent = null;
            active = false;
        }
        if (!active && naving) {
            // Navigator drops a finished route at once (the emulator run never showed ROUTE_FINISHED):
            // a route that goes away within ARRIVAL_M of its end has arrived, as Amap's onNaviArrive.
            naving = false;
            if (lastRemainM <= ARRIVAL_M) {
                retired.add(currentRouteId);
                feed.onTrafficEvents();
                feed.onNaviArrive();
            }
            feed.onNavStop();
            current = null; currentRouteId = null; currentContent = null;
        }
        if (active) {
            if (o.route != null && o.route.routeId.equals(o.routeId)) {
                RouteConverter.Converted converted;
                try { converted = RouteConverter.convert(o.route, frame, roads); }
                catch (RuntimeException e) { converted = null; }
                if (converted != null && (!converted.contentKey.equals(currentContent) || !o.routeId.equals(currentRouteId))) {
                    int type = !naving ? 1 : sawReroute ? 2 : 5;   // RouteTypeCommon, RouteTypeYaw, RouteTypeTMC
                    if (!naving) {
                        // The stock order: guidance starts (101, the resets), then the route data update.
                        naving = true;
                        firstNaviInfo = true;
                        lastRemainM = Double.MAX_VALUE;
                        feed.onNavStart();
                    }
                    current = converted;
                    currentRoute = o.route;
                    currentContent = converted.contentKey;
                    currentRouteId = o.routeId;
                    sawReroute = false;
                    feed.onRouteResult(converted, type);
                    // Amap guides the new path from its next NaviInfo cycle, not the tick that brought it,
                    // and its traffic-event update for the new path follows that first NaviInfo.
                    lastNaviInfo = o.monotonicMs;
                    firstNaviInfo = true;
                }
            }
            if (!naving && current != null && o.routeId.equals(currentRouteId)) {
                naving = true;
                feed.onNavStart();
            }
            // A yaw reroute leaves guidance; Navigator's NOT_ON_ROUTE before its first match is not one.
            if (naving && o.mode.equals("rerouting") && mode.equals("guidance")) {
                sawReroute = true;
                feed.onReroute(2);
            }
        }
        mode = active ? o.mode : "off";
        // The legal limit of the road here: Navigator's posted limit, else the route's own per-edge limit.
        double limitMps = o.speedLimitMps;
        boolean onCurrent = naving && currentRoute != null && o.routeId != null && o.routeId.equals(currentRouteId)
                && o.positionOnRoute && !Double.isNaN(o.scalar);
        if (Double.isNaN(limitMps) && onCurrent && currentRoute.speedLimits != null) {
            Double edge = currentRoute.speedLimits[Math.min((int) Math.floor(o.scalar), currentRoute.edges() - 1)];
            if (edge != null) limitMps = edge;
        }
        // Where Yandex names no limit, the matched OSM road's speed-limit sign in the direction travelled.
        if ((Double.isNaN(limitMps) || limitMps <= 0) && onCurrent && current != null && current.roadMatch != null) {
            int osm = current.roadMatch.legalKmhAt(o.scalar);
            if (osm > 0) limitMps = osm / 3.6;
        }
        int limit = Double.isNaN(limitMps) || limitMps <= 0 ? 0 : RouteConverter.round(limitMps * 3.6);
        if (limit != lastLimit) { lastLimit = limit; feed.onCurrentRoadSpeed(limit); }

        boolean fine = "FINE".equals(o.locationClass) && !Double.isNaN(o.lon) && o.stamp != Long.MIN_VALUE;
        if (!fine || o.stamp <= lastStamp || o.fixAgeMs < 0 || o.fixAgeMs > MAX_FIX_AGE_MS) return;
        lastStamp = o.stamp;
        if (naving && current != null && o.mode.equals("guidance") && o.positionOnRoute && o.routeId.equals(currentRouteId)
                && !Double.isNaN(o.scalar))
            if (naviInfoDue(o.monotonicMs)) {
                feed.onProgress(new StockFeed.Progress(o.scalar, o.timeToFinishS, o.trafficSpeedMps));
                lastRemainM = current.totalM - current.metres(o.scalar);
                // Amap's first traffic-event update follows the guidance start by about a second.
                if (firstNaviInfo) { firstNaviInfo = false; feed.onTrafficEvents(); }
            }
        // Amap's matched course is the road's: on the route, the bearing of the polyline edge under the car.
        double course = onCurrent ? RouteConverter.bearing(currentRoute, o.scalar) : o.heading;
        StockFeed.Fix fix = new StockFeed.Fix(o.lon, o.lat, o.rawLon, o.rawLat, Double.isNaN(o.speedMps) ? 0 : o.speedMps, course,
                o.simulated, o.fixAgeMs, o.vehicleSpeedKmh);
        // Amap's LocMatchInfo: on the route, the OSM road the route matched under the car, if any.
        RoadNetwork.Fragment road = onCurrent && current != null && current.roadMatch != null ? current.roadMatch.fragmentAt(o.scalar) : null;
        if (road != null) { fix.roadClass = road.roadClass; fix.ownership = road.ownership; }
        feed.onLocation(fix);
    }

    public boolean naving() { return naving; }

    private long lastNaviInfo = Long.MIN_VALUE / 4;

    /** Amap's guidance reports NaviInfo once a second, whatever the location rate (as k.h.a0.d.c.d() paces fixes). */
    private boolean naviInfoDue(long now) {
        long elapsed = (now - lastNaviInfo) - 1000;
        if (elapsed < 0) return false;
        lastNaviInfo = elapsed < 50 ? now - elapsed : now;
        return true;
    }

    /**
     * A recorded {@code denza-yandex-guidance-v1} sample as an observation at {@code nowMs}
     * (uptime clock). Old samples carry no location class: their {@code freshLocation} stands for FINE.
     */
    @SuppressWarnings("unchecked")
    public static Observation fromSample(Map<String, Object> s, long nowMs, YandexRoute route) {
        Observation o = new Observation();
        o.monotonicMs = nowMs;
        o.mode = String.valueOf(s.get("mode"));
        o.routeId = (String) s.get("routeId");
        o.route = route;
        o.positionOnRoute = Boolean.TRUE.equals(s.get("positionMatchesRoute"));
        Object p = s.get("sourcePolylinePosition");
        if (p instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) p;
            o.scalar = ((Number) m.get("segmentIndex")).doubleValue() + ((Number) m.get("segmentPosition")).doubleValue();
        }
        if (s.get("timeToFinishS") instanceof Number) o.timeToFinishS = ((Number) s.get("timeToFinishS")).doubleValue();
        if (s.get("speedLimitMps") instanceof Number) o.speedLimitMps = ((Number) s.get("speedLimitMps")).doubleValue();
        Object cls = s.get("locationClass");
        o.locationClass = cls != null ? String.valueOf(cls) : Boolean.TRUE.equals(s.get("freshLocation")) ? "FINE" : "UNKNOWN";
        Map<String, Object> loc = (Map<String, Object>) s.get("location");
        if (loc != null && loc.get("longitude") instanceof Number) {
            o.lon = ((Number) loc.get("longitude")).doubleValue();
            o.lat = ((Number) loc.get("latitude")).doubleValue();
            if (loc.get("speed") instanceof Number) o.speedMps = ((Number) loc.get("speed")).doubleValue();
            if (loc.get("heading") instanceof Number) o.heading = ((Number) loc.get("heading")).doubleValue();
            if (loc.get("absoluteTimestamp") instanceof Number) o.stamp = ((Number) loc.get("absoluteTimestamp")).longValue();
            Object rel = loc.get("relativeTimestamp"), captured = s.get("capturedUptimeMs");
            if (rel instanceof Number && captured instanceof Number)
                o.fixAgeMs = nowMs - ((Number) rel).longValue();
            if (s.get("lastFineFixAgeMs") instanceof Number && captured instanceof Number)
                o.fixAgeMs = ((Number) s.get("lastFineFixAgeMs")).longValue() + (nowMs - ((Number) captured).longValue());
        }
        o.finished = "ROUTE_FINISHED".equals(s.get("routeStatus"));
        o.simulated = Boolean.TRUE.equals(s.get("simulated"));
        return o;
    }
}
