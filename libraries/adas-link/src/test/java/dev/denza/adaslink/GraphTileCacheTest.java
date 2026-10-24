package dev.denza.adaslink;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Cache budgets, content identity, immutable readers and deterministic worker lifecycle races. */
public final class GraphTileCacheTest {
    static int checks;
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    static void basic() throws Exception {
        byte[] a = GraphTileTest.fixture(1), b = GraphTileTest.fixture(2), c = GraphTileTest.fixture(3);
        GraphTileCache cache = new GraphTileCache(2, 1024 * 1024);
        GraphTile first = cache.put(a, "a");
        check(first != null && cache.generation() == 1, "first accepted tile changes generation");
        cache.put(b, "b");
        GraphTile same = cache.put(a, "different source label");
        check(same == first && cache.generation() == 2, "content hash dedup reuses immutable decoded object");
        GraphTileCache.Snapshot old = cache.snapshot();
        check(old.tiles.size() == 2 && old.sourceBytes == a.length + b.length, "atomic source-byte accounting");
        cache.put(c, "c");
        GraphTileCache.Snapshot now = cache.snapshot();
        check(now.tiles.size() == 2 && now.tiles.get(0) == first && now.tiles.get(1).edge(3) != null, "LRU evicts least recently used content");
        check(((Number) cache.summary().get("evictions")).longValue() == 1, "eviction evidence");
        check(old.tiles.size() == 2 && old.tiles.get(0).edge(2) != null, "prior snapshot survives eviction");
        GraphTileTest.immutable(() -> old.tiles.clear(), "snapshot list immutable");
        GraphTileTest.immutable(() -> cache.summary().clear(), "summary detached and immutable");
        long generation = cache.generation(); cache.clear();
        check(cache.generation() == generation + 1 && cache.snapshot().tiles.isEmpty() && !cache.snapshot().released, "clear purges and advances lifecycle");
        check(cache.put(a, "again") != null, "clear permits later tile updates");
        GraphTileCache.Snapshot held = cache.snapshot();
        cache.release();
        check(cache.snapshot().released && cache.snapshot().tiles.isEmpty() && cache.snapshot().retainedBytes == 0, "release drops cache ownership");
        check(cache.put(b, "after release") == null, "released cache accepts no further work");
        check(held.tiles.get(0).edge(1) != null, "released cache does not invalidate a reader's snapshot");
        long releasedGeneration = cache.generation(); cache.release();
        check(cache.generation() == releasedGeneration, "release idempotent");
    }

    static void budgets() throws Exception {
        byte[] a = GraphTileTest.fixture(1), b = GraphTileTest.fixture(2);
        long chargeA = GraphTile.decode(a, "a").estimatedRetainedBytes();
        long chargeB = GraphTile.decode(b, "b").estimatedRetainedBytes();
        GraphTileCache one = new GraphTileCache(16, Math.max(chargeA, chargeB) + 64);
        GraphTile first = one.put(a, "a");
        check(first != null, "one decoded tile fits exact conservative charge");
        check(one.put(b, "b") != null && one.snapshot().tiles.size() == 1, "decoded byte budget evicts even below tile-count bound");
        check(one.snapshot().retainedBytes <= Math.max(chargeA, chargeB) + 64, "decoded retention charge bounded");
        check(one.snapshot().retainedBytes > one.snapshot().sourceBytes, "raw source bytes not mistaken for decoded heap size");
        char[] chars = new char[4096]; Arrays.fill(chars, 'x');
        byte[] large = GraphTileTest.tile(4, 77, 1, 2, 0, 0, false, new double[][]{{0, 0}, {.001, 0}}, new String(chars), 12.5f);
        GraphTile before = one.snapshot().tiles.get(0);
        check(one.put(large, "larger") == null && one.snapshot().tiles.get(0) == before, "oversized decoded tile does not evict useful contents");
        GraphTileCache tiny = new GraphTileCache(1, 1);
        check(tiny.put(a, "too large") == null && tiny.snapshot().tiles.isEmpty(), "raw byte precheck rejects before decoding");
        GraphTileTest.fails(() -> one.put(new byte[]{(byte) 0x80}, "malformed"), "malformed protobuf rejected");
        check(one.snapshot().tiles.get(0) == before, "malformed tile preserves current contents");
        GraphTileCache fieldBound = new GraphTileCache(16, 1024 * 1024, new GraphTile.Limits(10000, 2, 100, 100, 100));
        GraphTileTest.fails(() -> fieldBound.put(a, "budget"), "cache honours parser field budget");
        check(fieldBound.snapshot().tiles.isEmpty(), "budget failure cannot enter cache");
    }

    static final class BlockingDecoder implements GraphTileCache.Decoder {
        final CountDownLatch entered, proceed = new CountDownLatch(1);
        BlockingDecoder(int count) { entered = new CountDownLatch(count); }
        @Override public GraphTile decode(byte[] raw, String source, GraphTile.Limits limits) {
            entered.countDown();
            try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new AssertionError("test decoder was not released"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            return GraphTile.decode(raw, source, limits);
        }
    }
    static Thread worker(GraphTileCache cache, byte[] raw, AtomicReference<GraphTile> result, AtomicReference<Throwable> error) {
        Thread thread = new Thread(() -> { try { result.set(cache.put(raw, "worker")); } catch (Throwable e) { error.set(e); } });
        thread.start(); return thread;
    }
    static void finish(Thread thread, AtomicReference<Throwable> error) throws Exception {
        thread.join(5000); check(!thread.isAlive(), "worker finished"); check(error.get() == null, "worker did not fail: " + error.get());
    }
    static void lifecycleRace(boolean release) throws Exception {
        BlockingDecoder decoder = new BlockingDecoder(1);
        GraphTileCache cache = new GraphTileCache(16, 1024 * 1024, new GraphTile.Limits(), decoder);
        AtomicReference<GraphTile> result = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>();
        Thread thread = worker(cache, GraphTileTest.fixture(1), result, error);
        try {
            check(decoder.entered.await(5, TimeUnit.SECONDS), "background decode entered");
            check(cache.snapshot().tiles.isEmpty(), "snapshot remains readable while decoder blocked");
            if (release) cache.release(); else cache.clear();
        } finally { decoder.proceed.countDown(); }
        finish(thread, error);
        check(result.get() == null && cache.snapshot().tiles.isEmpty(), "in-flight decode cannot repopulate after " + (release ? "release" : "clear"));
        if (!release) check(cache.put(GraphTileTest.fixture(2), "new generation") != null, "fresh work after clear still accepted");
    }
    static void concurrentDedup() throws Exception {
        BlockingDecoder decoder = new BlockingDecoder(2);
        GraphTileCache cache = new GraphTileCache(16, 1024 * 1024, new GraphTile.Limits(), decoder);
        AtomicReference<GraphTile> a = new AtomicReference<>(), b = new AtomicReference<>();
        AtomicReference<Throwable> ea = new AtomicReference<>(), eb = new AtomicReference<>();
        byte[] raw = GraphTileTest.fixture(1);
        Thread ta = worker(cache, raw, a, ea), tb = worker(cache, raw, b, eb);
        try { check(decoder.entered.await(5, TimeUnit.SECONDS), "two concurrent decode attempts entered outside cache lock"); }
        finally { decoder.proceed.countDown(); }
        finish(ta, ea); finish(tb, eb);
        check(a.get() == b.get() && a.get() != null, "racing duplicate commits return one cached immutable object");
        check(cache.snapshot().tiles.size() == 1 && cache.generation() == 1, "racing duplicates change content generation once");
        check(((Number) cache.summary().get("accepted")).longValue() == 1 && ((Number) cache.summary().get("duplicates")).longValue() == 1, "concurrent dedup accounting");
    }

    @SuppressWarnings("unchecked") static void captured(File root) throws Exception {
        File directory = new File(root, "captures/yandex-java-graph-20261024/oracle");
        Map<String, Object> manifest = (Map<String, Object>) Json.parse(new String(Files.readAllBytes(new File(directory, "manifest.json").toPath()), StandardCharsets.UTF_8));
        GraphTileCache cache = new GraphTileCache();
        for (Object value : (List<Object>) manifest.get("tiles")) {
            String input = (String) ((Map<String, Object>) value).get("input");
            GraphTile tile = cache.put(Files.readAllBytes(new File(root, input).toPath()), input);
            check(tile != null, "captured tile accepted: " + input);
            check(cache.snapshot().retainedBytes <= GraphTileCache.DEFAULT_MAX_BYTES && cache.snapshot().tiles.size() <= 16, "captured cache remains bounded");
        }
        Files.write(new File(directory, "cache-summary.json").toPath(), (Json.write(cache.summary()) + "\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("GraphTileCache captured: " + Json.write(cache.summary()));
    }

    public static void main(String[] args) throws Exception {
        basic(); budgets(); lifecycleRace(false); lifecycleRace(true); concurrentDedup();
        if (args.length > 0) captured(new File(args[0]));
        System.out.println("GraphTileCacheTest: " + checks + " checks, 0 failures");
    }
}
