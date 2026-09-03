package dev.denza.personbean.probe;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The OBSERVE observer: static, so it lives as long as the process and a later REPORT can tell a
 * surviving process (same nonce, growing event count) from a restarted one.
 */
final class ProcessObserver {

    /** REPORT prints at most five events, so only five are kept; the total is counted separately. */
    private static final int KEPT_EVENTS = 5;

    private static final Deque<String> EVENTS = new ArrayDeque<>();

    private static HandlerThread thread;
    private static ContentObserver observer;
    private static long registeredAtNanos;
    private static int eventCount;

    private ProcessObserver() {
    }

    /** Registers once per process. Returns true when a previous call had already registered. */
    static synchronized boolean register(Context context) {
        if (observer != null) {
            return true;
        }
        thread = new HandlerThread("personbean-observe");
        thread.start();
        registeredAtNanos = SystemClock.elapsedRealtimeNanos();
        observer = new ContentObserver(new Handler(thread.getLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                record(uri);
            }
        };
        context.getApplicationContext().getContentResolver()
                .registerContentObserver(ProbeReceiver.AUTHORITY_URI, true, observer);
        return false;
    }

    static synchronized int eventCount() {
        return eventCount;
    }

    /** The last five events, oldest first, each already formatted as {@code <uri>@<ms>}. */
    static synchronized List<String> lastEvents() {
        return new ArrayList<>(EVENTS);
    }

    private static synchronized void record(Uri uri) {
        eventCount++;
        long sinceRegisterMs = (SystemClock.elapsedRealtimeNanos() - registeredAtNanos) / 1_000_000L;
        EVENTS.addLast(ProbeReceiver.token(uri == null ? null : uri.toString()) + "@" + sinceRegisterMs);
        while (EVENTS.size() > KEPT_EVENTS) {
            EVENTS.removeFirst();
        }
    }
}
