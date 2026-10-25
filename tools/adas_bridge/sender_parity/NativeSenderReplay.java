package dev.denza.adaslink;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Host-only adapter of captured ORIGINAL sender inputs. Never uses YandexRoute or convert(). */
public final class NativeSenderReplay {
    private final ManualScheduler clock;
    private final Recorder recorder;
    private final StockFeed feed;
    private StockFeed.Route route;
    private long lastSeq = -1;

    static final class Recorder implements Publisher {
        final Writer out;
        final ManualScheduler clock;
        long seq;
        Long activeInputSeq;
        Recorder(Writer out, ManualScheduler clock) { this.out=out; this.clock=clock; }
        public BridgeStatus.Mode mode() { return BridgeStatus.Mode.DRY_RUN; }
        void write(Map<String,Object> row) {
            try { out.write(Json.write(row)); out.write('\n'); out.flush(); }
            catch(IOException e) { throw new UncheckedIOException(e); }
        }
        public int startService(long topic) { note("start_service", obj("topic",Long.toString(topic)),clock.now()); return 0; }
        public int fire(long topic,byte[] payload,long now) {
            Map<String,Object> row=obj("kind","fire");row.put("seq",seq++);row.put("at_ms",now);
            row.put("topic",Long.toString(topic));row.put("payloadHex",Pb.hex(payload));row.put("input_seq",activeInputSeq);write(row);return 0;
        }
        public void note(String kind,Map<String,Object> fields,long now) {
            Map<String,Object> row=obj("kind","note");row.put("note",kind);row.put("at_ms",now);row.put("data",fields);write(row);
        }
        void gap(String field,Object detail) { note("coverage_gap",obj(field,detail),clock.now()); }
    }

    NativeSenderReplay(Map<String,Object> header,Writer out) {
        clock=new ManualScheduler(number(header,"start_ms").longValue());recorder=new Recorder(out,clock);
        StockFeed.Config config=new StockFeed.Config();
        config.frame=Coordinates.Frame.WGS84; // Captured doubles already are in the SDK's coordinate frame.
        config.strings=StockRouteJson.Strings.valueOf(string(header,"locale"));
        config.roadSpeedLimit=true;
        config.acceptSimulatedLocations=false;
        feed=new StockFeed(clock,recorder,config);
        Map<String,Object> meta=obj("schema","denza-stock-sender-port-v1");meta.put("kind","header");
        meta.put("input_header",header);meta.put("adapter","native-getters-direct-stockfeed");
        meta.put("reflection",Arrays.asList("pathIds (native identity)","naviInfo (raw indices/distances)",
                "location (native km/h integer casts)","regionState fields (captured original P bean)","sendPath (native resend)","vehicleSpeed"));
        meta.put("processing_boundary", "Original e0.P/v0 inputs invoke private location/naviInfo directly. Input attribution ends before queued work drains; independent timers have no input_seq.");
        meta.put("geometry_policy","native link lengths; no route conversion, projection, interpolation or Yandex driver");
        recorder.write(meta);
        Map<String,Object> gates=map(required(header,"gates"));
        feed.start(bool(gates,"service"),bool(gates,"switch"),bool(gates,"no_sleep"));clock.drain();
    }

    @SuppressWarnings("unchecked") void event(Map<String,Object> event) throws Exception {
        long seq=number(event,"seq").longValue(), at=number(event,"at_ms").longValue();
        if(seq<=lastSeq || at<clock.now()) throw new IllegalArgumentException("non-monotonic input seq/time");
        lastSeq=seq;recorder.activeInputSeq=null;clock.advanceTo(at);recorder.activeInputSeq=seq;
        String name=string(event,"event");Map<String,Object> data=map(required(event,"data"));
        recorder.note("original_input",event,clock.now());
        switch(name) {
            case "nav_start": feed.onNavStart();break;
            case "arrival": feed.onNaviArrive();break;
            case "nav_stop": feed.onNavStop();break;
            case "reroute":feed.onReroute(integer(data,"amapRouteType"));break;
            case "gates":feed.setGates(bool(data,"service"),bool(data,"switch"),bool(data,"no_sleep"));break;
            case "road_speed":feed.onCurrentRoadSpeed(integer(data,data.containsKey("kmh") ? "kmh" : "speedKmh"));break;
            case "vehicle_speed":invoke("vehicleSpeed",new Class<?>[]{int.class},integer(data,data.containsKey("kmh") ? "kmh" : "speedKmh"));break;
            case "traffic_events":feed.onTrafficEvents();break;
            case "ack":
                if(data.containsKey("topic") && longValue(data,"topic")!=1126088888320002L) {
                    recorder.gap("unrepresented_ADAS_answer",data);break;
                }
                feed.onPathSelectConfirm(data.containsKey("payloadHex") ? Pb.unhex(string(data,"payloadHex")) :
                        Base64.getDecoder().decode(string(data,"payloadB64")));break;
            case "route": {
                StockPath path=path(map(required(data,"path")));
                if(data.containsKey("resend") && bool(data,"resend")) {
                    if(route==null || route.pathId()!=path.pathId || !route.converted.contentKey.equals(path.contentKey()))
                        throw new IllegalArgumentException("resend has no identical previously captured route");
                    invoke("sendPath",new Class<?>[]{StockFeed.Route.class,long.class,boolean.class,long.class},
                            route,path.pathId,true,field("routeGeneration"));
                } else {
                    if(data.containsKey("lastMultipathRequestId") && data.containsKey("requestId") &&
                            longValue(data,"lastMultipathRequestId")==longValue(data,"requestId") &&
                            (!data.containsKey("someIpV2") || !bool(data,"someIpV2")))
                        recorder.gap("unrepresented_selection_only_G_branch",data);
                    RouteConverter.Converted converted=converted(path);
                    ((Map<String,Long>)field("pathIds")).put(converted.contentKey,path.pathId);
                    route=feed.onRouteResult(converted,integer(data,"amapRouteType"));
                }
                break;
            }
            case "progress": progress(data);break;
            case "location": {
                // The original P returns before reading a match or touching its throttle in these two cases.
                if(bool(data,"simulated") || (data.containsKey("matchCount") && integer(data,"matchCount")==0))break;
                if(data.containsKey("regionState")) {
                    Map<String,Object> region=map(required(data,"regionState"));
                    for(String regionField:Arrays.asList("regionId","provinceCode","cityCode","weather"))setField(regionField,integer(region,regionField));
                    setField("countryCode",longValue(region,"countryCode"));
                } else recorder.gap("missing_native_regionState",true);
                double speed=decimal(data,"speedKmh");
                StockFeed.Fix fix=new StockFeed.Fix(decimal(data,"matchedLon"),decimal(data,"matchedLat"),
                        decimal(data,"rawLon"),decimal(data,"rawLat"),speed/3.6,decimal(data,"heading"),
                        bool(data,"simulated"),0,decimal(data,"vehicleSpeedKmh"));
                fix.roadClass=integer(data,"roadClass");fix.ownership=integer(data,"ownership");
                if(fix.ownership==Integer.MIN_VALUE)fix.ownership=-1;
                for(String k:Arrays.asList("rawLocInfo","linkType","pathId","matchCount","throttleLastMs","throttlePeriodMs"))
                    if(data.containsKey(k)) recorder.gap("location."+k,data.get(k));
                if(!fix.simulated) {
                    // P passes native km/h ints. Invoke its port body so division/multiplication cannot invent a rounding defect.
                    invoke("location",new Class<?>[]{StockFeed.Fix.class,int.class,int.class},
                            fix,(int)speed,(int)decimal(data,"vehicleSpeedKmh"));
                }
                break;
            }
            case "advance":case "end":break;
            default:recorder.gap("unsupported_event",name);
        }
        recorder.activeInputSeq=null;clock.drain();
    }

    void progress(Map<String,Object> d) throws Exception {
        // Validate the recorded native values even when their PathInfo argument was not captured.
        // G may occur later than v0: never borrow that future table to fill this earlier input.
        long nativePath=longValue(d,"pathId");
        for(String key:Arrays.asList("segIdx","linkIdx","curPointIdx","linkRemain","segRemain","routeRemain","remainLights","crossManeuverID"))integer(d,key);
        decimal(d,"timeToFinishS");decimal(d,"curLinkSpeed");
        for(String key:Arrays.asList("NaviInfoFlag","naviInfoFlag"))if(d.containsKey(key))integer(d,key);
        if(route==null || nativePath!=route.pathId()) {
            Map<String,Object> gap=obj("reason",route==null ? "No earlier captured G/PathInfo snapshot" : "Captured current PathInfo has a different native pathId");
            gap.put("native_input",d);gap.put("current_captured_pathId",route==null ? null : Long.toString(route.pathId()));
            recorder.gap("unrepresentable_native_progress_missing_earlier_path_snapshot",gap);
            return;
        }
        int seg=integer(d,"segIdx"), link=integer(d,"linkIdx"), global=0;
        RouteConverter.Converted c=route.converted;
        if(seg<0 || seg>=c.path.segments.size() || link<0 || link>=c.path.segments.get(seg).links.size())
            throw new IllegalArgumentException("native progress indices outside native path");
        for(int i=0;i<seg;i++)global+=c.path.segments.get(i).links.size();global+=link;
        int[] ni={seg,link,global,integer(d,"linkRemain"),integer(d,"segRemain"),integer(d,"routeRemain"),integer(d,"remainLights")};
        double passed=c.linkEndM[global]-ni[3];
        c.linkSpeedKmh[global]=decimal(d,"curLinkSpeed");
        StockPath.Segment segment=c.path.segments.get(seg);
        int derivedCross=StockRouteJson.displayAction(segment.mainAction,segment.assistantAction);
        if(integer(d,"crossManeuverID")!=derivedCross)
            recorder.gap("crossManeuverID",obj("native",required(d,"crossManeuverID")));
        // curPointIdx has no input in the port's look-ahead scan. Preserve and report rather than inventing a scalar.
        recorder.gap("curPointIdx",required(d,"curPointIdx"));
        for(String k:Arrays.asList("NaviInfoFlag","naviInfoFlag","rawNaviInfo"))
            if(d.containsKey(k))recorder.gap("progress."+k,d.get(k));
        // Original e0.v0 is the processing boundary. g.l -> f.e is a separate, unrecorded input.
        // Do not fabricate its ADV output inside a captured v0 call.
        recorder.gap("unrecorded_DriveRouteManager_l_odd_distance_input",true);
        invoke("naviInfo",new Class<?>[]{StockFeed.Route.class,int[].class,double.class,double.class,double.class,long.class},
                route,ni,passed,decimal(d,"timeToFinishS"),Double.NaN,field("facilitiesProgressEpoch"));
    }

    StockPath path(Map<String,Object> d) {
        StockPath p=new StockPath();p.pathId=longValue(d,"pathId");
        if(d.containsKey("viaRoadInfo"))recorder.gap("path.viaRoadInfo",d.get("viaRoadInfo"));
        for(Object raw: list(required(d,"segments"))) {
            Map<String,Object>s=map(raw);StockPath.Segment segment=new StockPath.Segment();
            if(s.containsKey("segmentIndex") && integer(s,"segmentIndex")!=p.segments.size())
                recorder.gap("segment.segmentIndex",s.get("segmentIndex"));
            segment.length=longValue(s,"length");segment.travelTime=longValue(s,"travelTime");
            segment.mainAction=integer(s,"mainAction");segment.assistantAction=integer(s,"assistantAction");
            segment.trafficLightNum=longValue(s,"trafficLightNum");
            for(Object rawLink:list(required(s,"links"))) {
                Map<String,Object>l=map(rawLink);StockPath.Link v=new StockPath.Link();
                if(l.containsKey("urid"))recorder.gap("link.urid",l.get("urid"));
                v.topoId=longValue(l,"topoId");v.roadClass=integer(l,"roadClass");v.roadName=string(l,"roadName");
                v.length=integer(l,"length");v.linkType=integer(l,"linkType");v.formway=integer(l,"formway");
                v.hasTrafficLight=bool(l,"hasTrafficLight");v.laneNum=integer(l,"laneNum");v.hasMultiOut=bool(l,"hasMultiOut");
                v.mainAction=integer(l,"mainAction");v.assistantAction=integer(l,"assistantAction");v.adcode=longValue(l,"adcode");
                v.ownership=integer(l,"ownership");v.hasParallel=bool(l,"hasParallel");v.direction=integer(l,"direction");
                v.hasMixFork=bool(l,"hasMixFork");v.lon=ints(list(required(l,"lon")));v.lat=ints(list(required(l,"lat")));
                if(v.lon.length!=v.lat.length)throw new IllegalArgumentException("native coordinate array length mismatch");
                segment.links.add(v);
            }
            if(segment.links.isEmpty())throw new IllegalArgumentException("empty original segment");p.segments.add(segment);
        }
        if(p.segments.isEmpty())throw new IllegalArgumentException("empty original path");
        for(Object raw:list(required(d,"groups"))) {
            if(raw instanceof List) {
                List<Object>g=list(raw);if(g.size()!=3)throw new IllegalArgumentException("native group triple required");
                p.groups.add(new Object[]{((Number)g.get(0)).intValue(),((Number)g.get(1)).intValue(),(String)g.get(2)});
            } else {Map<String,Object>g=map(raw);p.groups.add(new Object[]{integer(g,"startSegmentIndex"),integer(g,"segmentCount"),string(g,"roadName")});}
        }
        for(Object raw:list(required(d,"trafficLights"))) {
            List<Object> point=list(raw);if(point.size()!=2)throw new IllegalArgumentException("traffic light must have lon/lat");
            p.trafficLights.add(new double[]{((Number)point.get(0)).doubleValue(),((Number)point.get(1)).doubleValue()});
        }
        return p;
    }

    static RouteConverter.Converted converted(StockPath p) {
        int n=p.linkCount(),ns=p.segments.size(),i=0;double offset=0;
        int[] ls=new int[n],li=new int[n];double[] begin=new double[n],end=new double[n],sb=new double[ns],se=new double[ns],speed=new double[ns];
        Arrays.fill(speed,Double.NaN);
        for(int s=0;s<ns;s++) {
            sb[s]=offset;StockPath.Segment segment=p.segments.get(s);
            for(int l=0;l<segment.links.size();l++,i++) {StockPath.Link v=segment.links.get(l);ls[i]=s;li[i]=l;begin[i]=offset;offset+=v.length;end[i]=offset;}
            se[s]=offset;
        }
        RouteConverter.Converted c=new RouteConverter.Converted("stock-native-"+p.pathId,p,offset,ls,li,begin,end,begin.clone(),end.clone(),sb,se,
                null,new double[]{0,offset},new ArrayList<double[]>(),new ArrayList<double[]>(),new ArrayList<double[]>(),speed);
        c.linkSpeedKmh=new double[n];Arrays.fill(c.linkSpeedKmh,Double.NaN);return c;
    }

    void setField(String name,Object value) throws Exception {Field f=StockFeed.class.getDeclaredField(name);f.setAccessible(true);f.set(feed,value);}
    Object field(String name) throws Exception {Field f=StockFeed.class.getDeclaredField(name);f.setAccessible(true);return f.get(feed);}
    Object invoke(String name,Class<?>[] types,Object...args) throws Exception {
        Method method=StockFeed.class.getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(feed,args);}catch(InvocationTargetException e){throw new IllegalStateException(name,e.getCause());}
    }
    static Map<String,Object> obj(String k,Object v){Map<String,Object>m=Json.object();m.put(k,v);return m;}
    @SuppressWarnings("unchecked")static Map<String,Object> map(Object o){if(!(o instanceof Map))throw new IllegalArgumentException("object required");return(Map<String,Object>)o;}
    @SuppressWarnings("unchecked")static List<Object>list(Object o){if(!(o instanceof List))throw new IllegalArgumentException("list required");return(List<Object>)o;}
    static Object required(Map<String,Object>m,String k){if(!m.containsKey(k)||m.get(k)==null)throw new IllegalArgumentException("missing native input: "+k);return m.get(k);}
    static Number number(Map<String,Object>m,String k){Object v=required(m,k);if(!(v instanceof Number))throw new IllegalArgumentException("number required: "+k);return(Number)v;}
    static int integer(Map<String,Object>m,String k){return Math.toIntExact(longValue(m,k));}
    static long longValue(Map<String,Object>m,String k){Object v=required(m,k);if(v instanceof String)return Long.parseLong((String)v);Number n=number(m,k);if(n.doubleValue()!=n.longValue())throw new IllegalArgumentException("integer required: "+k);return n.longValue();}
    static double decimal(Map<String,Object>m,String k){double v=number(m,k).doubleValue();if(!Double.isFinite(v))throw new IllegalArgumentException("finite number required: "+k);return v;}
    static String string(Map<String,Object>m,String k){Object v=required(m,k);if(!(v instanceof String))throw new IllegalArgumentException("string required: "+k);return(String)v;}
    static boolean bool(Map<String,Object>m,String k){Object v=required(m,k);if(!(v instanceof Boolean))throw new IllegalArgumentException("boolean required: "+k);return(Boolean)v;}
    static int[] ints(List<Object> a){int[] out=new int[a.size()];for(int i=0;i<a.size();i++){Number n=(Number)a.get(i);if(n.doubleValue()!=n.intValue())throw new IllegalArgumentException("native grid int required");out[i]=n.intValue();}return out;}

    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("NativeSenderReplay INPUT.jsonl OUTPUT.jsonl");
        try(BufferedReader input=Files.newBufferedReader(Paths.get(args[0]),StandardCharsets.UTF_8);
            Writer output=Files.newBufferedWriter(Paths.get(args[1]),StandardCharsets.UTF_8)) {
            String first=input.readLine();if(first==null)throw new IllegalArgumentException("missing input header");
            Map<String,Object> header=map(Json.parse(first));
            if(!"denza-stock-sender-input-v1".equals(string(header,"schema")))throw new IllegalArgumentException("input schema");
            NativeSenderReplay replay=new NativeSenderReplay(header,output);String line;boolean end=false;
            while((line=input.readLine())!=null){if(line.trim().isEmpty())continue;if(end)throw new IllegalArgumentException("input after end");Map<String,Object> row=map(Json.parse(line));replay.event(row);end="end".equals(row.get("event"));}
            if(!end)throw new IllegalArgumentException("missing end event / timer-tail boundary");
        }
    }
}
