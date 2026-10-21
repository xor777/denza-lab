package dev.denza.hud.probe;

/**
 * Keeps the HUD's manoeuvre block filled through a gap in the guidance. The module's route opens
 * navigation by itself ({@code HudRole.opensNavigation}), and a block left without a manoeuvre is
 * one the HUD fills with its own Chinese labels; drivers saw them while Navigator rebuilt the route
 * (2026-10-21). So a frame without a manoeuvre ahead repeats the last one shown, with the route
 * totals and the limit of the frame when it has them:
 *
 * <ul>
 * <li>while the route is rebuilt or the hint cannot be read, up to {@link #REBUILD_MS} - the time a
 *     lost position is held, after which the block empties as before;
 * <li>when the guidance simply has no manoeuvre ahead, up to {@link #GAP_MS}: long enough for a
 *     blink of the windshield, short enough that a turn already taken is not shown down a long
 *     straight.
 * </ul>
 * No route, no hold: the next route starts from nothing.
 */
final class ManeuverHold {
    static final long REBUILD_MS = NavigatorSource.DIM_AFTER_MS;
    static final long GAP_MS = 3000;

    private NavigatorSource.Hint last;
    private long lastAt;
    /** Why the hint now published is a repeat, for the module log; null while it is current. */
    String holding;

    /** The hint to publish for {@code frame} at {@code now}: its own, the last manoeuvre again, or null. */
    NavigatorSource.Hint apply(NavigatorSource.Frame frame, long now) {
        holding = null;
        if (!frame.drawMap()) {
            last = null;
            return null;
        }
        NavigatorSource.Hint current = frame.hint;
        if (current != null && current.icon != null) {
            last = current;
            lastAt = now;
            return current;
        }
        boolean rebuilding = current == null; // rerouting, or the hint could not be read
        if (last == null || now - lastAt > (rebuilding ? REBUILD_MS : GAP_MS)) return current;
        holding = rebuilding ? (frame.mode.equals("rerouting") ? "rerouting" : "unread") : "no-manoeuvre";
        return repeat(last, current);
    }

    /** {@code manoeuvre}'s block with {@code now}'s totals and limit when there are any. */
    private static NavigatorSource.Hint repeat(NavigatorSource.Hint manoeuvre, NavigatorSource.Hint now) {
        NavigatorSource.Hint hint = new NavigatorSource.Hint();
        hint.icon = manoeuvre.icon;
        hint.action = manoeuvre.action;
        hint.road = manoeuvre.road;
        hint.position = manoeuvre.position;
        hint.followingPosition = manoeuvre.followingPosition;
        hint.stockId = manoeuvre.stockId;
        hint.exit = manoeuvre.exit;
        hint.distance = manoeuvre.distance;
        NavigatorSource.Hint totals = now != null ? now : manoeuvre;
        hint.remainingMeters = totals.remainingMeters;
        hint.remainingSeconds = totals.remainingSeconds;
        hint.speedLimit = totals.speedLimit;
        hint.limitValue = totals.limitValue;
        hint.limitText = totals.limitText;
        return hint;
    }
}
