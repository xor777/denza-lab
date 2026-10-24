package dev.denza.adaslink;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Lossless snapshot traffic context, with explicit factory-default wire decision. */
public final class YandexTrafficContextTest {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static Map<String,Object> item(String type,Double speed){Map<String,Object> m=Json.object();m.put("jamType",type);m.put("speed",speed);return m;}
    static Map<String,Object> snapshot(Object... items){Map<String,Object> s=Json.object();s.put("jamSegments",Arrays.asList(items));
        Map<String,Object> units=Json.object();units.put("speed","m/s");s.put("units",units);s.put("conditionsOutdated",false);return s;}
    static void validation(){
        Map<String,Object> source=snapshot(item("FREE",12.3),item("BLOCKED",0.0));
        YandexTrafficContext c=YandexTrafficContext.fromSnapshot(source,2);
        check(c.segments.size()==2 && c.segments.get(0).rawSpeed==12.3,"stored exact raw speed retained");
        Map<String,Object> zero=c.at("route",1.2,true);
        check(Boolean.TRUE.equals(zero.get("speedKnown"))&&((Number)zero.get("rawSpeed")).doubleValue()==0,"observed traffic zero differs from unknown");
        check("BLOCKED".equals(zero.get("rawJamType")),"raw blocked label preserved without Amap recoding");
        check(((Number)c.at("route",2,true).get("sourceSegmentIndex")).intValue()==1,"endpoint belongs to final source edge");
        for(double scalar:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,3})
            check(Boolean.FALSE.equals(c.at("route",scalar,true).get("speedKnown")),"invalid scalar remains unknown");
        check(c.at("route",0,false).get("rawSpeed")==null,"off-route context clears previous value");
        YandexTrafficContext bad=YandexTrafficContext.fromSnapshot(snapshot(item("HARD",Double.POSITIVE_INFINITY),item("UNKNOWN",-1.0)),2);
        check(bad.segments.get(0).rawSpeed==null&&bad.segments.get(1).rawSpeed==null,"nonfinite and negative raw speeds unknown");
        check("invalid_nonfinite".equals(bad.at("r",0,true).get("speedStatus")),"nonfinite rejection reason explicit");
        check("invalid_negative".equals(bad.at("r",1,true).get("speedStatus")),"negative rejection reason explicit");
        check("UNKNOWN".equals(bad.segments.get(1).rawJamType),"UNKNOWN stays literal source enum");
        Map<String,Object> malformed=Json.object();malformed.put("jamType",3L);malformed.put("speed","fast");
        YandexTrafficContext wrongType=YandexTrafficContext.fromSnapshot(snapshot(malformed),1);
        check("type_error".equals(wrongType.at("r",0,true).get("speedStatus"))&&
                "type_error".equals(wrongType.at("r",0,true).get("jamTypeStatus")),"per-field wrong-type reasons explicit");
        YandexTrafficContext nullSpeed=YandexTrafficContext.fromSnapshot(snapshot(item("UNKNOWN",null)),1);
        check("source_null".equals(nullSpeed.at("r",0,true).get("speedStatus")),"null source remains distinguishable from invalid value");
        check(YandexTrafficContext.fromSnapshot(source,3).unavailableReason!=null,"misaligned traffic array is unavailable");
        Map<String,Object> absent=Json.object();
        check(YandexTrafficContext.fromSnapshot(absent,2).segments.isEmpty(),"absent array not fabricated");
        Map<String,Object> availability=Json.object();availability.put("jamSegments","unknown");source.put("availability",availability);
        check(YandexTrafficContext.fromSnapshot(source,2).segments.isEmpty(),"unknown array availability respected");
        check(YandexTrafficContext.fromSnapshot(snapshot((Object)null),1).at("r",0,true).get("rawSpeed")==null,"null entry remains unknown");
        Map<String,Object> old=snapshot(item("FREE",12.3));old.put("capturedAtMs",1234L);
        YandexTrafficContext original=YandexTrafficContext.fromSnapshot(old,1);
        check(original.at("r",0,true).get("snapshotCapturedAtMs").equals(1234L),"original source timestamp retained");
        check(Boolean.FALSE.equals(original.at("r",0,true).get("sourceAgeKnown")),"applying a captured source does not establish current age");
        check(!original.at("r",0,true).containsKey("modelObservedAtMonotonicMs"),"apply time never impersonates source time");
    }
    @SuppressWarnings("unchecked") static void recordedAndDriver(File root)throws Exception{
        File files=new File(root,"captures/yandex-drive-review-20261024/unpacked/files/denza-drive-evidence");
        YandexRoute route=null;
        for(String name:new String[]{"route-1.json","route-2.json"}){
            Map<String,Object> raw=(Map<String,Object>)Json.parse(new String(Files.readAllBytes(new File(files,name).toPath()),StandardCharsets.UTF_8));
            route=YandexRoute.fromSnapshot(raw);List<Object> segments=(List<Object>)raw.get("jamSegments");
            check(route.trafficContext.segments.size()==route.edges(),"real route traffic aligns with source edges: "+name);
            for(int i=0;i<segments.size();i++){
                Map<String,Object> segment=(Map<String,Object>)segments.get(i);YandexTrafficContext.Segment copied=route.trafficContext.segments.get(i);
                check(segment.get("jamType").equals(copied.rawJamType),"real raw jam type retained");
                check(((Number)segment.get("speed")).doubleValue()==copied.rawSpeed,"real raw traffic speed retained without conversion");
            }
        }
        ManualScheduler clock=new ManualScheduler(100_000);DryRunRecorder recorder=new DryRunRecorder(null,true);
        StockFeed.Config config=new StockFeed.Config();config.frame=Coordinates.Frame.WGS84;config.roadSpeedLimit=true;
        StockFeed feed=new StockFeed(clock,recorder,config);feed.start(true,true,true);clock.drain();
        NavigatorDriver driver=new NavigatorDriver(feed,config.frame,clock);
        NavigatorDriver.Observation o=new NavigatorDriver.Observation();o.monotonicMs=101_000;o.route=route;o.routeId=route.routeId;
        o.mode="guidance";o.positionOnRoute=true;o.scalar=0.2;o.locationClass="FINE";o.stamp=1;o.fixAgeMs=20;
        o.lon=route.lon[0];o.lat=route.lat[0];o.speedMps=10;clock.advanceTo(o.monotonicMs);driver.observe(o);clock.drain();
        Map<String,Object> note=lastNote(recorder);
        check(((Number)note.get("sourceSegmentIndex")).intValue()==0,"driver selects current source edge");
        check(route.trafficContext.segments.get(0).rawSpeed.equals(note.get("rawSpeed")),"driver preserves raw captured traffic input");
        check(YandexTrafficContext.WIRE_DECISION.equals(note.get("wireDecision")),"wire decision is explicit");
        check(Boolean.TRUE.equals(note.get("snapshotContext")),"snapshot not represented as fresh live traffic");
        Object originalStamp=note.get("snapshotCapturedAtMs");
        o.monotonicMs=101_500;clock.advanceTo(o.monotonicMs);driver.observe(o);clock.drain(); // Same route reapplied as on graph refresh.
        note=lastNote(recorder);
        check(originalStamp.equals(note.get("snapshotCapturedAtMs")),"graph refresh preserves original source time");
        check(Boolean.FALSE.equals(note.get("sourceAgeKnown"))&&!note.containsKey("modelObservedAtMonotonicMs"),"graph refresh cannot invent fresh source age");
        o.route=null;o.monotonicMs=102_000;o.stamp=2;clock.advanceTo(o.monotonicMs);driver.observe(o);clock.drain();
        boolean sent=false;
        for(Map<String,Object> row:recorder.records())if("fire".equals(row.get("kind"))&&((Number)row.get("topic")).longValue()==Topics.SD_SPEED_INTERSECTION){
            Map<String,Object> decoded=(Map<String,Object>)row.get("decoded");Map<String,Object> message=(Map<String,Object>)decoded.values().iterator().next();
            check(!message.containsKey("trafficFlowSpeed")||((Number)message.get("trafficFlowSpeed")).longValue()==0,"factory flow wire default retained");sent=true;
        }
        check(sent,"actual speedIntersection sender exercised");
        o=new NavigatorDriver.Observation();o.monotonicMs=103_000;clock.advanceTo(o.monotonicMs);driver.observe(o);clock.drain();
        note=lastNote(recorder);check(Boolean.FALSE.equals(note.get("speedKnown"))&&note.get("rawSpeed")==null,"route disappearance clears diagnostic context");
    }
    static Map<String,Object> lastNote(DryRunRecorder recorder){Map<String,Object> last=null;
        for(Map<String,Object> row:recorder.records())if("trafficContext".equals(row.get("input")))last=row;
        check(last!=null,"traffic context emitted");return last;}
    public static void main(String[] args)throws Exception{validation();recordedAndDriver(new File(args.length==0?".":args[0]));
        System.out.println("YandexTrafficContextTest: "+checks+" checks passed");}
}
