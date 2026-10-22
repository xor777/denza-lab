package dev.denza.hud.probe;

/** Scalar camera transition recovered from the pinned GBL; independent of its strategy selector.
 * SetTarget ELF 0x1260aa4, Pop 0x1262a20. See stock-camera evidence for native oracle traces. */
final class StockCameraQueue {
    private static final float MISSING = -9999f, EPSILON = 1e-6f;
    private float from, target, last, minAdvance, maxAdvance;
    private long started;
    private boolean done = true;

    void target(float current, float next, int count, float maxDelta, long now) {
        if (Math.abs(current - MISSING) <= EPSILON || Math.abs(next - MISSING) <= EPSILON || count <= 0) return;
        // Native updates two diagnostic targets here, but deliberately keeps the active trajectory.
        if (Math.abs(next - current) <= EPSILON) return;
        from = last = current;
        target = next;
        float direction = next > current ? 1f : -1f;
        maxAdvance = direction * maxDelta / count;
        minAdvance = maxAdvance * .01f;
        started = now;
        done = false;
    }

    /** Null means no new pose; the caller must keep its previous value. Time is monotonic ms. */
    Float pop(long now) {
        if (done) return null;
        // The signed early threshold is intentional: native uses the same asymmetric comparison.
        if (Math.abs(target - last) <= minAdvance) {
            done = true;
            return last = target;
        }
        float candidate = from + (target - from) * ((now - started) / 1000f);
        if (Math.abs(candidate - last) < Math.abs(minAdvance)) return null;
        if (Math.abs(candidate - last) > Math.abs(maxAdvance)) candidate = last + maxAdvance;
        if (Math.abs(candidate - from) > Math.abs(target - from)) {
            candidate = target;
            done = true;
        }
        last = candidate;
        return candidate;
    }

    static float nearestCourse(float current, float next) {
        while (next - current > 180) next -= 360;
        while (next - current < -180) next += 360;
        return next;
    }

    static float course(float value) { return (value % 360 + 360) % 360; }
}
