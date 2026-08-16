package dev.denza.apps.feature.hud;

import java.util.concurrent.Executor;

/**
 * Runs a blocking reader away from its caller, with at most one trailing read.
 *
 * <p>Lifecycle and result delivery stay on the callback executor. Requests that
 * arrive while a read is in flight are coalesced, and results from a previous
 * activation are discarded.</p>
 */
final class SingleFlightReadRunner<T> {
    interface Reader<T> {
        T read() throws Exception;
    }

    interface Listener<T> {
        void onResult(T value, Throwable error);
    }

    private final Object lock = new Object();
    private final Executor workerExecutor;
    private final Executor callbackExecutor;
    private final Reader<T> reader;
    private final Listener<T> listener;

    private boolean active;
    private boolean inFlight;
    private boolean trailingReadRequested;
    private long generation;

    SingleFlightReadRunner(
            Executor workerExecutor,
            Executor callbackExecutor,
            Reader<T> reader,
            Listener<T> listener) {
        this.workerExecutor = workerExecutor;
        this.callbackExecutor = callbackExecutor;
        this.reader = reader;
        this.listener = listener;
    }

    void activate() {
        synchronized (lock) {
            if (active) {
                return;
            }
            generation++;
            active = true;
            inFlight = false;
            trailingReadRequested = false;
        }
    }

    void deactivate() {
        synchronized (lock) {
            if (!active) {
                return;
            }
            generation++;
            active = false;
            inFlight = false;
            trailingReadRequested = false;
        }
    }

    boolean request() {
        final long requestGeneration;
        synchronized (lock) {
            if (!active) {
                return false;
            }
            if (inFlight) {
                trailingReadRequested = true;
                return true;
            }
            inFlight = true;
            requestGeneration = generation;
        }

        try {
            workerExecutor.execute(() -> read(requestGeneration));
        } catch (Throwable error) {
            dispatchResult(requestGeneration, null, error);
        }
        return true;
    }

    private void read(long requestGeneration) {
        T value = null;
        Throwable error = null;
        try {
            value = reader.read();
        } catch (Throwable readError) {
            error = readError;
        }
        dispatchResult(requestGeneration, value, error);
    }

    private void dispatchResult(long requestGeneration, T value, Throwable error) {
        callbackExecutor.execute(() -> complete(requestGeneration, value, error));
    }

    private void complete(long requestGeneration, T value, Throwable error) {
        final boolean runTrailingRead;
        synchronized (lock) {
            if (!active || generation != requestGeneration) {
                return;
            }
            inFlight = false;
            runTrailingRead = trailingReadRequested;
            trailingReadRequested = false;
        }

        try {
            listener.onResult(value, error);
        } finally {
            if (runTrailingRead) {
                request();
            }
        }
    }
}
