package dev.denza.hud.probe;

import dev.denza.hudlink.HudFields;
import dev.denza.hudlink.HudProtocol;
import dev.denza.hudlink.HudRoadPacket;
import dev.denza.hudlink.HudRole;
import dev.denza.hudlink.HudScene;
import dev.denza.hudlink.HudSource;
import java.util.Map;

/**
 * The module's road snapshot as the HUD gets it, for the host tests: its fields by role
 * ({@link ModuleFields}) composed by the `:hud-link` compositor ({@link HudScene}), the path every
 * frame takes on the car. No vehicle setters: the car's own speed sign needs a BYDAUTO permission
 * this process does not hold.
 */
final class HudWire {
    private HudWire() { }

    static byte[] road(boolean active) { return road(active, null, null); }

    /** Road packet 0x8001: navigating (16 = 2) with whatever of the hint is known, or the clear (16 = 1). */
    static byte[] road(boolean active, NavigatorSource.Hint hint, byte[] icon) {
        return road(active, hint, icon, 0, null);
    }

    /** One complete road snapshot: lanes do not depend on a manoeuvre being available. */
    static byte[] road(boolean active, NavigatorSource.Hint hint, byte[] icon, int laneCount, byte[] lanes) {
        return road(active, hint, icon, laneCount, lanes, null);
    }

    /**
     * Active: the module's fields composed alone, as its own output composes them while it draws
     * by itself. Inactive: the clear after a route, which always showed lanes (5 = 0).
     */
    static byte[] road(boolean active, NavigatorSource.Hint hint, byte[] icon, int laneCount, byte[] lanes, HudAr.Frame ar) {
        if (!active) return HudRoadPacket.clear(true);
        HudScene scene = new HudScene();
        for (Map.Entry<HudRole, HudFields> role : ModuleFields.of(hint, icon, laneCount, lanes, ar).entrySet()) {
            scene.fields(HudSource.MODULE, role.getKey(), role.getValue(), HudScene.LAYER_TTL_MS, 0L);
        }
        return scene.compose(0L).road();
    }

    static byte[] map(byte[] png) { return HudProtocol.mapPayload(png); }

    /** The route summary as Denza Apps writes it: metres below 1 km, one decimal below 10 km. */
    static String distance(int meters) { return HudProtocol.routeDistance(meters); }

    /** Remaining time as Yandex prints it: «17 мин», «1 ч», «1 ч 5 мин». */
    static String duration(int seconds) { return HudProtocol.duration(seconds); }
}
