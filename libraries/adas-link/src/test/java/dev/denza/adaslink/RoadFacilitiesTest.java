package dev.denza.adaslink;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public final class RoadFacilitiesTest {
    static int checks;
    static void check(boolean value,String reason) {checks++;if(!value)throw new AssertionError(reason);}
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object v){return (Map<String,Object>)v;}
    static double number(Map<String,Object> m,String key){Object v=m.get(key);return v instanceof Number?((Number)v).doubleValue():0;}
    static Map<String,Object> read(Path p)throws Exception{return object(Json.parse(new String(Files.readAllBytes(p),StandardCharsets.UTF_8)));}
    @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception {
        Path root=Paths.get(args[0]);Path base=root.resolve("captures/yandex-followup-20261024");
        Map<String,Object> proof=read(base.resolve("facilities-proof.json"));
        List<Object> goldens=(List<Object>)object(proof.get("beijing")).get("rawPacketGoldens");
        check(Topics.SD_ROAD_FACILITIES==1268847189590021L,"factory topic");
        for(Object value:goldens) {
            Map<String,Object> row=object(value),f=object(row.get("fields"));
            byte[] encoded=StockMessages.roadFacilities((int)number(f,"naviFacilityType"),(int)number(f,"distance"),number(f,"boardSignLocationLon"),number(f,"boardSignLocationLat"));
            check(Pb.hex(encoded).equals(row.get("payloadHex")),"exact factory bytes "+row.get("run")+":"+row.get("sourceLine"));
            Map<String,Object> decoded=object(Topics.schema(Topics.SD_ROAD_FACILITIES).decode(encoded).get("roadFacilities"));
            check(decoded.keySet().equals(f.keySet()),"factory zero omission and reserved-field absence");
        }
        Map<String,Object> fixtures=read(base.resolve("facilities-fixtures.json"));
        Map<String,Object> s=object(fixtures.get("syntheticRoute"));
        RouteConverter.Converted c=RouteConverter.convert(YandexRoute.fromSnapshot(s),Coordinates.Frame.WGS84);
        RoadFacilities.View view=c.roadFacilities;
        check(view.items.size()==6,"five named families, six occurrences");
        int[] types={31,8,8,91,17,30};
        for(int i=0;i<types.length;i++) {
            RoadFacilities.Placed p=view.items.get(i);
            check(p.source.type==types[i],"source type "+i);
            check(p.source.scalar==i+1,"position "+i);
            check(Math.abs(p.lon-37.25)<1e-10&&Math.abs(p.lat-(55.501+i*.001))<1e-10,"route interpolation "+i);
            check(view.next(p.metres,600)==p,"object exactly at progress stays visible "+i);
            check(view.next(p.metres+.01,600)!=p,"passed object clears "+i);
        }
        check(view.next(0,view.items.get(0).metres-.01)==null,"lookahead boundary");
        check(view.next(Double.NaN,600)==null&&view.next(-1,600)==null,"invalid progress");
        check(view.next(c.totalM+1,600)==null,"after destination");
        check(view.source.items.stream().filter(i->!i.mapped()).count()==3,"bump/ford/rugged retained without guessed types");
        RoadFacilities.Item bump=view.source.items.stream().filter(i->i.field.equals("speedBumps")).findFirst().get();
        check(bump.raw.equals(((List<?>)s.get("speedBumps")).get(0))&&bump.decision.contains("unproved"),"raw + decision for unsupported bump");
        RoadFacilities.Item multi=view.items.get(4).source;
        check(object(multi.raw).get("tags").equals(Arrays.asList("SCHOOL","SPEED_CONTROL")),"multi tags remain raw");

        Map<String,Object> broken=new LinkedHashMap<>(s);
        Map<String,Object> fractional=Json.object();fractional.put("segmentIndex",1.5);fractional.put("segmentPosition",0);
        Map<String,Object> malformed=Json.object();malformed.put("position",fractional);
        List<Object> crossings=new ArrayList<>((List<?>)s.get("pedestrianCrossings"));crossings.add(malformed);crossings.add(null);
        broken.put("pedestrianCrossings",crossings);
        RoadFacilities.Source recovered=RoadFacilities.readSource(broken,6);
        check(recovered.items.stream().filter(RoadFacilities.Item::mapped).count()==6,"malformed optional row never deletes valid neighbours");
        check(recovered.issues.size()==1&&recovered.items.size()==11,"malformed rows retained, invalid numeric index reported");
        Map<String,Object> future=Json.object();future.put("position",LaneGuidanceTest.pos(2));future.put("type","FUTURE_SUBTYPE");
        broken.put("railwayCrossings",Arrays.asList(future));
        recovered=RoadFacilities.readSource(broken,6);
        check(recovered.items.stream().anyMatch(i->i.field.equals("railwayCrossings")&&!i.mapped()&&i.raw==future),"future enum explicit raw-only decision");
        Map<String,Object> empty=Json.object();empty.put("pedestrianCrossings",Collections.emptyList());
        recovered=RoadFacilities.readSource(empty,6);
        check(recovered.availability.get("pedestrianCrossings").equals("known_empty")&&recovered.availability.get("railwayCrossings").equals("unavailable"),"absence differs from known empty");
        Map<String,Object> availability=Json.object(),issues=Json.object();availability.put("events","partial");availability.put("events[0].tags","unknown");
        Map<String,Object> failure=Json.object();failure.put("kind","missing_getter");failure.put("exception","java.lang.IllegalArgumentException");
        issues.put("events[0].tags",failure);broken.put("availability",availability);broken.put("issues",issues);
        recovered=RoadFacilities.readSource(broken,6);
        check(recovered.sourceAvailability.equals(availability)&&recovered.sourceIssues.equals(issues),"exact per-field partial provenance preserved separately from projection");
        check(Pb.hex(StockMessages.roadFacilities(0,0,0,0)).equals("0a00"),"factory clear");
        lifecycle(s);
        offRouteLifecycle(s);
        System.out.println(checks+" road facilities assertions passed");
    }
    static final class RetryPublisher implements Publisher {
        final DryRunRecorder recorder=new DryRunRecorder(null,true);boolean fail=true;int attempts;
        public BridgeStatus.Mode mode(){return BridgeStatus.Mode.DRY_RUN;}
        public int startService(long topic){return recorder.startService(topic);}
        public int fire(long topic,byte[] bytes,long at) {
            if(topic==Topics.SD_ROAD_FACILITIES) {attempts++;if(fail){fail=false;return -1;}}
            return recorder.fire(topic,bytes,at);
        }
        public void note(String kind,Map<String,Object> value,long at){recorder.note(kind,value,at);}
    }
    static void fix(StockFeed feed,ManualScheduler clock,double latitude) {
        feed.onLocation(new StockFeed.Fix(37.25,latitude,Double.NaN,Double.NaN,5,0,false,0,null));clock.drain();
    }
    static void lifecycle(Map<String,Object> input) {
        ManualScheduler clock=new ManualScheduler(10000);RetryPublisher publisher=new RetryPublisher();
        StockFeed.Config config=new StockFeed.Config();config.frame=Coordinates.Frame.WGS84;config.roadFacilities=true;
        StockFeed feed=new StockFeed(clock,publisher,config);feed.start(true,true,true);feed.onNavStart();clock.drain();
        RouteConverter.Converted c=RouteConverter.convert(YandexRoute.fromSnapshot(input),Coordinates.Frame.WGS84);
        feed.onRouteResult(c,0);clock.drain();feed.onProgress(new StockFeed.Progress(0,100,Double.NaN));clock.drain();
        fix(feed,clock,55.5);check(publisher.attempts==1,"first object attempted");
        check(LaneGuidanceTest.fires(publisher.recorder,Topics.SD_ROAD_FACILITIES).isEmpty(),"failed send not cached as delivered");
        clock.advanceTo(11000);fix(feed,clock,55.5);
        check(publisher.attempts==2,"failed object retries on fresh fix");
        List<Map<String,Object>> sent=LaneGuidanceTest.fires(publisher.recorder,Topics.SD_ROAD_FACILITIES);
        Map<String,Object> body=object(object(sent.get(0).get("decoded")).get("roadFacilities"));
        check(((Number)body.get("naviFacilityType")).intValue()==31,"nearest mapped object enters actual feed");
        check(((Number)body.get("distance")).intValue()==(int)Coordinates.distance(37.25,55.5,37.25,55.501),"wire distance floors straight-line metres");
        clock.advanceTo(12000);fix(feed,clock,55.5);check(publisher.attempts==2,"unchanged object and distance do not spam");
        long path=feed.publishedPathId();Map<String,Object> empty=new LinkedHashMap<>(input);
        for(String field:new String[]{"pedestrianCrossings","railwayCrossings","checkpoints","events"})empty.put(field,Collections.emptyList());
        check(feed.refreshConditions(RouteConverter.convert(YandexRoute.fromSnapshot(empty),Coordinates.Frame.WGS84)),"source disappearance refreshes same table");
        clock.advanceTo(13000);fix(feed,clock,55.5);
        sent=LaneGuidanceTest.fires(publisher.recorder,Topics.SD_ROAD_FACILITIES);
        check(sent.get(sent.size()-1).get("payloadHex").equals("0a00"),"disappearance sends exact factory clear");
        check(feed.publishedPathId()==path,"facility update preserves wire path");
        clock.advanceTo(14000);fix(feed,clock,55.5);check(publisher.attempts==3,"known empty clears only once");
        feed.onNavStop();clock.drain();int attempts=publisher.attempts;
        clock.advanceTo(15000);fix(feed,clock,55.5);check(publisher.attempts==attempts,"no objects after navigation stops");
        feed.close();
    }

    static String lastFacility(RetryPublisher publisher) {
        List<Map<String,Object>> rows=LaneGuidanceTest.fires(publisher.recorder,Topics.SD_ROAD_FACILITIES);
        return rows.isEmpty()?"absent":String.valueOf(rows.get(rows.size()-1).get("payloadHex"));
    }
    static void offRouteLifecycle(Map<String,Object> input) {
        ManualScheduler clock=new ManualScheduler(20000);RetryPublisher publisher=new RetryPublisher();publisher.fail=false;
        StockFeed.Config config=new StockFeed.Config();config.frame=Coordinates.Frame.WGS84;config.roadFacilities=true;
        StockFeed feed=new StockFeed(clock,publisher,config);feed.start(true,true,true);feed.onNavStart();clock.drain();
        feed.onRouteResult(RouteConverter.convert(YandexRoute.fromSnapshot(input),config.frame),0);clock.drain();
        feed.onProgress(new StockFeed.Progress(0,100,Double.NaN));clock.drain();fix(feed,clock,55.5);
        check(!lastFacility(publisher).equals("0a00"),"test starts with actual displayed route facility");
        clock.advanceTo(21000);
        // This queued progress belongs to the state before route loss. It must not reopen facilities.
        feed.onProgress(new StockFeed.Progress(.1,99,Double.NaN));
        feed.onReroute(2);clock.drain();fix(feed,clock,55.5001);
        String first=lastFacility(publisher);int attempts=publisher.attempts;
        clock.advanceTo(22000);fix(feed,clock,55.5002);String held=lastFacility(publisher);
        System.out.println("offRoute first="+first+" second="+held+" attempts="+attempts+"->"+publisher.attempts);
        check(first.equals("0a00"),"route loss clears old facility despite queued pre-loss progress");
        check(held.equals("0a00")&&publisher.attempts==attempts,"second off-route FINE fix cannot resurrect held-route facility");
        clock.advanceTo(23000);feed.onProgress(new StockFeed.Progress(.2,98,Double.NaN));clock.drain();fix(feed,clock,55.5002);
        check(!lastFacility(publisher).equals("0a00"),"fresh valid route progress explicitly restores facility eligibility");
        clock.advanceTo(24000);publisher.fail=true;feed.onReroute(2);clock.drain();fix(feed,clock,55.5003);
        check(!lastFacility(publisher).equals("0a00"),"failed clear is not cached as delivery");
        int failed=publisher.attempts;clock.advanceTo(25000);fix(feed,clock,55.5004);
        check(lastFacility(publisher).equals("0a00")&&publisher.attempts==failed+1,"clear retries on next fresh fix without restoring stale facility");
        clock.advanceTo(26000);fix(feed,clock,55.5005);check(publisher.attempts==failed+1,"successful clear remains deduplicated during continued route loss");
        feed.close();
    }
}
