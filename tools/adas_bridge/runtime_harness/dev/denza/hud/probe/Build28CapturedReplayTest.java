package dev.denza.hud.probe;

import dev.denza.adaslink.*;
import dev.denza.adaslink.stocknative.*;
import dev.denza.adaslink.yandexnative.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Recorded SDK input regression, never a factory-output oracle. See fixture provenance:
 * raw-coordinate admission and vehicle speed are explicit test scaffolds. Android raw
 * speed was not recorded. Speed-from-fixes acceptance uses recorded SDK positions
 * and joined recorded CAN reads, and reports unrepaired SDK-zero fixes explicitly.
 * Reflection permits running this identical driver against the pre-experiment source
 * checkpoint to compare all baseline wire bytes with the same clocks and path IDs. */
public final class Build28CapturedReplayTest {
    static int checks;
    static final List<Object> reports=new ArrayList<>();
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object x){return (Map<String,Object>)x;}
    @SuppressWarnings("unchecked") static List<Object> list(Object x){return (List<Object>)x;}
    static long integer(Object x){return ((Number)x).longValue();}
    static double number(Object x){return ((Number)x).doubleValue();}
    static int field(Map<String,Object> x,String name){return x.containsKey(name)?((Number)x.get(name)).intValue():0;}
    static YandexNativeAdapter.Policy policy(boolean candidate){
        YandexNativeAdapter.Policy p=YandexNativeAdapter.Policy.STOCK_LIKE_BY_ROAD;
        if(!candidate)return p;
        try {
            Class<?> type=Class.forName("dev.denza.adaslink.yandexnative.AdapterExperiments");
            return (YandexNativeAdapter.Policy)p.getClass().getMethod("withExperiments",type)
                    .invoke(p,type.getConstructor(boolean.class,boolean.class,boolean.class,boolean.class)
                            .newInstance(true,true,true,true)); // Frozen build-28 policy; later experiments stay off.
        } catch(ReflectiveOperationException error){throw new AssertionError("candidate experiments unavailable",error);}
    }
    static final class RecordedAttributes implements RouteAttributes {
        final YandexRoute route;final List<Object> spans;final double[] cuts;
        RecordedAttributes(YandexRoute route,List<Object> spans){
            this.route=route;this.spans=spans;TreeSet<Double> all=new TreeSet<>();
            for(Object value:spans){Map<String,Object> s=map(value);all.add(number(s.get("begin")));all.add(number(s.get("end")));}
            cuts=new double[all.size()];int i=0;for(double at:all)cuts[i++]=at;
        }
        public boolean matches(YandexRoute value){return value==route;}
        public double[] cuts(){return cuts.clone();}
        public RoadNetwork.KnownAttributes attributesBetween(double begin,double end){throw new AssertionError("legacy projected fields are not an input source");}
        public RendererClass rendererClassBetween(double begin,double end){
            RendererClass common=null;double through=begin;
            for(Object value:spans){Map<String,Object> s=map(value);double b=number(s.get("begin")),e=number(s.get("end"));
                if(e<=begin||b>=end)continue;if(b>through)return RendererClass.unknown("fixture_gap");
                RendererClass raw=s.get("fc")==null?RendererClass.unknown((String)s.get("quality")):
                    RendererClass.literal(s.get("rawClass"),(String)s.get("classType"),s.get("fc"),(String)s.get("fcType"));
                common=common==null?raw:RendererClass.consensus(common,raw);through=Math.max(through,e);
            }
            return through>=end&&common!=null?common:RendererClass.unknown("fixture_gap");
        }
        public Map<String,Object> summary(){Map<String,Object> out=Json.object();out.put("source","recorded_typed_adapter_input_intervals");return out;}
    }
    static final class Transport implements StockTransport {
        final NativeMapSessionTest.Runtime runtime;
        final List<String> transcript=new ArrayList<>();
        final Map<Long,Map<String,Object>> latest=new HashMap<>();
        byte[] route;int locations;
        Transport(NativeMapSessionTest.Runtime runtime){this.runtime=runtime;}
        void note(String operation,long topic,byte[] payload){
            StringBuilder hex=new StringBuilder();if(payload!=null)for(byte b:payload)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            transcript.add(runtime.now+" "+operation+" "+topic+" "+hex);
        }
        public int fire(long topic,byte[] payload){note("fire",topic,payload);if(topic==Topics.ROUTE)route=payload.clone();if(topic==Topics.SD_VEHICLE_LOCATION)locations++;
            if(topic==Topics.SD_VEHICLE_LOCATION||topic==Topics.SD_TRAFFIC_LIGHT)latest.put(topic,Topics.schema(topic).decode(payload));return 0;}
        public int startService(long topic){note("start",topic,null);return 0;}
        public int stopService(long topic){note("stop",topic,null);return 0;}
        public int request(long topic,byte[] payload,boolean response){note("request:"+response,topic,payload);return 0;}
        Map<String,Object> fields(long topic,String name){return map(latest.get(topic).get(name));}
    }
    static final class Rig {
        final NativeMapSessionTest.Runtime runtime=new NativeMapSessionTest.Runtime();
        final NativeMapSessionTest.Environment environment=new NativeMapSessionTest.Environment();
        final Transport transport=new Transport(runtime);final NativeMapSession session;
        final List<Map<String,Object>> motionAudits=new ArrayList<>();
        final YandexNativeAdapter.AdaptedRoute partition;
        final long firstCapture;final boolean recordedVehicle;long vehicleSequence=-1;
        Rig(YandexRoute route,boolean candidate,Map<String,Object> fixture,boolean recordedVehicle){
            this.recordedVehicle=recordedVehicle;
            firstCapture=integer(map(list(fixture.get("progress")).get(0)).get("capturedUptimeMs"));
            YandexNativeAdapter.Policy p=policy(candidate);environment.speed=25;environment.donorProfile=p.donorProfile;
            long path=integer(map(fixture.get("provenance")).get("recordedPathId"));
            partition=YandexNativeAdapter.prepare(route,path,path,1,route.additionalAttributes,p);
            if(recordedVehicle)runtime.now=100000+integer(map(map(list(fixture.get("progress")).get(0)).get("vehicleRead")).get("journaledElapsedMs"))-firstCapture;
            NativeMapSession created;
            try{
                created=NativeMapSession.class.getDeclaredConstructor(StockRuntime.class,AutoCloseable.class,NativeEnvironment.class,StockTransport.class,
                        java.util.function.LongSupplier.class,java.util.function.Consumer.class,LiveSessionLease.FrameMode.class,PublicRoadEvidence.class,
                        OwnerLaneEvidence.class,YandexNativeAdapter.Policy.class,java.util.function.Consumer.class)
                        .newInstance(runtime,runtime,environment,transport,(java.util.function.LongSupplier)()->path,(java.util.function.Consumer<Map<String,Object>>)value->{},
                            LiveSessionLease.FrameMode.WGS84,PublicRoadEvidence.EMPTY,OwnerLaneEvidence.EMPTY,p,(java.util.function.Consumer<Map<String,Object>>)motionAudits::add);
            }catch(NoSuchMethodException oldSource){
                if(candidate)throw new AssertionError("candidate requires detailed motion audit",oldSource);
                created=new NativeMapSession(runtime,runtime,environment,transport,()->path,value->{},LiveSessionLease.FrameMode.WGS84,PublicRoadEvidence.EMPTY,OwnerLaneEvidence.EMPTY,p);
            }catch(ReflectiveOperationException error){throw new AssertionError(error);}
            session=created;
            environment.session=session;session.start(true,true,true);session.clientInitialized(true);runtime.advance(0);
        }
        NativeNavigationInput input(Map<String,Object> row,YandexRoute route,boolean includeRoute){
            long at=100000+integer(row.get("capturedUptimeMs"))-firstCapture;
            if(recordedVehicle){
                Map<String,Object> read=map(row.get("vehicleRead"));long readAt=100000+integer(read.get("journaledElapsedMs"))-firstCapture;
                check(readAt<=at&&integer(read.get("startedAtElapsedMs"))<=integer(read.get("completedAtElapsedMs"))&&integer(read.get("completedAtElapsedMs"))<=integer(read.get("journaledElapsedMs"))&&integer(row.get("capturedUptimeMs"))-integer(read.get("startedAtElapsedMs"))<=1500&&"ok".equals(read.get("status")),"recorded CAN read is independently fresh from source read start and not future");
                if(integer(read.get("sequence"))!=vehicleSequence){
                    check(readAt>=runtime.now,"recorded CAN source sequence never regresses during replay");runtime.advance(readAt-runtime.now);
                    environment.speed=number(read.get("value"));
                    try{NativeMapSession.class.getDeclaredMethod("vehicleSpeedUpdated",float.class,long.class)
                            .invoke(session,(float)environment.speed,100000+integer(read.get("startedAtElapsedMs"))-firstCapture);}
                    catch(ReflectiveOperationException error){throw new AssertionError("recorded CAN replay requires source-time freshness input",error);}
                    vehicleSequence=integer(read.get("sequence"));
                }
            }
            runtime.advance(at-runtime.now);
            Map<String,Object> loc=map(row.get("location")),pos=map(row.get("sourcePolylinePosition"));
            NativeNavigationInput o=new NativeNavigationInput();o.routeId=route.routeId;o.routeEpoch=7;o.offlineGeometrySha256="recorded-village-geometry";
            o.monotonicMs=runtime.now;o.stamp=integer(loc.get("relativeTimestamp"));o.fixAgeMs=integer(row.get("lastFineFixAgeMs"));
            o.mode="guidance";o.routeStatus="ON_ROUTE";o.positionOnRoute=true;o.locationClass=(String)row.get("locationClass");
            o.scalar=number(pos.get("segmentIndex"))+number(pos.get("segmentPosition"));o.timeToFinishS=number(row.get("timeToFinishS"));
            o.lon=number(loc.get("longitude"));o.lat=number(loc.get("latitude"));o.rawLon=o.lon;o.rawLat=o.lat;
            o.speedMps=number(loc.get("speed"));o.heading=number(loc.get("heading"));o.speedLimitMps=number(row.get("speedLimitMps"));
            if(includeRoute)o.route=route;return o;
        }
    }
    static YandexRoute route(Map<String,Object> fixture){
        YandexRoute route=YandexRoute.fromSnapshot(map(fixture.get("route")));
        route.additionalAttributes=new RecordedAttributes(route,list(fixture.get("typedIntervals")));return route;
    }
    static Map<String,Object> summary(YandexNativeAdapter.AdaptedRoute route,boolean candidate){
        Map<String,Object> report=Json.object();int links=0,zero=0,short10=0;long length=0;
        for(int s=0;s<route.getSegmentCount();s++)for(int l=0;l<route.getSegmentInfo(s).getLinkCount();l++){
            int metres=route.getSegmentInfo(s).getLinkInfo(l).getLength();links++;length+=metres;if(metres==0)zero++;if(metres<10)short10++;
        }
        report.put("policy",candidate?"candidate28":"baseline26");report.put("links",links);report.put("zeroLengthLinks",zero);report.put("linksShorterThan10RoundedMetres",short10);report.put("roundedTotalMetres",length);
        Object merge=route.attributeEvidence().get("shortLinkMerge");
        if(merge instanceof Map){
            Map<String,Integer> barriers=new TreeMap<>();
            for(Object value:list(map(merge).get("retainedTiny")))for(String side:new String[]{"leftBarrier","rightBarrier"}){
                String reason=String.valueOf(map(value).get(side));barriers.put(reason,barriers.getOrDefault(reason,0)+1);
            }
            report.put("retainedTinyAdjacentBarriers",barriers);
        }
        return report;
    }
    static List<String> geometry(NativePath.Segment segment){
        List<String> points=new ArrayList<>();for(int l=0;l<segment.getLinkCount();l++){
            List<NativePath.IntPoint> link=segment.getLinkInfo(l).getPoints();for(int i=l==0?0:1;i<link.size();i++)points.add(link.get(i).lon+","+link.get(i).lat);
        }return points;
    }
    static void routeAcceptance(YandexNativeAdapter.AdaptedRoute baseline,YandexNativeAdapter.AdaptedRoute candidate){
        check(field(summary(candidate,true),"zeroLengthLinks")==0,"all 23 recorded zero-length links must be removed");
        for(int s=0;s<baseline.getSegmentCount();s++){
            NativePath.Segment a=baseline.getSegmentInfo(s),b=candidate.getSegmentInfo(s);
            check(a.getLength()==b.getLength()&&a.getTravelTime()==b.getTravelTime(),"section metric length and ETA preserved at section "+s);
            check(a.getMainAction()==b.getMainAction()&&a.getAssistantAction()==b.getAssistantAction(),"maneuver stays at its original section endpoint "+s);
            check(a.getTrafficLightNum()==b.getTrafficLightNum(),"exact light count retained in section "+s);
            check(geometry(a).equals(geometry(b)),"every quantized source geometry point/cut retained in order in section "+s);
            for(int l=0;l<b.getLinkCount();l++){
                NativePath.Link link=b.getLinkInfo(l);check(link.getLength()>0,"candidate wire route has no zero links");
                if(link.getLength()<10)check((l==0||b.getLinkInfo(l-1).getLinkType()!=link.getLinkType())
                        &&(l+1==b.getLinkCount()||b.getLinkInfo(l+1).getLinkType()!=link.getLinkType()),"sub10m link remains only at a hard section/linkType barrier");
            }
        }
        List<NativePath.Point> a=baseline.getAllTrafficLights(),b=candidate.getAllTrafficLights();check(a.size()==b.size(),"static light list count preserved");
        for(int i=0;i<a.size();i++)check(a.get(i).lon==b.get(i).lon&&a.get(i).lat==b.get(i).lat,"exact static traffic-light coordinates/order preserved");
        for(double scalar=0;scalar<510;scalar+=.125){
            YandexNativeAdapter.Position p=baseline.position(scalar),q=candidate.position(scalar);
            check(p.segmentIndex==q.segmentIndex&&p.routeRemainM==q.routeRemainM&&p.segmentRemainM==q.segmentRemainM,"merging preserves original source-span distance envelope at "+scalar);
        }
        for(Object value:list(map(candidate.attributeEvidence().get("shortLinkMerge")).get("mapping"))){
            Map<String,Object> merged=map(value),chosen=map(merged.get("chosenAttributeEvidence")),longest=null;double through=number(merged.get("begin"));long lightCount=0;
            for(Object piece:list(merged.get("sourceIntervals"))){Map<String,Object> source=map(piece);
                check(number(source.get("begin"))==through,"merge audit keeps every original interval without a gap or overlap");through=number(source.get("end"));lightCount+=integer(source.get("trafficLightNum"));
                if(longest==null||number(source.get("exactMetres"))>number(longest.get("exactMetres")))longest=source;
            }
            check(through==number(merged.get("end")),"merged audit reaches the same exact source endpoint");
            check(chosen.get("begin").equals(longest.get("begin"))&&chosen.get("end").equals(longest.get("end")),"attributes come from longest original constituent, with earliest tie");
            check(integer(merged.get("trafficLightNum"))==lightCount,"merged light count sums every original constituent");
        }
    }
    static void wireRouteAcceptance(Rig r,boolean candidate){
        Map<String,Object> decoded=Topics.schema(Topics.ROUTE).decode(r.transport.route);
        Map<String,Object> outer=map(Json.parse((String)map(decoded.get("naviSDRouteStruct")).get("navigationSDLink2")));
        Map<String,Object> payload=map(Json.parse((String)outer.get("payload")));
        Map<String,Object> path=map(list(payload.get("data")).get(0));List<Object> links=list(path.get("links"));
        check(links.size()==field(summary(r.partition,candidate),"links"),"actual factory Route wire contains the accepted partition's links");
        int zero=0;long length=0;for(Object value:links){int metres=field(map(value),"len");length+=metres;if(metres==0)zero++;}
        check(length==12092,"actual factory Route wire preserves complete recorded metric length");
        check(zero==(candidate?0:23),"actual factory Route wire removes all recorded zero links only when merge policy enabled");
    }
    static void capturedSpeedAcceptance(List<Object> zeroRows){
        // Named recorded scenarios, independently checked from SDK distance/dt and joined prior CAN reads.
        Map<Long,Integer> expectedWire=new LinkedHashMap<>();
        long[] stamps={35942054,35943090,35944091,35945061,35946067,35948090,35953193,35956264,35963406,35964416,35968510};
        int[] speeds={0,0,0,0,0,7,3,19,0,0,0};
        for(int i=0;i<stamps.length;i++)expectedWire.put(stamps[i],speeds[i]);
        for(Object value:zeroRows){Map<String,Object> row=map(value);long stamp=integer(row.get("sdkStamp"));
            check(expectedWire.containsKey(stamp)&&field(row,"wireGpsSpeedKmh")==expectedWire.get(stamp),"recorded zero scenario has its declared before/after result at SDK stamp "+stamp);
            String expected=stamp==35964416||stamp==35968510?"actual_vehicle_speed_disagreement":expectedWire.get(stamp)>0?"derived_from_adjacent_fixes":"displacement_below_1m";
            check(expected.equals(row.get("speedReason")),"unrepaired recorded zeros retain their actual rejection reason at "+stamp);
        }
        Map<String,Object> freeze=map(zeroRows.get(8));
        check(number(freeze.get("derivedDistanceM"))<.1&&field(freeze,"wireGpsSpeedKmh")==0,"real 14:32:55 near-freeze is explicitly not repaired by a>=1m derivative rule");
    }
    static void replay(Map<String,Object> fixture,boolean candidate,Path transcript)throws Exception{
        YandexRoute route=route(fixture);Rig r=new Rig(route,candidate,fixture,transcript==null);
        Map<String,Object> report=summary(r.partition,candidate);reports.add(report);
        report.put("vehicleSpeedInput",transcript==null?"joined_recorded_prior_CAN_reads":"explicit_constant25_baseline_comparison_scaffold");
        if(candidate)routeAcceptance(YandexNativeAdapter.prepare(route,r.partition.getPathID(),r.partition.getPathID(),1,route.additionalAttributes,policy(false)),r.partition);
        check(r.partition.getSegmentCount()==8,"recorded route keeps all eight maneuver sections");
        check(r.partition.getSegmentInfo(2).getLength()==512,"recorded 512m section remains");
        check(r.partition.getSegmentInfo(3).getLinkCount()==1&&r.partition.getSegmentInfo(3).getLength()==67,"67m connector ends at its own LEFT boundary");
        if(candidate)check(r.partition.getSegmentInfo(4).getLinkInfo(0).getLength()==739,"same-section 2/0/737m become739m; 67m previous-section LEFT connector stays separate");
        else check(r.partition.getSegmentInfo(4).getLinkInfo(0).getLength()==2&&r.partition.getSegmentInfo(4).getLinkInfo(1).getLength()==0
                &&r.partition.getSegmentInfo(4).getLinkInfo(2).getLength()==737,"baseline retains recorded 2/0/737m source partition");
        List<Object> rows=list(fixture.get("progress")),zeroRows=new ArrayList<>();Set<Long> stamps=new HashSet<>();int zero=0,repaired=0;
        for(int index=0;index<rows.size();index++){
            Map<String,Object> row=map(rows.get(index));NativeNavigationInput o=r.input(row,route,index==0);
            check(stamps.add(o.stamp),"recorded SDK source identities stay unique");int old=r.transport.locations;r.session.observe(o);r.runtime.advance(0);
            if(index==0)wireRouteAcceptance(r,candidate);
            check(r.transport.locations>old,"each recorded FINE source fix reaches actual factory location wire");
            Map<String,Object> loc=r.transport.fields(Topics.SD_VEHICLE_LOCATION,"sdVehicleLocation");
            if(!candidate||o.speedMps>=.3)check(field(loc,"GPSspeed")==((int)(float)(o.speedMps*3.6)),"nonzero source SDK speed remains unchanged");
            if(o.speedMps==0){zero++;Map<String,Object> outcome=Json.object();outcome.put("capturedAtMs",row.get("capturedAtMs"));outcome.put("sdkStamp",o.stamp);outcome.put("sdkSpeedMps",o.speedMps);outcome.put("wireGpsSpeedKmh",field(loc,"GPSspeed"));outcome.put("vehicleRead",row.get("vehicleRead"));
                Map<String,Object> read=map(row.get("vehicleRead"));long readAge=integer(row.get("capturedUptimeMs"))-integer(read.get("startedAtElapsedMs"));outcome.put("vehicleSourceReadStartedAgeMs",readAge);outcome.put("vehicleSourceReadCompletedAgeMs",integer(row.get("capturedUptimeMs"))-integer(read.get("completedAtElapsedMs")));outcome.put("movingByFreshCANgt5_4Kmh",readAge<=1500&&number(read.get("value"))>5.4);
                if(candidate){Map<String,Object> audit=r.motionAudits.get(r.motionAudits.size()-1);for(String key:new String[]{"speedReason","speedSource","selectedSpeedMps","derivedSpeedMps","derivedDistanceM","derivedDtMs","derivedSource","derivedConsistencyEvidence"})outcome.put(key,audit.get(key));
                    check(!"raw_gps_coordinate_pair".equals(audit.get("derivedSource")),"unrecorded raw-coordinate scaffold cannot supply a measured raw derivative");
                }
                if(field(loc,"GPSspeed")>0)repaired++;zeroRows.add(outcome);
            }
            check(Math.abs(number(loc.get("locationLongitudeAssociateRoad"))-o.lon)<0.000001,"recorded matched coordinate reaches wire on its current fix");
            YandexNativeAdapter.Position expected=r.partition.position(o.scalar);
            if(candidate){
                check(field(loc,"curStepId")==expected.segmentIndex&&field(loc,"curLinkId")==expected.linkIndex,"candidate wire indices use this same fix and merged partition");
                check(field(loc,"linkOffset")==expected.linkRemainM&&field(loc,"distanceToNextStep")==expected.segmentRemainM,"candidate wire offsets use same fix before location");
            }
            Map<String,Object> light=r.transport.fields(Topics.SD_TRAFFIC_LIGHT,"sdTrafficLight");
            check(field(light,"trafficLightExist")== (candidate?1:2),"recorded distant light is masked only by explicit candidate policy");
            if(candidate)check(!light.containsKey("trafficLightLat")&&!light.containsKey("trafficLightLon"),"masked factory TrafficLight has no coordinates");
        }
        check(rows.size()==45&&zero==11,"fixture retains the complete 45-fix window, including eleven SDK zero readings");
        report.put("recordedSdkZeroRows",zeroRows);report.put("zeroSdkRowsWithPositiveWireSpeed",repaired);report.put("zeroSdkRowsStillZeroOnWire",zero-repaired);
        if(candidate){capturedSpeedAcceptance(zeroRows);check(repaired==3,"recorded SDK-only derivative repairs exactly3 of11 zero rows under the declared guard");
            int moving=0,unrepairedMoving=0;for(Object value:zeroRows){Map<String,Object> row=map(value);if(Boolean.TRUE.equals(row.get("movingByFreshCANgt5_4Kmh"))){moving++;if(field(row,"wireGpsSpeedKmh")==0)unrepairedMoving++;}}
            check(moving==6&&unrepairedMoving==3,"acceptance gap is visible:3 of6 independently moving SDK-zero rows still publish GPSzero");
            report.put("movingSdkZeroRows",moving);report.put("movingSdkZeroRowsStillZeroOnWire",unrepairedMoving);report.put("noZeroGpsWhileMovingAcceptance","NOT_FULFILLED_ON_RECORDED_MATCHED_FALLBACK");
            report.put("allMovingSdkZerosRepaired",false);report.put("rawPreferredBranchEstablishedByRecordedReplay",false);
            report.put("speedAcceptanceLimit","Matched-coordinate fallback has a visible near-freeze and two rejected catch-up steps; no claim that all moving-zero output is solved. Raw preferred branch requires source timestamps absent from this recording.");}
        if(transcript!=null)Files.write(transcript,r.transport.transcript,StandardCharsets.UTF_8);
        r.session.close();
    }
    /** Synthetic 10Hz scheduling stress on the captured route; cadence is not recorded driving evidence. */
    static void cadenceStress(Map<String,Object> fixture){
        YandexRoute route=route(fixture);Rig r=new Rig(route,true,fixture,false);
        Map<String,Object> source=map(list(fixture.get("progress")).get(0));
        long stamp=integer(map(source.get("location")).get("relativeTimestamp"));
        for(int i=0;i<250;i++){
            Map<String,Object> row=new LinkedHashMap<>(source);row.put("capturedUptimeMs",r.firstCapture+i*100L);
            NativeNavigationInput o=r.input(row,route,i==0);o.stamp=stamp+i*100L;o.scalar=19.9+i*.02;o.speedMps=5;
            double[] p=r.partition.coordinate(o.scalar);o.lon=o.rawLon=p[0];o.lat=o.rawLat=p[1];
            r.session.observe(o);r.runtime.advance(0);long progress=r.runtime.count(".v0"),locations=r.runtime.count(".P");
            r.session.observe(o.copy());r.runtime.advance(0);
            check(r.runtime.count(".v0")==progress&&r.runtime.count(".P")==locations,"100ms duplicate polling cannot replay progress/location");
            check(progress==i+1&&locations==i+1,"10Hz unique source fixes retain one ordered callback pair each");
            int queries=0;for(NativeMapSessionTest.Runtime.Task task:r.runtime.tasks)
                if(task.identity instanceof StockTask&&"query ahead lanes".equals(((StockTask)task.identity).purpose))queries++;
            check(queries<=1&&r.runtime.tasks.size()<20,"100ms progress cannot multiply factory query timers or grow scheduler backlog");
        }
        r.session.close();check(r.runtime.tasks.isEmpty(),"arm closure cancels high-cadence work and all query timers");
    }
    public static void main(String[] args)throws Exception{
        if(args.length<1)throw new IllegalArgumentException("fixture path required");
        Map<String,Object> fixture=map(Json.parse(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8)));
        if(args.length==3&&"--baseline-wire".equals(args[1]))replay(fixture,false,Paths.get(args[2]));
        else{replay(fixture,false,null);replay(fixture,true,null);cadenceStress(fixture);}
        if(args.length==3&&"--report".equals(args[1]))Files.write(Paths.get(args[2]),Collections.singletonList(Json.write(reports)),StandardCharsets.UTF_8);
        List<Object> compact=new ArrayList<>();for(Object value:reports){Map<String,Object> row=new LinkedHashMap<>(map(value));row.remove("recordedSdkZeroRows");compact.add(row);}
        System.out.println(Json.write(compact));
        System.out.println("Build28CapturedReplayTest: "+checks+" checks passed (SDK replay; raw speed unavailable)");
    }
}
