package dev.denza.hud.probe;

import dev.denza.hudlink.HudMessage;
import dev.denza.hudlink.HudProtocol;
import dev.denza.hudlink.HudProtocol.Road;

/**
 * The road snapshot in this module's field order, on the wire Denza Apps uses too (`:hud-link`,
 * libraries/hud-link). No vehicle setters: the car's own speed sign needs a BYDAUTO permission this
 * process does not hold.
 */
final class HudWire {
    static final long ROAD = HudProtocol.TOPIC_ROAD, MAP = HudProtocol.TOPIC_MAP;

    static byte[] road(boolean active) { return road(active, null, null); }

    /** Road packet 0x8001: navigating (16 = 2) with whatever of the hint is known, or the clear (16 = 1). */
    static byte[] road(boolean active, NavigatorSource.Hint hint, byte[] icon) {
        return road(active, hint, icon, 0, null);
    }

    /** One complete road snapshot: lanes do not depend on a manoeuvre being available. */
    static byte[] road(boolean active, NavigatorSource.Hint hint, byte[] icon, int laneCount, byte[] lanes) {
        return road(active, hint, icon, laneCount, lanes, null);
    }

    static byte[] road(boolean active, NavigatorSource.Hint hint, byte[] icon, int laneCount, byte[] lanes, HudAr.Frame ar) {
        boolean hasLanes = active && laneCount > 0 && laneCount <= 6 && lanes != null && lanes.length > 0;
        HudMessage body = new HudMessage(256 + (icon == null ? 0 : icon.length) + (hasLanes ? lanes.length : 0))
                .number(Road.COUNTER, Road.STOCK_COUNTER)
                .number(Road.STATE, active ? Road.NAVIGATING : Road.OFF)
                .number(Road.LANE_COUNT, hasLanes ? laneCount : 0); // Explicit clear while the route stays active, too.
        if (hasLanes) body.bytes(Road.LANES_PNG, lanes);
        if (active && hint != null) {
            if (hint.remainingMeters >= 0) body.number(Road.REMAINING_METERS, hint.remainingMeters);
            if (hint.remainingSeconds >= 0) body.number(Road.REMAINING_SECONDS, hint.remainingSeconds);
            if (hint.icon != null) {
                body.bytes(Road.MANEUVER_PNG, icon)
                        .number(Road.MANEUVER_METERS, Math.max(0, hint.distance))
                        .text(Road.ROAD_NAME, hint.road);
            }
            if (hint.speedLimit > 0) body.number(Road.SPEED_LIMIT, hint.speedLimit); // currentMaxSpeedLimit, km/h
            if (hint.remainingMeters >= 0) body.text(Road.SUMMARY_DISTANCE, distance(hint.remainingMeters));
            if (hint.remainingSeconds >= 0) body.text(Road.SUMMARY_TIME, duration(hint.remainingSeconds));
            if (hint.icon != null) body.number(Road.STOCK_MANEUVER, hint.stockId);
            if (ar != null) {
                body.decimal(Road.LONGITUDE, ar.pose.point.lon)
                        .decimal(Road.LATITUDE, ar.pose.point.lat)
                        .number(Road.SPEED, Math.round(ar.pose.speed)) // m/s, unlike the road speed-limit field.
                        .number(Road.ALTITUDE, Math.round(ar.pose.altitude))
                        .text(Road.AR_LINE, ar.lineJson())
                        .text(Road.AR_POINT, ar.target.tuple())
                        .decimal(Road.HEADING, ar.pose.heading)
                        .decimal(Road.PROGRESS, ar.progress);
            }
        }
        return body.embedded(Road.ENVELOPE);
    }

    static byte[] map(byte[] png) { return HudProtocol.mapPayload(png); }

    /** The route summary as Denza Apps writes it: metres below 1 km, one decimal below 10 km. */
    static String distance(int meters) { return HudProtocol.routeDistance(meters); }

    /** Remaining time as Yandex prints it: «17 мин», «1 ч», «1 ч 5 мин». */
    static String duration(int seconds) { return HudProtocol.duration(seconds); }
}
