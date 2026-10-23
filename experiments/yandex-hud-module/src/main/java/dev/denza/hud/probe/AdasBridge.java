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
import dev.denza.adaslink.Coordinates;
import dev.denza.adaslink.DryRunRecorder;
import dev.denza.adaslink.Json;
import dev.denza.adaslink.NavigatorDriver;
import dev.denza.adaslink.RoadStore;
import dev.denza.adaslink.StockFeed;
import dev.denza.adaslink.YandexRoute;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Host of the map-to-ADAS bridge inside Navigator (libraries/adas-link). On the main thread it
 * looks at Navigator once a module tick - route, route status, the classified location with its
 * class, the route position, the posted limit - and copies the route only when it changes; on its
 * own thread the {@link NavigatorDriver} turns that into the stock map's events and the
 * {@link StockFeed} into the messages the stock map would fire. They go to a {@link DryRunRecorder}
 * (files/denza-adas-dry-run.jsonl, two 8 MiB files): this build never fires anything at the car.
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
    private boolean failed;
    private String copiedRoute;
    private long copiedAt;
    private int copies;
    private volatile Location raw;

    private AdasBridge(Context context) throws IOException {
        this.context = context.getApplicationContext();
        HandlerThread thread = new HandlerThread("denza-adas", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        worker = new Handler(thread.getLooper());
        AndroidScheduler scheduler = new AndroidScheduler(worker);
        StockFeed.Config config = new StockFeed.Config();
        config.frame = Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE;
        // Navigator's route simulation is the only way to drive the emulator; the stock map would skip it.
        config.acceptSimulatedLocations = emulator;
        // Nothing reaches ADAS in a dry run, so its answer is emulated as the car gave it (about 110 ms).
        config.emulatedAckDelayMs = 110;
        DryRunRecorder recorder = new DryRunRecorder(new RotatingWriter(new File(this.context.getFilesDir(), "denza-adas-dry-run.jsonl")), false);
        feed = new StockFeed(scheduler, recorder, config);
        driver = new NavigatorDriver(feed, config.frame, scheduler);
        worker.post(() -> {
            Map<String, Object> f = Json.object();
            f.put("gatesAssumed", "dnpService, dnpSwitch, noSleep on: Navigator cannot read BYDAuto");
            f.put("emulator", emulator);
            f.put("roads", loadRoads());
            recorder.note("state", f, SystemClock.elapsedRealtime());
            feed.start(true, true, true);
        });
        AdasFeed.install(() -> {
            BridgeStatus.Snapshot s = feed.status();
            return new AdasFeed.Snapshot(AdasFeed.Mode.valueOf(s.mode.name()), s.lastSendMonotonicMs, s.currentPathId, s.routeActive);
        });
        listenRaw();
        Log.i(TAG, "ADAS bridge running: dry-run trace " + new File(this.context.getFilesDir(), "denza-adas-dry-run.jsonl"));
    }

    /** The file tools/adas_bridge/road_store.py builds from our OSM map, pushed next to Navigator's files. */
    static final String ROADS = "denza-adas-roads.bin";

    /**
     * Bridge thread: map our OSM road store, when one was put in Navigator's files (internal, else
     * external), and hand it to the driver: routes are then cut at its junctions and carry its
     * attributes where they match it (RoadMatcher). Without it the bridge uses Yandex data only.
     */
    private String loadRoads() {
        File[] places = {new File(context.getFilesDir(), ROADS),
                context.getExternalFilesDir(null) == null ? null : new File(context.getExternalFilesDir(null), ROADS)};
        for (File file : places) {
            if (file == null || !file.isFile()) continue;
            try (FileInputStream in = new FileInputStream(file); FileChannel channel = in.getChannel()) {
                MappedByteBuffer map = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
                RoadStore store = new RoadStore(map);
                driver.setRoads(store);
                String said = file + ": " + store.fragmentCount() + " roads, " + channel.size() + " bytes";
                Log.i(TAG, "OSM roads " + said);
                return said;
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "OSM roads not loaded from " + file, error);
                return "failed: " + error;
            }
        }
        return "none";
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
            o.finished = status.equals("ROUTE_FINISHED");
            o.mode = status.equals("NOT_ON_ROUTE") || status.equals("ROUTE_LOST") ? "rerouting" : "guidance";
            o.positionOnRoute = !o.mode.equals("rerouting");
            // The full copy on the main thread only when the route changes, and once more ten
            // seconds later (conditions populate after the first result); conversion runs off it.
            // MapKit hands out a new wrapper object on every read: the route id is the identity.
            if (!o.routeId.equals(copiedRoute)) { copiedRoute = o.routeId; copies = 0; }
            if (copies == 0 || (copies == 1 && now - copiedAt >= 10_000)) {
                copy = new RouteSnapshot(api).read(route);
                copies++;
                copiedAt = now;
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
        Object classified = api.call(guidance, "getLocation");
        Object location = classified == null ? null : api.call(classified, "getLocation");
        if (location != null) {
            o.locationClass = String.valueOf(api.call(classified, "getLocationClass"));
            Object point = api.call(location, "getPosition");
            o.lon = (Double) api.call(point, "getLongitude");
            o.lat = (Double) api.call(point, "getLatitude");
            Object speed = api.call(location, "getSpeed"), heading = api.call(location, "getHeading");
            o.speedMps = speed == null ? Double.NaN : ((Number) speed).doubleValue();
            o.heading = heading == null ? Double.NaN : ((Number) heading).doubleValue();
            Object stamp = api.call(location, "getAbsoluteTimestamp"), relative = api.call(location, "getRelativeTimestamp");
            if (stamp instanceof Number) o.stamp = ((Number) stamp).longValue();
            // Relative timestamps are elapsedRealtime milliseconds: the fix's own age, not the read's.
            if (relative instanceof Number) o.fixAgeMs = now - ((Number) relative).longValue();
        }
        Location gps = raw;
        if (gps != null && now - gps.getElapsedRealtimeNanos() / 1_000_000 <= 1500) { o.rawLon = gps.getLongitude(); o.rawLat = gps.getLatitude(); }
        final Map<String, Object> routeCopy = copy;
        worker.post(() -> {
            try {
                if (routeCopy != null) o.route = YandexRoute.fromSnapshot(routeCopy);
            } catch (RuntimeException error) {
                Log.w(TAG, "route copy rejected", error);
            }
            try { driver.observe(o); }
            catch (RuntimeException error) { Log.w(TAG, "ADAS bridge step failed", error); }
        });
    }

    /** Line writer that keeps at most two files of {@link #LIMIT} bytes. Bridge thread only. */
    private static final class RotatingWriter extends Writer {
        static final long LIMIT = 8L << 20;
        private final File file;
        private Writer out;
        private long size;
        RotatingWriter(File file) throws IOException {
            this.file = file;
            open();
        }
        private void open() throws IOException {
            size = file.length();
            out = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8);
        }
        @Override public void write(char[] buffer, int offset, int length) throws IOException {
            out.write(buffer, offset, length);
            size += length;
        }
        @Override public void flush() throws IOException {
            out.flush();
            if (size >= LIMIT) {
                out.close();
                File old = new File(file.getPath() + ".1");
                if (old.exists() && !old.delete()) throw new IOException("cannot rotate " + old);
                if (!file.renameTo(old)) throw new IOException("cannot rotate " + file);
                open();
            }
        }
        @Override public void close() throws IOException { out.close(); }
    }
}
