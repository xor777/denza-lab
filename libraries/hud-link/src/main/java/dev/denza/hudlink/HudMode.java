package dev.denza.hudlink;

/**
 * Which of the HUD's windows are open together (docs/hud-output-architecture.md, 3.4). The
 * compositor picks it from what is published; sources never name it.
 */
public enum HudMode {
    /** The road packet is inactive: the HUD shows its own speed, and nothing of ours. */
    OFF,
    /**
     * Field 16 = 2: the map window and the manoeuvre block open together. Chosen only when the
     * block can be filled, or the HUD fills it with its own Chinese captions.
     */
    NAVIGATION,
}
