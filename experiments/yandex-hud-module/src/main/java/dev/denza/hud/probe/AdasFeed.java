package dev.denza.hud.probe;

/**
 * Our own map-to-ADAS feed as the overlay sees it: whether the bridge produces anything, when it
 * last did, which pathId it carries and whether a route is being fed.
 *
 * <p>The live host installs an immutable view of actual SOME/IP sends. Its local dry-run
 * serializer remains independent and never supplies the overlay's route-receipt indicators.
 * The fixture-only DRY_RUN value is retained for the overlay's host tests.
 *
 * Times are {@code SystemClock.elapsedRealtime()}. Read on the main thread at the overlay's tick;
 * an implementation returns an immutable snapshot and never blocks.
 */
interface AdasFeed {
    enum Mode { DISABLED, DRY_RUN, LIVE }

    final class Snapshot {
        final Mode mode;
        /** Monotonic ms of the last actual send in LIVE mode; -1 before the first. */
        final long lastSendMs;
        /** The pathId our position feed carries; -1 when not navigating. */
        final long pathId;
        /** A route is being fed (or would be, in dry run). */
        final boolean routeActive;
        /** Exact current guard confirmation; -1 until actual same-path status200. */
        final long confirmedPathId;
        /** Explicit producer lifecycle: LIVE/WAITING/STOPPED/BLOCKED/DISARMED; null for legacy fixtures. */
        final String runtimeState, reason;

        Snapshot(Mode mode, long lastSendMs, long pathId, boolean routeActive) {
            this(mode,lastSendMs,pathId,routeActive,-1,null,null);
        }
        Snapshot(Mode mode, long lastSendMs, long pathId, boolean routeActive,
                 long confirmedPathId, String runtimeState, String reason) {
            this.mode = mode; this.lastSendMs = lastSendMs; this.pathId = pathId; this.routeActive = routeActive;
            this.confirmedPathId=confirmedPathId; this.runtimeState=runtimeState; this.reason=reason;
        }
        boolean explicitState() { return runtimeState != null; }
        boolean confirmed() { return mode==Mode.LIVE && feeding() && pathId==confirmedPathId; }
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
