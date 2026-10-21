package dev.denza.hud.probe;

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
import java.util.Map;

/**
 * One output frame of the module as a source of the compositor (docs/hud-output-architecture.md,
 * 3.5): the painter's map PNG as the map window's only layer, whole and opaque, so it goes to the
 * HUD as painted; and the road fields by role, exactly the values the module's own encoder wrote
 * before the compositor. The compositor writes them back in that order (`HudRoadPacket`), so
 * ModuleTest and ArTest hold the same bytes. Only while the route is active; without one the
 * module withdraws everything ({@code HudSink.withdraw(MODULE)}).
 *
 * <ul>
 * <li>MANEUVER always, possibly empty: the module's route opens navigation by itself
 *     ({@code HudRole.opensNavigation}), a manoeuvre ahead or not.
 * <li>LANES always: the row and its count, or the explicit «no lanes» (5 = 0) the module has
 *     always sent while a route stays active.
 * <li>ROAD_LIMIT and AR only with a limit and a fresh AR frame; otherwise withdrawn. Both ride on
 *     a hint, as they always did.
 * </ul>
 */
final class ModuleFields {
    static final String MAP_LAYER = "map";
    /** Every role the module publishes fields to, in the order it publishes them. */
    static final HudRole[] ROLES = {HudRole.MANEUVER, HudRole.LANES, HudRole.ROAD_LIMIT, HudRole.AR};
    /** At most this many lanes in a row: a wider sign is not shown at all. */
    static final int MAX_LANES = 6;

    private ModuleFields() { }

    /**
     * One frame's fields by role; a role missing from the map is withdrawn. {@code icon}: the
     * manoeuvre's stock PNG; {@code lanes}: the row's PNG for {@code laneCount} lanes;
     * {@code ar}: a fresh AR frame or null.
     */
    static Map<HudRole, HudFields> of(NavigatorSource.Hint hint, byte[] icon, int laneCount, byte[] lanes, HudAr.Frame ar) {
        EnumMap<HudRole, HudFields> roles = new EnumMap<>(HudRole.class);
        HudFields.Builder maneuver = new HudFields.Builder();
        if (hint != null) {
            if (hint.remainingMeters >= 0) {
                maneuver.number(Road.REMAINING_METERS, hint.remainingMeters)
                        .text(Road.SUMMARY_DISTANCE, HudProtocol.routeDistance(hint.remainingMeters));
            }
            if (hint.remainingSeconds >= 0) {
                maneuver.number(Road.REMAINING_SECONDS, hint.remainingSeconds)
                        .text(Road.SUMMARY_TIME, HudProtocol.duration(hint.remainingSeconds));
            }
            if (hint.icon != null) {
                // The wire floors field 9 at 11 m (0-10 is the HUD's Chinese «now»), but a Denza Apps
                // host older than that floor writes these fields with its own encoder: floor them here too.
                maneuver.bytes(Road.MANEUVER_PNG, icon)
                        .number(Road.MANEUVER_METERS, Math.max(Road.MANEUVER_METERS_MIN, hint.distance))
                        .text(Road.ROAD_NAME, hint.road)
                        .number(Road.STOCK_MANEUVER, hint.stockId);
            }
        }
        roles.put(HudRole.MANEUVER, maneuver.build());

        boolean hasLanes = laneCount > 0 && laneCount <= MAX_LANES && lanes != null && lanes.length > 0;
        HudFields.Builder row = new HudFields.Builder().number(Road.LANE_COUNT, hasLanes ? laneCount : 0);
        if (hasLanes) row.bytes(Road.LANES_PNG, lanes);
        roles.put(HudRole.LANES, row.build());

        if (hint != null && hint.speedLimit > 0) {
            // currentMaxSpeedLimit, km/h.
            roles.put(HudRole.ROAD_LIMIT, new HudFields.Builder().number(Road.SPEED_LIMIT, hint.speedLimit).build());
        }
        if (hint != null && ar != null) {
            roles.put(HudRole.AR, new HudFields.Builder()
                    .decimal(Road.LONGITUDE, ar.pose.point.lon)
                    .decimal(Road.LATITUDE, ar.pose.point.lat)
                    .number(Road.SPEED, Math.round(ar.pose.speed)) // m/s, unlike the road speed-limit field.
                    .number(Road.ALTITUDE, Math.round(ar.pose.altitude))
                    .text(Road.AR_LINE, ar.lineJson())
                    .text(Road.AR_POINT, ar.target.tuple())
                    .decimal(Road.HEADING, ar.pose.heading)
                    .decimal(Road.PROGRESS, ar.progress)
                    .build());
        }
        return roles;
    }

    /**
     * One frame into {@code sink} as the module's: {@code map} as the map window, then
     * {@code roles}, withdrawing every role of {@link #ROLES} not in it. Everything lives
     * {@link HudScene#LAYER_TTL_MS}: a module that stops publishing leaves the glass after 1.5 s,
     * as its own watchdog emptied the slot.
     */
    static void publish(HudSink sink, HudImage map, Map<HudRole, HudFields> roles) {
        sink.layer(HudSource.MODULE, MAP_LAYER, HudRole.MAP, HudRect.whole(HudWindow.MAP), map, 1f, HudScene.LAYER_TTL_MS);
        for (HudRole role : ROLES) {
            HudFields values = roles.get(role);
            if (values != null) sink.fields(HudSource.MODULE, role, values, HudScene.LAYER_TTL_MS);
            else sink.withdraw(HudSource.MODULE, role);
        }
    }
}
