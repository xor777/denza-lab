package dev.denza.apps.feature.hud;

import dev.denza.hudlink.*;

/** Places an admitted semantic warning in the reserved map corner; never owns navigation. */
final class HudRadarSource {
    interface Renderer { HudImage draw(HudRadarWarning warning); }
    static final HudRect RECT = new HudRect(130, 0, 170, 78);
    private final HudSink sink;
    private final Renderer renderer;

    HudRadarSource(HudSink sink, Renderer renderer) {
        this.sink = sink;
        this.renderer = renderer;
    }

    void publish(HudRadarWarning warning, long now) {
        long ttl = warning.remainingMs(now);
        if (!warning.active || ttl == 0) {
            sink.withdraw(HudSource.RADAR);
            return;
        }
        sink.layer(HudSource.RADAR, "hud-speed", HudRole.MAP_BADGE, RECT,
                renderer.draw(warning), 1f, ttl);
    }
}
