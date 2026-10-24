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
        final Context context;final LiveMapBridge bridge;final String reader=UUID.randomUUID().toString(),armId=UUID.randomUUID().toString();
        String consumer;long seq;final Map<String,Object> arm=Json.object();
        Rig()throws Exception {
            Handler.reset();context=new Context(new File(root,"session-"+(++serial)));
            bridge=new LiveMapBridge(context,new Handler(Looper.getMainLooper()),BOOT,()->1_792_836_000_000L+SystemClock.now);Handler.advance(0);
            consumer=(String)read("denza-adas-consumer.json").get("consumerSession");
            check(Boolean.TRUE.equals(state().get("quiescent")),"initial process has no sender");
            arm.put("schema","denza-adas-live-arm-v1");arm.put("bootId",BOOT);arm.put("consumerSession",consumer);
            arm.put("readerSession",reader);arm.put("armId",armId);arm.put("issuedAtElapsedMs",SystemClock.now);
            arm.put("expiresAtElapsedMs",SystemClock.now+60000);arm.put("stockPackage",LiveSessionLease.STOCK_PACKAGE);arm.put("stockDisabled",true);
        }
        void write(String name,Map<String,Object> value)throws Exception{Files.write(new File(context.files,name).toPath(),Json.write(value).getBytes(StandardCharsets.UTF_8));}
        @SuppressWarnings("unchecked") Map<String,Object> read(String name)throws Exception{return (Map<String,Object>)Json.parse(new String(Files.readAllBytes(new File(context.files,name).toPath()),StandardCharsets.UTF_8));}
        Map<String,Object> state()throws Exception{return read("denza-adas-live-state.json");}
        void evidence(boolean on)throws Exception {
            Map<String,Object> row=Json.object(),values=Json.object();
            row.put("schema",AdasGateSnapshot.SCHEMA);row.put("bootId",BOOT);row.put("consumerSession",consumer);row.put("readerSession",reader);row.put("sequence",++seq);
            row.put("startedAtElapsedMs",SystemClock.now);row.put("completedAtElapsedMs",SystemClock.now);
            row.put("startedAtUptimeMs",SystemClock.now);row.put("completedAtUptimeMs",SystemClock.now);
            for(int fid:AdasGateSnapshot.REQUIRED_FIDS){
                long value=fid==AdasGateSnapshot.HNP_CONFIG?3:fid==AdasGateSnapshot.UNP_CONFIG||fid==AdasGateSnapshot.E2E_CONFIG?1:
                    fid==AdasGateSnapshot.NOA_UI_TYPE?2:fid==AdasGateSnapshot.HNP_SWITCH&&on?1:0;
                Map<String,Object> v=Json.object();v.put("status","ok");v.put("transact",5L);v.put("value",value);values.put(Integer.toString(fid),v);
            }
            row.put("values",values);write("denza-adas-gates.json",row);
            Map<String,Object> isolation=Json.object();isolation.put("schema","denza-adas-stock-isolation-v1");isolation.put("bootId",BOOT);
            isolation.put("consumerSession",consumer);isolation.put("readerSession",reader);isolation.put("stockAbsent",true);
            isolation.put("startedAtElapsedMs",SystemClock.now);isolation.put("completedAtElapsedMs",SystemClock.now);write("denza-adas-stock-isolation.json",isolation);
        }
        void arm(boolean on)throws Exception{evidence(on);write("denza-adas-live-arm.json",arm);Handler.advance(1000);}
        NavigatorDriver.Observation observation(boolean route){
            NavigatorDriver.Observation o=new NavigatorDriver.Observation();o.monotonicMs=SystemClock.now;o.mode="guidance";o.routeId="actual-yandex-test";
            o.positionOnRoute=true;o.scalar=.2;o.locationClass="FINE";o.lon=49.101;o.lat=55.801;o.speedMps=0;o.heading=90;
            o.stamp=SystemClock.now;o.fixAgeMs=0;
            if(route)o.route=new YandexRoute(o.routeId,new double[]{49.1,49.101,49.102},new double[]{55.8,55.801,55.802},
                    Collections.singletonList(new YandexRoute.Section(0,2,"STRAIGHT",null,60)),null,null,null,null,null,null);
            return o;
        }
        void observe(boolean route){NavigatorDriver.Observation o=observation(route);long wall=1_792_836_000_000L+SystemClock.now;
            bridge.observe(o,FixFreshness.capture(SystemClock.now,wall,SystemClock.now,SystemClock.now,wall),null);}
        long route()throws Exception{observe(true);Handler.advance(0);long path=bridge.status().pathId;
            if(path<0){Handler.advance(1);observe(false);path=bridge.status().pathId;}
            check(path>0,"real sender publishes fresh path ID");return path;}
        void ack(long id){AdasPassiveClient.latest.answer(Topics.PATH_SELECT_CONFIRM,StockMessages.pathSelectConfirm(id,200));Handler.advance(0);}
    }
    public static void main(String[] args)throws Exception {
        root=new File(args[0]);root.mkdirs();
        Rig live=new Rig();live.arm(true);check("LIVE".equals(live.state().get("state")),"real ready permits live feed");
        check(DriveEvidence.states.contains("live:"+live.armId+":binder_connected"),"live receiver lifecycle recorded with its arm");
        int records=DriveEvidence.packets.size();byte[] matchBytes={10,0};
        AdasPassiveClient.latest.answer(0x0004002C002C8001L,matchBytes);
        AdasPassiveClient.latest.answer(0x0004000D000D8002L,new byte[]{10,0});
        AdasPassiveClient.latest.answer(0x0001000D000D0001L,null);Handler.advance(0);
        check(DriveEvidence.packets.size()==records+3,"match, pilot and null availability recorded before any selection ACK");
        DriveEvidence.Record first=DriveEvidence.packets.get(records),last=DriveEvidence.packets.get(records+2);
        check(first.bytes==matchBytes&&first.session.equals(live.armId)&&first.receiver.equals("live"),"actual callback bytes and receiver identity preserved");
        check(last.bytes==null&&last.received==SystemClock.now,"null availability and original callback time preserved");
        long id=live.route();LiveMapTransport tx=LiveMapTransport.latest;
        check(tx.count(Topics.ROUTE)>0&&tx.count(Topics.PATH_SELECTED)==1,"actual route then one actual selection");
        check(tx.count(Topics.SD_VEHICLE_LOCATION)==0,"no dynamic packets before real ACK");
        live.ack(id+1);Handler.advance(1);live.observe(false);Handler.advance(0);
        check(tx.count(Topics.SD_VEHICLE_LOCATION)==0,"foreign ACK cannot enable real position");
        // The stock producer's first NaviInfo follows the route by one second.
        live.ack(id);Handler.advance(1000);live.evidence(true);live.observe(false);Handler.advance(0);
        check(tx.count(Topics.SD_VEHICLE_LOCATION)==1,"real matching ACK enables fresh FINE position");
        int count=tx.topics.size();Handler.advance(1001);
        check(tx.count(Topics.SD_VEHICLE_LOCATION)==1,"stationary held fix not repeated");
        // An unavailable/available bounce must still change the transport epoch.
        AdasPassiveClient.latest.epoch++;live.evidence(true);live.observe(false);Handler.advance(0);
        check(tx.closed&&AdasPassiveClient.latest.closed,"remote restart closes own channels");
        check(Boolean.TRUE.equals(live.state().get("quiescent")),"closed runtime proves quiescence");
        int after=tx.topics.size();Handler.advance(3000);check(tx.topics.size()==after,"closed queued jobs cannot emit");
        check("STOPPED".equals(live.state().get("state")),"same arm never automatically reopens");

        Rig replacement=new Rig();replacement.arm(true);long firstPath=replacement.route();
        LiveMapTransport replacementTx=LiveMapTransport.latest;
        check(firstPath>0&&firstPath<=Integer.MAX_VALUE,"compatible route ID stays positive signed32");
        NavigatorDriver.Observation off=new NavigatorDriver.Observation();off.mode="off";off.monotonicMs=SystemClock.now;
        replacement.bridge.observe(off,null,null);Handler.advance(0);
        check(!replacementTx.closed&&!AdasPassiveClient.latest.closed,"cancel guidance retains armed runtime and recorder");
        replacement.evidence(true);Handler.advance(1);long secondPath=replacement.route();
        check(secondPath>firstPath&&replacementTx.count(Topics.PATH_SELECTED)==2,"replacement route is actually sent with a new persisted ID");
        replacement.ack(firstPath);Handler.advance(1);replacement.observe(false);Handler.advance(0);
        check(replacementTx.count(Topics.SD_VEHICLE_LOCATION)==0,"old route ACK cannot release replacement dynamics");
        replacement.ack(secondPath);Handler.advance(1000);replacement.evidence(true);replacement.observe(false);Handler.advance(0);
        check(replacementTx.count(Topics.SD_VEHICLE_LOCATION)==1,"new route ACK plus current FINE releases replacement dynamics");
        replacement.bridge.observe(off,null,null);Handler.advance(0);replacement.evidence(true);Handler.advance(1);
        long thirdPath=replacement.route();check(thirdPath>secondPath&&replacementTx.count(Topics.PATH_SELECTED)==3,"second cancel and restart works within same arm");

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

        Rig stale=new Rig();stale.arm(true);stale.route();LiveMapTransport staleTx=LiveMapTransport.latest;
        Handler.advance(2000);check(staleTx.closed,"held gate snapshot expires and closes channel");

        Rig stock=new Rig();stock.arm(true);stock.observe(true);stock.context.pm.state=0;Handler.advance(0);
        check(LiveMapTransport.latest.topics.isEmpty(),"enabled stock map rejects already queued route");

        Rig failed=new Rig();failed.arm(true);LiveMapTransport failTx=LiveMapTransport.latest;failTx.result=-7;failed.observe(true);Handler.advance(0);
        check(failTx.count(Topics.ROUTE)==1&&failTx.count(Topics.PATH_SELECTED)==0,"failed actual route suppresses selection");
        failTx.result=0;failed.evidence(true);Handler.advance(1000);
        check(failTx.closed&&AdasPassiveClient.latest.closed&&"STOPPED".equals(failed.state().get("state")),
                "actual send failure is terminal for this arm even after transport recovery before tick");

        Rig bounce=new Rig();bounce.arm(true);LiveMapTransport bounceTx=LiveMapTransport.latest;
        java.lang.reflect.Method readiness=LiveMapBridge.class.getDeclaredMethod("readiness",long.class);readiness.setAccessible(true);
        bounce.evidence(false);readiness.invoke(bounce.bridge,SystemClock.now); // Real per-fire check, between polls.
        bounce.evidence(true);Handler.advance(1000);
        check(bounceTx.closed&&AdasPassiveClient.latest.closed&&"STOPPED".equals(bounce.state().get("state")),
                "observed gate loss stays terminal when fresh ON returns before coordinator tick");

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
        long newPath=rearmed.route();check(newPath>oldPath,"new arm keeps cross-session persisted ID freshness");

        Rig queuedArm=new Rig();queuedArm.arm(true);queuedArm.route();AdasPassiveClient queuedOld=AdasPassiveClient.latest;
        java.lang.reflect.Field budgets=LiveMapBridge.class.getDeclaredField("callbacks");budgets.setAccessible(true);
        Object oldBudget=budgets.get(queuedArm.bridge);
        for(int i=0;i<32;i++)check(queuedOld.sink.enqueue(()->{}),"old arm admits only its own bounded pending callbacks");
        queuedArm.arm.put("armId",UUID.randomUUID().toString());queuedArm.arm.put("issuedAtElapsedMs",SystemClock.now);
        queuedArm.arm.put("expiresAtElapsedMs",SystemClock.now+60000);queuedArm.evidence(true);queuedArm.write("denza-adas-live-arm.json",queuedArm.arm);
        java.lang.reflect.Method tick=LiveMapBridge.class.getDeclaredMethod("tick");tick.setAccessible(true);tick.invoke(queuedArm.bridge);
        LiveMapTransport queuedNewTx=LiveMapTransport.latest;Object newBudget=budgets.get(queuedArm.bridge);
        check(oldBudget!=newBudget&&AdasPassiveClient.latest.sink.enqueue(()->{}),"32 obsolete callbacks cannot consume first new-arm callback slot");
        java.lang.reflect.Field overflowFlag=oldBudget.getClass().getDeclaredField("overflow");overflowFlag.setAccessible(true);
        overflowFlag.setBoolean(oldBudget,true); // Old Binder callback resumed after new begin and failed its own admission/post.
        Handler.advance(0);queuedArm.evidence(true);Handler.advance(1000);
        check(!queuedNewTx.closed&&"LIVE".equals(queuedArm.state().get("state")),"late old-arm overflow latch cannot poison new arm");
        java.lang.reflect.Field queueCount=oldBudget.getClass().getDeclaredField("queued");queueCount.setAccessible(true);
        check(((java.util.concurrent.atomic.AtomicInteger)queueCount.get(oldBudget)).get()==0
                &&((java.util.concurrent.atomic.AtomicInteger)queueCount.get(newBudget)).get()==0,"old and new callback completion accounting remains independent");

        Rig overflowArm=new Rig();overflowArm.arm(true);LiveMapTransport overflowTx=LiveMapTransport.latest;
        for(int i=0;i<32;i++)check(AdasPassiveClient.latest.sink.enqueue(()->{}),"current arm pending callback bound");
        check(!AdasPassiveClient.latest.sink.enqueue(()->{}),"current arm 33rd pending callback still rejects");
        Handler.advance(0);overflowArm.evidence(true);Handler.advance(1000);
        check(overflowTx.closed&&"STOPPED".equals(overflowArm.state().get("state")),"current arm overflow remains terminal after queued callbacks drain");

        Rig storage=new Rig();storage.arm(true);storage.context.commitOk=false;storage.observe(true);Handler.advance(0);
        check(LiveMapTransport.latest.closed&&LiveMapTransport.latest.topics.isEmpty(),"unpersisted path ID never sent");

        Rig old=new Rig();old.arm.put("consumerSession",UUID.randomUUID().toString());old.arm(true);
        check("BLOCKED".equals(old.state().get("state"))&&Boolean.TRUE.equals(old.state().get("quiescent")),"another process's arm cannot bind sender");
        System.out.println("LiveRuntimeTest: "+checks+" checks passed (fake Android/Binder boundary, production coordinator/protocol)");
    }
}
