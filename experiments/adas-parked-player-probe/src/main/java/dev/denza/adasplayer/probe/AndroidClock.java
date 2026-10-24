package dev.denza.adasplayer.probe;

import android.os.SystemClock;

/** The real clock: elapsedRealtime, currentTimeMillis, Thread.sleep. */
final class AndroidClock implements Clock {
    public long nowMs() { return SystemClock.elapsedRealtime(); }

    public long wallMs() { return System.currentTimeMillis(); }

    public void sleep(long ms) throws InterruptedException {
        if (ms > 0) Thread.sleep(ms);
    }

    public void sleepUntil(long elapsedMs) throws InterruptedException {
        long remaining = elapsedMs - nowMs();
        if (remaining > 0) Thread.sleep(remaining);
    }
}
