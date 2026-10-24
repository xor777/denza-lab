package dev.denza.adaslink;

/**
 * Read-only view of the map-to-ADAS bridge for status displays (the ADAS overlay). Cheap to call
 * from any thread: the bridge publishes an immutable {@link Snapshot} after every change, and
 * {@link #status()} returns the latest one. No Android types, stable across versions.
 */
public interface BridgeStatus {
    /** What the bridge does with the messages it produces. */
    enum Mode {
        /** Nothing is produced. */
        DISABLED,
        /** Messages are recorded locally, never fired at the car. */
        DRY_RUN,
        /** Messages use the guarded real SOME/IP transport. */
        LIVE
    }

    final class Snapshot {
        public final Mode mode;
        /** {@code SystemClock.elapsedRealtime()}-style monotonic time of the last message produced; -1 before the first. */
        public final long lastSendMonotonicMs;
        /** The pathId the position feed currently carries; -1 when not navigating (the stock idle value). */
        public final long currentPathId;
        /** A route is being guided and has been sent (or would have been, in dry-run). */
        public final boolean routeActive;

        public Snapshot(Mode mode, long lastSendMonotonicMs, long currentPathId, boolean routeActive) {
            this.mode = mode;
            this.lastSendMonotonicMs = lastSendMonotonicMs;
            this.currentPathId = currentPathId;
            this.routeActive = routeActive;
        }

        public static final Snapshot DISABLED = new Snapshot(Mode.DISABLED, -1, -1, false);

        @Override public String toString() {
            return mode + " path=" + currentPathId + " active=" + routeActive + " last=" + lastSendMonotonicMs;
        }
    }

    /** Called on the bridge's worker thread after a change; must return quickly. */
    interface Listener {
        void onBridgeStatus(Snapshot status);
    }

    /** The latest snapshot, never null. */
    Snapshot status();

    void addListener(Listener listener);

    void removeListener(Listener listener);
}
