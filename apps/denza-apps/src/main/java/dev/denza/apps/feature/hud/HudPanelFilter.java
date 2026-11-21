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
 * <li>«Повороты» off: nothing of the module or the radar - unless a YandexHUD that knows the host is
 *     installed ({@code hosted}). Then nothing here switches its turns off, and the stored value
 *     holds back neither the module nor the radar (2026-11-21). Before, a fresh install, where it
 *     is off, let the module draw by itself until the host came up and then took its picture away.
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
    /** «Повороты» as stored. */
    final boolean enabled;
    /** A YandexHUD that publishes through the host is installed ({@link HudNavigatorInstall#YANDEX_HUD}). */
    final boolean hosted;
    final boolean map;
    final boolean sign;
    final boolean cameras;

    HudPanelFilter(boolean enabled, boolean hosted, boolean map, boolean sign, boolean cameras) {
        this.enabled = enabled;
        this.hosted = hosted;
        this.map = map;
        this.sign = sign;
        this.cameras = cameras;
    }

    /** The hints pass: «Повороты» on, or YandexHUD's, which nothing here switches off. */
    boolean hints() {
        return enabled || hosted;
    }

    /** HUD Speed is shown at all. */
    boolean camerasShown() {
        return hints() && cameras;
    }

    /** What the panel lets through from {@code source} for {@code role}. */
    boolean admits(HudSource source, HudRole role) {
        if (role == HudRole.ROAD_LIMIT && !sign) {
            return false;
        }
        boolean mapWindow = role == HudRole.MAP || role == HudRole.MAP_MARKS || role == HudRole.MAP_BADGE;
        if (source == HudSource.MODULE) {
            return hints() && (map || !mapWindow);
        }
        if (source == HudSource.RADAR) {
            return camerasShown() && (map || !mapWindow);
        }
        return true;
    }

    /** What the switches now hold back leaves {@code sink}. */
    void withdrawHeldBack(HudSink sink) {
        if (!hints()) {
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
