package dev.denza.hud.probe;

import android.os.Handler;
import android.os.SystemClock;
import dev.denza.adaslink.*;
import java.util.*;

/** Production coordinator regression: transport calls, exact route ownership and both queue orders. */
final class RerouteHandoffTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void step(LiveRuntimeTest.Rig r,long ms,boolean observe)throws Exception {
        for(long t=0;t<ms;t+=250){r.evidence(true);Handler.advance(Math.min(250,ms-t));if(observe){r.observe(false);Handler.advance(0);}}
    }
    private static LiveRuntimeTest.Rig streaming()throws Exception {
        LiveRuntimeTest.Rig r=new LiveRuntimeTest.Rig();r.lon0=37.067;r.lat0=55.516;
        r.arm.put("variant",Json.parse("{\"id\":\"loop-frame\",\"frame\":\"STOCK_GCJ\",\"startProtocol\":\"stock\",\"naviSt\":false,\"instrumentNaviStatus\":false}"));
        r.arm(true);r.observe(true);Handler.advance(0);LiveMapTransport tx=LiveMapTransport.latest;
        check(AdasPassiveClient.latest.requests.size()==1&&tx.count(Topics.ROUTE)==0,"initial stock start keeps one ODD and its pre-route hold");
        step(r,5000,true);check(tx.lastSelected()>0&&tx.count(Topics.ROUTE)==1,"first stock-start route and selection are not lost");
        check(tx.statuses().subList(0,5).equals(Arrays.asList(100L,100L,106L,106L,106L)),"first stock-start statuses remain complete");
        r.ack(tx.lastSelected());step(r,1000,true);
        check(tx.count(Topics.SD_VEHICLE_LOCATION)>0,"first guidance has ACK/FINE positions");
        return r;
    }
    private static void yaw(LiveRuntimeTest.Rig r){NavigatorDriver.Observation o=r.observation(false);o.mode="rerouting";o.positionOnRoute=false;r.observe(o);Handler.advance(0);}
    private static void newSubject(LiveRuntimeTest.Rig r,String id){r.routeId=id;r.subject(id);r.offlineEvidence();}
    private static int notes(LiveRuntimeTest.Rig r,String kind)throws Exception {int n=0;for(String l:r.journal().split("\n"))if(l.contains("\"input\":\""+kind+"\""))n++;return n;}
    private static double positionPath(LiveMapTransport tx,int i){Map<?,?> b=(Map<?,?>)Pb.SD_VEHICLE_LOCATION_NOTIFY.decode(tx.payloads.get(i)).get("sdVehicleLocation");return ((Number)b.get("curSDRouteID")).doubleValue();}
    static void run()throws Exception {
        readyReplacement(false);readyReplacement(true);normalDynamicEgress();partialAndWrongSubject();pendingTimeout();inventoryLoss(false);inventoryLoss(true);cancelOrArrival(false);cancelOrArrival(true);failedConversion();sameIdChange();finalBoundaryChange();
        System.out.println("RerouteHandoffTest: "+checks+" checks passed");
    }
    static void readyReplacement(boolean reconcileFirst)throws Exception {
        LiveRuntimeTest.Rig r=streaming();LiveMapTransport tx=LiveMapTransport.latest;long old=tx.lastSelected();yaw(r);
        int before=tx.topics.size(),starts=notes(r,"navStart"),stops=notes(r,"navStop"),odd=AdasPassiveClient.latest.requests.size();long changed=SystemClock.now;
        newSubject(r,"replacement-"+reconcileFirst);
        if(reconcileFirst){step(r,250,false);check(tx.topics.size()==before,"reconcile-first pending sends no old packets");r.ack(old);check(tx.topics.size()==before,"old ACK cannot unblock pending egress");}
        r.observe(true);Handler.advance(0);long fresh=tx.lastSelected();
        check(fresh>old&&tx.count(Topics.ROUTE)==2,"ready replacement is published immediately in both queue orders");
        check(tx.first(Topics.ROUTE,changed)-changed<1000,"replacement has no fresh-start five-second hold");
        check(notes(r,"navStart")==starts&&notes(r,"navStop")==stops&&AdasPassiveClient.latest.requests.size()==odd,"yaw handoff has no end/start or extra ODD");
        check(r.journal().contains("\"amapRouteType\":2"),"yaw route type survives the handoff");
        int count=tx.count(Topics.SD_VEHICLE_LOCATION);r.ack(old);step(r,250,true);
        check(tx.count(Topics.SD_VEHICLE_LOCATION)==count,"old path ACK does not release new path positions");
        r.ack(fresh);step(r,250,true);check(tx.count(Topics.SD_VEHICLE_LOCATION)>count,"new path ACK plus current FINE releases positions");
        for(int i=before;i<tx.topics.size();i++)if(tx.topics.get(i)==Topics.SD_VEHICLE_LOCATION)check(positionPath(tx,i)==fresh,"all replacement positions carry the new numeric path ID");
    }
    static void normalDynamicEgress()throws Exception {
        LiveRuntimeTest.Rig r=streaming();LiveMapTransport tx=LiveMapTransport.latest;int before=tx.count(Topics.ADV_AUTO_FUN_INFO);
        step(r,2000,true);check(tx.count(Topics.ADV_AUTO_FUN_INFO)>before,"ordinary same-subject guidance retains synchronous ADV_AUTO_FUN_INFO egress");
    }
    static void partialAndWrongSubject()throws Exception {
        LiveRuntimeTest.Rig r=streaming();LiveMapTransport tx=LiveMapTransport.latest;r.automaticOffline=false;int before=tx.topics.size();
        // Cheap identity revocation precedes new geometry; the previous scoped snapshot is still fresh.
        r.routeId="partial-new";r.routeEpoch++;r.offlineSubject=new OfflineMapGate.Subject(r.routeId,r.routeEpoch,null);r.bridge.sourceRoute(r.offlineSubject);
        step(r,500,false);check(tx.topics.size()==before&&notes(r,"navStop")==0,"null geometry plus old snapshot pauses without tearing down or sending old data");
        r.offlineSubject=new OfflineMapGate.Subject(r.routeId,r.routeEpoch,LiveRuntimeTest.Rig.GEOMETRY);r.bridge.sourceRoute(r.offlineSubject);r.offlineEvidence();
        NavigatorDriver.Observation wrong=r.observation(true);wrong.routeEpoch=r.routeEpoch-1;wrong.offlineGeometrySha256=LiveRuntimeTest.Rig.GEOMETRY;
        long now=SystemClock.now,wall=1_792_836_000_000L+now;
        r.bridge.observe(wrong,FixFreshness.capture(now,wall,now,now,wall),null);Handler.advance(0);
        wrong=r.observation(true);wrong.routeEpoch=r.routeEpoch;wrong.offlineGeometrySha256=LiveRuntimeTest.Rig.ALT_GEOMETRY;
        r.bridge.observe(wrong,FixFreshness.capture(now,wall,now,now,wall),null);Handler.advance(0);
        r.observe(false);Handler.advance(0);check(tx.topics.size()==before,"wrong epoch/hash prepared observations and route-less updates cannot adopt a replacement");
        r.observe(true);Handler.advance(0);check(tx.count(Topics.ROUTE)==2&&notes(r,"navStop")==0,"current full geometry and proof complete the existing handoff");
    }
    static void pendingTimeout()throws Exception {
        LiveRuntimeTest.Rig r=streaming();r.automaticOffline=false;LiveMapTransport tx=LiveMapTransport.latest;int before=tx.topics.size();newSubject(r,"timeout-new");
        for(int i=0;i<24;i++){if(i==8)newSubject(r,"timeout-newer");r.offlineEvidence();step(r,250,false);}
        check(notes(r,"liveSession.offlineWithdrawal")==1&&notes(r,"navStop")==1,"unprepared replacement times out despite inventory refresh and identity churn");
        for(int i=before;i<tx.topics.size();i++)check(tx.topics.get(i)==Topics.NAVIGATION_STATUS,"timeout never releases old positions");
    }
    static void inventoryLoss(boolean stale)throws Exception {
        LiveRuntimeTest.Rig r=streaming();r.automaticOffline=false;LiveMapTransport tx=LiveMapTransport.latest;newSubject(r,"loss-new");step(r,250,false);int before=tx.topics.size();
        if(stale)r.bridge.offlineMap(OfflineMapGate.Snapshot.available(r.offlineSubject,SystemClock.now-OfflineMapGate.MAX_AGE_MS,LiveRuntimeTest.Rig.INVENTORY));
        else r.bridge.offlineMap(OfflineMapGate.Snapshot.unavailable(r.offlineSubject,SystemClock.now,LiveRuntimeTest.Rig.INVENTORY,"removed","Офлайн-карта удалена"));
        step(r,250,false);check(notes(r,"liveSession.offlineWithdrawal")==1&&notes(r,"navStop")==1,"actual unavailable/stale inventory immediately withdraws pending replacement");
        for(int i=before;i<tx.topics.size();i++)check(tx.topics.get(i)==Topics.NAVIGATION_STATUS,"inventory failure permits only end status");
    }
    static void cancelOrArrival(boolean arrived)throws Exception {
        LiveRuntimeTest.Rig r=streaming();LiveMapTransport tx=LiveMapTransport.latest;newSubject(r,"cancel-new");step(r,250,false);
        NavigatorDriver.Observation o=r.observation(false);if(arrived)o.finished=true;else o.mode="off";
        r.observe(o);Handler.advance(0);step(r,250,false);
        check(notes(r,"navStop")==1&&tx.count(Topics.ROUTE)==1&&AdasPassiveClient.latest.requests.size()==1,"cancel/arrival during pending ends old guidance and cannot start replacement");
    }
    static void failedConversion()throws Exception {
        LiveRuntimeTest.Rig r=streaming();newSubject(r,"bad-conversion");r.lon0=49.1;r.observe(true);Handler.advance(0);
        LiveMapTransport tx=LiveMapTransport.latest;check(tx.count(Topics.ROUTE)==1&&!tx.closed,"failed conversion cannot relabel or send the old path");
        int before=tx.topics.size();step(r,5500,true);check(notes(r,"navStop")==1,"repeated failed conversion cannot extend replacement timeout");
        for(int i=before;i<tx.topics.size();i++)check(tx.topics.get(i)==Topics.NAVIGATION_STATUS,"failed replacement sends no old dynamics");
    }
    static void sameIdChange()throws Exception {
        LiveRuntimeTest.Rig r=streaming();r.automaticOffline=false;r.offlineSubject=new OfflineMapGate.Subject(r.routeId,r.routeEpoch+1,LiveRuntimeTest.Rig.ALT_GEOMETRY);r.routeEpoch++;
        r.bridge.sourceRoute(r.offlineSubject);r.offlineEvidence();step(r,250,false);
        check(notes(r,"navStop")==1,"same-ID epoch/geometry change withdraws rather than reusing its frozen table/ACK");
    }
    static void finalBoundaryChange()throws Exception {
        LiveRuntimeTest.Rig r=streaming();LiveMapTransport tx=LiveMapTransport.latest;int before=tx.topics.size();
        tx.beforeAdmission=()->newSubject(r,"marshal-replacement");step(r,1000,false);
        check(tx.topics.size()==before&&!tx.closed,"post-marshalling source change locally vetoes egress without terminal transport failure");
        r.observe(true);Handler.advance(0);check(tx.count(Topics.ROUTE)==2&&!tx.closed,"same arm can adopt replacement after the final-boundary veto");
    }
}
