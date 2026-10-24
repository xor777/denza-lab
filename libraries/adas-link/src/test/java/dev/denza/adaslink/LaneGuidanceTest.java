package dev.denza.adaslink;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.util.*;

/** Actual factory wire fixtures + real Yandex signs + state-machine lifecycle, no Android/device. */
public final class LaneGuidanceTest {
    static int checks;
    static void check(boolean v,String why) { checks++;if(!v)throw new AssertionError(why); }
    static Map<String,Object> pos(double s) { Map<String,Object> p=Json.object();p.put("segmentIndex",(int)s);p.put("segmentPosition",s-(int)s);return p; }
    static Map<String,Object> lane(String kind,String highlight,String... directions) {
        Map<String,Object> l=Json.object();l.put("laneKind",kind);l.put("directions",Arrays.asList(directions));l.put("highlightedDirection",highlight);return l;
    }
    @SafeVarargs static Map<String,Object> sign(double at,Map<String,Object>... lanes) {
        Map<String,Object> s=Json.object();s.put("position",pos(at));s.put("lanes",Arrays.asList(lanes));return s;
    }
    static Map<String,Object> snapshot(List<Object> signs,double step) {
        Map<String,Object> s=Json.object();s.put("routeId","lane-test");List<Object> p=new ArrayList<>();
        for(int i=0;i<=12;i++)p.add(Arrays.asList(37+i*step,55.0));s.put("points",p);s.put("laneSigns",signs);return s;
    }
    static RouteConverter.Converted convert(Map<String,Object> snapshot) {return RouteConverter.convert(YandexRoute.fromSnapshot(snapshot),Coordinates.Frame.WGS84);}
    @SuppressWarnings("unchecked") static Map<String,Object> inner(long topic,byte[] b) {return (Map<String,Object>)Topics.schema(topic).decode(b).values().iterator().next();}
    static byte[] bytes(int... v) {byte[] b=new byte[v.length];for(int i=0;i<v.length;i++)b[i]=(byte)v[i];return b;}
    static void wire() {
        // Original factory f0.g call from Beijing x003; independently retained raw Binder payloads.
        String intersection="0a0e12040101ffff1a04010103032004";
        check(intersection.equals(Pb.hex(StockMessages.intersection(0,bytes(1,1,3,3),bytes(1,1,255,255)))),"factory intersection bytes exact");
        String next="0a310a040101030312040101ffff1a04000000002204ffffffff2a04ffffffff3204ffffffff3803401149005037c9a4167a42";
        long timestamp=((Number)inner(Topics.SD_NEXT_LANES,Pb.unhex(next)).get("timestamp")).longValue();
        check(next.equals(Pb.hex(StockMessages.nextLanes(bytes(1,1,3,3),bytes(1,1,255,255),bytes(0,0,0,0),bytes(255,255,255,255),bytes(255,255,255,255),bytes(255,255,255,255),3,17,timestamp))),"factory nextLanes bytes exact including DOUBLE timestamp");
        check(inner(Topics.SD_NEXT_LANES,Pb.unhex(next)).get("frontLane").equals("0101ffff"),"factory255 sentinel preserved unsigned");
        check(Pb.INTERSECTION_NOTIFY.decode(StockMessages.intersection(-1,bytes(),bytes())).toString().contains("4294967295"),"factory hide -1 uint32");
    }
    static void enums() {
        String[] directions={"UNKNOWN_DIRECTION","LEFT180","LEFT135","LEFT90","LEFT45","STRAIGHT_AHEAD","RIGHT45","RIGHT90","RIGHT135","RIGHT180","LEFT_FROM_RIGHT","RIGHT_FROM_LEFT","LEFT_SHIFT","RIGHT_SHIFT"};
        int[] expected={255,5,1,1,1,0,3,3,3,8,1,3,0,0};
        for(int i=0;i<directions.length;i++) {
            RouteConverter.Converted c=convert(snapshot(Arrays.<Object>asList(sign(2,lane("PLAIN_LANE",directions[i],directions[i]))),.001));
            LaneGuidance.Placed p=c.laneGuidance.next(0);check(p!=null,"retained direction "+directions[i]);
            check((p.back()[0]&255)==expected[i]&&(p.front()[0]&255)==expected[i],"decision "+directions[i]);
            check(p.sign.decisions.get(0).get("sourceDirections").equals(Arrays.asList(directions[i])),"raw direction retained "+directions[i]);
        }
        String[] kinds={"UNKNOWN_KIND","PLAIN_LANE","BUS_LANE","TRAM_LANE","TAXI_LANE","BIKE_LANE"};int[] categories={255,0,1,2,2,2};
        for(int i=0;i<kinds.length;i++) {
            LaneGuidance.Placed p=convert(snapshot(Arrays.<Object>asList(sign(2,lane(kinds[i],"STRAIGHT_AHEAD","STRAIGHT_AHEAD"))),.001)).laneGuidance.next(0);
            check(p.back()[0]==0&&p.front()[0]==0,"kind does not destroy direction "+kinds[i]);
            check((p.backType()[0]&255)==categories[i],"category "+kinds[i]);
        }
        Map<String,Object> s=snapshot(Arrays.<Object>asList(sign(2,lane("PLAIN_LANE","LEFT90","STRAIGHT_AHEAD","LEFT90"),lane("PLAIN_LANE",null,"FUTURE_DIRECTION"),lane("BUS_LANE","RIGHT45","RIGHT45"))),.001);
        LaneGuidance.Placed p=convert(s).laneGuidance.next(0);
        check(Pb.hex(p.back()).equals("02ff03")&&Pb.hex(p.front()).equals("01ff03"),"unknown lane keeps order and other highlights");
        check(!((List<?>)p.sign.decisions.get(1).get("losses")).isEmpty(),"future enum has explicit diagnostic");
        LaneGuidance.Placed inconsistent=convert(snapshot(Arrays.<Object>asList(sign(2,lane("PLAIN_LANE","LEFT90","STRAIGHT_AHEAD"))),.001)).laneGuidance.next(0);
        check((inconsistent.front()[0]&255)==255&&inconsistent.back()[0]==0,"inconsistent highlight fails individually, background retained");
    }
    /** Retained corpus enum is independent of our mapping and of the all-NULL Beijing sample. */
    @SuppressWarnings("unchecked") static void categorySourceOracle(File root)throws Exception {
        Map<String,Object> oracle=(Map<String,Object>)Json.parse(new String(Files.readAllBytes(new File(root,"research/adas-bridge/lane-category-source-20261024.json").toPath()),StandardCharsets.UTF_8));
        Map<String,Object> categories=(Map<String,Object>)oracle.get("enum");
        for(Object raw:(List<?>)oracle.get("mapping")) {
            Map<String,Object> row=(Map<String,Object>)raw;String kind=(String)row.get("sourceKind");
            int expected=((Number)categories.get(row.get("factoryEnum"))).intValue();
            RouteConverter.Converted c=convert(snapshot(Arrays.<Object>asList(sign(2,
                    lane(kind,"LEFT90","LEFT90"),lane(kind,null,"LEFT90"))),.001));
            LaneGuidance.Placed p=c.laneGuidance.next(0);
            Map<String,Object> wire=inner(Topics.SD_NEXT_LANES,StockMessages.nextLanes(p.back(),p.front(),p.extend(),p.recommend(),p.backType(),p.frontType(),p.segmentIndex,p.linkIndex,1000));
            check(Pb.hex(bytes(expected,expected)).equals(wire.get("backLaneType")),"source enum reaches next-lane background "+kind);
            check(Pb.hex(bytes(expected,255)).equals(wire.get("frontLaneType")),"source enum only for explicit foreground "+kind);
            check("0101".equals(wire.get("backLane"))&&"01ff".equals(wire.get("frontLane")),"category does not change direction or width "+kind);
            Map<String,Object> bean=(Map<String,Object>)((List<?>)Json.parse(c.laneGuidance.aheadJson(0,42,1000))).get(0);
            check(bean.get("backLaneType").toString().equals(Arrays.asList(expected,expected).toString()),"source enum reaches 3km background "+kind);
            check(bean.get("frontLaneType").toString().equals(Arrays.asList(expected,255).toString()),"source enum reaches 3km foreground "+kind);
            if("LaneTypeOther".equals(row.get("factoryEnum"))) {
                List<?> losses=(List<?>)p.sign.decisions.get(0).get("losses");
                check(losses.contains("special subtype projected to Other category; no access inference"),"Other projection loss remains explicit "+kind);
            }
        }
        check(((Number)categories.get("LaneTypeTidal")).intValue()==3&&((Number)categories.get("LaneTypeVariable")).intValue()==4,"retain known SDK categories without inventing a Yandex mapping");
    }
    static void crossProduct() {
        String[] ds={"UNKNOWN_DIRECTION","LEFT180","LEFT135","LEFT90","LEFT45","STRAIGHT_AHEAD","RIGHT45","RIGHT90","RIGHT135","RIGHT180","LEFT_FROM_RIGHT","RIGHT_FROM_LEFT","LEFT_SHIFT","RIGHT_SHIFT"};
        int[] bits={0,8,2,2,2,1,4,4,4,16,2,4,1,1}, single={255,5,1,1,1,0,3,3,3,8,1,3,0,0};
        // Factory named union catalogue: unavailable unions stay255, foreground remains independent.
        Map<Integer,Integer> union=new HashMap<>();int[] masks={1,2,3,4,5,8,6,7,16,9,17,10,20,11,12,14,13,18};int[] codes={0,1,2,3,4,5,6,7,8,9,10,11,12,16,17,18,19,20};
        for(int i=0;i<masks.length;i++)union.put(masks[i],codes[i]);
        for(int set=1;set<(1<<ds.length);set++) {
            List<String> directions=new ArrayList<>();int mask=0,highlight=-1;
            for(int i=0;i<ds.length;i++)if((set&(1<<i))!=0){directions.add(ds[i]);mask|=bits[i];if(highlight<0)highlight=i;}
            Map<String,Object> l=lane("PLAIN_LANE",ds[highlight],directions.toArray(new String[0]));
            LaneGuidance.Sign sign=LaneGuidance.readSigns(snapshot(Arrays.<Object>asList(sign(2,l)),.001),12).get(0);
            int expected=(set&1)!=0?255:union.getOrDefault(mask,255);
            check(sign.supported()&&(sign.back[0]&255)==expected,"complete direction powerset background "+set);
            check((sign.front[0]&255)==single[highlight],"complete powerset highlight preserved "+set);
        }
        for(String kind:new String[]{"UNKNOWN_KIND","PLAIN_LANE","BUS_LANE","TRAM_LANE","TAXI_LANE","BIKE_LANE"})
            for(String direction:ds)for(boolean highlight:new boolean[]{false,true}) {
                LaneGuidance.Sign a=LaneGuidance.readSigns(snapshot(Arrays.<Object>asList(sign(2,lane(kind,highlight?direction:null,direction))),.001),12).get(0);
                check(a.supported()&&a.back.length==1&&a.front.length==1,"kind/direction/highlight cross product retains slot");
            }
    }
    static void malformedDiagnostics() {
        Map<String,Object> valid=sign(2,lane("PLAIN_LANE","STRAIGHT_AHEAD","STRAIGHT_AHEAD"));
        Map<String,Object> bad=snapshot(Arrays.<Object>asList("not an object",valid),.001);
        RouteConverter.Converted c=convert(bad);check(!c.laneGuidance.known&&c.laneGuidance.signs.size()==1,"invalid unplaced sign keeps valid evidence, blocks false nearest claim");
        check(c.laneGuidance.rawSignCount==2&&c.laneGuidance.diagnostics.get(0).get("index").equals(0),"precise rawcount/rejected index");
        Map<String,Object> posBad=sign(99,lane("PLAIN_LANE",null,"STRAIGHT_AHEAD"));
        c=convert(snapshot(Arrays.<Object>asList(posBad,valid),.001));check(!c.laneGuidance.known&&!c.laneGuidance.diagnostics.isEmpty(),"invalid position has explicit diagnostic");
        List<Object> tooMany=new ArrayList<>();for(int i=0;i<=LaneGuidance.MAX_SIGNS;i++)tooMany.add(valid);
        LaneGuidance.Source source=LaneGuidance.readSource(snapshot(tooMany,.001),12);
        check(!source.complete&&source.rawSignCount==4097&&source.signs.size()==4096&&!source.diagnostics.isEmpty(),"sign cap retains bounded prefix and exact rejection count");
        Map<String,Object> lane=lane("PLAIN_LANE","STRAIGHT_AHEAD","STRAIGHT_AHEAD","UNKNOWN_DIRECTION");
        LaneGuidance.Sign mixed=LaneGuidance.readSigns(snapshot(Arrays.<Object>asList(sign(2,lane)),.001),12).get(0);
        check((mixed.back[0]&255)==255&&mixed.front[0]==0,"partial background does not erase explicit foreground");
        check(mixed.decisions.get(0).get("knownDirectionMask").equals(1)&&mixed.decisions.get(0).get("knownSubsetFactoryAction").equals(0),"partial background known subset retained explicitly");
        List<String> fifteen=new ArrayList<>();for(int i=0;i<15;i++)fifteen.add("STRAIGHT_AHEAD");lane.put("directions",fifteen);
        LaneGuidance.Sign clipped=LaneGuidance.readSigns(snapshot(Arrays.<Object>asList(sign(2,lane)),.001),12).get(0);
        check(clipped.decisions.get(0).get("sourceDirectionCount").equals(15)&&Boolean.TRUE.equals(clipped.decisions.get(0).get("directionsTruncated")),"direction bound records rawcount and truncated prefix");
    }
    static void placement() {
        Map<String,Object> s=snapshot(Arrays.<Object>asList(sign(4,lane("PLAIN_LANE","STRAIGHT_AHEAD","STRAIGHT_AHEAD"))),.001);
        List<Object> sections=new ArrayList<>();
        for(int i=0;i<2;i++){Map<String,Object> sec=Json.object(),g=Json.object();g.put("begin",pos(i*4));g.put("end",pos(i==0?4:12));sec.put("geometry",g);sections.add(sec);}s.put("sections",sections);
        RouteConverter.Converted c=convert(s);LaneGuidance.Placed p=c.laneGuidance.next(0);
        check(p.segmentIndex==0,"exact section/link end uses approach segment");
        check(p.linkIndex==c.path.segments.get(0).links.size()-1,"exact link end uses approach link");
        check(c.laneGuidance.next(p.metres)!=null&&c.laneGuidance.next(p.metres+.001)==null,"sign expires strictly after passage, not before endpoint");
        List<Object> many=new ArrayList<>();for(int i=1;i<=10;i++)many.add(sign(i,lane("PLAIN_LANE",null,"STRAIGHT_AHEAD")));
        LaneGuidance all=convert(snapshot(many,.001)).laneGuidance;check(all.ahead(0).size()==6,"maximum six ahead");
        LaneGuidance sparse=convert(snapshot(many,.02)).laneGuidance;check(sparse.ahead(0).size()==2,"actual metres enforce3km window");
        check(sparse.ahead(-10000).isEmpty(),"distant signs not fabricated into window");
        Map<String,Object> absent=snapshot(many,.001);absent.remove("laneSigns");check(!convert(absent).laneGuidance.known,"absent source remains unknown");
        check(convert(snapshot(new ArrayList<>(),.001)).laneGuidance.known,"known empty distinct from absent");
    }
    static List<Map<String,Object>> fires(DryRunRecorder r,long topic) {List<Map<String,Object>> out=new ArrayList<>();for(Map<String,Object> x:r.records())if("fire".equals(x.get("kind"))&&((Number)x.get("topic")).longValue()==topic)out.add(x);return out;}
    @SuppressWarnings("unchecked") static void lifecycle() {
        ManualScheduler clock=new ManualScheduler(10000);DryRunRecorder rec=new DryRunRecorder(null,true);StockFeed feed=new StockFeed(clock,rec,new StockFeed.Config());feed.start(true,true,true);feed.onNavStart();clock.drain();
        RouteConverter.Converted c=convert(snapshot(Arrays.<Object>asList(sign(4,lane("PLAIN_LANE","LEFT90","STRAIGHT_AHEAD","LEFT90"))),.001));
        feed.onRouteResult(c,0);clock.drain();feed.onProgress(new StockFeed.Progress(3,20,Double.NaN));clock.drain();
        List<Map<String,Object>> next=fires(rec,Topics.SD_NEXT_LANES);check(next.size()==1,"progress emits real next sign");
        List<Map<String,Object>> intersections=fires(rec,Topics.SD_INTERSECTION);Map<String,Object> ii=(Map<String,Object>)((Map<String,Object>)intersections.get(0).get("decoded")).get("intersection");
        check(((Number)ii.get("distToNextGudc")).intValue()==RouteConverter.round(c.metres(4)-c.metres(3)),"distance measured from sign offset, not segment remain");
        feed.onProgress(new StockFeed.Progress(4.001,19,Double.NaN));clock.drain();
        next=fires(rec,Topics.SD_NEXT_LANES);Map<String,Object> clear=(Map<String,Object>)((Map<String,Object>)next.get(next.size()-1).get("decoded")).get("nextIntersectionLanesInfo");
        check(!clear.containsKey("backLane")&&((Number)clear.get("segmentIndex")).longValue()==4294967295L,"passage clears prior lanes with factory hide");
        int before=fires(rec,Topics.SD_AHEAD_LANES).size();feed.onNavStop();clock.advanceTo(25000);check(fires(rec,Topics.SD_AHEAD_LANES).size()==before,"stop cancels queued ahead task");
        feed.close();
    }
    static void replacement() {
        ManualScheduler clock=new ManualScheduler(1000);DryRunRecorder rec=new DryRunRecorder(null,true);StockFeed feed=new StockFeed(clock,rec,new StockFeed.Config());
        feed.start(true,true,true);feed.onNavStart();clock.drain();
        Map<String,Object> s=snapshot(Arrays.<Object>asList(sign(4,lane("PLAIN_LANE","LEFT90","LEFT90"))),.001);
        RouteConverter.Converted first=convert(s);feed.onRouteResult(first,0);clock.drain();
        feed.onProgress(new StockFeed.Progress(1,20,Double.NaN));
        s.put("routeId","replacement");feed.onRouteResult(convert(s),0);clock.drain();
        check(fires(rec,Topics.SD_NEXT_LANES).isEmpty(),"queued previous route cannot issue lane instructions");
        feed.onProgress(new StockFeed.Progress(1,20,Double.NaN));clock.drain();clock.advanceTo(3000);
        check(fires(rec,Topics.SD_AHEAD_LANES).size()==1,"replacement owns exactly one current generation timer");
        Map<String,Object> empty=snapshot(new ArrayList<>(),.001);empty.put("routeId","replacement-empty");feed.onRouteResult(convert(empty),0);clock.drain();
        feed.onProgress(new StockFeed.Progress(1,20,Double.NaN));clock.drain();
        List<Map<String,Object>> next=fires(rec,Topics.SD_NEXT_LANES);check(next.size()==2,"replacement without sign explicitly clears previous instructions");
        feed.close();int n=fires(rec,Topics.SD_AHEAD_LANES).size();clock.advanceTo(20000);check(fires(rec,Topics.SD_AHEAD_LANES).size()==n,"close cancels lane timer");
    }
    @SuppressWarnings("unchecked") static void sameContentRefresh() {
        ManualScheduler clock=new ManualScheduler(1000);DryRunRecorder rec=new DryRunRecorder(null,true);StockFeed feed=new StockFeed(clock,rec,new StockFeed.Config());feed.start(true,true,true);feed.onNavStart();clock.drain();
        Map<String,Object> s=snapshot(Arrays.<Object>asList(sign(4,lane("PLAIN_LANE","LEFT90","STRAIGHT_AHEAD","LEFT90"))),.001);
        RouteConverter.Converted initial=convert(s);feed.onRouteResult(initial,0);clock.drain();feed.onProgress(new StockFeed.Progress(3,20,Double.NaN));clock.drain();
        long path=feed.publishedPathId();int sends=fires(rec,Topics.ROUTE).size();
        s.put("laneSigns",Arrays.<Object>asList(sign(4,lane("PLAIN_LANE","STRAIGHT_AHEAD","STRAIGHT_AHEAD","LEFT90"))));RouteConverter.Converted fresh=convert(s);
        check(fresh.contentKey.equals(initial.contentKey),"lane highlight refresh leaves serialized path content unchanged");
        check(feed.refreshLaneGuidance(fresh),"same-content lane refresh admitted");feed.onProgress(new StockFeed.Progress(3,20,Double.NaN));clock.drain();clock.advanceTo(3000);
        check(feed.publishedPathId()==path&&fires(rec,Topics.ROUTE).size()==sends,"lane-only refresh does not resend path or change wire ID");
        List<Map<String,Object>> next=fires(rec,Topics.SD_NEXT_LANES);Map<String,Object> v=(Map<String,Object>)((Map<String,Object>)next.get(next.size()-1).get("decoded")).get("nextIntersectionLanesInfo");
        check("00".equals(v.get("frontLane")),"same-content new highlight reaches actual feed");
        List<Map<String,Object>> ahead=fires(rec,Topics.SD_AHEAD_LANES);check(ahead.size()==1,"old lane timer cancelled independently of route epoch");
        String json=(String)((Map<String,Object>)ahead.get(0).get("decoded")).get("aheadIntersectionsLanesInfo");
        check(json.contains("\"frontLane\":[0]"),"new lane timer reads refreshed annotations");feed.close();
    }
    @SuppressWarnings("unchecked") static void real(File root,File out)throws Exception {
        List<Object> report=new ArrayList<>();int total=0,retained=0;
        for(int n=1;n<=2;n++) {
            File f=new File(root,"captures/yandex-drive-review-20261024/unpacked/files/denza-drive-evidence/route-"+n+".json");
            Map<String,Object> s=(Map<String,Object>)Json.parse(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
            RouteConverter.Converted c=convert(s);List<Object> signs=new ArrayList<>();
            for(LaneGuidance.Placed p:c.laneGuidance.signs) {
                total++;if(p.sign.supported())retained++;Map<String,Object> row=Json.object();row.put("scalar",p.sign.scalar);row.put("metres",p.metres);row.put("segment",p.segmentIndex);row.put("link",p.linkIndex);row.put("rejectedReason",p.sign.rejectedReason);row.put("annotated",p.sign.annotated);row.put("decisions",p.sign.decisions);
                if(p.sign.supported()){row.put("intersectionHexAtSign",Pb.hex(StockMessages.intersection(0,p.back(),p.front())));row.put("nextHexAtFixedTimestamp",Pb.hex(StockMessages.nextLanes(p.back(),p.front(),p.extend(),p.recommend(),p.backType(),p.frontType(),p.segmentIndex,p.linkIndex,1000)));}
                signs.add(row);
            }
            Map<String,Object> r=Json.object();r.put("source",f.toString());r.put("routeId",c.routeId);r.put("signs",signs);r.put("aheadJsonAtStart",c.laneGuidance.aheadJson(0,42,1000));report.add(r);
        }
        check(total==12&&retained==12,"all twelve real route1/2 signs retained");
        if(out!=null){out.getParentFile().mkdirs();Files.write(out.toPath(),Json.write(report).getBytes(StandardCharsets.UTF_8));}
        System.out.println("Actual trip route1/2: "+retained+"/"+total+" signs retained; per-lane losses explicitly reported");
    }
    public static void main(String[] args)throws Exception {
        File root=new File(args.length>0?args[0]:".");
        wire();enums();categorySourceOracle(root);crossProduct();malformedDiagnostics();placement();lifecycle();replacement();sameContentRefresh();real(root,args.length>1?new File(args[1]):null);
        System.out.println("LaneGuidanceTest: "+checks+" checks PASS");
    }
}
