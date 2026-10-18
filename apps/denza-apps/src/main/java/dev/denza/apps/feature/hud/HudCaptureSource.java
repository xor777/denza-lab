package dev.denza.apps.feature.hud;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import dev.denza.hudlink.HudFields;
import dev.denza.hudlink.HudImage;
import dev.denza.hudlink.HudOutput;
import dev.denza.hudlink.HudProtocol;
import dev.denza.hudlink.HudProtocol.Road;
import dev.denza.hudlink.HudRect;
import dev.denza.hudlink.HudRole;
import dev.denza.hudlink.HudScene;
import dev.denza.hudlink.HudSession;
import dev.denza.hudlink.HudSource;
import dev.denza.hudlink.HudWindow;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Denza Apps' own source for the HUD, the capture (docs/hud-output-architecture.md, 4): the hints
 * read from ordinary Yandex, the AR approximation that rides with them, and the map window cut from
 * the screen. It publishes them into the `:hud-link` compositor ({@link HudOutput}), which alone
 * writes to the HUD and keeps the session's policy - binding, repeats, black frames, the clear.
 * What stays here is what Denza Apps knows: which fields a hint fills, the field-8 picture, and
 * how the session reads in «Сервис» ({@link HudSomeIpRuntime}). Main thread.
 */
final class HudCaptureSource {
    private static final String TAG = "DenzaHudGuidance";
    private static final String MAP_LAYER = "map";
    private static final HudRole[] GUIDANCE_ROLES = {HudRole.MANEUVER, HudRole.ROAD_LIMIT, HudRole.AR};
    /**
     * How long the hints stand without a new reading: as long as a route is held without one
     * ({@link HudRouteFreshness}), after which the monitor takes them away itself. A poll publishes
     * them every 350 ms; the lifetime only ends a source that stopped without saying so.
     */
    static final long GUIDANCE_TTL_MS = HudGuidanceModelsKt.HUD_LOST_ROUTE_GRACE_MS;

    private final HudOutput output;
    private final Map<String, byte[]> iconCache = new HashMap<>();
    private String lastPublishedKey;
    private byte[] mapPng;
    private HudImage mapImage;
    private boolean shutDown;

    HudCaptureSource(Context context) {
        output = new HudOutput(context, new Handler(Looper.getMainLooper()), TAG, new Status());
    }

    /** The hints and, while it holds, the AR geometry; every poll that has a route publishes them. */
    void publish(HudGuidance guidance, HudArGeometry arGeometry) {
        shutDown = false;
        Map<HudRole, HudFields> fields = fields(guidance, icon(guidance), arGeometry);
        for (HudRole role : GUIDANCE_ROLES) {
            HudFields values = fields.get(role);
            if (values != null) {
                output.fields(HudSource.CAPTURE, role, values, GUIDANCE_TTL_MS);
            } else {
                output.withdraw(HudSource.CAPTURE, role);
            }
        }
        // One line per manoeuvre, not per poll. Distance, ETA and the road name change on every
        // 350 ms sample; printing them wrote the whole route into logcat at ~3 lines/s and pushed
        // everything else, including AVC crashes, out of the ring.
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
    }

    /**
     * A frame of the map window as a PNG, or the last one again so it stays on the glass: the
     * same array renews the frame without drawing or sending it anew. The window shows only
     * beside the hints, so a frame alone opens nothing.
     */
    void publishMap(byte[] png) {
        if (png != mapPng) {
            mapPng = png;
            mapImage = HudImage.png(png);
        }
        output.layer(HudSource.CAPTURE, MAP_LAYER, HudRole.MAP, HudRect.whole(HudWindow.MAP),
                mapImage, 1f, HudScene.LAYER_TTL_MS);
    }

    /** The map window is no longer fed: the compositor empties it with black frames. */
    void clearMap() {
        mapPng = null;
        mapImage = null;
        output.withdraw(HudSource.CAPTURE, HudRole.MAP);
    }

    /**
     * No route: the hints and the map go together, and with nothing left the compositor sends one
     * clear and stops the session.
     */
    void clear() {
        mapPng = null;
        mapImage = null;
        lastPublishedKey = null;
        output.withdraw(HudSource.CAPTURE);
        HudSomeIpRuntime.onIdle();
    }

    /** The hints went off or the service is going: {@link #clear}, said as a stop in «Сервис». */
    void shutdown() {
        shutDown = true;
        clear();
    }

    private byte[] icon(HudGuidance guidance) {
        byte[] icon = HudNotificationArtworkRuntime.resolve(guidance, SystemClock.uptimeMillis());
        if (icon != null) {
            return icon;
        }
        String iconKey = guidance.getManeuver().name() + ":" + guidance.getRoundaboutExitNumber();
        icon = iconCache.get(iconKey);
        if (icon == null) {
            icon = HudManeuverIcon.render(guidance.getManeuver(), guidance.getRoundaboutExitNumber());
            iconCache.put(iconKey, icon);
        }
        return icon;
    }

    /**
     * The road-packet fields a hint fills, by role: the manoeuvre block always, the limit when
     * Yandex's sign shows one, the AR while the approximation holds.
     */
    static Map<HudRole, HudFields> fields(
            HudGuidance guidance, byte[] icon, HudArGeometry arGeometry) {
        EnumMap<HudRole, HudFields> fields = new EnumMap<>(HudRole.class);
        HudFields.Builder maneuver = new HudFields.Builder();
        Integer remainingMeters = guidance.getRemainingDistanceMeters();
        if (remainingMeters != null) {
            maneuver.number(Road.REMAINING_METERS, remainingMeters);
        }
        Integer remainingSeconds = guidance.getRemainingTimeSeconds();
        if (remainingSeconds != null) {
            maneuver.number(Road.REMAINING_SECONDS, remainingSeconds);
        }
        fields.put(HudRole.MANEUVER, maneuver
                .bytes(Road.MANEUVER_PNG, icon)
                .number(Road.MANEUVER_METERS, guidance.getManeuverDistanceMeters())
                .text(Road.ROAD_NAME, guidance.getNextRoadName())
                .text(Road.SUMMARY_DISTANCE, routeDistance(guidance))
                .text(Road.SUMMARY_TIME, guidance.getRemainingTimeText())
                .number(Road.STOCK_MANEUVER, guidance.getManeuver().getStockId())
                .build());
        Integer speedLimit = guidance.getSpeedLimitKmh();
        if (speedLimit != null) {
            fields.put(HudRole.ROAD_LIMIT, new HudFields.Builder()
                    .number(Road.SPEED_LIMIT, speedLimit)
                    .build());
        }
        if (arGeometry != null) {
            fields.put(HudRole.AR, new HudFields.Builder()
                    .decimal(Road.LONGITUDE, arGeometry.getVehicleLongitude())
                    .decimal(Road.LATITUDE, arGeometry.getVehicleLatitude())
                    .number(Road.SPEED, Math.round(arGeometry.getVehicleSpeedMetersPerSecond()))
                    .number(Road.ALTITUDE, Math.round(arGeometry.getVehicleAltitudeMeters()))
                    .text(Road.AR_LINE, arGeometry.getGuideLine())
                    .text(Road.AR_POINT, arGeometry.getGuidePoint())
                    .decimal(Road.HEADING, arGeometry.getVehicleHeadingDegrees())
                    .decimal(Road.PROGRESS, arGeometry.getNavigatingRatio())
                    .build());
        }
        return fields;
    }

    static String routeDistance(HudGuidance guidance) {
        Integer remainingMeters = guidance.getRemainingDistanceMeters();
        return remainingMeters == null ? "" : HudProtocol.routeDistance(remainingMeters);
    }

    /** The road packet the compositor makes of {@code guidance} from this source alone. */
    static byte[] roadPacketForTest(HudGuidance guidance, HudArGeometry arGeometry) {
        return sceneForTest(guidance, arGeometry).compose(0L).road();
    }

    /** The clear the compositor sends once this source's route is gone. */
    static byte[] clearPacketForTest(HudGuidance guidance) {
        return sceneForTest(guidance, null).compose(0L).clear();
    }

    private static HudScene sceneForTest(HudGuidance guidance, HudArGeometry arGeometry) {
        HudScene scene = new HudScene();
        for (Map.Entry<HudRole, HudFields> role : fields(guidance, null, arGeometry).entrySet()) {
            scene.fields(HudSource.CAPTURE, role.getKey(), role.getValue(), GUIDANCE_TTL_MS, 0L);
        }
        return scene;
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
            lastPublishedKey = null;
            HudSomeIpRuntime.onRecovering(reason(trouble, result));
        }

        @Override
        public void onClearing() {
            Log.i(TAG, "clearing the HUD");
            HudSomeIpRuntime.onIdle();
        }

        @Override
        public void onStopped() {
            if (shutDown) {
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
