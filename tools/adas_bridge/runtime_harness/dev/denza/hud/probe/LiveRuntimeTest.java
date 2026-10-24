package dev.denza.hud.probe;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dev.denza.adaslink.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Executes the production Android coordinator on deterministic clock and Binder boundaries. */
public final class LiveRuntimeTest {
    private static int checks,serial;
    private static File root;
    private static final String BOOT="56e1b912-893e-41bc-9e81-a6c38eb7d28d";
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static final class Rig {
        final Context context;final LiveMapBridge bridge;final String reader,armId;
        String consumer;long seq;final Map<String,Object> arm;
        boolean automaticOffline=true;
        long routeEpoch;
        OfflineMapGate.Subject offlineSubject;
        static final String GEOMETRY="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        static final String INVENTORY="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        static final String ALT_GEOMETRY="cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
        /** The gate reader's NaviSt view (arm variant naviSt), carried in each gates file when set. */
        Map<String,Object> naviSt;
        Rig()throws Exception {
            Handler.reset();context=new Context(new File(root,"session-"+(++serial)));
            reader=UUID.randomUUID().toString();armId=UUID.randomUUID().toString();arm=Json.object();
            bridge=new LiveMapBridge(context,new Handler(Looper.getMainLooper()),BOOT,()->1_792_836_000_000L+SystemClock.now);Handler.advance(0);
            consumer=(String)read("denza-adas-consumer.json").get("consumerSession");
            check(Boolean.TRUE.equals(state().get("quiescent")),"initial process has no sender: "+state());
            arm.put("schema","denza-adas-live-arm-v1");arm.put("bootId",BOOT);arm.put("consumerSession",consumer);
            arm.put("readerSession",reader);arm.put("armId",armId);arm.put("issuedAtElapsedMs",SystemClock.now);
            arm.put("expiresAtElapsedMs",SystemClock.now+60000);arm.put("stockPackage",LiveSessionLease.STOCK_PACKAGE);arm.put("stockDisabled",true);
        }
        /** The same app data and boot after the previous Navigator process died: a new process, the same arm. */
        Rig(Rig died)throws Exception {
            Handler.killAll();context=died.context;reader=died.reader;armId=died.armId;arm=died.arm;seq=died.seq;
            bridge=new LiveMapBridge(context,new Handler(Looper.getMainLooper()),BOOT,()->1_792_836_000_000L+SystemClock.now);
            consumer=died.consumer;
            automaticOffline=died.automaticOffline;routeEpoch=died.routeEpoch;offlineSubject=died.offlineSubject;
            if(offlineSubject!=null){bridge.sourceRoute(offlineSubject);offlineEvidence();}
        }
        void write(String name,Map<String,Object> value)throws Exception{Files.write(new File(context.files,name).toPath(),Json.write(value).getBytes(StandardCharsets.UTF_8));}
        @SuppressWarnings("unchecked") Map<String,Object> read(String name)throws Exception{return (Map<String,Object>)Json.parse(new String(Files.readAllBytes(new File(context.files,name).toPath()),StandardCharsets.UTF_8));}
        Map<String,Object> state()throws Exception{return read("denza-adas-live-state.json");}
        String journal()throws Exception{return new String(Files.readAllBytes(new File(context.files,"denza-adas-live.jsonl").toPath()),StandardCharsets.UTF_8);}
        void evidence(boolean on)throws Exception{evidence(on,SystemClock.now);}
        void evidence(boolean on,long at)throws Exception {
            if(automaticOffline)offlineEvidence();
            Map<String,Object> row=Json.object(),values=Json.object();
            row.put("schema",AdasGateSnapshot.SCHEMA);row.put("bootId",BOOT);row.put("consumerSession",consumer);row.put("readerSession",reader);row.put("sequence",++seq);
            row.put("startedAtElapsedMs",at);row.put("completedAtElapsedMs",at);
            row.put("startedAtUptimeMs",at);row.put("completedAtUptimeMs",at);
            for(int fid:AdasGateSnapshot.REQUIRED_FIDS){
                long value=fid==AdasGateSnapshot.HNP_CONFIG?3:fid==AdasGateSnapshot.UNP_CONFIG||fid==AdasGateSnapshot.E2E_CONFIG?1:
                    fid==AdasGateSnapshot.NOA_UI_TYPE?2:fid==AdasGateSnapshot.HNP_SWITCH&&on?1:0;
                Map<String,Object> v=Json.object();v.put("status","ok");v.put("transact",5L);v.put("value",value);values.put(Integer.toString(fid),v);
            }
            row.put("values",values);if(naviSt!=null)row.put(AdasGateSnapshot.NAVI_ST,naviSt);write("denza-adas-gates.json",row);
            Map<String,Object> isolation=Json.object();isolation.put("schema","denza-adas-stock-isolation-v1");isolation.put("bootId",BOOT);
            isolation.put("consumerSession",consumer);isolation.put("readerSession",reader);isolation.put("stockAbsent",true);
            isolation.put("startedAtElapsedMs",at);isolation.put("completedAtElapsedMs",at);write("denza-adas-stock-isolation.json",isolation);
        }
        void arm(boolean on)throws Exception{if(automaticOffline&&offlineSubject==null)subject(routeId);evidence(on);write("denza-adas-live-arm.json",arm);Handler.advance(1000);}
        void subject(String id){if(offlineSubject!=null&&!Objects.equals(id,offlineSubject.routeId))routeEpoch++;
            offlineSubject=new OfflineMapGate.Subject(id,routeEpoch,id==null?null:GEOMETRY);bridge.sourceRoute(offlineSubject);}
        void offlineEvidence(){if(offlineSubject!=null)bridge.offlineMap(OfflineMapGate.Snapshot.available(offlineSubject,SystemClock.now,INVENTORY));}
        String routeId="actual-yandex-test";
        /** Where the test route lies; the stock frame's envelope is Moscow's. */
        double lon0=49.1,lat0=55.8;
        NavigatorDriver.Observation observation(boolean route){return observation(route,.2);}
        NavigatorDriver.Observation observation(boolean route,double scalar){
            NavigatorDriver.Observation o=new NavigatorDriver.Observation();o.monotonicMs=SystemClock.now;o.mode="guidance";o.routeId=routeId;
            o.positionOnRoute=true;o.scalar=scalar;o.locationClass="FINE";o.lon=lon0+.001;o.lat=lat0+.001;o.speedMps=0;o.heading=90;
            o.stamp=SystemClock.now;o.fixAgeMs=0;
            if(route)o.route=new YandexRoute(o.routeId,new double[]{lon0,lon0+.001,lon0+.002},new double[]{lat0,lat0+.001,lat0+.002},
                    Collections.singletonList(new YandexRoute.Section(0,2,"STRAIGHT",null,60)),null,null,null,null,null,null);
            return o;
        }
        void observe(boolean route){observe(observation(route));}
        void observe(NavigatorDriver.Observation o){if(automaticOffline){subject(o.routeId);offlineEvidence();}o.routeEpoch=routeEpoch;
            o.offlineGeometrySha256=offlineSubject==null?null:offlineSubject.geometrySha256;long wall=1_792_836_000_000L+SystemClock.now;
            bridge.observe(o,FixFreshness.capture(SystemClock.now,wall,SystemClock.now,SystemClock.now,wall),null);}
        long route()throws Exception{observe(true);Handler.advance(0);long path=bridge.status().pathId;
            if(path<0){Handler.advance(1);observe(false);Handler.advance(0);path=bridge.status().pathId;}
            check(path>0,"real sender publishes fresh path ID");return path;}
        void ack(long id){AdasPassiveClient.latest.answer(Topics.PATH_SELECT_CONFIRM,StockMessages.pathSelectConfirm(id,200));Handler.advance(0);}
        /** A route with its real ACK, streaming positions. */
        long streaming()throws Exception{long id=route();ack(id);Handler.advance(1000);evidence(true);observe(false);Handler.advance(0);
            check(LiveMapTransport.latest.count(Topics.SD_VEHICLE_LOCATION)>=1,"fixture streams positions on its confirmed path");return id;}
    }
    static final byte[] PILOT_STATUS={10,0};
    static void clear(File f){File[] children=f.listFiles();if(children!=null)for(File c:children)clear(c);f.delete();}
    public static void main(String[] args)throws Exception {
        root=new File(args[0]);clear(root);root.mkdirs(); // App data of an earlier run would resume its arms.
        if(args.length>1){ // Named drive cases only, each reported on its own (for before/after comparisons).
            for(int i=1;i<args.length;i++){java.lang.reflect.Method m=LiveRuntimeTest.class.getDeclaredMethod(args[i]);
                try{m.invoke(null);System.out.println(args[i]+": PASS");}
                catch(java.lang.reflect.InvocationTargetException e){System.out.println(args[i]+": FAIL "+e.getCause());}}
            return;
        }
        Rig live=new Rig();live.arm(true);check("LIVE".equals(live.state().get("state")),"real ready permits live feed");
        check(DriveEvidence.states.contains("live:"+live.armId+":binder_connected"),"live receiver lifecycle recorded with its arm");
        int records=DriveEvidence.packets.size();byte[] matchBytes={10,0};
        AdasPassiveClient.latest.answer(0x0004002C002C8001L,matchBytes);
        AdasPassiveClient.latest.answer(0x0004000D000D8002L,PILOT_STATUS);
        AdasPassiveClient.latest.answer(0x0001000D000D0001L,null);Handler.advance(0);
        check(DriveEvidence.packets.size()==records+2,"match and null availability recorded; service-13 status is the overlay receiver's to record, once");
        DriveEvidence.Record first=DriveEvidence.packets.get(records),last=DriveEvidence.packets.get(records+1);
        check(first.bytes==matchBytes&&first.session.equals(live.armId)&&first.receiver.equals("live"),"actual callback bytes and receiver identity preserved");
        check(last.bytes==null&&last.received==SystemClock.now,"null availability and original callback time preserved");
        long id=live.route();LiveMapTransport tx=LiveMapTransport.latest;
        check(tx.count(Topics.ROUTE)>0&&tx.count(Topics.PATH_SELECTED)==1,"actual route then one actual selection");
        check(tx.count(Topics.SD_VEHICLE_LOCATION)==0,"no dynamic packets before real ACK");
        live.ack(id+1);Handler.advance(1);live.observe(false);Handler.advance(0);
        check(tx.count(Topics.SD_VEHICLE_LOCATION)==0,"foreign ACK cannot enable real position");
        live.ack(id);Handler.advance(1000);live.evidence(true);live.observe(false);Handler.advance(0);
        check(tx.count(Topics.SD_VEHICLE_LOCATION)==1,"real matching ACK enables fresh FINE position");
        Handler.advance(1001);
        check(tx.count(Topics.SD_VEHICLE_LOCATION)==1,"stationary held fix not repeated");
        // An unavailable/available bounce must still change the transport epoch.
        AdasPassiveClient.latest.epoch++;live.evidence(true);live.observe(false);Handler.advance(1000);
        check(tx.closed&&AdasPassiveClient.latest.closed,"remote restart closes own channels");
        check(Boolean.TRUE.equals(live.state().get("quiescent")),"closed runtime proves quiescence");
        int after=tx.topics.size();Handler.advance(3000);check(tx.topics.size()==after,"closed queued jobs cannot emit");
        check("STOPPED".equals(live.state().get("state")),"same arm never automatically reopens");

        Rig replacement=new Rig();replacement.arm(true);long firstPath=replacement.route();
        LiveMapTransport replacementTx=LiveMapTransport.latest;
        check(firstPath>0&&firstPath<=Integer.MAX_VALUE,"compatible route ID stays positive signed32");
        NavigatorDriver.Observation off=new NavigatorDriver.Observation();off.mode="off";off.monotonicMs=SystemClock.now;
        replacement.observe(off);Handler.advance(0);
        check(!replacementTx.closed&&!AdasPassiveClient.latest.closed,"cancel guidance retains armed runtime and recorder");
        replacement.evidence(true);Handler.advance(1);replacement.routeId="actual-yandex-second";long secondPath=replacement.route();
        check(secondPath>firstPath&&replacementTx.count(Topics.PATH_SELECTED)==2,"replacement route is actually sent with a new persisted ID");
        replacement.ack(firstPath);Handler.advance(1);replacement.observe(false);Handler.advance(0);
        check(replacementTx.count(Topics.SD_VEHICLE_LOCATION)==0,"old route ACK cannot release replacement dynamics");
        replacement.ack(secondPath);Handler.advance(1000);replacement.evidence(true);replacement.observe(false);Handler.advance(0);
        check(replacementTx.count(Topics.SD_VEHICLE_LOCATION)==1,"new route ACK plus current FINE releases replacement dynamics");
        replacement.observe(off);Handler.advance(0);replacement.evidence(true);Handler.advance(1);
        replacement.routeId="actual-yandex-third";long thirdPath=replacement.route();
        check(thirdPath>secondPath&&replacementTx.count(Topics.PATH_SELECTED)==3,"second cancel and restart works within same arm");

        Rig disabled=new Rig();disabled.arm(false);check("WAITING".equals(disabled.state().get("state")),"hardwareOFF waits without synthetic gates");
        check(LiveMapTransport.latest.topics.isEmpty()&&LiveMapTransport.latest.offered.isEmpty(),"OFF produces no offers or packets");
        disabled.evidence(true);Handler.advance(1000);check("LIVE".equals(disabled.state().get("state")),"fresh real ON permits initial start");

        Rig unknownWaiting=new Rig();unknownWaiting.arm(false);LiveMapTransport unknownTx=LiveMapTransport.latest;
        unknownWaiting.write("denza-adas-gates.json",Json.object());Handler.advance(1000);
        check("WAITING".equals(unknownWaiting.state().get("state"))&&!unknownTx.closed&&unknownTx.offered.isEmpty(),
                "missing initial gate evidence waits without closing or offering services");
        unknownWaiting.evidence(true);Handler.advance(1000);
        check("LIVE".equals(unknownWaiting.state().get("state")),"fresh initial evidence can complete healthy waiting");

        Rig waitingFailure=new Rig();waitingFailure.arm(false);LiveMapTransport waitingTx=LiveMapTransport.latest;
        AdasPassiveClient.latest.sink.state("client_setup_error","actual setup failure");Handler.advance(1000);
        check(waitingTx.closed&&AdasPassiveClient.latest.closed&&"STOPPED".equals(waitingFailure.state().get("state")),
                "actual initial channel failure is terminal while healthy OFF waiting remains recoverable");

        Rig removed=new Rig();removed.arm(true);removed.observe(true);LiveMapTransport removedTx=LiveMapTransport.latest;
        Files.delete(new File(removed.context.files,"denza-adas-live-arm.json").toPath());Handler.advance(0);
        check(removedTx.topics.isEmpty(),"disarm between enqueue and route fire blocks all egress");Handler.advance(1000);
        check(Boolean.TRUE.equals(removed.state().get("quiescent")),"disarm closes runtime for restore");

        Rig stock=new Rig();stock.arm(true);stock.observe(true);stock.context.pm.state=0;Handler.advance(0);
        check(LiveMapTransport.latest.topics.isEmpty(),"enabled stock map rejects already queued route");

        Rig failed=new Rig();failed.arm(true);LiveMapTransport failTx=LiveMapTransport.latest;failTx.result=-7;failed.observe(true);Handler.advance(0);
        check(failTx.count(Topics.ROUTE)==1&&failTx.count(Topics.PATH_SELECTED)==0,"failed actual route suppresses selection");
        failTx.result=0;failed.evidence(true);Handler.advance(1000);
        check(failTx.closed&&AdasPassiveClient.latest.closed&&"STOPPED".equals(failed.state().get("state")),
                "actual send failure is terminal for this arm even after transport recovery before tick");
        check(String.valueOf(failed.state().get("stopDetail")).contains("result -7"),"the terminal cause keeps the actual result");

        Rig rearmed=new Rig();rearmed.arm(true);long oldPath=rearmed.route();
        LiveMapTransport oldTransport=LiveMapTransport.latest;AdasPassiveClient oldAnswers=AdasPassiveClient.latest;
        rearmed.arm.put("armId",UUID.randomUUID().toString());rearmed.arm.put("issuedAtElapsedMs",SystemClock.now);
        rearmed.arm.put("expiresAtElapsedMs",SystemClock.now+60000);rearmed.arm(true);
        LiveMapTransport newTransport=LiveMapTransport.latest;AdasPassiveClient newAnswers=AdasPassiveClient.latest;
        check(oldTransport.closed&&oldAnswers.closed&&newTransport!=oldTransport&&newAnswers!=oldAnswers,
                "explicit new arm owns new channels after old session shutdown");
        oldTransport.listener.changed("server_disconnected");oldAnswers.sink.state("client_setup_error","old delayed callback");
        oldAnswers.answer(Topics.PATH_SELECT_CONFIRM,StockMessages.pathSelectConfirm(oldPath,200));
        rearmed.evidence(true);Handler.advance(1000);
        check(!newTransport.closed&&!newAnswers.closed&&"LIVE".equals(rearmed.state().get("state")),
                "old arm callbacks cannot fault or acknowledge the new arm");
        rearmed.routeId="actual-yandex-rearmed";long newPath=rearmed.route();check(newPath>oldPath,"new arm keeps cross-session persisted ID freshness");

        Rig storage=new Rig();storage.arm(true);storage.context.commitOk=false;storage.observe(true);Handler.advance(0);
        check(LiveMapTransport.latest.closed&&LiveMapTransport.latest.topics.isEmpty(),"unpersisted path ID never sent");

        Rig old=new Rig();old.arm.put("consumerSession",UUID.randomUUID().toString());old.arm(true);
        check("BLOCKED".equals(old.state().get("state"))&&Boolean.TRUE.equals(old.state().get("quiescent")),"another consumer's arm cannot bind sender");

        rerouteWithBlockedWorker();
        callbacksOfAnOldArm();
        firstCauseKept();
        gapPausesNotCloses();
        armExpiryEndsTheRoute();
        restartResumesTheArm();
        endedArmStaysEnded();
        mandatoryOfflineInventory();
        variantSession();
        variantFrameAfterOfflineWithdrawal();
        invalidVariantBlocks();
        naviStJournal();
        System.out.println("LiveRuntimeTest: "+checks+" checks passed (fake Android/Binder boundary, production coordinator/protocol)");
    }

    static void mandatoryOfflineInventory()throws Exception {
        OfflineMapGate.Snapshot productionReady=OfflineMapGate.Snapshot.available(
                new OfflineMapGate.Subject("current",1,Rig.GEOMETRY),0,Rig.INVENTORY);
        check(productionReady.state==OfflineMapGate.State.AVAILABLE&&productionReady.proofFingerprint==null
                &&productionReady.code.equals("offline_inventory_ready"),"runtime ready fixture matches inventory-only production state");
        Rig missing=new Rig();missing.automaticOffline=false;missing.subject(missing.routeId);missing.arm(true);
        missing.observe(true);Handler.advance(1000);
        check(LiveMapTransport.latest.topics.isEmpty()&&LiveMapTransport.latest.offered.isEmpty(),
                "no offline inventory snapshot means zero offers and map fires even with a valid arm and hardware");

        Rig changed=new Rig();changed.arm(true);LiveMapTransport changedTx=LiveMapTransport.latest;
        changed.automaticOffline=false;changed.routeId="new-unobserved-route";changed.subject(changed.routeId);
        changed.observe(true);Handler.advance(1000);
        check(changedTx.topics.isEmpty(),"previous route inventory snapshot cannot publish a new route");

        Rig loss=new Rig();loss.arm(true);long old=loss.streaming();LiveMapTransport tx=LiveMapTransport.latest;
        loss.automaticOffline=false;int before=tx.topics.size();
        loss.bridge.offlineMap(OfflineMapGate.Snapshot.unavailable(loss.offlineSubject,SystemClock.now,Rig.INVENTORY,"missing","Офлайн-карта удалена"));
        loss.observe(false);Handler.advance(0);
        for(int i=0;i<4;i++){loss.evidence(true);Handler.advance(1000);loss.observe(false);Handler.advance(0);}
        check(tx.topics.size()>before,"offline loss withdraws a route already at ADAS");
        for(int i=before;i<tx.topics.size();i++) {
            check(tx.topics.get(i)==Topics.NAVIGATION_STATUS,"offline loss allows only the old route end sequence");
            Map<?,?> body=(Map<?,?>)Pb.NAVIGATION_STATUS_NOTIFY.decode(tx.payloads.get(i)).get("navigationStatusLinkInfoNotifyStruct");
            check(((Number)body.get("navigationStatus")).longValue()==100,"offline teardown never sends an active state");
        }
        check(!tx.closed&&loss.bridge.status().confirmedPathId<0,"offline loss retains arm but invalidates old ACK");
        loss.offlineEvidence();long fresh=loss.route();
        check(fresh>old,"restored inventory publishes a fresh persisted path");
        long positions=tx.count(Topics.SD_VEHICLE_LOCATION);loss.ack(old);loss.observe(false);Handler.advance(0);
        check(tx.count(Topics.SD_VEHICLE_LOCATION)==positions,"old ACK cannot release route after inventory restoration");
        loss.ack(fresh);loss.evidence(true);Handler.advance(1000);loss.observe(false);Handler.advance(0);
        check(tx.count(Topics.SD_VEHICLE_LOCATION)>positions,"fresh path requires its own actual ACK");

        Rig finalBoundary=new Rig();finalBoundary.arm(true);LiveMapTransport boundaryTx=LiveMapTransport.latest;
        finalBoundary.observe(true);
        boundaryTx.beforeAdmission=()->finalBoundary.bridge.offlineMap(OfflineMapGate.Snapshot.unavailable(
                finalBoundary.offlineSubject,SystemClock.now,Rig.INVENTORY,"removed","Офлайн-карта удалена"));
        Handler.advance(0);
        check(boundaryTx.topics.isEmpty(),"inventory availability revoked at final transport boundary blocks the first route byte");
        check(!boundaryTx.closed,"final offline revocation does not masquerade as transport failure");

        Rig stale=new Rig();stale.arm(true);stale.streaming();stale.automaticOffline=false;
        LiveMapTransport staleTx=LiveMapTransport.latest;long expires=SystemClock.now+OfflineMapGate.MAX_AGE_MS;
        for(int i=0;i<6;i++){stale.evidence(true);Handler.advance(1000);}
        boolean sawEnd=false;
        for(int i=0;i<staleTx.topics.size();i++)if(staleTx.times.get(i)>=expires){
            check(staleTx.topics.get(i)==Topics.NAVIGATION_STATUS,"timer egress cannot outlive offline inventory TTL");sawEnd=true;
        }
        check(sawEnd&&stale.bridge.status().confirmedPathId<0,"TTL loss without a new observation withdraws and invalidates ACK");

        Rig geometry=new Rig();geometry.arm(true);geometry.automaticOffline=false;LiveMapTransport geometryTx=LiveMapTransport.latest;
        geometry.observe(true); // Queued copy carries the original geometry hash.
        geometry.offlineSubject=new OfflineMapGate.Subject(geometry.routeId,geometry.routeEpoch,Rig.ALT_GEOMETRY);
        geometry.bridge.sourceRoute(geometry.offlineSubject);geometry.offlineEvidence();Handler.advance(0);
        check(geometryTx.topics.isEmpty(),"same ID/epoch queued geometry cannot borrow a newer scoped inventory snapshot");

        Rig arrival=new Rig();arrival.arm(true);arrival.streaming();
        NavigatorDriver.Observation arrived=arrival.observation(false);arrived.finished=true;arrival.observe(arrived);Handler.advance(0);
        arrival.automaticOffline=false;LiveMapTransport arrivalTx=LiveMapTransport.latest;int afterArrival=arrivalTx.topics.size();
        arrival.bridge.offlineMap(OfflineMapGate.Snapshot.unavailable(arrival.offlineSubject,SystemClock.now,Rig.INVENTORY,"missing","Офлайн-карта удалена"));
        for(int i=0;i<4;i++){arrival.evidence(true);Handler.advance(1000);}
        check(arrivalTx.topics.size()>afterArrival,"offline loss while already ending still withdraws the route");
        for(int i=afterArrival;i<arrivalTx.topics.size();i++) {
            check(arrivalTx.topics.get(i)==Topics.NAVIGATION_STATUS,"ending after offline loss permits only status packets");
            Map<?,?> body=(Map<?,?>)Pb.NAVIGATION_STATUS_NOTIFY.decode(arrivalTx.payloads.get(i)).get("navigationStatusLinkInfoNotifyStruct");
            check(((Number)body.get("navigationStatus")).longValue()==100,"offline loss replaces remaining arrival110 with idle100");
        }
    }

    /** D (13:36:37–42): a reroute on a starved worker queued >32 callbacks; overflow ended the session. */
    static void rerouteWithBlockedWorker()throws Exception {
        Rig r=new Rig();r.arm(true);long first=r.streaming();LiveMapTransport tx=LiveMapTransport.latest;
        r.routeId="actual-yandex-reroute";r.evidence(true);r.observe(true);Handler.advance(0);
        long rerouted=tx.lastSelected();
        check(rerouted>first&&tx.count(Topics.PATH_SELECTED)==2,"the reroute's new route and selection went out");
        long sent=SystemClock.now;
        // The worker is blocked for 1.2 s: 100 service-13 callbacks and the selection ACK queue up behind it.
        for(int i=0;i<100;i++){SystemClock.now=sent+i*12;AdasPassiveClient.latest.answer(0x0004000D000D8002L,PILOT_STATUS);
            if(i==25){long at=SystemClock.now;AdasPassiveClient.latest.sink.enqueue(Topics.PATH_SELECT_CONFIRM,
                    ()->AdasPassiveClient.latest.sink.event(Topics.PATH_SELECT_CONFIRM,0,at,StockMessages.pathSelectConfirm(rerouted,200)));}}
        SystemClock.now=sent;Handler.block(1200);Handler.advance(0);
        check(!tx.closed&&"LIVE".equals(r.state().get("state")),"33+ queued callbacks during a reroute do not end the session");
        @SuppressWarnings("unchecked") Map<String,Object> stats=(Map<String,Object>)r.state().get("callbacks");
        check(((Number)stats.get("droppedOldest")).longValue()>0,"status callbacks beyond the ring drop the oldest, counted");
        check(((Number)stats.get("essential")).longValue()>=1,"the ACK went through the essential queue");
        r.evidence(true);r.observe(false);Handler.advance(0);
        check(r.bridge.status().confirmedPathId==rerouted,"the reroute's real ACK is processed after the block");
        long positions=tx.count(Topics.SD_VEHICLE_LOCATION);r.evidence(true);Handler.advance(1000);r.observe(false);Handler.advance(0);
        check(tx.count(Topics.SD_VEHICLE_LOCATION)>positions,"positions continue on the rerouted path");
    }

    /** Callbacks of a replaced arm can neither run for nor poison the new one. */
    static void callbacksOfAnOldArm()throws Exception {
        Rig r=new Rig();r.arm(true);r.route();AdasPassiveClient oldAnswers=AdasPassiveClient.latest;
        for(int i=0;i<40;i++)check(oldAnswers.sink.enqueue(0x0004000D000D8002L,()->{}),"the current arm takes every status callback");
        r.arm.put("armId",UUID.randomUUID().toString());r.arm.put("issuedAtElapsedMs",SystemClock.now);
        r.arm.put("expiresAtElapsedMs",SystemClock.now+60000);r.arm(true);LiveMapTransport newTx=LiveMapTransport.latest;
        check(!oldAnswers.sink.enqueue(Topics.PATH_SELECT_CONFIRM,()->{throw new AssertionError("old arm task ran");}),"a replaced arm refuses late callbacks");
        check(AdasPassiveClient.latest.sink.enqueue(0x0004000D000D8002L,()->{}),"the new arm's first callback is accepted");
        r.evidence(true);Handler.advance(1000);
        check(!newTx.closed&&"LIVE".equals(r.state().get("state")),"the old arm's backlog cannot poison the new arm");
    }

    /** A.4 / D: the real cause was overwritten by «Связь сеанса потеряна», a transient logged without its prerequisite. */
    static void firstCauseKept()throws Exception {
        Rig r=new Rig();r.arm(true);r.streaming();
        Handler.advance(3100); // the gate reader is late: its snapshot is older than the TTL
        check(r.journal().contains("\"detail\":\"stock isolation: stock isolation clock\"")&&r.journal().contains("\"reason\":\"stock isolation: stock isolation clock\""),
                "a gap is journalled with the prerequisite that failed (the reader's isolation proof lapsed first)");
        r.evidence(true);Handler.advance(500);
        r.context.pm.state=0;Handler.advance(1000);
        Map<String,Object> s=r.state();
        check("STOPPED".equals(s.get("state")),"an enabled stock map ends the session");
        check(!String.valueOf(s.get("reason")).contains("Связь сеанса потеряна")&&String.valueOf(s.get("stopDetail")).contains("stock map package state 0"),
                "the stop keeps its own cause, not a generic one: "+s.get("reason")+" / "+s.get("stopDetail"));
        check("stock isolation: stock isolation clock".equals(s.get("firstFailure")),"the first failure of the session is kept: "+s.get("firstFailure"));
    }

    /** A.4: at the shift into Drive a transient dropped the path and 42.66 s went without a packet. */
    static void gapPausesNotCloses()throws Exception {
        Rig r=new Rig();r.arm(true);long path=r.streaming();LiveMapTransport tx=LiveMapTransport.latest;
        Handler.advance(2700);r.observe(false);Handler.advance(400);
        check(!tx.closed&&"PAUSED".equals(r.state().get("state")),"a late gate snapshot pauses the feed, it does not close it: "+r.state().get("state"));
        r.evidence(true);Handler.advance(300);long positions=tx.count(Topics.SD_VEHICLE_LOCATION);r.observe(false);Handler.advance(0);
        check(tx.count(Topics.SD_VEHICLE_LOCATION)>positions&&r.bridge.status().pathId==path,"fresh evidence resumes the same path at the next fix");
        check(tx.count(Topics.ROUTE)==1,"no republication after the gap");
        check(DriveEvidence.feed.contains("PAUSED:Проверка штатной карты"),"the pause and its reason reach the change journal: "+DriveEvidence.feed);
        r.evidence(false);Handler.advance(300);r.observe(false);Handler.advance(800);
        check(!tx.closed&&"PAUSED".equals(r.state().get("state"))&&"NOA выключен".equals(r.state().get("reason")),"DNP off is a pause with a state, not an error: "+r.state().get("reason"));
        r.evidence(true);Handler.advance(1000);
        check(tx.count(Topics.PATH_SELECTED)==2,"DNP back on resends the route as the stock does (300 ms later)");
    }

    /** F (13:36:42 → 13:38:00): a closed session left ADAS holding the route; NOA engaged 78 s later on it. */
    static void armExpiryEndsTheRoute()throws Exception {
        Rig r=new Rig();r.arm.put("expiresAtElapsedMs",SystemClock.now+12000);r.arm(true);r.streaming();LiveMapTransport tx=LiveMapTransport.latest;
        int statuses=tx.statuses().size();
        for(int i=0;i<12;i++){r.evidence(true);Handler.advance(1000);}
        List<Long> sent=tx.statuses().subList(statuses,tx.statuses().size());
        check(Collections.frequency(sent,100L)==3,"the session's end sends the stock's three 100s before the arm lapses: "+sent);
        check(tx.closed&&"STOPPED".equals(r.state().get("state")),"then releases the transport: "+r.state());
        check(((Number)r.read("denza-adas-live-lineage.json").get("heldPathId")).longValue()==-1,"no route of ours is left at ADAS");
    }

    /** E: a Navigator restart mid-drive left the bridge BLOCKED with «Нет действующего разрешения для этого запуска». */
    static void restartResumesTheArm()throws Exception {
        Rig first=new Rig();first.arm(true);long path=first.streaming();
        Rig second=new Rig(first);Handler.advance(1000);second.evidence(true);Handler.advance(1000);
        Map<String,Object> s=second.state();
        check(second.consumer.equals(second.read("denza-adas-consumer.json").get("consumerSession")),"the restarted process keeps the boot's consumer identity");
        check("LIVE".equals(s.get("state"))&&((Number)s.get("armProcesses")).longValue()==2,"the same unexpired arm resumes in the new process: "+s.get("state")+" "+s.get("reason"));
        LiveMapTransport tx=LiveMapTransport.latest;
        second.evidence(true);Handler.advance(1000);second.evidence(true);Handler.advance(1000);
        check(Collections.frequency(tx.statuses(),100L)==3,"the route the dead process left at ADAS gets the stock's end first: "+tx.statuses());
        second.routeId="actual-yandex-after-restart";second.evidence(true);long next=second.route();second.ack(next);Handler.advance(1000);
        second.evidence(true);second.observe(false);Handler.advance(0);
        check(next>path&&tx.count(Topics.SD_VEHICLE_LOCATION)>=1,"a new route streams under the resumed arm");
    }

    @SuppressWarnings("unchecked") static Map<String,Object> object(Object v){return (Map<String,Object>)v;}
    @SuppressWarnings("unchecked") static List<Object> list(Object v){return (List<Object>)v;}
    /** Header rows of the live journal (one per feed start, so one per process of the arm). */
    static List<Map<String,Object>> headers(Rig r)throws Exception {
        List<Map<String,Object>> out=new ArrayList<>();
        for(String line:r.journal().split("\n"))if(line.contains("\"kind\":\"header\""))out.add(object(Json.parse(line)));
        return out;
    }
    /** {lon, lat} of the first point of the last actual route. */
    static double[] firstRoutePoint(LiveMapTransport tx){
        for(int i=tx.topics.size()-1;i>=0;i--)if(tx.topics.get(i)==Topics.ROUTE){
            Map<String,Object> inner=object(Pb.NAVI_SD_ROUTE_NOTIFY.decode(tx.payloads.get(i)).get("naviSDRouteStruct"));
            Map<String,Object> detail=object(Json.parse((String)inner.get("navigationSDLink2")));
            Map<String,Object> send=object(Json.parse((String)detail.get("payload")));
            Map<String,Object> p=object(list(object(list(send.get("data")).get(0)).get("pnts")).get(0));
            return new double[]{((Number)p.get("x")).doubleValue(),((Number)p.get("y")).doubleValue()};
        }
        throw new AssertionError("no route sent");
    }

    /**
     * The arm's variant drives the production coordinator: the feed header and the live state name it,
     * startProtocol stock makes the one ODD request and the 100/106 phase before the route, the frame is
     * the stock's, and a Navigator restart resumes the same arm with the same variant.
     */
    static void variantSession()throws Exception {
        Rig plain=new Rig();plain.arm(true);plain.route();
        check(AdasPassiveClient.latest.requests.isEmpty(),"no variant: no ODD request");
        Map<String,Object> plainHeader=headers(plain).get(0);
        check(list(object(plainHeader.get("variant")).get("overrides")).isEmpty(),"no variant: the header names the defaults");

        Rig r=new Rig();r.lon0=37.07;r.lat0=55.52;r.routeId="actual-yandex-variant";
        r.arm.put("variant",object(Json.parse("{\"id\":\"drive-1\",\"frame\":\"STOCK_GCJ\",\"startProtocol\":\"stock\","
                +"\"historySpeed\":{\"mode\":\"fixed\",\"value\":25}}")));
        r.arm(true);
        check("LIVE".equals(r.state().get("state")),"a valid variant arms the session: "+r.state().get("reason"));
        Map<String,Object> v=object(r.state().get("variant"));
        check("STOCK_GCJ".equals(v.get("frame"))&&"drive-1".equals(v.get("id"))&&"stock".equals(v.get("startProtocol")),"the live state names the effective variant: "+v);
        check(Boolean.FALSE.equals(r.state().get("guidanceActive")),"no guidance yet");
        check(r.journal().contains("\"input\":\"liveSession.variant\""),"the live journal records the variant at arm time");
        check(DriveEvidence.variants.stream().anyMatch(s->s.startsWith(r.armId+":")&&s.contains("STOCK_GCJ")),"the drive's change journal records it");
        Map<String,Object> header=headers(r).get(0);
        check("STOCK_GCJ".equals(object(header.get("variant")).get("frame"))&&"NATIVE_GCJ_UNRESTRICTED_CANDIDATE".equals(header.get("frame")),
                "the feed's trace header carries the variant and its frame");
        LiveMapTransport tx=LiveMapTransport.latest;AdasPassiveClient answers=AdasPassiveClient.latest;
        long start=SystemClock.now;r.observe(true);Handler.advance(0);
        check(answers.requests.size()==1&&answers.requests.get(0)==start,"the stock start's one ODD request at guidance start");
        check(tx.count(Topics.ROUTE)==0,"the route waits for the pre-route phase");
        for(int i=0;i<5;i++){r.evidence(true);Handler.advance(1000);}
        check(tx.count(Topics.ROUTE)==1&&tx.count(Topics.PATH_SELECTED)==1,"then the route and its selection");
        check(tx.statuses().subList(0,5).equals(Arrays.asList(100L,100L,106L,106L,106L)),"the stock's 100, 100, 106, 106, 106 first: "+tx.statuses());
        check(tx.first(Topics.ROUTE,start)==start+5000,"the route 5 s after the start, as the stock's phase");
        double[] p=firstRoutePoint(tx),gcj=Coordinates.toSdk(r.lon0,r.lat0,Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE);
        check(Coordinates.distance(p[0],p[1],gcj[0],gcj[1])<0.5,"the route's points in the stock frame");
        check(Boolean.TRUE.equals(r.state().get("guidanceActive")),"the live state reports guidance");
        long path=tx.lastSelected();r.ack(path);Handler.advance(1000);r.evidence(true);r.observe(false);Handler.advance(0);
        check(tx.count(Topics.SD_VEHICLE_LOCATION)>=1,"positions after the real ACK");
        check(((Number)r.state().get("confirmedPathId")).longValue()==path,"the live state reports the confirmed path");
        check(r.journal().contains("\"input\":\"liveEgress.request\"")&&r.journal().contains("\"input\":\"variantApplied\"")
                &&r.journal().contains("\"historySpeed\":25")&&r.journal().contains("\"input\":\"stockStart\""),
                "the request, the stock start and the variant's values are attributed in the journal");

        Rig second=new Rig(r);second.lon0=r.lon0;second.lat0=r.lat0;Handler.advance(1000);second.evidence(true);Handler.advance(1000);
        check("LIVE".equals(second.state().get("state"))&&((Number)second.state().get("armProcesses")).longValue()==2,"the restarted process resumes the arm");
        check("STOCK_GCJ".equals(object(second.state().get("variant")).get("frame")),"with the same variant");
        List<Map<String,Object>> both=headers(second);
        check(both.size()>=2&&"STOCK_GCJ".equals(object(both.get(both.size()-1).get("variant")).get("frame")),"its own feed header carries the variant again");
        check(DriveEvidence.variants.stream().filter(s->s.startsWith(r.armId+":")).count()>=2,"each process journals the arm's variant");
        for(int i=0;i<4;i++){second.evidence(true);Handler.advance(1000);} // the end of the route the dead process left
        LiveMapTransport tx2=LiveMapTransport.latest;AdasPassiveClient answers2=AdasPassiveClient.latest;
        second.routeId="actual-yandex-variant-after-restart";long again=SystemClock.now;second.observe(true);Handler.advance(0);
        check(answers2.requests.size()==1&&answers2.requests.get(0)==again,"the resumed arm's next guidance starts the stock's way");
        for(int i=0;i<5;i++){second.evidence(true);Handler.advance(1000);}
        check(tx2.count(Topics.ROUTE)==1,"the route after the restart's stock start");
        p=firstRoutePoint(tx2);
        check(Coordinates.distance(p[0],p[1],gcj[0],gcj[1])<0.5,"in the stock frame after the restart too");
    }

    /**
     * An offline withdrawal recreates the driver: the republished route stays in the session's frame
     * (the variant's STOCK_GCJ), not the build default. The default start protocol sends no ODD request.
     */
    static void variantFrameAfterOfflineWithdrawal()throws Exception {
        Rig r=new Rig();r.lon0=37.07;r.lat0=55.52;r.routeId="actual-yandex-gcj-offline";
        r.arm.put("variant",object(Json.parse("{\"id\":\"gcj\",\"frame\":\"STOCK_GCJ\"}")));
        r.arm(true);check("LIVE".equals(r.state().get("state")),"a frame-only variant arms: "+r.state().get("reason"));
        AdasPassiveClient answers=AdasPassiveClient.latest;
        long old=r.streaming();LiveMapTransport tx=LiveMapTransport.latest;
        double[] gcj=Coordinates.toSdk(r.lon0,r.lat0,Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE);
        double[] p=firstRoutePoint(tx);
        check(Coordinates.distance(p[0],p[1],gcj[0],gcj[1])<0.5,"the first route in the variant's frame");
        r.automaticOffline=false;int before=tx.topics.size();
        r.bridge.offlineMap(OfflineMapGate.Snapshot.unavailable(r.offlineSubject,SystemClock.now,Rig.INVENTORY,"missing","Офлайн-карта удалена"));
        r.observe(false);Handler.advance(0);
        for(int i=0;i<4;i++){r.evidence(true);Handler.advance(1000);r.observe(false);Handler.advance(0);}
        check(tx.topics.size()>before&&r.journal().contains("offlineWithdrawal"),"fixture: the offline loss withdrew the route");
        int routes=tx.count(Topics.ROUTE);
        r.offlineEvidence();long fresh=r.route();
        check(fresh>old&&tx.count(Topics.ROUTE)>routes,"restored inventory republishes the route on a fresh path");
        p=firstRoutePoint(tx);
        double[] wgs=Coordinates.toSdk(r.lon0,r.lat0,Coordinates.Frame.WGS84);
        check(Coordinates.distance(p[0],p[1],gcj[0],gcj[1])<0.5&&Coordinates.distance(p[0],p[1],wgs[0],wgs[1])>100,
                "the republished route stays in the session's STOCK_GCJ frame, not the build default");
        check(answers.requests.isEmpty(),"the default start protocol sends no ODD request");
    }

    /**
     * The gate reader's NaviSt reads and writes reach the live journal and the drive's change journal,
     * each once by its number, while the gates stay valid; the side channel changes no send.
     */
    static void naviStJournal()throws Exception {
        Rig r=new Rig();r.arm.put("variant",object(Json.parse("{\"id\":\"H6\",\"naviSt\":true}")));
        String read1="{\"n\":1,\"op\":\"read\",\"purpose\":\"original\",\"ok\":true,\"value\":1}";
        r.naviSt=object(Json.parse("{\"state\":\"IDLE\",\"original\":1,\"dirty\":false,\"reason\":\"original 1\",\"n\":1}"));
        r.write("denza-adas-navi-st.json",ledger(r,"["+read1+"]"));
        r.arm(true);
        check("LIVE".equals(r.state().get("state")),"the side channel's summary keeps the gates valid: "+r.state());
        check(Boolean.TRUE.equals(object(r.state().get("variant")).get("naviSt")),"the live state names the switch");
        check(DriveEvidence.naviSt.stream().filter(x->x.startsWith(r.armId+":")).count()==1,"the arm-time read is journalled");
        r.streaming();
        check(Boolean.TRUE.equals(r.state().get("guidanceActive"))&&((Number)r.state().get("confirmedPathId")).longValue()>0,
                "the reader's trigger: guidance on a confirmed path");
        r.naviSt=object(Json.parse("{\"state\":\"WRITTEN\",\"original\":1,\"dirty\":true,\"reason\":\"guidance on a confirmed path\",\"n\":4}"));
        r.write("denza-adas-navi-st.json",ledger(r,"["+read1+",{\"n\":2,\"op\":\"read\",\"purpose\":\"before write\",\"ok\":true,\"value\":1},"
                +"{\"n\":3,\"op\":\"write\",\"purpose\":\"guidance start\",\"ok\":true,\"value\":2},"
                +"{\"n\":4,\"op\":\"read\",\"purpose\":\"readback\",\"ok\":true,\"value\":2}]"));
        for(int i=0;i<3;i++){r.evidence(true);Handler.advance(1000);}
        long mine=DriveEvidence.naviSt.stream().filter(x->x.startsWith(r.armId+":")).count();
        check(mine==4,"each NaviSt read and write is journalled once: "+DriveEvidence.naviSt);
        check(DriveEvidence.naviSt.stream().anyMatch(x->x.startsWith(r.armId+":")&&x.contains("\"op\":\"write\"")&&x.contains("\"value\":2")),
                "the write of 2 is in the change journal");
        check(r.journal().contains("\"input\":\"liveSession.naviSt\""),"and in the live journal");
        r.write("denza-adas-navi-st.json",object(Json.parse("{\"schema\":\"denza-adas-navi-st-v1\",\"armId\":\"other\",\"readerSession\":\""+r.reader
                +"\",\"events\":[{\"n\":9,\"op\":\"write\",\"ok\":true,\"value\":2}]}")));
        r.naviSt.put("n",9L);for(int i=0;i<2;i++){r.evidence(true);Handler.advance(1000);}
        check(DriveEvidence.naviSt.stream().filter(x->x.startsWith(r.armId+":")).count()==4,"another arm's ledger is not journalled");
        check("LIVE".equals(r.state().get("state")),"still live");

        // Navigator restarts under the same arm: the variant (and so the reader's trigger) stays, and the
        // reader's next event reaches the new process's journals; old numbers may repeat, none is lost.
        Rig second=new Rig(r);second.naviSt=r.naviSt;second.naviSt.put("n",5L);
        second.write("denza-adas-navi-st.json",ledger(second,"[{\"n\":5,\"op\":\"write\",\"purpose\":\"restore: live state stale\",\"ok\":true,\"value\":1}]"));
        Handler.advance(1000);second.evidence(true);Handler.advance(1000);
        check("LIVE".equals(second.state().get("state"))&&Boolean.TRUE.equals(object(second.state().get("variant")).get("naviSt")),
                "the restarted process resumes the naviSt arm: "+second.state().get("state"));
        check(DriveEvidence.naviSt.stream().anyMatch(x->x.startsWith(r.armId+":")&&x.contains("\"n\":5")),"the restore after the restart is journalled");
    }
    static Map<String,Object> ledger(Rig r,String events)throws Exception{
        return object(Json.parse("{\"schema\":\"denza-adas-navi-st-v1\",\"armId\":\""+r.armId+"\",\"readerSession\":\""+r.reader+"\",\"events\":"+events+"}"));
    }

    /** A variant the schema does not allow rejects the arm: nothing is offered or sent. */
    static void invalidVariantBlocks()throws Exception {
        Rig bad=new Rig();bad.arm.put("variant",object(Json.parse("{\"frame\":\"GCJ\"}")));bad.arm(true);
        Map<String,Object> s=bad.state();
        check("BLOCKED".equals(s.get("state"))&&Boolean.TRUE.equals(s.get("quiescent"))&&String.valueOf(s.get("blockedDetail")).contains("variant"),
                "an invalid variant blocks the arm: "+s.get("state")+" "+s.get("blockedDetail"));
        Rig unknown=new Rig();unknown.arm.put("variant",object(Json.parse("{\"naviSt\":true,\"extra\":1}")));unknown.arm(true);
        check("BLOCKED".equals(unknown.state().get("state")),"an unknown switch blocks the arm");
    }

    /** A session that ended (any stop) is not reopened by a restart of the process. */
    static void endedArmStaysEnded()throws Exception {
        Rig first=new Rig();first.arm(true);first.route();LiveMapTransport.latest.result=-7;first.routeId="actual-yandex-failing";first.observe(true);Handler.advance(1000);
        check("STOPPED".equals(first.state().get("state")),"fixture: the first process ended its session");
        Rig second=new Rig(first);Handler.advance(1000);second.evidence(true);Handler.advance(1000);
        check("BLOCKED".equals(second.state().get("state"))&&"Сеанс уже завершён".equals(second.state().get("reason")),
                "an ended arm stays ended across a restart, shown as a state: "+second.state().get("reason"));
    }
}
