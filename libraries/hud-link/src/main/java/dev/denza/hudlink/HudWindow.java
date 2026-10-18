package dev.denza.hudlink;

/**
 * The HUD's windows, fixed by the firmware (docs/hud-output-architecture.md, 3.3). Only the map
 * window is built from layers; the pictures in the road packet are fields with one winner.
 */
public enum HudWindow {
    /** Event {@code 0x8003}: one Base64 PNG, 300×180 whole, a larger one is cut. Built from layers. */
    MAP(300, 180, true),
    /** Road packet field 8: the manoeuvre picture, 80×80 stock. */
    MANEUVER(80, 80, false),
    /** Road packet field 7, 41×N on 60, with the lane count in field 5. */
    LANES(0, 60, false),
    /** The road packet's scalar fields: metres, street, limit, summaries, AR. */
    FIELDS(0, 0, false);

    /** Pixels; 0 where the size is not fixed. */
    public final int width;
    public final int height;
    /** Whether the window is drawn from layers ({@link HudLayer}) rather than taken from a field. */
    public final boolean layered;

    HudWindow(int width, int height, boolean layered) {
        this.width = width;
        this.height = height;
        this.layered = layered;
    }
}
