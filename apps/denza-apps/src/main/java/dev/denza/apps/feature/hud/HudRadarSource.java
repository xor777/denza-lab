package dev.denza.apps.feature.hud;

import dev.denza.hudlink.HudFields;
import dev.denza.hudlink.HudImage;
import dev.denza.hudlink.HudProtocol.Road;
import dev.denza.hudlink.HudRadarWarning;
import dev.denza.hudlink.HudRect;
import dev.denza.hudlink.HudRole;
import dev.denza.hudlink.HudSink;
import dev.denza.hudlink.HudSource;
import dev.denza.hudlink.HudWindow;

/**
 * HUD Speed on the projection (docs/hud-output-architecture.md, 3.5, 7.3 and 7.4): its warning in
 * the lane window and nowhere else, its GPS bars at the bottom right of the map while the radar runs.
 * Over the map the warning was small, faded at the window's edges and in the road's way, and the
 * owner took it off the map on the glass on 2026-10-18. Never instead of the navigator's
 * manoeuvre, limit or AR, never opening navigation by itself. Everything lives as long as the
 * observation it came in.
 */
final class HudRadarSource {
    interface Renderer {
        /** The warning as the lane window's picture, {@link HudRadarPainter#LANE_WIDTH} wide. */
        HudImage lanes(HudRadarWarning warning);

        /** The GPS bars, or null for none. */
        HudImage gps(int level);
    }

    static final String GPS = "gps";
    /** The GPS bars: on the map window's bottom edge, and this far from its right one, clear of the fade there. */
    static final int BOTTOM = 0;
    static final int RIGHT = 35;

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
            sink.fields(HudSource.RADAR, HudRole.LANES, new HudFields.Builder()
                    .number(Road.LANE_COUNT, HudRadarPainter.LANE_CELLS)
                    .bytes(Road.LANES_PNG, renderer.lanes(observation).png())
                    .build(), ttl);
        } else {
            sink.withdraw(HudSource.RADAR, HudRole.LANES);
        }
        HudImage bars = renderer.gps(observation.gpsLevel);
        if (bars != null) {
            sink.layer(HudSource.RADAR, GPS, HudRole.MAP_BADGE, gpsRect(), bars, 1f, ttl);
        } else {
            sink.removeLayer(HudSource.RADAR, GPS);
        }
    }

    static HudRect gpsRect() {
        int size = HudRadarPainter.GPS;
        return new HudRect(HudWindow.MAP.width - RIGHT - size, HudWindow.MAP.height - BOTTOM - size,
                size, size);
    }
}
