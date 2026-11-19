package dev.denza.hud.probe;

import java.util.Objects;

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
 *
 * <p>A manoeuvre is its route and its place on it ({@link #shown}): every finish is at 0, so the
 * place alone does not tell one route's finish from the next one's, and the route id in the key is
 * what starts a new route over - not a reset someone has to remember to call. Only a manoeuvre the
 * current frame measured comes here; one repeated through a gap ({@code ManeuverHold}) never does,
 * so it cannot hold a new route's number down (2026-11-19, docs/hud-output-architecture.md, 3.4).
 */
final class ManeuverMeters {
    /** A manoeuvre is the same one while its place on the route moves by no more than this. */
    static final int SAME_PLACE = 5;

    private String route;
    private int at = -1;
    private int shown;

    /** Guidance off: nothing of this route is remembered. */
    void reset() {
        route = null;
        at = -1;
        shown = 0;
    }

    /** {@code distance} metres to the manoeuvre at {@code at} on {@code route}, as the HUD shows them. */
    int shown(String route, int at, int distance) {
        int rounded = round(distance);
        boolean same = at >= 0 && this.at >= 0 && Math.abs(at - this.at) <= SAME_PLACE && Objects.equals(route, this.route);
        shown = same ? Math.min(shown, rounded) : rounded;
        this.route = route;
        this.at = at;
        return shown;
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
