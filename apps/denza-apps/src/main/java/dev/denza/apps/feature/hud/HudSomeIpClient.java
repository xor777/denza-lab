package dev.denza.apps.feature.hud;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import dev.denza.hudlink.HudMessage;
import dev.denza.hudlink.HudProtocol;
import dev.denza.hudlink.HudProtocol.Road;
import dev.denza.hudlink.HudSomeIp;

import java.util.HashMap;
import java.util.Map;

/**
 * Product wrapper around the stock SOME/IP road-guidance endpoint verified by the HUD probe: the
 * road packet {@code 0x8001} the hints send, and the map window's event {@code 0x8003}, which
 * rides on the same session. The wire - Binder calls, protobuf, the map frame - is `:hud-link`,
 * shared with the YandexHUD module; the session's policy and the field choice are this app's.
 */
final class HudSomeIpClient {
    private static final String TAG = "DenzaHudGuidance";
    private static final long SHUTDOWN_DELAY_MS = 350L;
    private static final long RECOVERY_DELAY_MS = 1000L;
    /**
     * The road topic reaches the HUD over UDP, so one lost clear would leave the last arrow on the
     * glass. The clear goes out three times, 120 ms apart, all before {@link #SHUTDOWN_DELAY_MS}.
     */
    private static final int CLEAR_SENDS = 3;
    private static final long CLEAR_REPEAT_MS = 120L;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, byte[]> iconCache = new HashMap<>();
    private final Runnable shutdownRunnable = this::stopAndUnbind;
    private final Runnable clearRepeatRunnable = this::repeatClear;
    private final Runnable blankMapRepeatRunnable = this::repeatBlankMap;
    private final Runnable recoveryRunnable = () -> {
        recoveryScheduled = false;
        recoverConnection();
    };
    private IBinder binder;
    private boolean bound;
    private boolean serviceStarted;
    private boolean recoveryScheduled;
    private HudGuidance pending;
    private HudArGeometry pendingArGeometry;
    private int clearSendsLeft;
    private byte[] blankMapPayload;
    private int blankMapSendsLeft;
    private String lastPublishedKey;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            cancelRecovery();
            binder = service;
            Log.i(TAG, "connected " + name.flattenToShortString());
            if (pending != null) {
                startSessionAndPublishPending();
            } else {
                HudSomeIpRuntime.onIdle();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            serviceStarted = false;
            Log.w(TAG, "disconnected " + name.flattenToShortString());
            scheduleRecovery("Штатный сервис HUD отключился");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.w(TAG, "binding died " + name.flattenToShortString());
            resetBinding();
            scheduleRecovery("Привязка к штатному HUD потеряна");
        }
    };

    HudSomeIpClient(Context context) {
        this.context = context.getApplicationContext();
    }

    void publish(HudGuidance guidance) {
        publish(guidance, null);
    }

    void publish(HudGuidance guidance, HudArGeometry arGeometry) {
        handler.removeCallbacks(shutdownRunnable);
        cancelClearRepeats();
        pending = guidance;
        pendingArGeometry = arGeometry;
        ensureConnected();
        if (binder != null && !serviceStarted) {
            startSessionAndPublishPending();
        } else if (serviceStarted) {
            fireGuidance(guidance, arGeometry);
        }
    }

    void clear() {
        pending = null;
        pendingArGeometry = null;
        cancelRecovery();
        cancelClearRepeats();
        if (serviceStarted) {
            clearSendsLeft = CLEAR_SENDS;
            sendClear();
        }
        HudSomeIpRuntime.onIdle();
    }

    private void sendClear() {
        clearSendsLeft--;
        int result = fire(HudProtocol.TOPIC_ROAD, buildPayload(true, null));
        HudSomeIpRuntime.onFireResult(result);
        Log.i(TAG, "clear ret=" + result + " left=" + clearSendsLeft);
        if (clearSendsLeft > 0) {
            handler.postDelayed(clearRepeatRunnable, CLEAR_REPEAT_MS);
        }
    }

    private void repeatClear() {
        if (!serviceStarted || pending != null || clearSendsLeft <= 0) {
            clearSendsLeft = 0;
            return;
        }
        sendClear();
    }

    private void cancelClearRepeats() {
        handler.removeCallbacks(clearRepeatRunnable);
        clearSendsLeft = 0;
    }

    /**
     * A frame of the map window, already a payload ({@link HudProtocol#mapPayload}). It rides on the session
     * the hints started: with none started it is dropped, never a reason to start one - the HUD
     * shows the map window only beside a road packet that says a route is on. A frame ends the
     * black frames of an earlier {@link #clearMap} still to go.
     */
    void publishMap(byte[] payload) {
        cancelBlankMapRepeats();
        if (!serviceStarted || binder == null) {
            return;
        }
        HudMapRuntime.onFireResult(fire(HudProtocol.TOPIC_MAP, payload));
    }

    /**
     * Empties the map window with {@code blankPayload}, a black frame, three times 120 ms apart:
     * the event reaches the HUD over UDP like the road clear, and one lost black frame would leave
     * the last map on the glass. All three go before {@link #SHUTDOWN_DELAY_MS}, so a shutdown
     * right after still sends them. Nothing goes without a started session.
     */
    void clearMap(byte[] blankPayload) {
        cancelBlankMapRepeats();
        if (!serviceStarted) {
            return;
        }
        blankMapPayload = blankPayload;
        blankMapSendsLeft = CLEAR_SENDS;
        sendBlankMap();
    }

    private void sendBlankMap() {
        blankMapSendsLeft--;
        int result = fire(HudProtocol.TOPIC_MAP, blankMapPayload);
        HudMapRuntime.onFireResult(result);
        Log.i(TAG, "blank map ret=" + result + " left=" + blankMapSendsLeft);
        if (blankMapSendsLeft > 0) {
            handler.postDelayed(blankMapRepeatRunnable, CLEAR_REPEAT_MS);
        }
    }

    private void repeatBlankMap() {
        if (!serviceStarted || blankMapSendsLeft <= 0) {
            blankMapSendsLeft = 0;
            return;
        }
        sendBlankMap();
    }

    private void cancelBlankMapRepeats() {
        handler.removeCallbacks(blankMapRepeatRunnable);
        blankMapSendsLeft = 0;
    }

    void shutdown() {
        pending = null;
        clear();
        handler.removeCallbacks(shutdownRunnable);
        handler.postDelayed(shutdownRunnable, SHUTDOWN_DELAY_MS);
    }

    private void ensureConnected() {
        handler.removeCallbacks(shutdownRunnable);
        if (bound) {
            if (binder == null && pending != null) {
                scheduleRecovery("Ожидаю переподключение штатного HUD");
            }
            return;
        }
        HudSomeIpRuntime.onBinding();
        Intent intent = HudSomeIp.bindIntent(context.getPackageName());
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bind=" + bound);
            if (!bound) {
                scheduleRecovery("Штатный сервис HUD не принял подключение");
            }
        } catch (RuntimeException error) {
            bound = false;
            Log.e(TAG, "bind failed", error);
            scheduleRecovery("Ошибка подключения к штатному HUD");
        }
    }

    private int startSomeIpService() {
        return HudSomeIp.start(binder, TAG);
    }

    private void startSessionAndPublishPending() {
        HudSomeIpRuntime.onStarting();
        int result = startSomeIpService();
        HudSomeIpRuntime.onStartResult(result);
        serviceStarted = result == 0;
        Log.i(TAG, "start ret=" + result);
        if (!serviceStarted) {
            scheduleRecovery("Не удалось запустить сессию HUD: " + result);
            return;
        }
        HudGuidance guidance = pending;
        if (guidance != null) {
            fireGuidance(guidance, pendingArGeometry);
        }
    }

    private void fireGuidance(HudGuidance guidance, HudArGeometry arGeometry) {
        byte[] icon = HudNotificationArtworkRuntime.resolve(
                guidance,
                SystemClock.uptimeMillis());
        if (icon == null) {
            String iconKey = guidance.getManeuver().name()
                    + ":" + guidance.getRoundaboutExitNumber();
            icon = iconCache.get(iconKey);
            if (icon == null) {
                icon = HudManeuverIcon.render(guidance.getManeuver(), guidance.getRoundaboutExitNumber());
                iconCache.put(iconKey, icon);
            }
        }
        int result = fire(HudProtocol.TOPIC_ROAD,
                buildPayload(false, guidance, icon, arGeometry));
        HudSomeIpRuntime.onFireResult(result);
        if (result == 0) {
            // One line per manoeuvre, not per poll. Distance, ETA and the road name change on
            // every 350 ms sample; printing them wrote the whole route into logcat at ~3 lines/s
            // and pushed everything else, including AVC crashes, out of the ring.
            String publishedKey = guidance.getManeuver().name()
                    + ":" + guidance.getRoundaboutExitNumber()
                    + ":" + (arGeometry != null)
                    + ":" + guidance.getSpeedLimitKmh();
            if (!publishedKey.equals(lastPublishedKey)) {
                lastPublishedKey = publishedKey;
                Log.i(TAG, "publishing " + guidance.getManeuver()
                        + " roundaboutExit=" + guidance.getRoundaboutExitNumber()
                        + " ar=" + (arGeometry != null)
                        + " speedLimit=" + guidance.getSpeedLimitKmh());
            }
        } else {
            lastPublishedKey = null;
            Log.w(TAG, "publish ret=" + result);
            serviceStarted = false;
            scheduleRecovery("Штатный HUD отклонил подсказку: " + result);
        }
    }

    private void scheduleRecovery(String reason) {
        if (pending == null) {
            return;
        }
        if (recoveryScheduled) {
            return;
        }
        HudSomeIpRuntime.onRecovering(reason);
        recoveryScheduled = true;
        handler.postDelayed(recoveryRunnable, RECOVERY_DELAY_MS);
    }

    private void cancelRecovery() {
        handler.removeCallbacks(recoveryRunnable);
        recoveryScheduled = false;
    }

    private void recoverConnection() {
        if (pending == null) {
            return;
        }
        resetBinding();
        ensureConnected();
    }

    private void resetBinding() {
        IBinder oldBinder = binder;
        boolean wasBound = bound;
        binder = null;
        serviceStarted = false;
        bound = false;
        if (wasBound || oldBinder != null) {
            try {
                context.unbindService(connection);
            } catch (RuntimeException error) {
                Log.w(TAG, "reset unbind failed", error);
            }
        }
    }

    private int fire(long topic, byte[] payload) {
        return HudSomeIp.fire(binder, topic, payload, TAG);
    }

    private void stopAndUnbind() {
        cancelRecovery();
        cancelClearRepeats();
        cancelBlankMapRepeats();
        IBinder service = binder;
        if (serviceStarted && service != null) {
            Log.i(TAG, "stop ret=" + HudSomeIp.stop(service, TAG));
        }
        serviceStarted = false;
        binder = null;
        if (bound) {
            try {
                context.unbindService(connection);
            } catch (RuntimeException error) {
                Log.w(TAG, "unbind failed", error);
            }
            bound = false;
        }
        HudSomeIpRuntime.onIdle("Сессия HUD остановлена");
    }

    private static byte[] buildPayload(boolean clear, HudGuidance guidance) {
        return buildPayload(clear, guidance, null, null);
    }

    private static byte[] buildPayload(
            boolean clear,
            HudGuidance guidance,
            byte[] icon,
            HudArGeometry arGeometry) {
        // Field 2 as the stock navigator sends it; until 2026-10-09 it carried a sequence number.
        HudMessage message = new HudMessage(256 + (icon == null ? 0 : icon.length))
                .number(Road.COUNTER, Road.STOCK_COUNTER)
                .number(Road.STATE, clear ? Road.OFF : Road.NAVIGATING);
        if (!clear && guidance != null) {
            if (guidance.getRemainingDistanceMeters() != null) {
                message.number(Road.REMAINING_METERS, guidance.getRemainingDistanceMeters());
            }
            if (guidance.getRemainingTimeSeconds() != null) {
                message.number(Road.REMAINING_SECONDS, guidance.getRemainingTimeSeconds());
            }
            message.bytes(Road.MANEUVER_PNG, icon)
                    .number(Road.MANEUVER_METERS, guidance.getManeuverDistanceMeters())
                    .text(Road.ROAD_NAME, guidance.getNextRoadName());
            if (guidance.getSpeedLimitKmh() != null) {
                message.number(Road.SPEED_LIMIT, guidance.getSpeedLimitKmh());
            }
            message.text(Road.SUMMARY_DISTANCE, routeDistance(guidance))
                    .text(Road.SUMMARY_TIME, guidance.getRemainingTimeText())
                    .number(Road.STOCK_MANEUVER, guidance.getManeuver().getStockId());
            if (arGeometry != null) {
                message.decimal(Road.LONGITUDE, arGeometry.getVehicleLongitude())
                        .decimal(Road.LATITUDE, arGeometry.getVehicleLatitude())
                        .number(Road.SPEED, Math.round(arGeometry.getVehicleSpeedMetersPerSecond()))
                        .number(Road.ALTITUDE, Math.round(arGeometry.getVehicleAltitudeMeters()))
                        .text(Road.AR_LINE, arGeometry.getGuideLine())
                        .text(Road.AR_POINT, arGeometry.getGuidePoint())
                        .decimal(Road.HEADING, arGeometry.getVehicleHeadingDegrees())
                        .decimal(Road.PROGRESS, arGeometry.getNavigatingRatio());
            }
        }
        return message.embedded(Road.ENVELOPE);
    }

    static byte[] buildPayloadForTest(HudGuidance guidance, HudArGeometry arGeometry) {
        return buildPayload(false, guidance, null, arGeometry);
    }

    static byte[] buildClearPayloadForTest() {
        return buildPayload(true, null);
    }

    static String routeDistance(HudGuidance guidance) {
        Integer remainingMeters = guidance.getRemainingDistanceMeters();
        return remainingMeters == null ? "" : HudProtocol.routeDistance(remainingMeters);
    }
}
