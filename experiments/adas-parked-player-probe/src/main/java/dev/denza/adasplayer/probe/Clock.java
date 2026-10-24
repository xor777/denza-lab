package dev.denza.adasplayer.probe;

/** Wall and monotonic time plus sleeping, so the engine runs under a real or a virtual clock. */
interface Clock {
    /** elapsedRealtime-like monotonic milliseconds. */
    long nowMs();

    /** currentTimeMillis-like wall milliseconds, recorded beside each send. */
    long wallMs();

    void sleep(long ms) throws InterruptedException;

    /** Sleep until monotonic {@code elapsedMs}; returns immediately if already past. */
    void sleepUntil(long elapsedMs) throws InterruptedException;
}
