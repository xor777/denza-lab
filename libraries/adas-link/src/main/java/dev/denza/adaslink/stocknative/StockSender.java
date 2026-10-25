package dev.denza.adaslink.stocknative;

import static dev.denza.adaslink.stocknative.NativeModels.*;

/** Independent native sender core for the ORIGINAL classic EHP/SOMEIPv1 branch.
 * This is a host-verifiable model, not a replacement wired into NavigatorDriver or the product. */
public final class StockSender {
    /** Distinct original globals: service b, effective switch a; f0 sleeping is separate. p is NOT noSleep. */
    public static final class Gates {public boolean service,effectiveSwitch,memory;public int noOddRegionDistance=-1;}
    private final Gates gates=new Gates();
    private final NativeWire wire;
    private final NativeRouteSender routes;
    private final StockRuntime runtime;private final NativeEnvironment environment;private final StockTransport transport;
    private final NativeCityCache cities;
    private final StockLifecycle lifecycle;
    public final OriginalMmtSender mmt;
    public OriginalRoutePolicy policy;
    public StockSender(StockRuntime runtime,NativeEnvironment environment,StockTransport transport){
        if(runtime==null||environment==null||transport==null)throw new IllegalArgumentException("native sender dependencies");
        this.runtime=runtime;this.environment=environment;this.transport=transport;
        wire=new NativeWire(runtime,transport);routes=new NativeRouteSender(runtime,environment,transport);cities=new NativeCityCache(environment);
        mmt=new OriginalMmtSender(runtime,environment,wire);lifecycle=new StockLifecycle(environment,gates);
    }
    /** Original d.o callback after the server reports initialized. */
    public void serverInitialized(){transport.startService(3096254809047040L);transport.startService(3096409430228992L);transport.startService(3096418020294656L);transport.startService(3239172026531840L);}
    /** d.i owns a new per-navigation g/f; e0 and k/m remain process singletons. */
    public OriginalRoutePolicy navigationCreated(){boolean lane=environment.laneUi(),tip=environment.tipIsland();lifecycle.activate();OriginalOddSender odd=new OriginalOddSender(runtime,environment,transport,gates,cities);OriginalRoutePolicy created=new OriginalRoutePolicy(runtime,environment,routes,odd,gates,lifecycle);lifecycle.observerCreated();policy=created;lifecycle.created(tip,lane);return created;}
    /** d.j removes only its current listener; registry lifecycle stays at the SDK environment seam. */
    public void navigationDestroyed(){lifecycle.beforeDestroy();if(policy!=null){policy.onNavStop();policy=null;}}
    public void navigationStarted(){lifecycle.started();}
    public void navigationStopped(){lifecycle.stopped();}
    public void linkSwitchChanged(boolean enabled){lifecycle.onSwitch(enabled);}
    public void tipIslandSupportChanged(boolean enabled){lifecycle.supportTipIsland(enabled);}
    public void clientInitialized(boolean ready){lifecycle.clientInitialized(ready);}
    public boolean serviceAvailable(long topic){return lifecycle.available(topic);}
    public boolean serviceUnavailable(long topic){return lifecycle.unavailable(topic);}
    public void memoryGate(boolean enabled){gates.memory=enabled;}
    public void openCitiesUpdated(java.util.List<Integer> values){cities.a(values);}
    /** Original global gate writes. No implicit callbacks: replay the actual observer receipts separately. */
    public void gates(boolean service,boolean effectiveSwitch){gates.service=service;gates.effectiveSwitch=effectiveSwitch;}
    public int noOddRegionDistance(){return gates.noOddRegionDistance;}
    public void noSleep(boolean enabled){wire.t(enabled);}
    public void multipleRoutes(RouteCarResultData result){routes.f(result);}
    /** Raw original PathSelectedProxy.d response; proto absence/default behavior is retained. */
    public void pathSelected(byte[] payload){if(policy==null||!policy.listenerRegistered())return;try{NativeAck ack=NativeAck.parse(payload);if(!ack.present)return;if(ack.status==200)policy.g(ack.path);else policy.w();}catch(IllegalArgumentException malformed){/* Original generated parser catches malformed protobuf. */}}
}
