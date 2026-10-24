package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

/** Memory-only transport adversaries. No Android, Binder, device or activation path. */
public final class LiveEgressGuardTest {
    private static int checks;
    private static void check(boolean ok,String why) { checks++; if(!ok)throw new AssertionError(why); }
    private static final class Call { final long topic; final byte[] bytes; Call(long t,byte[] b){topic=t;bytes=b;} }
    private static class Transport implements Publisher,AutoCloseable {
        final List<Call> calls=new ArrayList<>(); final List<Long> offers=new ArrayList<>();
        int offerResult,routeResult,selectionResult,closes; boolean throwsFire;
        Runnable insideFire,afterMarshal;
        LongPredicate admission;
        @Override public BridgeStatus.Mode mode(){return BridgeStatus.Mode.LIVE;}
        @Override public int startService(long service){offers.add(service);return offerResult;}
        @Override public int fire(long topic,byte[] payload,long now){
            if(afterMarshal!=null)afterMarshal.run();
            if(admission!=null&&!admission.test(topic))return -203;
            calls.add(new Call(topic,payload)); if(insideFire!=null)insideFire.run();
            if(throwsFire)throw new IllegalStateException("transport failed");
            return topic==Topics.ROUTE?routeResult:topic==Topics.PATH_SELECTED?selectionResult:0;
        }
        @Override public void note(String k,Map<String,Object> f,long n){}
        @Override public void close(){closes++;}
        int count(long topic){int n=0;for(Call c:calls)if(c.topic==topic)n++;return n;}
    }
    private static final class Audit implements Publisher {
        boolean fail; int packetNotes; Runnable duringAttempt;
        @Override public BridgeStatus.Mode mode(){return BridgeStatus.Mode.DRY_RUN;}
        @Override public int startService(long service){throw new AssertionError("audit offer");}
        @Override public int fire(long topic,byte[] payload,long now){throw new AssertionError("audit fire cannot pretend success");}
        @Override public void note(String kind,Map<String,Object> fields,long now){
            if(fail)throw new IllegalStateException("disk unavailable");
            if("liveEgress.attempt".equals(kind)&&duringAttempt!=null)duringAttempt.run();
            if("liveEgress.packet".equals(kind))packetNotes++;
        }
    }
    private static final class Rig {
        final ManualScheduler time=new ManualScheduler(100_000);
        final Transport transport=new Transport(); final Audit audit=new Audit();
        final List<Long> selected=new ArrayList<>();
        boolean permitted=true; long epoch=1,expires=Long.MAX_VALUE,wallJump;
        Runnable duringPrerequisites;
        LiveEgressGuard.Readiness override;
        final LiveEgressGuard guard;
        Rig(){this(false);}
        Rig(boolean terminal){
            guard=new LiveEgressGuard(transport,audit,time,now->{
                LiveEgressGuard.Readiness result=override!=null?override:permitted?ready(epoch,now,expires):null;
                if(duringPrerequisites!=null)duringPrerequisites.run();return result;
            },selected::add,terminal);
            guard.setWallClock(()->wall());
        }
        long wall(){return 1_000_000+time.now()+wallJump;}
        void join(){for(long service:Topics.SERVICES)check(guard.startService(service)==0,"own offer admitted");}
        void begin(long id,int count){guard.routeChanged(id);guard.beginRoute(id,count);}
        int packet(long topic,byte[] bytes){return guard.fire(topic,bytes,time.now());}
        void route(long id){begin(id,1);check(packet(Topics.ROUTE,chunk(id,0,1,1))==0,"route transport success");check(packet(Topics.PATH_SELECTED,StockMessages.pathSelected(id))==0,"selection transport success");}
        void ack(long id){check(guard.onAck(epoch,Topics.PATH_SELECT_CONFIRM,time.now(),StockMessages.pathSelectConfirm(id,200))==RealPathAck.Decision.ACCEPTED,"real ACK accepted");}
        void fine(long id){guard.fineFix(id,FixFreshness.capture(time.now(),wall(),time.now(),time.now(),wall()),"FINE");}
        int position(long id){return packet(Topics.SD_VEHICLE_LOCATION,StockMessages.vehicleLocation(49.1,55.8,49.1,55.8,0,0,90,id,0,0,0,0,1));}
    }
    private static LiveEgressGuard.Readiness ready(long epoch,long now,long expiry){return new LiveEgressGuard.Readiness(epoch,now,expiry,true,true,true,true,true,true,true,true);}
    private static String path(long id){return "{\"data\":[{\"pathID\":"+id+"}]}";}
    private static byte[] chunk(long id,int index,int count,long time){return part(path(id),index,count,time);}
    private static byte[] part(String content,int index,int count,long time){
        Map<String,Object> e=Json.object();e.put("currentIndex",(long)index);e.put("length",(long)count);e.put("pathCount",1L);e.put("time",time);e.put("payload",content);
        return StockMessages.route(Json.write(e),1);
    }
    public static void main(String[] args){readiness();chunks();acksAndFreshness();queuedAndStop();failures();producerIntegration();postMarshal();terminalLifecycle();System.out.println("LiveEgressGuardTest: "+checks+" checks passed");}

    private static void terminalLifecycle(){
        Rig lost=new Rig(true);lost.join();lost.route(201);lost.ack(201);lost.fine(201);
        int sent=lost.transport.calls.size();lost.permitted=false;
        check(lost.position(201)!=0&&lost.guard.isClosed()&&lost.transport.closes==1,"observed prerequisites loss immediately closes owned production transport");
        lost.permitted=true;lost.guard.routeChanged(202);lost.guard.beginRoute(202,1);
        check(lost.packet(Topics.ROUTE,chunk(202,0,1,1))!=0&&lost.transport.calls.size()==sent,"same arm cannot recover after gates restore before coordinator tick");

        Rig failed=new Rig(true);failed.join();failed.begin(203,1);failed.transport.routeResult=-7;
        check(failed.packet(Topics.ROUTE,chunk(203,0,1,1))==-7&&failed.guard.isClosed()&&failed.transport.closes==1,"actual current send failure is terminal and preserves result");
        failed.transport.routeResult=0;check(failed.packet(Topics.ROUTE,chunk(203,0,1,1))!=0&&failed.transport.calls.size()==1,"same-arm transport recovery cannot retry after actual failure");
        Rig audit=new Rig(true);audit.join();audit.begin(204,1);audit.audit.fail=true;
        check(audit.packet(Topics.ROUTE,chunk(204,0,1,1))!=0&&audit.guard.isClosed()&&audit.transport.calls.isEmpty(),"current audit failure closes before actual transaction");

        Rig stale=new Rig(true);stale.join();stale.route(205);stale.ack(205);stale.fine(205);stale.time.advanceTo(101_001);
        check(stale.position(205)!=0&&!stale.guard.isClosed(),"held stale FINE is packet rejection, not terminal failure");
        check(stale.guard.onAck(stale.epoch,Topics.PATH_SELECT_CONFIRM,stale.time.now(),StockMessages.pathSelectConfirm(999,200))!=RealPathAck.Decision.ACCEPTED
                &&!stale.guard.isClosed(),"foreign callback does not close healthy production arm");
        stale.fine(205);check(stale.position(205)==0,"new genuine FINE can recover from a held stale fix");

        Rig lateFine=new Rig(true);lateFine.transport.admission=lateFine.guard::finalLeaseValid;lateFine.join();lateFine.route(206);lateFine.ack(206);lateFine.fine(206);
        lateFine.transport.afterMarshal=()->lateFine.time.advanceTo(101_001);
        check(lateFine.position(206)!=0&&!lateFine.guard.isClosed(),"post-marshalling FINE expiry is also nonterminal");
        lateFine.transport.afterMarshal=null;lateFine.fine(206);check(lateFine.position(206)==0,"post-marshalling stale rejection retains ACK for next genuine fix");

        Rig repeat=new Rig(true);repeat.join();
        for(long id=210;id<215;id++){
            repeat.time.advanceTo(repeat.time.now()+1);repeat.route(id);repeat.ack(id);repeat.fine(id);
            check(repeat.position(id)==0,"healthy repeated route owns fresh ACK/FINE");repeat.guard.navigationStopped();
            check(repeat.packet(Topics.NAVIGATION_STATUS,StockMessages.navigationStatus(100))==0&&!repeat.guard.isClosed(),"healthy stop preserves reusable arm");
        }
        Rig early=new Rig(true);early.join();early.begin(220,1);early.guard.navigationStopped();
        check(!early.guard.isClosed()&&!early.guard.isStopping(),"cancel before first actual chunk remains healthy idle");early.route(221);

        Rig superseded=new Rig(true);superseded.transport.admission=superseded.guard::finalLeaseValid;superseded.join();superseded.route(230);superseded.guard.navigationStopped();
        superseded.transport.afterMarshal=()->{superseded.guard.routeChanged(231);superseded.guard.beginRoute(231,1);};
        check(superseded.packet(Topics.NAVIGATION_STATUS,StockMessages.navigationStatus(100))!=0&&!superseded.guard.isClosed()
                &&superseded.guard.currentPathId()==231,"post-marshalling old stop cannot close replacement");
        superseded.transport.afterMarshal=null;
        check(superseded.packet(Topics.ROUTE,chunk(231,0,1,1))==0,"replacement group survives rejected old stop completion");

        Rig oldFailure=new Rig(true);oldFailure.join();oldFailure.begin(240,1);oldFailure.transport.routeResult=-7;
        oldFailure.transport.insideFire=()->{oldFailure.guard.routeChanged(241);oldFailure.guard.beginRoute(241,1);};
        check(oldFailure.packet(Topics.ROUTE,chunk(240,0,1,1))!=0&&!oldFailure.guard.isClosed()&&oldFailure.guard.currentPathId()==241,
                "delayed failure from superseded route cannot fault new generation");
        oldFailure.transport.insideFire=null;oldFailure.transport.routeResult=0;
        check(oldFailure.packet(Topics.ROUTE,chunk(241,0,1,1))==0,"old route cleanup cannot erase replacement chunk group");
        oldFailure.guard.beginRoute(240,1);
        check(oldFailure.packet(Topics.PATH_SELECTED,StockMessages.pathSelected(241))==0,"late old beginRoute cannot erase current completed group");

        Rig oldAudit=new Rig(true);oldAudit.join();oldAudit.begin(250,1);
        oldAudit.audit.duringAttempt=()->{oldAudit.guard.routeChanged(251);oldAudit.guard.beginRoute(251,1);throw new IllegalStateException("old delayed audit");};
        check(oldAudit.packet(Topics.ROUTE,chunk(250,0,1,1))!=0&&!oldAudit.guard.isClosed()&&oldAudit.guard.currentPathId()==251,
                "old audit failure does not mutate superseding generation");
        oldAudit.audit.duringAttempt=null;check(oldAudit.packet(Topics.ROUTE,chunk(251,0,1,1))==0,"replacement survives old audit completion");

        Rig oldQuery=new Rig(true);oldQuery.join();oldQuery.begin(260,1);oldQuery.permitted=false;
        oldQuery.duringPrerequisites=()->{oldQuery.duringPrerequisites=null;oldQuery.permitted=true;
            oldQuery.guard.routeChanged(261);oldQuery.guard.beginRoute(261,1);};
        check(oldQuery.packet(Topics.ROUTE,chunk(260,0,1,1))!=0&&!oldQuery.guard.isClosed()&&oldQuery.guard.currentPathId()==261,
                "old prerequisite failure completed after replacement cannot close its healthy generation");
        check(oldQuery.packet(Topics.ROUTE,chunk(261,0,1,1))==0,"replacement retains its route group after stale prerequisite completion");

        Rig binderCode=new Rig(true);binderCode.transport.admission=binderCode.guard::finalLeaseValid;binderCode.join();binderCode.begin(270,1);
        binderCode.transport.routeResult=-203;
        check(binderCode.packet(Topics.ROUTE,chunk(270,0,1,1))==-203&&binderCode.guard.isClosed(),
                "a real transport failure numerically matching admission rejection is terminal when final lease allowed it");

        long[] now={100_000};Scheduler paused=new Scheduler(){
            public long now(){return now[0];}public void post(Object t,long d,Runnable task){}public void cancel(Object t){}
        };
        Transport stoppedTransport=new Transport();LiveEgressGuard stopped=new LiveEgressGuard(stoppedTransport,new Audit(),paused,
                at->ready(1,at,Long.MAX_VALUE),id->{},true);stoppedTransport.admission=stopped::finalLeaseValid;
        for(long service:Topics.SERVICES)stopped.startService(service);stopped.routeChanged(280);stopped.beginRoute(280,1);
        check(stopped.fire(Topics.ROUTE,chunk(280,0,1,1),now[0])==0,"production stop-deadline fixture has an actual route");stopped.navigationStopped();
        int stopCount=stoppedTransport.calls.size();stoppedTransport.afterMarshal=()->now[0]+=1001;
        check(stopped.fire(Topics.NAVIGATION_STATUS,StockMessages.navigationStatus(100),now[0])!=0
                &&stoppedTransport.calls.size()==stopCount&&!stopped.isClosed()&&!stopped.isStopping(),
                "healthy production stop deadline rejects old packet and returns to idle");
        stoppedTransport.afterMarshal=null;stopped.routeChanged(281);stopped.beginRoute(281,1);
        check(stopped.fire(Topics.ROUTE,chunk(281,0,1,1),now[0])==0,"arm remains reusable after bounded stop expiry");
    }
    private static void readiness(){
        Rig r=new Rig();r.guard.routeChanged(10);r.guard.beginRoute(10,1);
        check(r.packet(Topics.ROUTE,chunk(10,0,1,1))!=0&&r.transport.calls.isEmpty(),"all four own joins required");
        r.permitted=false;check(r.guard.startService(Topics.SERVICES[0])!=0&&r.transport.offers.isEmpty(),"unknown readiness blocks actual offer");
        r.permitted=true;r.expires=r.time.now();check(r.guard.startService(Topics.SERVICES[0])!=0,"TTL equality is expired");
        r.expires=Long.MAX_VALUE;
        for(int bit=0;bit<8;bit++){
            boolean[] b={true,true,true,true,true,true,true,true};b[bit]=false;
            r.override=new LiveEgressGuard.Readiness(1,r.time.now(),Long.MAX_VALUE,b[0],b[1],b[2],b[3],b[4],b[5],b[6],b[7]);
            check(r.guard.startService(Topics.SERVICES[0])!=0,"every prerequisite is mandatory "+bit);
        }
        r.override=ready(1,r.time.now()+1,Long.MAX_VALUE);check(r.guard.startService(Topics.SERVICES[0])!=0,"future readiness capture rejected");
        r.override=null;r.join();r.route(10);r.ack(10);r.fine(10);check(r.position(10)==0,"fully ready real-selected fresh path admitted");
        int before=r.transport.calls.size();
        check(r.packet(Topics.event(13,1),new byte[0])!=0&&r.packet(Topics.PATH_SELECT_CONFIRM,new byte[0])!=0,"control and inbound topics never published");
        check(r.transport.calls.size()==before,"forbidden topics do not reach transport");
        r.permitted=false;check(r.position(10)!=0&&r.guard.confirmedPathId()==-1,"prerequisite loss revokes route and ACK");
        r.permitted=true;check(r.position(10)!=0,"readiness restoration does not restore old path");
        r.begin(10,1);check(r.packet(Topics.ROUTE,chunk(10,0,1,1))!=0,"wire ID cannot be retried after loss");
        r.epoch=2;r.guard.routeChanged(11);r.guard.beginRoute(11,1);
        check(r.packet(Topics.ROUTE,chunk(11,0,1,1))!=0,"new transport epoch needs four new own offers");
        r.join();r.route(11);check(r.guard.onAck(1,Topics.PATH_SELECT_CONFIRM,r.time.now(),StockMessages.pathSelectConfirm(11,200))==RealPathAck.Decision.WRONG_SESSION,"old queued callback epoch rejected");
        r.guard.routeChanged(1L<<53);r.guard.beginRoute(1L<<53,1);check(r.packet(Topics.ROUTE,chunk(1L<<53,0,1,1))!=0,"double route-ID exactness boundary");
    }
    private static void chunks(){
        Rig r=new Rig();r.join();r.begin(20,2);String p=path(20);int cut=p.length()/2;
        check(r.packet(Topics.ROUTE,part(p.substring(0,cut),0,2,123))==0,"first chunk real fire");
        check(r.packet(Topics.PATH_SELECTED,StockMessages.pathSelected(20))!=0,"selection cannot precede final actual chunk");
        check(r.packet(Topics.ROUTE,part(p.substring(cut),1,2,123))==0,"last chunk completes matching route JSON");
        check(r.packet(Topics.PATH_SELECTED,StockMessages.pathSelected(20))==0,"complete route then selection");
        check(r.transport.count(Topics.ROUTE)==2&&r.audit.packetNotes==3,"all actual results audited without emulated fire");
        check(r.packet(Topics.PATH_SELECTED,StockMessages.pathSelected(20))!=0,"selection cannot repeat without new group");
        Rig wrong=new Rig();wrong.join();wrong.begin(21,1);
        check(wrong.packet(Topics.ROUTE,chunk(22,0,1,1))!=0&&wrong.transport.calls.isEmpty(),"assembled foreign path rejected before single chunk fire");
        wrong.begin(22,1);check(wrong.packet(Topics.ROUTE,chunk(22,1,1,1))!=0,"out of order chunk rejected");
        wrong.begin(23,1);check(wrong.packet(Topics.ROUTE,chunk(23,0,2,1))!=0,"chunk total cannot alter beginRoute count");
        wrong.begin(24,2);check(wrong.packet(Topics.ROUTE,part("{",0,2,1))==0,"partial JSON accepted only inside declared group");
        check(wrong.packet(Topics.ROUTE,part("}",1,2,2))!=0,"inconsistent envelope time rejected");
        check(wrong.packet(Topics.PATH_SELECTED,StockMessages.pathSelected(24))!=0,"malformed group cannot select");
        wrong.begin(25,LiveEgressGuard.MAX_CHUNKS+1);check(wrong.packet(Topics.ROUTE,chunk(25,0,1,1))!=0,"chunk budget rejected");
        wrong.begin(26,1);check(wrong.packet(Topics.ROUTE,new byte[]{10,(byte)255})!=0,"malformed protobuf rejected");
        wrong.begin(27,1);check(wrong.packet(Topics.ROUTE,new byte[LiveEgressGuard.MAX_PACKET_BYTES+1])!=0,"packet byte budget checked before parse");
        wrong.begin(28,1);String deep="";for(int i=0;i<65;i++)deep+="[";for(int i=0;i<65;i++)deep+="]";
        check(wrong.packet(Topics.ROUTE,part(deep,0,1,1))!=0,"route JSON nesting budget rejected");
        Rig fail=new Rig();fail.join();fail.begin(29,1);fail.transport.routeResult=-5;
        check(fail.packet(Topics.ROUTE,chunk(29,0,1,1))==-5,"actual route failure retained");
        check(fail.packet(Topics.PATH_SELECTED,StockMessages.pathSelected(29))!=0&&fail.transport.count(Topics.PATH_SELECTED)==0,"failed route suppresses selection");
    }
    private static void acksAndFreshness(){
        Rig r=new Rig();r.join();r.route(30);r.fine(30);check(r.position(30)!=0,"transport success alone cannot open dynamic feed");
        check(r.guard.onAck(1,Topics.event(13,0x8002),r.time.now(),StockMessages.pathSelectConfirm(30,200))==RealPathAck.Decision.WRONG_TOPIC,"service13 status cannot act as selected44");
        byte[] absent=Pb.PATH_SELECT_CONFIRM_NOTIFY.encode((Object)Pb.PATH_SELECT_CONFIRM.encode(null,null,30L,null,null,null,null,null));
        check(r.guard.onAck(1,Topics.PATH_SELECT_CONFIRM,r.time.now(),absent)==RealPathAck.Decision.MISSING_FIELDS,"missing status does not default to success");
        check(r.guard.onAck(1,Topics.PATH_SELECT_CONFIRM,r.time.now(),StockMessages.pathSelectConfirm(31,200))==RealPathAck.Decision.UNRELATED,"foreign route ACK ignored");
        r.ack(30);check(r.position(30)==0,"real current path status200 opens fresh dynamic feed");
        check(r.position(31)!=0&&r.position(-1)!=0,"position payload path is independently checked");
        check(r.packet(Topics.NAVIGATION_STATUS,StockMessages.navigationStatus(100))!=0,"active route cannot emit stock idle loop");
        r.time.advanceTo(101_000);check(r.position(30)==0,"1000ms fresh boundary inclusive");
        r.time.advanceTo(101_001);check(r.position(30)!=0,"1001ms lease rejects stationary repeats");
        r.guard.fineFix(30,FixFreshness.capture(100_000,1_100_000,101_001,101_001,r.wall()),"FINE");
        check(r.position(30)!=0,"re-reading held identity cannot refresh anchor");
        r.fine(30);check(r.position(30)==0,"new genuine FINE sample restores lease");
        r.guard.fineFix(30,FixFreshness.capture(999_000,r.wall(),999_000,r.time.now(),r.wall()),"EXTRAPOLATED");
        check(r.position(30)!=0,"EXTRAPOLATED revokes current FINE eligibility");
        r.fine(30);check(r.position(30)==0,"prediction timestamp does not poison FINE highwater");
        r.wallJump=60_000;check(r.position(30)!=0,"wall jump rejects held fix");r.wallJump=0;
        r.guard.routeChanged(31);r.guard.beginRoute(31,1);r.packet(Topics.ROUTE,chunk(31,0,1,1));r.packet(Topics.PATH_SELECTED,StockMessages.pathSelected(31));r.ack(31);
        r.guard.fineFix(31,FixFreshness.capture(r.time.now(),r.wall(),r.time.now(),r.time.now(),r.wall()),"FINE");
        check(r.position(31)!=0,"same fix cannot gain new route ownership");r.time.advanceTo(101_002);r.fine(31);check(r.position(31)==0,"new fix owns new path");
        Rig future=new Rig();future.join();future.route(40);future.ack(40);
        future.guard.fineFix(40,FixFreshness.capture(100_001,future.wall(),100_000,100_000,future.wall()),"FINE");
        future.time.advanceTo(100_500);future.guard.fineFix(40,FixFreshness.capture(100_001,1_100_000,100_500,100_500,future.wall()),"FINE");
        check(future.position(40)!=0,"future source identity stays invalid after time catches up");
        future.fine(40);future.time.advanceTo(140_500);future.wallJump=-40_000;
        check(future.position(40)!=0,"elapsed age rejects sleep even when wall is reset");
        Rig failed=new Rig();failed.join();failed.begin(41,1);failed.packet(Topics.ROUTE,chunk(41,0,1,1));failed.transport.selectionResult=-7;
        check(failed.packet(Topics.PATH_SELECTED,StockMessages.pathSelected(41))==-7,"selection failure retained");
        check(failed.guard.onAck(1,Topics.PATH_SELECT_CONFIRM,failed.time.now(),StockMessages.pathSelectConfirm(41,200))!=RealPathAck.Decision.ACCEPTED,"failed selection cannot arm ACK barrier");
        Rig fast=new Rig();fast.join();fast.begin(42,1);fast.packet(Topics.ROUTE,chunk(42,0,1,1));
        long[] received={-1};fast.transport.insideFire=()->{received[0]=fast.time.now();fast.time.advanceTo(fast.time.now()+5);};
        check(fast.packet(Topics.PATH_SELECTED,StockMessages.pathSelected(42))==0,"selection commits after synchronous fire returns");
        check(fast.guard.onAck(1,Topics.PATH_SELECT_CONFIRM,received[0],StockMessages.pathSelectConfirm(42,200))==RealPathAck.Decision.ACCEPTED,"ACK received during fire uses pre-fire timestamp");
    }
    private static void queuedAndStop(){
        Rig r=new Rig();r.join();r.begin(50,1);r.guard.routeChanged(51);r.guard.beginRoute(50,1);
        check(r.packet(Topics.ROUTE,chunk(50,0,1,1))!=0,"late old beginRoute cannot publish under new model");
        r.route(52);r.ack(52);r.fine(52);r.guard.navigationStopped();
        check(r.position(52)!=0,"navStop immediately blocks dynamics");
        check(r.packet(Topics.NAVIGATION_STATUS,StockMessages.navigationStatus(101))!=0,"only own explicit stop status allowed while stopping");
        check(r.packet(Topics.NAVIGATION_STATUS,StockMessages.navigationStatus(100))==0,"one explicit own status100 allowed after route");
        int n=r.transport.calls.size();check(r.packet(Topics.NAVIGATION_STATUS,StockMessages.navigationStatus(100))!=0&&r.transport.calls.size()==n,"second idle status never reaches wire");
        r.time.advanceTo(110_000);check(r.transport.closes==0&&!r.guard.isClosed(),"normal route stop retains armed transport");
        r.route(53);r.fine(53);check(r.position(53)!=0,"replacement still needs its own ACK");
        check(r.guard.onAck(r.epoch,Topics.PATH_SELECT_CONFIRM,r.time.now(),StockMessages.pathSelectConfirm(52,200))!=RealPathAck.Decision.ACCEPTED&&r.position(53)!=0,"previous route ACK cannot authorize replacement");
        r.ack(53);check(r.position(53)==0,"new route works after normal stop with its own ACK and fix");
        r.guard.close();r.guard.close();check(r.transport.closes==1,"explicit session close remains terminal and idempotent");
        Rig silent=new Rig();silent.join();silent.route(60);silent.guard.navigationStopped();silent.time.advanceTo(101_000);
        check(silent.transport.closes==0&&!silent.guard.isStopping()&&silent.transport.count(Topics.NAVIGATION_STATUS)==0,"stop grace goes idle without inventing a stop packet");
        Rig immediate=new Rig();immediate.join();immediate.route(63);immediate.guard.navigationStopped();immediate.route(64);
        check(immediate.guard.currentPathId()==64&&!immediate.guard.isClosed(),"immediate replacement cancels old stop deadline");
        check(immediate.packet(Topics.NAVIGATION_STATUS,StockMessages.navigationStatus(100))!=0,"late old stop cannot clear new route");
        immediate.time.advanceTo(101_000);check(immediate.guard.currentPathId()==64&&!immediate.guard.isClosed(),"old stop timer cannot close replacement");
        Rig gate=new Rig();gate.join();gate.route(61);gate.permitted=false;gate.guard.navigationStopped();
        check(gate.transport.closes==1&&gate.packet(Topics.NAVIGATION_STATUS,StockMessages.navigationStatus(100))!=0,"lost prerequisites allow cleanup only, no stop packet");
        Rig unsent=new Rig();unsent.join();unsent.begin(65,1);unsent.guard.navigationStopped();
        check(!unsent.guard.isClosed()&&unsent.transport.calls.isEmpty(),"cancel before first route chunk stays idle without status100");
        unsent.route(66);check(unsent.guard.currentPathId()==66&&unsent.transport.count(Topics.PATH_SELECTED)==1,"cancel before first send accepts new route");
        Rig superseded=new Rig();superseded.join();superseded.route(67);superseded.guard.navigationStopped();
        superseded.audit.duringAttempt=()->superseded.guard.routeChanged(68);
        check(superseded.packet(Topics.NAVIGATION_STATUS,StockMessages.navigationStatus(100))!=0,"superseded queued stop never sends");
        check(!superseded.guard.isClosed()&&superseded.guard.currentPathId()==68,"old stop completion cannot close replacement route");
        Rig queued=new Rig();queued.join();queued.begin(62,1);queued.time.post(null,0,()->queued.packet(Topics.ROUTE,chunk(62,0,1,1)));queued.guard.close();queued.time.drain();
        check(queued.transport.calls.isEmpty(),"uncancelled anonymous task still fails at egress after close");
    }
    private static void failures(){
        Rig r=new Rig();r.join();r.begin(70,1);r.audit.fail=true;
        check(r.packet(Topics.ROUTE,chunk(70,0,1,1))!=0&&r.transport.calls.isEmpty(),"pre-fire audit failure prevents wire send");
        Rig mid=new Rig();mid.join();mid.begin(71,1);mid.transport.insideFire=()->mid.permitted=false;
        check(mid.packet(Topics.ROUTE,chunk(71,0,1,1))!=0&&mid.transport.count(Topics.ROUTE)==1,"readiness loss during Binder call prevents success commit");
        check(mid.packet(Topics.PATH_SELECTED,StockMessages.pathSelected(71))!=0&&mid.transport.count(Topics.PATH_SELECTED)==0,"in-call loss suppresses subsequent selection");
        Rig epoch=new Rig();epoch.join();epoch.begin(72,1);epoch.transport.insideFire=()->epoch.epoch++;
        check(epoch.packet(Topics.ROUTE,chunk(72,0,1,1))!=0,"in-call transport epoch change rejects completion");
        Rig thrown=new Rig();thrown.join();thrown.begin(73,1);thrown.transport.throwsFire=true;
        check(thrown.packet(Topics.ROUTE,chunk(73,0,1,1))!=0&&thrown.guard.confirmedPathId()==-1,"transport exception revokes route and ACK");
        Rig replaced=new Rig();replaced.join();replaced.begin(74,1);replaced.packet(Topics.ROUTE,chunk(74,0,1,1));
        replaced.transport.insideFire=()->replaced.guard.routeChanged(75);
        check(replaced.packet(Topics.PATH_SELECTED,StockMessages.pathSelected(74))!=0,"reentrant model replacement cannot arm old ACK");
        Rig delayed=new Rig();delayed.join();delayed.route(76);delayed.ack(76);delayed.fine(76);
        int before=delayed.transport.calls.size();delayed.audit.duringAttempt=()->delayed.time.advanceTo(101_001);
        check(delayed.position(76)!=0&&delayed.transport.calls.size()==before,"audit delay cannot extend FINE lease before actual fire");
        Rig ttl=new Rig();ttl.join();ttl.begin(77,1);ttl.expires=100_001;ttl.audit.duringAttempt=()->ttl.time.advanceTo(100_001);
        check(ttl.packet(Topics.ROUTE,chunk(77,0,1,1))!=0&&ttl.transport.calls.isEmpty(),"audit delay cannot extend prerequisite TTL before actual fire");
        Rig changed=new Rig();changed.join();changed.begin(78,1);changed.audit.duringAttempt=()->changed.guard.routeChanged(79);
        check(changed.packet(Topics.ROUTE,chunk(78,0,1,1))!=0&&changed.transport.calls.isEmpty(),"model replacement inside audit blocks actual stale route fire");
        Rig blocked=new Rig();blocked.join();blocked.route(80);blocked.ack(80);blocked.fine(80);
        int count=blocked.transport.calls.size();int[] queries={0};
        blocked.duringPrerequisites=()->{if(++queries[0]==2)blocked.time.advanceTo(101_001);};
        check(blocked.position(80)!=0&&blocked.transport.calls.size()==count,"blocking final prerequisite callback cannot extend FINE lease");
        Rig expired=new Rig();expired.join();expired.begin(81,1);expired.expires=100_001;
        expired.duringPrerequisites=()->expired.time.advanceTo(100_001);
        check(expired.packet(Topics.ROUTE,chunk(81,0,1,1))!=0&&expired.transport.calls.isEmpty(),"readiness expiry is checked after blocked prerequisite callback");
        Rig delayedAck=new Rig();delayedAck.join();delayedAck.route(82);
        delayedAck.duringPrerequisites=()->delayedAck.time.advanceTo(102_501);
        check(delayedAck.guard.onAck(1,Topics.PATH_SELECT_CONFIRM,100_000,StockMessages.pathSelectConfirm(82,200))==RealPathAck.Decision.STALE,
                "blocking readiness query counts toward ACK callback age");
    }
    private static void producerIntegration(){
        ManualScheduler time=new ManualScheduler(200_000);Transport transport=new Transport();Audit audit=new Audit();StockFeed[] feed={null};
        LiveEgressGuard guard=new LiveEgressGuard(transport,audit,time,now->ready(1,now,Long.MAX_VALUE),
                id->feed[0].onPathSelectConfirm(StockMessages.pathSelectConfirm(id,200)));
        guard.setWallClock(()->2_000_000+time.now());
        StockFeed.Config config=new StockFeed.Config();config.frame=Coordinates.Frame.WGS84;final long[] pathSequence={800_000};config.livePathIds=()->++pathSequence[0];
        feed[0]=new StockFeed(time,guard,config);feed[0].start(true,true,true);feed[0].onNavStart();
        long id=feed[0].onRouteResult(converted(),1).pathId();
        check(guard.currentPathId()==id&&feed[0].publishedPathId()==-1,"guard owns pending model ID before producer publication commit");
        guard.fineFix(id,FixFreshness.capture(time.now(),2_000_000+time.now(),time.now(),time.now(),2_000_000+time.now()),"FINE");
        time.drain();
        check(transport.count(Topics.ROUTE)==1&&transport.count(Topics.PATH_SELECTED)==1&&feed[0].publishedPathId()==id,"strict producer and guard complete real route transaction");
        feed[0].onProgress(new StockFeed.Progress(0,100,10));feed[0].onLocation(new StockFeed.Fix(49.1,55.8,Double.NaN,Double.NaN,0,90,false,0,null));time.drain();
        check(transport.count(Topics.SD_VEHICLE_LOCATION)==0,"actual producer dynamic bundles remain closed before real selected ACK");
        check(guard.onAck(1,Topics.PATH_SELECT_CONFIRM,time.now(),StockMessages.pathSelectConfirm(id,200))==RealPathAck.Decision.ACCEPTED,"real ACK delivered into producer via accepted-only sink");
        feed[0].onLocation(new StockFeed.Fix(49.1,55.8,Double.NaN,Double.NaN,0,90,false,0,null));time.drain();
        check(transport.count(Topics.SD_VEHICLE_LOCATION)==1,"real selected producer stationary fix emits once");
        time.advanceTo(205_000);check(transport.count(Topics.SD_VEHICLE_LOCATION)==1,"combined producer and guard cannot repeat stationary fix");
        feed[0].onNavStop();time.drain();
        check(!guard.isStopping()&&!guard.isClosed()&&guard.currentPathId()==-1&&transport.closes==0,"actual producer navStop ends route and retains armed session");
        int after=transport.calls.size();time.advanceTo(220_000);check(transport.calls.size()==after,"idle producer timers cannot publish without a route");
        feed[0].onNavStart();long next=feed[0].onRouteResult(converted(),1).pathId();time.drain();
        check(next!=id&&transport.count(Topics.PATH_SELECTED)==2,"same selected geometry can restart with a fresh wire ID");
        check(guard.confirmedPathId()==-1,"restarted producer does not inherit old ACK");feed[0].close();guard.close();
    }
    private static RouteConverter.Converted converted(){
        StockPath p=new StockPath();StockPath.Segment s=new StockPath.Segment();s.length=1000;s.travelTime=100;
        StockPath.Link l=new StockPath.Link();l.length=1000;l.lon=new int[]{176760000,176796000};l.lat=new int[]{200880000,200880000};s.links.add(l);p.segments.add(s);
        return new RouteConverter.Converted("host-only",p,1000,new int[]{0},new int[]{0},new double[]{0},new double[]{1000},new double[]{0},new double[]{1},new double[]{0},new double[]{1000},null,
                new double[]{0,1000},Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),new double[]{36});
    }
    private static void postMarshal(){
        Rig fine=new Rig();fine.transport.admission=fine.guard::finalLeaseValid;
        check(!fine.guard.finalLeaseValid(Topics.ROUTE),"no direct raw transport call is admitted outside guard-owned fire");
        fine.join();fine.route(90);fine.ack(90);fine.fine(90);int before=fine.transport.calls.size();
        fine.transport.afterMarshal=()->fine.time.advanceTo(101_001);
        check(fine.position(90)!=0&&fine.transport.calls.size()==before,"marshalling pause cannot extend original FINE lease");
        Rig ttl=new Rig();ttl.transport.admission=ttl.guard::finalLeaseValid;ttl.join();ttl.begin(91,1);ttl.expires=100_001;
        ttl.transport.afterMarshal=()->ttl.time.advanceTo(100_001);
        check(ttl.packet(Topics.ROUTE,chunk(91,0,1,1))!=0&&ttl.transport.calls.isEmpty(),"marshalling pause cannot extend cached readiness TTL");
        Rig replaced=new Rig();replaced.transport.admission=replaced.guard::finalLeaseValid;replaced.join();replaced.begin(92,1);
        replaced.transport.afterMarshal=()->replaced.guard.routeChanged(93);
        check(replaced.packet(Topics.ROUTE,chunk(92,0,1,1))!=0&&replaced.transport.calls.isEmpty(),"model replacement during marshalling cannot send old route under new path");
        Rig noQuery=new Rig();noQuery.transport.admission=noQuery.guard::finalLeaseValid;noQuery.join();noQuery.begin(94,1);
        int[] queries={0};noQuery.duringPrerequisites=()->queries[0]++;
        noQuery.transport.afterMarshal=()->{
            int n=queries[0];check(noQuery.guard.finalLeaseValid(Topics.ROUTE),"currently owned route has final lease");
            check(!noQuery.guard.finalLeaseValid(Topics.TRAFFIC_INFO),"final lease bound to actual packet topic");
            check(queries[0]==n,"final check performs no prerequisite callback");
        };
        check(noQuery.packet(Topics.ROUTE,chunk(94,0,1,1))==0,"post-marshalling final guard permits valid route");

        // A blocked worker cannot run its scheduled closer while marshalling; the deadline must
        // reject independently, even though the close task has not executed.
        final long[] elapsed={100_000};Scheduler paused=new Scheduler(){
            public long now(){return elapsed[0];}public void post(Object token,long delay,Runnable task){}public void cancel(Object token){}
        };
        Transport transport=new Transport();LiveEgressGuard guard=new LiveEgressGuard(transport,new Audit(),paused,
                now->ready(1,now,Long.MAX_VALUE),id->{});guard.setWallClock(()->1_000_000+elapsed[0]);transport.admission=guard::finalLeaseValid;
        for(long service:Topics.SERVICES)guard.startService(service);guard.routeChanged(95);guard.beginRoute(95,1);
        check(guard.fire(Topics.ROUTE,chunk(95,0,1,1),elapsed[0])==0,"stop fixture has actual own route");guard.navigationStopped();
        int count=transport.calls.size();transport.afterMarshal=()->{
            elapsed[0]+=1001;check(!guard.isClosed(),"scheduled close deliberately held by blocked worker");
            check(!guard.finalLeaseValid(Topics.NAVIGATION_STATUS),"exact stop deadline rejects without scheduled close");
        };
        check(guard.fire(Topics.NAVIGATION_STATUS,StockMessages.navigationStatus(100),100_000)!=0&&transport.calls.size()==count,
                "late one-shot stop status never reaches actual wire");
        check(guard.isClosed()&&transport.closes==1,"rejected late stop still closes own transport");
    }
}
