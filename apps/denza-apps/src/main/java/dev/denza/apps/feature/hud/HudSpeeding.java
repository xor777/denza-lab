package dev.denza.apps.feature.hud;

import dev.denza.hudlink.HudRadarWarning;

/**
 * Whether the speed means a fine at HUD Speed's camera ahead, and how red its warning is for it
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
    /** Red mixed into the card's picture at full red ({@link HudTint}); the map stays as it is. */
    static final float TINT = 0.6f;
    /** The level moves in these steps, so a picture is drawn again only when it shows. */
    private static final float STEP = 1f / 32f;
    /** {@link HudRadarWarning#unit} for miles per hour; 1 is km/h. */
    static final int MPH = 2;
    static final double KMH_PER_MPH = 1.609344;

    private boolean fined;
    private float level;
    private long at = -1L;
    /** The last camera {@link #update(HudRadarWarning, Double, long)} was given; null before any. */
    private HudRadarWarning camera;

    /**
     * The red now for {@code camera} - the warning shown, null for none - and the wheels'
     * {@code kmh}, null when the car has not said or its reading went stale: no speed is no red.
     * Another camera starts plain: the latch and the red were the previous one's, and at 69 km/h
     * a 50 camera's red stayed on for a 60 camera the driver never went over by ten (2026-11-19).
     */
    float update(HudRadarWarning camera, Double kmh, long now) {
        if (camera == null) {
            return update(0, null, now);
        }
        if (this.camera != null && !sameCamera(this.camera, camera)) {
            reset();
        }
        this.camera = camera;
        return update(camera.limit, inUnit(kmh, camera.unit), now);
    }

    /** The same camera's warning: its object, its limit and its unit; a new observation of it is the same. */
    static boolean sameCamera(HudRadarWarning a, HudRadarWarning b) {
        return a.objectId == b.objectId && a.limit == b.limit && a.unit == b.unit;
    }

    /**
     * {@code kmh} in a camera's unit: 1 km/h, 2 mph; null stays null. An if, not a conditional
     * expression: {@code Double} beside {@code double} in one unboxes a null into an exception.
     */
    static Double inUnit(Double kmh, int unit) {
        if (kmh == null || unit != MPH) {
            return kmh;
        }
        return kmh / KMH_PER_MPH;
    }

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
        camera = null;
    }
}
