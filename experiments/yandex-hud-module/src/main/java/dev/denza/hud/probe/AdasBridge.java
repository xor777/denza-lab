package dev.denza.hud.probe;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import dev.denza.adaslink.AndroidScheduler;
import dev.denza.adaslink.BridgeStatus;
import dev.denza.adaslink.FieldDefaults;
import dev.denza.adaslink.DryRunRecorder;
import dev.denza.adaslink.FixFreshness;
import dev.denza.adaslink.Json;
import dev.denza.adaslink.NavigatorDriver;
import dev.denza.adaslink.GraphRoadNetwork;
import dev.denza.adaslink.GraphTileCache;
import dev.denza.adaslink.VectorRoadTile;
import dev.denza.adaslink.VectorRoadTileCache;
import dev.denza.adaslink.StockFeed;
import dev.denza.adaslink.YandexRoute;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Collections;

/**
 * Host of the map-to-ADAS bridge inside Navigator (libraries/adas-link). On the main thread it
 * looks at Navigator once a module tick - route, route status, the classified location with its
 * class, the route position, the posted limit - and periodically copies bounded route conditions; on its
 * own thread the {@link NavigatorDriver} turns that into the stock map's events and the
 * {@link StockFeed} into the messages the stock map would fire. They go to a {@link DryRunRecorder}
 * (files/denza-adas-dry-run.jsonl, five 8 MiB files). A separate, explicitly armed
 * {@link LiveMapBridge} uses real gates and acknowledgements for actual publication.
 *
 * <p>DNP service, DNP switch and NOA no-sleep come from BYDAuto on the stock map; Navigator cannot
 * read them, so the dry run assumes all three on (said in the trace header). Raw GNSS for the
 * position's GPS slot comes from a passive listener: no GPS request of our own.
 */
final class AdasBridge {
    static final String TAG = "DenzaAdasBridge";
    private static AdasBridge instance;

    /** The overlay's view of the bridge; DISABLED until the bridge runs. */
    static BridgeStatus status() {
        AdasBridge bridge = instance;
        return bridge == null ? null : bridge.feed;
    }

    /** One module tick (main thread): starts the bridge on first use, then samples Navigator. */
    static void sample(Context context, Reflect api, Object guidance, NavigatorSource.Frame frame) {
        try {
            if (instance == null) instance = new AdasBridge(context);
            instance.look(api, guidance, frame);
        } catch (Throwable error) {
            if (instance == null || !instance.failed) Log.w(TAG, "ADAS bridge sample failed", error);
            if (instance != null) instance.failed = true;
        }
    }

    private final Context context;
    private final boolean emulator = Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish");
    private final Handler worker;
    private final StockFeed feed;
    private final NavigatorDriver driver;
    private final LiveMapBridge live;
    private final YandexOfflineMapSource offlineMaps;
    private final YandexOfflineCachePath offlinePath = new YandexOfflineCachePath();
    private final YandexOfflineAttributes offlineAttributes = new YandexOfflineAttributes();
    private String attributesAppliedKey;
    private long offlineReportedAt;
    private final FixFreshness.Tracker fixes = new FixFreshness.Tracker();
    private boolean failed;
    private final RouteSnapshot.Refresh routeRefresh = new RouteSnapshot.Refresh();
    private volatile Location raw;
    private YandexRoute graphRoute;
    private GraphRoadNetwork currentRoads;
    private long graphGeneration = -1, graphAppliedAt;
    private long vectorGeneration = -1;

    private AdasBridge(Context context) throws IOException {
        this.context = context.getApplicationContext();
        // The dry run, route copies, conversion and the graph: background work. Live sends have their own thread.
        HandlerThread thread = new HandlerThread("denza-adas", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        worker = new Handler(thread.getLooper());
        AndroidScheduler scheduler = new AndroidScheduler(worker);
        // Frame, legal limit and facilities come from the one named place (FieldDefaults).
        StockFeed.Config config = FieldDefaults.navigator(new StockFeed.Config());
        // Navigator's route simulation is the only way to drive the emulator; the stock map would skip it.
        config.acceptSimulatedLocations = emulator;
        // Nothing reaches ADAS in a dry run, so its answer is emulated as the car gave it (about 110 ms).
        config.emulatedAckDelayMs = 110;
        DryRunRecorder recorder = new DryRunRecorder(new RotatingWriter(new File(this.context.getFilesDir(), "denza-adas-dry-run.jsonl"), DRY_RUN_GENERATIONS), false);
        feed = new StockFeed(scheduler, recorder, config);
        driver = new NavigatorDriver(feed, config.frame, scheduler);
        // Actual sends on their own normal-priority thread: conversion, graph work and the dry run stay here.
        HandlerThread sender = new HandlerThread("denza-adas-live", android.os.Process.THREAD_PRIORITY_DEFAULT);
        sender.start();
        live = new LiveMapBridge(this.context, new Handler(sender.getLooper()));
        offlineMaps = new YandexOfflineMapSource(live);
        worker.post(() -> {
            Map<String, Object> f = Json.object();
            f.put("gatesAssumed", "dnpService, dnpSwitch, noSleep on: Navigator cannot read BYDAuto");
            f.put("emulator", emulator);
            f.put("roads", "yandex-native-online");
            f.put("coordinateFrame",config.frame.name());
            recorder.note("state", f, SystemClock.elapsedRealtime());
            feed.start(true, true, true);
        });
        // The overlay shows only actual transmission; the independent dry run stays in its log.
        AdasFeed.install(live::status);
        listenRaw();
        Log.i(TAG, "ADAS bridge running: dry-run trace " + new File(this.context.getFilesDir(), "denza-adas-dry-run.jsonl"));
    }

    private void listenRaw() {
        try {
            LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (manager == null || !manager.getAllProviders().contains(LocationManager.PASSIVE_PROVIDER)) return;
            manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0, 0, new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    if (LocationManager.GPS_PROVIDER.equals(location.getProvider())) raw = location;
                }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
                @Override public void onProviderEnabled(String provider) { }
                @Override public void onProviderDisabled(String provider) { }
            }, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException error) {
            Log.w(TAG, "no passive GNSS: the GPS slot carries Navigator's location", error);
        }
    }

    /** Main thread: one look at Navigator, posted to the bridge thread as an immutable observation. */
    private void look(Reflect api, Object guidance, NavigatorSource.Frame frame) {
        long now = SystemClock.elapsedRealtime();
        final NavigatorDriver.Observation o = new NavigatorDriver.Observation();
        o.monotonicMs = now;
        Map<String, Object> copy = null;
        Object route = frame.route;
        if (route != null && frame.resumed && Boolean.TRUE.equals(api.call(guidance, "isValid"))) {
            // Route status only once a route exists and guidance is resumed (NavigatorSource's rule).
            String status = String.valueOf(api.call(api.call(api.call(guidance, "navigation"), "getGuidance"), "getRouteStatus"));
            o.routeId = (String) api.call(route, "getRouteId");
            offlineMaps.routeIdentity(o.routeId, routeRefresh.route(o.routeId));
            o.finished = status.equals("ROUTE_FINISHED");
            o.mode = status.equals("NOT_ON_ROUTE") || status.equals("ROUTE_LOST") ? "rerouting" : "guidance";
            o.positionOnRoute = !o.mode.equals("rerouting");
            // Native listener registration would require another JNI callback ABI. Passive bounded
            // polling observes SDK updates without requesting network updates or adding a Proxy.
            Map<String,Object> capture=routeRefresh.begin(o.routeId,now);
            if (capture!=null) {
                try {
                    copy = new RouteSnapshot(api).read(route);
                    RouteSnapshot.captured(copy,capture,SystemClock.elapsedRealtime(),System.currentTimeMillis());
                } catch(RuntimeException error) {
                    offlineMaps.geometryUnavailable(o.routeId, routeRefresh.route(o.routeId));
                    Log.w(TAG,"conditions copy rejected",error);
                }
            }
            Object position = api.call(guidance, "getRoutePosition");
            if (position != null) o.scalar = ((Number) api.call(position, "getSegmentIndex")).doubleValue()
                    + ((Number) api.call(position, "getSegmentPosition")).doubleValue();
            Object here = api.call(route, "getRoutePosition");
            if (here != null) o.timeToFinishS = ((Number) api.call(here, "timeToFinish")).doubleValue();
            Object limit = api.call(api.call(api.call(guidance, "navigation"), "getGuidance"), "getSpeedLimit");
            if (limit != null) o.speedLimitMps = ((Number) api.call(limit, "getValue")).doubleValue();
            o.simulated = !String.valueOf(api.call(guidance, "getSimulationStatus")).equals("IDLE");
        }
        o.routeEpoch=routeRefresh.route(o.routeId);
        // Bind installed-map admission to the current route before either queue can consume it.
        offlineMaps.sample(api, o.routeId, o.routeEpoch, copy, now);
        o.offlineGeometrySha256 = offlineMaps.geometrySha256();
        offlineAttributes.sample(o.routeId, o.routeEpoch, o.offlineGeometrySha256,
                offlinePath.sample(api, now), offlineMaps.inventorySnapshot());
        final Map<String,Object> offlineStatus;
        if (now - offlineReportedAt >= 5000) {
            offlineReportedAt = now; offlineStatus = offlineMaps.status();
        } else { offlineStatus = null; }
        Object classified = api.call(guidance, "getLocation");
        Object location = classified == null ? null : api.call(classified, "getLocation");
        FixFreshness.Sample sampledFix = FixFreshness.capture(-1, -1, -1, -1, -1);
        if (location != null) {
            o.locationClass = String.valueOf(api.call(classified, "getLocationClass"));
            Object point = api.call(location, "getPosition");
            o.lon = (Double) api.call(point, "getLongitude");
            o.lat = (Double) api.call(point, "getLatitude");
            Object speed = api.call(location, "getSpeed"), heading = api.call(location, "getHeading");
            o.speedMps = speed == null ? Double.NaN : ((Number) speed).doubleValue();
            o.heading = heading == null ? Double.NaN : ((Number) heading).doubleValue();
            Object stamp = api.call(location, "getAbsoluteTimestamp"), relative = api.call(location, "getRelativeTimestamp");
            // The source monotonic identity orders fixes independently of changes to wall time.
            if (relative instanceof Number && ((Number) relative).longValue() >= 0)
                o.stamp = ((Number) relative).longValue();
            if ("FINE".equals(o.locationClass) && stamp instanceof Number && relative instanceof Number) {
                // Read clocks after the location; a newer fix must not look future due to read latency.
                long elapsed = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
                sampledFix = fixes.observe(((Number) relative).longValue(), ((Number) stamp).longValue(),
                        SystemClock.uptimeMillis(), elapsed, wall);
            }
        }
        Location gps = raw;
        if (gps != null && now - gps.getElapsedRealtimeNanos() / 1_000_000 <= 1500) { o.rawLon = gps.getLongitude(); o.rawLat = gps.getLatitude(); }
        final Map<String, Object> routeCopy = copy;
        final FixFreshness.Sample fix = sampledFix;
        worker.post(() -> {
            // A route stop/replacement on the main thread invalidates already queued old copies.
            if(!routeRefresh.current(o.routeEpoch))return;
            Map<String,Object> graphSummary = null;
            try {
                if (routeCopy != null) {
                    o.route = YandexRoute.fromSnapshot(routeCopy);
                    if (graphRoute == null || !graphRoute.routeId.equals(o.route.routeId)) {
                        graphGeneration = -1; vectorGeneration = -1;
                    }
                    graphRoute = o.route;
                }
                if (o.routeId == null || o.mode.equals("off")) {
                    graphRoute = null; graphGeneration = -1; vectorGeneration = -1; currentRoads=null; driver.setRoads(null);
                    attributesAppliedKey = null;
                } else if (graphRoute != null && graphRoute.routeId.equals(o.routeId)) {
                    GraphTileCache.Snapshot graph = YandexGraphSource.snapshot();
                    VectorRoadTileCache.Snapshot vectors = YandexVectorSource.snapshot();
                    long observedVectorGeneration = vectors == null || vectors.released ? -1 : vectors.generation;
                    if (graph != null && !graph.released && (graph.generation != graphGeneration || observedVectorGeneration != vectorGeneration)
                            && (graphGeneration < 0 || o.monotonicMs-graphAppliedAt>=10000)) {
                        GraphRoadNetwork roads = new GraphRoadNetwork(graph.tiles, graphRoute,
                                vectors == null || vectors.released ? Collections.<VectorRoadTile>emptyList() : vectors.tiles);
                        currentRoads=roads.fragmentCount()==0 ? null : roads;
                        driver.setRoads(currentRoads);
                        graphGeneration=graph.generation; graphAppliedAt=o.monotonicMs;
                        vectorGeneration=observedVectorGeneration;
                        o.route=graphRoute;
                        graphSummary=roads.summary();
                        Map<String,Object> summary=graphSummary;
                        summary.put("routeId",graphRoute.routeId); summary.put("generation",graphGeneration);
                        summary.put("vectorGeneration",vectorGeneration);
                        summary.put("appliedUptimeMs",o.monotonicMs);

                    }
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "route copy rejected", error);
            }
            try {
                if(!routeRefresh.current(o.routeEpoch))return;
                YandexOfflineAttributes.View attributes = offlineAttributes.prepare(graphRoute);
                if (graphRoute != null && graphRoute.routeId.equals(o.routeId)) {
                    // Initial publication waits for the bounded acquisition attempt, never for
                    // an invented full-coverage percentage. Partial results keep explicit gaps.
                    if (!attributes.settled) o.route = null;
                    else {
                        graphRoute.additionalAttributes = attributes.attributes;
                        String key = o.routeEpoch + "|" + o.offlineGeometrySha256;
                        if (!key.equals(attributesAppliedKey)) { o.route = graphRoute; attributesAppliedKey = key; }
                    }
                } else o.route = null;
                // Include queue/conversion delay and suspend since the original, immutable anchor.
                o.monotonicMs = SystemClock.elapsedRealtime();
                o.fixAgeMs = fix.ageMs(o.monotonicMs, System.currentTimeMillis());
                driver.observe(o);
                live.observe(o,fix,currentRoads);
                if (offlineStatus != null)
                    RouteSnapshot.writeAtomic(new File(context.getFilesDir(), "denza-offline-map-status.json"), offlineStatus);
                if (offlineStatus != null)
                    RouteSnapshot.writeAtomic(new File(context.getFilesDir(), "denza-yandex-route-vector-status.json"), attributes.status);
                if(graphSummary!=null) {
                    graphSummary.put("conversion",driver.conversionSummary());
                    RouteSnapshot.writeAtomic(new File(context.getFilesDir(),"denza-yandex-graph-route.json"),graphSummary);
                }
            } catch (IOException | RuntimeException error) { Log.w(TAG, "ADAS bridge step failed", error); }
        });
    }

    /**
     * Ring sizes for a two-hour drive at the stock cadence, from the 2026-10-24 journals: the live
     * journal about 0.31 MiB a minute (38 MiB in two hours), the dry run about 0.2 (24 MiB). At least
     * {@code generations - 1} full files are always kept: live 40 MiB, dry run 32 MiB.
     */
    static final int LIVE_GENERATIONS = DriveEvidenceStreams.LIVE_GENERATIONS, DRY_RUN_GENERATIONS = DriveEvidenceStreams.DRY_RUN_GENERATIONS;

    /** {@code generations} files, rotated at LIMIT physical UTF-8 bytes plus one flush batch. Bridge thread only. */
    static final class RotatingWriter extends Writer {
        static final long LIMIT = 8L << 20;
        private final File file;
        private final int generations;
        private Writer out;
        private long size;
        RotatingWriter(File file) throws IOException { this(file, 2); }
        RotatingWriter(File file, int generations) throws IOException {
            if (generations < 2) throw new IllegalArgumentException("generations");
            this.file = file; this.generations = generations;
            open();
        }
        private void open() throws IOException {
            size = file.length();
            // Bytes are counted as they leave the encoder: no stat per record on the sending thread.
            out = new OutputStreamWriter(new java.io.FilterOutputStream(new FileOutputStream(file, true)) {
                @Override public void write(int b) throws IOException { out.write(b); size++; }
                @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); size += len; }
            }, StandardCharsets.UTF_8);
        }
        @Override public void write(char[] buffer, int offset, int length) throws IOException {
            out.write(buffer, offset, length);
        }
        @Override public void flush() throws IOException {
            out.flush();
            if (size >= LIMIT) {
                out.close();
                DriveEvidenceLog.rotate(file, generations);
                open();
            }
        }
        @Override public void close() throws IOException { out.close(); }
    }
}
