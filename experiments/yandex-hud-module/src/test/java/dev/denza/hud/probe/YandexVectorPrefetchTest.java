package dev.denza.hud.probe;

import dev.denza.adaslink.RouteVectorTilePlan;
import dev.denza.adaslink.VectorRoadTile;
import dev.denza.adaslink.VectorRoadTileCache;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Fake loader checks policy only; it does not establish native offscreen fetch behavior. */
public final class YandexVectorPrefetchTest {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    static final class Fake implements YandexVectorPrefetch.Loader {
        int opens, moves, closes; VectorRoadTile.TileId tile; boolean throwMove;
        @Override public void open() { opens++; }
        @Override public void move(VectorRoadTile.TileId id, RouteVectorTilePlan.Center center) {
            moves++; tile = id; if (throwMove) throw new IllegalStateException("native move failed");
        }
        @Override public void close() { closes++; }
    }
    static RouteVectorTilePlan plan() { return new RouteVectorTilePlan(new double[]{49.12,49.1201},new double[]{55.8,55.8}); }
    static long count(YandexVectorPrefetch p, String name) { return ((Number)p.status().get(name)).longValue(); }
    static byte[] raw() { return new byte[]{8,14,18,2,0,0,26,1,1,50,14,10,4,'r','o','a','d',18,6,18,2,8,1,40,1}; }
    static void observe(VectorRoadTileCache c, VectorRoadTile.TileId id) { check(c.put(raw(),id,id,id) != null,"fixture observation"); }
    static void observation() {
        Fake f = new Fake(); VectorRoadTileCache c = new VectorRoadTileCache();
        YandexVectorPrefetch p = new YandexVectorPrefetch(f,plan(),7,new YandexVectorPrefetch.Policy());
        p.tick(7,0,0,null); check(f.opens == 0 && p.status().get("state").equals("waiting_vector_source"),"no source, no map work");
        p.tick(7,0,0,c.snapshot()); check(f.opens == 1 && f.moves == 1,"one lazy offscreen window, one move");
        p.tick(7,0,1000,c.snapshot());
        check(count(p,"observedRequestedTiles") == 0 && f.moves == 1,"issuing a move does not establish tile success");
        VectorRoadTile.TileId requested = f.tile;
        observe(c,new VectorRoadTile.TileId(0,0,15)); p.tick(7,0,2000,c.snapshot());
        check(count(p,"observedRequestedTiles") == 0 && f.moves == 1,"unrelated cache generation not success");
        observe(c,requested); p.tick(7,0,3000,c.snapshot());
        check(count(p,"observedRequestedTiles") == 1 && f.moves == 2,"actual pending tile arrival accepted");
        p.tick(7,0,3100,c.snapshot()); check(f.moves == 2,"pending operation bounded");
        p.tick(8,0,3200,c.snapshot());
        check(Boolean.TRUE.equals(p.status().get("closed")) && f.closes == 1,"reroute epoch cancels own map");
        p.tick(7,0,4000,c.snapshot());
        check(f.moves == 2,"delayed old ticks cannot revive old epoch");
        p.close(); check(f.closes == 1,"close idempotent");
    }
    static void budgets() {
        Fake f = new Fake(); VectorRoadTileCache c = new VectorRoadTileCache();
        YandexVectorPrefetch p = new YandexVectorPrefetch(f,plan(),1,new YandexVectorPrefetch.Policy());
        for (int i = 0; i < 60; i++) {
            p.tick(1,0,i*5000L,c.snapshot());
        }
        check(f.moves == 18 && f.opens == 1,"nine neighbors, two attempts per tile, no request-driven success");
        check(count(p,"observedRequestedTiles")==0 && count(p,"timeouts")==18,"timeouts remain missing rather than success");
        check(count(p,"trackedAttempts") <= 64 && p.status().get("state").equals("missing_retry_budget_or_cooldown"),"bounded retry bookkeeping");
        p.close();
        Fake g = new Fake(); YandexVectorPrefetch next = new YandexVectorPrefetch(g,plan(),2,new YandexVectorPrefetch.Policy());
        next.tick(2,0,0,c.snapshot()); c.clear(); next.tick(2,0,1000,c.snapshot());
        check(Boolean.TRUE.equals(next.status().get("closed")) && g.closes == 1,"cache epoch cancellation");
        Fake h = new Fake(); h.throwMove = true; YandexVectorPrefetch fail = new YandexVectorPrefetch(h,plan(),3,new YandexVectorPrefetch.Policy());
        fail.tick(3,0,0,new VectorRoadTileCache().snapshot());
        check(fail.status().get("state").equals("failed") && h.closes == 1,"partial window failure closes own resources");
        Fake j = new Fake(); YandexVectorPrefetch rollback = new YandexVectorPrefetch(j,plan(),4,new YandexVectorPrefetch.Policy());
        rollback.tick(4,0,1000,new VectorRoadTileCache().snapshot()); rollback.tick(4,0,999,new VectorRoadTileCache().snapshot());
        check(rollback.status().get("state").equals("failed") && j.closes == 1,"clock rollback fails closed");
    }
    static void owner() throws Exception {
        Fake f = new Fake(); YandexVectorPrefetch p = new YandexVectorPrefetch(f,plan(),1,new YandexVectorPrefetch.Policy());
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = new Thread(() -> {try{p.tick(1,0,0,new VectorRoadTileCache().snapshot());}catch(Throwable x){thrown.set(x);}});
        t.start();t.join(5000); check(thrown.get() instanceof IllegalStateException && f.moves == 0,"wrong thread does not call map API"); p.close();
    }
    public static void main(String[] args) throws Exception { observation(); budgets(); owner(); System.out.println("YandexVectorPrefetchTest: "+checks+" checks passed; native loading unverified"); }
}
