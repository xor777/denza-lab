package dev.denza.adaslink.stocknative;

/** Original sender clocks and THREE Android Handler queues. Host replay supplies the execution ledger. */
public interface StockRuntime {
    enum Queue { MMT, MAIN, SOMEIP }
    long uptimeMillis();
    long elapsedRealtime();
    long currentTimeMillis();
    /** Optional host ledger barrier at an original processing-method entry/exit (not a second callback). */
    default void processingEnter(String method,Object...args){}
    default void processingExit(String method){}
    /** runnable identity is stable for recurring original Runnable objects, distinct from its token. */
    void post(Queue queue, Object runnable, Object token, long delayMs, Runnable action);
    void removeCallbacks(Queue queue, Object runnable);
    void removeToken(Queue queue, Object token);
    /** Host-only barriers around independently computed mutable state. */
    default void stateEnter(String method,Object receiver,Object...arguments){}
    default void stateExit(String method,Object receiver,Object result){}
}
