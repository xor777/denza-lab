package dev.denza.disharebridge;

/**
 * What {@link DiShareProjectionBridge} may believe about the one share it started. Pure, so the
 * decisions are tested without a car; the bridge feeds it on the main thread only.
 *
 * <p>DiShare's API service tells each client whether it is the current mirror client
 * ({@code IDiShareApiClient} tx 1): {@code true} once the app being shared is the one the client
 * registered, {@code false} when that stops being so, because the share ended (605 on P→D, the
 * stock exit) or another app took its place. Both ends were seen on the car (dishare-api-notes.md,
 * "End of a share").
 *
 * <p>A {@code false} ends the share only after a {@code true}, and only once it has stood for a
 * settle period with no {@code true} after it. DiShare reports the current mirror app to a client
 * as soon as the client is created, so casting the same app again can bring the previous share's
 * {@code true} and then its teardown's {@code false} before the new share's {@code true}. Ending
 * on that {@code false} would be wrong twice: the product would drop a running share, and
 * removing a client DiShare again counts as its mirror client stops the share.
 */
final class DiShareShareSession {
    enum Phase {
        IDLE,
        STARTING,
        ACTIVE,
        /** DiShare ended the share on its side. */
        ENDED,
        /** We stopped it. */
        STOPPED,
        FAILED,
    }

    private Phase phase = Phase.IDLE;
    private boolean mirroring;
    private boolean endPending;

    Phase phase() {
        return phase;
    }

    void starting() {
        phase = Phase.STARTING;
        mirroring = false;
        endPending = false;
    }

    /** The start transaction succeeded. Returns false when the session was no longer starting. */
    boolean started() {
        if (phase != Phase.STARTING) {
            return false;
        }
        phase = Phase.ACTIVE;
        return true;
    }

    /** Returns whether this is the start's one failure. */
    boolean failed() {
        if (phase != Phase.STARTING) {
            return false;
        }
        phase = Phase.FAILED;
        return true;
    }

    /** Our own stop. Whatever DiShare says afterwards is no longer about this session. */
    void stopped() {
        if (phase == Phase.STARTING || phase == Phase.ACTIVE) {
            phase = Phase.STOPPED;
        }
        endPending = false;
    }

    /**
     * {@code IDiShareApiClient} tx 1. Returns true when this flag starts the settle period after
     * which the share counts as ended; the caller then (re)arms its timer and calls
     * {@link #settled()} when it fires. A {@code true} calls a pending end off.
     */
    boolean mirrorClientChanged(boolean current) {
        if (phase != Phase.STARTING && phase != Phase.ACTIVE) {
            return false;
        }
        if (current) {
            mirroring = true;
            endPending = false;
            return false;
        }
        boolean ending = phase == Phase.ACTIVE && mirroring;
        mirroring = false;
        if (!ending) {
            return false;
        }
        endPending = true;
        return true;
    }

    boolean endPending() {
        return endPending;
    }

    /** The settle period ran out. Returns true when the share has ended, once. */
    boolean settled() {
        if (phase != Phase.ACTIVE || !endPending) {
            return false;
        }
        endPending = false;
        phase = Phase.ENDED;
        return true;
    }

    /**
     * DiShare's process went away; the share's {@code BYD-Mirror} display lived in it. Returns true
     * when that ends an active share. While starting it is a failed start, which the bridge reports.
     */
    boolean dishareLost() {
        if (phase != Phase.ACTIVE) {
            return false;
        }
        endPending = false;
        phase = Phase.ENDED;
        return true;
    }
}
