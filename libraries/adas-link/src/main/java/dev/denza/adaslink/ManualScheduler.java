package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** A deterministic {@link Scheduler} on virtual time, for tests, replays and the host bench. */
public final class ManualScheduler implements Scheduler {
    private static final class Task {
        final long at, seq;
        final Object token;
        final Runnable run;
        Task(long at, long seq, Object token, Runnable run) { this.at = at; this.seq = seq; this.token = token; this.run = run; }
    }

    private final List<Task> queue = new ArrayList<>();
    private long now, seq;

    public ManualScheduler(long startMs) { now = startMs; }

    @Override public long now() { return now; }

    @Override public void post(Object token, long delayMs, Runnable task) {
        queue.add(new Task(now + Math.max(0, delayMs), seq++, token, task));
    }

    @Override public void cancel(Object token) {
        for (Iterator<Task> it = queue.iterator(); it.hasNext(); ) if (it.next().token == token) it.remove();
    }

    /** Runs every task due up to {@code t}, in order, then sets the clock to {@code t}. */
    public void advanceTo(long t) {
        while (true) {
            Task next = null;
            for (Task task : queue) if (task.at <= t && (next == null || task.at < next.at || (task.at == next.at && task.seq < next.seq))) next = task;
            if (next == null) break;
            queue.remove(next);
            now = Math.max(now, next.at);
            next.run.run();
        }
        now = Math.max(now, t);
    }

    /** Runs what is due now (input handlers post work with no delay). */
    public void drain() { advanceTo(now); }
}
