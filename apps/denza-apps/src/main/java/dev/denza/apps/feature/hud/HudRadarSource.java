package dev.denza.apps.feature.hud;

import dev.denza.hudlink.HudImage;
import dev.denza.hudlink.HudRadarWarning;
import dev.denza.hudlink.HudRect;
import dev.denza.hudlink.HudRole;
import dev.denza.hudlink.HudSink;
import dev.denza.hudlink.HudSource;
import dev.denza.hudlink.HudWindow;

/**
 * HUD Speed in the map window (docs/hud-output-architecture.md, 3.5 and 7.3): z 300, over the
 * navigator's map and its own marks, never instead of its manoeuvre, lanes, limit or AR, never
 * opening navigation by itself. At the bottom - the top of the window is the road ahead, the
 * bottom is beside and behind the car: the warning left of the cursor, the GPS bars in the right
 * corner while the radar runs. Everything lives as long as the observation it came in.
 */
final class HudRadarSource {
    interface Renderer {
        /** The warning strip; its width is the picture's. */
        HudImage warning(HudRadarWarning warning);

        /** The GPS bars, or null for none. */
        HudImage gps(int level);
    }

    static final String WARNING = "warning";
    static final String GPS = "gps";
    /** From the window's edge, as the glass keeps its event marks (hud-scene.js EVENTS.edge). */
    static final int MARGIN = 6;

    private final HudSink sink;
    private final Renderer renderer;

    HudRadarSource(HudSink sink, Renderer renderer) {
        this.sink = sink;
        this.renderer = renderer;
    }

    void publish(HudRadarWarning observation, long now) {
        long ttl = observation.remainingMs(now);
        if (!observation.running || ttl == 0) {
            sink.withdraw(HudSource.RADAR);
            return;
        }
        if (observation.active) {
            HudImage strip = renderer.warning(observation);
            sink.layer(HudSource.RADAR, WARNING, HudRole.MAP_BADGE, warningRect(strip.width), strip, 1f, ttl);
        } else {
            sink.removeLayer(HudSource.RADAR, WARNING);
        }
        HudImage bars = renderer.gps(observation.gpsLevel);
        if (bars != null) {
            sink.layer(HudSource.RADAR, GPS, HudRole.MAP_BADGE, gpsRect(), bars, 1f, ttl);
        } else {
            sink.removeLayer(HudSource.RADAR, GPS);
        }
    }

    static HudRect warningRect(int width) {
        return new HudRect(MARGIN, HudWindow.MAP.height - MARGIN - HudRadarPainter.TILE,
                Math.min(width, HudRadarPainter.MAX_WIDTH), HudRadarPainter.TILE);
    }

    static HudRect gpsRect() {
        int size = HudRadarPainter.GPS;
        return new HudRect(HudWindow.MAP.width - MARGIN - size, HudWindow.MAP.height - MARGIN - size, size, size);
    }
}
