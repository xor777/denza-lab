package dev.denza.hud.probe;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Callback/lifecycle tests with no Navigator, device, network or Android runtime. */
public final class YandexRouteRegionsTest {
    static int checks;
    static final String GEOMETRY=String.join("",Collections.nCopies(64,"a"));
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static final class Clock implements YandexRouteRegions.Runtime {
        final Thread owner=Thread.currentThread();long now;
        static final class Task {final Runnable r;final long at;Task(Runnable r,long at){this.r=r;this.at=at;}}
        final List<Task> tasks=new ArrayList<>();
        @Override public long now(){return now;}
        @Override public synchronized void post(Runnable r,long delay){tasks.add(new Task(r,now+delay));}
        @Override public synchronized void remove(Runnable r){tasks.removeIf(t->t.r==r);}
        @Override public void assertOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("wrong owner");}
        void drain(){int guard=10000;for(;;){Task first=null;synchronized(this){for(Task t:tasks)if(t.at<=now&&(first==null||t.at<first.at))first=t;if(first!=null)tasks.remove(first);}if(first==null)return;if(--guard==0)throw new AssertionError("unbounded callback loop");first.r.run();}}
        void advance(long delta){now+=delta;drain();}
    }
    static final class Query {final double lon,lat;final YandexRouteRegions.Callback callback;Query(double lon,double lat,YandexRouteRegions.Callback cb){this.lon=lon;this.lat=lat;callback=cb;}}
    static final class Access implements YandexRouteRegions.Access {
        final List<Query> queries=new ArrayList<>();List<Integer> automatic;boolean unsupported;
        @Override public void request(double lon,double lat,YandexRouteRegions.Callback cb){queries.add(new Query(lon,lat,cb));if(unsupported)throw new IllegalStateException("Missing method");if(automatic!=null)cb.regions(automatic);}
        Query last(){return queries.get(queries.size()-1);}
    }
    static Map<String,Object> region(int id,Integer parent,String name,boolean usable,long release){Map<String,Object> r=new LinkedHashMap<>();r.put("id",id);r.put("parentId",parent);r.put("name",name);r.put("usable",usable);r.put("state",usable?"COMPLETED":"AVAILABLE");r.put("downloadedReleaseTime",usable?release:null);r.put("currentReleaseTime",release);return Collections.unmodifiableMap(r);}
    @SafeVarargs static YandexOfflineInventory.Snapshot inventory(long now,Map<String,Object>... regions){List<Map<String,Object>> rows=Arrays.asList(regions);int usable=0;for(Map<String,Object> r:rows)if(Boolean.TRUE.equals(r.get("usable")))usable++;String fp=rows.toString();return new YandexOfflineInventory.Snapshot(now,1,usable>0,fp,usable>0?"ready":"no_usable_regions","",fp,rows,rows,rows.size(),usable);}
    static Map<String,Object> route(String id,double... coordinates){List<List<Double>> points=new ArrayList<>();for(int n=0;n<coordinates.length;n+=2)points.add(Arrays.asList(coordinates[n],coordinates[n+1]));Map<String,Object> r=new LinkedHashMap<>();r.put("routeId",id);r.put("coordinateFrame","wgs84");r.put("pointOrder","lon,lat");r.put("points",points);return r;}
    static YandexRouteRegions.Snapshot sample(YandexRouteRegions lookup,Access access,Clock clock,Map<String,Object> route,YandexOfflineInventory.Snapshot maps){return lookup.sample(access,(String)route.get("routeId"),1,GEOMETRY,route,maps,clock.now);}
    static Map<String,Object> shortRoute(){return route("one",37,55,37.001,55);}
    static void wholeRouteAndManualDownload(){
        Clock clock=new Clock();Access access=new Access();YandexRouteRegions lookup=new YandexRouteRegions(clock);
        Map<String,Object> route=route("one",37,55,37.3,55,37.6,55);
        YandexOfflineInventory.Snapshot absent=inventory(0,region(1,null,"Москва",false,10),region(2,null,"Тула",false,10));
        YandexRouteRegions.Snapshot initial=sample(lookup,access,clock,route,absent);
        check(initial.pending&&!initial.complete&&!initial.ready(0),"async regional discovery blocks preparation");
        while(!lookup.snapshot().complete){Query query=access.last();query.callback.regions(Arrays.asList(query.lon<37.3?1:2,10000));clock.drain();}
        YandexRouteRegions.Snapshot missing=lookup.snapshot();
        check(access.queries.size()>3,"queries span chosen route, not only vehicle position");
        check(access.queries.get(0).lon==37&&access.last().lon==37.6,"both route endpoints discovered");
        check(missing.sampleCount==(int)Math.floor(missing.routeMeters/5000)+2,"explicit cumulative route-distance sampling");
        check(missing.complete&&!missing.ready(0)&&missing.code.equals("regions_manual_download_required"),"complete discovery without installed maps asks manual download");
        check(missing.reason.contains("Москва")&&missing.reason.contains("Тула"),"manual message names maps along whole chosen route");
        check(missing.missingRegions.size()==2,"unavailable catalog states remain download candidates");
        check(missing.status().get("automaticDownload").equals(false)&&missing.status().get("completeRouteCoverageChecked").equals(false),"discovery neither downloads nor claims full coverage");
        int calls=access.queries.size();
        YandexOfflineInventory.Snapshot partly=inventory(0,region(1,null,"Москва",true,10),region(2,null,"Тула",false,10));
        check(!sample(lookup,access,clock,route,partly).ready(0),"one installed unrelated-to-rest region cannot admit route");
        YandexOfflineInventory.Snapshot all=inventory(0,region(1,null,"Москва",true,10),region(2,null,"Тула",true,10));
        YandexRouteRegions.Snapshot ready=sample(lookup,access,clock,route,all);
        check(ready.ready(0)&&ready.complete&&ready.missingRegions.isEmpty(),"manual installation admits discovery without route reselection");
        check(access.queries.size()==calls,"inventory polls reuse completed route lookup");
        check(!ready.fingerprint.equals(missing.fingerprint),"downloaded package versions fence regional proof");
        check(!sample(lookup,access,clock,route,partly).ready(0),"deletion immediately revokes discovery admission");
        try{ready.requirements.clear();throw new AssertionError("mutable snapshot");}catch(UnsupportedOperationException ok){checks++;}
        try{ready.requirements.get(0).put("satisfied",false);throw new AssertionError("mutable group");}catch(UnsupportedOperationException ok){checks++;}
        try{((List<?>)ready.requirements.get(0).get("alternatives")).clear();throw new AssertionError("mutable alternatives");}catch(UnsupportedOperationException ok){checks++;}
    }
    static void hierarchyAlternatives(){
        for(boolean child:new boolean[]{true,false}){
            Clock clock=new Clock();Access access=new Access();access.automatic=Arrays.asList(102027,43,11119,225,10000);
            YandexRouteRegions lookup=new YandexRouteRegions(clock);YandexOfflineInventory.Snapshot maps=inventory(0,region(43,11119,"Казань",child,10),region(11119,null,"Татарстан",!child,10));
            sample(lookup,access,clock,shortRoute(),maps);clock.drain();
            check(lookup.snapshot().ready(0),"one actual returned usable alternative suffices, child="+child);
            check(lookup.snapshot().requirements.size()==1&&lookup.snapshot().missingRegions.isEmpty(),"same hierarchy answer deduplicated; unsupported geographies ignored");
        }
        Clock clock=new Clock();Access access=new Access();access.automatic=Arrays.asList(11119,225,10000);YandexRouteRegions lookup=new YandexRouteRegions(clock);
        sample(lookup,access,clock,shortRoute(),inventory(0,region(43,11119,"Казань",true,10),region(11119,null,"Татарстан",false,10)));clock.drain();
        check(!lookup.snapshot().ready(0)&&lookup.snapshot().reason.contains("Татарстан"),"installed child cannot cover another point returning parent only");
        Clock c2=new Clock();Access a2=new Access();a2.automatic=Arrays.asList(225,10000);YandexRouteRegions l2=new YandexRouteRegions(c2);
        sample(l2,a2,c2,shortRoute(),inventory(0,region(43,null,"Казань",true,10)));c2.drain();
        check(l2.snapshot().code.equals("regions_catalog_gap")&&!l2.snapshot().ready(0),"no catalog match fails closed; region is not invented");
    }
    static void freshnessAndRouteFences(){
        Clock clock=new Clock();Access access=new Access();access.automatic=Arrays.asList(1);YandexRouteRegions lookup=new YandexRouteRegions(clock);Map<String,Object> route=shortRoute();
        YandexOfflineInventory.Snapshot maps=inventory(0,region(1,null,"Карта",true,10));sample(lookup,access,clock,route,maps);clock.drain();YandexRouteRegions.Snapshot ready=lookup.snapshot();
        check(ready.ready(4999)&&!ready.ready(5000)&&!ready.ready(-1),"inventory TTL applies even without another producer sample");
        clock.advance(5000);check(sample(lookup,access,clock,route,maps).code.equals("regions_inventory_stale"),"stale inventory blocks cached discovery");
        int calls=access.queries.size();YandexOfflineInventory.Snapshot refreshed=inventory(5000,region(1,null,"Карта",true,10));check(sample(lookup,access,clock,route,refreshed).ready(5000)&&access.queries.size()==calls,"fresh identical inventory restores cache without SDK queries");
        YandexOfflineInventory.Snapshot replaced=inventory(5000,region(1,null,"Карта",true,11));check(!sample(lookup,access,clock,route,replaced).fingerprint.equals(ready.fingerprint),"installed release change invalidates proof fingerprint");
        access.automatic=null;Map<String,Object> replacement=route("two",40,56,40.001,56);sample(lookup,access,clock,replacement,replaced);Query stale=access.last();
        check(!lookup.snapshot().ready(5000)&&lookup.snapshot().routeId.equals("two"),"new route revokes ready before async work");
        lookup.sample(access,"two",2,GEOMETRY,replacement,replaced,clock.now);Query epoch=access.last();stale.callback.regions(Arrays.asList(1));check(lookup.snapshot().answeredCount==0,"old epoch callback ignored");
        lookup.sample(access,"two",2,GEOMETRY.replace('a','b'),replacement,replaced,clock.now);epoch.callback.regions(Arrays.asList(1));check(lookup.snapshot().answeredCount==0,"old geometry callback ignored");
        Query geometry=access.last();lookup.sample(access,"two",2,null,null,replaced,clock.now);geometry.callback.regions(Arrays.asList(1));check(lookup.snapshot().code.equals("route_geometry_unavailable")&&lookup.snapshot().answeredCount==0,"unavailable geometry cancels previous request logically");
        clock.now=4999;lookup.sample(access,"two",2,GEOMETRY,replacement,replaced,clock.now);check(lookup.snapshot().code.equals("regions_clock_invalid"),"clock rollback fails closed");
        lookup.close();check(lookup.snapshot().code.equals("regions_closed")&&!lookup.snapshot().ready(clock.now),"close withdraws result and cancels callbacks");
    }
    static void boundedFailures(){
        Clock clock=new Clock();Access access=new Access();YandexRouteRegions lookup=new YandexRouteRegions(clock);YandexOfflineInventory.Snapshot maps=inventory(0,region(1,null,"Карта",true,10));
        sample(lookup,access,clock,shortRoute(),maps);Query first=access.last();clock.advance(5000);check(access.queries.size()==1,"timeout waits bounded retry delay");clock.advance(1000);check(access.queries.size()==2,"exactly one retry after timeout");
        first.callback.regions(Arrays.asList(1));check(lookup.snapshot().answeredCount==0,"late timed out callback cannot satisfy replacement request");
        clock.advance(5000);check(lookup.snapshot().code.equals("regions_request_timeout")&&!lookup.snapshot().ready(clock.now),"second timeout terminal, no automatic retry loop");clock.advance(100000);check(access.queries.size()==2,"failed route lookup does not loop");
        Clock c2=new Clock();Access a2=new Access();a2.unsupported=true;YandexRouteRegions l2=new YandexRouteRegions(c2);sample(l2,a2,c2,shortRoute(),maps);c2.drain();check(l2.snapshot().code.equals("regions_api_unsupported")&&a2.queries.size()==1,"unsupported SDK blocks without retries or guessed region");
        for(List<Integer> ids:Arrays.asList(Collections.<Integer>emptyList(),Arrays.asList((Integer)null),Arrays.asList(-1),Collections.nCopies(65,1))){Clock c=new Clock();Access a=new Access();YandexRouteRegions l=new YandexRouteRegions(c);sample(l,a,c,shortRoute(),maps);a.last().callback.regions(ids);check(l.snapshot().code.equals("regions_answer_invalid")&&!l.snapshot().ready(0),"malformed SDK answer rejected");}
        Clock c3=new Clock();Access a3=new Access();YandexRouteRegions l3=new YandexRouteRegions(c3);sample(l3,a3,c3,shortRoute(),maps);a3.last().callback.error();c3.advance(1000);a3.last().callback.error();check(l3.snapshot().code.equals("regions_request_error")&&a3.queries.size()==2,"error callback bounded to two attempts");
        Clock c4=new Clock();Access a4=new Access();YandexRouteRegions l4=new YandexRouteRegions(c4);sample(l4,a4,c4,shortRoute(),maps);c4.advance(180000);check(l4.snapshot().code.equals("regions_lookup_timeout")&&!l4.snapshot().ready(c4.now),"global wall deadline bounds even delayed Handler callbacks");
    }
    static void geometryAndThreads() throws Exception {
        YandexOfflineInventory.Snapshot maps=inventory(0,region(1,null,"Карта",true,10));
        for(Map<String,Object> route:Arrays.asList(route("one",0,0,180,0),route("one",181,0,1,1),route("one",0,Double.NaN,1,1),route("one",1,1,1,1))){Clock c=new Clock();Access a=new Access();YandexRouteRegions l=new YandexRouteRegions(c);sample(l,a,c,route,maps);check(!l.snapshot().ready(0)&&!l.snapshot().pending&&a.queries.isEmpty(),"bad or over-limit geometry never enters SDK");}
        Clock c=new Clock();Access a=new Access();a.automatic=Arrays.asList(1);YandexRouteRegions l=new YandexRouteRegions(c);sample(l,a,c,route("one",179.99,0,-179.99,0),maps);c.drain();check(l.snapshot().complete&&a.queries.size()==2&&l.snapshot().routeMeters<3000,"antimeridian uses short arc with endpoint lookup");
        Clock c2=new Clock();Access a2=new Access();YandexRouteRegions l2=new YandexRouteRegions(c2);sample(l2,a2,c2,shortRoute(),maps);Query q=a2.last();List<Integer> response=new ArrayList<>(Arrays.asList(1));Thread callback=new Thread(()->{q.callback.regions(response);for(int n=0;n<100;n++)q.callback.error();});callback.start();callback.join(5000);response.set(0,999);check(c2.tasks.size()==3,"duplicate callbacks cannot enqueue unbounded work");check(l2.snapshot().answeredCount==0,"foreign thread callback queues owned work");c2.drain();check(l2.snapshot().answeredCount==1&&l2.snapshot().requirements.size()==1,"owner applies queued immutable callback copy");
        AtomicReference<Throwable> error=new AtomicReference<>();int calls=a2.queries.size();Thread wrong=new Thread(()->{try{sample(l2,a2,c2,shortRoute(),maps);}catch(Throwable e){error.set(e);}});wrong.start();wrong.join(5000);check(error.get() instanceof IllegalStateException&&calls==a2.queries.size(),"wrong-thread sample never accesses SDK");
    }
    public static void main(String[]args)throws Exception{wholeRouteAndManualDownload();hierarchyAlternatives();freshnessAndRouteFences();boundedFailures();geometryAndThreads();System.out.println("YandexRouteRegionsTest: "+checks+" checks passed; discovery only, no coverage or device claim");}
}
