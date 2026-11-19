package dev.denza.hud.probe;

/**
 * The hint the module publishes for a frame. {@code HudModule} calls {@link #apply} on every tick,
 * before it skips a frame or returns on one without a route, in this order:
 *
 * <ol>
 * <li>a copy of the frame's own hint, its metres through {@link ManeuverMeters} - only a manoeuvre
 *     this frame measured is counted, and the counter keys it by route id and place, so a new route
 *     starts over without a reset to remember;
 * <li>then {@link ManeuverHold}, which may put the last manoeuvre's shape into a frame without one.
 *     Nothing it holds goes back into the counter (2026-11-19: a held old finish at 0 m capped the
 *     new route's finish, docs/hud-output-architecture.md, 3.4).
 * </ol>
 * Guidance off (cancelled, finished, hidden without background guidance): both forget the route.
 */
final class ManeuverHints {
    final ManeuverHold hold = new ManeuverHold();
    private final ManeuverMeters meters = new ManeuverMeters();

    NavigatorSource.Hint apply(NavigatorSource.Frame frame, long now) {
        if (!frame.active()) {
            hold.reset();
            meters.reset();
            return null;
        }
        NavigatorSource.Hint current = frame.hint == null ? null : frame.hint.copy();
        if (current != null && current.icon != null && current.distance >= 0) {
            current.distance = meters.shown(frame.routeId, current.at, current.distance);
        }
        return hold.apply(frame, current, now);
    }
}
