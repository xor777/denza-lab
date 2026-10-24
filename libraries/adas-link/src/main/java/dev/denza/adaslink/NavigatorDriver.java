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
 *
 * <p>One Yandex route is one published path for its whole life, as one Amap route is one stock path:
 * a new path goes out only when Yandex's route changes (a new route ID: a new route or a reroute).
 * The link table published for it is frozen with the road graph of that moment; later graph tiles
 * and re-copies of the same route refresh only the dynamic conditions on that table, and a road
 * graph that would cut the table differently waits for the next real route. Dynamic look-ups by
 * position (the graph's legal limit and road under the car) follow the newest graph.
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
        /** Bridge source generation, increased on route replacement/stop; -1 for legacy replay. */
        public long routeEpoch=-1;
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
        /** Internal forward-only fix identity: SDK monotonic timestamp; never emitted on the wire. */
        public long stamp = Long.MIN_VALUE, fixAgeMs = Long.MAX_VALUE;
        public boolean simulated;
        // The raw platform GNSS fix (passive provider), NaN when none fresh.
        public double rawLon = Double.NaN, rawLat = Double.NaN;
        /** Vehicle speed from the car, km/h; null when unknown (GNSS speed stands in, flagged). */
        public Double vehicleSpeedKmh;
        /** Conversions of {@link #route} made ahead, off the sending thread ({@link #prepare}); null: convert here. */
        public Prepared prepared;

        /** A field-for-field copy for a hand-off to another thread. */
        public Observation copy() {
            Observation o = new Observation();
            o.monotonicMs = monotonicMs; o.mode = mode; o.routeId = routeId; o.route = route; o.routeEpoch = routeEpoch;
            o.finished = finished; o.positionOnRoute = positionOnRoute; o.scalar = scalar; o.timeToFinishS = timeToFinishS;
            o.trafficSpeedMps = trafficSpeedMps; o.speedLimitMps = speedLimitMps; o.locationClass = locationClass;
            o.lon = lon; o.lat = lat; o.speedMps = speedMps; o.heading = heading; o.stamp = stamp; o.fixAgeMs = fixAgeMs;
            o.simulated = simulated; o.rawLon = rawLon; o.rawLat = rawLat; o.vehicleSpeedKmh = vehicleSpeedKmh; o.prepared = prepared;
            return o;
        }
    }

    /** {@link #route} converted on two road graphs: the newest, and the one its published table was frozen with. */
    public static final class Prepared {
        final YandexRoute route;
        final RoadNetwork latestRoads, frozenRoads;
        final RouteConverter.Converted latest, frozen;
        final RuntimeException failure;
        final double durationMs;
        private Prepared(YandexRoute route, RoadNetwork latestRoads, RoadNetwork frozenRoads, RouteConverter.Converted latest,
                RouteConverter.Converted frozen, RuntimeException failure, double durationMs) {
            this.route = route; this.latestRoads = latestRoads; this.frozenRoads = frozenRoads; this.latest = latest;
            this.frozen = frozen; this.failure = failure; this.durationMs = durationMs;
        }
    }

    /**
     * Converts {@code o.route} ahead of {@link #observe}, on a thread that may wait (the conversion
     * and the graph match are the slow part of a route). {@code frozenRoads} is the graph the caller
     * froze for this route ID at its first sight; the driver falls back to its own conversion when
     * its inputs differ.
     */
    public static void prepare(Observation o, Coordinates.Frame frame, RoadNetwork latestRoads, RoadNetwork frozenRoads) {
        if (o.route == null) return;
        long start = System.nanoTime();
        RouteConverter.Converted latest = null, frozen = null; RuntimeException failure = null;
        try {
            latest = RouteConverter.convert(o.route, frame, latestRoads);
            frozen = frozenRoads == latestRoads ? null : RouteConverter.convert(o.route, frame, frozenRoads);
        } catch (RuntimeException error) { failure = error; }
        o.prepared = new Prepared(o.route, latestRoads, frozenRoads, latest, frozen, failure, (System.nanoTime() - start) / 1e6);
    }

    private final StockFeed feed;
    private final Coordinates.Frame frame;
    private final Scheduler clock;
    private boolean naving, sawReroute;
    private String mode = "off", currentRouteId, currentContent;
    private long lastStamp = Long.MIN_VALUE, lastObservation;
    private int lastLimit = Integer.MIN_VALUE;
    private String lastLimitSource;
    private String lastTrafficContext;
    private final Set<String> retired = new HashSet<>();
    private RouteConverter.Converted current;
    /** The newest conversion of the current route (newest graph): dynamic look-ups by position only. */
    private RouteConverter.Converted latest;
    /** The graph the published table was converted with; frozen for the path's life. */
    private RoadNetwork routeRoads;
    private long frozenTables;
    private YandexRoute currentRoute;
    private long observedEpoch=-1, acceptedRevision=-1, roadsRevision, convertedRoadsRevision=-1;
    private String currentSnapshotKey;
    private double lastRemainM = Double.MAX_VALUE;
    private boolean firstNaviInfo;
    private final Object watchdog = "navigator-watchdog";

    public NavigatorDriver(StockFeed feed, Coordinates.Frame frame, Scheduler clock) {
        this.feed = feed; this.frame = frame; this.clock = clock;
    }

    private volatile RoadNetwork roads;
    private Map<String,Object> lastConversion = Json.object();

    /** Last conversion attempt, including partial graph coverage; bridge worker only. */
    public Map<String,Object> conversionSummary() { return new java.util.LinkedHashMap<>(lastConversion); }

    /** Observed road graph to match each new route onto (cuts and attributes, {@link RoadMatcher}); null: Yandex only. */
    public void setRoads(RoadNetwork roads) { if(this.roads!=roads)roadsRevision++; this.roads = roads; }

    public void observe(Observation o) {
        if(o.routeEpoch>=0) {
            if(o.routeEpoch<observedEpoch)return;
            if(o.routeEpoch>observedEpoch) { observedEpoch=o.routeEpoch;acceptedRevision=-1; }
        }
        lastObservation = o.monotonicMs;
        clock.cancel(watchdog);
        clock.post(watchdog, WATCHDOG_MS, () -> {
            if (naving) { naving = false; current = null; latest = null; routeRoads = null; currentRouteId = null; currentContent = null; feed.onNavStop(); }
        });
        boolean active = o.routeId != null && !o.mode.equals("off") && !retired.contains(o.routeId);
        if (o.finished && naving) {
            retired.add(currentRouteId);
            naving = false;
            feed.onTrafficEvents();
            feed.onNaviArrive();
            feed.onNavStop();
            current = null; latest = null; routeRoads = null; currentRouteId = null; currentContent = null;
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
            current = null; latest = null; routeRoads = null; currentRouteId = null; currentContent = null;
        }
        if (active) {
            if (o.route != null && o.route.routeId.equals(o.routeId)
                    && (o.route.captureGeneration<0 || o.routeEpoch<0 || o.route.captureGeneration==o.routeEpoch)
                    && (!o.routeId.equals(currentRouteId) || o.route.captureRevision<0 || o.route.captureRevision>=acceptedRevision)) {
                if(o.route.captureRevision>=0)acceptedRevision=o.route.captureRevision;
                boolean sameRoute = current != null && o.routeId.equals(currentRouteId);
                boolean unchanged=sameRoute && o.route.snapshotKey!=null
                        && o.route.snapshotKey.equals(currentSnapshotKey) && roadsRevision==convertedRoadsRevision;
                Map<String,Object> provenance=o.route.conditionsProvenance();
                provenance.put("decision",unchanged?"unchanged":"conversion_pending");
                if(unchanged) {
                    // Capture provenance advances; identical values do not restart annotation timers.
                    currentRoute=o.route;
                } else {
                RouteConverter.Converted converted = convert(o, roads, false);
                if (converted != null) latest = converted;
                if (converted != null && !sameRoute) {
                    int type = !naving ? 1 : sawReroute ? 2 : 5;   // RouteTypeCommon, RouteTypeYaw, RouteTypeTMC
                    if (!naving) {
                        // The stock order: guidance starts (101, the resets), then the route data update.
                        naving = true;
                        firstNaviInfo = true;
                        lastRemainM = Double.MAX_VALUE;
                        feed.onNavStart();
                        lastLimit = Integer.MIN_VALUE; // onNavStart resets the dynamic road limit.
                    }
                    current = converted;
                    routeRoads = roads;
                    currentRoute = o.route;
                    currentContent = converted.contentKey;
                    currentRouteId = o.routeId;
                    currentSnapshotKey=o.route.snapshotKey;convertedRoadsRevision=roadsRevision;
                    sawReroute = false;
                    feed.onRouteResult(converted, type);
                    provenance.put("decision","published_new_route");
                    // Amap guides the new path from its next NaviInfo cycle, not the tick that brought it,
                    // and its traffic-event update for the new path follows that first NaviInfo.
                    lastNaviInfo = o.monotonicMs;
                    firstNaviInfo = true;
                } else if (converted != null) {
                    // The same Yandex route: its published table stays. Conditions refresh on that table only,
                    // converted with the graph the table was frozen with when the newest graph cuts it otherwise.
                    RouteConverter.Converted conditions = StockFeed.sameLinkTable(current, converted) ? converted
                            : roads == routeRoads ? null : convert(o, routeRoads, true);
                    if (conditions != null && StockFeed.sameLinkTable(current, conditions) && feed.refreshConditions(conditions)) {
                        current = conditions; currentRoute = o.route;
                        provenance.put("decision", conditions == converted ? "conditions_only" : "conditions_only_frozen_graph");
                    } else {
                        frozenTables++;
                        provenance.put("decision","table_change_frozen");
                        provenance.put("frozenTableChanges",frozenTables);
                    }
                    currentSnapshotKey=o.route.snapshotKey;convertedRoadsRevision=roadsRevision;
                }
                if(converted==null)provenance.put("decision","conversion_rejected");
                }
                feed.onConditionsSnapshot(provenance);
            }
            if (!naving && current != null && o.routeId.equals(currentRouteId)) {
                naving = true;
                feed.onNavStart();
                lastLimit = Integer.MIN_VALUE;
            }
            // A yaw reroute leaves guidance; Navigator's NOT_ON_ROUTE before its first match is not one.
            if (naving && o.mode.equals("rerouting") && mode.equals("guidance")) {
                sawReroute = true;
                feed.onReroute(2);
            }
        }
        mode = active ? o.mode : "off";
        // The legal limit of the road here: Navigator's posted limit, else the route's own per-edge limit.
        int limit = active ? legalKmh(o.speedLimitMps) : 0;
        String limitSource = limit > 0 ? "yandex_guidance" : "unknown";
        boolean onCurrent = naving && currentRoute != null && o.routeId != null && o.routeId.equals(currentRouteId)
                && o.positionOnRoute && Double.isFinite(o.scalar) && o.scalar >= 0 && o.scalar <= currentRoute.edges();
        if (limit == 0 && onCurrent && currentRoute.speedLimits != null) {
            int index = Math.min((int) Math.floor(o.scalar), currentRoute.edges() - 1);
            Double edge = index < currentRoute.speedLimits.length ? currentRoute.speedLimits[index] : null;
            limit = edge == null ? 0 : legalKmh(edge);
            if (limit > 0) limitSource = "yandex_route_edge";
        }
        // Where Yandex names no limit, the matched graph road's speed-limit sign in the direction travelled
        // (the newest graph: a look-up by position, independent of the frozen link table).
        RouteConverter.Converted lookup = latest != null && current != null && latest.routeId.equals(current.routeId) ? latest : current;
        if (limit == 0 && onCurrent && lookup != null && lookup.roadMatch != null) {
            int graphLimit = lookup.roadMatch.legalKmhAt(o.scalar);
            if (graphLimit > 0) { limit = graphLimit; limitSource = "matched_graph"; }
        }
        if (limit != lastLimit || !limitSource.equals(lastLimitSource)) {
            lastLimit = limit; lastLimitSource = limitSource;
            feed.onCurrentRoadSpeed(limit, limitSource);
        }
        Map<String,Object> trafficContext;
        if(currentRoute!=null && currentRoute.trafficContext!=null)
            trafficContext=currentRoute.trafficContext.at(currentRouteId,o.scalar,onCurrent);
        else {
            trafficContext=Json.object();trafficContext.put("routeId",o.routeId);
            trafficContext.put("source","yandex_route_snapshot.jamSegments");trafficContext.put("snapshotContext",true);
            trafficContext.put("sourceAgeKnown",false);
            trafficContext.put("speedKnown",false);trafficContext.put("rawSpeed",null);trafficContext.put("rawJamType",null);
            trafficContext.put("speedStatus","unavailable");trafficContext.put("jamTypeStatus","unavailable");
            trafficContext.put("unavailableReason","missing_route_traffic_context");
            trafficContext.put("wireDecision",YandexTrafficContext.WIRE_DECISION);
        }
        String trafficKey=Json.write(trafficContext);
        if(!trafficKey.equals(lastTrafficContext)) {
            lastTrafficContext=trafficKey;feed.onTrafficContext(trafficContext);
        }

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
        // The guided position of this very fix; off the route (rerouting) the stock's indices stay frozen.
        if (onCurrent && o.mode.equals("guidance")) fix.scalar = o.scalar;
        // Amap's LocMatchInfo: on the route, the graph road the route matched under the car, if any.
        RoadNetwork.Fragment road = onCurrent && lookup != null && lookup.roadMatch != null ? lookup.roadMatch.fragmentAt(o.scalar) : null;
        if (road != null) { fix.roadClass = road.roadClass; fix.ownership = road.ownership; }
        feed.onLocation(fix);
    }

    public boolean naving() { return naving; }

    /**
     * {@code o.route} on {@code with}: the prepared conversion when it was made for these exact inputs,
     * else here. {@code frozenGraph}: the conversion on the graph the published table was frozen with.
     */
    private RouteConverter.Converted convert(Observation o, RoadNetwork with, boolean frozenGraph) {
        Prepared p = o.prepared;
        long start = System.nanoTime();
        RouteConverter.Converted converted = null; RuntimeException failure = null; boolean ahead = false;
        if (p != null && p.route == o.route && p.failure == null) {
            if (!frozenGraph && p.latestRoads == with) { converted = p.latest; ahead = true; }
            else if (frozenGraph && p.frozenRoads == with) { converted = p.frozen != null ? p.frozen : p.latestRoads == with ? p.latest : null; ahead = converted != null; }
        }
        if (!ahead) {
            try { converted = RouteConverter.convert(o.route, frame, with); }
            catch (RuntimeException e) { failure = e; }
        }
        if (!frozenGraph) {
            lastConversion = Json.object();
            lastConversion.put("routeId",o.routeId);
            if (converted != null) {
                lastConversion.put("routeMetres",converted.totalM);
                lastConversion.put("links",converted.linkSegment.length);
                lastConversion.put("graphMatchedMetres",converted.roadMatch==null?0:converted.roadMatch.matchedM);
                lastConversion.put("graphCuts",converted.roadMatch==null?0:converted.roadMatch.cuts.length);
                lastConversion.put("graphMatchedSamples",converted.roadMatch==null?0:converted.roadMatch.matchedSamples);
            } else lastConversion.put("error",String.valueOf(failure != null ? failure : p != null ? p.failure : null));
            lastConversion.put("durationMs",ahead ? p.durationMs : (System.nanoTime()-start)/1000000.0);
            lastConversion.put("preparedAhead",ahead);
        }
        return converted;
    }

    /** Zero is the wire's unknown/clear value, never an observed zero-speed legal limit. */
    private static int legalKmh(double mps) {
        double kmh = mps * 3.6;
        if (!Double.isFinite(kmh) || kmh <= 0 || kmh > Integer.MAX_VALUE) return 0;
        return RouteConverter.round(kmh);
    }

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
     * (elapsedRealtime clock). Old samples carry no location class: their {@code freshLocation} stands for FINE.
     * Untagged historical replays use only recorded ages plus an absolute-age veto; SDK relative
     * timestamps are never subtracted from capturedUptimeMs (that is a different clock).
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
            long absolute = timestamp(loc.get("absoluteTimestamp")), relative = timestamp(loc.get("relativeTimestamp"));
            long captured = timestamp(s.get("capturedUptimeMs")), wall = timestamp(s.get("capturedAtMs"));
            boolean declared = s.containsKey("relativeTimestampClock") || s.containsKey("capturedSdkUptimeMs");
            o.stamp = declared ? relative : absolute; // Legacy absolute identity is replay-only.
            if (o.stamp < 0) o.stamp = Long.MIN_VALUE;
            long recordedAge = timestamp(s.get("lastFineFixAgeMs"));
            if (!declared && recordedAge < 0) recordedAge = timestamp(s.get("fixAgeMs"));
            if (captured >= 0 && nowMs >= captured && wall >= 0 && absolute >= 0 && wall >= absolute && recordedAge >= 0) {
                long delay = nowMs - captured;
                if (recordedAge < Long.MAX_VALUE - delay && wall < Long.MAX_VALUE - delay) {
                    long age = Math.max(recordedAge + delay, wall - absolute + delay);
                    if (declared) {
                        long sdkNow = timestamp(s.get("capturedSdkUptimeMs"));
                        if (!FixFreshness.RELATIVE_CLOCK.equals(s.get("relativeTimestampClock")) || sdkNow < 0 || relative < 0)
                            age = FixFreshness.UNKNOWN;
                        else age = Math.max(age, FixFreshness.capture(relative, absolute, sdkNow, captured, wall)
                                .ageMs(nowMs, wall + delay));
                    }
                    o.fixAgeMs = age;
                }
            }
        }
        o.finished = "ROUTE_FINISHED".equals(s.get("routeStatus"));
        o.simulated = Boolean.TRUE.equals(s.get("simulated"));
        return o;
    }

    private static long timestamp(Object value) {
        if (!(value instanceof Number)) return -1;
        Number n = (Number) value;
        long integer = n.longValue();
        return Double.isFinite(n.doubleValue()) && integer >= 0 && n.doubleValue() == integer ? integer : -1;
    }
}
