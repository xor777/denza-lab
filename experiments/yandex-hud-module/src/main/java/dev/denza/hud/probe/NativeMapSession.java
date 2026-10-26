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
    private final java.util.function.Consumer<java.util.Map<String,Object>> motionAudit;
    private String lastMotionAuditKey, motionReason, progressReason;
    private String motionSpeedReason;
    private double motionSelectedSpeed;
    private long motionSpeedSelectedAt;
    private double motionDerivedSpeed=Double.NaN,motionDerivedDistance=Double.NaN;
    private double motionConsistencyUpper=Double.NaN,motionPreviousSdkSpeed=Double.NaN;
    private Double motionPreviousVehicleMps,motionCurrentVehicleMps;
    private long motionDerivedDt=-1;
    private String motionDerivedSource,motionConsistency;
    private MotionFix previousMotionFix;
    /** Input observations only: never retain a substituted speed as a subsequent source. */
    private static final class MotionFix {
        final long stamp,rawAt;final double lon,lat,rawLon,rawLat,sdkSpeed;final Double vehicleMps;final boolean rawUsable;
        MotionFix(NativeNavigationInput o,long now,Double vehicle){
            stamp=o.stamp;lon=o.lon;lat=o.lat;sdkSpeed=o.speedMps;rawAt=o.rawAtElapsedMs;rawLon=o.rawLon;rawLat=o.rawLat;vehicleMps=vehicle;
            rawUsable=!o.rawMock&&rawAt>=0&&now>=rawAt&&now-rawAt<=1500&&coordinate(rawLon,rawLat);
        }
    }
    private NativeModels.LocInfo motionLocationInput;
    private double lastProgressScalar=Double.NaN;
    private long lastCoherentFix=Long.MIN_VALUE;
    private String currentPathReason;
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
    private long routeEpoch=-1, lastFix=Long.MIN_VALUE, lastNavi=-1, lastHistoryReset=-1;
    private boolean historyResetFresh;
    private String routeId, geometry, blocked;
    private YandexNativeAdapter.AdaptedRoute route;
    private NativeModels.NaviInfo lastNaviInfo;
    private Boolean service, effectiveSwitch, awake;
    private boolean offRouteActive,offRouteReplacementPending,arrivalPending,draining,routeProgressReady,forceRouteRefresh;
    private Float latestVehicleSpeed;private long vehicleSpeedObservedAt=-1,vehicleSpeedSourceAt=-1;
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
        this(runtime,owner,environment,transport,ids,routeAudit,frameMode,publicRoadEvidence,laneEvidence,routePolicy,value->{});
    }
    NativeMapSession(StockRuntime runtime,AutoCloseable owner,NativeEnvironment environment,
                     StockTransport transport,LongSupplier ids,java.util.function.Consumer<java.util.Map<String,Object>> routeAudit,
                     LiveSessionLease.FrameMode frameMode,PublicRoadEvidence publicRoadEvidence,OwnerLaneEvidence laneEvidence,
                     YandexNativeAdapter.Policy routePolicy,java.util.function.Consumer<java.util.Map<String,Object>> motionAudit){
        this.laneEvidence=java.util.Objects.requireNonNull(laneEvidence,"owner lane evidence");
        this.publicRoadEvidence=java.util.Objects.requireNonNull(publicRoadEvidence,"public-road evidence");
        this.routePolicy=java.util.Objects.requireNonNull(routePolicy,"route policy").withPublicRoadEvidence(publicRoadEvidence).withLaneEvidence(laneEvidence);
        this.runtime=runtime;this.runtimeOwner=owner; this.ids=ids;this.routeAudit=routeAudit;this.motionAudit=java.util.Objects.requireNonNull(motionAudit,"motion audit");
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
        if(closed||draining)return;
        motionReason="not_admitted";progressReason="not_updated";motionLocationInput=null;motionSpeedReason=null;motionSelectedSpeed=Double.NaN;motionSpeedSelectedAt=-1;
        motionDerivedSpeed=Double.NaN;motionDerivedDistance=Double.NaN;motionDerivedDt=-1;motionDerivedSource=null;motionConsistency=null;
        motionConsistencyUpper=Double.NaN;motionPreviousSdkSpeed=Double.NaN;motionPreviousVehicleMps=null;motionCurrentVehicleMps=null;
        try{observeInput(o);}finally{auditMotion(o);}
    }
    private void observeInput(NativeNavigationInput o){
        if(closed||draining||o.routeEpoch<routeEpoch)return;
        if(o.routeId==null||"off".equals(o.mode)){stopNavigation();routeEpoch=o.routeEpoch;return;}
        if(o.simulated){clearHistorySpeed(o.monotonicMs,false);invalidateLocation();blocked="simulated guidance cannot publish to ADAS";return;}
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
            policy.put("missingBooleanPolicy",routePolicy.donorProfile==null?"explicit_false_test_policy":routePolicy.donorProfile.id+"_donor");
            policy.put("compatibilityProfile",routePolicy.donorProfile==null?"none":routePolicy.donorProfile.id);
            policy.put("roadClassPolicy",routePolicy.roadClass.name());
            policy.put("historySpeedPolicy",routePolicy.historySpeed.name());
            policy.put("adapterExperiments",routePolicy.experiments.toMap());
            policy.put("gpsSpeedSourcePolicy",routePolicy.experiments.speedFromFixes?"sdk_below_0.3_adjacent_fine_displacement_raw_pair_then_sdk_pair":"yandex_sdk");
            if(routePolicy.experiments.speedFromFixes){
                policy.put("speedFromFixesWindowMs",java.util.Arrays.asList(200,1500));policy.put("speedFromFixesMinDistanceM",1);
                policy.put("speedFromFixesConsistency","EXPERIMENT: fresh actual vehicle zero veto; upper disagreement max(2*v,v+2m/s) from independent vehicle endpoint readings, otherwise positive SDK endpoint; no manufacturer physical-limit claim; no independent anchor and both SDK speeds zero cannot distinguish a jump from movement");
            }
            policy.put("progressSourcePolicy",routePolicy.experiments.coherentProgress?"same_accepted_fix":"sampled_1000ms");
            policy.put("attributeEvidence",candidate.attributeEvidence());
            java.util.List<Object> decisions=new java.util.ArrayList<>();
            for(dev.denza.adaslink.yandexnative.FieldDecision decision:candidate.fieldDecisions()){
                java.util.Map<String,Object> value=dev.denza.adaslink.Json.object();
                value.put("field",decision.field);value.put("source",decision.source);value.put("kind",decision.kind.name());
                value.put("meaning",decision.meaning);value.put("absence",decision.absence);decisions.add(value);
            }
            if(routePolicy.experiments.speedFromFixes){
                java.util.Map<String,Object> speedDecision=dev.denza.adaslink.Json.object();
                speedDecision.put("field","LocInfo.speed / VehicleLocation.GPSspeed");speedDecision.put("kind","DERIVED");
                speedDecision.put("source","adjacent fresh FINE source fixes; independently timestamped non-mock raw coordinates preferred, SDK matched pair otherwise");
                speedDecision.put("meaning","EXPERIMENT: SDK speed below 0.3m/s with displacement at least 1m over 200–1500ms uses distance/time, subject to logged source-consistency guards; positive SDK speed remains exact");
                speedDecision.put("absence","retain original SDK speed on first fix, missing/invalid pair, stop, gap, reset or disagreement; no coordinate fabrication or CAN-to-GPS substitution");decisions.add(speedDecision);
            }
            policy.put("fields",decisions);policy.put("blockers",candidate.blockers());routeAudit.accept(policy);
            if(!candidate.admitted()){invalidateLocation();blocked=String.join("; ",candidate.blockers());rejectedPreparation=new RejectedPreparation(o,blocked);return;}
            rejectedPreparation=null;
            long previous=route==null?-1:route.getPathID();
            route=candidate;routeId=o.routeId;geometry=o.offlineGeometrySha256;routeEpoch=o.routeEpoch;
            currentPathReason=forceRouteRefresh?"source_lifetime_restart":"route_observation";lastCoherentFix=Long.MIN_VALUE;lastProgressScalar=Double.NaN;
            previousMotionFix=null;
            forceRouteRefresh=false;
            routeProgressReady=false;lastNaviInfo=null;lastHistoryReset=-1;
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
        sender.roadSpeedLimit(routePolicy.donorProfile!=null?routePolicy.donorProfile.speedLimitKmh:
                Double.isFinite(o.speedLimitMps)&&o.speedLimitMps>=0
                ? (int)Math.min(Integer.MAX_VALUE,Math.round(o.speedLimitMps*3.6)):0);
        boolean fresh="FINE".equals(o.locationClass)&&o.fixAgeMs>=0&&o.fixAgeMs<=1500;
        NativeModels.NaviInfo progress=null;
        if(o.positionOnRoute&&!offRoute&&fresh){
            YandexNativeAdapter.Guidance g=new YandexNativeAdapter.Guidance();g.scalar=o.scalar;g.timeToFinishS=o.timeToFinishS;
            g.onRoute=true;g.fixAgeMs=o.fixAgeMs;progress=YandexNativeAdapter.navi(route,g);
        }
        if(progress==null)clearHistorySpeed(o.monotonicMs,fresh);
        else if(!routePolicy.experiments.coherentProgress&&(o.monotonicMs-lastNavi>=1000||lastHistoryReset>=0)){
            sender.progressUpdated(progress,route);lastNaviInfo=progress;lastNavi=o.monotonicMs;routeProgressReady=true;lastHistoryReset=-1;
            lastProgressScalar=o.scalar;
            progressReason="sampled_1000ms";
        }
        // Original H does not clear its prior path/indices. The ordered facade queues v0
        // before P, so the first coherent progress input owns this route's location envelope.
        // Once initialized, off-route fixes keep the factory's last current-route envelope.
        if(!routeProgressReady&&!routePolicy.experiments.coherentProgress){motionReason="waiting_first_route_progress";invalidateLocation();return;}
        if("FINE".equals(o.locationClass)&&o.fixAgeMs>=0&&o.fixAgeMs<=1500&&!o.simulated){
            YandexNativeAdapter.Location l=new YandexNativeAdapter.Location();
            l.scalar=o.scalar;l.matchedLon=o.lon;l.matchedLat=o.lat;l.rawLon=o.rawLon;l.rawLat=o.rawLat;
            // Preserve every existing SDK admission check before selecting another speed source.
            if(!Double.isFinite(o.speedMps)||o.speedMps<0){motionReason="invalid_sdk_speed";invalidateLocation();return;}
            l.speedMps=o.speedMps;l.headingDeg=o.heading;l.simulated=o.simulated;l.onRoute=o.positionOnRoute;
            NativeModels.LocInfo info=YandexNativeAdapter.location(route,l);
            if(info!=null){
                if(o.stamp<=(routePolicy.experiments.coherentProgress?lastCoherentFix:lastFix)){
                    if(previousMotionFix!=null&&o.stamp<previousMotionFix.stamp)previousMotionFix=null;
                    motionReason="duplicate_or_regressed_fix";return;
                }
                if(routePolicy.experiments.coherentProgress){
                    if(progress!=null){sender.progressUpdated(progress,route);lastNaviInfo=progress;lastNavi=o.monotonicMs;lastProgressScalar=o.scalar;routeProgressReady=true;lastHistoryReset=-1;progressReason="same_accepted_fix";}
                    else if(!offRoute||!routeProgressReady){motionReason="missing_current_progress";invalidateLocation();return;}
                    else progressReason="offroute_retains_factory_envelope";
                }
                motionSpeedSelectedAt=runtime.elapsedRealtime();motionSelectedSpeed=speedFromFixes(o);
                if(motionSelectedSpeed!=o.speedMps){l.speedMps=motionSelectedSpeed;info=YandexNativeAdapter.location(route,l);}
                sender.locationUpdated(info,runtime.elapsedRealtime()+1500-o.fixAgeMs);lastFix=o.stamp;lastCoherentFix=o.stamp;motionLocationInput=info;motionReason="accepted";
            }
            else{motionReason="invalid_location_input";invalidateLocation();}
        }else{motionReason="stale_or_non_fine_fix";invalidateLocation();}
    }
    private Double freshVehicleMps(long now){
        return latestVehicleSpeed!=null&&latestVehicleSpeed>=0&&vehicleSpeedSourceAt>=0&&now>=vehicleSpeedSourceAt&&now-vehicleSpeedSourceAt<=1500?latestVehicleSpeed/3.6:null;
    }
    private static boolean coordinate(double lon,double lat){return Double.isFinite(lon)&&Double.isFinite(lat)&&lon>=-180&&lon<=180&&lat>=-90&&lat<=90;}
    private static double disagreementUpper(double sourceMps){return Math.max(2*sourceMps,sourceMps+2);}
    /** Experimental average-displacement vs point-speed disagreement gate, not a physical
     * vehicle limit: factor two permits sampling/acceleration disagreement, +2m/s avoids
     * an arbitrarily tight relative bound at low nonzero source speed. Fresh actual zero
     * is a separate explicit stop veto. Neither independent input is substituted as GPS. */
    private double speedFromFixes(NativeNavigationInput o){
        if(!routePolicy.experiments.speedFromFixes){motionSpeedReason="experiment_disabled";return o.speedMps;}
        if(o.offRoute()||!o.positionOnRoute){previousMotionFix=null;motionSpeedReason="offroute_history_reset";return o.speedMps;}
        long now=runtime.elapsedRealtime();MotionFix current=new MotionFix(o,now,freshVehicleMps(now));
        MotionFix previous=previousMotionFix;previousMotionFix=current;
        motionCurrentVehicleMps=current.vehicleMps;
        // A positive SDK measurement is retained exactly, including a raw-speed disagreement.
        if(o.speedMps>=0.3){motionSpeedReason="sdk_speed_nonzero";return o.speedMps;}
        if(previous==null){motionSpeedReason="first_fix_no_history";return o.speedMps;}
        motionPreviousSdkSpeed=previous.sdkSpeed;motionPreviousVehicleMps=previous.vehicleMps;
        long sdkDt=current.stamp-previous.stamp;
        if(sdkDt<200||sdkDt>1500){motionSpeedReason=sdkDt<=0?"source_time_regressed_or_duplicate":"source_pair_interval_outside_200_1500ms";motionDerivedDt=sdkDt;return o.speedMps;}
        boolean rawPair=previous.rawUsable&&current.rawUsable&&current.rawAt-previous.rawAt>=200&&current.rawAt-previous.rawAt<=1500;
        motionDerivedSource=rawPair?"raw_gps_coordinate_pair":"sdk_matched_coordinate_pair";
        motionDerivedDt=rawPair?current.rawAt-previous.rawAt:sdkDt;
        motionDerivedDistance=rawPair?Coordinates.distance(previous.rawLon,previous.rawLat,current.rawLon,current.rawLat):Coordinates.distance(previous.lon,previous.lat,current.lon,current.lat);
        motionDerivedSpeed=motionDerivedDistance/(motionDerivedDt/1000.0);
        if(!Double.isFinite(motionDerivedSpeed)||motionDerivedSpeed*3.6>Integer.MAX_VALUE){motionSpeedReason="derived_speed_outside_native_range";return o.speedMps;}
        if(motionDerivedDistance<1){motionSpeedReason="displacement_below_1m";return o.speedMps;}
        if(current.vehicleMps!=null){
            motionConsistency="fresh_actual_vehicle_speed";
            if(current.vehicleMps==0){motionSpeedReason="actual_vehicle_stop";return o.speedMps;}
            double endpoint=Math.max(current.vehicleMps,previous.vehicleMps==null?current.vehicleMps:previous.vehicleMps);
            motionConsistencyUpper=disagreementUpper(endpoint);
            if(motionDerivedSpeed>motionConsistencyUpper){motionSpeedReason="actual_vehicle_speed_disagreement";return o.speedMps;}
        }else if(previous.sdkSpeed>=0.3){
            motionConsistency="positive_previous_sdk_speed_only";
            motionConsistencyUpper=disagreementUpper(previous.sdkSpeed);
            if(motionDerivedSpeed>motionConsistencyUpper){motionSpeedReason="sdk_endpoint_speed_disagreement";return o.speedMps;}
        }else motionConsistency="no_independent_speed_anchor";
        motionSpeedReason="derived_from_adjacent_fixes";return motionDerivedSpeed;
    }
    private void auditMotion(NativeNavigationInput o){
        String key=o.routeEpoch+":"+o.routeId+":"+(route==null?-1:route.getPathID())+":"+o.stamp+":"+o.rawAtElapsedMs;
        if(key.equals(lastMotionAuditKey))return;lastMotionAuditKey=key;
        java.util.Map<String,Object> a=dev.denza.adaslink.Json.object();
        a.put("routeId",o.routeId);a.put("epoch",o.routeEpoch);a.put("pathId",route==null?-1:route.getPathID());a.put("routeStatus",o.routeStatus);a.put("nativePathReason",currentPathReason);
        a.put("adapterExperiments",routePolicy.experiments.toMap());a.put("admissionReason",motionReason);a.put("blocked",blocked);a.put("progressReason",progressReason);
        a.put("observedElapsedMs",o.monotonicMs);a.put("adapterSubmissionElapsedMs",runtime.elapsedRealtime());a.put("sdkStamp",o.stamp);a.put("sdkAbsoluteTimestampMs",o.sdkAbsoluteTimestampMs);a.put("sdkRelativeTimestampMs",o.sdkRelativeTimestampMs);
        a.put("sdkFixAgeMs",o.fixAgeMs);a.put("locationClass",o.locationClass);a.put("sdkAccuracyM",finite(o.sdkAccuracyM));a.put("sdkSpeedMps",finite(o.speedMps));
        a.put("rawAtElapsedMs",o.rawAtElapsedMs);a.put("rawTimestampMs",o.rawTimestampMs);a.put("rawAgeMs",o.rawAtElapsedMs<0?null:runtime.elapsedRealtime()-o.rawAtElapsedMs);
        a.put("rawHasSpeed",o.rawHasSpeed);a.put("rawSpeedMps",finite(o.rawSpeedMps));a.put("rawHasAccuracy",o.rawHasAccuracy);a.put("rawAccuracyM",finite(o.rawAccuracyM));a.put("rawHasSpeedAccuracy",o.rawHasSpeedAccuracy);a.put("rawSpeedAccuracyMps",finite(o.rawSpeedAccuracyMps));
        a.put("rawHasBearing",o.rawHasBearing);a.put("rawBearing",finite(o.rawBearing));a.put("rawMock",o.rawMock);a.put("rawSourceLon",finite(o.rawSourceLon));a.put("rawSourceLat",finite(o.rawSourceLat));
        String speedDecision=motionSpeedReason==null?"not_selected_"+motionReason:motionSpeedReason;
        double selected=motionSpeedReason==null?o.speedMps:motionSelectedSpeed;
        a.put("selectedSpeedMps",finite(selected));a.put("speedReason",speedDecision);a.put("speedSource","derived_from_adjacent_fixes".equals(speedDecision)?motionDerivedSource:"yandex_sdk");a.put("speedSelectedAtElapsedMs",motionSpeedSelectedAt);
        a.put("derivedSpeedMps",finite(motionDerivedSpeed));a.put("derivedDistanceM",finite(motionDerivedDistance));a.put("derivedDtMs",motionDerivedDt<0?null:motionDerivedDt);a.put("derivedSource",motionDerivedSource);a.put("derivedConsistencyEvidence",motionConsistency);
        a.put("derivedConsistencyUpperMps",finite(motionConsistencyUpper));a.put("derivedPreviousSdkSpeedMps",finite(motionPreviousSdkSpeed));a.put("derivedPreviousVehicleMps",motionPreviousVehicleMps);a.put("derivedCurrentVehicleMps",motionCurrentVehicleMps);
        a.put("matchedLon",finite(o.lon));a.put("matchedLat",finite(o.lat));a.put("rawLon",finite(o.rawLon));a.put("rawLat",finite(o.rawLat));a.put("heading",finite(o.heading));a.put("scalar",finite(o.scalar));a.put("timeToFinishS",finite(o.timeToFinishS));a.put("positionOnRoute",o.positionOnRoute);a.put("simulated",o.simulated);
        // This observation precedes queued P processing, which reads realSpeed independently.
        a.put("latestObservedVehicleSpeedKmh",latestVehicleSpeed);a.put("latestObservedVehicleSpeedAgeMs",vehicleSpeedObservedAt<0?null:runtime.elapsedRealtime()-vehicleSpeedObservedAt);
        a.put("latestObservedVehicleSpeedSourceAtElapsedMs",vehicleSpeedSourceAt<0?null:vehicleSpeedSourceAt);a.put("latestObservedVehicleSpeedSourceAgeMs",vehicleSpeedSourceAt<0?null:runtime.elapsedRealtime()-vehicleSpeedSourceAt);
        a.put("progressScalar",finite(lastProgressScalar));a.put("progressObservedElapsedMs",lastNavi);
        if(lastNaviInfo!=null){a.put("progressPathId",lastNaviInfo.pathID);a.put("progressSegment",lastNaviInfo.curSegIdx);a.put("progressLink",lastNaviInfo.curLinkIdx);a.put("progressPoint",lastNaviInfo.curPointIdx);a.put("progressLinkRemainM",lastNaviInfo.linkRemainDist);}
        if(motionLocationInput!=null){NativeModels.LocMatchInfo match=motionLocationInput.matchInfo.get(0);a.put("nativeInputSpeedKmh",motionLocationInput.speed);a.put("nativeInputRawLon",motionLocationInput.gpsPos.lon);a.put("nativeInputRawLat",motionLocationInput.gpsPos.lat);a.put("nativeInputMatchedLon",match.stPos.lon);a.put("nativeInputMatchedLat",match.stPos.lat);a.put("nativeInputCourse",match.course);a.put("nativeInputLinkId",match.linkId);}
        motionAudit.accept(a);
    }
    private static Object finite(double value){return Double.isFinite(value)?value:null;}
    private void clearHistorySpeed(long observedAt,boolean fresh){
        if(lastNaviInfo==null||lastNaviInfo.curLinkSpeed==0)return;
        if(lastHistoryReset>=0&&observedAt-lastHistoryReset<1000&&(!fresh||historyResetFresh))return;
        // Missing/stale progress cannot supply new distances or indices. Retain its last
        // coherent envelope and clear only speed. Queued output may fail final admission;
        // retry on fresh recovery and at most once per second until coherent progress returns.
        sender.progressUpdated(YandexNativeAdapter.clearHistorySpeed(lastNaviInfo),route);
        progressReason="history_speed_cleared_retained_envelope";
        lastHistoryReset=observedAt;historyResetFresh=fresh;
    }
    void clientInitialized(boolean ready){if(!closed)sender.clientInitialized(ready);}
    void sourceGenerationChanged(){forceRouteRefresh=route!=null;lastNavi=-1;lastNaviInfo=null;lastHistoryReset=-1;lastProgressScalar=Double.NaN;lastCoherentFix=Long.MIN_VALUE;routeProgressReady=false;invalidateLocation();}
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
        route=null;routeId=null;geometry=null;lastFix=Long.MIN_VALUE;lastNavi=-1;lastNaviInfo=null;lastHistoryReset=-1;
        lastCoherentFix=Long.MIN_VALUE;lastProgressScalar=Double.NaN;currentPathReason=null;
        routeProgressReady=false;
        forceRouteRefresh=false;
        rejectedPreparation=null;
        offRouteActive=false;offRouteReplacementPending=false;
    }
    void invalidateLocation(){previousMotionFix=null;sender.invalidateLocation();lastFix=Long.MIN_VALUE;}
    void openCitiesUpdated(java.util.List<Integer> values){if(!closed)sender.openCitiesUpdated(values);}
    void cityCodes(java.util.List<Integer> values){openCitiesUpdated(values);}
    void vehicleSpeedUpdated(float kmh){vehicleSpeedUpdated(kmh,runtime.elapsedRealtime());}
    void vehicleSpeedUpdated(float kmh,long sourceStartedAtElapsedMs){if(!closed&&!draining&&Float.isFinite(kmh)){latestVehicleSpeed=kmh;vehicleSpeedObservedAt=runtime.elapsedRealtime();vehicleSpeedSourceAt=sourceStartedAtElapsedMs;sender.vehicleSpeedUpdated(kmh);}}
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
