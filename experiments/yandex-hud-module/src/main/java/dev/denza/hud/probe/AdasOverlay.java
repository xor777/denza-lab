package dev.denza.hud.probe;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The ADAS status overlay inside Navigator: a View added to Navigator's own map window (no
 * SYSTEM_ALERT_WINDOW, no window of its own), fed by {@link AdasPassiveClient} and by our bridge's
 * {@link AdasFeed}, drawn by {@link AdasPainter} at the board's placement A (bottom right).
 *
 * <p>The listener runs while the map is visible and for half a minute after, then releases its
 * own subscriptions. Binder callbacks only enqueue (bounded); decoding happens on the overlay's
 * worker, the state and the drawing on the main thread.
 *
 * <p>Emulator only (ranchu/goldfish): {@code files/denza-adas-overlay.json} replaces the live
 * source while it exists - either a board state ({@code "state"}, as in boards.js STATES) or
 * synthetic SOME/IP observations run through the real decoder and rules ({@code "inject"}) - with
 * an optional black backdrop and a fixed anchor for comparing a screenshot with its board. Nothing
 * there can reach the car: the file is read only on emulator hardware and nothing is ever fired.
 */
final class AdasOverlay implements AdasPassiveClient.Sink {
    static final String TAG = "DenzaAdasOverlay";
    private static final String MAP_ACTIVITY = "ru.yandex.yandexmaps.app.MapActivity";
    private static final long LINGER_MS = 30000, TICK_MS = 250;
    private static AdasOverlay instance;
    private static volatile boolean navigating;

    /** Called once from HudModule.initialize, in Navigator's main process. */
    static void install(Application application) {
        if (instance != null) return;
        instance = new AdasOverlay(application);
        application.registerActivityLifecycleCallbacks(instance.lifecycle);
        Log.i(TAG, "installed");
    }

    /** Navigator is guiding a route: its right-hand toolbar is gone and the corner is lower right. */
    static void navigating(boolean value) { navigating = value; }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean emulator = Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish");
    private HandlerThread thread;
    private Handler worker;
    private final AtomicInteger pending = new AtomicInteger();
    private AdasPassiveClient client;
    /** Worker only. */
    private final AdasObservations model = new AdasObservations();
    private volatile AdasObservations published = new AdasObservations();
    private final List<Layer> layers = new ArrayList<>();
    private final java.util.Set<Activity> started = new java.util.HashSet<>();
    private long dropped;
    private String said = "";

    private AdasOverlay(Context context) { this.context = context.getApplicationContext(); }

    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        // Started, or resumed: the module may be installed while the map activity is already up.
        public void onActivityStarted(Activity activity) { shown(activity); }
        public void onActivityResumed(Activity activity) { shown(activity); }
        public void onActivityStopped(Activity activity) {
            if (!started.remove(activity)) return;
            if (started.isEmpty()) main.postDelayed(release, LINGER_MS);
        }
        public void onActivityDestroyed(Activity activity) {
            started.remove(activity);
            for (int i = layers.size() - 1; i >= 0; i--) if (layers.get(i).activity == activity) layers.remove(i).detach();
        }
        public void onActivityCreated(Activity activity, Bundle state) { }
        public void onActivityPaused(Activity activity) { }
        public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    };

    private void shown(Activity activity) {
        if (!activity.getClass().getName().equals(MAP_ACTIVITY)) return;
        started.add(activity);
        main.removeCallbacks(release);
        listen();
        attach(activity);
    }

    private void attach(Activity activity) {
        for (Layer layer : layers) if (layer.activity == activity) return;
        try {
            View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            Layer layer = new Layer(activity);
            ((ViewGroup) decor).addView(layer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            layers.add(layer);
        } catch (Throwable error) { Log.e(TAG, "overlay attach failed", error); }
    }

    /* --------------------------------------------------------------- the listener */

    private void listen() {
        if (client != null) return;
        if (thread == null) {
            thread = new HandlerThread("denza-adas-overlay", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        client = new AdasPassiveClient(context, this);
        client.start();
    }

    private final Runnable release = new Runnable() {
        public void run() {
            final AdasPassiveClient old = client;
            client = null;
            if (old == null) return;
            old.stopAdmission();
            worker.post(new Runnable() { public void run() {
                old.close();
                model.session(false, SystemClock.elapsedRealtime());
                published = model.copy();
            } });
        }
    };

    @Override public boolean enqueue(final Runnable task) {
        if (worker == null || pending.incrementAndGet() > 64) { pending.decrementAndGet(); dropped++; return false; }
        worker.post(new Runnable() { public void run() { pending.decrementAndGet(); task.run(); } });
        return true;
    }

    @Override public void state(String event, String detail) {
        long now = SystemClock.elapsedRealtime();
        if (event.equals("subscribed")) model.session(!detail.startsWith("0/"), now);
        else if (event.equals("binder_disconnected") || event.equals("null_binding") || event.equals("bind_failed")
                || event.equals("client_setup_error") || event.equals("closed") || event.equals("rebind_exhausted")
                || event.equals("hal_available") && detail.equals("false")) model.session(false, now);
        published = model.copy();
        Log.i(TAG, event + " " + detail);
    }

    @Override public void event(long topic, long timestamp, long receivedAt, byte[] payload) {
        try {
            model.apply(AdasWire.decode(topic, payload), receivedAt);
            published = model.copy();
        } catch (RuntimeException invalid) {
            String key = AdasPassiveClient.hex(topic) + ":" + invalid.getMessage();
            if (!key.equals(said)) { said = key; Log.w(TAG, "undecodable " + key); }
        }
    }

    /* ----------------------------------------------------------------- the view */

    /** What a screenshot of one board state needs: the state, a black backdrop, the board's anchor. */
    private static final class Fixture {
        AdasOverlayState state;
        JSONObject inject;
        boolean black;
        int[] anchor;
    }

    private long fixtureAt = -1, fixtureStamp = -1;
    private Fixture fixture;

    /** The emulator harness's file, re-read when it changes; null on the car and without the file. */
    private Fixture fixture(long now) {
        if (!emulator) return null;
        if (now - fixtureAt < 500) return fixture;
        fixtureAt = now;
        File file = new File(context.getFilesDir(), "denza-adas-overlay.json");
        if (!file.isFile()) { fixture = null; fixtureStamp = -1; return null; }
        if (file.lastModified() == fixtureStamp && fixture != null) return fixture;
        try {
            byte[] bytes = new byte[(int) Math.min(16384, file.length())];
            try (FileInputStream in = new FileInputStream(file)) { if (in.read(bytes) != bytes.length) return fixture; }
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            Fixture f = new Fixture();
            if (json.has("state")) f.state = boardState(json.getJSONObject("state"));
            f.inject = json.optJSONObject("inject");
            f.black = "black".equals(json.optString("backdrop"));
            JSONArray a = json.optJSONArray("anchor");
            if (a != null) f.anchor = new int[]{a.getInt(0), a.getInt(1)};
            fixture = f; fixtureStamp = file.lastModified();
            Log.i(TAG, "emulator fixture " + json);
        } catch (Exception error) { Log.w(TAG, "emulator fixture rejected", error); fixture = null; }
        return fixture;
    }

    /** A boards.js STATES entry as the overlay's state. */
    static AdasOverlayState boardState(JSONObject j) throws Exception {
        AdasOverlayState s = new AdasOverlayState();
        s.noa = fnOf(j.optString("noa", "off"));
        s.icc = fnOf(j.optString("icc", "off"));
        String mode = j.optString("mode", "");
        s.mode = mode.equals("city") ? AdasOverlayState.Mode.CITY : mode.equals("highway") ? AdasOverlayState.Mode.HIGHWAY : null;
        String route = j.optString("route", "");
        s.route = route.isEmpty() || route.equals("null") ? null : AdasOverlayState.Route.valueOf(route.toUpperCase());
        if (j.has("stale") && !j.isNull("stale")) { s.stale = true; s.staleSeconds = j.getInt("stale"); }
        String alarm = j.optString("alarm", "");
        s.alarm = alarm.equals("stock-empty") ? AdasOverlayState.Alarm.STOCK_EMPTY : alarm.equals("yandex-silent") ? AdasOverlayState.Alarm.YANDEX_SILENT : null;
        s.takeover = j.has("takeover") && !j.isNull("takeover") ? j.getString("takeover") : null;
        JSONArray d = j.optBoolean("expanded") ? j.optJSONArray("details") : null;
        if (d != null) {
            s.details = new ArrayList<>();
            for (int i = 0; i < d.length(); i++) {
                JSONArray item = d.getJSONArray(i);
                s.details.add(new AdasOverlayState.Detail(item.getString(0), item.getString(1), item.length() > 2 && "warn".equals(item.getString(2))));
            }
        }
        s.narrow = j.optBoolean("narrow");
        return s;
    }

    private static AdasOverlayState.Fn fnOf(String v) {
        return v.equals("active") ? AdasOverlayState.Fn.ACTIVE : v.equals("available") ? AdasOverlayState.Fn.AVAILABLE : AdasOverlayState.Fn.OFF;
    }

    /** Synthetic observations through the real decoder: each entry's age is milliseconds before now. */
    static AdasObservations injected(JSONObject j, long now) throws Exception {
        AdasObservations o = new AdasObservations();
        o.session(true, now - 3_600_000L);
        JSONArray p = j.optJSONArray("pilot");
        if (p != null) o.apply(AdasWire.decode(AdasWire.PILOT, AdasWire.pilot(p.getInt(0), p.getInt(1), p.optBoolean(2))), now - j.optLong("pilotAgeMs", 0));
        if (j.has("mode")) o.apply(AdasWire.decode(AdasWire.MODE, AdasWire.mode(j.getInt("mode"))), now);
        JSONArray al = j.optJSONArray("alarm");
        if (al != null) o.apply(AdasWire.decode(AdasWire.ALARM, AdasWire.alarm(al.getInt(0), al.getInt(1))), now);
        JSONArray sel = j.optJSONArray("selected");
        if (sel != null) o.apply(AdasWire.decode(AdasWire.SELECTED, AdasWire.selected(sel.getLong(0), sel.getInt(1))), now);
        JSONArray m = j.optJSONArray("match");
        if (m != null) {
            long[] paths = new long[m.length()];
            for (int i = 0; i < paths.length; i++) paths[i] = m.getLong(i);
            o.apply(AdasWire.decode(AdasWire.PATH_MATCH, AdasWire.match(AdasWire.SUCCESS, paths)), now);
        }
        if (j.has("stockEmptyAgeMs")) {
            long at = now - j.getLong("stockEmptyAgeMs");
            o.apply(AdasWire.decode(AdasWire.NAV_STATUS, AdasWire.navStatus(AdasWire.STATUS_IDLE)), at);
            o.apply(AdasWire.decode(AdasWire.LOCATION, AdasWire.location(-1)), at);
        }
        return o;
    }

    static AdasFeed.Snapshot injectedFeed(JSONObject j, long now) {
        JSONObject f = j.optJSONObject("feed");
        if (f == null) return AdasFeed.DISABLED;
        return new AdasFeed.Snapshot(AdasFeed.Mode.valueOf(f.optString("mode", "DRY_RUN")),
                f.has("sendAgeMs") ? now - f.optLong("sendAgeMs") : -1, f.optLong("pathId", -1), f.optBoolean("routeActive", true));
    }

    private final class Layer extends View {
        final Activity activity;
        private final AdasPainter painter = new AdasPainter(context.getAssets());
        private Bitmap bitmap;
        private final int[] origin = new int[2], location = new int[2];
        private String key = "";
        private boolean black;
        private int ax, ay;

        Layer(Activity activity) {
            super(activity);
            this.activity = activity;
            setClickable(false); setFocusable(false); setWillNotDraw(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); main.post(tick); }
        @Override protected void onDetachedFromWindow() { main.removeCallbacks(tick); super.onDetachedFromWindow(); }

        void detach() {
            main.removeCallbacks(tick);
            if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
            if (bitmap != null) { bitmap.recycle(); bitmap = null; }
        }

        private final Runnable tick = new Runnable() {
            public void run() {
                try { update(); } catch (Throwable error) { Log.e(TAG, "overlay frame failed", error); }
                main.postDelayed(this, TICK_MS);
            }
        };

        private void update() {
            if (getWidth() == 0) return;
            long now = SystemClock.elapsedRealtime();
            float density = getResources().getDisplayMetrics().density;
            boolean narrow = AdasOverlayState.isNarrow(getWidth(), density);
            Fixture f = fixture(now);
            AdasOverlayState state;
            if (f != null && f.state != null) state = f.state;
            else if (f != null && f.inject != null) {
                try { state = AdasOverlayState.of(injected(f.inject, now), injectedFeed(f.inject, now), now, narrow || f.inject.optBoolean("narrow")); }
                catch (Exception error) { Log.w(TAG, "inject rejected", error); return; }
            } else state = AdasOverlayState.of(published, AdasFeed.current().status(), now, narrow);
            int[] anchor;
            if (f != null && f.anchor != null) {
                getLocationOnScreen(location);
                anchor = new int[]{f.anchor[0] - location[0], f.anchor[1] - location[1]};
            } else {
                int insetRight = 0, insetBottom = 0;
                WindowInsets insets = getRootWindowInsets();
                if (insets != null) { insetRight = insets.getSystemWindowInsetRight(); insetBottom = insets.getSystemWindowInsetBottom(); }
                anchor = AdasOverlayState.anchor(getWidth(), getHeight(), insetRight, insetBottom, density, navigating);
            }
            boolean nextBlack = f != null && f.black;
            String next = state + "@" + anchor[0] + "," + anchor[1] + ":" + nextBlack + ":" + density;
            if (next.equals(key)) return;
            key = next;
            bitmap = painter.render(state, density / 2f, origin, bitmap);
            ax = anchor[0]; ay = anchor[1]; black = nextBlack;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            if (black) canvas.drawColor(Color.BLACK);
            if (bitmap != null) canvas.drawBitmap(bitmap, ax + origin[0], ay + origin[1], null);
        }
    }
}
