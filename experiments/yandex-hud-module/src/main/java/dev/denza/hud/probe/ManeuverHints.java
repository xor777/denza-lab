package dev.denza.hud.probe;

/**
 * The hint the module publishes for a frame: {@link ManeuverHold} through a gap, then
 * {@link ManeuverMeters}' rounding. {@code HudModule} calls {@link #apply} on every tick, before it
 * skips a frame or returns on one without a route, so both start over where they must:
 *
 * <ul>
 * <li>guidance off (cancelled, finished, hidden without background guidance): the hold and the
 *     metres both forget the route;
 * <li>another route id: the metres start over. Every finish sits at the same place, 0 m before the
 *     finish, so without this a new route's finish showed the old one's last metres and could not
 *     grow back (2026-11-19). The hold stays: repeating the last manoeuvre while a route is
 *     rebuilt is what it is for.
 * </ul>
 */
final class ManeuverHints {
    final ManeuverHold hold = new ManeuverHold();
    private final ManeuverMeters meters = new ManeuverMeters();
    private String route;

    NavigatorSource.Hint apply(NavigatorSource.Frame frame, long now) {
        if (!frame.active()) {
            hold.reset();
            meters.reset();
            route = null;
            return null;
        }
        if (frame.routeId != null && !frame.routeId.equals(route)) {
            meters.reset();
            route = frame.routeId;
        }
        NavigatorSource.Hint hint = hold.apply(frame, now);
        meters.apply(hint);
        return hint;
    }
}
