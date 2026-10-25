package dev.denza.hud.probe;

import dev.denza.adaslink.stocknative.NativeEnvironment;
import dev.denza.adaslink.stocknative.NativeModels;
import dev.denza.adaslink.stocknative.StockRuntime;
import dev.denza.adaslink.stocknative.StockSender;
import dev.denza.adaslink.stocknative.StockTransport;
import dev.denza.adaslink.yandexnative.YandexNativeAdapter;
import java.util.function.LongSupplier;

/** Session-owned input adapter. No sender output is used as a subsequent input. */
final class NativeMapSession implements AutoCloseable {
    private final StockSender sender;
    private final AutoCloseable runtimeOwner;
    private final LongSupplier ids;
    private final java.util.function.Consumer<java.util.Map<String,Object>> routeAudit;
    private volatile boolean closed, navigating;
    private long routeEpoch=-1, lastFix=Long.MIN_VALUE, lastNavi=-1;
    private String routeId, geometry, blocked;
    private YandexNativeAdapter.AdaptedRoute route;
    private Boolean service, effectiveSwitch, awake;
    NativeMapSession(StockRuntime runtime,AutoCloseable owner,NativeEnvironment environment,
                     StockTransport transport,LongSupplier ids){this(runtime,owner,environment,transport,ids,value->{});}
    NativeMapSession(StockRuntime runtime,AutoCloseable owner,NativeEnvironment environment,
                     StockTransport transport,LongSupplier ids,java.util.function.Consumer<java.util.Map<String,Object>> routeAudit){
        this.runtimeOwner=owner; this.ids=ids;this.routeAudit=routeAudit;
        sender=new StockSender(runtime,environment,transport);
        sender.mmt.I0(environment.adasType());
    }
    void start(boolean service,boolean rawSwitch,boolean noSleep){
        if(closed)throw new IllegalStateException("closed native session");
        gates(service,rawSwitch,noSleep);
        sender.serverInitialized();
        sender.mmt.p(100);
    }
    void gates(boolean enabled,boolean rawSwitch,boolean noSleep){
        if(closed)return;
        boolean effective=rawSwitch&&noSleep;
        boolean changed=effectiveSwitch!=null&&effectiveSwitch!=effective;
        sender.gates(enabled,effective); sender.noSleep(noSleep);
        service=enabled; effectiveSwitch=effective; awake=noSleep;
        if(changed){sender.linkSwitchChanged(effective);if(sender.policy!=null)sender.policy.onDnpSwitchState(effective,false,false);}
    }
    void observe(NativeNavigationInput o){
        if(closed||o.routeEpoch<routeEpoch)return;
        if(o.routeId==null||"off".equals(o.mode)){stopNavigation();routeEpoch=o.routeEpoch;return;}
        if(o.simulated){blocked="simulated guidance cannot publish to ADAS";return;}
        if(o.finished){if(navigating&&route!=null)sender.mmt.onNaviArrive(route.getPathID(),0);stopNavigation();routeEpoch=o.routeEpoch;return;}
        if(o.route!=null&&(route==null||o.routeEpoch!=routeEpoch||!o.routeId.equals(routeId)
                ||!java.util.Objects.equals(geometry,o.offlineGeometrySha256))){
            long pathId=ids.getAsLong();
            YandexNativeAdapter.AdaptedRoute candidate=YandexNativeAdapter.prepare(o.route,pathId,pathId,0,o.route.additionalAttributes,YandexNativeAdapter.Policy.EXPLICIT_FALSE);
            java.util.Map<String,Object> policy=dev.denza.adaslink.Json.object();
            policy.put("routeId",o.routeId);policy.put("pathId",pathId);policy.put("epoch",o.routeEpoch);
            policy.put("missingBooleanPolicy","explicit_false_test_policy");
            java.util.List<Object> decisions=new java.util.ArrayList<>();
            for(dev.denza.adaslink.yandexnative.FieldDecision decision:candidate.fieldDecisions()){
                java.util.Map<String,Object> value=dev.denza.adaslink.Json.object();
                value.put("field",decision.field);value.put("source",decision.source);value.put("kind",decision.kind.name());
                value.put("meaning",decision.meaning);value.put("absence",decision.absence);decisions.add(value);
            }
            policy.put("fields",decisions);policy.put("blockers",candidate.blockers());routeAudit.accept(policy);
            if(!candidate.admitted()){blocked=String.join("; ",candidate.blockers());return;}
            long previous=route==null?-1:route.getPathID();
            route=candidate;routeId=o.routeId;geometry=o.offlineGeometrySha256;routeEpoch=o.routeEpoch;
            blocked=null;lastFix=Long.MIN_VALUE;lastNavi=-1;
            if(!navigating){navigating=true;sender.navigationCreated();sender.navigationStarted();sender.mmt.d();}
            if(previous>0)sender.mmt.onChangeNaviPath(previous,route.getPathID());
            sender.mmt.t(route.result());sender.policy.t(route.result());
            sender.mmt.J0(route);
        }
        if(route==null||!navigating||!o.routeId.equals(routeId)||o.routeEpoch!=routeEpoch)return;
        if(Double.isFinite(o.speedLimitMps)&&o.speedLimitMps>=0)
            sender.mmt.onCurrentRoadSpeed((int)Math.round(o.speedLimitMps*3.6));
        if(o.positionOnRoute&&o.monotonicMs-lastNavi>=1000){
            YandexNativeAdapter.Guidance g=new YandexNativeAdapter.Guidance();g.scalar=o.scalar;g.timeToFinishS=o.timeToFinishS;
            NativeModels.NaviInfo info=YandexNativeAdapter.navi(route,g);
            if(info!=null){sender.mmt.l(info,route);sender.policy.l(info,route);lastNavi=o.monotonicMs;}
        }
        if(o.positionOnRoute&&"FINE".equals(o.locationClass)&&o.fixAgeMs>=0&&o.fixAgeMs<=1500&&o.stamp>lastFix&&!o.simulated){
            YandexNativeAdapter.Location l=new YandexNativeAdapter.Location();
            l.scalar=o.scalar;l.matchedLon=o.lon;l.matchedLat=o.lat;l.rawLon=o.rawLon;l.rawLat=o.rawLat;
            l.speedMps=o.speedMps;l.headingDeg=o.heading;l.simulated=o.simulated;
            NativeModels.LocInfo info=YandexNativeAdapter.location(route,l);
            if(info!=null){sender.mmt.onLocInfoUpdate(info);lastFix=o.stamp;}
        }
    }
    void clientInitialized(boolean ready){if(!closed)sender.clientInitialized(ready);}
    void serviceEvent(long topic){if(closed)return;if((topic>>>48)==1){if((topic&0xffff)==1)sender.serviceAvailable(topic);else if((topic&0xffff)==2)sender.serviceUnavailable(topic);}}
    void pathSelected(byte[] payload){if(!closed&&navigating)sender.pathSelected(payload);}
    private void stopNavigation(){
        if(navigating){navigating=false;sender.mmt.onNavStop();sender.navigationStopped();sender.navigationDestroyed();}
        route=null;routeId=null;geometry=null;lastFix=Long.MIN_VALUE;lastNavi=-1;
    }
    boolean navigating(){return navigating&&!closed;}
    long pathId(){return route==null?-1:route.getPathID();}
    long confirmedPathId(){return closed?-1:sender.selectedPathId();}
    String blocked(){return blocked;}
    long routeEpoch(){return routeEpoch;}
    public void close(){
        if(closed)return;
        // Fence first: callbacks from a previous arm can never re-open this instance.
        closed=true;
        try{stopNavigation();}finally{try{runtimeOwner.close();}catch(Exception error){throw new IllegalStateException("native runtime disposal",error);}}
    }
}
