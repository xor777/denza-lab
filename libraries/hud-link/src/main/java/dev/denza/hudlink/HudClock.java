package dev.denza.hudlink;

/**
 * Milliseconds on a monotonic clock. On the car, {@code SystemClock.uptimeMillis}, the clock a
 * {@code Handler} posts by; in tests, a clock the test moves.
 */
public interface HudClock {
    long now();
}
