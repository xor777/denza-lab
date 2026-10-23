package dev.denza.hud.probe;

/**
 * Our own map-to-ADAS feed as the overlay sees it: whether the bridge produces anything, when it
 * last did, which pathId it carries and whether a route is being fed.
 *
 * <p>This is the overlay's side of {@code dev.denza.adaslink.BridgeStatus} (libraries/adas-link,
 * not on main yet). Until it lands the module runs with {@link #NONE}; then the bridge host
 * installs an adapter, field for field, with {@link #install}:
 *
 * <pre>
 * AdasFeed.install(() -&gt; {
 *     BridgeStatus.Snapshot s = bridge.status();
 *     return new AdasFeed.Snapshot(AdasFeed.Mode.valueOf(s.mode.name()),
 *             s.lastSendMonotonicMs, s.currentPathId, s.routeActive);
 * });
 * </pre>
 *
 * Times are {@code SystemClock.elapsedRealtime()}. Read on the main thread at the overlay's tick;
 * an implementation returns an immutable snapshot and never blocks.
 */
interface AdasFeed {
    enum Mode { DISABLED, DRY_RUN, LIVE }

    final class Snapshot {
        final Mode mode;
        /** Monotonic ms of the last message produced; -1 before the first. */
        final long lastSendMs;
        /** The pathId our position feed carries; -1 when not navigating. */
        final long pathId;
        /** A route is being fed (or would be, in dry run). */
        final boolean routeActive;

        Snapshot(Mode mode, long lastSendMs, long pathId, boolean routeActive) {
            this.mode = mode; this.lastSendMs = lastSendMs; this.pathId = pathId; this.routeActive = routeActive;
        }
        /** We feed a route: the overlay then shows the route receipt and judges both feeds. */
        boolean feeding() { return mode != Mode.DISABLED && routeActive && pathId >= 0; }
    }

    Snapshot status();

    Snapshot DISABLED = new Snapshot(Mode.DISABLED, -1, -1, false);
    AdasFeed NONE = new AdasFeed() { public Snapshot status() { return DISABLED; } };

    final class Holder { static volatile AdasFeed current = NONE; private Holder() { } }

    static void install(AdasFeed feed) { Holder.current = feed == null ? NONE : feed; }
    static AdasFeed current() { return Holder.current; }
}
