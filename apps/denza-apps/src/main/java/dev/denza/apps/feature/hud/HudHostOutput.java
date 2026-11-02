package dev.denza.apps.feature.hud;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import dev.denza.hudlink.HudComposition;
import dev.denza.hudlink.HudFields;
import dev.denza.hudlink.HudHostEndpoint;
import dev.denza.hudlink.HudImage;
import dev.denza.hudlink.HudOutput;
import dev.denza.hudlink.HudProtocol;
import dev.denza.hudlink.HudRect;
import dev.denza.hudlink.HudRole;
import dev.denza.hudlink.HudSession;
import dev.denza.hudlink.HudSink;
import dev.denza.hudlink.HudRadarWarning;
import dev.denza.hudlink.HudSource;

/**
 * This app as the HUD's host (docs/hud-output-architecture.md, 4-5): the one compositor and session
 * of the process, on the main thread. Its own capture ({@link HudCaptureSource}) publishes into it,
 * and so do the YandexHUD module and HUD Speed from their processes, through
 * {@link HudHostService}: one writer whatever is installed.
 *
 * <p>What reaches the glass is what the HUD panel says ({@link HudGuidanceSettings}): with
 * «Повороты» off nothing of any source passes - YandexHUD stays attached and is not shown, so it
 * does not start drawing by itself either; «Карта» off holds back the module's map, «Ограничение
 * скорости» off every source's limit, «Камеры» off HUD Speed. The capture keeps its own switches.
 *
 * <p>The car's own sign, which only this app can write, is the composition's second output: it
 * follows the road limit's winner ({@link HudComposition#roadLimitKmh}) with or without
 * navigation (stage 5).
 *
 * <p>While HUD Speed warns of a camera with a limit, the car's speed from its wheels is read at a
 * screen's cadence and the glass turns red at a fine's speed ({@link HudSpeeding}): the warning's
 * picture and the map window, together, over a moment.
 */
final class HudHostOutput {
    private static final String TAG = "DenzaHudGuidance";
    /** How often the red follows the speed: the map window's own rate (HudSession.MAP_MIN_INTERVAL_MS). */
    private static final long RED_TICK_MS = 83L;
    private static final double KMH_PER_MPH = 1.609344;
    private static HudHostOutput instance;

    private final Context context;
    private final HudOutput output;
    private final Sink sink = new Sink();
    private final HudRadarSource radar;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HudSpeeding speeding = new HudSpeeding();
    private final Runnable redTick = this::followSpeed;
    private HudWheelSpeed wheels;
    private float red;
    private HudHostEndpoint endpoint;
    private HudNativeSpeedLimitRunner sign;
    private Integer limit;
    private boolean stopAsked;

    /** The process's host; main thread. */
    static HudHostOutput get(Context context) {
        if (instance == null) {
            instance = new HudHostOutput(context.getApplicationContext());
        }
        return instance;
    }

    private HudHostOutput(Context context) {
        this.context = context;
        output = new HudOutput(context, new Handler(Looper.getMainLooper()), TAG, new Status());
        output.setLimitListener(kmh -> {
            limit = kmh;
            followSign();
        });
        HudRadarArt art = new HudRadarArt(context);
        radar = new HudRadarSource(sink, new HudRadarSource.Renderer() {
            @Override
            public HudImage lanes(HudRadarWarning warning, float red) {
                return HudRadarPainter.lanes(warning, art, red);
            }

            @Override
            public HudImage gps(int level) {
                return HudRadarPainter.gps(level, art);
            }
        });
    }

    /** Where this process's sources publish. */
    HudSink sink() {
        return sink;
    }

    /** The Binder {@link HudHostService} hands a source in another app. */
    HudHostEndpoint endpoint() {
        if (endpoint == null) {
            endpoint = new HudHostEndpoint(new Handler(Looper.getMainLooper()), sink,
                    uid -> HudHostGate.INSTANCE.admit(context, uid),
                    new HudHostEndpoint.Listener() {
                        @Override
                        public void onAttached(HudSource source, int uid) {
                            HudHostRuntime.INSTANCE.onAttached(source);
                        }

                        @Override
                        public void onDetached(HudSource source, int uid) {
                            HudHostRuntime.INSTANCE.onDetached(source);
                        }

                        @Override
                        public void onRadar(HudRadarWarning warning) {
                            if (camerasShown()) {
                                radar.publish(warning, SystemClock.elapsedRealtime());
                                followSpeed();
                            }
                        }
                    },
                    TAG);
        }
        return endpoint;
    }

    /**
     * A switch of the HUD panel moved: what it now holds back leaves the glass at once, and what
     * it lets through comes with each source's next publication.
     */
    void onSettingsChanged() {
        HudGuidanceSettings settings = HudGuidanceSettings.INSTANCE;
        if (!settings.isEnabled(context)) {
            output.withdraw(HudSource.MODULE);
        } else if (!settings.isMapEnabled(context)) {
            output.withdraw(HudSource.MODULE, HudRole.MAP);
            output.withdraw(HudSource.MODULE, HudRole.MAP_MARKS);
        }
        if (!settings.isSignEnabled(context)) {
            for (HudSource source : HudSource.values()) {
                output.withdraw(source, HudRole.ROAD_LIMIT);
            }
        }
        if (!camerasShown()) {
            output.withdraw(HudSource.RADAR);
            stopRed();
        }
        followSign();
    }

    /**
     * The red after the speed: while a camera with a limit is ahead, and after it until the red
     * has gone. Main thread.
     */
    private void followSpeed() {
        main.removeCallbacks(redTick);
        long now = SystemClock.elapsedRealtime();
        HudRadarWarning camera = radar.current(now);
        int limit = camera == null ? 0 : camera.limit;
        if (limit <= 0 && speeding.settled()) {
            stopRed();
            return;
        }
        if (wheels == null) {
            wheels = new HudWheelSpeed(context);
        }
        wheels.watch(limit > 0);
        Double kmh = wheels.kmh(now);
        Double speed = kmh == null || camera == null || camera.unit != 2 ? kmh : kmh / KMH_PER_MPH;
        showRed(speeding.update(limit, speed, now), now);
        if (limit > 0 || !speeding.settled()) {
            main.postDelayed(redTick, RED_TICK_MS);
        } else {
            wheels.watch(false);
        }
    }

    private void showRed(float level, long now) {
        radar.setRed(level, now);
        if (level != red) {
            red = level;
            output.setTint(level * HudSpeeding.TINT);
        }
    }

    /** No red, and no fast reading of the speed for it. */
    private void stopRed() {
        main.removeCallbacks(redTick);
        speeding.reset();
        radar.forget();
        if (red != 0f) {
            red = 0f;
            output.setTint(0f);
        }
        if (wheels != null) {
            wheels.watch(false);
        }
    }

    /** What the panel lets through from {@code source} for {@code role}. */
    private boolean admits(HudSource source, HudRole role) {
        HudGuidanceSettings settings = HudGuidanceSettings.INSTANCE;
        if (role == HudRole.ROAD_LIMIT && !settings.isSignEnabled(context)) {
            return false;
        }
        if (source == HudSource.MODULE) {
            return settings.isEnabled(context)
                    && (settings.isMapEnabled(context) || (role != HudRole.MAP && role != HudRole.MAP_MARKS));
        }
        return source != HudSource.RADAR || camerasShown();
    }

    private boolean camerasShown() {
        return HudGuidanceSettings.INSTANCE.isEnabled(context)
                && HudGuidanceSettings.INSTANCE.areCamerasEnabled(context);
    }

    /**
     * The car's sign after the composition's limit. Its thread starts with the first limit to
     * write; nothing to write leaves the car's own assistance to carry on from what it last had.
     */
    private void followSign() {
        Integer target = limit;
        if (sign == null) {
            if (target == null) {
                return;
            }
            sign = new HudNativeSpeedLimitRunner(context);
        }
        sign.setTarget(target);
    }

    /**
     * The map window's picture for a screen beside the HUD - the instruments' right card - or
     * none ({@code null}). Main thread.
     */
    void setMapFrameListener(kotlin.jvm.functions.Function1<byte[], kotlin.Unit> listener) {
        output.setFrameListener(listener == null ? null : (window, png) -> listener.invoke(png));
    }

    /** The hints went off: the stop that follows is said as one in «Сервис». */
    void noteStopAsked() {
        stopAsked = true;
    }

    /** The compositor, behind what the panel lets through. */
    private final class Sink implements HudSink {
        @Override
        public void layer(HudSource source, String id, HudRole role, HudRect rect, HudImage image,
                float opacity, long ttlMs) {
            if (!admits(source, role)) {
                return;
            }
            stopAsked = false;
            output.layer(source, id, role, rect, image, opacity, ttlMs);
        }

        @Override
        public void removeLayer(HudSource source, String id) {
            output.removeLayer(source, id);
        }

        @Override
        public void fields(HudSource source, HudRole role, HudFields fields, long ttlMs) {
            if (!admits(source, role)) {
                return;
            }
            stopAsked = false;
            output.fields(source, role, fields, ttlMs);
        }

        @Override
        public void withdraw(HudSource source, HudRole role) {
            output.withdraw(source, role);
        }

        @Override
        public void withdraw(HudSource source) {
            output.withdraw(source);
        }
    }

    /** The session as «Сервис» shows it, and its failures in the log; no route data. */
    private final class Status implements HudSession.Listener {
        @Override
        public void onBinding() {
            HudSomeIpRuntime.onBinding();
        }

        @Override
        public void onStarted(int result) {
            HudSomeIpRuntime.onStarting();
            HudSomeIpRuntime.onStartResult(result);
        }

        @Override
        public void onFired(long topic, int result, boolean clearing) {
            if (topic == HudProtocol.TOPIC_MAP) {
                HudMapRuntime.onFireResult(result);
            } else if (!clearing) {
                HudSomeIpRuntime.onFireResult(result);
            }
            if (result != 0) {
                Log.w(TAG, (topic == HudProtocol.TOPIC_MAP ? "map" : "road")
                        + (clearing ? " clear" : "") + " ret=" + result);
            }
        }

        @Override
        public void onTrouble(HudSession.Trouble trouble, int result) {
            HudSomeIpRuntime.onRecovering(reason(trouble, result));
        }

        @Override
        public void onClearing() {
            Log.i(TAG, "clearing the HUD");
            HudSomeIpRuntime.onIdle();
        }

        @Override
        public void onStopped() {
            if (stopAsked) {
                HudSomeIpRuntime.onIdle("Сессия HUD остановлена");
            } else {
                HudSomeIpRuntime.onIdle();
            }
        }
    }

    private static String reason(HudSession.Trouble trouble, int result) {
        switch (trouble) {
            case BIND_REFUSED:
                return "Штатный сервис HUD не принял подключение";
            case BIND_FAILED:
                return "Ошибка подключения к штатному HUD";
            case BIND_TIMEOUT:
                return "Штатный HUD не ответил на подключение";
            case DISCONNECTED:
                return "Штатный сервис HUD отключился";
            case START_FAILED:
                return "Не удалось запустить сессию HUD: " + result;
            default:
                return "Штатный HUD отклонил подсказку: " + result;
        }
    }
}
