package dev.denza.hud.probe;

import dev.denza.adaslink.VectorRoadTile;
import dev.denza.adaslink.VectorRoadTileCache;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Source policy tests with fake loaders/cache snapshots. Native offscreen fetching is not exercised. */
public final class YandexRouteVectorSourceTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static final class Fake implements YandexVectorPrefetch.Loader {
        int opens,moves,closes; boolean throwMove; VectorRoadTile.TileId requested;
        @Override public void open(){opens++;}
        @Override public void move(VectorRoadTile.TileId tile,dev.denza.adaslink.RouteVectorTilePlan.Center center){moves++;requested=tile;if(throwMove)throw new IllegalStateException("move unavailable");}
        @Override public void close(){closes++;}
    }
    static Map<String,Object> copy(String id){
        Map<String,Object> m=new LinkedHashMap<>();m.put("routeId",id);m.put("coordinateFrame","wgs84");m.put("pointOrder","lon,lat");
        m.put("points",Arrays.asList(Arrays.asList(0d,0d),Arrays.asList(.001d,.002d)));return m;
    }
    static final String HASH="1b1774a093e87792d6cea98d47040bd79e4f918554790f35963b87e6e63caad5";
    static long number(YandexRouteVectorSource s,String field){return((Number)s.status().get(field)).longValue();}
    static byte[] raw(){return new byte[]{8,14,18,2,0,0,26,1,1,50,14,10,4,'r','o','a','d',18,6,18,2,8,1,40,1};}
    @SuppressWarnings("unchecked") static Map<String,Object> prefetch(YandexRouteVectorSource s){return(Map<String,Object>)s.status().get("prefetch");}
    static void lifecycle(){
        Fake loader=new Fake();VectorRoadTileCache cache=new VectorRoadTileCache(32,32L<<20);
        YandexRouteVectorSource source=new YandexRouteVectorSource(cache::snapshot,api->loader);Reflect api=new Reflect();
        source.sample(api,"a",1,HASH,copy("a"),0,false,0);
        check(loader.opens==0&&source.status().get("state").equals("offline_inventory_unavailable"),"no usable offline inventory, no camera work");
        source.sample(api,"a",1,HASH,copy("a"),0,true,0);
        check(loader.opens==1&&loader.moves==1,"current copied geometry starts one private window");
        check(number(source,"maximumTiles")==24&&number(source,"aheadMetres")==3000,"moving three-kilometre window with bounded tile target");
        check(((Number)prefetch(source).get("requiredTiles")).intValue()<=24,"planner window bound passed to prefetch");
        source.sample(api,"a",1,HASH,null,.1,true,100);
        check(loader.moves==1&&loader.opens==1,"same geometry uses retained plan and rate-limited request");
        check(((Number)prefetch(source).get("observedRequestedTiles")).longValue()==0,"request is not counted as observed");
        VectorRoadTile.TileId requested=loader.requested;
        check(cache.put(raw(),requested,requested,requested,1)!=null,"actual cache fixture admitted");
        source.sample(api,"a",1,HASH,null,.2,true,1000);
        check(((Number)prefetch(source).get("observedRequestedTiles")).longValue()==1,"only actual matching cache tile establishes success");
        int moves=loader.moves;
        source.sample(api,"b",2,HASH,copy("b"),0,true,1100);
        check(loader.moves==moves&&loader.opens==1,"new epoch cancels old pending work and respects cross-route rate limit");
        source.sample(api,"b",2,HASH,null,0,true,2100);
        check(loader.opens==1&&loader.moves==moves+1,"new route reuses one physical window");
        source.sample(api,null,1,null,null,0,false,2200);
        check(number(source,"staleEpochsIgnored")==1&&source.status().get("routeId").equals("b"),"late old cancellation cannot cancel current route");
        source.sample(api,"b",2,HASH,null,0,false,2300);
        check(source.status().get("prefetch")==null&&loader.closes==0,"offline loss cancels scheduling, keeps bounded owned SDK window");
        source.sample(api,"b",2,HASH,null,0,false,2400);
        check(loader.moves==moves+1,"no offline means no further moves");
        source.sample(api,"b",2,HASH,null,0,true,3400);
        check(loader.opens==1,"offline recovery does not allocate another physical window");
        source.sample(api,null,3,null,null,0,true,3500);
        check(!Boolean.TRUE.equals(source.status().get("hasPlan"))&&source.status().get("prefetch")==null,"route cancellation drops route plan and pending work");
        source.close();source.close();check(loader.closes==1&&Boolean.TRUE.equals(source.status().get("closed")),"final owner close releases own SDK references once");
        source.sample(api,"c",4,HASH,copy("c"),0,true,5000);check(loader.opens==1,"closed owner never restarts");
    }
    static void failures(){
        Fake loader=new Fake();loader.throwMove=true;VectorRoadTileCache cache=new VectorRoadTileCache();
        final int[] factories={0};YandexRouteVectorSource s=new YandexRouteVectorSource(cache::snapshot,api->{factories[0]++;return loader;});Reflect api=new Reflect();
        s.sample(api,"a",1,HASH,copy("a"),0,true,0);
        check(s.status().get("state").equals("failed")&&loader.closes==1,"initial native/API failure closes owned references");
        for(int i=1;i<100;i++)s.sample(api,"a",1,HASH,null,0,true,i*100L);
        check(factories[0]==1&&loader.moves==1&&number(s,"failures")==1,"same failing identity cannot allocate every tick");
        loader.throwMove=false;s.sample(api,"a",1,HASH,null,0,true,30000);
        check(factories[0]==2&&loader.opens==2,"explicit cooldown permits a bounded retry");s.close();
        Fake invalidLoader=new Fake();YandexRouteVectorSource invalid=new YandexRouteVectorSource(cache::snapshot,a->invalidLoader);
        invalid.sample(api,"a",1,HASH,copy("wrong"),0,true,0);
        check(invalidLoader.opens==0&&invalid.status().get("error").equals("route_geometry_invalid"),"mismatched copy rejected before map access");
        invalid.sample(api,"a",1,HASH,copy("a"),0,true,100);
        check(invalidLoader.opens==0,"bad-copy cooldown is not bypassed by another same-identity tick");
        invalid.sample(api,"b",2,HASH,copy("b"),0,true,200);
        check(invalidLoader.opens==1,"new source route may recover from prior route failure");invalid.close();
        final int[] waitingFactories={0};YandexRouteVectorSource wait=new YandexRouteVectorSource(()->null,a->{waitingFactories[0]++;return new Fake();});
        wait.sample(api,"a",1,HASH,null,0,true,0);check(wait.status().get("state").equals("waiting_route_copy"),"hash alone cannot manufacture route geometry");
        wait.sample(api,"a",1,HASH,copy("a"),0,true,100);
        check(wait.status().get("state").equals("waiting_vector_source")&&waitingFactories[0]==0,"no captured source means no loader allocation");wait.close();
        VectorRoadTileCache released=new VectorRoadTileCache();released.release();
        YandexRouteVectorSource releasedSource=new YandexRouteVectorSource(released::snapshot,a->{waitingFactories[0]++;return new Fake();});
        releasedSource.sample(api,"a",1,HASH,copy("a"),0,true,0);
        check(releasedSource.status().get("state").equals("waiting_vector_source")&&waitingFactories[0]==0,"released source cannot allocate a loader");releasedSource.close();
        final int[] boundedFactories={0};YandexRouteVectorSource bounded=new YandexRouteVectorSource(cache::snapshot,a->{boundedFactories[0]++;Fake f=new Fake();f.throwMove=true;return f;});
        for(int i=0;i<8;i++)bounded.sample(api,"a",1,HASH,i==0?copy("a"):null,0,true,i*30000L);
        check(boundedFactories[0]==3&&number(bounded,"physicalCreationAttempts")==3,"persistent native failure has a lifetime creation bound");
        check(bounded.status().get("state").equals("window_budget_exhausted"),"exhaustion remains explicit after cooldown");
        bounded.sample(api,"b",2,HASH,copy("b"),0,true,300000);
        check(boundedFactories[0]==3&&bounded.status().get("state").equals("window_budget_exhausted"),"new route cannot reset physical creation budget");bounded.close();
    }
    static String hash(List<List<Double>> points)throws Exception{
        MessageDigest d=MessageDigest.getInstance("SHA-256");ByteBuffer p=ByteBuffer.allocate(16);
        for(List<Double> q:points){p.clear();p.putDouble(q.get(0)).putDouble(q.get(1));d.update(p.array());}
        StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format("%02x",b&255));return s.toString();
    }
    static void geometryAndOwner()throws Exception{
        Fake loader=new Fake();VectorRoadTileCache cache=new VectorRoadTileCache();YandexRouteVectorSource source=new YandexRouteVectorSource(cache::snapshot,api->loader);Reflect api=new Reflect();
        source.sample(api,"a",1,HASH,copy("a"),0,true,0);
        Map<String,Object> changed=copy("a");List<List<Double>> points=Arrays.asList(Arrays.asList(.001,.002),Arrays.asList(0d,0d));changed.put("points",points);
        source.sample(api,"a",1,hash(points),changed,0,true,2000);
        check(number(source,"routeChanges")==2&&loader.opens==1,"same route ID changed geometry cancels logical epoch and reuses window");
        source.sample(api,"a",1,hash(points),null,999,true,4000);
        check(source.status().get("state").equals("waiting_progress")&&loader.closes==0,"out-of-route scalar waits without dropping physical window");
        int moves=loader.moves;
        source.sample(api,"a",1,hash(points),null,Double.NaN,true,35000);
        check(source.status().get("state").equals("waiting_progress")&&number(source,"failures")==0&&loader.moves==moves,"missing reroute progress is not a retryable SDK failure");
        source.sample(api,"a",1,hash(points),null,0,true,36000);
        check(loader.opens==1&&loader.moves==moves+1,"valid progress resumes the same physical window");
        source.close();
        Fake wrongThreadLoader=new Fake();YandexRouteVectorSource owned=new YandexRouteVectorSource(cache::snapshot,a->wrongThreadLoader);AtomicReference<Throwable> result=new AtomicReference<>();
        Thread t=new Thread(()->{try{owned.sample(api,"a",1,HASH,copy("a"),0,true,0);}catch(Throwable e){result.set(e);}});t.start();t.join(5000);
        check(result.get()instanceof IllegalStateException&&wrongThreadLoader.moves==0,"main-thread owner fencing before SDK work");owned.close();
    }
    public static void main(String[]args)throws Exception{lifecycle();failures();geometryAndOwner();System.out.println("YandexRouteVectorSourceTest: "+checks+" checks passed; native fetch remains unverified");}
}
