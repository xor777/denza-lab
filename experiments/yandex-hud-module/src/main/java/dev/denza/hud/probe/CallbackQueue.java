package dev.denza.hud.probe;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binder callbacks to one worker, never blocking the caller and never refusing by volume. Essential
 * tasks (service-44 selection and PathMatch answers, availability, client lifecycle) keep every
 * entry; the rest (service-13 status at ~30 a second) sit in a bounded ring that drops its oldest
 * entry when full, counted. One drain is posted at a time and runs the essential tasks first. A
 * blocked worker therefore costs status updates, never an ACK and never the session.
 * Plain Java: the host harness tests it as it runs on the car.
 */
final class CallbackQueue {
    interface Poster { boolean post(Runnable task); }
    static final int RING = 64, BATCH = 128;
    private final Poster poster;
    private final int ring;
    private final ArrayDeque<Runnable> essential = new ArrayDeque<>(), others = new ArrayDeque<>();
    private boolean scheduled, closed;
    private long accepted, essentialAccepted, dropped, maxDepth, postFailures, failures;
    private String firstFailure;

    CallbackQueue(Poster poster) { this(poster, RING); }
    CallbackQueue(Poster poster, int ring) {
        if (poster == null || ring < 1) throw new IllegalArgumentException("callback queue");
        this.poster = poster; this.ring = ring;
    }

    /** Never blocks; false only once closed or when the worker no longer takes posts. */
    boolean enqueue(boolean isEssential, Runnable task) {
        boolean post;
        synchronized (this) {
            if (closed || task == null) return false;
            accepted++;
            if (isEssential) { essential.add(task); essentialAccepted++; }
            else {
                if (others.size() >= ring) { others.poll(); dropped++; }
                others.add(task);
            }
            maxDepth = Math.max(maxDepth, essential.size() + others.size());
            post = !scheduled;
            scheduled = true;
        }
        if (post && !poster.post(this::drain)) {
            synchronized (this) { scheduled = false; postFailures++; }
            return false;
        }
        return true;
    }

    private void drain() {
        for (int n = 0; ; n++) {
            Runnable next;
            synchronized (this) {
                if (closed) { essential.clear(); others.clear(); scheduled = false; return; }
                if (n >= BATCH && !(essential.isEmpty() && others.isEmpty())) {
                    // Let ticks and sends interleave with a long backlog; one drain stays scheduled.
                    if (!poster.post(this::drain)) { scheduled = false; postFailures++; }
                    return;
                }
                next = essential.poll();
                if (next == null) next = others.poll();
                if (next == null) { scheduled = false; return; }
            }
            try { next.run(); }
            catch (RuntimeException failure) {
                synchronized (this) { failures++; if (firstFailure == null) firstFailure = failure.toString(); }
            }
        }
    }

    /** Pending tasks are discarded; later callbacks are refused. */
    synchronized void close() { closed = true; essential.clear(); others.clear(); }

    synchronized int depth() { return essential.size() + others.size(); }
    synchronized long dropped() { return dropped; }
    synchronized long essentialAccepted() { return essentialAccepted; }

    synchronized Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accepted", accepted); m.put("essential", essentialAccepted); m.put("droppedOldest", dropped);
        m.put("depth", essential.size() + others.size()); m.put("maxDepth", maxDepth); m.put("ring", (long) ring);
        m.put("postFailures", postFailures); m.put("taskFailures", failures); m.put("firstTaskFailure", firstFailure);
        return m;
    }
}
