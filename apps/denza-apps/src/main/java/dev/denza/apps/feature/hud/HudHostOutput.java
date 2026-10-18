package dev.denza.apps.feature.hud;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import dev.denza.hudlink.HudFields;
import dev.denza.hudlink.HudHostEndpoint;
import dev.denza.hudlink.HudImage;
import dev.denza.hudlink.HudOutput;
import dev.denza.hudlink.HudProtocol;
import dev.denza.hudlink.HudProtocol.Road;
import dev.denza.hudlink.HudRect;
import dev.denza.hudlink.HudRole;
import dev.denza.hudlink.HudSession;
import dev.denza.hudlink.HudSink;
import dev.denza.hudlink.HudSource;
import dev.denza.hudlink.HudRadarWarning;

/**
 * This app as the HUD's host (docs/hud-output-architecture.md, 4-5): the one compositor and session
 * of the process, on the main thread. Its own capture ({@link HudCaptureSource}) publishes into it,
 * and so does the YandexHUD module from Navigator's process, through {@link HudHostService}: one
 * writer whatever is installed. The module's road limit is kept for the car's own sign
 * ({@link #moduleLimitKmh}), which only this app can write.
 */
final class HudHostOutput {
    private static final String TAG = "DenzaHudGuidance";
    private static HudHostOutput instance;

    private final Context context;
    private final HudOutput output;
    private final HudModuleLimit moduleLimit = new HudModuleLimit(SystemClock::uptimeMillis);
    private final Sink sink = new Sink();
    private final HudRadarSource radar = new HudRadarSource(sink, HudRadarPainter::draw);
    private HudHostEndpoint endpoint;
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
                            radar.publish(warning, SystemClock.elapsedRealtime());
                        }
                    },
                    TAG);
        }
        return endpoint;
    }

    /** The module's road limit, km/h, while its fields live; else null. */
    Integer moduleLimitKmh() {
        return moduleLimit.current();
    }

    /** The hints went off: the stop that follows is said as one in «Сервис». */
    void noteStopAsked() {
        stopAsked = true;
    }

    /** The compositor, with the module's limit kept as it passes. */
    private final class Sink implements HudSink {
        @Override
        public void layer(HudSource source, String id, HudRole role, HudRect rect, HudImage image,
                float opacity, long ttlMs) {
            stopAsked = false;
            output.layer(source, id, role, rect, image, opacity, ttlMs);
        }

        @Override
        public void removeLayer(HudSource source, String id) {
            output.removeLayer(source, id);
        }

        @Override
        public void fields(HudSource source, HudRole role, HudFields fields, long ttlMs) {
            stopAsked = false;
            if (source == HudSource.MODULE && role == HudRole.ROAD_LIMIT) {
                Object limit = fields.value(Road.SPEED_LIMIT);
                moduleLimit.published(limit instanceof Long ? (int) (long) (Long) limit : null, ttlMs);
            }
            output.fields(source, role, fields, ttlMs);
        }

        @Override
        public void withdraw(HudSource source, HudRole role) {
            if (source == HudSource.MODULE && role == HudRole.ROAD_LIMIT) {
                moduleLimit.withdrawn();
            }
            output.withdraw(source, role);
        }

        @Override
        public void withdraw(HudSource source) {
            if (source == HudSource.MODULE) {
                moduleLimit.withdrawn();
            }
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
