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
    private final StockRuntime runtime;
    private final AutoCloseable runtimeOwner;
    private final LongSupplier ids;
    private final java.util.function.Consumer<java.util.Map<String,Object>> routeAudit;
    private volatile boolean closed, navigating;
    private long routeEpoch=-1, lastFix=Long.MIN_VALUE, lastNavi=-1;
    private String routeId, geometry, blocked;
    private YandexNativeAdapter.AdaptedRoute route;
    private Boolean service, effectiveSwitch, awake;
    private boolean offRouteActive,offRouteReplacementPending,arrivalPending,draining;
    private Float latestVehicleSpeed;private long vehicleSpeedObservedAt=-1;
    NativeMapSession(StockRuntime runtime,AutoCloseable owner,NativeEnvironment environment,
                     StockTransport transport,LongSupplier ids){this(runtime,owner,environment,transport,ids,value->{});}
    NativeMapSession(StockRuntime runtime,AutoCloseable owner,NativeEnvironment environment,
                     StockTransport transport,LongSupplier ids,java.util.function.Consumer<java.util.Map<String,Object>> routeAudit){
        this.runtime=runtime;this.runtimeOwner=owner; this.ids=ids;this.routeAudit=routeAudit;
        sender=new StockSender(runtime,environment,transport);
        sender.senderType(environment.adasType());
    }
    void start(boolean service,boolean rawSwitch,boolean noSleep){
        if(closed)throw new IllegalStateException("closed native session");
        gates(service,rawSwitch,noSleep);
        sender.serverInitialized();
        sender.idleStatus();
    }
    void gates(boolean enabled,boolean rawSwitch,boolean noSleep){
        if(closed)return;
        boolean effective=rawSwitch&&noSleep;
        boolean changed=effectiveSwitch!=null&&effectiveSwitch!=effective;
        sender.gates(enabled,effective); sender.noSleep(noSleep);
        service=enabled; effectiveSwitch=effective; awake=noSleep;
        if(changed)sender.effectiveSwitchChanged(effective);
    }
    void observe(NativeNavigationInput o){
        if(closed||draining||o.routeEpoch<routeEpoch)return;
        if(o.routeId==null||"off".equals(o.mode)){stopNavigation();routeEpoch=o.routeEpoch;return;}
        if(o.simulated){invalidateLocation();blocked="simulated guidance cannot publish to ADAS";return;}
        if(o.finished){if(navigating&&route!=null){arrivalPending=true;sender.navigationArrived(route.getPathID());}stopNavigation();routeEpoch=o.routeEpoch;return;}
        boolean offRoute=o.offRoute();
        // A changed ID alone says nothing about why Yandex selected another route. Only an
        // observed deviation status starts the native off-course callback/type policy.
        if(offRoute&&!offRouteActive){sender.rerouteStarted(2);offRouteReplacementPending=true;}
        offRouteActive=offRoute;
        if(o.route!=null&&(route==null||o.routeEpoch!=routeEpoch||!o.routeId.equals(routeId)
                ||!java.util.Objects.equals(geometry,o.offlineGeometrySha256))){
            long pathId=ids.getAsLong();
            int nativeRouteType=route!=null&&offRouteReplacementPending?2:0;
            YandexNativeAdapter.AdaptedRoute candidate=YandexNativeAdapter.prepare(o.route,pathId,pathId,nativeRouteType,o.route.additionalAttributes,YandexNativeAdapter.Policy.EXPLICIT_FALSE);
            java.util.Map<String,Object> policy=dev.denza.adaslink.Json.object();
            policy.put("routeId",o.routeId);policy.put("pathId",pathId);policy.put("epoch",o.routeEpoch);
            policy.put("nativeRouteType",nativeRouteType);policy.put("routeStatus",o.routeStatus);
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
            blocked=null;lastFix=Long.MIN_VALUE;lastNavi=-1;arrivalPending=false;offRouteReplacementPending=false;
            if(!navigating){navigating=true;sender.navigationCreated();sender.navigationStarted();sender.navigationReset();
                if(latestVehicleSpeed!=null&&runtime.elapsedRealtime()>=vehicleSpeedObservedAt&&runtime.elapsedRealtime()-vehicleSpeedObservedAt<=1500)sender.vehicleSpeedUpdated(latestVehicleSpeed);
            }
            if(previous>0)sender.pathChanged(previous,route.getPathID());
            sender.routeUpdated(route.result(),route);
        }
        if(route==null||!navigating||!o.routeId.equals(routeId)||o.routeEpoch!=routeEpoch)return;
        if(!offRoute)offRouteReplacementPending=false; // Returned to this route without replacement.
        // Original callback uses 0 when no current legal limit is supplied; don't retain an
        // earlier road's limit merely because Yandex now reports an unavailable value.
        sender.roadSpeedLimit(Double.isFinite(o.speedLimitMps)&&o.speedLimitMps>=0
                ? (int)Math.min(Integer.MAX_VALUE,Math.round(o.speedLimitMps*3.6)):0);
        if(o.positionOnRoute&&o.monotonicMs-lastNavi>=1000){
            YandexNativeAdapter.Guidance g=new YandexNativeAdapter.Guidance();g.scalar=o.scalar;g.timeToFinishS=o.timeToFinishS;
            NativeModels.NaviInfo info=YandexNativeAdapter.navi(route,g);
            if(info!=null){sender.progressUpdated(info,route);lastNavi=o.monotonicMs;}
        }
        if("FINE".equals(o.locationClass)&&o.fixAgeMs>=0&&o.fixAgeMs<=1500&&!o.simulated){
            YandexNativeAdapter.Location l=new YandexNativeAdapter.Location();
            l.scalar=o.scalar;l.matchedLon=o.lon;l.matchedLat=o.lat;l.rawLon=o.rawLon;l.rawLat=o.rawLat;
            l.speedMps=o.speedMps;l.headingDeg=o.heading;l.simulated=o.simulated;l.onRoute=o.positionOnRoute;
            NativeModels.LocInfo info=YandexNativeAdapter.location(route,l);
            if(info!=null){if(o.stamp<=lastFix)return;sender.locationUpdated(info,runtime.elapsedRealtime()+1500-o.fixAgeMs);lastFix=o.stamp;}
            else invalidateLocation();
        }else invalidateLocation();
    }
    void clientInitialized(boolean ready){if(!closed)sender.clientInitialized(ready);}
    void serviceEvent(long topic){if(closed)return;if((topic>>>48)==1){if((topic&0xffff)==1)sender.serviceAvailable(topic);else if((topic&0xffff)==2)sender.serviceUnavailable(topic);}}
    void pathSelected(byte[] payload){if(!closed&&navigating)sender.pathSelected(payload);}
    private void stopNavigation(){
        invalidateLocation();
        if(navigating){navigating=false;sender.navigationCancelled();sender.navigationStopped();sender.navigationDestroyed();}
        route=null;routeId=null;geometry=null;lastFix=Long.MIN_VALUE;lastNavi=-1;
        offRouteActive=false;offRouteReplacementPending=false;
    }
    void invalidateLocation(){sender.invalidateLocation();lastFix=Long.MIN_VALUE;}
    void openCitiesUpdated(java.util.List<Integer> values){if(!closed)sender.openCitiesUpdated(values);}
    void cityCodes(java.util.List<Integer> values){openCitiesUpdated(values);}
    void vehicleSpeedUpdated(float kmh){if(!closed&&!draining&&Float.isFinite(kmh)){latestVehicleSpeed=kmh;vehicleSpeedObservedAt=runtime.elapsedRealtime();sender.vehicleSpeedUpdated(kmh);}}
    boolean arrivalPending(){return arrivalPending;}
    /** Finish through original cancellation/arrival callbacks while the caller owns a bounded drain. */
    boolean beginGracefulStop(){
        if(closed)return arrivalPending;draining=true;boolean arrival=arrivalPending;
        boolean wasNavigating=navigating;stopNavigation();
        if(!wasNavigating)sender.navigationCancelled();
        return arrival;
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
