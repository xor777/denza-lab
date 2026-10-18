package dev.denza.hud.speed;

import android.content.Context;
import android.os.*;
import android.util.Log;
import dev.denza.hudlink.HudHostClient;
import dev.denza.hudlink.HudRadarWarning;

/** Embedded service hooks. Host-only: no SOME/IP, overlay, database or purchase changes. */
public final class HudSpeedModule {
    private static final String TAG = "DenzaHudSpeed";
    private static final HandlerThread THREAD = new HandlerThread("hud-speed-link");
    private static final Handler WORK;
    static { THREAD.start(); WORK = new Handler(THREAD.getLooper()); }
    private static Context owner; // Hook thread; guarded by the class monitor.
    private static HudSpeedModule current; // Worker only.
    private static boolean readFailureLogged;
    private final HudHostClient host;
    private HudRadarWarning latest;
    private long attemptedAt = -5_000;
    private boolean attached;
    private boolean stopped;
    private final Runnable tick = this::tick;

    public static synchronized void start(Context service) {
        try {
            owner = service;
            readFailureLogged = false;
            WORK.post(() -> {
                if (current != null) current.close();
                current = new HudSpeedModule(service);
            });
        } catch (Throwable failure) { Log.w(TAG, "adapter start failed", failure); }
    }

    /** Called at entry: no register assumptions about the original method's return paths. */
    public static synchronized void update(Context service) {
        try {
            if (owner != service) return;
            long now = SystemClock.elapsedRealtime();
            HudRadarWarning observation;
            try {
                Class<?> helpers = Class.forName("g.i", false, service.getClassLoader());
                observation = HudSpeedSource.read(service, new HudSpeedSource.Labels() {
                    public String title(int type) throws Exception {
                        return (String) helpers.getMethod("y", Context.class, int.class).invoke(null, service, type);
                    }
                    public int distance(double a, double b, double c, double d) throws Exception {
                        return ((Number) helpers.getMethod("x", double.class, double.class, double.class, double.class)
                                .invoke(null, a, b, c, d)).intValue();
                    }
                    public String[] art(int type) throws Exception {
                        // g.i.w(type): b the tile, e the frame with a limit, d the frame without.
                        Object info = helpers.getMethod("w", int.class).invoke(null, type);
                        if (info == null) return null;
                        return new String[] {(String) info.getClass().getField("b").get(info),
                                (String) info.getClass().getField("e").get(info),
                                (String) info.getClass().getField("d").get(info)};
                    }
                }, now);
            } catch (Throwable failure) {
                if (!readFailureLogged) Log.w(TAG, "snapshot failed; clearing", failure);
                readFailureLogged = true;
                observation = HudRadarWarning.clear(now);
            }
            final HudRadarWarning snapshot = observation;
            WORK.post(() -> { if (current != null) current.publish(snapshot); });
        } catch (Throwable failure) { Log.w(TAG, "adapter update failed", failure); }
    }

    public static synchronized void stop(Context service) {
        try {
            if (owner != service) return;
            owner = null;
            WORK.post(() -> {
                if (current != null) current.close();
                current = null;
            });
        } catch (Throwable failure) { Log.w(TAG, "adapter stop failed", failure); }
    }

    private HudSpeedModule(Context context) {
        host = new HudHostClient(context, WORK, TAG, new HudHostClient.Listener() {
            public void onAttached() {
                attached = true;
                if (latest != null && latest.remainingMs(SystemClock.elapsedRealtime()) > 0) send(latest);
            }
            public void onLost() { attached = false; }
        });
        tick();
    }

    private void publish(HudRadarWarning warning) {
        latest = warning;
        if (attached) send(warning);
    }

    private void send(HudRadarWarning warning) {
        if (!host.sendRadar(warning)) {
            attached = false;
            host.disconnect();
        }
    }

    private void tick() {
        if (stopped) return;
        long now = SystemClock.elapsedRealtime();
        if (!attached && now - attemptedAt >= 5_000) {
            host.disconnect(); // Also bounds a binding that never answers.
            attemptedAt = now;
            host.connect();
        }
        if (latest != null && latest.active && latest.remainingMs(now) == 0) {
            latest = HudRadarWarning.idle(now, latest.gpsLevel);
            if (attached) send(latest);
        } else if (latest != null && !latest.active && latest.running && now - latest.sampledAt >= 1_000) {
            // The GPS bars stand between the engine's events; a warning is never re-stamped.
            latest = latest.resampled(now);
            if (attached) send(latest);
        }
        WORK.postDelayed(tick, 500);
    }

    private void close() {
        stopped = true;
        WORK.removeCallbacks(tick);
        host.disconnect(); // Authenticated detach withdraws everything immediately.
        attached = false;
        latest = null;
    }
}
