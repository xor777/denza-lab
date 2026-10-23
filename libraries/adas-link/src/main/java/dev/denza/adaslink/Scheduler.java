package dev.denza.adaslink;

/**
 * The stock map's handlers ({@code MmtSingle}, {@code SomeIpClient_Thread}) as one serial queue:
 * tasks run one at a time in time order, FIFO at equal times, tokens cancel as
 * {@code removeCallbacksAndMessages(token)}. All {@link StockFeed} calls happen on it.
 */
public interface Scheduler {
    /** Monotonic milliseconds ({@code SystemClock.elapsedRealtime()} on the device). */
    long now();

    void post(Object token, long delayMs, Runnable task);

    void cancel(Object token);
}
