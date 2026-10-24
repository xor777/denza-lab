package dev.denza.adaslink;

import java.io.BufferedReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dynamic legal limits, independent of the factory route JSON's literal speedLimit=0. */
public final class DynamicLegalLimitTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static final class Rig {
        final ManualScheduler clock = new ManualScheduler(100_000);
        final DryRunRecorder recorder = new DryRunRecorder(null, true);
        final StockFeed feed;
        final NavigatorDriver driver;
        long stamp;
        Rig(boolean enabled) {
            StockFeed.Config c = new StockFeed.Config(); c.frame = Coordinates.Frame.WGS84; c.roadSpeedLimit = enabled;
            feed = new StockFeed(clock, recorder, c); driver = new NavigatorDriver(feed, c.frame, clock);
            feed.start(true, true, true); clock.drain();
        }
        void observe(YandexRoute route, double posted, double scalar, boolean onRoute) {
            clock.advanceTo(clock.now() + 1000);
            NavigatorDriver.Observation o = new NavigatorDriver.Observation();
            o.monotonicMs = clock.now(); o.route = route; o.routeId = route.routeId; o.mode = "guidance";
            o.speedLimitMps = posted; o.scalar = scalar; o.positionOnRoute = onRoute;
            o.locationClass = "FINE"; o.lon = 0.0001; o.lat = 0; o.speedMps = 10; o.heading = 90;
            o.stamp = ++stamp; o.fixAgeMs = 20;
            driver.observe(o); clock.drain();
        }
        long trafficLimit() {
            Map<String,Object> last = null;
            for (Map<String,Object> row : recorder.records()) if ("fire".equals(row.get("kind"))
                    && ((Number) row.get("topic")).longValue() == Topics.TRAFFIC_INFO) last = row;
            check(last != null, "actual TrafficInfo emitted");
            @SuppressWarnings("unchecked") Map<String,Object> outer = (Map<String,Object>) last.get("decoded");
            @SuppressWarnings("unchecked") Map<String,Object> message = (Map<String,Object>) outer.values().iterator().next();
            Object value = message.get("spdLmtSpeedValue");
            return value == null ? 0 : ((Number) value).longValue();
        }
        Map<String,Object> note() {
            Map<String,Object> result = null;
            for (Map<String,Object> row : recorder.records()) if ("currentRoadSpeed".equals(row.get("input"))) result = row;
            check(result != null, "limit provenance note exists"); return result;
        }
    }
    private static YandexRoute route(String id, Double first, Double second) {
        return new YandexRoute(id, new double[]{0,0.001,0.002}, new double[]{0,0,0},
            Arrays.asList(new YandexRoute.Section(0,2,"STRAIGHT","Observed road",20)),
            new Double[]{first,second}, new double[0], Collections.<double[]>emptyList(),
            Collections.<double[]>emptyList(), new double[0], Collections.<YandexRoute.Camera>emptyList());
    }
    private static void unknown(Rig r, String why) {
        check(r.trafficLimit() == 0, why + ": previous wire value cleared");
        Map<String,Object> note = r.note();
        check(Boolean.FALSE.equals(note.get("known")) && note.get("limitKmh") == null
                && "unknown".equals(note.get("source")), why + ": no observed zero claimed");
    }
    private static void transitions() {
        Rig r = new Rig(true);
        YandexRoute road = route("limits", 40/3.6, null);
        r.observe(road,60/3.6,0.2,true); check(r.trafficLimit()==60,"SDK legal limit wins over route edge");
        r.observe(road,90/3.6,0.2,true); check(r.trafficLimit()==90,"SDK change reaches dynamic wire");
        r.observe(road,Double.POSITIVE_INFINITY,1.2,true); unknown(r,"non-finite SDK must not become an integer limit");
        r.observe(road,Double.NaN,0.2,true); check(r.trafficLimit()==40,"absent SDK falls back to current route edge");
        check("yandex_route_edge".equals(r.note().get("source")),"route fallback provenance retained");
        r.observe(road,40/3.6,0.2,true);
        check("yandex_guidance".equals(r.note().get("source")),"same numeric limit can change to SDK source");
        r.observe(road,Double.NaN,0.2,true);
        check("yandex_route_edge".equals(r.note().get("source")),"same numeric limit can return to route source");
        r.observe(road,Double.NaN,1.2,true); unknown(r,"edge has no limit");
        r.observe(road,60/3.6,1.2,true); check(r.trafficLimit()==60,"known limit recovers after absence");
        for (double invalid : new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.MAX_VALUE}) {
            r.observe(road,invalid,1.2,true); unknown(r,"invalid SDK " + invalid);
        }
        r.observe(road,Double.POSITIVE_INFINITY,0.2,true);
        check(r.trafficLimit()==40,"invalid SDK can use independently known route edge");
        r.observe(road,Double.NaN,0.2,false); unknown(r,"position does not belong to current route");
        // No FINE position is supplied for malformed scalar: verify limit lookup itself stays bounded.
        for (double scalar : new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,3}) {
            clockOnly(r,road,scalar); check(r.note().get("limitKmh")==null,"invalid route scalar cannot invent route limit");
        }
        r.observe(route("bad-edge",Double.POSITIVE_INFINITY,Double.NaN),Double.NaN,0.2,true);
        unknown(r,"invalid route edge limit");
        r.observe(route("good-again",60/3.6,null),60/3.6,0.2,true); check(r.trafficLimit()==60,"new route restores limit");
        NavigatorDriver.Observation off = new NavigatorDriver.Observation(); off.monotonicMs=r.clock.now()+1000;
        r.clock.advanceTo(off.monotonicMs); r.driver.observe(off); r.clock.drain();
        r.observe(route("restart",60/3.6,null),60/3.6,0.2,true);
        check(r.trafficLimit()==60,"same numerical limit survives nav-start reset in next session");
        Rig legacy = new Rig(false); legacy.observe(road,90/3.6,0.2,true);
        check(legacy.trafficLimit()==0,"historical oracle config default remains suppressed");
        check(!new StockFeed.Config().roadSpeedLimit,"Config default remains false");
        String json = StockRouteJson.messageSend(RouteConverter.convert(road,Coordinates.Frame.WGS84).path,1,StockRouteJson.Strings.EN);
        check(json.contains("\"speedLimit\":0"),"static route speedLimit remains literal zero");
    }
    private static void graphFallback() {
        Rig r = new Rig(true); YandexRoute road=route("graph-fallback",null,null);
        final double metres=6371008.8*Math.PI/180;
        final RoadNetwork.Fragment fragment=RoadNetwork.Fragment.partial(0,0,1,54,36,true,true,"Graph road",
                new double[]{0,0.002*metres},new double[]{0,0},new double[]{0,0.002*metres});
        r.driver.setRoads(new RoadNetwork() {
            public Coordinates.Frame frame(){return Coordinates.Frame.WGS84;}
            public double planarX(double lon){return lon*metres;} public double planarY(double lat){return lat*metres;}
            public double nodeX(int node){return node==0?0:0.002*metres;} public double nodeY(int node){return 0;}
            public int[] near(double x,double y,double radius){return new int[]{0};}
            public RoadNetwork.Fragment fragment(int index){return fragment;}
            public int adjacencyStart(int node){return node;} public int adjacencyEnd(int node){return node+1;}
            public int adjacency(int index){return index;}
        });
        r.observe(road,Double.NaN,0.2,true); check(r.trafficLimit()==54,"matched graph legal limit supplies missing route/SDK limit");
        check("matched_graph".equals(r.note().get("source")),"matched graph provenance explicit");
        r.observe(road,90/3.6,0.2,true); check(r.trafficLimit()==90,"SDK overrides observed graph limit");
        r.observe(road,Double.NaN,0.2,false); unknown(r,"graph cannot supply limit off current route");
    }
    private static void clockOnly(Rig r,YandexRoute road,double scalar) {
        NavigatorDriver.Observation o=new NavigatorDriver.Observation(); o.monotonicMs=r.clock.now()+1000;
        r.clock.advanceTo(o.monotonicMs); o.routeId=road.routeId; o.mode="guidance"; o.positionOnRoute=true; o.scalar=scalar;
        r.driver.observe(o); r.clock.drain();
    }
    @SuppressWarnings("unchecked") private static void recordedSdk(File root) throws Exception {
        File files=new File(root,"captures/yandex-drive-review-20261024/unpacked/files");
        Set<Double> values=new HashSet<>(); Rig r=new Rig(true);
        for(String name:new String[]{"denza-drive-evidence/progress.jsonl.1","denza-drive-evidence/progress.jsonl"})
            try(BufferedReader reader=Files.newBufferedReader(new File(files,name).toPath(),StandardCharsets.UTF_8)) {
                String line;
                while((line=reader.readLine())!=null) {
                    Map<String,Object> sample=(Map<String,Object>)Json.parse(line);
                    if(!"guidance".equals(sample.get("kind")) || !"0ade8ea3-9fe7-4b40-8c49-e205750b212c".equals(sample.get("sourceSessionId"))
                            || !(sample.get("speedLimitMps") instanceof Number) || !"FINE".equals(sample.get("locationClass"))) continue;
                    double mps=((Number)sample.get("speedLimitMps")).doubleValue(); if(!values.add(mps))continue;
                    // The exact SDK float values from the real trip exercise m/s→integer km/h rounding.
                    YandexRoute road=route("recorded-limit-"+values.size(),null,null);
                    r.observe(road,mps,0.2,true);
                    check(r.trafficLimit()==Math.round(mps*3.6),"recorded SDK value reaches TrafficInfo: "+mps);
                    check("yandex_guidance".equals(r.note().get("source")),"SDK source is explicit");
                }
            }
        check(values.size()==5,"real trip supplied distinct 20/40/50/60/90 km/h SDK limits");
    }
    public static void main(String[] args) throws Exception {
        transitions(); graphFallback(); recordedSdk(new File(args.length==0?".":args[0]));
        System.out.println("DynamicLegalLimitTest: "+checks+" checks passed");
    }
}
