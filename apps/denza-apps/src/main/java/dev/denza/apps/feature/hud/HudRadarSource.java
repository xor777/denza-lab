package dev.denza.apps.feature.hud;

import dev.denza.hudlink.HudImage;
import dev.denza.hudlink.HudRadarWarning;
import dev.denza.hudlink.HudRect;
import dev.denza.hudlink.HudRole;
import dev.denza.hudlink.HudSink;
import dev.denza.hudlink.HudSource;
import dev.denza.hudlink.HudWindow;

import java.util.function.IntSupplier;

/**
 * The radar's warning in the map window (docs/hud-output-architecture.md, 3.5: z 300, a reserved
 * corner): over the navigator's map, never instead of its manoeuvre, lanes, limit or AR, and never
 * opening navigation by itself.
 *
 * <p>The corner is the top one the route does not turn into, as the glass once placed its lanes:
 * a right turn leaves the left corner free, a left turn the right one; straight on, the left,
 * since the map puts its own event marks to the right of the route first. It is chosen when a
 * warning's object first appears and kept for it, so a badge never jumps while the car approaches.
 */
final class HudRadarSource {
    interface Renderer {
        HudImage draw(HudRadarWarning warning);
    }

    static final String LAYER = "warning";
    /** From the window's edge, as the glass keeps its event marks (hud-scene.js EVENTS.edge). */
    static final int MARGIN = 4;

    private final HudSink sink;
    private final Renderer renderer;
    private final IntSupplier maneuver;
    private boolean shown;
    private int objectId;
    private boolean left = true;

    /** {@code maneuver}: field 28 of the manoeuvre the HUD shows now, 0 when none. */
    HudRadarSource(HudSink sink, Renderer renderer, IntSupplier maneuver) {
        this.sink = sink;
        this.renderer = renderer;
        this.maneuver = maneuver;
    }

    void publish(HudRadarWarning warning, long now) {
        long ttl = warning.remainingMs(now);
        if (!warning.active || ttl == 0) {
            shown = false;
            sink.withdraw(HudSource.RADAR);
            return;
        }
        if (!shown || warning.objectId != objectId) {
            shown = true;
            objectId = warning.objectId;
            left = leftCorner(maneuver.getAsInt());
        }
        sink.layer(HudSource.RADAR, LAYER, HudRole.MAP_BADGE, rect(left), renderer.draw(warning), 1f, ttl);
    }

    /** Whether the badge takes the top-left corner for a manoeuvre with stock id {@code stockId}. */
    static boolean leftCorner(int stockId) {
        switch (stockId) {
            case 1: // left
            case 3: // slight left
            case 7: // sharp left
            case 9: // U-turn left
                return false;
            default:
                return true;
        }
    }

    static HudRect rect(boolean left) {
        int x = left ? MARGIN : HudWindow.MAP.width - MARGIN - HudRadarPainter.WIDTH;
        return new HudRect(x, MARGIN, HudRadarPainter.WIDTH, HudRadarPainter.HEIGHT);
    }
}
