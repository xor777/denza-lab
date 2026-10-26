package dev.denza.hud.probe;

import dev.denza.adaslink.*;
import dev.denza.adaslink.stocknative.*;
import java.util.*;

/** Product callback policy: deterministic clocks, no car, original APK or old output goldens. */
public final class NativeMapSessionTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static final class Runtime implements StockRuntime,AutoCloseable {
        long now=100000,serial;boolean closed;final List<Task> tasks=new ArrayList<>();
        final List<Object[]> entries=new ArrayList<>();
        static final class Task {Queue queue;Object identity,token;long at,serial;Runnable action;}
        public long uptimeMillis(){return now;}public long elapsedRealtime(){return now;}public long currentTimeMillis(){return 1790000000000L+now;}
        public void processingEnter(String name,Object...args){Object[] row=new Object[args.length+1];row[0]=name;System.arraycopy(args,0,row,1,args.length);entries.add(row);}
        public void post(Queue queue,Object identity,Object token,long delay,Runnable action){if(closed)return;Task t=new Task();t.queue=queue;t.identity=identity;t.token=token;t.at=now+delay;t.serial=serial++;t.action=action;tasks.add(t);}
        public void removeCallbacks(Queue q,Object id){tasks.removeIf(t->t.queue==q&&t.identity==id);}
        public void removeToken(Queue q,Object token){tasks.removeIf(t->t.queue==q&&t.token==token);}
        void advance(long ms){long end=now+ms;int count=0;while(true){Task next=null;for(Task t:tasks)if(t.at<=end&&(next==null||t.at<next.at||t.at==next.at&&t.serial<next.serial))next=t;if(next==null)break;tasks.remove(next);now=Math.max(now,next.at);next.action.run();if(++count>5000)throw new AssertionError("scheduler runaway");}now=end;}
        public void close(){closed=true;tasks.clear();}
        long count(String suffix){return entries.stream().filter(v->((String)v[0]).endsWith(suffix)).count();}
        Object[] last(String suffix){for(int i=entries.size()-1;i>=0;i--)if(((String)entries.get(i)[0]).endsWith(suffix))return entries.get(i);throw new AssertionError(suffix);}
    }
    static final class Environment implements NativeEnvironment {
        NativeMapSession session;double speed=10;dev.denza.adaslink.yandexnative.NativeDonorProfile donorProfile;
        public int adasType(){return 1;}public boolean tipIsland(){return true;}public boolean laneUi(){return false;}public boolean noaConfigured(){return true;}
        public void startClientOrSubscribe(Map<Long,Long[]> x){}public void stopClient(Map<Long,Long[]> x){}public void unSubscribe(Map<Long,Long[]> x){}
        public void resetRouteSignals(){}public void setAvpSupport(int x){}public boolean someIpV2(){return false;}public boolean isNaving(){return session!=null&&session.navigating();}public boolean isKD(){return false;}public boolean isMainThread(){return false;}public double realSpeed(){return speed;}
        public boolean hasMapDataService(){return donorProfile!=null;}public int getAdcodeByLonLat(double x,double y){return donorProfile==null?-1:donorProfile.regionCode;}public int getTownAdcodeByLonLat(double x,double y){return donorProfile==null?-1:donorProfile.regionCode;}public Integer getCityProvince(int x){return donorProfile==null?null:donorProfile.regionCode;}
        public long queryAppointLanesInfo(int a,int b,int c){return -1;}public String cachedOpenCityJson(){return "";}public void writeCachedOpenCityJson(String x){}public List<Integer> avpMapIds(String id){return Collections.emptyList();}
        public double distanceBetweenPoints(double a,double b,double c,double d){return Coordinates.distance(a,b,c,d);}public NativeModels.Coord2DDouble lastLocation(){return null;}public void setLeaveTunnelDist(int x){}public void setEnterTunnelDist(int x){}public String stringResource(String name){return dev.denza.adaslink.yandexnative.NativeResources.get(name,false);}
    }
    static final class Transport implements StockTransport {
        final List<byte[]> routes=new ArrayList<>();int locations;final Map<Long,Map<String,Object>> decoded=new HashMap<>();final List<Long> order=new ArrayList<>();
        public int fire(long topic,byte[] payload){order.add(topic);if(topic==Topics.ROUTE)routes.add(payload.clone());if(topic==Topics.SD_VEHICLE_LOCATION)locations++;if(topic==Topics.SD_VEHICLE_LOCATION||topic==Topics.TRAFFIC_INFO||topic==Topics.SD_SPEED_INTERSECTION||topic==Topics.SD_REGION||topic==Topics.ADV_AUTO_FUN_INFO)decoded.put(topic,Topics.schema(topic).decode(payload));return 0;}
        @SuppressWarnings("unchecked")Map<String,Object> fields(long topic,String key){return (Map<String,Object>)decoded.get(topic).get(key);}
        public int startService(long topic){return 0;}public int stopService(long topic){return 0;}public int request(long topic,byte[] payload,boolean response){return 0;}
        @SuppressWarnings("unchecked")int routeType(){String raw=new String(routes.get(routes.size()-1),java.nio.charset.StandardCharsets.UTF_8);Map<String,Object> outer=(Map<String,Object>)Json.parse(raw.substring(raw.indexOf('{'),raw.lastIndexOf('}')+1));Map<String,Object> inner=(Map<String,Object>)Json.parse((String)outer.get("payload"));return ((Number)inner.get("RouteType")).intValue();}
    }
    static final class Rig {
        final Runtime runtime=new Runtime();final Environment environment=new Environment();final Transport transport=new Transport();long ids=2000;
        final NativeMapSession session;final List<Map<String,Object>> audits=new ArrayList<>();
        Rig(){this(LiveSessionLease.FrameMode.WGS84);}
        Rig(LiveSessionLease.FrameMode mode){this(mode,dev.denza.adaslink.yandexnative.PublicRoadEvidence.EMPTY);}
        Rig(LiveSessionLease.FrameMode mode,dev.denza.adaslink.yandexnative.PublicRoadEvidence evidence){this(mode,evidence,dev.denza.adaslink.yandexnative.OwnerLaneEvidence.EMPTY);}
        Rig(LiveSessionLease.FrameMode mode,dev.denza.adaslink.yandexnative.PublicRoadEvidence evidence,dev.denza.adaslink.yandexnative.OwnerLaneEvidence lanes){this(mode,evidence,lanes,dev.denza.adaslink.yandexnative.YandexNativeAdapter.Policy.TEST_ROAD_DATA.withHistorySpeed(dev.denza.adaslink.yandexnative.YandexNativeAdapter.HistorySpeedPolicy.EXPERIMENTAL_YANDEX_SECTION_FREE_FLOW_PROXY));}
        Rig(dev.denza.adaslink.yandexnative.YandexNativeAdapter.Policy policy){this(LiveSessionLease.FrameMode.WGS84,dev.denza.adaslink.yandexnative.PublicRoadEvidence.EMPTY,dev.denza.adaslink.yandexnative.OwnerLaneEvidence.EMPTY,policy);}
        Rig(LiveSessionLease.FrameMode mode,dev.denza.adaslink.yandexnative.PublicRoadEvidence evidence,dev.denza.adaslink.yandexnative.OwnerLaneEvidence lanes,dev.denza.adaslink.yandexnative.YandexNativeAdapter.Policy policy){environment.donorProfile=policy.donorProfile;session=new NativeMapSession(runtime,runtime,environment,transport,()->++ids,audits::add,mode,evidence,lanes,policy);environment.session=session;session.start(true,true,true);session.clientInitialized(true);runtime.advance(0);}
        NativeNavigationInput input(String id,long epoch,boolean route){NativeNavigationInput o=new NativeNavigationInput();o.routeId=id;o.routeEpoch=epoch;o.mode="guidance";o.routeStatus="ON_ROUTE";o.positionOnRoute=true;o.offlineGeometrySha256="geometry-"+id;o.monotonicMs=runtime.now;o.stamp=runtime.now;o.fixAgeMs=0;o.locationClass="FINE";o.scalar=.3;o.timeToFinishS=30;o.lon=37.6003;o.lat=55.7003;o.rawLon=37.60029;o.rawLat=55.70029;o.speedMps=3;o.heading=45;
            if(route)o.route=new YandexRoute(id,new double[]{37.6,37.601},new double[]{55.7,55.701},Collections.singletonList(new YandexRoute.Section(0,1,"STRAIGHT",null,30)),null,null,null,null,null,null);return o;}
        void observe(NativeNavigationInput o){session.observe(o);runtime.advance(0);}
    }
    public static void main(String[] args){
        routeReasonsAndOffRoute();stationaryInvalidationAndRecovery();offRouteWireAfterStationary();queuedExpiry();limitClear();gracefulStop();branchActions();coordinateModes();rejectedPreparationIsMemoized();replacementWaitsForOwnProgress();sectionFreeFlowProxyReachesFactoryWire();catalogOwnershipStaysWithinConfirmedSourceEdges();catalogLaneCountStaysWithinConfirmedSourceEdges();stockLikeByRoadReachesFactoryWire();incoherentProgressClearsHistory();closedCityLongLookaheadRetainsFactoryOdd();
        System.out.println("NativeMapSessionTest: "+checks+" checks passed");
    }
    static void routeReasonsAndOffRoute(){
        Rig r=new Rig(dev.denza.adaslink.yandexnative.YandexNativeAdapter.Policy.STOCK_LIKE_BY_ROAD);r.observe(r.input("a",1,true));check(r.transport.routeType()==1,"ordinary guidance uses source RouteTypeCommon1, not the bench seed default0");
        r.observe(r.input("b",2,true));check(r.transport.routeType()==1,"ordinary identity replacement remains Common1 without inventing offcourse reason");
        NativeNavigationInput off=r.input("b",2,false);off.routeStatus="NOT_ON_ROUTE";off.positionOnRoute=false;off.scalar=Double.NaN;off.timeToFinishS=Double.NaN;off.lon=37.61;off.lat=55.71;off.stamp++;
        NativeModels.NaviInfo before=(NativeModels.NaviInfo)r.runtime.last(".v0")[1];
        long navi=r.runtime.count(".v0"),location=r.runtime.count(".P");r.observe(off);
        check(r.runtime.count(".b0")==1,"observed source deviation invokes reroute once");
        check(before.curLinkSpeed>0&&r.runtime.count(".v0")==navi+1,"off-route reuses last progress once to clear history speed");
        NativeModels.NaviInfo reset=(NativeModels.NaviInfo)r.runtime.last(".v0")[1];
        check(reset.curLinkSpeed==0&&historyOnWire(r)==0,"off-route reset reaches original NaviInfo and actual speed wire despite missing scalar/ETA");
        check(reset.pathID==before.pathID&&reset.curSegIdx==before.curSegIdx&&reset.curLinkIdx==before.curLinkIdx
                &&reset.routeRemain.dist==before.routeRemain.dist&&reset.routeRemain.time==before.routeRemain.time,"off-route reset preserves the admitted path, indices and distances");
        check(r.runtime.count(".P")==location+1,"current FINE off-route coordinate still reaches native location processing");
        NativeModels.LocInfo info=(NativeModels.LocInfo)r.runtime.last(".P")[1];
        check(info.matchInfo.get(0).linkId==-1&&info.matchInfo.get(0).roadClass==-1,"off-route source does not invent current road identity");check(info.matchInfo.get(0).stPos.lon==37.61,"actual current coordinate preserved");
        r.observe(off.copy());check(r.runtime.count(".b0")==1,"repeated deviation status does not duplicate reroute callbacks");
        r.observe(r.input("c",3,true));check(r.transport.routeType()==2,"replacement after observed deviation uses native offcourse2");
        r.session.close();
    }
    static void offRouteWireAfterStationary(){
        Rig r=new Rig();r.environment.speed=0;NativeNavigationInput stopped=r.input("a",1,true);stopped.speedMps=0;r.observe(stopped);
        Map<String,Object> previous=r.transport.fields(Topics.SD_VEHICLE_LOCATION,"sdVehicleLocation");
        r.runtime.advance(1001);NativeNavigationInput off=r.input("a",1,false);off.routeStatus="NOT_ON_ROUTE";off.positionOnRoute=false;off.scalar=Double.NaN;off.lon=37.61;off.lat=55.71;off.rawLon=37.6099;off.rawLat=55.7099;off.speedMps=10;r.environment.speed=36;r.observe(off);
        Map<String,Object> current=r.transport.fields(Topics.SD_VEHICLE_LOCATION,"sdVehicleLocation");
        check(((Number)current.get("locationLongitudeAssociateRoad")).doubleValue()==37.61,"actual outgoing off-route coordinate replaces old stationary coordinate");
        check(((Number)current.get("locationLongitude")).doubleValue()==37.6099,"independent outgoing raw coordinate preserved");
        check(((Number)current.get("GPSspeed")).intValue()==36&&((Number)current.get("vehicleSpeed")).intValue()==36,"outgoing current speeds replace prior stationary zero");
        check(Boolean.TRUE.equals(r.transport.fields(Topics.TRAFFIC_INFO,"trafficInfo").get("offCourse")),"first actual off-route TrafficInfo preserves original reroute flag");
        check(Objects.equals(previous.get("curSDRouteID"),current.get("curSDRouteID"))&&Objects.equals(previous.get("curStepId"),current.get("curStepId"))&&Objects.equals(previous.get("curLinkId"),current.get("curLinkId")),"factory retains last native path/indices until replacement; road-link unknown does not clear envelope");
        check(r.transport.order.indexOf(Topics.ROUTE)<r.transport.order.indexOf(Topics.SD_VEHICLE_LOCATION),"route publication precedes first dynamic output without ACK requirement");
        int sends=r.transport.locations;r.runtime.advance(2000);check(r.transport.locations==sends,"current moving fix removes old stationary repeat");r.session.close();
    }
    static void stationaryInvalidationAndRecovery(){
        Rig r=new Rig();r.environment.speed=0;NativeNavigationInput stopped=r.input("a",1,true);stopped.speedMps=0;r.observe(stopped);int first=r.transport.locations;r.runtime.advance(1000);check(r.transport.locations>first,"fresh zero speed retains original stationary repeat");
        NativeNavigationInput bad=r.input("a",1,false);bad.locationClass="COARSE";r.observe(bad);int before=r.transport.locations;r.runtime.advance(2500);check(r.transport.locations==before,"bad fix cancels old stationary repeat");
        NativeNavigationInput recovered=r.input("a",1,false);recovered.speedMps=2;r.environment.speed=7.2;r.observe(recovered);check(r.transport.locations>before,"fresh source recovers without replacing session");r.session.close();
    }
    static void queuedExpiry(){
        Rig r=new Rig();r.session.observe(r.input("a",1,true));r.runtime.now+=1600;r.runtime.advance(0);check(r.runtime.count(".P")==0,"expired queued input is dropped before original real-speed read");check(!r.runtime.closed,"input expiry does not permanently close native runtime");r.session.close();
    }
    static void limitClear(){
        Rig r=new Rig();NativeNavigationInput known=r.input("a",1,true);known.speedLimitMps=20;r.observe(known);check(((Integer)r.runtime.last(".J")[1])==72,"known legal limit uses kmh");
        r.observe(r.input("a",1,false));check(((Integer)r.runtime.last(".J")[1])==0,"missing legal limit clears previous road value");r.session.close();
    }
    static void gracefulStop(){
        Rig r=new Rig();r.observe(r.input("a",1,true));check(!r.session.beginGracefulStop(),"normal cancel has no arrival phase");r.runtime.advance(0);check(!r.session.navigating()&&!r.runtime.closed,"graceful stop preserves runtime for native reset and idle output");r.session.close();
        Rig arrived=new Rig();arrived.observe(arrived.input("a",1,true));NativeNavigationInput finish=arrived.input("a",1,false);finish.finished=true;arrived.observe(finish);check(arrived.session.beginGracefulStop(),"drain preserves observed arrival phase");arrived.runtime.advance(0);check(!arrived.runtime.closed,"arrival drain does not dispose runtime early");arrived.session.close();
    }
    static void branchActions(){
        for(String name:new String[]{"FORK_LEFT","FORK_RIGHT","EXIT_LEFT","EXIT_RIGHT"}){
            dev.denza.adaslink.yandexnative.NativeEnums.Action action=dev.denza.adaslink.yandexnative.NativeEnums.action(name);
            check(action.mapped&&action.assistant==(name.endsWith("LEFT")?8:7),"branch direction retained for "+name);
            check(action.main==0&&action.icon==0,"no invented angle/merge/icon for "+name);
        }
    }
    static void coordinateModes(){
        for(LiveSessionLease.FrameMode mode:new LiveSessionLease.FrameMode[]{LiveSessionLease.FrameMode.WGS84,LiveSessionLease.FrameMode.NATIVE_GCJ_UNRESTRICTED_CANDIDATE}){
            Rig r=new Rig(mode);r.observe(r.input("a",1,true));Coordinates.Frame expected=Coordinates.Frame.valueOf(mode.name());
            check(r.session.frame()==expected&&r.session.frameOrdinal()==1,"fixed arm chooses declared frame");
            NativeModels.LocInfo fix=(NativeModels.LocInfo)r.runtime.last(".P")[1];double[] point=Coordinates.toSdk(37.6003,55.7003,expected);
            check(fix.matchInfo.get(0).stPos.lon==point[0]&&fix.matchInfo.get(0).stPos.lat==point[1],"core receives selected coordinate frame once");
            r.runtime.advance(1000);r.observe(r.input("b",2,true));check(r.session.frame()==expected&&r.session.frameOrdinal()==2,"fixed frame remains fixed for new route ID");r.session.close();
        }
        Rig alternate=new Rig(LiveSessionLease.FrameMode.ALTERNATE_ROUTES);alternate.observe(alternate.input("a",1,true));
        check(alternate.session.frame()==Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE,"alternate starts in native candidate");
        NativeNavigationInput refresh=alternate.input("a",2,true);refresh.offlineGeometrySha256="new-a-geometry";alternate.observe(refresh);
        check(alternate.session.frame()==Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE&&alternate.session.frameOrdinal()==1,"same route geometry refresh retains original frame/ordinal");
        alternate.runtime.advance(1000);NativeNavigationInput progress=alternate.input("a",2,false);progress.offlineGeometrySha256="new-a-geometry";alternate.observe(progress);
        check(alternate.session.frameOrdinal()==1,"progress never advances alternating ordinal");
        alternate.observe(alternate.input("b",3,true));check(alternate.session.frame()==Coordinates.Frame.WGS84&&alternate.session.frameOrdinal()==2,"next Yandex route gets WGS control");
        alternate.observe(alternate.input("a",4,true));check(alternate.session.frame()==Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE&&alternate.session.frameOrdinal()==1,"returning Yandex ID retains pinned frame");
        Map<String,Object> audit=alternate.audits.get(alternate.audits.size()-1);check("ALTERNATE_ROUTES".equals(audit.get("coordinateFrameMode"))&&"NATIVE_GCJ_UNRESTRICTED_CANDIDATE".equals(audit.get("coordinateFrame"))&&Long.valueOf(1).equals(audit.get("coordinateRouteOrdinal")),"route policy journals mode, selected frame and ordinal");alternate.session.close();
        Rig blocked=new Rig(LiveSessionLease.FrameMode.NATIVE_GCJ_UNRESTRICTED_CANDIDATE);NativeNavigationInput outside=blocked.input("outside",1,true);outside.route=new YandexRoute("outside",new double[]{41,41.001},new double[]{55.7,55.701},Collections.singletonList(new YandexRoute.Section(0,1,"STRAIGHT",null,30)),null,null,null,null,null,null);blocked.observe(outside);
        check(blocked.session.blocked()!=null&&!blocked.runtime.closed&&blocked.transport.routes.isEmpty(),"outside candidate route is blocked without terminating runtime");
        blocked.observe(blocked.input("outside",2,true));check(blocked.session.frameOrdinal()==1&&blocked.session.navigating(),"same ID retry keeps its first pinned frame/ordinal");
        for(int i=1;i<NativeMapSession.FRAME_ROUTE_LIMIT;i++)blocked.session.sdkCoordinate("id-"+i,37.6,55.7);
        check(blocked.session.sdkCoordinate("overflow",37.6,55.7)==null,"route-frame association has a bounded capacity");
        check(blocked.session.sdkCoordinate("outside",37.6,55.7)!=null,"existing association survives capacity limit");blocked.session.close();
    }
    static void rejectedPreparationIsMemoized(){
        Rig r=new Rig(LiveSessionLease.FrameMode.NATIVE_GCJ_UNRESTRICTED_CANDIDATE);
        NativeNavigationInput outside=r.input("outside",1,true);
        outside.route=new YandexRoute("outside",new double[]{41,41.001},new double[]{55.7,55.701},Collections.singletonList(new YandexRoute.Section(0,1,"STRAIGHT",null,30)),null,null,null,null,null,null);
        r.observe(outside);long firstId=r.ids;String reason=r.session.blocked();
        for(int i=0;i<30;i++){r.runtime.advance(100);NativeNavigationInput fix=outside.copy();fix.monotonicMs=r.runtime.now;fix.stamp=r.runtime.now;r.observe(fix);}
        check(r.ids==firstId&&r.audits.size()==1,"repeated fixes on one rejected snapshot allocate/prepare/audit once");
        check(reason.equals(r.session.blocked())&&!r.runtime.closed&&r.transport.routes.isEmpty(),"cached rejection preserves blocked reason and authorized runtime");
        outside.route.additionalAttributes=new RouteAttributes(){
            public boolean matches(YandexRoute route){return route==outside.route;}
            public double[] cuts(){return new double[0];}
            public RoadNetwork.KnownAttributes attributesBetween(double a,double b){return RoadNetwork.KnownAttributes.NONE;}
            public Map<String,Object> summary(){return Collections.emptyMap();}
        };
        r.observe(outside.copy());check(r.ids==firstId+1&&r.audits.size()==2,"changed immutable attributes retry rejected preparation");
        NativeNavigationInput newSource=outside.copy();newSource.route=new YandexRoute("outside",new double[]{41,41.001},new double[]{55.7,55.701},outside.route.sections,null,null,null,null,null,null);
        r.observe(newSource);check(r.ids==firstId+2&&r.audits.size()==3,"new route snapshot retries even when subject hashes agree");
        newSource.offlineGeometrySha256="updated-source-geometry";r.observe(newSource.copy());
        check(r.ids==firstId+3&&r.audits.size()==4,"changed source geometry identity retries");
        newSource.routeEpoch=2;r.observe(newSource.copy());check(r.ids==firstId+4&&r.audits.size()==5,"new route epoch retries");
        NativeNavigationInput valid=r.input("outside",2,true);r.observe(valid);
        check(r.session.blocked()==null&&r.session.navigating()&&!r.runtime.closed&&r.transport.routes.size()==1,"valid same-ID replacement resumes without rearming");
        check(r.session.frameOrdinal()==1,"rejection retry does not advance same-ID frame policy");r.session.close();
    }
    static void replacementWaitsForOwnProgress(){
        Rig r=new Rig(LiveSessionLease.FrameMode.ALTERNATE_ROUTES);r.observe(r.input("a",1,true));
        long oldPath=r.session.pathId();int oldLocations=r.transport.locations;
        check(r.session.frame()==Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE,"A uses native frame before replacement");
        r.runtime.advance(1001);NativeNavigationInput missingEta=r.input("b",2,true);missingEta.timeToFinishS=Double.NaN;r.observe(missingEta);
        check(r.session.pathId()!=oldPath&&r.session.frame()==Coordinates.Frame.WGS84,"B route itself is accepted in alternate WGS frame");
        check(r.transport.locations==oldLocations,"new B coordinates cannot use old A path envelope before B progress");
        r.runtime.advance(1001);NativeNavigationInput stillMissing=r.input("b",2,false);stillMissing.timeToFinishS=Double.NaN;r.observe(stillMissing);
        check(r.transport.locations==oldLocations&&!r.runtime.closed,"missing ETA keeps B location paused without closing arm");
        r.runtime.advance(1001);r.observe(r.input("b",2,false));Map<String,Object> position=r.transport.fields(Topics.SD_VEHICLE_LOCATION,"sdVehicleLocation");
        check(r.transport.locations>oldLocations&&((Number)position.get("curSDRouteID")).longValue()==r.session.pathId(),"first valid B progress enables B position with its own native path envelope");
        check(((Number)position.get("locationLongitudeAssociateRoad")).doubleValue()==37.6003,"resumed B position uses B WGS control frame exactly once");
        r.runtime.advance(1001);NativeNavigationInput off=r.input("b",2,false);off.positionOnRoute=false;off.routeStatus="NOT_ON_ROUTE";off.scalar=Double.NaN;off.timeToFinishS=Double.NaN;off.lon=37.61;
        int beforeOff=r.transport.locations;r.observe(off);position=r.transport.fields(Topics.SD_VEHICLE_LOCATION,"sdVehicleLocation");
        check(r.transport.locations>beforeOff&&((Number)position.get("locationLongitudeAssociateRoad")).doubleValue()==37.61,"off-route current positions continue after first B progress despite unavailable new ETA");r.session.close();
    }
    static int historyOnWire(Rig r){return ((Number)r.transport.fields(Topics.SD_SPEED_INTERSECTION,"speedIntersectionInformation").getOrDefault("historySpeed",0)).intValue();}
    static void sectionFreeFlowProxyReachesFactoryWire(){
        for(LiveSessionLease.FrameMode frame:new LiveSessionLease.FrameMode[]{LiveSessionLease.FrameMode.WGS84,LiveSessionLease.FrameMode.NATIVE_GCJ_UNRESTRICTED_CANDIDATE}){
            Rig r=new Rig(frame);NativeNavigationInput first=r.input("free-flow",1,false);
            // Source section means seen in drive21: 5 m/s =18 km/h, 2 m/s =7.2 km/h.
            first.route=new YandexRoute(first.routeId,new double[]{37.6,37.601,37.602,37.603},new double[]{55.7,55.701,55.702,55.703},
                    Arrays.asList(new YandexRoute.Section(0,1,"STRAIGHT",null,30,18),
                            new YandexRoute.Section(1,2,"STRAIGHT",null,30,7.2),
                            new YandexRoute.Section(2,3,"STRAIGHT",null,30,Double.NaN)),
                    null,new double[0],Collections.emptyList(),Collections.emptyList(),null,null);
            first.speedLimitMps=20/3.6;r.observe(first);long path=r.session.pathId();
            NativeModels.NaviInfo navi=(NativeModels.NaviInfo)r.runtime.last(".v0")[1];
            check(navi.curLinkSpeed==18&&navi.curSegIdx==0,"session supplies current first-section free-flow proxy to original v0 in "+frame);
            check(historyOnWire(r)==18,"original sender forwards source proxy18 to SpeedIntersection wire");
            Map<String,Object> otherSpeedFields=new HashMap<>(r.transport.fields(Topics.SD_SPEED_INTERSECTION,"speedIntersectionInformation"));otherSpeedFields.remove("historySpeed");
            check(((Integer)r.runtime.last(".J")[1])==20,"legal limit remains independent from free-flow proxy18");
            r.runtime.advance(1001);NativeNavigationInput second=r.input("free-flow",1,false);second.scalar=1;second.speedLimitMps=20/3.6;r.observe(second);
            navi=(NativeModels.NaviInfo)r.runtime.last(".v0")[1];
            check(navi.curLinkSpeed==7&&navi.curSegIdx==1&&navi.pathID==path,"exact section boundary chooses next source7.2 rounded7 on same path");
            check(historyOnWire(r)==7,"source section transition reaches actual wire7 instead of retaining18");
            Map<String,Object> secondFields=new HashMap<>(r.transport.fields(Topics.SD_SPEED_INTERSECTION,"speedIntersectionInformation"));secondFields.remove("historySpeed");
            check(otherSpeedFields.equals(secondFields),"proxy transition changes no other SpeedIntersection field");
            NativeModels.LocInfo fix=(NativeModels.LocInfo)r.runtime.last(".P")[1];
            Coordinates.Frame coordinate=Coordinates.Frame.valueOf(frame.name());double[] expected=Coordinates.toSdk(second.lon,second.lat,coordinate);
            check(fix.matchInfo.get(0).stPos.lon==expected[0]&&fix.matchInfo.get(0).stPos.lat==expected[1],"history proxy preserves independent selected coordinate frame");
            check(fix.matchInfo.get(0).ownership==Integer.MIN_VALUE&&fix.matchInfo.get(0).roadClass==-1,"history proxy fills no ownership or road class");
            long locations=r.runtime.count(".P");r.runtime.advance(1001);NativeNavigationInput stale=r.input("free-flow",1,false);stale.scalar=1.25;stale.fixAgeMs=1501;stale.speedLimitMps=20/3.6;r.observe(stale);
            check(((NativeModels.NaviInfo)r.runtime.last(".v0")[1]).curLinkSpeed==0&&historyOnWire(r)==0,"stale source explicitly clears prior7 in v0 and actual wire");
            check(r.runtime.count(".P")==locations,"stale proxy reset does not turn expired fix into a location");
            r.runtime.advance(1001);NativeNavigationInput fresh=r.input("free-flow",1,false);fresh.scalar=1.5;r.observe(fresh);
            check(historyOnWire(r)==7,"fresh current source recovers its own section value");
            r.runtime.advance(1001);NativeNavigationInput absent=r.input("free-flow",1,false);absent.scalar=2;r.observe(absent);
            navi=(NativeModels.NaviInfo)r.runtime.last(".v0")[1];
            check(navi.curSegIdx==2&&navi.curLinkSpeed==0&&historyOnWire(r)==0,"missing next-section free-flow clears old proxy instead of inventing a global default");
            check(r.session.pathId()==path&&r.transport.routes.size()==1,"speed section/freshness changes never rebuild the static path");r.session.close();
        }
    }
    @SuppressWarnings("unchecked")
    static void catalogOwnershipStaysWithinConfirmedSourceEdges(){
        dev.denza.adaslink.yandexnative.PublicRoadEvidence evidence=dev.denza.adaslink.yandexnative.PublicRoadEvidence.parse(
                "{\"schema\":\"denza-owner-confirmed-public-roads-v1\",\"edges\":[[37.6,55.7,37.601,55.701]]}");
        for(LiveSessionLease.FrameMode frame:new LiveSessionLease.FrameMode[]{LiveSessionLease.FrameMode.WGS84,LiveSessionLease.FrameMode.NATIVE_GCJ_UNRESTRICTED_CANDIDATE}){
            Rig r=new Rig(frame,evidence);NativeNavigationInput first=r.input("catalog",1,false);
            first.route=new YandexRoute(first.routeId,new double[]{37.6,37.601,37.602},new double[]{55.7,55.701,55.702},
                    Arrays.asList(new YandexRoute.Section(0,1,"STRAIGHT",null,30,18),new YandexRoute.Section(1,2,"STRAIGHT",null,30,18)),
                    null,new double[0],Collections.emptyList(),Collections.emptyList(),null,null);
            r.observe(first);long path=r.session.pathId();
            String wire=new String(r.transport.routes.get(0),java.nio.charset.StandardCharsets.UTF_8);
            Map<String,Object> envelope=(Map<String,Object>)Json.parse(wire.substring(wire.indexOf('{'),wire.lastIndexOf('}')+1));
            Map<String,Object> route=(Map<String,Object>)Json.parse((String)envelope.get("payload"));
            List<Map<String,Object>> links=(List<Map<String,Object>>)((Map<String,Object>)((List<?>)route.get("data")).get(0)).get("links");
            check(links.size()==2&&((Number)links.get(0).get("ownership")).intValue()==0&&((Number)links.get(1).get("ownership")).intValue()==Integer.MIN_VALUE,
                    "factory route marks only exact catalogued WGS edge public; next untraversed edge remains unknown in "+frame);
            NativeModels.LocInfo fix=(NativeModels.LocInfo)r.runtime.last(".P")[1];
            check(fix.matchInfo.get(0).ownership==0&&((Number)r.transport.fields(Topics.SD_VEHICLE_LOCATION,"sdVehicleLocation").getOrDefault("currentRoadOwnership",0)).intValue()==0,
                    "catalogued edge public ownership reaches original location processor and actual wire");
            Coordinates.Frame selected=Coordinates.Frame.valueOf(frame.name());double[] point=Coordinates.toSdk(first.lon,first.lat,selected);
            check(fix.matchInfo.get(0).stPos.lon==point[0]&&fix.matchInfo.get(0).stPos.lat==point[1],"catalog matching uses original WGS edges independently of output-frame conversion");
            Map<String,Object> attrs=(Map<String,Object>)r.audits.get(0).get("attributeEvidence");
            check(evidence.sha256().equals(((Map<?,?>)attrs.get("publicRoadEvidence")).get("sha256")),"route audit pins the exact immutable owner catalog");
            r.runtime.advance(1001);NativeNavigationInput future=r.input("catalog",1,false);future.scalar=1.25;future.lon=37.60125;future.lat=55.70125;r.observe(future);
            fix=(NativeModels.LocInfo)r.runtime.last(".P")[1];
            check(fix.matchInfo.get(0).ownership==Integer.MIN_VALUE&&((Number)r.transport.fields(Topics.SD_VEHICLE_LOCATION,"sdVehicleLocation").get("currentRoadOwnership")).intValue()==-1,
                    "progress onto an uncatalogued future edge stays SDK unknown and original wire -1");
            r.runtime.advance(1001);NativeNavigationInput off=r.input("catalog",1,false);off.routeStatus="NOT_ON_ROUTE";off.positionOnRoute=false;off.scalar=Double.NaN;r.observe(off);
            fix=(NativeModels.LocInfo)r.runtime.last(".P")[1];
            check(fix.matchInfo.get(0).ownership==Integer.MIN_VALUE&&((Number)r.transport.fields(Topics.SD_VEHICLE_LOCATION,"sdVehicleLocation").get("currentRoadOwnership")).intValue()==-1,
                    "off-route fix stays unknown even when its coordinates lie on a catalogued edge");
            check(r.session.pathId()==path&&r.transport.routes.size()==1,"ownership progress and off-route handling do not allocate or repartition a path");r.session.close();
        }
    }
    @SuppressWarnings("unchecked")
    static List<Map<String,Object>> serializedLinks(Rig r){
        String wire=new String(r.transport.routes.get(0),java.nio.charset.StandardCharsets.UTF_8);
        Map<String,Object> envelope=(Map<String,Object>)Json.parse(wire.substring(wire.indexOf('{'),wire.lastIndexOf('}')+1));
        Map<String,Object> route=(Map<String,Object>)Json.parse((String)envelope.get("payload"));
        return (List<Map<String,Object>>)((Map<String,Object>)((List<?>)route.get("data")).get(0)).get("links");
    }
    @SuppressWarnings("unchecked")
    static void catalogLaneCountStaysWithinConfirmedSourceEdges(){
        dev.denza.adaslink.yandexnative.OwnerLaneEvidence lanes=dev.denza.adaslink.yandexnative.OwnerLaneEvidence.parse(
                "{\"schema\":\"denza-owner-confirmed-lanes-v1\",\"laneNum\":2,\"edges\":[[37.6,55.7,37.601,55.701]]}");
        for(LiveSessionLease.FrameMode frame:new LiveSessionLease.FrameMode[]{LiveSessionLease.FrameMode.WGS84,LiveSessionLease.FrameMode.NATIVE_GCJ_UNRESTRICTED_CANDIDATE}){
            Rig baseline=new Rig(frame),confirmed=new Rig(frame,dev.denza.adaslink.yandexnative.PublicRoadEvidence.EMPTY,lanes);
            NativeNavigationInput input=baseline.input("local-street",1,false);
            input.route=new YandexRoute(input.routeId,new double[]{37.6,37.601,37.602},new double[]{55.7,55.701,55.702},
                    Arrays.asList(new YandexRoute.Section(0,1,"STRAIGHT",null,30,18),new YandexRoute.Section(1,2,"STRAIGHT",null,30,18)),
                    null,new double[0],Collections.emptyList(),Collections.emptyList(),null,null);
            baseline.observe(input);confirmed.observe(input.copy());
            List<Map<String,Object>> before=serializedLinks(baseline),after=serializedLinks(confirmed);
            check(after.size()==2&&((Number)after.get(0).get("laneNum")).intValue()==2&&((Number)after.get(1).get("laneNum")).intValue()==0,
                    "actual factory route uses total laneNum2 only on exact fully catalogued first edge; future edge remains default0 in "+frame);
            for(int i=0;i<before.size();i++){
                Map<String,Object> a=new HashMap<>(before.get(i)),b=new HashMap<>(after.get(i));a.remove("laneNum");b.remove("laneNum");
                check(a.equals(b),"lane evidence changes no other serialized link field, including IDs/point indexes/ownership");
            }
            NativePath beforePath=(NativePath)baseline.runtime.last(".v0")[2],afterPath=(NativePath)confirmed.runtime.last(".v0")[2];
            for(int i=0;i<beforePath.getSegmentCount();i++){
                List<NativePath.IntPoint> a=beforePath.getSegmentInfo(i).getLinkInfo(0).getPoints(),b=afterPath.getSegmentInfo(i).getLinkInfo(0).getPoints();
                check(a.size()==b.size(),"lane evidence preserves native geometry point count");
                for(int j=0;j<a.size();j++)check(a.get(j).lon==b.get(j).lon&&a.get(j).lat==b.get(j).lat,"lane evidence preserves selected-frame native geometry exactly");
            }
            Map<String,Object> attrs=(Map<String,Object>)confirmed.audits.get(0).get("attributeEvidence");
            check(lanes.sha256().equals(((Map<?,?>)attrs.get("ownerLaneEvidence")).get("sha256")),"session audit pins exact lane catalog SHA");
            check(((Number)after.get(0).get("ownership")).intValue()==Integer.MIN_VALUE,"lane catalog does not imply public ownership");
            baseline.session.close();confirmed.session.close();
        }
    }
    static long oddOnWire(Rig r){return ((Number)r.transport.fields(Topics.ADV_AUTO_FUN_INFO,"advAutoFunInfo").getOrDefault("noODDRegionDist",0)).longValue();}
    static int roadClassOnWire(Rig r){return ((Number)r.transport.fields(Topics.TRAFFIC_INFO,"trafficInfo").get("roadClass")).intValue();}
    static YandexRoute mixedDonorRoute(String id){
        YandexRoute route=new YandexRoute(id,new double[]{37.6,37.601,37.602},new double[]{55.7,55.701,55.702},
                Arrays.asList(new YandexRoute.Section(0,1,"STRAIGHT","Real highway",30,18,"HIGHWAY"),
                        new YandexRoute.Section(1,2,"STRAIGHT","Real street",30,18,"SMALL")),
                new Double[]{25.0,25.0},new double[0],Collections.emptyList(),Collections.emptyList(),null,null);
        route.additionalAttributes=new RouteAttributes(){
            public boolean matches(YandexRoute value){return value==route;}
            public double[] cuts(){return new double[]{1};}
            public RoadNetwork.KnownAttributes attributesBetween(double begin,double end){return RoadNetwork.KnownAttributes.NONE;}
            public RendererClass rendererClassBetween(double begin,double end){int fc=end<=1?2:7;return RendererClass.literal(Integer.toString(fc),"string",fc,"uint32");}
            public Map<String,Object> summary(){return Collections.emptyMap();}
        };
        return route;
    }
    static void stockLikeByRoadReachesFactoryWire(){
        for(LiveSessionLease.FrameMode frame:new LiveSessionLease.FrameMode[]{LiveSessionLease.FrameMode.WGS84,LiveSessionLease.FrameMode.NATIVE_GCJ_UNRESTRICTED_CANDIDATE}){
            Rig r=new Rig(frame,dev.denza.adaslink.yandexnative.PublicRoadEvidence.EMPTY,dev.denza.adaslink.yandexnative.OwnerLaneEvidence.EMPTY,
                    dev.denza.adaslink.yandexnative.YandexNativeAdapter.Policy.STOCK_LIKE_BY_ROAD);
            r.session.openCitiesUpdated(Arrays.asList(1,372));
            NativeNavigationInput input=r.input("stock-like-by-road",1,false);input.route=mixedDonorRoute(input.routeId);input.speedLimitMps=25;r.observe(input);
            List<Map<String,Object>> links=serializedLinks(r);check(links.size()==2,"mixed donor route preserves one real link in each source section in "+frame);
            check(((Number)links.get(0).get("roadclass")).intValue()==6&&((Number)links.get(1).get("roadclass")).intValue()==9,
                    "factory route serializes HIGHWAY over fc2 as6 and SMALL fc7 as9");
            NativePath path=(NativePath)r.runtime.last(".v0")[2];
            for(int i=0;i<links.size();i++){
                Map<String,Object> link=links.get(i);NativePath.Link nativeLink=path.getSegmentInfo(i).getLinkInfo(0);
                check(((Number)link.get("formway")).intValue()==1&&((Number)link.get("ownership")).intValue()==0&&((Number)link.get("direction")).intValue()==1,"factory route receives donor formway/ownership/direction");
                check(((Number)link.get("laneNum")).intValue()==0&&((Number)link.get("adminCode")).intValue()==990001,"each serialized donor link carries lanes0 and adminCode990001");
                check(Boolean.TRUE.equals(link.get("hasMultiOut"))&&((Number)link.get("speedLimit")).intValue()==0,"factory route receives donor multi-out and zero static speed limit despite Yandex90");
                check((i==0?"Real highway":"Real street").equals(link.get("roadname"))&&((Number)link.get("len")).intValue()>0,"source road name and metric length survive donor profile");
                check(nativeLink.getURID()==372&&nativeLink.get64TopoID().longValue()==((Number)link.get("linkID")).longValue(),"original ODD sender reads URID372 from the same native link/identity");
                List<NativePath.IntPoint> points=nativeLink.getPoints();double[] a=Coordinates.toSdk(input.route.lon[i],input.route.lat[i],Coordinates.Frame.valueOf(frame.name()));double[] b=Coordinates.toSdk(input.route.lon[i+1],input.route.lat[i+1],Coordinates.Frame.valueOf(frame.name()));
                check(points.size()==2&&points.get(0).lon==Math.round(a[0]*3600000)&&points.get(0).lat==Math.round(a[1]*3600000)
                        &&points.get(1).lon==Math.round(b[0]*3600000)&&points.get(1).lat==Math.round(b[1]*3600000),"real route geometry reaches native path in selected frame exactly once");
            }
            NativeModels.NaviInfo navi=(NativeModels.NaviInfo)r.runtime.last(".v0")[1];
            check(navi.curSegIdx==0&&navi.curLinkSpeed==85&&historyOnWire(r)==85&&roadClassOnWire(r)==6,"HIGHWAY6 / history85 reaches actual original TrafficInfo and SpeedIntersection wire");
            check(((Integer)r.runtime.last(".J")[1])==0,"profile overrides Yandex legal limit90 with donor zero");
            NativeModels.LocInfo fix=(NativeModels.LocInfo)r.runtime.last(".P")[1];
            check(fix.matchInfo.get(0).roadClass==6&&fix.matchInfo.get(0).formway==1&&fix.matchInfo.get(0).ownership==0,"current highway location matches serialized donor class and attributes");
            double[] matched=Coordinates.toSdk(input.lon,input.lat,Coordinates.Frame.valueOf(frame.name())),raw=Coordinates.toSdk(input.rawLon,input.rawLat,Coordinates.Frame.valueOf(frame.name()));
            check(fix.matchInfo.get(0).stPos.lon==matched[0]&&fix.matchInfo.get(0).stPos.lat==matched[1]&&fix.gpsPos.lon==raw[0]&&fix.gpsPos.lat==raw[1],"matched and raw fixes preserve independent selected coordinate frame");
            Map<String,Object> region=r.transport.fields(Topics.SD_REGION,"regionalAndWeather");
            check(((Number)region.get("regionId")).longValue()==990001&&((Number)region.get("provinceCode")).longValue()==990001
                    &&((Number)region.get("cityCode")).longValue()==990001&&((Number)region.get("countryCode")).longValue()==1,"original Region path emits 990001 three times and country1");
            check(oddOnWire(r)==Integer.MAX_VALUE,"actual open-city set including URID372 yields original ODD MAX");
            r.runtime.advance(1001);NativeNavigationInput street=r.input(input.routeId,1,false);street.scalar=1;street.lon=37.601;street.lat=55.701;street.speedLimitMps=25;r.observe(street);
            navi=(NativeModels.NaviInfo)r.runtime.last(".v0")[1];fix=(NativeModels.LocInfo)r.runtime.last(".P")[1];
            check(navi.curSegIdx==1&&navi.curLinkIdx==0&&navi.curLinkSpeed==25&&historyOnWire(r)==25&&roadClassOnWire(r)==9,"first SMALL link changes original TrafficInfo to9 and SpeedIntersection to25 on same path");
            check(fix.matchInfo.get(0).roadClass==9,"current street location class agrees with serialized class9 and TrafficInfo9");
            check(((Integer)r.runtime.last(".J")[1])==0&&r.session.pathId()==path.getPathID()&&r.transport.routes.size()==1,"section transition retains same path and donor legal limit0");
            int oddPackets=Collections.frequency(r.transport.order,Topics.ADV_AUTO_FUN_INFO);r.session.openCitiesUpdated(Arrays.asList(1,373));r.runtime.advance(1001);NativeNavigationInput excluded=street.copy();excluded.monotonicMs=r.runtime.now;excluded.stamp=r.runtime.now;r.observe(excluded);
            check(Collections.frequency(r.transport.order,Topics.ADV_AUTO_FUN_INFO)>oddPackets&&oddOnWire(r)==0,"nonempty actual open-city set without372 emits original ODD0 (protobuf zero default)");
            Map<String,Object> audit=r.audits.get(0);
            check("stock-like-by-road".equals(audit.get("compatibilityProfile"))&&"EXPERIMENTAL_STOCK_LIKE_BY_ROAD".equals(audit.get("historySpeedPolicy")),"route audit exposes named donor profile and per-road history policy");
            check(((Map<?,?>)audit.get("attributeEvidence")).containsKey("compatibilityProfile"),"route audit records donor values and provenance");
            r.runtime.advance(1001);NativeNavigationInput off=street.copy();off.monotonicMs=r.runtime.now;off.stamp=r.runtime.now;off.positionOnRoute=false;off.routeStatus="NOT_ON_ROUTE";off.scalar=Double.NaN;off.timeToFinishS=Double.NaN;r.observe(off);
            check(historyOnWire(r)==0,"per-road donor history resets on actual off-route wire without scalar or ETA");r.session.close();
        }
    }
    static void incoherentProgressClearsHistory(){
        for(String missing:new String[]{"eta","scalar","stale"}){
            Rig r=new Rig(dev.denza.adaslink.yandexnative.YandexNativeAdapter.Policy.STOCK_LIKE_BY_ROAD);
            NativeNavigationInput first=r.input("incoherent-"+missing,1,false);first.route=mixedDonorRoute(first.routeId);r.observe(first);
            NativeModels.NaviInfo admitted=(NativeModels.NaviInfo)r.runtime.last(".v0")[1];check(historyOnWire(r)==85,"coherent initial highway history85 before "+missing);
            r.runtime.advance(100);NativeNavigationInput invalid=r.input(first.routeId,1,false);invalid.scalar=1.1;invalid.lon=37.6011;invalid.lat=55.7011;
            if("scalar".equals(missing))invalid.scalar=Double.NaN;else invalid.timeToFinishS=Double.NaN;
            if("stale".equals(missing))invalid.fixAgeMs=1600;
            r.observe(invalid);NativeModels.NaviInfo reset=(NativeModels.NaviInfo)r.runtime.last(".v0")[1];
            check(reset.curLinkSpeed==0&&historyOnWire(r)==0,"incoherent "+missing+" clears history before normal one-second progress cadence");
            check(reset.pathID==admitted.pathID&&reset.curSegIdx==admitted.curSegIdx&&reset.curLinkIdx==admitted.curLinkIdx
                    &&reset.curPointIdx==admitted.curPointIdx&&reset.linkRemainDist==admitted.linkRemainDist
                    &&reset.routeRemain.dist==admitted.routeRemain.dist&&reset.routeRemain.time==admitted.routeRemain.time,"history reset reuses admitted envelope without inventing "+missing+" progress");
            long updates=r.runtime.count(".v0");r.observe(invalid.copy());check(r.runtime.count(".v0")==updates,"repeated incoherent "+missing+" coalesces zero reset");
            r.runtime.advance(100);NativeNavigationInput fresh=r.input(first.routeId,1,false);fresh.scalar=1.1;fresh.lon=37.6011;fresh.lat=55.7011;fresh.timeToFinishS=Double.NaN;r.observe(fresh);
            check(historyOnWire(r)==0&&((NativeModels.LocInfo)r.runtime.last(".P")[1]).matchInfo.get(0).roadClass==9,"fresh street without ETA retains zero history and truthful location after "+missing);
            updates=r.runtime.count(".v0");r.observe(fresh.copy());check(r.runtime.count(".v0")==updates,"repeated fresh missing ETA coalesces reset after "+missing);
            r.runtime.advance(1001);NativeNavigationInput street=r.input(first.routeId,1,false);street.scalar=1.1;r.observe(street);
            check(historyOnWire(r)==25&&((NativeModels.NaviInfo)r.runtime.last(".v0")[1]).curSegIdx==1,"coherent recovered street restores its own history25 after "+missing);
            r.runtime.advance(1001);r.observe(r.input(first.routeId,1,false));check(historyOnWire(r)==85,"coherent recovered highway restores its own history85 after "+missing);
            check(r.session.pathId()==admitted.pathID&&r.transport.routes.size()==1,"incoherent recovery retains existing route lifetime");r.session.close();
        }
    }
    static void closedCityLongLookaheadRetainsFactoryOdd(){
        Rig r=new Rig(dev.denza.adaslink.yandexnative.YandexNativeAdapter.Policy.STOCK_LIKE_BY_ROAD);r.session.openCitiesUpdated(Arrays.asList(1,373));
        NativeNavigationInput input=r.input("closed-city-long-lookahead",1,false);
        input.route=new YandexRoute(input.routeId,new double[]{37.6,37.63,37.66},new double[]{55.7,55.7,55.7},
                Arrays.asList(new YandexRoute.Section(0,1,"STRAIGHT",null,100,85,"HIGHWAY"),new YandexRoute.Section(1,2,"STRAIGHT",null,100,85,"HIGHWAY")),null,null,null,null,null,null);
        r.observe(input);check(oddOnWire(r)==Integer.MAX_VALUE,"original ODD lookahead over2000m returns MAX before closed-city membership check; URID372 exclusion does not universally imply zero");r.session.close();
    }

}
