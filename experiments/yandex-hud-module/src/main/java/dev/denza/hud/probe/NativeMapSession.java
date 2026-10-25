package dev.denza.hud.probe;

import dev.denza.adaslink.stocknative.NativeEnvironment;
import dev.denza.adaslink.stocknative.NativeModels;
import dev.denza.adaslink.stocknative.StockRuntime;
import dev.denza.adaslink.stocknative.StockSender;
import dev.denza.adaslink.stocknative.StockTransport;
import dev.denza.adaslink.yandexnative.YandexNativeAdapter;
import dev.denza.adaslink.yandexnative.PublicRoadEvidence;
import dev.denza.adaslink.yandexnative.OwnerLaneEvidence;
import dev.denza.adaslink.Coordinates;
import dev.denza.adaslink.LiveSessionLease;
import java.util.function.LongSupplier;

/** Session-owned input adapter. No sender output is used as a subsequent input. */
final class NativeMapSession implements AutoCloseable {
    private final StockSender sender;
    private final StockRuntime runtime;
    private final AutoCloseable runtimeOwner;
    private final LongSupplier ids;
    private final java.util.function.Consumer<java.util.Map<String,Object>> routeAudit;
    static final int FRAME_ROUTE_LIMIT=4096;
    private final LiveSessionLease.FrameMode frameMode;
    private final PublicRoadEvidence publicRoadEvidence;
    private final OwnerLaneEvidence laneEvidence;
    private final YandexNativeAdapter.Policy routePolicy;
    private final java.util.Map<String,RouteFrame> routeFrames=new java.util.LinkedHashMap<>();
    private static final class RouteFrame {
        final Coordinates.Frame frame;final int ordinal;
        RouteFrame(Coordinates.Frame frame,int ordinal){this.frame=frame;this.ordinal=ordinal;}
    }
    private volatile boolean closed, navigating;
    private long routeEpoch=-1, lastFix=Long.MIN_VALUE, lastNavi=-1;
    private String routeId, geometry, blocked;
    private YandexNativeAdapter.AdaptedRoute route;
    private Boolean service, effectiveSwitch, awake;
    private boolean offRouteActive,offRouteReplacementPending,arrivalPending,draining,routeProgressReady,forceRouteRefresh;
    private Float latestVehicleSpeed;private long vehicleSpeedObservedAt=-1;
    private RejectedPreparation rejectedPreparation;
    /** Source snapshots and their attribute results are owned immutable observations. A new
     * snapshot/result may change admission; another fix on the same observation cannot. */
    private static final class RejectedPreparation {
        final String id,geometry,reason;final long epoch;
        final dev.denza.adaslink.YandexRoute source;
        final dev.denza.adaslink.RouteAttributes attributes;
        RejectedPreparation(NativeNavigationInput input,String reason){
            id=input.routeId;epoch=input.routeEpoch;geometry=input.offlineGeometrySha256;
            source=input.route;attributes=input.route.additionalAttributes;this.reason=reason;
        }
        boolean matches(NativeNavigationInput input){
            return epoch==input.routeEpoch&&java.util.Objects.equals(id,input.routeId)
                    &&java.util.Objects.equals(geometry,input.offlineGeometrySha256)
                    &&source==input.route&&attributes==input.route.additionalAttributes;
        }
    }
    NativeMapSession(StockRuntime runtime,AutoCloseable owner,NativeEnvironment environment,
                     StockTransport transport,LongSupplier ids){this(runtime,owner,environment,transport,ids,value->{});}
    NativeMapSession(StockRuntime runtime,AutoCloseable owner,NativeEnvironment environment,
                     StockTransport transport,LongSupplier ids,java.util.function.Consumer<java.util.Map<String,Object>> routeAudit){
        this(runtime,owner,environment,transport,ids,routeAudit,LiveSessionLease.FrameMode.WGS84);
    }
    NativeMapSession(StockRuntime runtime,AutoCloseable owner,NativeEnvironment environment,
                     StockTransport transport,LongSupplier ids,java.util.function.Consumer<java.util.Map<String,Object>> routeAudit,
                     LiveSessionLease.FrameMode frameMode){
        this(runtime,owner,environment,transport,ids,routeAudit,frameMode,PublicRoadEvidence.EMPTY);
    }
    NativeMapSession(StockRuntime runtime,AutoCloseable owner,NativeEnvironment environment,
                     StockTransport transport,LongSupplier ids,java.util.function.Consumer<java.util.Map<String,Object>> routeAudit,
                     LiveSessionLease.FrameMode frameMode,PublicRoadEvidence publicRoadEvidence){
        this(runtime,owner,environment,transport,ids,routeAudit,frameMode,publicRoadEvidence,OwnerLaneEvidence.EMPTY);
    }
    NativeMapSession(StockRuntime runtime,AutoCloseable owner,NativeEnvironment environment,
                     StockTransport transport,LongSupplier ids,java.util.function.Consumer<java.util.Map<String,Object>> routeAudit,
                     LiveSessionLease.FrameMode frameMode,PublicRoadEvidence publicRoadEvidence,OwnerLaneEvidence laneEvidence){
        this(runtime,owner,environment,transport,ids,routeAudit,frameMode,publicRoadEvidence,laneEvidence,
                YandexNativeAdapter.Policy.TEST_ROAD_DATA.withHistorySpeed(YandexNativeAdapter.HistorySpeedPolicy.EXPERIMENTAL_YANDEX_SECTION_FREE_FLOW_PROXY));
    }
    NativeMapSession(StockRuntime runtime,AutoCloseable owner,NativeEnvironment environment,
                     StockTransport transport,LongSupplier ids,java.util.function.Consumer<java.util.Map<String,Object>> routeAudit,
                     LiveSessionLease.FrameMode frameMode,PublicRoadEvidence publicRoadEvidence,OwnerLaneEvidence laneEvidence,
                     YandexNativeAdapter.Policy routePolicy){
        this.laneEvidence=java.util.Objects.requireNonNull(laneEvidence,"owner lane evidence");
        this.publicRoadEvidence=java.util.Objects.requireNonNull(publicRoadEvidence,"public-road evidence");
        this.routePolicy=java.util.Objects.requireNonNull(routePolicy,"route policy").withPublicRoadEvidence(publicRoadEvidence).withLaneEvidence(laneEvidence);
        this.runtime=runtime;this.runtimeOwner=owner; this.ids=ids;this.routeAudit=routeAudit;
        if(frameMode==null)throw new IllegalArgumentException("coordinate frame mode required");this.frameMode=frameMode;
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
        if(o.route!=null&&(forceRouteRefresh||route==null||o.routeEpoch!=routeEpoch||!o.routeId.equals(routeId)
                ||!java.util.Objects.equals(geometry,o.offlineGeometrySha256))){
            if(rejectedPreparation!=null&&rejectedPreparation.matches(o)){
                invalidateLocation();blocked=rejectedPreparation.reason;return;
            }
            RouteFrame selected=routeFrame(o.routeId,true);
            if(selected==null){invalidateLocation();blocked="coordinate frame route association limit";return;}
            long pathId=ids.getAsLong();
            // Original RouteRequestController.requestRoute chooses RouteTypeCommon=1 for
            // new ordinary guidance. The retained bench's initial 0 was an unset seed field.
            int nativeRouteType=route!=null&&offRouteReplacementPending?2:1;
            YandexNativeAdapter.AdaptedRoute candidate=YandexNativeAdapter.prepare(o.route,pathId,pathId,nativeRouteType,o.route.additionalAttributes,routePolicy.withFrame(selected.frame));
            java.util.Map<String,Object> policy=dev.denza.adaslink.Json.object();
            policy.put("routeId",o.routeId);policy.put("pathId",pathId);policy.put("epoch",o.routeEpoch);
            policy.put("nativeRouteType",nativeRouteType);policy.put("routeStatus",o.routeStatus);
            policy.put("nativePathReason",forceRouteRefresh?"source_lifetime_restart":"route_observation");
            policy.put("coordinateFrameMode",frameMode.name());policy.put("coordinateFrame",selected.frame.name());policy.put("coordinateRouteOrdinal",(long)selected.ordinal);
            policy.put("missingBooleanPolicy",routePolicy.compatibilityCn?"compatibility_cn_donor":"explicit_false_test_policy");
            policy.put("compatibilityProfile",routePolicy.compatibilityCn?"compatibility-cn":"none");
            policy.put("roadClassPolicy",routePolicy.roadClass.name());
            policy.put("historySpeedPolicy",routePolicy.historySpeed.name());
            policy.put("attributeEvidence",candidate.attributeEvidence());
            java.util.List<Object> decisions=new java.util.ArrayList<>();
            for(dev.denza.adaslink.yandexnative.FieldDecision decision:candidate.fieldDecisions()){
                java.util.Map<String,Object> value=dev.denza.adaslink.Json.object();
                value.put("field",decision.field);value.put("source",decision.source);value.put("kind",decision.kind.name());
                value.put("meaning",decision.meaning);value.put("absence",decision.absence);decisions.add(value);
            }
            policy.put("fields",decisions);policy.put("blockers",candidate.blockers());routeAudit.accept(policy);
            if(!candidate.admitted()){invalidateLocation();blocked=String.join("; ",candidate.blockers());rejectedPreparation=new RejectedPreparation(o,blocked);return;}
            rejectedPreparation=null;
            long previous=route==null?-1:route.getPathID();
            route=candidate;routeId=o.routeId;geometry=o.offlineGeometrySha256;routeEpoch=o.routeEpoch;
            forceRouteRefresh=false;
            routeProgressReady=false;
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
        sender.roadSpeedLimit(!routePolicy.compatibilityCn&&Double.isFinite(o.speedLimitMps)&&o.speedLimitMps>=0
                ? (int)Math.min(Integer.MAX_VALUE,Math.round(o.speedLimitMps*3.6)):0);
        if(o.positionOnRoute&&o.monotonicMs-lastNavi>=1000){
            YandexNativeAdapter.Guidance g=new YandexNativeAdapter.Guidance();g.scalar=o.scalar;g.timeToFinishS=o.timeToFinishS;
            g.onRoute=o.positionOnRoute&&!offRoute&&"FINE".equals(o.locationClass);g.fixAgeMs=o.fixAgeMs;
            NativeModels.NaviInfo info=YandexNativeAdapter.navi(route,g);
            if(info!=null){sender.progressUpdated(info,route);lastNavi=o.monotonicMs;routeProgressReady=true;}
        }
        // Original H does not clear its prior path/indices. The ordered facade queues v0
        // before P, so the first coherent progress input owns this route's location envelope.
        // Once initialized, off-route fixes keep the factory's last current-route envelope.
        if(!routeProgressReady){invalidateLocation();return;}
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
    void sourceGenerationChanged(){forceRouteRefresh=route!=null;lastNavi=-1;routeProgressReady=false;invalidateLocation();}
    private RouteFrame routeFrame(String id,boolean remember){
        if(id==null)return null;RouteFrame existing=routeFrames.get(id);if(existing!=null)return existing;
        if(routeFrames.size()>=FRAME_ROUTE_LIMIT)return null;
        int ordinal=routeFrames.size()+1;
        Coordinates.Frame frame=frameMode==LiveSessionLease.FrameMode.WGS84||frameMode==LiveSessionLease.FrameMode.ALTERNATE_ROUTES&&ordinal%2==0
                ?Coordinates.Frame.WGS84:Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE;
        RouteFrame choice=new RouteFrame(frame,ordinal);if(remember)routeFrames.put(id,choice);return choice;
    }
    boolean supportsCoordinates(String id,double lon,double lat,double rawLon,double rawLat){
        RouteFrame choice=routeFrame(id,false);return choice!=null&&YandexNativeAdapter.supportsCoordinate(lon,lat,choice.frame)&&YandexNativeAdapter.supportsCoordinate(rawLon,rawLat,choice.frame);
    }
    double[] sdkCoordinate(String id,double lon,double lat){RouteFrame choice=routeFrame(id,true);return choice==null?null:YandexNativeAdapter.sdkCoordinate(lon,lat,choice.frame);}
    Coordinates.Frame frame(){return route==null?null:route.frame();}
    int frameOrdinal(){RouteFrame choice=routeId==null?null:routeFrames.get(routeId);return choice==null?-1:choice.ordinal;}
    boolean acceptsRoute(String id,long epoch,String sha){return route!=null&&java.util.Objects.equals(routeId,id)&&routeEpoch==epoch&&java.util.Objects.equals(geometry,sha);}
    void serviceEvent(long topic){if(closed)return;if((topic>>>48)==1){if((topic&0xffff)==1)sender.serviceAvailable(topic);else if((topic&0xffff)==2)sender.serviceUnavailable(topic);}}
    void pathSelected(byte[] payload){if(!closed&&navigating)sender.pathSelected(payload);}
    private void stopNavigation(){
        invalidateLocation();
        if(navigating){navigating=false;sender.navigationCancelled();sender.navigationStopped();sender.navigationDestroyed();}
        route=null;routeId=null;geometry=null;lastFix=Long.MIN_VALUE;lastNavi=-1;
        routeProgressReady=false;
        forceRouteRefresh=false;
        rejectedPreparation=null;
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
