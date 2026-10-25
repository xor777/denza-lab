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
        NativeMapSession session;double speed=10;
        public int adasType(){return 1;}public boolean tipIsland(){return true;}public boolean laneUi(){return false;}public boolean noaConfigured(){return true;}
        public void startClientOrSubscribe(Map<Long,Long[]> x){}public void stopClient(Map<Long,Long[]> x){}public void unSubscribe(Map<Long,Long[]> x){}
        public void resetRouteSignals(){}public void setAvpSupport(int x){}public boolean someIpV2(){return false;}public boolean isNaving(){return session!=null&&session.navigating();}public boolean isKD(){return false;}public boolean isMainThread(){return false;}public double realSpeed(){return speed;}
        public boolean hasMapDataService(){return false;}public int getAdcodeByLonLat(double x,double y){return -1;}public int getTownAdcodeByLonLat(double x,double y){return -1;}public Integer getCityProvince(int x){return null;}
        public long queryAppointLanesInfo(int a,int b,int c){return -1;}public String cachedOpenCityJson(){return "";}public void writeCachedOpenCityJson(String x){}public List<Integer> avpMapIds(String id){return Collections.emptyList();}
        public double distanceBetweenPoints(double a,double b,double c,double d){return Coordinates.distance(a,b,c,d);}public NativeModels.Coord2DDouble lastLocation(){return null;}public void setLeaveTunnelDist(int x){}public void setEnterTunnelDist(int x){}public String stringResource(String name){return dev.denza.adaslink.yandexnative.NativeResources.get(name,false);}
    }
    static final class Transport implements StockTransport {
        final List<byte[]> routes=new ArrayList<>();int locations;final Map<Long,Map<String,Object>> decoded=new HashMap<>();final List<Long> order=new ArrayList<>();
        public int fire(long topic,byte[] payload){order.add(topic);if(topic==Topics.ROUTE)routes.add(payload.clone());if(topic==Topics.SD_VEHICLE_LOCATION)locations++;if(topic==Topics.SD_VEHICLE_LOCATION||topic==Topics.TRAFFIC_INFO)decoded.put(topic,Topics.schema(topic).decode(payload));return 0;}
        @SuppressWarnings("unchecked")Map<String,Object> fields(long topic,String key){return (Map<String,Object>)decoded.get(topic).get(key);}
        public int startService(long topic){return 0;}public int stopService(long topic){return 0;}public int request(long topic,byte[] payload,boolean response){return 0;}
        @SuppressWarnings("unchecked")int routeType(){String raw=new String(routes.get(routes.size()-1),java.nio.charset.StandardCharsets.UTF_8);Map<String,Object> outer=(Map<String,Object>)Json.parse(raw.substring(raw.indexOf('{'),raw.lastIndexOf('}')+1));Map<String,Object> inner=(Map<String,Object>)Json.parse((String)outer.get("payload"));return ((Number)inner.get("RouteType")).intValue();}
    }
    static final class Rig {
        final Runtime runtime=new Runtime();final Environment environment=new Environment();final Transport transport=new Transport();long ids=2000;
        final NativeMapSession session;final List<Map<String,Object>> audits=new ArrayList<>();
        Rig(){this(LiveSessionLease.FrameMode.WGS84);}
        Rig(LiveSessionLease.FrameMode mode){session=new NativeMapSession(runtime,runtime,environment,transport,()->++ids,audits::add,mode);environment.session=session;session.start(true,true,true);session.clientInitialized(true);runtime.advance(0);}
        NativeNavigationInput input(String id,long epoch,boolean route){NativeNavigationInput o=new NativeNavigationInput();o.routeId=id;o.routeEpoch=epoch;o.mode="guidance";o.routeStatus="ON_ROUTE";o.positionOnRoute=true;o.offlineGeometrySha256="geometry-"+id;o.monotonicMs=runtime.now;o.stamp=runtime.now;o.fixAgeMs=0;o.locationClass="FINE";o.scalar=.3;o.timeToFinishS=30;o.lon=37.6003;o.lat=55.7003;o.rawLon=37.60029;o.rawLat=55.70029;o.speedMps=3;o.heading=45;
            if(route)o.route=new YandexRoute(id,new double[]{37.6,37.601},new double[]{55.7,55.701},Collections.singletonList(new YandexRoute.Section(0,1,"STRAIGHT",null,30)),null,null,null,null,null,null);return o;}
        void observe(NativeNavigationInput o){session.observe(o);runtime.advance(0);}
    }
    public static void main(String[] args){
        routeReasonsAndOffRoute();stationaryInvalidationAndRecovery();offRouteWireAfterStationary();queuedExpiry();limitClear();gracefulStop();branchActions();coordinateModes();rejectedPreparationIsMemoized();replacementWaitsForOwnProgress();
        System.out.println("NativeMapSessionTest: "+checks+" checks passed");
    }
    static void routeReasonsAndOffRoute(){
        Rig r=new Rig();r.observe(r.input("a",1,true));check(r.transport.routeType()==1,"ordinary guidance uses source RouteTypeCommon1, not the bench seed default0");
        r.observe(r.input("b",2,true));check(r.transport.routeType()==1,"ordinary identity replacement remains Common1 without inventing offcourse reason");
        NativeNavigationInput off=r.input("b",2,false);off.routeStatus="NOT_ON_ROUTE";off.positionOnRoute=false;off.scalar=Double.NaN;off.lon=37.61;off.lat=55.71;off.stamp++;
        long navi=r.runtime.count(".v0"),location=r.runtime.count(".P");r.observe(off);
        check(r.runtime.count(".b0")==1,"observed source deviation invokes reroute once");
        check(r.runtime.count(".v0")==navi,"off-route does not invent path progress");check(r.runtime.count(".P")==location+1,"current FINE off-route coordinate still reaches native location processing");
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
}
