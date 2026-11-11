package dev.denza.apps.feature.hud;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import dev.denza.hudlink.HudFields;
import dev.denza.hudlink.HudImage;
import dev.denza.hudlink.HudProtocol;
import dev.denza.hudlink.HudProtocol.Road;
import dev.denza.hudlink.HudRect;
import dev.denza.hudlink.HudRole;
import dev.denza.hudlink.HudScene;
import dev.denza.hudlink.HudSink;
import dev.denza.hudlink.HudSource;
import dev.denza.hudlink.HudWindow;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Denza Apps' own source for the HUD, the capture (docs/hud-output-architecture.md, 4): the hints
 * read from ordinary Yandex, the AR approximation that rides with them, and the map window cut from
 * the screen. It publishes them into this app's host ({@link HudHostOutput}), the one compositor
 * of the process, which alone writes to the HUD and keeps the session's policy - binding,
 * repeats, black frames, the clear - for this source and for the YandexHUD module alike. What
 * stays here is what Denza Apps knows: which fields a hint fills and the field-8 picture. Main
 * thread.
 */
final class HudCaptureSource {
    /**
     * DO NOT TRANSLATE: Yandex's own text, Russian whatever this app speaks (CyrillicLiteralGuardTest).
     * Groups: hours, the minutes after them, minutes alone.
     */
    private static final Pattern YANDEX_TIME =
            Pattern.compile("([0-9]{1,3}) ч(?: ([0-9]{1,3}) мин)?|([0-9]{1,3}) мин");

    private static final String TAG = "DenzaHudGuidance";
    private static final String MAP_LAYER = "map";
    private static final HudRole[] GUIDANCE_ROLES = {HudRole.MANEUVER, HudRole.ROAD_LIMIT, HudRole.AR};
    /**
     * How long the hints stand without a new reading: as long as a route is held without one
     * ({@link HudRouteFreshness}), after which the monitor takes them away itself. A poll publishes
     * them every 350 ms; the lifetime only ends a source that stopped without saying so.
     */
    static final long GUIDANCE_TTL_MS = HudGuidanceModelsKt.HUD_LOST_ROUTE_GRACE_MS;

    private final HudHostOutput host;
    private final HudSink output;
    private final Map<String, byte[]> iconCache = new HashMap<>();
    private String lastPublishedKey;
    private byte[] mapPng;
    private HudImage mapImage;

    HudCaptureSource(Context context) {
        host = HudHostOutput.get(context);
        output = host.sink();
    }

    /** The hints and, while it holds, the AR geometry; every poll that has a route publishes them. */
    void publish(HudGuidance guidance, HudArGeometry arGeometry) {
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
     * With YandexHUD installed only the limit Yandex shows: the module's own, when it publishes
     * one through the host, wins over it, and with a YandexHUD that draws the HUD itself this is
     * what the car's sign follows. A limit alone opens no navigation, so nothing of this source
     * reaches the glass beside the module.
     */
    void publishLimit(HudGuidance guidance) {
        output.withdraw(HudSource.CAPTURE, HudRole.MANEUVER);
        output.withdraw(HudSource.CAPTURE, HudRole.AR);
        Integer speedLimit = guidance.getSpeedLimitKmh();
        if (speedLimit != null) {
            output.fields(HudSource.CAPTURE, HudRole.ROAD_LIMIT, new HudFields.Builder()
                    .number(Road.SPEED_LIMIT, speedLimit)
                    .build(), GUIDANCE_TTL_MS);
        } else {
            output.withdraw(HudSource.CAPTURE, HudRole.ROAD_LIMIT);
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
        host.noteStopAsked();
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
        return fields(guidance, icon, arGeometry, routeDistance(guidance), summaryTime(guidance));
    }

    private static Map<HudRole, HudFields> fields(HudGuidance guidance, byte[] icon,
            HudArGeometry arGeometry, String summaryDistance, String summaryTime) {
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
                .text(Road.SUMMARY_DISTANCE, summaryDistance)
                .text(Road.SUMMARY_TIME, summaryTime)
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
        return remainingMeters == null
                ? ""
                : HudProtocol.routeDistance(remainingMeters, HudSummaryWords.forGlass());
    }

    /**
     * Yandex's own text, or in English the same time reprinted when the whole of that text is hours,
     * minutes, or hours with minutes. The figure comes from this match and not from
     * {@code remainingTimeSeconds}: the guidance parser reads «1 д 5 ч» as five hours, and a
     * notification merge can keep the seconds of an earlier text. Any other text stays Yandex's.
     */
    static String summaryTime(HudGuidance guidance) {
        String text = guidance.getRemainingTimeText();
        if (!HudSummaryWords.printsTime()) {
            return text;
        }
        Matcher time = YANDEX_TIME.matcher(text);
        if (!time.matches()) {
            return text;
        }
        int minutes = whole(time.group(1)) * 60 + whole(time.group(2)) + whole(time.group(3));
        // HudProtocol.duration prints at least a minute, which «0 мин» did not say.
        return minutes == 0 ? text : HudProtocol.duration(minutes * 60, HudSummaryWords.forGlass());
    }

    private static int whole(String digits) {
        return digits == null ? 0 : Integer.parseInt(digits);
    }

    /** The road packet the compositor makes of {@code guidance} from this source alone. */
    static byte[] roadPacketForTest(HudGuidance guidance, HudArGeometry arGeometry) {
        return sceneForTest(fields(guidance, null, arGeometry)).compose(0L).road();
    }

    /** The same packet with the summary's two lines as given, to hold them to the frozen bytes. */
    static byte[] roadPacketForTest(HudGuidance guidance, HudArGeometry arGeometry,
            String summaryDistance, String summaryTime) {
        return sceneForTest(fields(guidance, null, arGeometry, summaryDistance, summaryTime))
                .compose(0L).road();
    }

    /** The clear the compositor sends once this source's route is gone. */
    static byte[] clearPacketForTest(HudGuidance guidance) {
        return sceneForTest(fields(guidance, null, null)).compose(0L).clear();
    }

    private static HudScene sceneForTest(Map<HudRole, HudFields> fields) {
        HudScene scene = new HudScene();
        for (Map.Entry<HudRole, HudFields> role : fields.entrySet()) {
            scene.fields(HudSource.CAPTURE, role.getKey(), role.getValue(), GUIDANCE_TTL_MS, 0L);
        }
        return scene;
    }
}
