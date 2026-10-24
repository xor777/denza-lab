package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Live-specific producer rules with a memory-only guarded publisher; no transport or Android. */
public final class LiveStockFeedTest {
    private static int checks;
    private static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    private static final class Call {
        final long topic;final byte[] payload;Call(long t,byte[] p){topic=t;payload=p;}
    }
    private static class Guard implements StockFeed.GuardedLivePublisher {
        final List<Call> calls=new ArrayList<>();final List<Long> changed=new ArrayList<>();final List<String> notes=new ArrayList<>();
        int routeCalls,failRouteAt,selectionResult,begins,expectedChunks,stopped;
        Runnable afterRoute;
        @Override public BridgeStatus.Mode mode(){return BridgeStatus.Mode.LIVE;}
        @Override public int startService(long service){return 0;}
        @Override public int fire(long topic,byte[] payload,long now){
            calls.add(new Call(topic,payload));
            if(topic==Topics.ROUTE){routeCalls++;if(afterRoute!=null)afterRoute.run();if(routeCalls==failRouteAt)return -9;}
            return topic==Topics.PATH_SELECTED?selectionResult:0;
        }
        @Override public void note(String kind,Map<String,Object> fields,long now){notes.add(kind);}
        @Override public void routeChanged(long id){changed.add(id);}
        @Override public void beginRoute(long id,int chunks){begins++;expectedChunks=chunks;}
        @Override public void navigationStopped(){stopped++;}
        int count(long topic){int n=0;for(Call c:calls)if(c.topic==topic)n++;return n;}
    }
    /** Time advances while queued work is deliberately held, as with a busy Android Handler. */
    private static final class LagClock implements Scheduler {
        static final class Task {final Object token;final long at;final Runnable run;Task(Object t,long a,Runnable r){token=t;at=a;run=r;}}
        long now=100_000;final List<Task> queue=new ArrayList<>();
        @Override public long now(){return now;}
        @Override public void post(Object token,long delay,Runnable r){queue.add(new Task(token,now+delay,r));}
        @Override public void cancel(Object token){for(Iterator<Task> i=queue.iterator();i.hasNext();)if(i.next().token==token)i.remove();}
        void drain(){int budget=10000;for(;;){Task next=null;for(Task t:queue)if(t.at<=now&&(next==null||t.at<next.at))next=t;
            if(next==null)return;if(--budget==0)throw new AssertionError("scheduler loop");queue.remove(next);next.run.run();}}
        void advance(long delta){now+=delta;drain();}
    }
    private static final class Rig {
        final LagClock clock=new LagClock();final Guard guard=new Guard();final StockFeed.Config config=config();final StockFeed feed;
        Rig(){feed=new StockFeed(clock,guard,config);feed.start(true,true,true);feed.onNavStart();clock.drain();guard.calls.clear();}
        long route(RouteConverter.Converted c){long id=feed.onRouteResult(c,1).pathId();return id;}
    }
    public static void main(String[] args){construction();routeTransactions();leasesAndClose();freshPositions();System.out.println("LiveStockFeedTest: "+checks+" checks passed");}
    private static StockFeed.Config config(){StockFeed.Config c=new StockFeed.Config();c.frame=Coordinates.Frame.WGS84;AtomicLong id=new AtomicLong(800_000);c.livePathIds=id::incrementAndGet;return c;}
    private static void construction(){
        Publisher raw=new Guard(){ };StockFeed.Config emulated=config();emulated.emulatedAckDelayMs=110;
        rejected(()->new StockFeed(new LagClock(),raw,emulated),"LIVE rejects emulated ACK");
        StockFeed.Config missing=config();missing.livePathIds=null;
        rejected(()->new StockFeed(new LagClock(),raw,missing),"LIVE requires ID supplier");
        Publisher unguarded=new Publisher(){
            public BridgeStatus.Mode mode(){return BridgeStatus.Mode.LIVE;}public int startService(long s){return 0;}
            public int fire(long t,byte[] p,long n){return 0;}public void note(String k,Map<String,Object> f,long n){}
        };
        rejected(()->new StockFeed(new LagClock(),unguarded,config()),"raw LIVE publisher rejected without external guard marker");
    }
    private static void routeTransactions(){
        Rig r=new Rig();long first=r.route(route(0));
        check(r.feed.publishedPathId()==-1,"queued route not yet published");r.clock.drain();
        check(r.guard.count(Topics.ROUTE)==1&&r.guard.count(Topics.PATH_SELECTED)==1,"one complete route then selection");
        check(r.feed.publishedPathId()==first&&r.feed.status().routeActive,"path commits only after full success");
        check(((Number)r.feed.debugState().get(2)).longValue()==-1,"successful sends are not ACKs");
        long second=r.route(route(0));r.clock.drain();
        check(second!=first&&r.feed.publishedPathId()==second,"identical table gets fresh live ID per attempt");
        check(r.guard.changed.equals(Arrays.asList(first,second)),"route change notified before queue");
        r.feed.onPathSelectConfirm(StockMessages.pathSelectConfirm(second,500));r.clock.drain();
        check(r.guard.count(Topics.ROUTE)==2,"failed ACK does not autonomously resend live route");
        r.feed.onPathSelectConfirm(StockMessages.pathSelectConfirm(first,200));r.clock.drain();
        check(((Number)r.feed.debugState().get(2)).longValue()==-1,"foreign/old ACK cannot select live path");

        r=new Rig();r.guard.failRouteAt=1;r.route(route(600_000));r.clock.drain();
        check(r.guard.expectedChunks==2&&r.guard.routeCalls==1&&r.guard.count(Topics.PATH_SELECTED)==0&&r.feed.publishedPathId()==-1,"first failed routechunk stops later chunks, selection and commit");
        r=new Rig();r.guard.failRouteAt=2;r.route(route(600_000));r.clock.drain();
        check(r.guard.expectedChunks==2&&r.guard.routeCalls==2,"two-chunk fixture reaches failed second chunk");
        check(r.guard.count(Topics.PATH_SELECTED)==0&&r.feed.publishedPathId()==-1,"partial multichunk route never selected/published");
        r=new Rig();r.guard.selectionResult=-7;r.route(route(0));r.clock.drain();
        check(r.guard.count(Topics.ROUTE)==1&&r.guard.count(Topics.PATH_SELECTED)==1&&r.feed.publishedPathId()==-1,"selection failure prevents published commit");
        r=new Rig();RouteConverter.Converted malformed=route(0);malformed.path.segments.get(0).links.get(0).lat=new int[0];r.route(malformed);r.clock.drain();
        check(r.guard.count(Topics.ROUTE)==0&&r.guard.count(Topics.PATH_SELECTED)==0&&r.guard.notes.contains("routeSerializeFailed"),"serialization failure sends neither route nor selection");

        Rig reused=new Rig();reused.config.livePathIds=()->123; // Constructor snapshots the supplier; config mutation cannot replace it.
        long a=reused.route(route(0)),b=reused.route(route(0));
        check(a!=b,"live path supplier captured at construction");
        StockFeed.Config duplicate=config();duplicate.livePathIds=()->123;
        StockFeed duplicateFeed=new StockFeed(new LagClock(),new Guard(),duplicate);duplicateFeed.onRouteResult(route(0),1);
        rejected(()->duplicateFeed.onRouteResult(route(0),1),"reused supplier ID fails closed");
        check(duplicateFeed.publishedPathId()==-1,"invalid ID clears publication");
    }
    private static void leasesAndClose(){
        Rig r=new Rig();r.route(route(0));r.feed.setGates(false,true,true);r.feed.setGates(true,true,true);r.clock.drain();
        check(r.guard.count(Topics.ROUTE)==0&&r.guard.count(Topics.PATH_SELECTED)==0,"queued route before gate cycle invalidated");
        r.clock.advance(1000);check(r.guard.count(Topics.ROUTE)==0,"gate reopening does not resend reused ID");
        r.route(route(0));r.clock.drain();check(r.guard.count(Topics.PATH_SELECTED)==1,"fresh attempt after gates reopen works");
        Rig offMid=new Rig();offMid.guard.afterRoute=()->offMid.feed.setGates(true,true,false);offMid.route(route(600_000));offMid.clock.drain();
        check(offMid.guard.routeCalls==1&&offMid.guard.count(Topics.PATH_SELECTED)==0&&offMid.feed.publishedPathId()==-1,"gate rechecked between chunks");
        Rig stoppedMid=new Rig();stoppedMid.guard.afterRoute=stoppedMid.feed::onNavStop;stoppedMid.route(route(600_000));stoppedMid.clock.drain();
        check(stoppedMid.guard.routeCalls==1&&stoppedMid.guard.count(Topics.PATH_SELECTED)==0,"nav stop during transaction prevents later chunk/selection");
        Rig replaced=new Rig();long[] replacement={0};
        replaced.guard.afterRoute=()->{if(replaced.guard.routeCalls==1)replacement[0]=replaced.route(route(0));};
        replaced.route(route(600_000));replaced.clock.drain();
        check(replaced.guard.routeCalls==2&&replaced.guard.count(Topics.PATH_SELECTED)==1&&replaced.feed.publishedPathId()==replacement[0],"route replacement drops old remaining chunks, publishes newest only");
        r=new Rig();r.route(route(0));r.feed.onNavStop();r.clock.drain();
        check(r.guard.count(Topics.ROUTE)==0,"navstop invalidates anonymous queued route send");
        r=new Rig();r.route(route(0));r.feed.onLocation(fix(0,20));int before=r.guard.calls.size();r.feed.close();r.clock.advance(60_000);
        check(r.guard.calls.size()==before&&r.feed.status()==BridgeStatus.Snapshot.DISABLED,"close cancels loops and anonymous pending work");
        check(r.guard.stopped==1,"close explicitly revokes guard navigation lease");
        final Rig closed=r;rejected(()->closed.feed.start(true,true,true),"closed producer cannot restart same session");
    }
    private static void freshPositions(){
        Rig r=new Rig();r.route(route(0));r.clock.drain();r.feed.onLocation(fix(0,20));r.clock.drain();
        check(r.guard.count(Topics.SD_VEHICLE_LOCATION)==1,"stationary fresh FINE location emits once");
        r.clock.advance(10_000);check(r.guard.count(Topics.SD_VEHICLE_LOCATION)==1,"FINE loss does not repeat captured stationary position");
        r.feed.onLocation(fix(0,20));r.clock.drain();check(r.guard.count(Topics.SD_VEHICLE_LOCATION)==2,"next genuine fresh stationary fix emits once");
        r.feed.onLocation(fix(0,1001));r.clock.drain();check(r.guard.count(Topics.SD_VEHICLE_LOCATION)==2,"already stale fix rejects before queue");
        r.feed.onLocation(fix(0,-1));r.clock.drain();check(r.guard.count(Topics.SD_VEHICLE_LOCATION)==2,"future/negative-age fix rejects");
        Rig queue=new Rig();queue.route(route(0));queue.clock.drain();queue.feed.onLocation(fix(5,20));queue.clock.now+=981;queue.clock.drain();
        check(queue.guard.count(Topics.SD_VEHICLE_LOCATION)==0,"queue delay20+981ms exceeds1000; captured age not refreshed on execution");
        queue.feed.onLocation(fix(5,20));queue.clock.now+=980;queue.clock.drain();
        check(queue.guard.count(Topics.SD_VEHICLE_LOCATION)==1,"queue age1000 inclusive passes");
        Rig epoch=new Rig();epoch.route(route(0));epoch.clock.drain();epoch.feed.onLocation(fix(5,20));epoch.feed.setGates(false,true,true);epoch.feed.setGates(true,true,true);epoch.clock.drain();
        check(epoch.guard.count(Topics.SD_VEHICLE_LOCATION)==0,"old queued location cannot cross gate epoch");
    }
    private static StockFeed.Fix fix(double speed,long age){return new StockFeed.Fix(37.6,55.7,Double.NaN,Double.NaN,speed,90,false,age,null);}
    private static RouteConverter.Converted route(int nameLength){
        StockPath p=new StockPath();StockPath.Segment segment=new StockPath.Segment();segment.length=1000;segment.travelTime=100;
        StockPath.Link link=new StockPath.Link();link.length=1000;link.lon=new int[]{135360000,135396000};link.lat=new int[]{200520000,200520000};
        char[] name=new char[nameLength];Arrays.fill(name,'x');link.roadName=new String(name);segment.links.add(link);p.segments.add(segment);
        return new RouteConverter.Converted("route",p,1000,new int[]{0},new int[]{0},new double[]{0},new double[]{1000},new double[]{0},new double[]{1},
                new double[]{0},new double[]{1000},null,new double[]{0,1000},Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),new double[]{36});
    }
    private static void rejected(Runnable task,String why){boolean rejected=false;try{task.run();}catch(IllegalArgumentException|IllegalStateException expected){rejected=true;}check(rejected,why);}
}
