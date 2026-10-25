package dev.denza.hud.probe;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dev.denza.adaslink.*;
import dev.denza.adaslink.stocknative.StockRuntime;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Actual product coordinator/runtime on deterministic Android/Binder boundaries.
 * Assertions concern lifecycle and admission, not historical packet goldens or car acceptance. */
public final class NativeProductRuntimeTest {
    static int checks,serial;static File root;
    static final String BOOT="56e1b912-893e-41bc-9e81-a6c38eb7d28d";
    static final String GEOM="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    static final String INVENTORY="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static final class Rig {
        final Context context;final LiveMapBridge bridge;final String reader=UUID.randomUUID().toString();
        final Map<String,Object> arm=Json.object();String consumer;long seq,epoch;String routeId="route-a";double vehicleKmh=18;
        OfflineMapGate.Subject subject;
        Rig()throws Exception{
            Handler.reset();context=new Context(new File(root,"case-"+(++serial)));
            bridge=new LiveMapBridge(context,new Handler(Looper.getMainLooper()),BOOT,()->1792836000000L+SystemClock.now);
            Handler.advance(0);consumer=(String)read("denza-adas-consumer.json").get("consumerSession");
            arm.put("schema","denza-adas-live-arm-v1");arm.put("bootId",BOOT);arm.put("consumerSession",consumer);
            arm.put("readerSession",reader);arm.put("armId",UUID.randomUUID().toString());arm.put("issuedAtElapsedMs",SystemClock.now);arm.put("expiresAtElapsedMs",SystemClock.now+60000);
            arm.put("stockPackage",LiveSessionLease.STOCK_PACKAGE);arm.put("stockDisabled",true);
        }
        void write(String name,Map<String,Object> value)throws Exception{Files.write(new File(context.files,name).toPath(),Json.write(value).getBytes(StandardCharsets.UTF_8));}
        @SuppressWarnings("unchecked")Map<String,Object> read(String name)throws Exception{return (Map<String,Object>)Json.parse(new String(Files.readAllBytes(new File(context.files,name).toPath()),StandardCharsets.UTF_8));}
        Map<String,Object> state()throws Exception{return read("denza-adas-live-state.json");}
        void subject(String id){if(!Objects.equals(routeId,id))epoch++;routeId=id;subject=new OfflineMapGate.Subject(id,epoch,id==null?null:GEOM);bridge.sourceRoute(subject);offline();}
        void offline(){if(subject!=null)bridge.offlineMap(OfflineMapGate.Snapshot.available(subject,SystemClock.now,INVENTORY));}
        void evidence(boolean enabled)throws Exception{
            offline();Map<String,Object> row=Json.object(),values=Json.object();
            row.put("schema",AdasGateSnapshot.SCHEMA);row.put("bootId",BOOT);row.put("consumerSession",consumer);row.put("readerSession",reader);row.put("sequence",++seq);
            row.put("startedAtElapsedMs",SystemClock.now);row.put("completedAtElapsedMs",SystemClock.now);row.put("startedAtUptimeMs",SystemClock.now);row.put("completedAtUptimeMs",SystemClock.now);
            for(int fid:AdasGateSnapshot.ALL_FIDS){long value=fid==AdasGateSnapshot.HNP_CONFIG?3:fid==AdasGateSnapshot.UNP_CONFIG||fid==AdasGateSnapshot.E2E_CONFIG?1:fid==AdasGateSnapshot.NOA_UI_TYPE||fid==AdasGateSnapshot.TIP_ISLAND_SUPPORT?2:fid==AdasGateSnapshot.HNP_SWITCH&&enabled?1:0;
                Map<String,Object> v=Json.object();v.put("status","ok");v.put("transact",5L);v.put("value",value);values.put(Integer.toString(fid),v);}
            row.put("values",values);Map<String,Object> speed=Json.object();speed.put("status","ok");speed.put("device",1013L);speed.put("fid",-1807745016L);speed.put("transact",7L);speed.put("unit","km/h");speed.put("value",vehicleKmh);speed.put("startedAtElapsedMs",SystemClock.now);speed.put("completedAtElapsedMs",SystemClock.now);row.put("vehicleSpeed",speed);write("denza-adas-gates.json",row);
            Map<String,Object> isolation=Json.object();isolation.put("schema","denza-adas-stock-isolation-v1");isolation.put("bootId",BOOT);isolation.put("consumerSession",consumer);isolation.put("readerSession",reader);isolation.put("stockAbsent",true);isolation.put("startedAtElapsedMs",SystemClock.now);isolation.put("completedAtElapsedMs",SystemClock.now);write("denza-adas-stock-isolation.json",isolation);
        }
        void arm()throws Exception{subject(routeId);evidence(true);write("denza-adas-live-arm.json",arm);Handler.advance(1000);}
        NativeNavigationInput input(boolean route){NativeNavigationInput o=new NativeNavigationInput();o.monotonicMs=SystemClock.now;o.routeEpoch=epoch;o.routeId=routeId;o.offlineGeometrySha256=routeId==null?null:GEOM;o.mode=routeId==null?"off":"guidance";o.positionOnRoute=true;o.scalar=.2;o.timeToFinishS=50;o.locationClass="FINE";o.lon=37.6002;o.lat=55.7002;o.rawLon=37.60019;o.rawLat=55.70019;o.speedMps=5;o.heading=45;o.stamp=SystemClock.now;o.rawAtElapsedMs=SystemClock.now;
            if(route&&routeId!=null)o.route=new YandexRoute(routeId,new double[]{37.6,37.601,37.602},new double[]{55.7,55.701,55.702},Collections.singletonList(new YandexRoute.Section(0,2,"STRAIGHT",null,60)),null,null,null,null,null,null);return o;}
        void observe(boolean route)throws Exception{observe(input(route));}
        void observe(NativeNavigationInput o)throws Exception{long wall=1792836000000L+SystemClock.now;bridge.observe(o,FixFreshness.capture(SystemClock.now,wall,SystemClock.now,SystemClock.now,wall),null);Handler.advance(0);}
        long route()throws Exception{observe(true);long path=bridge.status().pathId;check(path>0,"new native route admitted: "+state());return path;}
        void close()throws Exception{Files.deleteIfExists(new File(context.files,"denza-adas-live-arm.json").toPath());Handler.advance(1000);}
        void graceful()throws Exception{Map<String,Object> request=new LinkedHashMap<>(arm);request.put("schema","denza-adas-live-stop-v1");request.put("requestedAtElapsedMs",SystemClock.now);write(LiveMapBridge.STOP,request);}
        void advanceEvidence(long duration)throws Exception{for(long elapsed=0;elapsed<duration;elapsed+=250){evidence(true);Handler.advance(Math.min(250,duration-elapsed));}}
    }
    public static void main(String[] args)throws Exception{
        root=new File(args[0]);erase(root);root.mkdirs();
        runtimeCancellation();
        freshnessRecovery();gracefulShutdown();cityRequestRecovery();coordinateArm();inflightGeneration();refreshLatency();
        Rig disarmed=new Rig();check(Boolean.TRUE.equals(disarmed.state().get("quiescent")),"unarmed owns no transports");check("stocknative".equals(disarmed.state().get("sender")),"fixed sender identity");
        Map<String,Object> invalid=new LinkedHashMap<>(disarmed.arm);invalid.put("variant",Json.object());boolean rejected=false;
        try{LiveSessionLease.read(Json.write(invalid),BOOT,disarmed.consumer,SystemClock.now);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"legacy variant arm rejected");
        Rig r=new Rig();r.arm();long path=r.route();LiveMapTransport old=LiveMapTransport.latest;AdasPassiveClient oldAnswers=AdasPassiveClient.answers;AdasPassiveClient oldCore=AdasPassiveClient.core;
        Object nativeSession=field(r.bridge,"session");
        dev.denza.adaslink.yandexnative.YandexNativeAdapter.Policy productionPolicy=(dev.denza.adaslink.yandexnative.YandexNativeAdapter.Policy)field(nativeSession,"routePolicy");
        ProductNativeEnvironment productionEnvironment=(ProductNativeEnvironment)field(r.bridge,"environment");
        check(productionPolicy.compatibilityCn&&productionPolicy.historySpeed==dev.denza.adaslink.yandexnative.YandexNativeAdapter.HistorySpeedPolicy.EXPERIMENTAL_FIXED_25,"LiveMapBridge wires compatibility donor policy into actual NativeMapSession");
        check(productionEnvironment.hasMapDataService()&&productionEnvironment.getAdcodeByLonLat(37.6,55.7)==110000&&Integer.valueOf(110000).equals(productionEnvironment.getCityProvince(110000)),"LiveMapBridge wires matching declared Region donor environment");
        check(old.count(Topics.ROUTE)>0,"factory route reaches owned transport");
        check(AdasPassiveClient.answers!=AdasPassiveClient.core,"subscription maps have separate reader ownership");
        oldAnswers.answer(Topics.PATH_SELECT_CONFIRM,StockMessages.pathSelectConfirm(path,200));Handler.advance(0);r.observe(false);
        check(r.bridge.status().confirmedPathId==path,"native core ACK state drives overlay");
        long speedAt=fieldLong(r.bridge,"gpsSpeedAt");
        NativeNavigationInput simulated=r.input(false);simulated.simulated=true;simulated.speedMps=70;r.observe(simulated);
        check(fieldLong(r.bridge,"gpsSpeedAt")==speedAt,"simulation cannot refresh real-speed fallback");
        NativeNavigationInput aging=r.input(false);long now=SystemClock.now,wall=1792836000000L+now;
        r.bridge.observe(aging,FixFreshness.capture(now-1490,wall-1490,now,now,wall),null);Handler.advance(0);
        check(fieldLong(r.bridge,"gpsSpeedAt")==now-1490,"GPS fallback retains source age without extension");
        NativeNavigationInput futureRaw=r.input(false);futureRaw.rawAtElapsedMs=SystemClock.now+1;r.observe(futureRaw);
        NativeNavigationInput retained=(NativeNavigationInput)field(r.bridge,"latest");
        check(Double.isNaN(retained.rawLon),"future raw-GNSS sample omitted");
        NativeNavigationInput staleRaw=r.input(false);staleRaw.rawAtElapsedMs=SystemClock.now-1600;r.observe(staleRaw);
        check(Double.isNaN(((NativeNavigationInput)field(r.bridge,"latest")).rawLon),"raw-GNSS ages independently at consumption");
        r.observe(false);
        int nativeLocations=ProductNativeEnvironment.realSpeedReads;
        NativeNavigationInput offRoad=r.input(false);offRoad.positionOnRoute=false;offRoad.stamp++;
        r.observe(offRoad);check(ProductNativeEnvironment.realSpeedReads>nativeLocations,"off-route FINE fix keeps truthful coordinates without fabricated NaviInfo");
        r.evidence(false);Handler.advance(1000);check(!old.closed,"temporary gate gap retains same arm");
        r.evidence(true);r.observe(false);Handler.advance(300);check(!old.closed,"switch recovery stays in same core");
        int calls=old.topics.size();r.subject("route-b");
        // A replacement without geometry admission cannot release old route dynamics.
        r.bridge.offlineMap(OfflineMapGate.Snapshot.unavailable(r.subject,SystemClock.now,INVENTORY,"pending","pending"));
        Handler.advance(700);check(old.topics.size()==calls,"route replacement gap rejects old output without closing arm");
        check(!old.closed,"not-transacted admission rejection is nonfatal");
        r.evidence(true);long next=r.route();check(next!=path,"route replacement allocates independent native identity");
        oldAnswers.answer(Topics.PATH_SELECT_CONFIRM,StockMessages.pathSelectConfirm(path,200));Handler.advance(0);r.observe(false);
        check(!r.bridge.status().confirmed(),"old-path ACK cannot confirm replacement");
        r.subject(null);r.evidence(true);r.observe(false);int beforeIdle=old.count(Topics.NAVIGATION_STATUS);Handler.advance(1000);
        check(old.count(Topics.NAVIGATION_STATUS)>beforeIdle,"original idle status survives no-route withdrawal");check(!old.closed,"route stop does not tear down arm");
        r.close();check(old.closed&&oldAnswers.closed&&oldCore.closed,"arm teardown closes writer and both reader connections");
        int afterClose=old.topics.size();oldAnswers.answer(Topics.PATH_SELECT_CONFIRM,StockMessages.pathSelectConfirm(next,200));Handler.advance(2000);check(old.topics.size()==afterClose,"late ACK and pending timers cannot revive closed writer");
        r.arm.put("armId",UUID.randomUUID().toString());r.arm.put("issuedAtElapsedMs",SystemClock.now);r.arm.put("expiresAtElapsedMs",SystemClock.now+60000);r.subject("route-c");r.evidence(true);r.write("denza-adas-live-arm.json",r.arm);Handler.advance(1000);r.route();
        check(LiveMapTransport.latest!=old&&!LiveMapTransport.latest.closed,"new arm owns a fresh exclusive writer");check(old.topics.size()==afterClose,"rearm never lets old queued work use new writer");
        check(!Files.readAllLines(new File(r.context.files,"denza-adas-live.jsonl").toPath()).isEmpty(),"route/input policy journal present");r.close();
        System.out.println("NativeProductRuntimeTest: "+checks+" checks passed");
    }
    static void runtimeCancellation(){
        Handler.reset();List<String> effects=new ArrayList<>();AndroidStockRuntime runtime=new AndroidStockRuntime(error->{throw new AssertionError(error);});
        Object token=new String("token"),equalToken=new String("token");
        for(StockRuntime.Queue queue:StockRuntime.Queue.values())runtime.post(queue,new Object(),token,100,()->effects.add(queue.name()));
        runtime.removeToken(StockRuntime.Queue.MAIN,equalToken);check(runtime.pendingCount()==3,"tokens compare by identity");runtime.removeToken(StockRuntime.Queue.MAIN,token);check(runtime.pendingCount()==2,"token cancellation forgets occurrence metadata");
        runtime.close();check(runtime.pendingCount()==0&&!runtime.active(),"close clears all queues and metadata");Handler.advance(1000);check(effects.isEmpty(),"all three queues fenced on close");
        runtime.post(StockRuntime.Queue.MAIN,new Object(),null,0,()->effects.add("late"));Handler.advance(0);check(effects.isEmpty(),"post after close ignored");
        AndroidStockRuntime recoverable=new AndroidStockRuntime(error->{throw new AssertionError(error);});
        recoverable.post(StockRuntime.Queue.MMT,new Object(),null,0,()->{throw new dev.denza.adaslink.stocknative.NativeEnvironment.DataUnavailable("stale measured speed");});
        recoverable.post(StockRuntime.Queue.MMT,new Object(),null,1,()->effects.add("fresh"));Handler.advance(1);
        check(recoverable.active()&&effects.equals(Collections.singletonList("fresh")),"missing external data does not dispose native runtime");recoverable.close();
        effects.clear();AndroidStockRuntime scoped=new AndroidStockRuntime(error->{throw new AssertionError(error);});scoped.inputGeneration(1);
        scoped.post(StockRuntime.Queue.MAIN,new Object(),null,1,()->effects.add("obsolete mutation"));
        scoped.post(StockRuntime.Queue.SOMEIP,new Object(),null,0,()->{scoped.inputGeneration(2);scoped.post(StockRuntime.Queue.MMT,new Object(),null,0,()->effects.add("obsolete nested mutation"));});
        Handler.advance(2);check(effects.isEmpty(),"stale queued and inherited nested callbacks cannot mutate new route state");
        final dev.denza.adaslink.stocknative.StockTask[] status=new dev.denza.adaslink.stocknative.StockTask[1];
        status[0]=new dev.denza.adaslink.stocknative.StockTask("k.h.a0.c.c.a.e0$d","navigation status",()->{effects.add("global status");scoped.post(StockRuntime.Queue.MMT,status[0],null,1000,status[0]);});
        scoped.post(StockRuntime.Queue.MMT,status[0],null,0,status[0]);scoped.inputGeneration(3);Handler.advance(1001);
        check(effects.size()==2,"original process-global status timer survives route generation changes");scoped.close();
    }
    static void freshnessRecovery()throws Exception{
        Rig r=new Rig();r.arm();r.route();LiveMapTransport wire=LiveMapTransport.latest;
        int initial=wire.count(Topics.SD_VEHICLE_LOCATION);check(initial>0,"fresh location reaches wire");
        NativeNavigationInput coarse=r.input(false);coarse.locationClass="COARSE";r.observe(coarse);r.evidence(true);Handler.advance(1000);
        check(wire.count(Topics.SD_VEHICLE_LOCATION)==initial,"coarse fix cancels old stationary location output");
        check(!wire.closed,"bad fix retains authorized session");r.evidence(true);r.observe(false);
        check(wire.count(Topics.SD_VEHICLE_LOCATION)>initial,"fresh FINE fix resumes same sender");
        int resumed=wire.count(Topics.SD_VEHICLE_LOCATION);
        NativeNavigationInput queued=r.input(false);long now=SystemClock.now,wall=1792836000000L+now;
        r.bridge.observe(queued,FixFreshness.capture(now,wall,now,now,wall),null);Handler.block(1800);r.evidence(true);Handler.advance(0);
        check(wire.count(Topics.SD_VEHICLE_LOCATION)==resumed,"queued location cannot gain freshness while worker lags");
        check(!wire.closed,"queued stale fix does not revoke arm");r.evidence(true);r.observe(false);
        check(wire.count(Topics.SD_VEHICLE_LOCATION)>resumed,"fresh input recovers after queue lag");
        Map<String,Object> gate=r.read("denza-adas-gates.json");gate.remove("vehicleSpeed");gate.put("sequence",++r.seq);r.write("denza-adas-gates.json",gate);
        int beforeMissing=wire.count(Topics.SD_VEHICLE_LOCATION);Handler.block(1);r.observe(false);Handler.advance(1000);
        check(!wire.closed&&wire.count(Topics.SD_VEHICLE_LOCATION)==beforeMissing,"missing measured speed suppresses output without ending arm");
        r.evidence(true);r.observe(false);check(wire.count(Topics.SD_VEHICLE_LOCATION)>beforeMissing,"actual speed recovery resumes output");
        for(int invalid=0;invalid<2;invalid++){
            NativeNavigationInput bad=r.input(false);if(invalid==0)bad.lon=181;else bad.speedMps=Double.NaN;
            int beforeBad=wire.count(Topics.SD_VEHICLE_LOCATION);r.observe(bad);r.advanceEvidence(250);
            check(!wire.closed&&"PAUSED".equals(r.state().get("state")),"invalid finite coordinates/NaN speed pause same arm");
            check(wire.count(Topics.SD_VEHICLE_LOCATION)==beforeBad,"invalid input cannot release old stationary location");
            r.evidence(true);r.observe(false);r.advanceEvidence(1000);r.observe(false);check(!wire.closed&&wire.count(Topics.SD_VEHICLE_LOCATION)>beforeBad,"fresh input resumes after invalid coordinates/speed within original throttle");
        }
        r.close();
    }
    static void cityRequestRecovery()throws Exception{
        AdasPassiveClient.nextOddResult=-200;Rig retry=new Rig();retry.arm();retry.route();AdasPassiveClient client=AdasPassiveClient.answers;
        check(client.oddRequests==1,"failed city read is attempted once initially");client.oddResult=0;
        for(int tick=0;tick<24;tick++){retry.evidence(true);retry.observe(false);Handler.advance(250);}
        check(client.oddRequests==2,"transient city read failure retries after backoff and succeeds");
        for(int tick=0;tick<24;tick++){retry.evidence(true);retry.observe(false);Handler.advance(250);}
        check(client.oddRequests==2,"successful city read consumes epoch without further retry");retry.close();
        Rig permanent=new Rig();permanent.arm();permanent.route();client=AdasPassiveClient.answers;
        for(int tick=0;tick<64;tick++){permanent.evidence(true);permanent.observe(false);Handler.advance(250);}
        check(client.oddRequests==3,"permanent city read failure is bounded to three attempts per epoch");
        client.answer(0x0001000D000D0002L,new byte[0]);Handler.advance(0);client.answer(0x0001000D000D0001L,new byte[0]);Handler.advance(0);
        check(client.oddRequests==4,"service13 availability cycle opens a fresh bounded request budget");permanent.close();AdasPassiveClient.nextOddResult=0;
    }
    @SuppressWarnings("unchecked")static void coordinateArm()throws Exception{
        Rig r=new Rig();r.arm.put("coordinateFrame","NATIVE_GCJ_UNRESTRICTED_CANDIDATE");r.arm();r.route();LiveMapTransport wire=LiveMapTransport.latest;
        ProductNativeEnvironment env=(ProductNativeEnvironment)field(r.bridge,"environment");double[] expected=Coordinates.toSdk(37.6002,55.7002,Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE);
        check(env.locationLon==expected[0]&&env.locationLat==expected[1],"environment lastLocation uses selected source frame once");
        Map<String,Object> position=null;for(int i=wire.topics.size()-1;i>=0;i--)if(wire.topics.get(i)==Topics.SD_VEHICLE_LOCATION){position=(Map<String,Object>)Topics.schema(Topics.SD_VEHICLE_LOCATION).decode(wire.payloads.get(i)).get("sdVehicleLocation");break;}
        check(position!=null&&((Number)position.get("locationLongitudeAssociateRoad")).doubleValue()==env.locationLon,"environment and actual core output share one coordinate frame");
        check("NATIVE_GCJ_UNRESTRICTED_CANDIDATE".equals(r.state().get("coordinateFrame"))&&"NATIVE_GCJ_UNRESTRICTED_CANDIDATE".equals(r.state().get("coordinateFrameMode")),"readiness state exposes selected arm frame");
        NativeNavigationInput outsideFix=r.input(false);outsideFix.lon=41;int before=wire.count(Topics.SD_VEHICLE_LOCATION);r.observe(outsideFix);r.advanceEvidence(250);
        check(!wire.closed&&"PAUSED".equals(r.state().get("state"))&&wire.count(Topics.SD_VEHICLE_LOCATION)==before,"outside native fix pauses same arm before transformation/core");
        check(env.locationLon==expected[0],"outside native fix cannot poison environment location");r.evidence(true);r.observe(false);r.advanceEvidence(1000);r.observe(false);
        check(!wire.closed&&wire.count(Topics.SD_VEHICLE_LOCATION)>before,"fresh in-envelope fix resumes same native arm");
        r.subject("outside-route");r.evidence(true);NativeNavigationInput outsideRoute=r.input(true);outsideRoute.route=new YandexRoute("outside-route",new double[]{41,41.001},new double[]{55.7,55.701},Collections.singletonList(new YandexRoute.Section(0,1,"STRAIGHT",null,30)),null,null,null,null,null,null);int routes=wire.count(Topics.ROUTE);r.observe(outsideRoute);
        check(!wire.closed&&"BLOCKED".equals(r.state().get("state"))&&wire.count(Topics.ROUTE)==routes,"outside native route blocks admission without replacing runtime");r.advanceEvidence(1000);
        check(wire.count(Topics.ROUTE)==routes,"blocked replacement cannot publish a route through old subject admission");r.close();
    }
    @SuppressWarnings("unchecked")static void inflightGeneration()throws Exception{
        Rig r=new Rig();r.vehicleKmh=0;r.arm.put("coordinateFrame","ALTERNATE_ROUTES");r.arm();NativeNavigationInput first=r.input(true);first.route=twoSections(r.routeId);r.observe(first);LiveMapTransport wire=LiveMapTransport.latest;r.advanceEvidence(1000);
        final long[] pathB={-1};final int[] boundary={-1};wire.beforeAdmissionTopic=Topics.SD_VEHICLE_LOCATION;
        wire.beforeAdmission=()->{try{
            r.subject("route-b");r.evidence(true);NativeNavigationInput b=r.input(true);b.route=twoSections(r.routeId);b.speedMps=0;b.lon=37.6015;b.lat=55.7015;b.rawLon=37.60149;b.rawLat=55.70149;r.observe(b);
            pathB[0]=r.bridge.status().pathId;boundary[0]=wire.topics.size();
        }catch(Exception error){throw new AssertionError(error);}};
        NativeNavigationInput a=r.input(false);a.speedMps=0;a.lon=37.6004;a.lat=55.7004;a.rawLon=37.60039;a.rawLat=55.70039;r.observe(a);
        check(pathB[0]>0&&boundary[0]>=0&&wire.topics.size()==boundary[0],"A payload already at transport boundary cannot enter accepted B after swap");
        int locations=wire.count(Topics.SD_VEHICLE_LOCATION),lanes=wire.count(Topics.SD_AHEAD_LANES),odd=wire.count(Topics.ADV_AUTO_FUN_INFO),statuses=wire.count(Topics.NAVIGATION_STATUS);
        r.advanceEvidence(1000);
        r.evidence(true);NativeNavigationInput b=r.input(false);b.speedMps=0;b.lon=37.6015;b.lat=55.7015;b.rawLon=37.60149;b.rawLat=55.70149;r.observe(b);
        int recreated=wire.count(Topics.SD_VEHICLE_LOCATION);r.advanceEvidence(1000);
        check(recreated>locations&&wire.count(Topics.SD_VEHICLE_LOCATION)>recreated,"fresh B input recreates its stationary timer after original throttle");
        check(wire.count(Topics.ADV_AUTO_FUN_INFO)>odd,"B actual-speed input recreates stationary ODD timer");
        check(wire.count(Topics.NAVIGATION_STATUS)>statuses,"global status timer remains live after B swap");
        r.evidence(true);b=r.input(false);b.speedMps=0;b.lon=37.6015;b.lat=55.7015;b.rawLon=37.60149;b.rawLat=55.70149;r.observe(b);r.advanceEvidence(250);
        check(wire.count(Topics.SD_AHEAD_LANES)>lanes,"B path recreates lane query/flush after generation change");
        for(int i=boundary[0];i<wire.topics.size();i++)if(wire.topics.get(i)==Topics.SD_VEHICLE_LOCATION){Map<String,Object> point=(Map<String,Object>)Topics.schema(Topics.SD_VEHICLE_LOCATION).decode(wire.payloads.get(i)).get("sdVehicleLocation");
            check(((Number)point.get("curSDRouteID")).longValue()==pathB[0]&&((Number)point.get("locationLongitudeAssociateRoad")).doubleValue()==37.6015,"post-swap position belongs to B and its WGS control frame");}
        long previous=pathB[0];OfflineMapGate.Subject sameB=r.subject;
        r.bridge.sourceRoute(new OfflineMapGate.Subject(null,r.epoch,null));r.bridge.sourceRoute(sameB);
        r.evidence(true);b=r.input(false);b.speedMps=0;b.lon=37.6015;b.lat=55.7015;b.rawLon=37.60149;b.rawLat=55.70149;r.observe(b);
        check(r.bridge.status().pathId!=previous&&r.bridge.status().pathId>0,"withdrawal/reappearance gives same Yandex ID a fresh native lifetime/path");
        check("WGS84".equals(r.state().get("coordinateFrame"))&&((Number)r.state().get("coordinateRouteOrdinal")).longValue()==2,"source lifetime restart retains Yandex frame/ordinal association");
        int restartedLanes=wire.count(Topics.SD_AHEAD_LANES);r.advanceEvidence(1000);r.evidence(true);b=r.input(false);b.speedMps=0;b.lon=37.6015;b.lat=55.7015;b.rawLon=37.60149;b.rawLat=55.70149;r.observe(b);r.advanceEvidence(1000);
        check(wire.count(Topics.SD_AHEAD_LANES)>restartedLanes,"fresh source lifetime lets original v0 recreate its lane timer");
        r.close();
    }
    static YandexRoute twoSections(String id){return new YandexRoute(id,new double[]{37.6,37.601,37.602},new double[]{55.7,55.701,55.702},Arrays.asList(new YandexRoute.Section(0,1,"STRAIGHT",null,30),new YandexRoute.Section(1,2,"STRAIGHT",null,30)),null,null,null,null,null,null);}
    static void refreshLatency()throws Exception{
        Rig fast=new Rig();fast.arm();fast.evidence(true);fast.context.pm.nextReadDelayMs=20;fast.route();Handler.advance(0);LiveMapTransport wire=LiveMapTransport.latest;
        check(wire.count(Topics.ROUTE)>0&&wire.count(Topics.SD_VEHICLE_LOCATION)>0,"real refresh latency cannot make its new readiness snapshot look future");fast.close();
        Rig slow=new Rig();slow.arm();slow.evidence(true);slow.context.pm.nextReadDelayMs=1600;slow.context.pm.afterReadDelay=()->{try{slow.evidence(true);}catch(Exception error){throw new AssertionError(error);}};slow.route();Handler.advance(0);wire=LiveMapTransport.latest;
        check(wire.count(Topics.ROUTE)>0&&!wire.closed,"static route still admits after bounded slow refresh");
        check(wire.count(Topics.SD_VEHICLE_LOCATION)==0,"slow refresh cannot extend source-fix/raw-slot lease before native consumption");
        slow.evidence(true);slow.observe(false);Handler.advance(0);check(wire.count(Topics.SD_VEHICLE_LOCATION)>0&&!wire.closed,"fresh source recovers after refresh latency without a new arm");slow.close();
        Rig fallback=new Rig();fallback.arm();fallback.evidence(true);NativeNavigationInput input=fallback.input(true);input.fixAgeMs=200;fallback.context.pm.nextReadDelayMs=700;fallback.bridge.observe(input,null,null);Handler.advance(0);Handler.advance(0);wire=LiveMapTransport.latest;
        check(wire.count(Topics.SD_VEHICLE_LOCATION)>0,"fallback fix age remains bound to immutable input clock and is not counted twice");fallback.close();
    }
    static void gracefulShutdown()throws Exception{
        Rig cancel=new Rig();cancel.arm();cancel.route();LiveMapTransport wire=LiveMapTransport.latest;
        Map<String,Object> fractional=new LinkedHashMap<>(cancel.arm);fractional.put("schema","denza-adas-live-stop-v1");fractional.put("requestedAtElapsedMs",SystemClock.now+.5);cancel.write(LiveMapBridge.STOP,fractional);cancel.advanceEvidence(1000);
        check(!wire.closed&&!"DRAINING".equals(cancel.state().get("state")),"fractional stop timestamp cannot enter terminal drain");
        int tunnels=wire.count(Topics.SD_TUNNEL),incidents=wire.count(Topics.SD_TRAFFIC_INCIDENT);cancel.graceful();cancel.advanceEvidence(2500);
        check(wire.closed,"explicit cancel drains and closes bounded sender");
        check(wire.count(Topics.SD_TUNNEL)>tunnels&&wire.count(Topics.SD_TRAFFIC_INCIDENT)>incidents,"original reset packets precede transport close");
        check(wire.statuses().get(wire.statuses().size()-1)==100L&&!wire.statuses().contains(110L),"normal cancellation finishes in100 without synthetic arrival");
        check("original terminal output sent".equals(cancel.state().get("stopDetail")),"terminal completion is distinct from forced disposal");
        Rig arrival=new Rig();arrival.arm();arrival.route();wire=LiveMapTransport.latest;
        NativeNavigationInput end=arrival.input(false);end.finished=true;arrival.observe(end);arrival.graceful();arrival.advanceEvidence(4500);
        List<Long> status=wire.statuses();int n=status.size();
        check(wire.closed&&n>=4&&status.subList(n-4,n).equals(Arrays.asList(110L,110L,110L,100L)),"original arrival110x3 then100 survives explicit close");
        Rig revoke=new Rig();revoke.arm();revoke.route();wire=LiveMapTransport.latest;revoke.graceful();Handler.advance(1000);
        Files.delete(new File(revoke.context.files,LiveMapBridge.ARM).toPath());int before=wire.topics.size();Handler.advance(2500);
        check(wire.closed&&wire.topics.size()==before,"revocation during drain immediately fences all terminal output");
        Rig expired=new Rig();expired.arm.put("expiresAtElapsedMs",SystemClock.now+2200);expired.arm();expired.route();wire=LiveMapTransport.latest;expired.graceful();expired.advanceEvidence(1500);
        check(wire.closed,"expiry during terminal drain closes immediately");for(long sent:wire.times)check(sent<((Number)expired.arm.get("expiresAtElapsedMs")).longValue(),"expired authorization never permits terminal packets");
        Rig fault=new Rig();fault.arm();fault.route();wire=LiveMapTransport.latest;fault.graceful();fault.advanceEvidence(1000);int beforeFault=wire.topics.size();wire.ready=false;Handler.advance(250);
        check(wire.closed&&wire.topics.size()==beforeFault,"transport failure aborts drain without bypassing admission");
    }
    static Object field(Object owner,String name)throws Exception{java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    static long fieldLong(Object owner,String name)throws Exception{return ((Number)field(owner,name)).longValue();}
    static void erase(File f){File[] list=f.listFiles();if(list!=null)for(File child:list)erase(child);f.delete();}
}
