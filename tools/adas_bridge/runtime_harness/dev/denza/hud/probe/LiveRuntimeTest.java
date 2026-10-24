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
            row.put("values",values);write("denza-adas-gates.json",row);
            Map<String,Object> isolation=Json.object();isolation.put("schema","denza-adas-stock-isolation-v1");isolation.put("bootId",BOOT);
            isolation.put("consumerSession",consumer);isolation.put("readerSession",reader);isolation.put("stockAbsent",true);
            isolation.put("startedAtElapsedMs",at);isolation.put("completedAtElapsedMs",at);write("denza-adas-stock-isolation.json",isolation);
        }
        void arm(boolean on)throws Exception{if(automaticOffline&&offlineSubject==null)subject(routeId);evidence(on);write("denza-adas-live-arm.json",arm);Handler.advance(1000);}
        void subject(String id){if(offlineSubject!=null&&!Objects.equals(id,offlineSubject.routeId))routeEpoch++;
            offlineSubject=new OfflineMapGate.Subject(id,routeEpoch,id==null?null:GEOMETRY);bridge.sourceRoute(offlineSubject);}
        void offlineEvidence(){if(offlineSubject!=null)bridge.offlineMap(OfflineMapGate.Snapshot.available(offlineSubject,SystemClock.now,INVENTORY));}
        String routeId="actual-yandex-test";
        NavigatorDriver.Observation observation(boolean route){return observation(route,.2);}
        NavigatorDriver.Observation observation(boolean route,double scalar){
            NavigatorDriver.Observation o=new NavigatorDriver.Observation();o.monotonicMs=SystemClock.now;o.mode="guidance";o.routeId=routeId;
            o.positionOnRoute=true;o.scalar=scalar;o.locationClass="FINE";o.lon=49.101;o.lat=55.801;o.speedMps=0;o.heading=90;
            o.stamp=SystemClock.now;o.fixAgeMs=0;
            if(route)o.route=new YandexRoute(o.routeId,new double[]{49.1,49.101,49.102},new double[]{55.8,55.801,55.802},
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

    /** A session that ended (any stop) is not reopened by a restart of the process. */
    static void endedArmStaysEnded()throws Exception {
        Rig first=new Rig();first.arm(true);first.route();LiveMapTransport.latest.result=-7;first.routeId="actual-yandex-failing";first.observe(true);Handler.advance(1000);
        check("STOPPED".equals(first.state().get("state")),"fixture: the first process ended its session");
        Rig second=new Rig(first);Handler.advance(1000);second.evidence(true);Handler.advance(1000);
        check("BLOCKED".equals(second.state().get("state"))&&"Сеанс уже завершён".equals(second.state().get("reason")),
                "an ended arm stays ended across a restart, shown as a state: "+second.state().get("reason"));
    }
}
