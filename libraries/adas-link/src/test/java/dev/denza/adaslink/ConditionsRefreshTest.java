package dev.denza.adaslink;

import java.util.*;

/** Same-source-route conditions, immutable wire table, real disappearance and queued reroute races. */
public final class ConditionsRefreshTest {
    private static int checks;
    private static void check(boolean value,String reason) { checks++;if(!value)throw new AssertionError(reason); }
    private static Map<String,Object> map(Object... pairs) {
        Map<String,Object> m=Json.object();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;
    }
    private static Map<String,Object> snapshot(String id,long epoch,long revision,double limit,int time,int free,String highlight,boolean camera) {
        Map<String,Object> s=LaneGuidanceTest.snapshot(new ArrayList<Object>(),.001);s.put("routeId",id);
        s.put("laneSigns",highlight==null?Collections.emptyList():Arrays.asList(LaneGuidanceTest.sign(4,
                LaneGuidanceTest.lane("PLAIN_LANE",highlight,"LEFT90","STRAIGHT_AHEAD"))));
        // This actual stable junction keeps the table cut when a point-scoped lane sign disappears.
        s.put("restrictedTurns",Arrays.asList(map("position",LaneGuidanceTest.pos(4))));
        s.put("speedLimits",Collections.nCopies(12,limit>0?limit/3.6:null));
        s.put("sections",Arrays.asList(map("geometry",map("begin",LaneGuidanceTest.pos(0),"end",LaneGuidanceTest.pos(12)),
                "metadata",map("annotation",map("action","STRAIGHT","toponym","Observed road"),"weight",map(
                "distance",map("value",1000),"time",map("value",free>0?3600.0/free:Double.NaN),"timeWithTraffic",map("value",time))))));
        s.put("events",camera?Arrays.asList(map("polylinePosition",LaneGuidanceTest.pos(5),"tags",Arrays.asList("SPEED_CONTROL"),"speedLimit",60/3.6)):Collections.emptyList());
        List<Object> jams=new ArrayList<>();for(int i=0;i<12;i++)jams.add(map("speed",revision+0.5,"jamType",revision%2==0?"SLOW":"FREE"));
        s.put("jamSegments",jams);s.put("conditionsOutdated",revision==2);
        s.put("conditionsCapture",map("routeGeneration",epoch,"revision",revision,"completedAtElapsedMs",revision*1000,"sourceUpdatedAtKnown",false));
        s.put("capturedAtMs",100000+revision*1000);return s;
    }
    private static final class Rig {
        final ManualScheduler clock=new ManualScheduler(100000);
        final DryRunRecorder rec=new DryRunRecorder(null,true);
        final StockFeed feed;
        final NavigatorDriver driver;
        long stamp;
        Rig() { StockFeed.Config c=new StockFeed.Config();c.frame=Coordinates.Frame.WGS84;c.roadSpeedLimit=true;
            feed=new StockFeed(clock,rec,c);driver=new NavigatorDriver(feed,c.frame,clock);feed.start(true,true,true);clock.drain(); }
        NavigatorDriver.Observation observation(YandexRoute route,long epoch,boolean fine) {
            NavigatorDriver.Observation o=new NavigatorDriver.Observation();o.routeEpoch=epoch;o.route=route;o.routeId=route.routeId;
            o.monotonicMs=clock.now();o.mode="guidance";o.positionOnRoute=true;o.scalar=3;o.timeToFinishS=90;
            if(fine) { o.locationClass="FINE";o.stamp=++stamp;o.fixAgeMs=10;o.lon=37.003;o.lat=55;o.speedMps=10; }
            return o;
        }
        void read(Map<String,Object> s) { clock.advanceTo(clock.now()+1000);YandexRoute r=YandexRoute.fromSnapshot(s);
            driver.observe(observation(r,r.captureGeneration,true));clock.drain(); }
        int count(long topic) { return LaneGuidanceTest.fires(rec,topic).size(); }
        Map<String,Object> last(long topic) {List<Map<String,Object>> rows=LaneGuidanceTest.fires(rec,topic);check(!rows.isEmpty(),"wire topic exists "+topic);
            Map<?,?> outer=(Map<?,?>)rows.get(rows.size()-1).get("decoded");return cast(outer.values().iterator().next()); }
        Map<String,Object> note(String kind) {Map<String,Object> last=null;for(Map<String,Object> r:rec.records())if(kind.equals(r.get("input")))last=r;
            check(last!=null,"diagnostic exists "+kind);return last;}
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> cast(Object o) {return (Map<String,Object>)o;}
    private static long number(Map<String,Object> m,String key) {Object n=m.get(key);return n instanceof Number?((Number)n).longValue():0;}
    private static void transitions() {
        Rig r=new Rig();Map<String,Object> a=snapshot("same",1,1,40,100,36,"LEFT90",true);
        r.read(a);r.read(a);long path=r.feed.publishedPathId();int routes=r.count(Topics.ROUTE),selections=r.count(Topics.PATH_SELECTED);
        long timestamp=number(r.last(Topics.SD_NEXT_LANES),"timestamp");
        check(number(r.last(Topics.SD_ACTION_CAMERA),"cameraType")==7,"initial camera actually shown");
        Map<String,Object> b=snapshot("same",1,2,60,120,72,"STRAIGHT_AHEAD",false);r.read(b);
        check(r.feed.publishedPathId()==path && r.count(Topics.ROUTE)==routes && r.count(Topics.PATH_SELECTED)==selections,"all dynamic changes keep wire path and selection");
        check("conditions_only".equals(r.note("conditionsSnapshot").get("decision")),"dynamic revision classified explicitly");
        check(number(r.last(Topics.TRAFFIC_INFO),"spdLmtSpeedValue")==60,"new route edge legal limit reaches wire");
        check(number(r.last(Topics.SD_SPEED_INTERSECTION),"historySpeed")==72,"freeflow refresh reaches actual feed view");
        check(number(r.last(Topics.SD_ACTION_CAMERA),"cameraType")==0,"removed camera explicitly clears active output");
        check("00".equals(r.last(Topics.SD_NEXT_LANES).get("frontLane")),"new highlight reaches active feed");
        long changed=number(r.last(Topics.SD_NEXT_LANES),"timestamp");check(changed>timestamp,"actual sign change latches new timestamp");
        check(Boolean.TRUE.equals(r.note("trafficContext").get("conditionsOutdated")),"SDK outdated flag remains visible");
        check(Boolean.FALSE.equals(r.note("conditionsSnapshot").get("sourceAgeKnown")),"capture never invents source age");
        // Same values with another capture revision, wall time and elapsed time.
        Map<String,Object> equal=new LinkedHashMap<>(b);equal.put("conditionsCapture",map("routeGeneration",1L,"revision",3L,"completedAtElapsedMs",9000L));equal.put("capturedAtMs",109000L);
        r.read(equal);check("unchanged".equals(r.note("conditionsSnapshot").get("decision")),"unchanged values skip conversion and timer restart");
        check(number(r.last(Topics.SD_NEXT_LANES),"timestamp")==changed,"same sign timestamp survives recapture");
        // Same lane output survives a graph-style fresh Converted object as well.
        RouteConverter.Converted view=RouteConverter.convert(YandexRoute.fromSnapshot(equal),Coordinates.Frame.WGS84);
        check(r.feed.refreshConditions(view),"compatible conditions refresh accepted");r.clock.advanceTo(r.clock.now()+1000);
        r.feed.onProgress(new StockFeed.Progress(3,20,Double.NaN));r.clock.drain();
        check(number(r.last(Topics.SD_NEXT_LANES),"timestamp")==changed,"same sign timestamp survives new wrapper/view");
        Map<String,Object> gone=snapshot("same",1,4,0,120,72,null,false);gone.put("jamSegments",Collections.nCopies(12,null));
        r.read(gone);
        check(r.feed.publishedPathId()==path&&r.count(Topics.ROUTE)==routes,"point conditions disappear without changing stable link table");
        check(number(r.last(Topics.TRAFFIC_INFO),"spdLmtSpeedValue")==0,"missing legal limit clears previous wire value");
        check(!r.last(Topics.SD_NEXT_LANES).containsKey("frontLane"),"removed sign sends explicit empty reset");
        check(!Boolean.TRUE.equals(r.note("trafficContext").get("speedKnown")),"removed jam data cannot retain old speed");
        // A lower revision cannot resurrect the sign or the limit.
        r.read(a);check(number(r.last(Topics.TRAFFIC_INFO),"spdLmtSpeedValue")==0,"late lower same-route revision rejected");
        check(!r.last(Topics.SD_NEXT_LANES).containsKey("frontLane"),"late snapshot cannot resurrect removed lanes");
        r.feed.close();
    }
    private static void immutableTable() {
        ManualScheduler clock=new ManualScheduler(100000);DryRunRecorder rec=new DryRunRecorder(null,true);
        StockFeed feed=new StockFeed(clock,rec,new StockFeed.Config());feed.start(true,true,true);feed.onNavStart();clock.drain();
        RouteConverter.Converted initial=RouteConverter.convert(YandexRoute.fromSnapshot(snapshot("immutable",1,1,40,100,36,"LEFT90",true)),Coordinates.Frame.WGS84);
        StockFeed.Route path=feed.onRouteResult(initial,1);clock.drain();
        RouteConverter.Converted update=RouteConverter.convert(YandexRoute.fromSnapshot(snapshot("immutable",1,2,60,200,72,"LEFT90",false)),Coordinates.Frame.WGS84);
        check(feed.refreshConditions(update),"travel-time difference remains a compatible condition update");
        check(path.converted.path.segments.get(0).travelTime==100,"published route travel time remains original immutable table");
        check(path.conditions.path.segments.get(0).travelTime==200,"runtime annotation view receives new section time");
        check(path.converted==initial && path.conditions==update,"published and conditions model ownership separated");
        RouteConverter.Converted foreign=RouteConverter.convert(YandexRoute.fromSnapshot(snapshot("foreign",2,1,60,200,72,"LEFT90",false)),Coordinates.Frame.WGS84);
        check(!feed.refreshConditions(foreign)&&path.conditions==update,"foreign route refresh rejected without mutation");feed.close();
    }
    private static void optionalItems() {
        Map<String,Object> s=snapshot("optional",1,1,40,100,36,null,false);
        s.put("events",Arrays.asList(null,map("polylinePosition",LaneGuidanceTest.pos(999),"tags",Arrays.asList("SPEED_CONTROL")),
                map("polylinePosition",LaneGuidanceTest.pos(5),"tags",Arrays.asList("SCHOOL"))));
        s.put("restrictedTurns",Arrays.asList(null,map("position",LaneGuidanceTest.pos(999))));
        s.put("pedestrianCrossings",Arrays.asList(map("position",LaneGuidanceTest.pos(4))));
        YandexRoute route=YandexRoute.fromSnapshot(s);RouteConverter.Converted converted=RouteConverter.convert(route,Coordinates.Frame.WGS84);
        check(converted.totalM>0,"malformed optional item cannot poison required geometry/conversion");
        check(route.optionalParseIssues.size()==4,"null/out-of-range optional items diagnosed individually");
        check(route.roadFacilitiesSource.items.size()==6,"facilities retain every raw optional item including malformed ones");
        int mapped=0;for(RoadFacilities.Item item:route.roadFacilitiesSource.items)if(item.mapped())mapped++;
        check(mapped==2,"valid SCHOOL and crossing survive adjacent malformed optional objects");
    }
    private static void identityAndRace() {
        Rig r=new Rig();Map<String,Object> a=snapshot("a",10,1,40,100,36,"LEFT90",true);r.read(a);long old=r.feed.publishedPathId();
        Map<String,Object> changed=snapshot("a",10,2,40,100,36,"LEFT90",true);
        List<Object> points=new ArrayList<>((List<?>)changed.get("points"));points.set(6,Arrays.asList(37.0062,55.0002));changed.put("points",points);
        r.read(changed);check(r.feed.publishedPathId()==old,"a changed table under the same Yandex route ID keeps the published path (new path only on a new route)");
        int routes=r.count(Topics.ROUTE);long path=r.feed.publishedPathId();
        // Queue an old route's NaviInfo, then replace route before the scheduler runs it.
        int before=r.count(Topics.SD_NEXT_LANES);
        r.feed.onProgress(new StockFeed.Progress(3,20,Double.NaN));
        Map<String,Object> other=snapshot("b",11,1,70,100,50,"STRAIGHT_AHEAD",false);
        List<Object> otherPoints=new ArrayList<>((List<?>)other.get("points"));otherPoints.set(6,Arrays.asList(37.0062,55.0002));other.put("points",otherPoints);
        YandexRoute b=YandexRoute.fromSnapshot(other);
        r.driver.observe(r.observation(b,11,false));r.clock.drain();long next=r.feed.publishedPathId();
        check(next!=path&&r.count(Topics.ROUTE)>routes,"a new Yandex route starts its own table");
        check(r.count(Topics.SD_NEXT_LANES)==before,"queued prior-route NaviInfo cannot publish stale lanes after replacement");
        int laneCount=r.count(Topics.SD_NEXT_LANES);
        NavigatorDriver.Observation late=r.observation(YandexRoute.fromSnapshot(a),10,true);r.driver.observe(late);r.clock.drain();
        check(r.feed.publishedPathId()==next&&r.count(Topics.SD_NEXT_LANES)==laneCount,"older route epoch cannot resurrect queued route/sign");
        NavigatorDriver.Observation off=new NavigatorDriver.Observation();off.routeEpoch=12;off.monotonicMs=r.clock.now();r.driver.observe(off);r.clock.drain();
        r.driver.observe(late);r.clock.drain();check(!r.driver.naving(),"stop epoch rejects delayed old route");r.feed.close();
    }
    public static void main(String[] args) {transitions();immutableTable();optionalItems();identityAndRace();System.out.println("ConditionsRefreshTest: "+checks+" checks passed");}
}
