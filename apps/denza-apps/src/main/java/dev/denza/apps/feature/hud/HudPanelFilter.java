package dev.denza.apps.feature.hud;

import dev.denza.hudlink.HudRole;
import dev.denza.hudlink.HudSink;
import dev.denza.hudlink.HudSource;

/**
 * What the HUD panel's switches let through to the host's compositor ({@link HudHostOutput}),
 * and what leaves the glass at once when one of them goes off. Plain Java over the switches'
 * values ({@link HudGuidanceSettings}).
 *
 * <ul>
 * <li>«Повороты» off: nothing of the module or the radar.
 * <li>«Карта» off: no map window at all - the module's map and marks, and the radar's GPS bars,
 *     which alone kept a black map with the bars lit until 2026-11-19
 *     (docs/hud-output-architecture.md, the panel's acceptance). The radar's warning in the lane
 *     window stays.
 * <li>«Ограничение скорости» off: no source's limit.
 * <li>«Камеры» off: nothing of the radar.
 * </ul>
 * The capture keeps its own switches.
 */
final class HudPanelFilter {
    final boolean enabled;
    final boolean map;
    final boolean sign;
    final boolean cameras;

    HudPanelFilter(boolean enabled, boolean map, boolean sign, boolean cameras) {
        this.enabled = enabled;
        this.map = map;
        this.sign = sign;
        this.cameras = cameras;
    }

    /** HUD Speed is shown at all. */
    boolean camerasShown() {
        return enabled && cameras;
    }

    /** What the panel lets through from {@code source} for {@code role}. */
    boolean admits(HudSource source, HudRole role) {
        if (role == HudRole.ROAD_LIMIT && !sign) {
            return false;
        }
        boolean mapWindow = role == HudRole.MAP || role == HudRole.MAP_MARKS || role == HudRole.MAP_BADGE;
        if (source == HudSource.MODULE) {
            return enabled && (map || !mapWindow);
        }
        if (source == HudSource.RADAR) {
            return camerasShown() && (map || !mapWindow);
        }
        return true;
    }

    /** What the switches now hold back leaves {@code sink}. */
    void withdrawHeldBack(HudSink sink) {
        if (!enabled) {
            sink.withdraw(HudSource.MODULE);
        } else if (!map) {
            sink.withdraw(HudSource.MODULE, HudRole.MAP);
            sink.withdraw(HudSource.MODULE, HudRole.MAP_MARKS);
        }
        if (!sign) {
            for (HudSource source : HudSource.values()) {
                sink.withdraw(source, HudRole.ROAD_LIMIT);
            }
        }
        if (!camerasShown()) {
            sink.withdraw(HudSource.RADAR);
        } else if (!map) {
            sink.withdraw(HudSource.RADAR, HudRole.MAP_BADGE);
        }
    }
}
