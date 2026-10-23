package dev.denza.hud.probe;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Private-file acceptance harness, inert on the IVI. No exported receiver, socket, or vehicle command. */
final class HudEmulatorControl {
    private final boolean enabled = Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish");
    private final Context context;
    private final Reflect api;
    private long pollAt, themeAt, themeStamp, sequence, recorded, recordEvery, recordLimit;
    private String lastCommand = "";
    private String recordedRoute;
    private String attemptedRoute;
    private long routeAttemptAt = -10_000, recordedRouteAt = -10_000, identityFailureAt = -10_000;
    private Object identityFailureRoute, recordedObject, attemptedObject;
    /** Serialization and the fsync'd write of the route copy run here, never on Navigator's main thread. */
    private android.os.Handler writer;
    private volatile boolean writing, writeFailed;
    private boolean iconSheet;
    HudEmulatorControl(Context context, Reflect api) { this.context = context; this.api = api; }

    void poll(Object guidance, long now) {
        if (!enabled || now < pollAt) return;
        pollAt = now + 500;
        File command = new File(context.getFilesDir(), "denza-hud-command.json");
        if (!command.isFile()) return;
        try {
            byte[] bytes = new byte[(int) Math.min(4096, command.length())];
            try (FileInputStream in = new FileInputStream(command)) {
                if (in.read(bytes) != bytes.length) return;
            }
            String raw = new String(bytes, StandardCharsets.UTF_8);
            if (raw.equals(lastCommand)) return;
            lastCommand = raw;
            JSONObject value = new JSONObject(raw);
            if (!command.delete()) throw new IOException("Cannot consume emulator command");
            String action = value.getString("action");
            if (action.equals("simulate")) {
                if (api.call(guidance, "route") == null
                        || !Boolean.TRUE.equals(api.call(guidance, "isGuidanceResumed"))
                        || !api.call(guidance, "getSimulationStatus").toString().equals("IDLE"))
                    throw new IllegalStateException("Select and start a route; simulation must be idle");
                api.call(guidance, "startSimulationWithExistingRoute");
            } else if (action.equals("stop")) {
                // stopSimulation() asserts natively when already idle. Cleanup must be idempotent.
                if (!api.call(guidance, "getSimulationStatus").toString().equals("IDLE"))
                    api.call(guidance, "stopSimulation");
                if (api.call(guidance, "route") != null) api.call(guidance, "stop");
            } else if (action.equals("speed")) {
                api.call(guidance, "setSimulatedSpeed", value.getDouble("metresPerSecond"));
            } else if (action.equals("record")) {
                // Design evidence along a whole simulated route: every Nth published frame, bounded.
                recordEvery = Math.max(1, value.getInt("every"));
                recordLimit = Math.min(600, value.getInt("limit"));
                recorded = 0;
            } else if (action.equals("icons")) {
                iconSheet = true; // written by the next frame that has a painter
            } else if (action.equals("background")) {
                api.call(api.call(guidance, "bgConfigurator"), "setBackgroundOnRouteEnabled", value.getBoolean("enabled"));
            } else throw new IllegalArgumentException("Unknown emulator action");
            Log.i("DenzaYandexHud", "emulator action=" + action);
        } catch (Exception error) { Log.w("DenzaYandexHud", "emulator action failed", error); }
    }

    /** Emulator check of the real Android drawing: every icon and every posted limit, one PNG. */
    void icons(HudPainter painter) {
        if (!enabled || !iconSheet || painter == null) return;
        iconSheet = false;
        try {
            String[] kinds = {"camera-limit", "camera", "closed", "bump", "railway", "accident", "works", "school", "police", "danger",
                    "lane-camera", "crossroad-camera", "no-stopping-camera", "mobile-camera"};
            int[] limits = {5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130};
            Bitmap sheet = painter.sheet(kinds, limits);
            try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "denza-hud-icons.png"))) {
                sheet.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally { sheet.recycle(); }
            Log.i("DenzaYandexHud", "emulator icons written");
        } catch (Exception error) { Log.w("DenzaYandexHud", "emulator icons failed", error); }
    }

    /** Emulator design loop: a pushed theme replaces the APK asset without rebuilding; null when unchanged. */
    JSONObject theme(long now) {
        if (!enabled || now < themeAt) return null;
        themeAt = now + 500;
        File file = new File(context.getFilesDir(), "denza-hud-theme.json");
        if (!file.isFile() || file.lastModified() == themeStamp) return null;
        themeStamp = file.lastModified();
        try {
            byte[] bytes = new byte[(int) Math.min(1 << 20, file.length())];
            try (FileInputStream in = new FileInputStream(file)) {
                if (in.read(bytes) != bytes.length) return null;
            }
            JSONObject theme = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            Log.i("DenzaYandexHud", "emulator theme loaded");
            return theme;
        } catch (Exception error) { Log.w("DenzaYandexHud", "emulator theme rejected", error); return null; }
    }

    /** Rich diagnostic route copy; routeId/points retain the existing AR evidence contract. Emulator only. */
    void route(Object route) {
        if (!enabled || route == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (route == identityFailureRoute && now - identityFailureAt < 10_000) return;
        String id;
        try {
            id = (String) api.call(route, "getRouteId");
            if (id == null) throw new IllegalStateException("Missing route identity");
            identityFailureRoute = null;
        } catch (Exception error) {
            identityFailureRoute = route; identityFailureAt = now;
            Log.w("DenzaYandexHud", "native route identity failed", error);
            return;
        }
        // A new route id is copied at once; the same route is refreshed at most once a
        // minute (conditions may populate after the first result), a failed attempt after ten seconds.
        // MapKit hands out a new wrapper object on every read: the route id is the identity.
        boolean changed = !id.equals(recordedRoute) || writeFailed;
        if (!changed && now - recordedRouteAt < 60_000) return;
        if (id.equals(attemptedRoute) && now - routeAttemptAt < 10_000) return;
        if (writing) return; // one write in flight; the next tick tries again
        attemptedRoute = id; attemptedObject = route; routeAttemptAt = now;
        try {
            long wallAt = System.currentTimeMillis();
            // The MapKit tree must be copied on the main thread; that copy is all that runs here.
            final java.util.Map<String, Object> reference = new RouteSnapshot(api).read(route);
            final long copied = android.os.SystemClock.elapsedRealtime();
            reference.put("sourceSessionId", NavigationEvidence.SOURCE_SESSION_ID);
            reference.put("capturedAtUnixMs", wallAt);
            reference.put("capturedAtElapsedRealtimeMs", now);
            reference.put("readDurationMs", copied - now);
            recordedObject = route;
            recordedRoute = id;
            recordedRouteAt = copied;
            if (writer == null) {
                android.os.HandlerThread thread = new android.os.HandlerThread("denza-hud-evidence", android.os.Process.THREAD_PRIORITY_BACKGROUND);
                thread.start();
                writer = new android.os.Handler(thread.getLooper());
            }
            writing = true;
            writeFailed = false;
            writer.post(() -> {
                try {
                    long begin = android.os.SystemClock.elapsedRealtime();
                    String json = RouteSnapshot.json(reference);
                    long serialized = android.os.SystemClock.elapsedRealtime();
                    RouteSnapshot.writeAtomic(new File(context.getFilesDir(), "denza-hud-native-route.json"), json);
                    Log.i("DenzaYandexHud", "route evidence copy=" + (copied - now) + "ms serialize=" + (serialized - begin)
                            + "ms write=" + (android.os.SystemClock.elapsedRealtime() - serialized) + "ms json=" + json.length());
                } catch (Exception error) {
                    writeFailed = true; // retried after ten seconds, as before
                    Log.w("DenzaYandexHud", "native route evidence failed", error);
                }
                finally { writing = false; }
            });
        } catch (Exception error) { Log.w("DenzaYandexHud", "native route evidence failed", error); }
    }

    void frame(byte[] png, String mode, boolean visible, boolean background, boolean resumed, HudAr.Frame ar, int geometryBuilds) {
        if (!enabled) return;
        try {
            if (recordLimit > 0 && mode.equals("guidance") && sequence % recordEvery == 0 && recorded < recordLimit) {
                File record = new File(context.getFilesDir(), "denza-hud-record");
                if (record.isDirectory() || record.mkdir())
                    try (FileOutputStream out = new FileOutputStream(new File(record, String.format("frame-%04d.png", recorded++)))) { out.write(png); }
            }
            File directory = new File(context.getFilesDir(), "denza-hud-evidence");
            if (!directory.isDirectory() && !directory.mkdir()) return;
            // Bounded 60-frame ring for emulator acceptance; never stores locations or routes in logs.
            long index = sequence++;
            try (FileOutputStream out = new FileOutputStream(new File(directory, String.format("frame-%02d.png", index % 60)))) { out.write(png); }
            JSONObject info = new JSONObject().put("sequence", index).put("mode", mode).put("visible", visible)
                    .put("background", background).put("resumed", resumed).put("uptime", android.os.SystemClock.elapsedRealtime())
                    .put("ar", ar != null).put("arGeometryBuilds", geometryBuilds);
            try (FileOutputStream out = new FileOutputStream(new File(directory, "latest.json"))) { out.write(info.toString().getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception error) { Log.w("DenzaYandexHud", "emulator evidence failed", error); }
    }
}
