package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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
public final class StockFeed implements BridgeStatus {
    public static final class Config {
        public Coordinates.Frame frame = Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE;
        public StockRouteJson.Strings strings = StockRouteJson.Strings.EN;
        /** TrafficInfo.SpdLmtSpeedValue from Yandex's posted limit (the stock code's onCurrentRoadSpeed); false sends 0. */
        public boolean roadSpeedLimit = true;
        /** Stock skips simulated locations ({@code locInfo.isSimulate == 1}); true only for emulator traces. */
        public boolean acceptSimulatedLocations;
        /** Emulated MMTPathSelected after this delay (dry-run only; the car's took ~110 ms); negative: none. */
        public long emulatedAckDelayMs = -1;
        /** ADAS type ({@code getAdasType}); 1 on this car. */
        public int adasType = 1;
    }

    /** One location fix: Yandex's FINE location (route-matched), the raw GNSS fix when there is one. */
    public static final class Fix {
        public final double matchedLon, matchedLat, rawLon, rawLat, speedMps, heading;
        public final boolean simulated;
        public final long ageMs;
        public final Double vehicleSpeedKmh;
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
    private int regionId, provinceCode, cityCode, weather; private long countryCode = 1;
    private int flowSpeed, historySpeed, hasParallel, hasMixFork, hasSolid, solidDistance; private String voice = "";
    private int icon, actMain, actAssist, actDistance = -1, cameraType, cameraDistance = -1;
    private int giStart, giEnd, giOdd;
    private int laneSeg = -1, laneLink = -1, tidal, laneDist;
    private boolean inTunnel;              // v2: matched link is a tunnel
    private int mapState = 100;
    private boolean statusLoopCreated;
    private short arriveCounter;
    private long lastLocationSend = Long.MIN_VALUE / 4, locationThrottle = 1000;
    private int oddLast;                    // DnpOddDistanceController.f13983c
    private int lastVehicleSpeed = Integer.MIN_VALUE;
    private int enteredTunnel = -1;
    private boolean cameraShown;
    private volatile long lastFire = -1;

    /** A converted route with its pathId, the Amap RouteType it came with, and its own request. */
    public static final class Route {
        public final RouteConverter.Converted converted;
        public final int amapRouteType;
        public final long requestId;
        Route(RouteConverter.Converted converted, int amapRouteType, long requestId) {
            this.converted = converted; this.amapRouteType = amapRouteType; this.requestId = requestId;
        }
        public long pathId() { return converted.path.pathId; }
    }

    public StockFeed(Scheduler clock, Publisher publisher, Config config) {
        this.clock = clock; this.publisher = publisher; this.config = config;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Process start: NoaCanSomeIpLink starts the four IVI services, GaoDeSignalManager's constructor
     * sets map state 100, which starts the 1 s NavigationStatus loop for good.
     */
    public void start(boolean dnpService, boolean dnpSwitch, boolean noSleep) {
        Map<String, Object> h = Json.object();
        h.put("schema", DryRunRecorder.SCHEMA);
        h.put("frame", config.frame.name());
        h.put("locale", config.strings.name());
        h.put("roadSpeedLimit", config.roadSpeedLimit);
        h.put("acceptSimulatedLocations", config.acceptSimulatedLocations);
        h.put("emulatedAckDelayMs", config.emulatedAckDelayMs);
        h.put("adasType", (long) config.adasType);
        h.put("publisher", publisher.mode().name());
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
        Map<String, Object> f = Json.object();
        f.put("dnpService", service); f.put("dnpSwitch", switchOn); f.put("noSleep", noSleep);
        publisher.note("gates", f, clock.now());
        this.dnpService = service;
        this.dnpSwitchRaw = switchOn;
        this.noSleep = noSleep;
        sleeping = !noSleep;                                   // f0.t(zG)
        boolean a = switchOn && noSleep;
        if (a != dnpSwitch) {
            dnpSwitch = a;
            onDnpSwitchState(a);
        }
    }

    /** GaoDeSignalManager.onNavStart: map state 101, observers' d() = z(). */
    public void onNavStart() {
        publisher.note("navStart", Json.object(), clock.now());
        naving = true;
        setMapState(101);
        clock.post(null, 0, this::resetAll);
        publishStatus();
    }

    /** GaoDeSignalManager.onNaviArrive: MmtSomeIpDataManager.R() arms three 110s. */
    public void onNaviArrive() {
        publisher.note("naviArrive", Json.object(), clock.now());
        clock.post(null, 0, () -> arriveCounter = 1);
    }

    /** GaoDeSignalManager.onNavStop: map state 100, observers onNavStop (z(), DriveRouteManager reset). */
    public void onNavStop() {
        publisher.note("navStop", Json.object(), clock.now());
        naving = false;
        setMapState(100);
        clock.post(null, 0, this::resetAll);
        clock.cancel(tokenLanes);
        // DriveRouteManager.onNavStop
        curPathId = -1;
        selectPathId = -1;
        route = null;
        oddReset();
        clock.cancel(tokenNoaSwitch);
        requestId = -1;
        publishStatus();
    }

    /** GaoDeSignalManager.onReroute: type 2 (yaw) sets map state 105; MmtSomeIpDataManager.b0(). */
    public void onReroute(int amapRouteType) {
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
        Long id = pathIds.get(converted.contentKey);
        if (id == null) {
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
        if (gate()) clock.post(null, 0, () -> onRouteDataUpdate(result));
        publishStatus();
        return result;
    }

    /** DriveRouteManager.G(): a new focus path is sent with its selection. */
    private void onRouteDataUpdate(Route r) {
        if (r == null) return;
        long pathId = r.pathId();
        if (curPathId == pathId) return;
        clock.cancel("checkPathByUpdateNavi");
        curPathId = pathId;
        // k.e() is -1 here (no multi-path send), never the result's own request id: route + selection.
        sendPath(r, pathId, false);
        publishStatus();
    }

    /** k.i() -> k.g() -> n.a() -> e0.k() per chunk, then e0.l(pathId). */
    private void sendPath(Route r, long pathId, boolean resend) {
        if (r == null || r.pathId() != pathId) return;
        try {
            String payload = StockRouteJson.messageSend(r.converted.path, StockRouteJson.routeType(r.amapRouteType, resend), config.strings);
            List<String> chunks = StockRouteJson.chunks(payload, 1, wallClock());
            for (String chunk : chunks) fire(Topics.ROUTE, StockMessages.route(chunk, 1));
        } catch (RuntimeException e) {
            // k.g() catches and logs; the selection below is inside k.i(), after g(), so it still goes.
            Map<String, Object> f = Json.object();
            f.put("error", String.valueOf(e));
            publisher.note("routeSerializeFailed", f, clock.now());
        }
        fire(Topics.PATH_SELECTED, StockMessages.pathSelected(pathId));
        if (config.emulatedAckDelayMs >= 0) {
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
    }

    /** PathSelectedProxy.d(): 44/0x8002 from ADAS. Status 200 -> g(pathIDResp), else w(). */
    public void onPathSelectConfirm(byte[] payload) {
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
        if (gate() && result == 1) clock.post(null, 0, () -> {
            if (route == null) return;
            curPathId = path;
            sendPath(route, path, true);
        });
    }

    /** DriveRouteManager.onDnpSwitchState: on -> resend the focus path 300 ms later; off -> forget it. */
    private void onDnpSwitchState(boolean on) {
        if (on) {
            clock.cancel(tokenNoaSwitch);
            clock.post(tokenNoaSwitch, 300, () -> {
                if (route == null) return;
                curPathId = route.pathId();
                sendPath(route, curPathId, false);
            });
        } else {
            curPathId = -1;
            clock.cancel("checkPathByUpdateNavi");
            requestId = -1;
        }
        publishStatus();
    }

    private boolean gate() { return (dnpService && dnpSwitch) || config.adasType == 6; }

    // ------------------------------------------------------------------ NaviInfo

    /**
     * Amap NaviInfo for the route being guided (MmtSomeIpDataManager.v0 and DriveRouteManager.l):
     * placed on our link table; never behind the last placement on the same path, as Amap's own
     * guidance never moves back (DYN-01). Not called while Navigator is off the route (frozen).
     */
    public void onProgress(Progress p) {
        Route r = route;
        if (r == null || !naving) return;
        RouteConverter.Converted c = r.converted;
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
        clock.post(null, 0, () -> naviInfo(r, ni, passed, time, traffic));
        if (dnpSwitch) oddDistance();                                // DnpOddDistanceController.e()
    }

    private void naviInfo(Route r, int[] ni, double passed, double timeToFinish, double trafficSpeed) {
        RouteConverter.Converted c = r.converted;
        int seg = ni[0], link = ni[1], linkRemain = ni[3], segRemain = ni[4], routeRemain = ni[5], remainLights = ni[6];
        StockPath.Segment segment = c.path.segments.get(seg);
        StockPath.Link l = segment.links.get(link);
        double[] light = fontRoundLight(c, ni[2], linkRemain, remainLights);
        if (niPathId != r.pathId()) scheduleAheadLanes();          // J0(pathInfo)
        hasMixFork = l.hasMixFork ? 1 : 2;
        hasParallel = l.hasParallel ? 1 : 2;
        int main = l.mainAction;                                    // (the segment fallback result is discarded in stock)
        int assistant = l.assistantAction != 0 ? l.assistantAction : segment.assistantAction;
        int cross = StockRouteJson.displayAction(segment.mainAction, segment.assistantAction);
        actDistance = segRemain;
        icon = cross; actMain = main; actAssist = assistant;
        laneDist = 0;                                               // FontRoundBean.dstLinkDistance: no lane info link
        niSegDist = segRemain; niPathId = r.pathId(); niSegIdx = seg; niLinkIdx = link; niLinkRemain = linkRemain;
        distToDest = routeRemain;
        timeToDest = Double.isNaN(timeToFinish) ? 0 : (int) Math.max(0, RouteConverter.round(timeToFinish));
        solidLaneDistance();
        setLightLocation(0, light[0], light[1]);
        historySpeed = Double.isNaN(trafficSpeed) ? 0 : RouteConverter.round(trafficSpeed * 3.6);
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
            cameraShown = true;
            int distance = RouteConverter.round(next[0] - passed);
            cameraType = 7;
            cameraDistance = distance;
            f0ActionCamera();
            eyeSpeed = (int) next[1];
            eyeDist = distance;
        } else if (cameraShown) {
            cameraShown = false;
            eyeSpeed = 0; eyeDist = 0;                               // reSetCameraSpeedParam
            iSpeed = 0; giStart = 0; giEnd = 0; iStartLon = iStartLat = iEndLon = iEndLat = 0;   // reSetIntervalCameraSpeed
            icon = 0; actMain = 0; actAssist = 0; actDistance = -1; cameraType = 0; cameraDistance = -1;
        }
    }

    /** SpeedIntersectionInformationParam.y(): no long solid lane from Yandex, so the reset branch. */
    private void solidLaneDistance() {
        solidDistance = -1;
    }

    private void scheduleAheadLanes() {
        // J0 -> b.run: query lanes per segment within 3 km, F() 2 s later sends at most six; repeats 10 s.
        // Yandex lane signs are not mapped to Amap LaneInfo, so the query returns none: "[]".
        clock.cancel(tokenLanes);
        clock.post(tokenLanes, 2000, new Runnable() {
            @Override public void run() {
                if (!naving) return;
                fire(Topics.SD_AHEAD_LANES, StockMessages.aheadLanes("[]"));
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
        Map<String, Object> f = Json.object();
        f.put("ageMs", fix.ageMs);
        f.put("speedMps", fix.speedMps);
        f.put("simulated", fix.simulated);
        f.put("raw", !Double.isNaN(fix.rawLon));
        f.put("vehicleSpeedSource", fix.vehicleSpeedKmh == null ? "gnss" : "vehicle");
        publisher.note("location", f, clock.now());
        if (fix.simulated && !config.acceptSimulatedLocations) return;
        int gpsSpeed = (int) (fix.speedMps * 3.6);
        int realSpeed = fix.vehicleSpeedKmh != null ? (int) (double) fix.vehicleSpeedKmh : gpsSpeed;
        if (realSpeed != lastVehicleSpeed) {                         // BydAutoSpeed listener -> f.g(speed)
            lastVehicleSpeed = realSpeed;
            vehicleSpeed(realSpeed);
        }
        clock.post(null, 0, () -> location(fix, gpsSpeed, realSpeed));
    }

    private void location(Fix fix, int gpsSpeed, int realSpeed) {
        double[] matched = Coordinates.toSdk(fix.matchedLon, fix.matchedLat, config.frame);
        double[] raw = Double.isNaN(fix.rawLon) ? matched : Coordinates.toSdk(fix.rawLon, fix.rawLat, config.frame);
        double rLon = Coordinates.grid(matched[0]), rLat = Coordinates.grid(matched[1]);
        double gLon = Coordinates.grid(raw[0]), gLat = Coordinates.grid(raw[1]);
        int course = Double.isNaN(fix.heading) ? 0 : (int) fix.heading;
        niOwnership = StockPath.OWNERSHIP_UNKNOWN;
        roadClass = StockPath.ROAD_CLASS_NULL;
        if (throttle()) {
            sendRegion();
            weather = 0;
            locationBundle(gpsSpeed, realSpeed, gLon, gLat, rLon, rLat, course);
            offCourse = false;
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
        if (realSpeed == 0 || gpsSpeed == 0) {
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

    /** GaoDeSignalManager.onCurrentRoadSpeed: the posted limit in km/h. */
    public void onCurrentRoadSpeed(int kmh) {
        int value = config.roadSpeedLimit ? Math.max(kmh, 0) : 0;
        clock.post(null, 0, () -> roadSpeed = value);
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
    }

    // ------------------------------------------------------------------ resets (z(), b0(), d0())

    private void resetAll() {
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
    private void fire(long topic, byte[] payload) {
        boolean routeOrSelection = topic == Topics.ROUTE || topic == Topics.PATH_SELECTED;
        if (sleeping && !routeOrSelection) return;
        publisher.fire(topic, payload, clock.now());
        lastFire = clock.now();
        if (routeOrSelection) publishStatus();
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
        Snapshot s = snapshot;
        // lastSend moves every second; the snapshot carries the newest without a listener storm.
        return lastFire == s.lastSendMonotonicMs ? s : new Snapshot(s.mode, lastFire, s.currentPathId, s.routeActive);
    }

    @Override public void addListener(Listener listener) { listeners.add(listener); }
    @Override public void removeListener(Listener listener) { listeners.remove(listener); }

    /** For tests. */
    List<Object> debugState() {
        List<Object> s = new ArrayList<>();
        s.add(mapState); s.add(statusLoopCreated); s.add(selectPathId); s.add(requestId); s.add(dnpSwitchRaw); s.add(noSleep);
        s.add(laneSeg); s.add(laneLink); s.add(laneDist); s.add(giOdd); s.add(inTunnel); s.add(lightDistance); s.add(flowSpeed);
        return s;
    }
}
