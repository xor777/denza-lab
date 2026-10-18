package dev.denza.hudlink;

/**
 * Who publishes to the HUD (docs/hud-output-architecture.md, 4). All of them are ours, so the
 * placement table ({@link HudRole}) ranks them per role and no source negotiates with another.
 */
public enum HudSource {
    /** The YandexHUD module inside Navigator: its own map, hints, lanes, limit and AR geometry. */
    MODULE,
    /** Denza Apps reading ordinary Yandex: the mirrored screen, its accessibility nodes, its notification. */
    CAPTURE,
    /** Camera warnings from the radar app, read by Denza Apps. */
    RADAR,
}
