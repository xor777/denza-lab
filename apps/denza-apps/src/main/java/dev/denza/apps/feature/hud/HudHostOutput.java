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
 * <p>While HUD Speed warns of a camera with a limit on a route, the car's speed from its wheels is
 * read at a screen's cadence and the warning turns red at a fine's speed ({@link HudSpeeding}),
 * over a moment ({@link HudRadarHost}). The map stays as it is (owner, 2026-11-02).
 */
final class HudHostOutput {
    private static final String TAG = "DenzaHudGuidance";
    private static volatile HudHostOutput instance;

    private final Context context;
    private final HudOutput output;
    private final Sink sink = new Sink();
    private final HudRadarHost cameras;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HudHostEndpoint endpoint;
    private final HudSignFollower sign;
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
        sign = new HudSignFollower(() -> HudGuidanceSettings.INSTANCE.isSignEnabled(context),
                () -> new HudNativeSpeedLimitRunner(context));
        output.setLimitListener(sign::limitChanged);
        HudRadarArt art = new HudRadarArt(context);
        HudRadarSource radar = new HudRadarSource(sink, new HudRadarSource.Renderer() {
            @Override
            public HudImage lanes(HudRadarWarning warning, float red) {
                return HudRadarPainter.lanes(warning, art, red);
            }

            @Override
            public HudImage gps(int level) {
                return HudRadarPainter.gps(level, art);
            }
        });
        cameras = new HudRadarHost(radar, () -> {
            HudWheelSpeed wheels = new HudWheelSpeed(context);
            return new HudRadarHost.Wheels() {
                @Override
                public void watch(boolean on) {
                    wheels.watch(on);
                }

                @Override
                public Double kmh(long now) {
                    return wheels.kmh(now);
                }
            };
        }, new HudRadarHost.Timer() {
            @Override
            public void postDelayed(Runnable task, long delayMs) {
                main.postDelayed(task, delayMs);
            }

            @Override
            public void remove(Runnable task) {
                main.removeCallbacks(task);
            }
        }, SystemClock::elapsedRealtime);
        output.setNavigationListener(cameras::onNavigation);
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
                            // A radar attach replaces whatever the previous one left, red included.
                            if (source == HudSource.RADAR) {
                                cameras.forget();
                            }
                            HudHostRuntime.INSTANCE.onAttached(source);
                        }

                        @Override
                        public void onDetached(HudSource source, int uid) {
                            if (source == HudSource.RADAR) {
                                cameras.forget();
                            }
                            HudHostRuntime.INSTANCE.onDetached(source);
                        }

                        @Override
                        public void onRadar(HudRadarWarning warning) {
                            if (panel().camerasShown()) {
                                cameras.onRadar(warning);
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
        HudPanelFilter panel = panel();
        panel.withdrawHeldBack(output);
        if (!panel.camerasShown()) {
            cameras.forget();
        }
        sign.follow();
    }

    /**
     * Any thread, the moment a switch of the HUD panel moves, before {@link #onSettingsChanged}
     * is posted: the car's sign stops at once if its switch went off ({@link HudSignFollower}).
     */
    static void switchMoved() {
        HudHostOutput host = instance;
        if (host != null) {
            host.sign.switchMoved();
        }
    }

    /** The HUD panel's switches as they stand. */
    private HudPanelFilter panel() {
        HudGuidanceSettings settings = HudGuidanceSettings.INSTANCE;
        return new HudPanelFilter(settings.isEnabled(context), settings.isMapEnabled(context),
                settings.isSignEnabled(context), settings.areCamerasEnabled(context));
    }

    /** What the panel lets through from {@code source} for {@code role}. */
    private boolean admits(HudSource source, HudRole role) {
        return panel().admits(source, role);
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
