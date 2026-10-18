package dev.denza.hudsurfaces.probe;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Does one scenario's steps on the calling (worker) thread and logs each of them under
 * {@link SurfacesActivity#TAG}. The reset button cuts the scenario short; then, as after any
 * failure, the runner clears through whichever session still holds service 266 - or through a
 * fresh one when none does - stops and unbinds. Nothing is left bound when {@link #run} returns.
 */
final class ScenarioRunner {
    static final long OPEN_TIMEOUT_MS = 3_000L;
    private static final long SLICE_MS = 20L;

    interface Listener {
        void onPhase(String title, String hint, long millis);

        void onLine(String line);
    }

    private final Context context;
    private final Plans.Art art;
    private final Listener listener;
    private final Map<SessionId, ProbeSession> sessions = new EnumMap<>(SessionId.class);
    private volatile boolean cancelled;
    private long began;
    private int fired;
    private int refused;

    ScenarioRunner(Context context, Plans.Art art, Listener listener) {
        this.context = context.getApplicationContext();
        this.art = art;
        this.listener = listener;
    }

    /** The reset button: the running scenario stops at its next step and clears. */
    void cancel() {
        cancelled = true;
    }

    void run(Scenario scenario) {
        began = SystemClock.uptimeMillis();
        List<Step> steps = Plans.plan(scenario, art);
        log("scenario " + scenario.id + " begins at "
                + new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + ": "
                + steps.size() + " steps, " + Plans.durationMs(steps) + " ms planned");
        boolean complete = false;
        try {
            for (Step step : steps) {
                if (cancelled) {
                    log("reset pressed: scenario " + scenario.id + " cut short");
                    break;
                }
                if (!perform(step, true)) {
                    log("scenario " + scenario.id + " failed at " + step.kind + ": resetting");
                    break;
                }
            }
            complete = !cancelled && allClosed();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log("interrupted");
        } catch (RuntimeException error) {
            Log.e(SurfacesActivity.TAG, "scenario " + scenario.id + " threw", error);
        } finally {
            if (!complete) {
                abort();
            }
            closeLeftovers();
            log("scenario " + scenario.id + (complete ? " done" : " ended by reset") + ": "
                    + fired + " fired, " + refused + " not accepted, "
                    + (SystemClock.uptimeMillis() - began) + " ms");
        }
    }

    /**
     * The same reset as at the end of every plan, through the session that wrote last: B when it
     * is started, else A, else a fresh A opened only for this.
     */
    private void abort() {
        List<SessionId> started = new ArrayList<>();
        for (SessionId id : SessionId.values()) {
            ProbeSession session = sessions.get(id);
            if (session != null && session.isStarted()) {
                started.add(id);
            }
        }
        SessionId clearer;
        if (started.isEmpty()) {
            ProbeSession old = sessions.remove(SessionId.A);
            if (old != null) {
                old.unbind();
            }
            try {
                if (!perform(Step.open(SessionId.A, "сессия только для очистки"), false)) {
                    log("reset impossible: no session could be started");
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            started.add(SessionId.A);
            clearer = SessionId.A;
        } else {
            clearer = started.contains(SessionId.B) ? SessionId.B : SessionId.A;
        }
        // Every step of the reset runs even when one fails: the stops and unbinds must happen.
        for (Step step : Plans.clearAndClose(clearer, started, art)) {
            try {
                perform(step, false);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Returns false when the scenario cannot go on; the caller then resets. */
    private boolean perform(Step step, boolean cancellable) throws InterruptedException {
        switch (step.kind) {
            case PHASE:
                log("phase «" + step.text + "» " + step.millis + " ms: " + step.hint);
                listener.onPhase(step.text, step.hint, step.millis);
                return true;
            case OPEN: {
                ProbeSession previous = sessions.get(step.session);
                if (previous != null && previous.isBound()) {
                    log("open " + step.session + ": already bound");
                    return false;
                }
                ProbeSession session = new ProbeSession(context, step.session);
                sessions.put(step.session, session);
                int result = session.open(OPEN_TIMEOUT_MS);
                log("open " + step.session + " type=" + session.type + " start ret=" + result
                        + " (ожидается: " + step.text + ")");
                return ProbeSession.offerStarted(result);
            }
            case FIRE: {
                ProbeSession session = sessions.get(step.session);
                if (session == null || !session.isStarted()) {
                    log("fire " + step.session + ": session not started");
                    return false;
                }
                int result = session.fire(step.topic, step.payload);
                fired++;
                if (result != 0) {
                    refused++;
                }
                log(String.format(Locale.ROOT, "#%d +%dms %s %s ret=%d %s", fired, elapsed(),
                        step.session, PacketSummary.topicName(step.topic), result,
                        PacketSummary.of(step.topic, step.payload)));
                // A positive code is the service's answer and is logged; a negative one means
                // the binder is gone, and nothing more can reach the glass through it.
                return result >= 0;
            }
            case PAUSE:
                sleep(step.millis, cancellable);
                return true;
            case STOP: {
                ProbeSession session = sessions.get(step.session);
                if (session == null || !session.isBound()) {
                    log("stop " + step.session + ": not bound");
                    return true;
                }
                log("stop " + step.session + " ret=" + session.stop() + " (" + step.text + ")");
                return true;
            }
            case UNBIND: {
                ProbeSession session = sessions.get(step.session);
                if (session != null) {
                    session.unbind();
                    log("unbind " + step.session + " type=" + session.type);
                }
                return true;
            }
            default:
                throw new IllegalStateException("Unknown step " + step.kind);
        }
    }

    private void sleep(long millis, boolean cancellable) throws InterruptedException {
        if (!cancellable) {
            // The reset's own pauses: neither the button nor an interrupt shortens them.
            SystemClock.sleep(millis);
            return;
        }
        long until = SystemClock.uptimeMillis() + millis;
        while (true) {
            long left = until - SystemClock.uptimeMillis();
            if (left <= 0 || (cancellable && cancelled)) {
                return;
            }
            Thread.sleep(Math.min(SLICE_MS, left));
        }
    }

    private boolean allClosed() {
        for (ProbeSession session : sessions.values()) {
            if (session.isBound()) {
                return false;
            }
        }
        return true;
    }

    private void closeLeftovers() {
        for (ProbeSession session : sessions.values()) {
            if (session.isBound()) {
                log("unbind " + session.id + " (left bound) type=" + session.type);
                session.unbind();
            }
        }
        sessions.clear();
    }

    private long elapsed() {
        return SystemClock.uptimeMillis() - began;
    }

    private void log(String line) {
        Log.i(SurfacesActivity.TAG, line);
        listener.onLine(line);
    }
}
