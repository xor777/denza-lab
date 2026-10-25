package dev.denza.adaslink.stocknative;

/** Process singleton initialization/state of SendSomeIpDataManager j.e0.
 * Route chunks and selection share this instance; direct f0/ODD egress does not.
 */
final class NativeSignalTransport {
    private final StockRuntime clock;
    private final StockTransport transport;
    private boolean initialized;
    private long lastMemoryStatusWall;
    NativeSignalTransport(StockRuntime clock,StockTransport transport){this.clock=clock;this.transport=transport;}
    private synchronized void initialize(){if(!initialized){lastMemoryStatusWall=clock.currentTimeMillis();initialized=true;}}
    int fire(long topic,byte[] payload){initialize();return transport.fire(topic,payload);}
    long lastMemoryStatusWall(){return lastMemoryStatusWall;}
}
