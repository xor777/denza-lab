package dev.denza.adaslink;

/**
 * Yandex 29.8.1 Location.relativeTimestamp is CLOCK_MONOTONIC milliseconds, matching Android
 * uptimeMillis, not elapsedRealtime. Native producer/marshaler proof: captures/yandex-location-
 * clock-20261024/native-clock/manifest.json (159a167063c1be301fb9fcd89a3239058f35c80e1c6c432913db202cecef4822).
 * Capture all three current clocks AFTER reading the location. No Android dependency.
 */
public final class FixFreshness {
    public static final String RELATIVE_CLOCK = "android.uptimeMillis";
    public static final long UNKNOWN = Long.MAX_VALUE;
    /** Millisecond rounding and sequential clock reads; a larger wall/elapsed discontinuity rejects. */
    private static final long CLOCK_READ_SKEW_MS = 20;
    private FixFreshness() { }

    public static final class Sample {
        public final long relativeMs, absoluteMs;
        private final long nativeAge, readElapsed, readWall;
        private final boolean valid;
        private Sample(long relative, long absolute, long producerNow, long elapsedNow, long wallNow) {
            relativeMs = relative; absoluteMs = absolute; readElapsed = elapsedNow; readWall = wallNow;
            valid = relative >= 0 && absolute >= 0 && producerNow >= relative && elapsedNow >= 0 && wallNow >= absolute;
            nativeAge = valid ? producerNow - relative : UNKNOWN;
        }

        /** Unknown is never fresh. Elapsed aging preserves rejection of a held fix across suspend. */
        public long ageMs(long elapsedNow, long wallNow) {
            if (!valid || elapsedNow < readElapsed || wallNow < readWall || wallNow < absoluteMs) return UNKNOWN;
            long elapsed = elapsedNow - readElapsed, wall = wallNow - readWall;
            if (wall > elapsed ? wall - elapsed > CLOCK_READ_SKEW_MS : elapsed - wall > CLOCK_READ_SKEW_MS)
                return UNKNOWN;
            if (nativeAge > Long.MAX_VALUE - elapsed) return UNKNOWN;
            // The absolute age is an independent veto for cached pre-suspend fixes at cold start.
            return Math.max(nativeAge + elapsed, wallNow - absoluteMs);
        }

        public boolean isFresh(String locationClass, long elapsedNow, long wallNow, long maximumAgeMs) {
            long age = ageMs(elapsedNow, wallNow);
            return "FINE".equals(locationClass) && age != UNKNOWN && maximumAgeMs >= 0 && age <= maximumAgeMs;
        }
    }

    public static Sample capture(long relative, long absolute, long producerNow, long elapsedNow, long wallNow) {
        return new Sample(relative, absolute, producerNow, elapsedNow, wallNow);
    }

    /**
     * One producer stream, used only for FINE timestamps. Duplicate reads never move the anchor;
     * a regressing identity (A→B→A) rejects. Even a rejected future identity cannot mature on a
     * later read. A bad forward timestamp may conservatively block the stream until a newer one.
     */
    public static final class Tracker {
        private long highestRelative = -1;
        private Sample latest;
        public Sample observe(long relative, long absolute, long producerNow, long elapsedNow, long wallNow) {
            if (relative < 0 || absolute < 0 || relative < highestRelative)
                return capture(-1, -1, -1, -1, -1);
            if (relative == highestRelative && latest != null)
                return absolute == latest.absoluteMs ? latest : capture(-1, -1, -1, -1, -1);
            highestRelative = relative;
            latest = capture(relative, absolute, producerNow, elapsedNow, wallNow);
            return latest;
        }
    }
}
