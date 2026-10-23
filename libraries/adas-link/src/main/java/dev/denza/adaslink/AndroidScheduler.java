package dev.denza.adaslink;

import android.os.Handler;
import android.os.SystemClock;

/** {@link Scheduler} on an Android {@link Handler}: the feed's own serial thread on the device. */
public final class AndroidScheduler implements Scheduler {
    private final Handler handler;

    public AndroidScheduler(Handler handler) { this.handler = handler; }

    @Override public long now() { return SystemClock.elapsedRealtime(); }

    @Override public void post(Object token, long delayMs, Runnable task) {
        handler.postAtTime(task, token, SystemClock.uptimeMillis() + Math.max(0, delayMs));
    }

    @Override public void cancel(Object token) {
        // removeCallbacksAndMessages(null) would drop every task on the thread; a null token cancels nothing.
        if (token != null) handler.removeCallbacksAndMessages(token);
    }

    public boolean onThread() { return handler.getLooper().isCurrentThread(); }
}
