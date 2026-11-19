package dev.denza.hud.probe;

/**
 * Keeps the HUD's manoeuvre block filled through a gap in the guidance. The module's route opens
 * navigation by itself ({@code HudRole.opensNavigation}), and a block left without a manoeuvre is
 * one the HUD fills with its own Chinese labels; drivers saw them while Navigator rebuilt the route
 * (2026-10-21). So a frame without a manoeuvre ahead is given the last one's {@link Shape} - and
 * only that:
 *
 * <ul>
 * <li>while the route is rebuilt or the hint cannot be read, up to {@link #REBUILD_MS} - the time a
 *     lost position is held, after which the block empties as before;
 * <li>when the guidance simply has no manoeuvre ahead, up to {@link #GAP_MS}: long enough for a
 *     blink of the windshield, short enough that a turn already taken is not shown down a long
 *     straight.
 * </ul>
 *
 * <p>Everything outside the shape - the street, the route totals, the limit, the lanes, AR, the
 * map - is the current frame's, or the field's «unknown» when the current frame has none
 * (docs/hud-output-architecture.md, 3.4). Twice a held manoeuvre carried more than its shape
 * (2026-11-19: the old limit into the car's own speed-limit assistance, then the old route's totals
 * for 30 s), so the hold no longer keeps a hint at all, only the four values the table allows.
 * No route, no hold: the next route starts from nothing ({@link #reset}).
 */
final class ManeuverHold {
    static final long REBUILD_MS = NavigatorSource.DIM_AFTER_MS;
    static final long GAP_MS = 3000;

    /**
     * What a held manoeuvre gives a frame without one: the block the HUD already shows. Field 9 is
     * what makes the block filled ({@code HudRole.fillsNavigation}); the arrow is fields 8 and 28,
     * drawn from icon, exit and stock id. Nothing else, ever.
     */
    static final class Shape {
        final String icon;
        final int exit, stockId, meters;

        Shape(NavigatorSource.Hint shown) {
            icon = shown.icon;
            exit = shown.exit;
            stockId = shown.stockId;
            meters = shown.distance;
        }

        void into(NavigatorSource.Hint hint) {
            hint.icon = icon;
            hint.exit = exit;
            hint.stockId = stockId;
            hint.distance = meters;
        }
    }

    private Shape last;
    private long lastAt;
    /** Why the hint now published carries a held shape, for the module log; null while it is current. */
    String holding;

    /**
     * The hint to publish for {@code frame}: {@code current} - the frame's own, already a copy - as
     * it is, the same with the last shape in it, or null.
     */
    NavigatorSource.Hint apply(NavigatorSource.Frame frame, NavigatorSource.Hint current, long now) {
        holding = null;
        if (!frame.drawMap()) {
            last = null;
            return null;
        }
        if (current != null && current.icon != null) {
            last = new Shape(current);
            lastAt = now;
            return current;
        }
        boolean rebuilding = current == null; // rerouting, or the hint could not be read
        if (last == null || now - lastAt > (rebuilding ? REBUILD_MS : GAP_MS)) return current;
        holding = rebuilding ? (frame.mode.equals("rerouting") ? "rerouting" : "unread") : "no-manoeuvre";
        NavigatorSource.Hint held = current != null ? current : new NavigatorSource.Hint();
        last.into(held);
        return held;
    }

    /** Guidance went off: nothing of this route is repeated into the next. */
    void reset() {
        last = null;
        holding = null;
    }
}
