package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** FontRoundBean.dstLinkDistance and lightDist are independent in the factory DEX. */
public final class TrafficLightDynamicsTest {
    private static int checks;
    private static void check(boolean ok,String reason) { checks++;if(!ok)throw new AssertionError(reason); }
    private static final class Sent {
        final long topic;final byte[] payload;
        Sent(long topic,byte[] payload) { this.topic=topic;this.payload=payload; }
    }
    private static final class Memory implements StockFeed.GuardedLivePublisher {
        final BridgeStatus.Mode mode;final List<Sent> sent=new ArrayList<>();
        Memory(boolean live) { mode=live?BridgeStatus.Mode.LIVE:BridgeStatus.Mode.DRY_RUN; }
        public BridgeStatus.Mode mode() { return mode; }
        public int startService(long service) { return 0; }
        public int fire(long topic,byte[] payload,long now) { sent.add(new Sent(topic,payload));return 0; }
        public void note(String kind,Map<String,Object> fields,long now) { }
        public void routeChanged(long id) { }
        public void beginRoute(long id,int chunks) { }
        public void navigationStopped() { }
        List<Map<String,Object>> of(long topic) {
            List<Map<String,Object>> out=new ArrayList<>();
            for(Sent s:sent)if(s.topic==topic)out.add(LaneGuidanceTest.inner(topic,s.payload));
            return out;
        }
    }
    private static final class Rig {
        final ManualScheduler clock=new ManualScheduler(100_000);
        final Memory wire;final StockFeed feed;final NavigatorDriver driver;
        long stamp;
        Rig(boolean live) {
            wire=new Memory(live);StockFeed.Config config=new StockFeed.Config();config.frame=Coordinates.Frame.WGS84;
            long[] id={800_000};config.livePathIds=()->++id[0];
            feed=new StockFeed(clock,wire,config);driver=new NavigatorDriver(feed,config.frame,clock);
            feed.start(true,true,true);clock.drain();
        }
        void tick(YandexRoute route,double scalar,boolean onRoute) {
            clock.advanceTo(clock.now()+1000);
            NavigatorDriver.Observation o=DynamicTests.guidance(clock.now(),route.routeId,route,scalar,10,++stamp);
            o.fixAgeMs=0;
            if(!onRoute) { o.mode="rerouting";o.positionOnRoute=false;o.lon+=.001;o.lat+=.001; }
            driver.observe(o);clock.drain();
        }
        Map<String,Object> last(long topic) {
            List<Map<String,Object>> out=wire.of(topic);check(!out.isEmpty(),"wire topic exists "+topic);return out.get(out.size()-1);
        }
    }
    private static long number(Map<String,Object> m,String name) { Object n=m.get(name);return n==null?0:((Number)n).longValue(); }
    private static Map<String,Object> fixture(boolean lane,boolean light) {
        Map<String,Object> s=LaneGuidanceTest.snapshot(lane?Arrays.<Object>asList(LaneGuidanceTest.sign(8,
                LaneGuidanceTest.lane("PLAIN_LANE","STRAIGHT_AHEAD","STRAIGHT_AHEAD"))):Collections.<Object>emptyList(),.0023);
        s.put("routeId","light-dynamics-"+lane+"-"+light);
        s.put("trafficLights",light?Arrays.asList(Collections.singletonMap("position",LaneGuidanceTest.pos(10))):Collections.emptyList());
        // Intermediate junctions make the target several links ahead, without changing its identity.
        s.put("restrictedTurns",Arrays.asList(Collections.singletonMap("position",LaneGuidanceTest.pos(2)),
                Collections.singletonMap("position",LaneGuidanceTest.pos(5))));
        List<Object> sections=new ArrayList<>();
        for(int[] ends:new int[][]{{0,5},{5,12}}) {
            Map<String,Object> geometry=Json.object();geometry.put("begin",LaneGuidanceTest.pos(ends[0]));geometry.put("end",LaneGuidanceTest.pos(ends[1]));
            sections.add(Collections.singletonMap("geometry",geometry));
        }
        s.put("sections",sections);
        return s;
    }
    /** Invert the independent DEX accumulator: current link remain + whole following link lengths. */
    private static double atLaneDistance(RouteConverter.Converted c,int distance) {
        LaneGuidance.Placed lane=c.laneGuidance.signs.get(0);int target=-1;
        for(int i=0;i<c.linkSegment.length;i++)if(c.linkSegment[i]==lane.segmentIndex&&c.linkInSegment[i]==lane.linkIndex)target=i;
        int suffix=0;
        for(int i=target;i>=0;i--) {
            StockPath.Link link=c.path.segments.get(c.linkSegment[i]).links.get(c.linkInSegment[i]);
            int remain=distance-suffix;
            if(remain>0&&remain<link.length) {
                double passed=c.linkEndM[i]-remain;
                for(int p=0;p<c.cumulative.length-1;p++)if(passed>=c.cumulative[p]&&passed<=c.cumulative[p+1])
                    return p+(passed-c.cumulative[p])/(c.cumulative[p+1]-c.cumulative[p]);
            }
            suffix+=link.length;
        }
        throw new AssertionError("distance outside fixture "+distance);
    }
    private static void laneTargetGate() {
        YandexRoute route=YandexRoute.fromSnapshot(fixture(true,true));RouteConverter.Converted c=RouteConverter.convert(route,Coordinates.Frame.WGS84);
        for(boolean live:new boolean[]{false,true})for(int distance:new int[]{499,500,501,900}) {
            Rig r=new Rig(live);double scalar=atLaneDistance(c,distance);r.tick(route,scalar,true);r.wire.sent.clear();r.tick(route,scalar,true);
            List<Map<String,Object>> lights=r.wire.of(Topics.SD_TRAFFIC_LIGHT);
            check(lights.size()==(distance<500?1:0),(live?"LIVE":"dry")+": lane target "+distance+" m controls strict <500 gate");
            check(c.lightM[0]-c.metres(scalar)>500,"the light itself remains beyond500m at lane gate "+distance);
            if(distance<500)check(number(lights.get(0),"trafficLightExist")==2,"mapped light emitted beyond500m when lane target is499m");
            r.feed.close();
        }
    }
    private static void noTargetAndReset() {
        for(boolean live:new boolean[]{false,true}) {
            Rig r=new Rig(live);YandexRoute route=YandexRoute.fromSnapshot(fixture(false,true));
            r.tick(route,1,true);r.tick(route,1,true);
            check(number(r.last(Topics.SD_TRAFFIC_LIGHT),"trafficLightExist")==2,"no lane target keeps factory dstLinkDistance default0");
            // Passing the final light on this path clears its coordinates and resets the gate to0.
            r.tick(route,11,true);
            Map<String,Object> reset=r.last(Topics.SD_TRAFFIC_LIGHT);
            check(number(reset,"trafficLightExist")==1&&number(reset,"trafficLightLon")==0&&number(reset,"trafficLightLat")==0,
                    "no light sends factory clear, rather than retaining coordinates");
            r.feed.onNavStop();r.clock.drain();int count=r.wire.of(Topics.SD_TRAFFIC_LIGHT).size();
            r.clock.advanceTo(r.clock.now()+1000);r.feed.onLocation(new StockFeed.Fix(37.005,55,Double.NaN,Double.NaN,10,90,false,0,null));r.clock.drain();
            check(r.wire.of(Topics.SD_TRAFFIC_LIGHT).size()==count,"stopped navigation emits no light");r.feed.close();
            Rig noLight=new Rig(live);YandexRoute absent=YandexRoute.fromSnapshot(fixture(true,false));
            RouteConverter.Converted c=RouteConverter.convert(absent,Coordinates.Frame.WGS84);double scalar=atLaneDistance(c,501);
            noLight.tick(absent,scalar,true);noLight.wire.sent.clear();noLight.tick(absent,scalar,true);
            check(number(noLight.last(Topics.SD_TRAFFIC_LIGHT),"trafficLightExist")==1,"no coordinate resets gate to0 even with lane target beyond500m");noLight.feed.close();
        }
    }
    private static void closeLightDoesNotOpenGate() {
        Map<String,Object> s=fixture(true,true);
        s.put("trafficLights",Arrays.asList(Collections.singletonMap("position",LaneGuidanceTest.pos(6))));
        YandexRoute route=YandexRoute.fromSnapshot(s);RouteConverter.Converted c=RouteConverter.convert(route,Coordinates.Frame.WGS84);
        double scalar=atLaneDistance(c,501),lightAhead=c.lightM[0]-c.metres(scalar);
        check(lightAhead>0&&lightAhead<500,"fixture puts the light within500m, lane target beyond500m");
        for(boolean live:new boolean[]{false,true}) {
            Rig r=new Rig(live);r.tick(route,scalar,true);r.wire.sent.clear();r.tick(route,scalar,true);
            check(r.wire.of(Topics.SD_TRAFFIC_LIGHT).isEmpty(),"a close light does not replace the independent lane-target gate");r.feed.close();
        }
    }
    private static void rerouteRetainsNaviInfo() {
        for(boolean live:new boolean[]{false,true}) {
            Rig r=new Rig(live);YandexRoute route=YandexRoute.fromSnapshot(fixture(true,true));
            r.tick(route,3,true);r.tick(route,3,true);Map<String,Object> before=r.last(Topics.SD_VEHICLE_LOCATION);
            check(number(before,"linkOffset")>0&&number(before,"distanceToNextStep")>0,"reroute begins inside link and segment, not at endpoint");
            r.tick(route,3.5,false);Map<String,Object> after=r.last(Topics.SD_VEHICLE_LOCATION);
            for(String key:new String[]{"curSDRouteID","curStepId","curLinkId","distanceToNextStep","linkOffset"})
                check(java.util.Objects.equals(before.get(key),after.get(key)),"reroute retains last NaviInfo "+key+" instead of inventing endpoint offsets");
            check(!before.get("locationLongitudeAssociateRoad").equals(after.get("locationLongitudeAssociateRoad")),"fresh off-route matched road point still advances");
            check(r.wire.of(Topics.ROUTE).size()==1,"off-route location does not replace route");r.feed.close();
        }
    }
    public static void main(String[] args) {
        laneTargetGate();noTargetAndReset();closeLightDoesNotOpenGate();rerouteRetainsNaviInfo();
        System.out.println("TrafficLightDynamicsTest: "+checks+" checks passed");
    }
}
