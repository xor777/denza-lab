package dev.denza.adaslink;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

/** Content+TileId identity, replacement, retention budget and delayed lifecycle boundaries. */
public final class VectorRoadTileCacheTest {
    static int checks;
    static void check(boolean ok, String m) { checks++; if (!ok) throw new AssertionError(m); }
    static final VectorRoadTile.TileId A = new VectorRoadTile.TileId(0, 0, 2), B = new VectorRoadTile.TileId(1, 0, 2), C = new VectorRoadTile.TileId(2, 0, 2);
    static VectorRoadTile put(VectorRoadTileCache c, byte[] raw, VectorRoadTile.TileId id) { return c.put(raw, id, id, id); }
    static void basic() {
        byte[] raw = VectorRoadTileTest.fixture(1), changed = VectorRoadTileTest.fixture(2);
        VectorRoadTileCache c = new VectorRoadTileCache(2, 1 << 20);
        VectorRoadTile first = put(c, raw, A); check(first != null && c.generation() == 1, "firsttile accepted");
        check(put(c, raw, A) == first && c.generation() == 1, "content+context duplicate no generation churn");
        check(put(c, raw, B) != first && c.snapshot().tiles.size() == 2, "same bytes different context notdeduplicated");
        VectorRoadTileCache.Snapshot held = c.snapshot();
        VectorRoadTile second = put(c, changed, A);
        check(second != first && c.snapshot().tiles.size() == 2 && c.generation() == 3, "newcontent replaces sameTileId");
        check(((Number) c.summary().get("replacements")).intValue() == 1, "replacement counter");
        check(held.tiles.get(0) == first && held.generation == 2, "heldsnapshot remainsvalid afterreplacement");
        put(c, raw, C);
        check(c.snapshot().tiles.get(0) == second && c.snapshot().tiles.size() == 2, "LRUevicts otheroldtile");
        check(((Number) c.summary().get("evictions")).intValue() == 1, "eviction observed");
        VectorRoadTileTest.immutable(() -> held.tiles.clear()); VectorRoadTileTest.immutable(() -> c.summary().clear());
        VectorRoadTileTest.immutable(() -> ((List<?>) c.summary().get("activeFingerprints")).clear());
        long epoch = c.snapshot().epoch; c.clear();
        check(c.snapshot().tiles.isEmpty() && c.snapshot().epoch > epoch, "clear advancesepoch");
        check(put(c, raw, A) != null, "clear permitsnewdecode"); c.release(); long g = c.generation(); c.release();
        check(c.snapshot().released && c.snapshot().tiles.isEmpty() && c.generation() == g, "release idempotent");
        check(put(c, raw, A) == null && held.tiles.size() == 2, "release blocksnewwork preservesheldobjects");
    }
    static void budgets() {
        byte[] a = VectorRoadTileTest.fixture(1), b = VectorRoadTileTest.fixture(2);
        long charge = VectorRoadTile.decode(a, A, A, A).estimatedRetainedBytes();
        VectorRoadTileCache c = new VectorRoadTileCache(100, charge);
        check(put(c, a, A) != null && c.snapshot().retainedBytes <= charge, "exactdecodedchargefits");
        check(put(c, b, B) != null && c.snapshot().tiles.size() == 1 && c.snapshot().retainedBytes <= charge, "bytecapnotjusttilecap");
        VectorRoadTile keep = c.snapshot().tiles.get(0);
        VectorRoadTileTest.fails(() -> put(c, new byte[]{(byte) 128}, B), "malformedreplacementthrows");
        check(c.snapshot().tiles.get(0) == keep, "malformedreplacementpreservesoldtile");
        VectorRoadTileTest.fails(() -> c.put(a, A, B, A), "differentcontextcannotbypasscachehashhit");
        check(c.snapshot().tiles.get(0) == keep, "contextrejectionpreservescontent");
        check(put(new VectorRoadTileCache(1, 1), a, A) == null, "oversizerawrejectedbeforeparse");
        VectorRoadTileCache tiny = new VectorRoadTileCache(1, a.length);
        check(put(tiny, a, A) == null && tiny.snapshot().tiles.isEmpty(), "decodedchargegreaterthanrawrejectswithoutinsert");
    }
    static final class Block implements VectorRoadTileCache.Decoder {
        final CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        @Override public VectorRoadTile decode(byte[] raw, VectorRoadTile.TileId w, VectorRoadTile.TileId r, VectorRoadTile.TileId s, VectorRoadTile.Limits limits) {
            entered.countDown();
            try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new AssertionError("decodeblocknotreleased"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            return VectorRoadTile.decode(raw, w, r, s, limits);
        }
    }
    static void boundary(boolean release) throws Exception {
        Block b = new Block(); VectorRoadTileCache c = new VectorRoadTileCache(16, 1 << 20, new VectorRoadTile.Limits(), b);
        AtomicReference<Object> result = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { result.set(put(c, VectorRoadTileTest.fixture(1), A)); } catch (Throwable e) { result.set(e); } });
        worker.start(); check(b.entered.await(5, TimeUnit.SECONDS), "decoderstartedoutsidecachelock");
        check(c.snapshot().tiles.isEmpty(), "snapshotnotblockedbydecode");
        if (release) c.release(); else c.clear();
        b.proceed.countDown(); worker.join(5000);
        check(!worker.isAlive() && result.get() == null && c.snapshot().tiles.isEmpty(), "oldepochdecodecannotresurrectafterclearorrelease");
    }
    static VectorRoadTile sequenced(VectorRoadTileCache c, byte[] raw, VectorRoadTile.TileId id, long sequence) {
        return c.put(raw, id, id, id, sequence);
    }
    static long metric(VectorRoadTileCache c, String name) { return ((Number)c.summary().get(name)).longValue(); }
    static void sequenceAdmission() {
        byte[] a = VectorRoadTileTest.fixture(1), b = VectorRoadTileTest.fixture(2);
        VectorRoadTileCache c = new VectorRoadTileCache(16, 1 << 20);
        VectorRoadTile first = sequenced(c,a,A,100), different = sequenced(c,a,B,50);
        check(first != null && different != null && c.snapshot().tiles.size() == 2, "different tiles naturally arrive out of sequence");
        check(sequenced(c,b,A,99) == null && metric(c,"staleSequenceRejected") == 1,"old same-tile replacement rejected");
        check(sequenced(c,a,A,100) == null,"equal sequence replay rejected even same content");
        long generation = c.generation();
        check(sequenced(c,a,B,51) == different && c.generation() == generation,"newer same-content packet advances watermark without generation churn");
        check(sequenced(c,b,B,50) == null,"duplicate-content admission still protects later stale replacement");
        VectorRoadTile newA = sequenced(c,b,A,101);
        check(newA != null && newA.features().get(0).id == 2,"newer different content replaces");
        check(put(c,a,A) == null && metric(c,"unsequencedRejected") == 1,"legacy overload cannot bypass native ordering");
        c.clear();
        check(sequenced(c,a,A,100) == null && c.snapshot().tiles.isEmpty(),"clear preserves process-lifetime sequence watermarks");
        check(sequenced(c,a,B,52) != null,"clear still admits useful different tile newer than its own watermark");
        VectorRoadTile keep = c.snapshot().tiles.get(0);
        VectorRoadTileTest.fails(() -> sequenced(c,new byte[]{(byte)128},B,53),"malformed newer packet rejected");
        check(sequenced(c,b,B,52) == null && c.snapshot().tiles.get(0)==keep,"malformed newer packet does not permit an older replacement");
        check(sequenced(c,b,B,54)!=null,"valid following packet admitted");
        VectorRoadTileTest.fails(() -> sequenced(c,a,A,-1),"negative native sequence rejected");
        c.release(); check(sequenced(c,a,A,1000)==null,"release admits no sequence");
        VectorRoadTileCache zero = new VectorRoadTileCache();
        check(sequenced(zero,a,A,0)!=null && sequenced(zero,b,A,0)==null,"native sequence zero valid and replay protected");
    }
    static void retirement() {
        byte[] a = VectorRoadTileTest.fixture(1), b = VectorRoadTileTest.fixture(2);
        VectorRoadTileCache c = new VectorRoadTileCache(1,1<<20);
        sequenced(c,a,A,100); sequenced(c,a,B,200);
        check(sequenced(c,b,A,99)==null,"eviction of content does not forget recent watermark");
        for(int i=0;i<40;i++) {
            VectorRoadTile.TileId id = new VectorRoadTile.TileId(i+2,0,8);
            check(sequenced(c,a,id,300+i)!=null,"new tile sequence admitted within bounded history");
            check(metric(c,"sequenceTrackingEntries")<=metric(c,"maxSequenceTrackingEntries"),"sequence metadata bounded independent of route length");
        }
        long floor=metric(c,"sequenceRetirementFloor");
        check(floor>=200 && metric(c,"sequenceTrackingRetirements")>0,"retired history has conservative explicit floor");
        check(sequenced(c,b,A,101)==null && metric(c,"retiredSequenceRejected")==1,"unknown retired TileId cannot resurrect at stale floor");
        check(sequenced(c,b,A,1000)!=null,"new packet above retired floor can reintroduce tile");
        floor=metric(c,"sequenceRetirementFloor");
        c.clear();
        check(metric(c,"sequenceRetirementFloor")==floor && sequenced(c,a,B,201)==null,"clear does not reset retired process history");
        VectorRoadTileCache useful = new VectorRoadTileCache(1,1<<20);
        // Keep A recent while retiring higher-sequence unrelated keys. A's own watermark
        // remains authoritative even when its next useful sequence is below the floor.
        sequenced(useful,a,A,1);
        for(int i=0;i<20;i++) {
            VectorRoadTile.TileId id=new VectorRoadTile.TileId(i+2,0,8);
            sequenced(useful,a,id,100+i); sequenced(useful,a,A,2+i);
        }
        check(metric(useful,"sequenceRetirementFloor")>21 && sequenced(useful,b,A,22)!=null,"tracked useful tile is not blocked by global high-water of unrelated tiles");
    }
    static final class FirstBlock implements VectorRoadTileCache.Decoder {
        final CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        final AtomicInteger calls=new AtomicInteger();
        @Override public VectorRoadTile decode(byte[] raw,VectorRoadTile.TileId w,VectorRoadTile.TileId r,VectorRoadTile.TileId s,VectorRoadTile.Limits limits) {
            if(calls.incrementAndGet()==1) {
                entered.countDown();
                try {if(!proceed.await(5,TimeUnit.SECONDS))throw new AssertionError("blocked decode not released");}
                catch(InterruptedException e){throw new AssertionError(e);}
            }
            return VectorRoadTile.decode(raw,w,r,s,limits);
        }
    }
    static void delayedSequence(boolean sameTile, boolean clear, boolean legacy) throws Exception {
        FirstBlock block=new FirstBlock();
        VectorRoadTileCache c=new VectorRoadTileCache(16,1<<20,new VectorRoadTile.Limits(),block);
        AtomicReference<Object> old=new AtomicReference<>();
        Thread t=new Thread(()->{try{old.set(legacy?put(c,VectorRoadTileTest.fixture(1),A):sequenced(c,VectorRoadTileTest.fixture(1),A,10));}catch(Throwable e){old.set(e);}});
        t.start();check(block.entered.await(5,TimeUnit.SECONDS),"old decode entered");
        if(clear)c.clear();
        VectorRoadTile recent=sequenced(c,VectorRoadTileTest.fixture(2),sameTile?A:B,20);
        check(recent!=null,"newer admission commits while old decode blocked");
        block.proceed.countDown();t.join(5000);
        check(!t.isAlive(),"old decode completes");
        if(sameTile||clear||legacy)check(old.get()==null&&c.snapshot().tiles.size()==1,"obsolete completion cannot mutate newer content or epoch");
        else check(old.get() instanceof VectorRoadTile&&c.snapshot().tiles.size()==2,"useful out-of-order different-tile decode still commits");
        if(sameTile&&!clear&&!legacy)check(metric(c,"staleCompletions")==1,"stale completion reported");
    }
    public static void main(String[] args) throws Exception {
        basic(); budgets(); boundary(false); boundary(true);sequenceAdmission();retirement();
        delayedSequence(true,false,false);delayedSequence(false,false,false);delayedSequence(true,true,false);delayedSequence(false,false,true);
        System.out.println("VectorRoadTileCacheTest: " + checks + " checks passed");
    }
}
