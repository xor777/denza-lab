package dev.denza.hud.probe;

import dev.denza.adaslink.*;
import dev.denza.adaslink.stocknative.*;
import dev.denza.adaslink.yandexnative.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Experimental CAN-source acceptance on recorded SDK/CAN inputs. This is not a
 * replay of factory locator fusion or evidence about NOA retention. Raw coordinates
 * are an explicit SDK-coordinate admission scaffold, never a claimed raw recording.
 * Reflection keeps this identical wire driver compilable against frozen v2 / pre-flag
 * sources for finite before/after regression comparisons with identical clocks/IDs. */
public final class Build29CapturedReplayTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") static List<Object> list(Object value){return (List<Object>)value;}
    static double number(Object value){return ((Number)value).doubleValue();}
    static long integer(Object value){return ((Number)value).longValue();}
    static int scalar(Map<String,Object> value,String key){return ((Number)value.getOrDefault(key,0)).intValue();}
    static YandexNativeAdapter.Policy policy(boolean vehicle,boolean oldFlags,boolean derivative){
        YandexNativeAdapter.Policy policy=YandexNativeAdapter.Policy.STOCK_LIKE_BY_ROAD;
        try{
            Class<?> type=Class.forName("dev.denza.adaslink.yandexnative.AdapterExperiments");Object experiments;
            try{experiments=type.getConstructor(boolean.class,boolean.class,boolean.class,boolean.class,boolean.class)
                    .newInstance(oldFlags,derivative,oldFlags,oldFlags,vehicle);}
            catch(NoSuchMethodException v2){if(vehicle)throw new AssertionError("CAN-source policy missing");
                experiments=type.getConstructor(boolean.class,boolean.class,boolean.class,boolean.class).newInstance(oldFlags,derivative,oldFlags,oldFlags);}
            return (YandexNativeAdapter.Policy)policy.getClass().getMethod("withExperiments",type).invoke(policy,experiments);
        }catch(ClassNotFoundException oldSource){if(vehicle||oldFlags)throw new AssertionError("experiment policy missing");return policy;}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    static final class RecordedAttributes implements RouteAttributes {
        final YandexRoute route;final List<Object> intervals;
        RecordedAttributes(YandexRoute route,List<Object> intervals){this.route=route;this.intervals=intervals;}
        public boolean matches(YandexRoute value){return route==value;}
        public double[] cuts(){TreeSet<Double> cuts=new TreeSet<>();for(Object value:intervals){Map<String,Object> row=map(value);cuts.add(number(row.get("begin")));cuts.add(number(row.get("end")));}
            double[] result=new double[cuts.size()];int i=0;for(double cut:cuts)result[i++]=cut;return result;}
        public RoadNetwork.KnownAttributes attributesBetween(double begin,double end){throw new AssertionError("legacy converted attributes are not source evidence");}
        public RendererClass rendererClassBetween(double begin,double end){
            RendererClass common=null;double through=begin;
            for(Object value:intervals){Map<String,Object> row=map(value);double b=number(row.get("begin")),e=number(row.get("end"));
                if(e<=begin||b>=end)continue;if(b>through)return RendererClass.unknown("fixture_gap");
                RendererClass raw=row.get("fc")==null?RendererClass.unknown((String)row.get("quality")):
                    RendererClass.literal(row.get("rawClass"),(String)row.get("classType"),row.get("fc"),(String)row.get("fcType"));
                common=common==null?raw:RendererClass.consensus(common,raw);through=Math.max(through,e);
            }return through>=end&&common!=null?common:RendererClass.unknown("fixture_gap");
        }
        public Map<String,Object> summary(){return Collections.<String,Object>singletonMap("source","recorded_typed_adapter_input_intervals");}
    }
    static YandexRoute route(Map<String,Object> fixture){YandexRoute route=YandexRoute.fromSnapshot(map(fixture.get("route")));route.additionalAttributes=new RecordedAttributes(route,list(fixture.get("typedIntervals")));return route;}
    static final class Transport implements StockTransport {
        final NativeMapSessionTest.Runtime runtime;final List<String> wire=new ArrayList<>();
        final List<Map<String,Object>> positions=new ArrayList<>();byte[] route;int blockedLocations;java.util.function.BooleanSupplier locationSpeedFresh=()->true;
        Transport(NativeMapSessionTest.Runtime runtime){this.runtime=runtime;}
        void record(String action,long topic,byte[] payload){StringBuilder hex=new StringBuilder();if(payload!=null)for(byte value:payload)hex.append(String.format(Locale.ROOT,"%02x",value&255));wire.add(runtime.now+" "+action+" "+topic+" "+hex);}
        public int fire(long topic,byte[] payload){
            // Match the product final-egress guard: overdue factory stationary tasks
            // must not bypass the source deadline while a later expiry fence waits.
            if(topic==Topics.SD_VEHICLE_LOCATION&&!locationSpeedFresh.getAsBoolean()){blockedLocations++;return -203;}
            record("fire",topic,payload);if(topic==Topics.ROUTE)route=payload.clone();
            if(topic==Topics.SD_VEHICLE_LOCATION)positions.add(map(Topics.schema(topic).decode(payload).get("sdVehicleLocation")));return 0;}
        public int startService(long topic){record("start",topic,null);return 0;}
        public int stopService(long topic){record("stop",topic,null);return 0;}
        public int request(long topic,byte[] payload,boolean response){record("request:"+response,topic,payload);return 0;}
        Map<String,Object> last(){check(!positions.isEmpty(),"actual factory position wire exists");return positions.get(positions.size()-1);}
    }
    static final class Rig {
        final NativeMapSessionTest.Runtime runtime=new NativeMapSessionTest.Runtime();
        final NativeMapSessionTest.Environment environment=new NativeMapSessionTest.Environment();
        final Transport transport=new Transport(runtime);final List<Map<String,Object>> audits=new ArrayList<>();
        final NativeMapSession session;final YandexRoute source;final long firstCapture;long vehicleSequence=-1;
        Rig(Map<String,Object> fixture,boolean vehicle,boolean oldFlags,boolean recordedCan){this(fixture,vehicle,oldFlags,recordedCan,oldFlags);}
        Rig(Map<String,Object> fixture,boolean vehicle,boolean oldFlags,boolean recordedCan,boolean derivative){
            source=route(fixture);firstCapture=integer(map(list(fixture.get("progress")).get(0)).get("capturedUptimeMs"));
            YandexNativeAdapter.Policy policy=policy(vehicle,oldFlags,derivative);environment.donorProfile=policy.donorProfile;environment.speed=25;
            long path=integer(map(fixture.get("provenance")).get("recordedPathId"));
            if(recordedCan)runtime.now=time(integer(map(map(list(fixture.get("progress")).get(0)).get("vehicleRead")).get("journaledElapsedMs")));
            NativeMapSession created;
            try{created=NativeMapSession.class.getDeclaredConstructor(StockRuntime.class,AutoCloseable.class,NativeEnvironment.class,StockTransport.class,
                    java.util.function.LongSupplier.class,java.util.function.Consumer.class,LiveSessionLease.FrameMode.class,PublicRoadEvidence.class,
                    OwnerLaneEvidence.class,YandexNativeAdapter.Policy.class,java.util.function.Consumer.class)
                    .newInstance(runtime,runtime,environment,transport,(java.util.function.LongSupplier)()->path,(java.util.function.Consumer<Map<String,Object>>)v->{},
                        LiveSessionLease.FrameMode.WGS84,PublicRoadEvidence.EMPTY,OwnerLaneEvidence.EMPTY,policy,(java.util.function.Consumer<Map<String,Object>>)audits::add);}
            catch(NoSuchMethodException oldSource){if(vehicle)throw new AssertionError("CAN-source audit required");created=new NativeMapSession(runtime,runtime,environment,transport,()->path,v->{},LiveSessionLease.FrameMode.WGS84,PublicRoadEvidence.EMPTY,OwnerLaneEvidence.EMPTY,policy);}
            catch(ReflectiveOperationException error){throw new AssertionError(error);}
            session=created;environment.session=session;
            if(vehicle)transport.locationSpeedFresh=()->{try{return (Boolean)NativeMapSession.class.getDeclaredMethod("locationSpeedFresh",long.class).invoke(session,runtime.now);}
                catch(ReflectiveOperationException error){throw new AssertionError("final source-expiry guard required",error);}};
            session.start(true,true,true);session.clientInitialized(true);runtime.advance(0);
        }
        long time(long sourceElapsed){return 100000+sourceElapsed-firstCapture;}
        void can(float value,long startedAt){try{NativeMapSession.class.getDeclaredMethod("vehicleSpeedUpdated",float.class,long.class).invoke(session,value,startedAt);}
            catch(ReflectiveOperationException error){throw new AssertionError("source-clock CAN input required",error);}}
        NativeNavigationInput input(Map<String,Object> row,boolean first,boolean recordedCan){
            long at=time(integer(row.get("capturedUptimeMs")));
            if(recordedCan){Map<String,Object> read=map(row.get("vehicleRead"));long started=time(integer(read.get("startedAtElapsedMs"))),receipt=time(integer(read.get("journaledElapsedMs")));
                check("ok".equals(read.get("status"))&&started<=receipt&&receipt<=at&&at-started<=1500,"recorded CAN is valid and fresh by original source start");
                if(vehicleSequence!=integer(read.get("sequence"))){check(receipt>=runtime.now,"CAN delivery order is monotonic");runtime.advance(receipt-runtime.now);environment.speed=number(read.get("value"));can((float)environment.speed,started);vehicleSequence=integer(read.get("sequence"));}}
            runtime.advance(at-runtime.now);Map<String,Object> loc=map(row.get("location")),pos=map(row.get("sourcePolylinePosition"));
            NativeNavigationInput input=new NativeNavigationInput();input.routeId=source.routeId;input.routeEpoch=7;input.offlineGeometrySha256="recorded-village-geometry";
            input.monotonicMs=runtime.now;input.stamp=integer(loc.get("relativeTimestamp"));input.fixAgeMs=integer(row.get("lastFineFixAgeMs"));
            input.mode="guidance";input.routeStatus="ON_ROUTE";input.positionOnRoute=true;input.locationClass=(String)row.get("locationClass");
            input.scalar=number(pos.get("segmentIndex"))+number(pos.get("segmentPosition"));input.timeToFinishS=number(row.get("timeToFinishS"));
            input.lon=number(loc.get("longitude"));input.lat=number(loc.get("latitude"));input.rawLon=input.lon;input.rawLat=input.lat;
            input.speedMps=number(loc.get("speed"));input.heading=number(loc.get("heading"));input.speedLimitMps=number(row.get("speedLimitMps"));
            if(first)input.route=source;return input;
        }
        void observe(NativeNavigationInput input){session.observe(input);runtime.advance(0);}
    }
    static Map<String,Object> withoutGps(Map<String,Object> position){Map<String,Object> copy=new LinkedHashMap<>(position);copy.remove("GPSspeed");return copy;}
    static Map<String,Object> captured(Map<String,Object> fixture,boolean vehicle,boolean oldFlags,boolean recordedCan,Path transcript)throws Exception{return captured(fixture,vehicle,oldFlags,recordedCan,oldFlags,transcript);}
    static Map<String,Object> captured(Map<String,Object> fixture,boolean vehicle,boolean oldFlags,boolean recordedCan,boolean derivative,Path transcript)throws Exception{
        Rig rig=new Rig(fixture,vehicle,oldFlags,recordedCan,derivative);List<Object> rows=new ArrayList<>();int movingZero=0,movingZeroStillZero=0,canZero=0,lowRolling=0,positiveSdkChanged=0;
        for(int i=0;i<list(fixture.get("progress")).size();i++){
            Map<String,Object> row=map(list(fixture.get("progress")).get(i));NativeNavigationInput input=rig.input(row,i==0,recordedCan);int count=rig.transport.positions.size();rig.observe(input);
            check(rig.transport.positions.size()>count,"every recorded fresh fix reaches actual unchanged sender wire");Map<String,Object> wire=new LinkedHashMap<>(rig.transport.last());
            Map<String,Object> result=Json.object();result.put("capturedAtMs",row.get("capturedAtMs"));result.put("sdkStamp",input.stamp);result.put("sdkSpeedMps",input.speedMps);result.put("position",wire);
            if(recordedCan){Map<String,Object> read=map(row.get("vehicleRead"));double can=number(read.get("value"));long age=integer(row.get("capturedUptimeMs"))-integer(read.get("startedAtElapsedMs"));
                result.put("vehicleRead",read);result.put("vehicleSourceAgeMs",age);
                if(vehicle){check(scalar(wire,"GPSspeed")== (int)(float)can,"all-CAN mode selects recorded CAN on every fix, including positive SDK and zero CAN");check(scalar(wire,"vehicleSpeed")== (int)can,"independent factory realSpeed field remains separately read");}
                if(input.speedMps==0&&can>5.4){movingZero++;if(scalar(wire,"GPSspeed")==0)movingZeroStillZero++;}
                if(can==0){canZero++;check(scalar(wire,"GPSspeed")==0,"both recorded CAN stops stay exactly zero on wire");}
                if(input.speedMps==0&&can>0&&can<=5.4){lowRolling++;if(vehicle)check(scalar(wire,"GPSspeed")== (int)can,"recorded low rolling CAN1/1/5 is retained");}
                if(input.speedMps>=.3&&scalar(wire,"GPSspeed")!=(int)(float)(input.speedMps*3.6))positiveSdkChanged++;
                if(!rig.audits.isEmpty()){Map<String,Object> audit=rig.audits.get(rig.audits.size()-1);result.put("speedSource",audit.get("speedSource"));result.put("speedReason",audit.get("speedReason"));result.put("selectedSpeedMps",audit.get("selectedSpeedMps"));}
            }
            rows.add(result);
        }
        if(transcript!=null)Files.write(transcript,rig.transport.wire,StandardCharsets.UTF_8);
        Map<String,Object> report=Json.object();report.put("policy",vehicle?"experimental_CAN_primary_original_SDK_fallback":oldFlags?(derivative?"frozen_candidate28_policy":"candidate29_CAN_flag_off_original_SDK_control"):"baseline26_policy");report.put("observations",rows.size());report.put("rows",rows);
        report.put("routeWireSha256",hex(java.security.MessageDigest.getInstance("SHA-256").digest(rig.transport.route)));
        report.put("movingSdkZeroRows",movingZero);report.put("movingSdkZeroRowsStillZero",movingZeroStillZero);report.put("recordedCANZeroRows",canZero);report.put("recordedLowRollingSdkZeroRows",lowRolling);report.put("positiveSdkValuesChangedOnWire",positiveSdkChanged);
        if(vehicle){check(movingZero==6&&movingZeroStillZero==0,"all six independently moving SDK-zero samples now publish positive GPSspeed");check(canZero==2&&lowRolling==3,"real stop and low-rolling populations remain explicit");check(positiveSdkChanged>0,"all-CAN policy intentionally changes positive SDK measurements too");}
        rig.session.close();check(rig.runtime.tasks.isEmpty(),"session closure removes all native callback/timer work");return report;
    }
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    static void compareCaptured(Map<String,Object> before,Map<String,Object> after){
        check(before.get("routeWireSha256").equals(after.get("routeWireSha256")),"static factory route payload is byte-identical across speed-only policies");
        check(list(before.get("rows")).size()==45&&list(after.get("rows")).size()==45,"complete recorded45-fix input corpus remains");
        for(int i=0;i<45;i++){Map<String,Object> a=map(list(before.get("rows")).get(i)),b=map(list(after.get("rows")).get(i));
            check(a.get("sdkStamp").equals(b.get("sdkStamp")),"source identity is unchanged");
            check(withoutGps(map(a.get("position"))).equals(withoutGps(map(b.get("position")))),"every coordinate, route/progress field and independent vehicleSpeed is unchanged by CAN source selection");
            if(number(a.get("sdkSpeedMps"))>=.3)check(scalar(map(a.get("position")),"GPSspeed")== (int)(float)(number(a.get("sdkSpeedMps"))*3.6),"flag-off v2 behavior preserves positive SDK speed");
        }
    }
    static Map<String,Object> lastAudit(Rig rig){check(!rig.audits.isEmpty(),"motion decision audit exists");return rig.audits.get(rig.audits.size()-1);}
    static void sourceBoundaries(Map<String,Object> fixture){
        Map<String,Object> first=map(list(fixture.get("progress")).get(0));
        Object[][] cases={
            {"missing",null,0L,7d,25,"read_missing"},
            {"fresh",18f,0L,Double.NaN,18,"fresh_valid"},
            {"age1499",18f,1499L,7d,18,"fresh_valid"},
            {"age1500",18f,1500L,7d,18,"fresh_valid"},
            {"age1501",18f,1501L,7d,25,"source_stale"},
            {"future",18f,-1L,7d,25,"source_time_future"},
            {"negative",-1f,0L,7d,25,"speed_negative"},
            {"NaN",Float.NaN,0L,7d,25,"speed_non_finite"},
            {"infinite",Float.POSITIVE_INFINITY,0L,7d,25,"speed_non_finite"},
            {"outside_native_range",Float.MAX_VALUE,0L,7d,25,"speed_outside_native_range"},
            {"measured_stop",0f,0L,7d,0,"fresh_valid"},
            {"sdk_stop_without_CAN",null,0L,0d,0,"read_missing"}
        };
        for(Object[] c:cases){Rig rig=new Rig(fixture,true,true,false,false);NativeNavigationInput input=rig.input(first,true,false);input.speedMps=(Double)c[3];
            if(c[1]!=null)rig.can((Float)c[1],rig.runtime.now-(Long)c[2]);
            // Model the explicitly declared ProductNativeEnvironment source rule; the
            // integration harness separately verifies this supplier rather than treating
            // an invalid mock realSpeed as a recorded independent vehicle observation.
            rig.environment.speed="fresh_valid".equals(c[5])?number(c[1]):input.speedMps*3.6;
            rig.observe(input);check(scalar(rig.transport.last(),"GPSspeed")== (Integer)c[4],c[0]+": actual wire uses fresh CAN or exact original SDK fallback");
            Map<String,Object> audit=lastAudit(rig);check(c[5].equals(audit.get("vehicleSpeedInputReadReason")),c[0]+": original source validity is explicit");
            boolean fresh="fresh_valid".equals(c[5]);check((fresh?"autoservice_vehicle_speed":"yandex_sdk").equals(audit.get("speedSource")),c[0]+": audit selects CAN or SDK without derivative");
            check(audit.get("derivedSpeedMps")==null&&audit.get("derivedSource")==null,c[0]+": candidate29 never invokes displacement fallback");rig.session.close();
        }
        for(double sdk:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1}){Rig rig=new Rig(fixture,true,true,false,false);NativeNavigationInput input=rig.input(first,true,false);input.speedMps=sdk;rig.observe(input);
            check(rig.transport.positions.isEmpty(),"unavailable CAN plus invalid original SDK is inadmissible");rig.session.close();}
        Rig queued=new Rig(fixture,true,true,false,false);NativeNavigationInput input=queued.input(first,true,false);input.speedMps=7;queued.can(18,queued.runtime.now-1500);queued.session.observe(input);
        queued.runtime.now+=2;queued.runtime.advance(0);check(queued.transport.positions.isEmpty(),"callback delayed beyond selected CAN source expiry cannot invoke actual position publication");queued.session.close();
        Rig revoked=new Rig(fixture,true,true,false,false);input=revoked.input(first,true,false);input.speedMps=7;revoked.can(18,revoked.runtime.now);revoked.session.observe(input);revoked.can(Float.NaN,-1);revoked.runtime.advance(0);
        check(revoked.transport.positions.isEmpty(),"a failed latest read revokes earlier queued CAN work even while earlier value would be fresh");
        revoked.runtime.advance(1000);input=input.copy();input.route=null;input.monotonicMs=revoked.runtime.now;input.stamp+=1000;revoked.observe(input);
        check(scalar(revoked.transport.last(),"GPSspeed")==25&&"vehicle_unavailable_sdk_fallback".equals(lastAudit(revoked).get("speedReason")),"next valid SDK observation resumes exact SDK fallback after failed CAN read");revoked.session.close();
        Rig retained=new Rig(fixture,true,true,false,false);input=retained.input(first,true,false);input.speedMps=7;retained.environment.speed=0;retained.can(0,retained.runtime.now-1490);retained.observe(input);
        check(scalar(retained.transport.last(),"GPSspeed")==0,"selected measured CAN0 starts original stationary behavior");retained.runtime.advance(5);retained.environment.speed=36;retained.can(36,retained.runtime.now);int count=retained.transport.positions.size();retained.runtime.advance(1006);
        check(retained.transport.positions.size()==count,"newer positive global CAN cannot keep an older selected CAN0 stationary repeat alive after its own source expiry");retained.session.close();
        Rig stalled=new Rig(fixture,true,true,false,false);input=stalled.input(first,true,false);input.speedMps=7;stalled.environment.speed=0;stalled.can(0,stalled.runtime.now);stalled.observe(input);
        int before=stalled.transport.positions.size();stalled.runtime.now+=2000;stalled.environment.speed=36;stalled.can(36,stalled.runtime.now);
        NativeNavigationInput newest=input.copy();newest.route=null;newest.monotonicMs=stalled.runtime.now;newest.stamp+=2000;stalled.session.observe(newest);stalled.runtime.advance(0);
        check(stalled.transport.blockedLocations>0,"stalled original callback actually attempts egress and is rejected by selected-source expiry gate");
        for(int i=before;i<stalled.transport.positions.size();i++)check(scalar(stalled.transport.positions.get(i),"GPSspeed")!=0,"overdue stationary callback cannot publish an expired selected CAN0 before a later due expiry fence");stalled.session.close();
    }
    public static void main(String[] args)throws Exception{
        if(args.length<1)throw new IllegalArgumentException("fixture path required");Map<String,Object> fixture=map(Json.parse(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8)));
        if(args.length==3&&"--wire-baseline28".equals(args[1])){captured(fixture,false,true,true,Paths.get(args[2]));return;}
        if(args.length==3&&"--wire-baseline26".equals(args[1])){captured(fixture,false,false,false,Paths.get(args[2]));return;}
        Map<String,Object> before=captured(fixture,false,true,true,null),control=captured(fixture,false,true,true,false,null),after=captured(fixture,true,true,true,false,null);compareCaptured(before,after);compareCaptured(control,after);sourceBoundaries(fixture);
        Map<String,Object> report=Json.object();report.put("scope","experimental all-CAN source on recorded SDK/CAN input; not factory fusion or NOA acceptance");report.put("before",before);report.put("flagOffControl",control);report.put("after",after);
        if(args.length==3&&"--report".equals(args[1]))Files.write(Paths.get(args[2]),Collections.singletonList(Json.write(report)),StandardCharsets.UTF_8);
        System.out.println("Build29CapturedReplayTest: "+checks+" checks passed;45 CAN-selected positions,6/6 moving SDK zeros eliminated,2 CAN stops retained (experimental source policy)");
    }
}
