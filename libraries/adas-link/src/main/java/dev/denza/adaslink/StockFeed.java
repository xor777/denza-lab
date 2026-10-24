package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * The stock map's whole ADAS feed as one state machine, driven by Navigator's events instead of
 * Amap's. Each method below names the stock code it mirrors (BydLaunchermap, ADAS type 1):
 * <ul>
 * <li>{@code DriveRouteManager} ({@code k.h.e0.c.g}): route result, path changes, the
 *   MMTPathSelected answer and the resend on a foreign or failed answer, the DNP switch resend;</li>
 * <li>{@code MomentaUtil} / {@code SDRouteUtil} / {@code SendSomeIpDataManager} ({@code k}, {@code n},
 *   {@code e0}): serialization, chunking, route and selection events;</li>
 * <li>{@code MmtSomeIpDataManager} ({@code k.h.a0.c.c.a.e0}): location bundle (position, TrafficInfo,
 *   tunnel, incidents, light), the 1 s stationary loop, the NaviInfo bundle (tidal lane, action and
 *   camera, speed/intersection), the 1 s NavigationStatus loop with 110 after arrival, resets;</li>
 * <li>{@code SomeIPMatrixManager} ({@code f0}): the no-sleep gate on every dynamic send;</li>
 * <li>{@code DnpOddDistanceController} ({@code f}): noODDRegionDist.</li>
 * </ul>
 * Single-threaded: every call must be made on the {@link Scheduler}'s thread.
 */
public final class StockFeed implements BridgeStatus, AutoCloseable {
    /**
     * Explicit integration boundary: the wrapper must enforce genuine gate/transport/FINE leases,
     * transaction completeness and real ACK barriers at every fire. A raw LIVE publisher is rejected.
     */
    public interface GuardedLivePublisher extends Publisher {
        void routeChanged(long pathId);
        void beginRoute(long pathId, int chunkCount);
        void navigationStopped();
        /** Guidance ended; {@code arrived} when Amap's onNaviArrive preceded it (the stock then says 110 first). */
        default void navigationStopped(boolean arrived) { navigationStopped(); }
        /** Every prerequisite held at the last check: a failed route attempt may be retried now. */
        default boolean admitting() { return true; }
    }
    public static final class Config {
        public Coordinates.Frame frame = Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE;
        public StockRouteJson.Strings strings = StockRouteJson.Strings.EN;
        /**
         * TrafficInfo.SpdLmtSpeedValue (the stock code's onCurrentRoadSpeed) from Yandex's posted limit,
         * current route edge or matched graph road. The default false preserves the historical
         * factory oracle's zero-valued dynamic field; Navigator wrappers explicitly enable it.
         * Unknown limits clear this field to 0. This does not affect StockRouteJson's separate
         * static speedLimit=0 literal.
         */
        public boolean roadSpeedLimit = false;
        /** Yandex road-facility projection; legacy factory-only replay keeps its historical event set. */
        public boolean roadFacilities = false;
        /** Stock skips simulated locations ({@code locInfo.isSimulate == 1}); true only for emulator traces. */
        public boolean acceptSimulatedLocations;
        /** Emulated MMTPathSelected after this delay (dry-run only; the car's took ~110 ms); negative: none. */
        public long emulatedAckDelayMs = -1;
        /** ADAS type ({@code getAdasType}); 1 on this car. */
        public int adasType = 1;
        /** LIVE only: a fresh persistent wire ID for every attempt, including identical route tables. */
        public LongSupplier livePathIds;
    }

    /** One location fix: Yandex's FINE location (route-matched), the raw GNSS fix when there is one. */
    public static final class Fix {
        public final double matchedLon, matchedLat, rawLon, rawLat, speedMps, heading;
        public final boolean simulated;
        public final long ageMs;
        public final Double vehicleSpeedKmh;
        /** Amap LocMatchInfo of the road under the car: its class and ownership; the stock's NULL values when unmatched. */
        public int roadClass = FieldDefaults.ROAD_CLASS_UNMATCHED, ownership = FieldDefaults.OWNERSHIP_UNMATCHED;
        public Fix(double matchedLon, double matchedLat, double rawLon, double rawLat, double speedMps, double heading, boolean simulated,
                   long ageMs, Double vehicleSpeedKmh) {
            this.matchedLon = matchedLon; this.matchedLat = matchedLat; this.rawLon = rawLon; this.rawLat = rawLat; this.speedMps = speedMps;
            this.heading = heading; this.simulated = simulated; this.ageMs = ageMs; this.vehicleSpeedKmh = vehicleSpeedKmh;
        }
    }

    /** Guidance progress on the current route (Amap NaviInfo's inputs). */
    public static final class Progress {
        public final double scalar, timeToFinishS, trafficSpeedMps;
        public Progress(double scalar, double timeToFinishS, double trafficSpeedMps) {
            this.scalar = scalar; this.timeToFinishS = timeToFinishS; this.trafficSpeedMps = trafficSpeedMps;
        }
    }

    // Tokens of the stock handlers.
    private final Object tokenSeg = "MSG_TOKEN_SEG", tokenStatus = "status-loop", tokenOdd = "odd-loop",
            tokenNoaSwitch = "sendPathByNoaSwitch", tokenLanes = "MSG_TOKEN_QUERY_LANES", tokenAck = "emulated-ack";

    private final Scheduler clock;
    private final Publisher publisher;
    private final Config config;
    private final boolean live;
    private final LongSupplier livePathIds;
    private final Set<Long> liveUsedIds = new HashSet<>();
    private boolean closed, started;
    private long sessionEpoch, routeGeneration;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Snapshot snapshot = Snapshot.DISABLED;

    // Gates: NoaStateManager (BYDAuto) flags.
    private boolean dnpService, dnpSwitchRaw, noSleep;
    private boolean dnpSwitch;            // a: switch && noSleep
    private boolean sleeping;             // f0.f13251b

    // NaviController.isNaving and GaoDeSignalManager's route.
    private boolean naving;
    private Route route;                  // f13989e: the route result being guided
    private long curPathId = -1, selectPathId = -1; // DriveRouteManager f13986b / f13987c
    private long requestId = -1;          // k.f14252d (only the multi-path send sets it)
    private final Map<String, Long> pathIds = new HashMap<>();
    private long nextPathId = 100001;
    private final Map<Long, Double> lastPassed = new HashMap<>();

    // MmtSomeIpDataManager parameter objects.
    private long niPathId = -1; private int niSegIdx = -1, niLinkIdx = -1, niSegDist = -1, niLinkRemain = -1, niOwnership = -1;
    private int jamLength, distToJam = -1, jamStatus, passTime, construction, roadSpeed, eyeSpeed, eyeDist, iSpeed, distToDest, timeToDest;
    private double cLon, cLat, iStartLon, iStartLat, iEndLon, iEndLat;
    private boolean offCourse;
    private int roadClass = -1;
    private int lightExist, lightState, lightDir, lightWaitNum, lightDistance; private double lightLon, lightLat; private long lightStart, lightEnd;
    private int tunnelDist, tunnelState; private double tStartLon, tStartLat, tEndLon, tEndLat;
    private int regionId = FieldDefaults.REGION_ID, provinceCode = FieldDefaults.PROVINCE_CODE, cityCode = FieldDefaults.CITY_CODE,
            weather = FieldDefaults.WEATHER; private long countryCode = FieldDefaults.COUNTRY_CODE;
    private int flowSpeed, historySpeed, hasParallel, hasMixFork, hasSolid, solidDistance; private String voice = "";
    private int icon, actMain, actAssist, actDistance = -1, cameraType, cameraDistance = -1;
    private int giStart, giEnd, giOdd;
    private int laneSeg = -1, laneLink = -1, tidal, laneDist;
    private double lanePassedM;
    private long laneScheduleGeneration = -1, laneGeneration;
    private LaneGuidance lastLaneDiagnostics;
    private LaneGuidance.Placed shownLane, rejectedLane;
    private String shownLaneKey;
    private long shownLaneTimestamp;
    private boolean inTunnel;              // v2: matched link is a tunnel
    private int mapState = 100;
    private boolean statusLoopCreated;
    private short arriveCounter;
    private long lastLocationSend = Long.MIN_VALUE / 4, locationThrottle = 1000;
    private int oddLast;                    // DnpOddDistanceController.f13983c
    private int lastVehicleSpeed = Integer.MIN_VALUE;
    private int enteredTunnel = -1;
    private boolean cameraShown;
    private double shownCamera = Double.NaN;
    private byte[] lastFacilitiesPayload;
    private long lastFacilitiesPath=-1, lastFacilitiesAttempt=Long.MIN_VALUE/4;
    private RoadFacilities.Source lastFacilitiesSource;
    private boolean facilitiesResetPending;
    private boolean facilitiesProgressValid;
    private long facilitiesProgressEpoch;
    private volatile long lastFire = -1;
    private boolean arrivePending;
    private long lastPublishAttempt = Long.MIN_VALUE / 4;
    private long routeRetries;

    /** A converted route with its pathId, the Amap RouteType it came with, and its own request. */
    public static final class Route {
        /** Immutable table used by route chunks/resends for this wire path ID. */
        public final RouteConverter.Converted converted;
        /** Latest compatible conditions; never changes the published table or ACK identity. */
        public RouteConverter.Converted conditions;
        public final int amapRouteType;
        public final long requestId;
        Route(RouteConverter.Converted converted, int amapRouteType, long requestId) {
            this.converted = converted; this.conditions=converted; this.amapRouteType = amapRouteType; this.requestId = requestId;
        }
        public long pathId() { return converted.path.pathId; }
    }

    public StockFeed(Scheduler clock, Publisher publisher, Config config) {
        if (clock == null || publisher == null || config == null) throw new IllegalArgumentException("Feed dependencies");
        live = publisher.mode() == Mode.LIVE;
        if (live && (!(publisher instanceof GuardedLivePublisher) || config.emulatedAckDelayMs >= 0 || config.livePathIds == null))
            throw new IllegalArgumentException("LIVE requires guarded publisher, fresh path IDs and no emulated ACK");
        this.publisher = publisher; this.config = config; this.livePathIds = config.livePathIds;
        // Named tokens cancel normally; epoch guards also invalidate every anonymous queued task.
        this.clock = new Scheduler() {
            @Override public long now() { return clock.now(); }
            @Override public void post(Object token, long delayMs, Runnable task) {
                if (closed) return;
                final long epoch = sessionEpoch;
                clock.post(token, delayMs, () -> { if (!closed && sessionEpoch == epoch) task.run(); });
            }
            @Override public void cancel(Object token) { clock.cancel(token); }
        };
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Process start: NoaCanSomeIpLink starts the four IVI services, GaoDeSignalManager's constructor
     * sets map state 100, which starts the 1 s NavigationStatus loop for good.
     */
    public void start(boolean dnpService, boolean dnpSwitch, boolean noSleep) {
        if (closed || (live && started)) throw new IllegalStateException("Feed session closed/already started");
        started = true;
        Map<String, Object> h = Json.object();
        h.put("schema", DryRunRecorder.SCHEMA);
        h.put("frame", config.frame.name());
        h.put("locale", config.strings.name());
        h.put("roadSpeedLimit", config.roadSpeedLimit);
        h.put("acceptSimulatedLocations", config.acceptSimulatedLocations);
        h.put("emulatedAckDelayMs", config.emulatedAckDelayMs);
        h.put("adasType", (long) config.adasType);
        h.put("publisher", publisher.mode().name());
        FieldDefaults.describe(h);
        publisher.note("header", h, clock.now());
        for (long service : Topics.SERVICES) publisher.startService(service);
        this.dnpService = dnpService;
        this.dnpSwitchRaw = dnpSwitch;
        this.noSleep = noSleep;
        this.sleeping = !noSleep;
        this.dnpSwitch = dnpSwitch && noSleep;
        setMapState(100);
        publishStatus();
    }

    /** NoaStateManager: DNP service availability, DNP switch, NOA no-sleep (from BYDAuto on the car). */
    public void setGates(boolean service, boolean switchOn, boolean noSleep) {
        boolean wasOpen = gate();
        Map<String, Object> f = Json.object();
        f.put("dnpService", service); f.put("dnpSwitch", switchOn); f.put("noSleep", noSleep);
        publisher.note("gates", f, clock.now());
        this.dnpService = service;
        this.dnpSwitchRaw = switchOn;
        this.noSleep = noSleep;
        sleeping = !noSleep;                                   // f0.t(zG)
        boolean a = switchOn && noSleep;
        if (live && wasOpen && !(service && switchOn && noSleep)) {
            sessionEpoch++;
            routeGeneration++; curPathId = -1; selectPathId = -1; niPathId = -1;
            ((GuardedLivePublisher) publisher).navigationStopped();
            setMapState(mapState); // New epoch keeps only the status cadence; guarded egress is shut.
        }
        if (a != dnpSwitch) {
            dnpSwitch = a;
            onDnpSwitchState(a);
        }
    }

    /** GaoDeSignalManager.onNavStart: map state 101, observers' d() = z(). */
    public void onNavStart() {
        if (live) sessionEpoch++;
        publisher.note("navStart", Json.object(), clock.now());
        naving = true;
        setMapState(101);
        clock.post(null, 0, this::resetAll);
        publishStatus();
    }

    /** GaoDeSignalManager.onNaviArrive: MmtSomeIpDataManager.R() arms three 110s. */
    public void onNaviArrive() {
        publisher.note("naviArrive", Json.object(), clock.now());
        // LIVE: the stop that follows opens a new epoch, which would drop a queued setter; set it now.
        if (live) { arriveCounter = 1; arrivePending = true; }
        else clock.post(null, 0, () -> arriveCounter = 1);
    }

    /** GaoDeSignalManager.onNavStop: map state 100, observers onNavStop (z(), DriveRouteManager reset). */
    public void onNavStop() {
        if (live) {
            sessionEpoch++;
            boolean arrived = arrivePending; arrivePending = false;
            ((GuardedLivePublisher) publisher).navigationStopped(arrived);
        }
        publisher.note("navStop", Json.object(), clock.now());
        naving = false;
        setMapState(100);
        clock.post(null, 0, this::resetAll);
        clock.cancel(tokenLanes);
        // DriveRouteManager.onNavStop
        curPathId = -1;
        selectPathId = -1;
        route = null;
        routeGeneration++;
        oddReset();
        clock.cancel(tokenNoaSwitch);
        requestId = -1;
        publishStatus();
    }

    /** LIVE session end: guidance ends as on a stop, so the status loop sends the stock's end sequence. */
    public void endGuidance() {
        if (closed) return;
        if (naving) onNavStop();
    }

    /**
     * DriveRouteManager.onDnpSwitchState(true): the stock resends its focus path 300 ms after the DNP
     * switch comes back on. LIVE sends it as a fresh attempt (a new wire ID); the dry run has its own
     * gate path in {@link #setGates}.
     */
    public void onDnpSwitchOn() {
        if (!live || closed || route == null || !naving) return;
        final Route expected = route;
        clock.cancel(tokenNoaSwitch);
        clock.post(tokenNoaSwitch, 300, () -> {
            if (route != expected || !naving) return;
            Map<String, Object> f = Json.object(); f.put("pathId", expected.pathId()); f.put("reason", "dnpSwitch on");
            publisher.note("routeResend", f, clock.now());
            onRouteResult(expected.converted, expected.amapRouteType);
        });
    }

    /** GaoDeSignalManager.onReroute: type 2 (yaw) sets map state 105; MmtSomeIpDataManager.b0(). */
    public void onReroute(int amapRouteType) {
        facilitiesProgressValid=false;facilitiesProgressEpoch++;
        Map<String, Object> f = Json.object();
        f.put("routeType", (long) amapRouteType);
        publisher.note("reroute", f, clock.now());
        if (amapRouteType == 2) setMapState(105);
        clock.post(null, 0, () -> {
            resetJam(); resetConstruction(); resetLightLocation(); resetIntersectionInfo();
            offCourse = amapRouteType == 2;
            if (amapRouteType == 2) { clearTunnel(); sendTunnel(); }
        });
    }

    /**
     * GaoDeSignalManager.setRouteCarResultData: a route result (Navigator's new or replaced route).
     * The pathId is this process's next Amap-style id (100002, ...) unless the same link table was
     * already published, which keeps its id: one id, one table (DYN-03, F1).
     */
    public Route onRouteResult(RouteConverter.Converted converted, int amapRouteType) {
        if (closed) throw new IllegalStateException("Feed closed");
        Long id;
        try { id = live ? Long.valueOf(livePathIds.getAsLong()) : pathIds.get(converted.contentKey); }
        catch (RuntimeException failure) {
            if (live) {
                routeGeneration++; route = null; curPathId = -1; selectPathId = -1; niPathId = -1;
                ((GuardedLivePublisher) publisher).navigationStopped(); publishStatus();
            }
            throw failure;
        }
        if (live) {
            if (id <= 0 || id > 9_007_199_254_740_991L || liveUsedIds.size() >= 4096 || !liveUsedIds.add(id)) {
                routeGeneration++; route = null; curPathId = -1; selectPathId = -1; niPathId = -1;
                ((GuardedLivePublisher) publisher).navigationStopped();
                publishStatus();
                throw new IllegalStateException("LIVE path ID must be fresh, positive and exactly representable");
            }
            curPathId = -1; selectPathId = -1; niPathId = -1;
        } else if (id == null) {
            id = ++nextPathId;
            pathIds.put(converted.contentKey, id);
        }
        converted.path.pathId = id;
        Route result = new Route(converted, amapRouteType, id);
        Map<String, Object> f = Json.object();
        f.put("routeId", converted.routeId);
        f.put("pathId", id);
        f.put("amapRouteType", (long) amapRouteType);
        f.put("links", (long) converted.path.linkCount());
        f.put("segments", (long) converted.path.segments.size());
        publisher.note("routeResult", f, clock.now());
        this.route = result;
        lastPublishAttempt = clock.now();
        facilitiesResetPending=true;facilitiesProgressValid=false;lastFacilitiesAttempt=Long.MIN_VALUE/4;
        final long generation = ++routeGeneration;
        if (live) ((GuardedLivePublisher) publisher).routeChanged(id);
        if (gate()) clock.post(null, 0, () -> onRouteDataUpdate(result, generation));
        publishStatus();
        return result;
    }

    /** The actual link table/geometry is fixed; section travel times are conditions, not identity. */
    public static boolean sameLinkTable(RouteConverter.Converted a,RouteConverter.Converted b) {
        return a!=null && b!=null && a.routeId.equals(b.routeId) && tableKey(a).equals(tableKey(b));
    }

    private static String tableKey(RouteConverter.Converted c) {
        StockPath p=new StockPath();
        for(StockPath.Segment source:c.path.segments) {
            StockPath.Segment s=new StockPath.Segment();s.length=source.length;s.mainAction=source.mainAction;
            s.assistantAction=source.assistantAction;s.trafficLightNum=source.trafficLightNum;
            s.links.addAll(source.links);p.segments.add(s);
        }
        p.groups.addAll(c.path.groups);p.trafficLights.addAll(c.path.trafficLights);
        return p.contentKey()+java.util.Arrays.toString(c.cumulative)+java.util.Arrays.toString(c.linkBeginScalar)
                +java.util.Arrays.toString(c.linkEndScalar);
    }

    /** Refresh all supported dynamic annotations, including known disappearance, with no wire reselection. */
    public boolean refreshConditions(RouteConverter.Converted refreshed) {
        if (closed || route == null || refreshed == null || !route.converted.routeId.equals(refreshed.routeId)
                || !sameLinkTable(route.converted,refreshed)) return false;
        boolean lanesChanged=!laneKey(route.conditions.laneGuidance).equals(laneKey(refreshed.laneGuidance));
        route.conditions=refreshed;
        if(lanesChanged) { laneGeneration++;laneScheduleGeneration=-1;clock.cancel(tokenLanes); }
        return true;
    }

    /** Compatibility for callers which previously refreshed only lanes. */
    public boolean refreshLaneGuidance(RouteConverter.Converted refreshed) { return refreshConditions(refreshed); }

    public void onConditionsSnapshot(Map<String,Object> provenance) {
        publisher.note("conditionsSnapshot",provenance,clock.now());
    }

    private static String signKey(LaneGuidance.Placed p) {
        return p.sign.scalar+":"+p.segmentIndex+":"+p.linkIndex+":"+p.sign.annotated+":"+p.sign.rejectedReason+":"
                +Pb.hex(p.back())+":"+Pb.hex(p.front())+":"+Pb.hex(p.extend())+":"+Pb.hex(p.recommend())+":"
                +Pb.hex(p.backType())+":"+Pb.hex(p.frontType())+":"+Json.write(p.sign.decisions);
    }
    private static String laneKey(LaneGuidance lanes) {
        if(lanes==null)return "null";
        StringBuilder key=new StringBuilder().append(lanes.known).append(':').append(lanes.rawSignCount).append(':').append(Json.write(lanes.diagnostics));
        for(LaneGuidance.Placed p:lanes.signs)key.append('|').append(signKey(p));return key.toString();
    }

    /** DriveRouteManager.G(): a new focus path is sent with its selection. */
    private void onRouteDataUpdate(Route r, long generation) {
        if (r == null) return;
        long pathId = r.pathId();
        if (live && !liveRouteCurrent(r, pathId, generation)) return;
        if (curPathId == pathId) return;
        clock.cancel("checkPathByUpdateNavi");
        if (!live) curPathId = pathId;
        // k.e() is -1 here (no multi-path send), never the result's own request id: route + selection.
        if (sendPath(r, pathId, false, generation) && live) curPathId = pathId;
        publishStatus();
    }

    /** k.i() -> k.g() -> n.a() -> e0.k() per chunk, then e0.l(pathId). */
    private boolean sendPath(Route r, long pathId, boolean resend, long generation) {
        if (r == null || r.pathId() != pathId || (live && !liveRouteCurrent(r,pathId,generation))) return false;
        try {
            String payload = StockRouteJson.messageSend(r.converted.path, StockRouteJson.routeType(r.amapRouteType, resend), config.strings);
            List<String> chunks = StockRouteJson.chunks(payload, 1, wallClock());
            if (live) {
                if (!liveRouteCurrent(r,pathId,generation)) return false;
                ((GuardedLivePublisher) publisher).beginRoute(pathId,chunks.size());
            }
            for (String chunk : chunks) {
                if (live && !liveRouteCurrent(r,pathId,generation)) return false;
                int result = fire(Topics.ROUTE, StockMessages.route(chunk, 1));
                if (live && result != 0) return false;
            }
        } catch (RuntimeException e) {
            // k.g() catches and logs; the selection below is inside k.i(), after g(), so it still goes.
            Map<String, Object> f = Json.object();
            f.put("error", String.valueOf(e));
            publisher.note("routeSerializeFailed", f, clock.now());
            if (live) return false;
        }
        if (live && !liveRouteCurrent(r,pathId,generation)) return false;
        int selected = fire(Topics.PATH_SELECTED, StockMessages.pathSelected(pathId));
        if (live && (selected != 0 || !liveRouteCurrent(r,pathId,generation))) return false;
        if (!live && config.emulatedAckDelayMs >= 0) {
            final long id = pathId;
            clock.cancel(tokenAck);
            clock.post(tokenAck, config.emulatedAckDelayMs, () -> {
                Map<String, Object> f = Json.object();
                f.put("source", "emulated");
                f.put("pathIDResp", id);
                publisher.note("ack", f, clock.now());
                onPathSelectConfirm(StockMessages.pathSelectConfirm(id, 200));
            });
        }
        return true;
    }

    /** PathSelectedProxy.d(): 44/0x8002 from ADAS. Status 200 -> g(pathIDResp), else w(). */
    public void onPathSelectConfirm(byte[] payload) {
        if (live) {
            RealPathAck.Selection s;
            try { s = RealPathAck.decode(payload); } catch (IllegalArgumentException malformed) { return; }
            if (!closed && gate() && s.pathId != null && s.statusCode != null && s.statusCode == 200
                    && curPathId > 0 && s.pathId == curPathId) selectPathId = curPathId;
            publishStatus();
            return;
        }
        Map<String, Object> notify;
        try { notify = Pb.PATH_SELECT_CONFIRM_NOTIFY.decode(payload); }
        catch (RuntimeException e) { return; }
        @SuppressWarnings("unchecked") Map<String, Object> s = (Map<String, Object>) notify.get("naviPathUserSelectStsConfirmStruct");
        if (s == null) return;
        long status = s.containsKey("statusCode") ? ((Number) s.get("statusCode")).longValue() : 0;
        long path = s.containsKey("pathIDResp") ? ((Number) s.get("pathIDResp")).longValue() : 0;
        if (status == 200) {
            // DriveRouteManager.g(): a confirm for another path means ours was not taken: resend.
            selectPathId = path;
            if (curPathId != path) onSelectMainPathStatus(curPathId, 1);
        } else onSelectMainPathStatus(curPathId, 1);   // w()
        publishStatus();
    }

    /** DriveRouteManager.onSelectMainPathStatus(path, 1) -> I(path): resend route and selection, RouteType 14. */
    private void onSelectMainPathStatus(long path, int result) {
        if (live) return; // A real retry is a new guarded attempt with a fresh wire ID.
        if (gate() && result == 1) clock.post(null, 0, () -> {
            if (route == null) return;
            curPathId = path;
            sendPath(route, path, true, routeGeneration);
        });
    }

    /** DriveRouteManager.onDnpSwitchState: on -> resend the focus path 300 ms later; off -> forget it. */
    private void onDnpSwitchState(boolean on) {
        if (live) {
            routeGeneration++; curPathId = -1; selectPathId = -1; niPathId = -1;
            clock.cancel(tokenNoaSwitch); requestId = -1; publishStatus();
            return;
        }
        if (on) {
            clock.cancel(tokenNoaSwitch);
            clock.post(tokenNoaSwitch, 300, () -> {
                if (route == null) return;
                curPathId = route.pathId();
                sendPath(route, curPathId, false, routeGeneration);
            });
        } else {
            curPathId = -1;
            clock.cancel("checkPathByUpdateNavi");
            requestId = -1;
        }
        publishStatus();
    }

    private boolean gate() {
        return !closed && (live ? started && dnpService && dnpSwitchRaw && noSleep
                : (dnpService && dnpSwitch) || config.adasType == 6);
    }
    private boolean liveRouteCurrent(Route r, long path, long generation) {
        return !closed && naving && gate() && route == r && r.pathId() == path && routeGeneration == generation;
    }

    // ------------------------------------------------------------------ NaviInfo

    /**
     * Amap NaviInfo for the route being guided (MmtSomeIpDataManager.v0 and DriveRouteManager.l):
     * placed on our link table; never behind the last placement on the same path, as Amap's own
     * guidance never moves back (DYN-01). Not called while Navigator is off the route (frozen).
     */
    public void onProgress(Progress p) {
        Route r = route;
        if (r == null || !naving) return;
        final long generation = routeGeneration;
        if (live && (!liveRouteCurrent(r,r.pathId(),generation) || curPathId != r.pathId())) return;
        RouteConverter.Converted c = r.conditions;
        double passed = c.metres(p.scalar);
        Double last = lastPassed.get(r.pathId());
        if (last != null && passed < last) {
            Map<String, Object> f = Json.object();
            f.put("pathId", r.pathId());
            f.put("passedM", passed);
            f.put("lastPassedM", last);
            publisher.note("progressBehind", f, clock.now());
            return;
        }
        lastPassed.put(r.pathId(), passed);
        final int[] ni = c.naviInfo(passed);
        final double time = p.timeToFinishS;
        final double traffic = p.trafficSpeedMps;
        final long facilitiesEpoch=facilitiesProgressEpoch;
        clock.post(null, 0, () -> {
            if (route==r && routeGeneration==generation && (!live || liveRouteCurrent(r,r.pathId(),generation)))
                naviInfo(r, ni, passed, time, traffic, facilitiesEpoch);
        });
        if (dnpSwitch) oddDistance();                                // DnpOddDistanceController.e()
    }

    private void naviInfo(Route r, int[] ni, double passed, double timeToFinish, double trafficSpeed, long facilitiesEpoch) {
        RouteConverter.Converted c = r.conditions;
        int seg = ni[0], link = ni[1], linkRemain = ni[3], segRemain = ni[4], routeRemain = ni[5], remainLights = ni[6];
        StockPath.Segment segment = c.path.segments.get(seg);
        StockPath.Link l = segment.links.get(link);
        double[] light = fontRoundLight(c, ni[2], linkRemain, remainLights);
        if (route == r && (niPathId != r.pathId() || laneScheduleGeneration != routeGeneration)) {
            scheduleAheadLanes(); laneScheduleGeneration = routeGeneration;
        }          // J0(pathInfo)
        hasMixFork = l.hasMixFork ? 1 : 2;
        hasParallel = l.hasParallel ? 1 : 2;
        int main = l.mainAction;                                    // (the segment fallback result is discarded in stock)
        int assistant = l.assistantAction != 0 ? l.assistantAction : segment.assistantAction;
        int cross = StockRouteJson.displayAction(segment.mainAction, segment.assistantAction);
        actDistance = segRemain;
        icon = cross; actMain = main; actAssist = assistant;
        niSegDist = segRemain; niPathId = r.pathId(); niSegIdx = seg; niLinkIdx = link; niLinkRemain = linkRemain;
        if (route == r) {
            lanePassedM = passed;
            if(facilitiesEpoch==facilitiesProgressEpoch)facilitiesProgressValid=true;
            publishLanes(r, passed);
        }
        distToDest = routeRemain;
        timeToDest = Double.isNaN(timeToFinish) ? 0 : (int) Math.max(0, RouteConverter.round(timeToFinish));
        solidLaneDistance();
        setLightLocation(0, light[0], light[1]);
        // curLinkSpeed: the factory's typical link speed (ERAT 0x4269), not live traffic: the matched graph
        // road's, else the closest Yandex value, the section's free-flow speed (distance over time without traffic).
        double free = c.linkSpeedKmh != null && !Double.isNaN(c.linkSpeedKmh[ni[2]]) ? c.linkSpeedKmh[ni[2]] : c.segmentFreeFlowKmh[seg];
        historySpeed = Double.isNaN(free) ? 0 : RouteConverter.round(free);
        // Captured Yandex jam speed is retained in trafficContext diagnostics. The factory's
        // flow-speed setter has no callers; receiver units/mapping remain unproved, so keep 0.
        icon = cross;
        tunnels(c, passed);
        cameras(c, passed);
        f0TidalLane(-1, tidal);
        f0ActionCamera();
        f0SpeedIntersection();
        voice = "";
        publishStatus();
    }

    /** FontRoundBean.lightLocation: the next light within 3 km ahead, from getAllTrafficLights(). */
    private static double[] fontRoundLight(RouteConverter.Converted c, int globalLink, int linkRemain, int remainLights) {
        double acc = 0;
        int link = 0;
        for (StockPath.Segment s : c.path.segments) for (StockPath.Link l : s.links) {
            if (link >= globalLink) {
                acc += link == globalLink ? linkRemain : l.length;
                if (l.hasTrafficLight) {
                    int index = c.path.trafficLights.size() - remainLights;
                    if (index >= 0 && index < c.path.trafficLights.size()) return c.path.trafficLights.get(index);
                    return new double[]{0, 0};
                }
                if (acc >= 3000) return new double[]{0, 0};
            }
            link++;
        }
        return new double[]{0, 0};
    }

    /** TunnelDetector events from Yandex's tunnel ranges: near-enter under 2 km, enter, leave. */
    private void tunnels(RouteConverter.Converted c, double passed) {
        for (int i = 0; i < c.tunnelM.size(); i++) {
            double[] t = c.tunnelM.get(i);
            double[] ends = c.tunnelEnds.get(i);
            if (passed >= t[0] && passed < t[1]) {
                inTunnel = true;
                if (enteredTunnel != i) {                            // onEnterTunnel -> L()
                    enteredTunnel = i;
                    setTunnel(0, 2, ends[0], ends[1], 0, 0);
                    sendTunnel();
                }
                return;
            }
            if (enteredTunnel == i && passed >= t[1]) {              // onLeaveTunnel -> N()
                enteredTunnel = -1;
                inTunnel = false;
                setTunnel(0, config.adasType == 6 ? 2 : 4, 0, 0, ends[2], ends[3]);
                sendTunnel();
                clearTunnel();
            }
            double ahead = t[0] - passed;
            if (ahead > 0 && ahead < 2000) {                         // onNearEnterTunnel -> T()
                setTunnel(RouteConverter.round(ahead), 1, 0, 0, 0, 0);
                return;
            }
        }
        inTunnel = false;
    }

    /** onShowNaviCameraExt -> f0(): a speed camera within 2 km ahead (SubCameraExtTypeUltrahighSpeed). */
    private void cameras(RouteConverter.Converted c, double passed) {
        double[] next = null;
        for (double[] cam : c.speedCameras) if (cam[0] >= passed && cam[0] - passed < 2000) { next = cam; break; }
        if (next != null) {
            int distance = RouteConverter.round(next[0] - passed);
            cameraType = 7;
            cameraDistance = distance;
            eyeSpeed = (int) next[1];
            eyeDist = distance;
            // onShowNaviCameraExt fires when the camera ahead changes; distances move with NaviInfo.
            if (shownCamera != next[0]) { shownCamera = next[0]; cameraShown = true; f0ActionCamera(); }
        } else if (cameraShown) {
            cameraShown = false;
            shownCamera = Double.NaN;
            eyeSpeed = 0; eyeDist = 0;                               // reSetCameraSpeedParam
            iSpeed = 0; giStart = 0; giEnd = 0; iStartLon = iStartLat = iEndLon = iEndLat = 0;   // reSetIntervalCameraSpeed
            icon = 0; actMain = 0; actAssist = 0; actDistance = -1; cameraType = 0; cameraDistance = -1;
        }
    }

    /** SpeedIntersectionInformationParam.y(): no long solid lane from Yandex, so the reset branch. */
    private void solidLaneDistance() {
        solidDistance = -1;
    }

    /** Point-scoped signs. Unsupported nearest signs block later signs until passed. */
    private void publishLanes(Route r, double passed) {
        LaneGuidance guidance = r.conditions.laneGuidance;
        if (guidance != lastLaneDiagnostics && guidance != null) {
            Map<String,Object> status=Json.object();status.put("known",guidance.known);status.put("rawSignCount",guidance.rawSignCount);
            status.put("placedSignCount",guidance.signs.size());status.put("diagnostics",guidance.diagnostics);
            publisher.note("laneSourceStatus",status,clock.now()); lastLaneDiagnostics=guidance;
        }
        LaneGuidance.Placed next = guidance == null ? null : guidance.next(passed);
        if (next != null && !next.sign.supported()) {
            if (rejectedLane != next) {
                Map<String,Object> f = Json.object(); f.put("scalar",next.sign.scalar); f.put("reason",next.sign.rejectedReason);
                publisher.note("laneSignUnsupported",f,clock.now()); rejectedLane=next;
            }
            next = null;
        }
        if (next == null) {
            if (shownLane != null) {
                laneDist = -1; laneSeg = -1; laneLink = -1;
                fire(Topics.SD_INTERSECTION,StockMessages.intersection(-1,new byte[0],new byte[0]));
                fire(Topics.SD_NEXT_LANES,StockMessages.nextLanes(new byte[0],new byte[0],-1,-1,wallClock()));
                shownLane = null;shownLaneKey=null;shownLaneTimestamp=0;
            }
            return;
        }
        laneDist = Math.max(0,RouteConverter.round(next.metres-passed));
        laneSeg = next.segmentIndex; laneLink = next.linkIndex;
        String key=r.pathId()+":"+signKey(next);
        if (!key.equals(shownLaneKey)) {
            Map<String,Object> f=Json.object();f.put("scalar",next.sign.scalar);f.put("annotated",next.sign.annotated);f.put("decisions",next.sign.decisions);
            publisher.note("laneSignDecisions",f,clock.now());
            shownLaneKey=key;shownLaneTimestamp=wallClock();
        }
        int a = fire(Topics.SD_INTERSECTION,StockMessages.intersection(laneDist,next.back(),next.front()));
        int b = fire(Topics.SD_NEXT_LANES,StockMessages.nextLanes(next.back(),next.front(),next.extend(),next.recommend(),next.backType(),next.frontType(),laneSeg,laneLink,shownLaneTimestamp));
        if (a == 0 && b == 0) shownLane = next;
    }

    private void scheduleAheadLanes() {
        // Factory query window <=3 km, at most six beans, 2 s query delay then every10 s.
        clock.cancel(tokenLanes);
        final Route expected = route;
        final long generation = routeGeneration, annotations = laneGeneration;
        clock.post(tokenLanes, 2000, new Runnable() {
            @Override public void run() {
                if (!naving || route != expected || routeGeneration != generation || annotations != laneGeneration || expected == null) return;
                LaneGuidance guidance = expected.conditions.laneGuidance;
                // A known empty query is []; unavailable source is not fabricated as an instruction.
                String json = guidance == null ? "[]" : guidance.aheadJson(lanePassedM,expected.pathId(),wallClock());
                fire(Topics.SD_AHEAD_LANES, StockMessages.aheadLanes(json));
                clock.post(tokenLanes, 10000, this);
            }
        });
    }

    // ------------------------------------------------------------------ location

    /**
     * Amap LocInfo (MmtSomeIpDataManager.P): at most once a second ({@code k.h.a0.d.c.d()}), the
     * region, then the location bundle; stationary (either speed 0) starts the 1 s repeat instead.
     */
    public void onLocation(Fix fix) {
        final long capturedAt = clock.now();
        if (closed || (live && !freshQueuedFix(fix,capturedAt))) return;
        Map<String, Object> f = Json.object();
        f.put("ageMs", fix.ageMs);
        f.put("speedMps", fix.speedMps);
        f.put("simulated", fix.simulated);
        f.put("raw", !Double.isNaN(fix.rawLon));
        // No car speed reaches Navigator's process: FieldDefaults.VEHICLE_SPEED says GNSS stands in.
        f.put("vehicleSpeedSource", fix.vehicleSpeedKmh == null ? "gnss" : "vehicle");
        publisher.note("location", f, clock.now());
        if (fix.simulated && !config.acceptSimulatedLocations) return;
        int gpsSpeed = (int) (fix.speedMps * 3.6);
        int realSpeed = fix.vehicleSpeedKmh != null ? (int) (double) fix.vehicleSpeedKmh : gpsSpeed;
        if (!live && realSpeed != lastVehicleSpeed) {                // BydAutoSpeed listener -> f.g(speed)
            lastVehicleSpeed = realSpeed;
            vehicleSpeed(realSpeed);
        }
        final long generation = routeGeneration;
        clock.post(null, 0, () -> {
            if (!live || (generation == routeGeneration && freshQueuedFix(fix,capturedAt)))
                location(fix, gpsSpeed, realSpeed);
        });
    }

    private boolean freshQueuedFix(Fix fix, long capturedAt) {
        long now = clock.now();
        return !closed && gate() && now >= capturedAt && fix.ageMs >= 0 && fix.ageMs <= NavigatorDriver.MAX_FIX_AGE_MS
                && now - capturedAt <= NavigatorDriver.MAX_FIX_AGE_MS - fix.ageMs;
    }

    private void location(Fix fix, int gpsSpeed, int realSpeed) {
        double[] matched, raw;
        try {
            matched = Coordinates.toSdk(fix.matchedLon, fix.matchedLat, config.frame);
            raw = Double.isNaN(fix.rawLon) ? matched : Coordinates.toSdk(fix.rawLon, fix.rawLat, config.frame);
        } catch (IllegalArgumentException outside) {
            // Outside the frame's envelope there is no stock-frame position to send.
            publisher.note("locationOutsideFrame", Json.object(), clock.now());
            return;
        }
        double rLon = Coordinates.grid(matched[0]), rLat = Coordinates.grid(matched[1]);
        double gLon = Coordinates.grid(raw[0]), gLat = Coordinates.grid(raw[1]);
        int course = Double.isNaN(fix.heading) ? 0 : (int) fix.heading;
        niOwnership = fix.ownership;                                 // locMatchInfo.ownership (MIN_VALUE -> -1)
        roadClass = fix.roadClass;                                   // locMatchInfo.roadClass
        if (live || throttle()) {
            sendRegion();
            weather = FieldDefaults.WEATHER;
            locationBundle(gpsSpeed, realSpeed, gLon, gLat, rLon, rLat, course);
            publishFacilities(route, lanePassedM, raw[0], raw[1]);
            offCourse = false;
        }
    }

    /** Point objects use the current route's progress; 600 m visibility is an explicit adapter policy.
     * Factory e0.i0 is callback-driven and sends straight-line distance from last location. */
    private void publishFacilities(Route expected,double passed,double lon,double lat) {
        if(!config.roadFacilities || !naving || expected==null || niPathId!=expected.pathId()) return;
        RoadFacilities.View view=expected.conditions.roadFacilities;
        RoadFacilities.Source source=view==null?null:view.source;
        if(source!=lastFacilitiesSource) {
            Map<String,Object> info=source==null?Json.object():source.note();
            info.put("lookAheadM",RoadFacilities.LOOK_AHEAD_M);info.put("visibilityPolicy","adapter nearest mapped object");
            info.put("coordinateSource","interpolated route polyline");
            publisher.note("roadFacilitiesSource",info,clock.now());lastFacilitiesSource=source;
        }
        RoadFacilities.Placed next=view==null || !facilitiesProgressValid?null:view.next(passed,RoadFacilities.LOOK_AHEAD_M);
        int distance=next==null?0:(int)Math.max(0,Coordinates.distance(lon,lat,next.lon,next.lat));
        byte[] payload=next==null?StockMessages.roadFacilities(0,0,0,0):
                StockMessages.roadFacilities(next.source.type,distance,next.lon,next.lat);
        if(!facilitiesResetPending && lastFacilitiesPath==expected.pathId() && java.util.Arrays.equals(lastFacilitiesPayload,payload))return;
        long now=clock.now();if(now-lastFacilitiesAttempt<1000)return;
        lastFacilitiesAttempt=now;
        int result=fire(Topics.SD_ROAD_FACILITIES,payload);
        if(result==0 && route==expected && niPathId==expected.pathId()) {
            lastFacilitiesPayload=payload;lastFacilitiesPath=expected.pathId();facilitiesResetPending=false;
        }
    }

    /** k.h.a0.d.c.d(): true at most once per second, absorbing up to 50 ms of jitter. */
    private boolean throttle() {
        long elapsed = (clock.now() - lastLocationSend) - locationThrottle;
        if (elapsed < 0) return false;
        lastLocationSend = elapsed < 50 ? clock.now() - elapsed : clock.now();
        return true;
    }

    /** H0(): the location bundle, or the stationary loop. */
    private void locationBundle(int gpsSpeed, int realSpeed, double gLon, double gLat, double rLon, double rLat, int course) {
        clock.cancel(tokenSeg);
        if (!live && (realSpeed == 0 || gpsSpeed == 0)) {
            clock.post(tokenSeg, 0, new Runnable() {                // class a: no tunnel in the loop
                @Override public void run() {
                    sendPosition(gLon, gLat, rLon, rLat, realSpeed, gpsSpeed, course);
                    sendTrafficInfo();
                    light();
                    clock.post(tokenSeg, 1000, this);
                }
            });
            return;
        }
        sendPosition(gLon, gLat, rLon, rLat, realSpeed, gpsSpeed, course);
        sendTrafficInfo();
        sendTunnel();
        light();
    }

    private void light() {
        if ((lightDistance < 500 || lightStart > 0) && naving)
            fire(Topics.SD_TRAFFIC_LIGHT, StockMessages.trafficLight(lightExist, lightLon, lightLat, lightState, lightStart, lightEnd, lightDir, lightWaitNum));
    }

    /**
     * GaoDeSignalManager.onUpdateTREvent -> D0(): the traffic events along the route, sent as the
     * incident list. Yandex's route events are not mapped to Amap's layer/type codes, so the list is
     * empty, as the stock sends it when Amap has none.
     */
    public void onTrafficEvents() {
        clock.post(null, 0, () -> {
            fire(Topics.SD_TRAFFIC_INCIDENT, StockMessages.incidents(new int[0], new int[0], new double[0], new double[0]));
            resetConstruction();
        });
    }

    /** GaoDeSignalManager.onCurrentRoadSpeed: the posted limit in km/h. */
    public void onCurrentRoadSpeed(int kmh) {
        int value = config.roadSpeedLimit ? Math.max(kmh, 0) : 0;
        clock.post(null, 0, () -> roadSpeed = value);
    }

    /** Known positive limit or explicit unknown, with local evidence separate from the wire's 0. */
    public void onCurrentRoadSpeed(int kmh, String source) {
        Map<String,Object> fields = Json.object();
        fields.put("known", kmh > 0);
        fields.put("limitKmh", kmh > 0 ? (long) kmh : null);
        fields.put("source", kmh > 0 ? source : "unknown");
        fields.put("enabled", config.roadSpeedLimit);
        fields.put("wireValueKmh", config.roadSpeedLimit ? Math.max(kmh, 0) : 0);
        publisher.note("currentRoadSpeed", fields, clock.now());
        onCurrentRoadSpeed(kmh);
    }

    /** Diagnostic source retention only: Amap flow-speed units and congestion window mapping are unproved. */
    public void onTrafficContext(Map<String,Object> fields) {
        publisher.note("trafficContext", fields, clock.now());
    }

    // ------------------------------------------------------------------ DNP ODD distance

    private void oddDistance() {
        // The open-city set (KEY_OPEN_CITY) is empty without Amap's cloud cache: h(MAX), as the car logged.
        oddLast = Integer.MAX_VALUE;
        fire(Topics.ADV_AUTO_FUN_INFO, StockMessages.noOddRegionDistance(Integer.MAX_VALUE));
    }

    private void vehicleSpeed(int kmh) {
        if (kmh > 0 || !dnpService || !dnpSwitch) { clock.cancel(tokenOdd); return; }
        clock.cancel(tokenOdd);
        clock.post(tokenOdd, 0, new Runnable() {
            @Override public void run() {
                fire(Topics.ADV_AUTO_FUN_INFO, StockMessages.noOddRegionDistance(oddLast));
                clock.post(tokenOdd, 1000, this);
            }
        });
    }

    private void oddReset() {
        oddLast = 0;
        clock.cancel(tokenOdd);
    }

    // ------------------------------------------------------------------ NavigationStatus loop

    /** GaoDeSignalManager.setMapState -> MmtSomeIpDataManager.p(): the loop restarts now. */
    private void setMapState(int state) {
        mapState = state;
        statusLoopCreated = true;
        clock.cancel(tokenStatus);
        clock.post(tokenStatus, 0, this::statusTick);
    }

    /** MmtSomeIpDataManager.d.run(). */
    private void statusTick() {
        int state;
        if (naving) { arriveCounter = 0; state = 101; }
        else if (arriveCounter > 0 && arriveCounter <= 3) { arriveCounter++; state = 110; }
        else { arriveCounter = 0; state = mapState; }
        fire(Topics.NAVIGATION_STATUS, StockMessages.navigationStatus(state));
        clock.post(tokenStatus, 1000, this::statusTick);
        retryUnpublished();
    }

    /**
     * LIVE: a route whose attempt never completed (a prerequisite gap during its chunks or selection)
     * never reached ADAS, so the same table goes again as a fresh attempt once the guard admits.
     * This is not a republication of a route ADAS holds: that happens only on a new Yandex route.
     */
    private void retryUnpublished() {
        if (!live || closed || !naving || route == null || curPathId == route.pathId()) return;
        if (clock.now() - lastPublishAttempt < 1000 || !((GuardedLivePublisher) publisher).admitting() || !gate()) return;
        Route unsent = route;
        Map<String, Object> f = Json.object(); f.put("pathId", unsent.pathId()); f.put("attempt", ++routeRetries);
        publisher.note("routeRetry", f, clock.now());
        try { onRouteResult(unsent.converted, unsent.amapRouteType); }
        catch (RuntimeException failure) { lastPublishAttempt = clock.now(); }
    }

    // ------------------------------------------------------------------ resets (z(), b0(), d0())

    private void resetAll() {
        // A LIVE stop permits status100 only; do not bypass that guard to clear an old object.
        // The next accepted route explicitly overwrites/clears facilities on its first fresh fix.
        if(lastFacilitiesPayload!=null)fire(Topics.SD_ROAD_FACILITIES,StockMessages.roadFacilities(0,0,0,0));
        lastFacilitiesPayload=null;lastFacilitiesPath=-1;lastFacilitiesSource=null;
        facilitiesResetPending=true;facilitiesProgressValid=false;lastFacilitiesAttempt=Long.MIN_VALUE/4;
        if (shownLane != null) {
            fire(Topics.SD_INTERSECTION,StockMessages.intersection(-1,new byte[0],new byte[0]));
            fire(Topics.SD_NEXT_LANES,StockMessages.nextLanes(new byte[0],new byte[0],-1,-1,wallClock()));
        }
        shownLane = null;shownLaneKey=null;shownLaneTimestamp=0; rejectedLane = null; lastLaneDiagnostics = null; lanePassedM = 0; laneScheduleGeneration = -1;
        laneSeg = -1; laneLink = -1; tidal = 1; laneDist = 0;               // reSetNextIntersectionLaneInfoParam
        niPathId = -1; niSegIdx = -1; niLinkIdx = -1; niSegDist = -1; niLinkRemain = -1; niOwnership = -1; // reSetNaviInfo
        distToDest = 0; timeToDest = 0; eyeSpeed = 0; roadSpeed = 0; eyeDist = 0; iSpeed = 0; giStart = 0; giEnd = 0; // reSetTrafficParam
        iStartLon = iStartLat = iEndLon = iEndLat = 0; resetConstruction(); resetJam(); offCourse = false;
        resetLightLocation();
        giStart = 0; giEnd = 0; giOdd = 0;                                  // reSetGenernalNavigationParam
        icon = 0; actMain = 0; actAssist = 0; actDistance = -1; cameraType = 0; cameraDistance = -1;
        flowSpeed = 0; historySpeed = 0; hasParallel = 0; hasMixFork = 0; hasSolid = 0; solidDistance = -1; voice = "";
        clearTunnel();
        sendTunnel();
        fire(Topics.SD_TRAFFIC_INCIDENT, StockMessages.incidents(new int[0], new int[0], new double[0], new double[0]));
        enteredTunnel = -1;
        inTunnel = false;
        cameraShown = false;
        shownCamera = Double.NaN;
        publishStatus();
    }

    private void resetJam() { jamLength = 0; distToJam = -1; jamStatus = 0; passTime = 0; }
    private void resetConstruction() { construction = 0; cLon = 0; cLat = 0; }
    private void resetLightLocation() {
        lightExist = 1; lightLon = 0; lightLat = 0; lightState = 0; lightStart = 0; lightEnd = 0; lightDir = 0; lightWaitNum = 0; lightDistance = 0;
    }
    private void resetIntersectionInfo() { hasParallel = 0; hasMixFork = 0; hasSolid = 0; solidDistance = -1; voice = ""; }

    /** LightParam.setLightLocationParam. */
    private void setLightLocation(int distance, double lon, double lat) {
        lightDistance = distance; lightLon = lon; lightLat = lat;
        if (lon == 0.0d && lat == 0.0d) resetLightLocation();
        else if (lon > 0.0d && lat > 0.0d) lightExist = 2;
    }

    private void setTunnel(int dist, int state, double sLon, double sLat, double eLon, double eLat) {
        tunnelDist = dist; tunnelState = state; tStartLon = sLon; tStartLat = sLat; tEndLon = eLon; tEndLat = eLat;
    }
    private void clearTunnel() { setTunnel(0, 0, 0, 0, 0, 0); }

    // ------------------------------------------------------------------ f0 senders

    private void sendPosition(double gLon, double gLat, double rLon, double rLat, int realSpeed, int gpsSpeed, int course) {
        fire(Topics.SD_VEHICLE_LOCATION, StockMessages.vehicleLocation(gLon, gLat, rLon, rLat, realSpeed, gpsSpeed, course, niPathId,
                niSegIdx, niLinkIdx, niSegDist, niLinkRemain, niOwnership));
    }

    private void sendTrafficInfo() {
        fire(Topics.TRAFFIC_INFO, StockMessages.trafficInfo(jamLength, distToJam, jamStatus, passTime, construction, cLon, cLat, roadSpeed,
                eyeSpeed, eyeDist, iStartLon, iStartLat, iEndLon, iEndLat, iSpeed, offCourse, distToDest, timeToDest, roadClass));
    }

    private void sendTunnel() { fire(Topics.SD_TUNNEL, StockMessages.tunnel(tunnelDist, tunnelState, tStartLon, tStartLat, tEndLon, tEndLat)); }

    private void sendRegion() { fire(Topics.SD_REGION, StockMessages.region(regionId, countryCode, provinceCode, cityCode, weather)); }

    private void f0TidalLane(int rampDist, int tidalLane) { fire(Topics.SD_TIDAL_LANE, StockMessages.tidalLane(rampDist, tidalLane)); }

    private void f0ActionCamera() {
        fire(Topics.SD_ACTION_CAMERA, StockMessages.actionAndCamera(icon, actMain, actAssist, actDistance, cameraType, cameraDistance));
    }

    private void f0SpeedIntersection() {
        fire(Topics.SD_SPEED_INTERSECTION, StockMessages.speedIntersection(flowSpeed, historySpeed, hasParallel, hasMixFork, hasSolid, solidDistance, voice));
    }

    /** Every dynamic sender returns early while ADAS sleeps ({@code f0.f13251b}); route and selection do not. */
    private int fire(long topic, byte[] payload) {
        if (closed || (live && !gate())) return -1;
        boolean routeOrSelection = topic == Topics.ROUTE || topic == Topics.PATH_SELECTED;
        if (sleeping && !routeOrSelection) return -1;
        int result;
        try { result = publisher.fire(topic, payload, clock.now()); }
        catch (RuntimeException failure) {
            if (!live) throw failure;
            Map<String,Object> info = Json.object(); info.put("topic",topic); info.put("error",failure.toString());
            publisher.note("liveFireFailed",info,clock.now());
            return -2;
        }
        if (!live || result == 0) lastFire = clock.now();
        if (routeOrSelection) publishStatus();
        return result;
    }

    private static long wallClock() { return System.currentTimeMillis(); }

    // ------------------------------------------------------------------ foreign writers (DYN-05)

    private int foreignIdle;

    /**
     * An event from another writer of our topics, seen by a passive client subscription: the idle
     * stock map's pathId -1 position or NavigationStatus 100 while we guide. Recorded and counted;
     * ADAS's arbitration between the two writers is unknown, so nothing else changes.
     */
    public void onForeignEvent(long topic, byte[] payload) {
        Map<String, Object> f = Json.object();
        f.put("topic", topic);
        f.put("name", Topics.name(topic));
        try {
            Pb.Msg schema = Topics.schema(topic);
            f.put("decoded", schema == null ? null : schema.decode(payload));
        } catch (RuntimeException e) { f.put("decoded", null); }
        boolean conflict = naving && (topic == Topics.NAVIGATION_STATUS || topic == Topics.SD_VEHICLE_LOCATION);
        if (conflict) foreignIdle++;
        f.put("conflictWhileGuiding", conflict);
        publisher.note("foreignEvent", f, clock.now());
    }

    public int foreignConflicts() { return foreignIdle; }

    // ------------------------------------------------------------------ status

    public long currentPathId() { return niPathId; }
    public long publishedPathId() { return curPathId; }
    public boolean naving() { return naving; }

    private void publishStatus() {
        Snapshot next = new Snapshot(publisher.mode(), lastFire, niPathId != -1 ? niPathId : curPathId, naving && curPathId != -1);
        Snapshot old = snapshot;
        if (old.mode == next.mode && old.lastSendMonotonicMs == next.lastSendMonotonicMs && old.currentPathId == next.currentPathId
                && old.routeActive == next.routeActive) return;
        snapshot = next;
        for (Listener l : listeners) l.onBridgeStatus(next);
    }

    @Override public Snapshot status() {
        if (closed) return Snapshot.DISABLED;
        Snapshot s = snapshot;
        // lastSend moves every second; the snapshot carries the newest without a listener storm.
        return lastFire == s.lastSendMonotonicMs ? s : new Snapshot(s.mode, lastFire, s.currentPathId, s.routeActive);
    }

    @Override public void addListener(Listener listener) { listeners.add(listener); }
    @Override public void removeListener(Listener listener) { listeners.remove(listener); }

    /** Stops producer work only; the external transport wrapper owns unoffer/unsubscribe/unbind. */
    @Override public void close() {
        if (closed) return;
        closed = true; sessionEpoch++; routeGeneration++;
        if (live) ((GuardedLivePublisher) publisher).navigationStopped();
        for (Object token : new Object[]{tokenSeg,tokenStatus,tokenOdd,tokenNoaSwitch,tokenLanes,tokenAck}) clock.cancel(token);
        route = null; naving = false; curPathId = -1; selectPathId = -1; niPathId = -1;
        snapshot = Snapshot.DISABLED;
        for (Listener listener : listeners) listener.onBridgeStatus(snapshot);
    }

    /** For tests. */
    List<Object> debugState() {
        List<Object> s = new ArrayList<>();
        s.add(mapState); s.add(statusLoopCreated); s.add(selectPathId); s.add(requestId); s.add(dnpSwitchRaw); s.add(noSleep);
        s.add(laneSeg); s.add(laneLink); s.add(laneDist); s.add(giOdd); s.add(inTunnel); s.add(lightDistance); s.add(flowSpeed);
        return s;
    }
}
