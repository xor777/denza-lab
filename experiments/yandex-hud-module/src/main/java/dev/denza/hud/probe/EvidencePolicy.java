package dev.denza.hud.probe;

/**
 * The emulator evidence recorder's decisions, without Android: when a sample is due, its
 * generation (bumped on every mode:routeId change), sequence and ring slot, when the native route
 * archive is attempted, the success cache that a failed save never poisons, the retry gate, and the
 * single-pending back-pressure. Thread-safe: NavigationEvidence decides on main and settles on its
 * worker. Times are the caller's monotonic milliseconds.
 */
final class EvidencePolicy {
    static final long PERIOD_MS = 1000, ARCHIVE_RETRY_MS = 10_000;
    static final int RING = 120;

    /** One accepted sample. */
    static final class Decision {
        final long generation, sequence;
        final int slot;
        /** Read and save the native archive of this route with this sample. */
        final boolean archive;
        Decision(long generation, long sequence, int slot, boolean archive) {
            this.generation = generation; this.sequence = sequence; this.slot = slot; this.archive = archive;
        }
    }

    private long nextAt, sequence, generation, archiveAttemptAt;
    private String state = "", archived, archiveAttempt;
    private boolean pending, archiveUnsupported;

    /** False while a sample is in flight, or within a second of the last one in the same mode. A mode change is due at once. */
    synchronized boolean due(String mode, long now) {
        if (pending) return false;
        return !(now < nextAt && state.startsWith(mode + ":"));
    }

    /** Accepts a sample read for {@code routeId} (null without a route); marks it pending. */
    synchronized Decision take(String mode, String routeId, long now) {
        boolean archive = false;
        if (routeId != null && !archiveUnsupported && !routeId.equals(archived)
                && (!routeId.equals(archiveAttempt) || now - archiveAttemptAt >= ARCHIVE_RETRY_MS)) {
            archiveAttempt = routeId;
            archiveAttemptAt = now;
            archive = true;
        }
        String key = mode + ":" + routeId;
        if (!key.equals(state)) { state = key; generation++; }
        nextAt = now + PERIOD_MS;
        long seq = sequence++;
        pending = true;
        return new Decision(generation, seq, (int) (seq % RING), archive);
    }

    /** The in-flight sample is written, failed, or could not be posted. */
    synchronized void done() { pending = false; }

    /** Result of an archive attempt: saved caches the route; unsupported (a missing API) stops all retries. */
    synchronized void archived(String routeId, boolean saved, boolean unsupported) {
        if (saved) archived = routeId;
        if (unsupported) archiveUnsupported = true;
    }

    synchronized boolean pending() { return pending; }
}
