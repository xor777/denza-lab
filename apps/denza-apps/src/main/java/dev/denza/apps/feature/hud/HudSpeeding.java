package dev.denza.apps.feature.hud;

/**
 * Whether the speed means a fine at HUD Speed's camera ahead, and how red the glass is for it
 * (docs/hud-output-architecture.md, 7.5). The owner's rule, 2026-11-02: more than the camera's
 * limit plus {@link #OVER} is a certain fine. The car's own speed from its wheels, which reads a
 * little above the true one; the owner took that over a speed the camera would agree with.
 *
 * <p>The red comes and goes over a moment, not at once, and goes only at {@link #RELEASE} over
 * the limit, so a speed on the line does not make it blink. Plain Java on a caller's clock.
 */
final class HudSpeeding {
    /** Over the limit by more than this: a fine. */
    static final int OVER = 10;
    /** And at this or less over it the red goes again. */
    static final int RELEASE = 8;
    /** How fast the red comes and goes: the time to two thirds of the way. */
    static final long RISE_MS = 350L;
    static final long FALL_MS = 800L;
    /** The card's background at full red: this much of HUD Speed's red over black. */
    static final float BACKGROUND = 0.5f;
    /** Red mixed into the card's and the map's pictures at full red ({@link dev.denza.hudlink.HudTint}). */
    static final float TINT = 0.6f;
    /** The level moves in these steps, so a picture is drawn again only when it shows. */
    private static final float STEP = 1f / 32f;

    private boolean fined;
    private float level;
    private long at = -1L;

    /**
     * The red now, 0 to 1. {@code limit}: the camera's, 0 for none; {@code speed}: in the limit's
     * unit, null when the car has not said. Neither a limit nor a speed means no fine.
     */
    float update(int limit, Double speed, long now) {
        if (limit <= 0 || speed == null) {
            fined = false;
        } else if (speed > limit + OVER) {
            fined = true;
        } else if (speed <= limit + RELEASE) {
            fined = false;
        }
        float target = fined ? 1f : 0f;
        long dt = at < 0L ? 0L : Math.max(0L, now - at);
        at = now;
        level += (target - level) * (1f - (float) Math.exp(-dt / (double) (fined ? RISE_MS : FALL_MS)));
        if (Math.abs(target - level) < STEP / 2f) {
            level = target;
        }
        return level();
    }

    /** The red as last worked out, in {@link #STEP}s. */
    float level() {
        return Math.round(level / STEP) * STEP;
    }

    /** Nothing red and nothing on its way. */
    boolean settled() {
        return !fined && level == 0f;
    }

    /** Back to no red at once: the cameras went off. */
    void reset() {
        fined = false;
        level = 0f;
        at = -1L;
    }
}
