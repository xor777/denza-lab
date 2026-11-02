package dev.denza.hud.probe;

/**
 * The metres to the manoeuvre as the HUD shows them, field 9. Navigator measures them anew every
 * frame, ten a second, and the fix moves them back and forth by a few metres, so the digits on the
 * glass never stood still while Navigator's own screen shows calm round numbers (owner,
 * 2026-11-02). So they are rounded as Navigator rounds its own: to the nearest multiple of a step
 * that grows with the distance ({@link #round}), with no averaging in time.
 *
 * <p>One thing more than Navigator: within one manoeuvre the number only goes down. A fix that
 * wanders across a step's middle would show 250, 300, 250, and the HUD's AR scene starts over
 * whenever field 9 grows (docs/instrument-display-findings.md, the AR arrow's scene machine).
 * The map's zoom and the AR geometry keep the exact distance; only what the HUD prints is rounded.
 * The wire's 11 m floor comes after this ({@code ModuleFields}).
 */
final class ManeuverMeters {
    /** A manoeuvre is the same one while its place on the route moves by no more than this. */
    static final int SAME_PLACE = 5;

    private int at = -1;
    private int shown;

    /** {@code hint}'s distance as the HUD shows it, written back into it; nothing without a manoeuvre. */
    void apply(NavigatorSource.Hint hint) {
        if (hint == null || hint.icon == null || hint.distance < 0) return;
        int rounded = round(hint.distance);
        if (hint.at < 0 || at < 0 || Math.abs(hint.at - at) > SAME_PLACE) shown = rounded;
        else shown = Math.min(shown, rounded);
        at = hint.at;
        hint.distance = shown;
    }

    /**
     * Navigator's rounding: to the nearest multiple of 1 m under 10 m, 10 m up to 50 m, 50 m up to
     * 300 m, 100 m up to 3 km and 1 km beyond (owner, 2026-11-02). The steps meet so that a shorter
     * distance never shows longer: 50 and 51 m both show 50, 3000 and 3001 m both 3000.
     */
    static int round(int meters) {
        int step = meters < 10 ? 1 : meters <= 50 ? 10 : meters <= 300 ? 50 : meters <= 3000 ? 100 : 1000;
        return (meters + step / 2) / step * step;
    }
}
