package dev.denza.apps;

/**
 * Holds back overlay geometry until two equivalent samples have remained available
 * for the configured interval. The exact second sample is returned so the overlay
 * follows the native dialog rather than a rounded or averaged approximation.
 */
final class SimulcastGeometryStabilizer<T> {
    interface Equivalence<T> {
        boolean equivalent(T first, T second, int epsilonPx);
    }

    private final long minimumIntervalMs;
    private final int epsilonPx;
    private final Equivalence<T> equivalence;

    private T candidate;
    private long candidateSinceMs;

    SimulcastGeometryStabilizer(
            long minimumIntervalMs,
            int epsilonPx,
            Equivalence<T> equivalence) {
        this.minimumIntervalMs = minimumIntervalMs;
        this.epsilonPx = epsilonPx;
        this.equivalence = equivalence;
    }

    T offer(long timestampMs, T sample) {
        if (sample == null) {
            reset();
            return null;
        }
        if (candidate == null || !equivalence.equivalent(candidate, sample, epsilonPx)) {
            candidate = sample;
            candidateSinceMs = timestampMs;
            return null;
        }
        if (timestampMs - candidateSinceMs < minimumIntervalMs) {
            return null;
        }
        candidate = sample;
        candidateSinceMs = timestampMs;
        return sample;
    }

    void reset() {
        candidate = null;
        candidateSinceMs = 0L;
    }
}
