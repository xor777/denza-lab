package dev.denza.hud.probe;

import android.app.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.*;
import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bootstrap inserted into MapKit initialization and Guidance construction; no Activity or service replacement. */
public final class HudModule {
    private static final String TAG = "DenzaYandexHud";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context context;
    private static Object pendingGuidance;
    private static Engine engine;
    private static int visibleActivities;
    private static boolean callbacksRegistered;

    public static void initialize(Context value) {
        try {
            Context application = value.getApplicationContext();
            if (Build.VERSION.SDK_INT >= 28 && !application.getPackageName().equals(Application.getProcessName())) return;
            MAIN.post(() -> {
                context = application;
                if (!callbacksRegistered && application instanceof Application) {
                    callbacksRegistered = true;
                    ((Application) application).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                        public void onActivityStarted(Activity activity) { visibleActivities++; }
                        public void onActivityStopped(Activity activity) { visibleActivities = Math.max(0, visibleActivities - 1); }
                        public void onActivityCreated(Activity activity, Bundle state) {}
                        public void onActivityResumed(Activity activity) {}
                        public void onActivityPaused(Activity activity) {}
                        public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
                        public void onActivityDestroyed(Activity activity) {}
                    });
                }
                startIfReady();
            });
        } catch (Throwable error) { Log.e(TAG, "module initialization failed", error); }
    }

    public static void attach(Object guidance) {
        try { MAIN.post(() -> { pendingGuidance = guidance; startIfReady(); }); }
        catch (Throwable error) { Log.e(TAG, "module attachment failed", error); }
    }

    private static void startIfReady() {
        if (context == null || pendingGuidance == null) return;
        try {
            if (engine == null) engine = new Engine(context);
            engine.guidance = pendingGuidance;
        } catch (Throwable error) { Log.e(TAG, "module startup failed", error); }
    }

    private static final class Engine {
        final Context context;
        final Reflect api = new Reflect();
        final NavigatorSource source = new NavigatorSource(api);
        final HudArSource arSource = new HudArSource(api);
        JSONObject theme;
        final HandlerThread thread = new HandlerThread("denza-hud-output", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        final Handler worker;
        final AtomicLong epoch = new AtomicLong();
        final HudEmulatorControl emulator;
        Object guidance;
        HudMap map;
        HudPainter painter;
        HudTransport transport;
        StockHudArt stockArt;
        boolean busy, wasActive;
        String mode = "off", hintKey, outputKey = "", arFailure;
        long frames, lastStats, nextAttempt;
        long mapFrames, mainMicros, maxMainMicros;

        Engine(Context context) throws Exception {
            this.context = context;
            try (InputStream input = context.getAssets().open("denza-hud/theme.json")) {
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) data.write(buffer, 0, count);
                theme = new JSONObject(data.toString("UTF-8"));
            }
            emulator = new HudEmulatorControl(context, api);
            thread.start();
            worker = new Handler(thread.getLooper());
            worker.post(() -> {
                Bitmap bitmap = Bitmap.createBitmap(300, 180, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(0xff000000);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
                bitmap.recycle();
                stockArt = new StockHudArt(context.getAssets());
                transport = new HudTransport(context, worker, bytes.toByteArray());
            });
            MAIN.post(this::tick);
            Log.i(TAG, "embedded module ready exp.1");
        }

        void tick() {
            long now = SystemClock.elapsedRealtime();
            long begin = SystemClock.elapsedRealtimeNanos();
            try {
                if (guidance == null) return;
                emulator.poll(guidance, now);
                JSONObject override = busy ? null : emulator.theme(now);
                if (override != null) { theme = override; releaseDrawing(); }
                NavigatorSource.Frame frame = source.read(guidance, visibleActivities > 0, now);
                String nextOutputKey = frame.mode + (frame.drawMap() ? ":" + api.call(frame.route, "getRouteId") : "");
                if (!nextOutputKey.equals(outputKey)) {
                    outputKey = nextOutputKey;
                    epoch.incrementAndGet(); // Invalidate a queued old route, including rerouting/location loss.
                    arSource.reset();
                }
                if (!frame.mode.equals(mode)) {
                    mode = frame.mode;
                    Log.i(TAG, "mode=" + mode + " visible=" + (visibleActivities > 0)
                            + " background=" + frame.backgroundEnabled + " resumed=" + frame.resumed);
                }
                if (frame.active() != wasActive) {
                    wasActive = frame.active();
                    epoch.incrementAndGet();
                    if (!wasActive) worker.post(() -> transport.clear());
                }
                if (busy || now < nextAttempt) return;
                if (!frame.active()) { releaseDrawing(); return; }
                if (painter == null) painter = new HudPainter(context, theme);
                HudMap.Shot shot = null;
                try {
                    if (frame.drawMap()) {
                        if (map == null) map = new HudMap(api, theme);
                        shot = map.capture(frame, now);
                        if (shot.bitmap != null && (shot.bitmap.getWidth() != 300 || shot.bitmap.getHeight() != 180)) throw new IllegalStateException("Invalid frame dimensions");
                    } else if (map != null) map.clearRoute();
                    painter.compose(shot, frame);
                } finally { if (shot != null && shot.bitmap != null) shot.bitmap.recycle(); }
                long elapsed = (SystemClock.elapsedRealtimeNanos() - begin) / 1000;
                if (frame.drawMap()) {
                    mapFrames++;
                    mainMicros += elapsed;
                    maxMainMicros = Math.max(maxMainMicros, elapsed);
                }
                // Immutable semantic snapshot; stock artwork and PNG work belong to the output worker.
                final NavigatorSource.Hint hint = frame.drawMap() ? frame.hint : null;
                final HudAr.Frame ar = arSource.read(frame, now, System.currentTimeMillis());
                final int arBuilds = arSource.geometryBuilds;
                if (ar != null) emulator.route(frame.route);
                if (!java.util.Objects.equals(arFailure, arSource.failure)) {
                    arFailure = arSource.failure;
                    if (arFailure != null) Log.w(TAG, "AR unavailable: " + arFailure);
                }
                final String[] laneSymbols = StockHudSymbols.lanes(frame.lanes);
                logHint(hint);
                final long token = epoch.get();
                final HudPainter owned = painter;
                final boolean visible = visibleActivities > 0;
                busy = true;
                worker.post(() -> {
                    try {
                        byte[] png = owned.encode(!frame.drawMap());
                        if (token == epoch.get()) {
                            byte[] lanes = stockArt.lanes(laneSymbols);
                            byte[] icon = stockArt.manoeuvre(hint);
                            if (token != epoch.get()) return;
                            HudAr.Frame freshAr = ar != null && SystemClock.elapsedRealtime() <= ar.pose.expiresAt ? ar : null;
                            transport.publish(png, HudWire.road(true, hint, icon, laneSymbols.length, lanes, freshAr));
                            emulator.frame(png, frame.mode, visible, frame.backgroundEnabled, frame.resumed, freshAr, arBuilds);
                        }
                    } catch (Throwable error) {
                        Log.e(TAG, "output failed", error);
                        transport.clear();
                    } finally {
                        MAIN.post(() -> { busy = false; frames++; });
                    }
                });
                if (now - lastStats >= 10000) {
                    lastStats = now;
                    Log.i(TAG, "frames=" + frames + " mapFrames=" + mapFrames
                            + " mapMainMeanUs=" + (mapFrames == 0 ? 0 : mainMicros / mapFrames)
                            + " mapMainMaxUs=" + maxMainMicros + " visible=" + visible
                            + " marks=" + (painter == null ? 0 : painter.placedMarks));
                }
            } catch (Throwable error) {
                Log.e(TAG, "frame failed; clearing HUD", error);
                epoch.incrementAndGet();
                worker.post(() -> transport.clear());
                if (!busy) releaseDrawing();
                nextAttempt = now + 1000;
            } finally {
                // One in-flight frame; fixed-rate attempts skip work under output backpressure.
                MAIN.postDelayed(this::tick, Math.max(0, (wasActive ? 100 : 400) - (SystemClock.elapsedRealtime() - now)));
            }
        }

        /** One line per manoeuvre, limit or failure, not per frame: metres change every frame. */
        private void logHint(NavigatorSource.Hint hint) {
            String key = hint == null ? "none" : hint.iconKey() + ":" + hint.stockId + ":" + hint.speedLimit;
            if (source.hintFailure != null) key = "failed:" + source.hintFailure;
            if (key.equals(hintKey)) return;
            hintKey = key;
            if (source.hintFailure != null) Log.w(TAG, "hint unavailable: " + source.hintFailure);
            else if (hint != null) Log.i(TAG, "hint " + hint.iconKey() + " stock=" + hint.stockId + " in=" + hint.distance
                    + "m road=" + (hint.road.isEmpty() ? "-" : "set") + " remain=" + hint.remainingMeters + "m/" + hint.remainingSeconds
                    + "s limit=" + hint.speedLimit + " raw=" + hint.limitValue + "/" + hint.limitText);
        }

        private void releaseDrawing() {
            HudMap oldMap = map; HudPainter oldPainter = painter;
            map = null; painter = null;
            try { if (oldMap != null) oldMap.close(); }
            catch (Throwable error) { Log.w(TAG, "map release failed", error); }
            finally { if (oldPainter != null) oldPainter.close(); }
        }
    }
}
