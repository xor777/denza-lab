package dev.denza.hud.probe;

import dev.denza.adaslink.RouteVectorTilePlan;
import dev.denza.adaslink.VectorRoadTile;
import dev.denza.adaslink.VectorRoadTileCache;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Experimental offscreen-camera tile-loading probe. No pixels, global map changes, or network API. */
final class YandexVectorPrefetch implements AutoCloseable {
    interface Loader {
        void open();
        void move(VectorRoadTile.TileId tile, RouteVectorTilePlan.Center center);
        void close();
    }
    static final class Policy {
        final long minimumMoveIntervalMs, timeoutMs, cooldownMs;
        final int maximumAttempts;
        Policy() { this(1000, 5000, 30000, 2); }
        Policy(long interval, long timeout, long cooldown, int attempts) {
            if (interval < 1000 || timeout < 1000 || timeout > 30000 || cooldown < timeout || cooldown > 300000 || attempts < 1 || attempts > 4)
                throw new IllegalArgumentException("bounded prefetch policy required");
            minimumMoveIntervalMs = interval; timeoutMs = timeout; cooldownMs = cooldown; maximumAttempts = attempts;
        }
    }
    private static final class Attempt { int count; long lastAt; }
    private final Thread owner = Thread.currentThread();
    private final RouteVectorTilePlan plan; private final long routeEpoch; private final Loader loader; private final Policy policy;
    private final Map<VectorRoadTile.TileId, Attempt> attempts = new LinkedHashMap<>();
    private volatile boolean closed;
    private boolean opened; private long lastTick = -1, lastMove = -1, cacheEpoch = -1, pendingGeneration, pendingAt;
    private long requests, observed, timeouts; private VectorRoadTile.TileId pending;
    private RouteVectorTilePlan.Window lastWindow; private String state = "new", error;
    YandexVectorPrefetch(Reflect api, RouteVectorTilePlan plan, long routeEpoch) { this(new ReflectLoader(api), plan, routeEpoch, new Policy()); }
    YandexVectorPrefetch(Loader loader, RouteVectorTilePlan plan, long routeEpoch, Policy policy) {
        if (loader == null || plan == null || routeEpoch < 0 || policy == null) throw new IllegalArgumentException("owned route prefetch dependencies required");
        this.loader = loader; this.plan = plan; this.routeEpoch = routeEpoch; this.policy = policy;
    }
    /** Main-thread owner only. Only actual cache contents establish tile observations. */
    void tick(long currentRouteEpoch, double progressScalar, long nowMs, VectorRoadTileCache.Snapshot snapshot) {
        owner(); if (closed) return;
        if (currentRouteEpoch != routeEpoch) { state = "route_superseded"; closeOwned(); return; }
        if (nowMs < 0 || (lastTick >= 0 && nowMs < lastTick)) { fail("clock_rollback"); return; } lastTick = nowMs;
        if (snapshot == null) { state = "waiting_vector_source"; return; }
        if (snapshot.released || (cacheEpoch >= 0 && cacheEpoch != snapshot.epoch)) { state = "source_epoch_changed"; closeOwned(); return; }
        cacheEpoch = snapshot.epoch;
        Set<VectorRoadTile.TileId> present = new HashSet<>(); for (VectorRoadTile tile : snapshot.tiles) present.add(tile.tileId());
        try { lastWindow = plan.window(progressScalar, present); }
        catch (RuntimeException e) { fail("invalid_progress: " + e); return; }
        attempts.keySet().retainAll(new HashSet<>(lastWindow.required));
        if (pending != null) {
            if (snapshot.generation > pendingGeneration && present.contains(pending)) { observed++; pending = null; }
            else if (!lastWindow.required.contains(pending)) pending = null;
            else if (nowMs - pendingAt >= policy.timeoutMs) { timeouts++; pending = null; }
            else { state = "pending_observed_tile"; return; }
        }
        if (lastWindow.missing.isEmpty()) { state = lastWindow.truncated ? "sampled_window_truncated" : "sampled_window_observed"; return; }
        if (lastMove >= 0 && nowMs - lastMove < policy.minimumMoveIntervalMs) { state = "rate_limited"; return; }
        for (VectorRoadTile.TileId tile : lastWindow.missing) {
            Attempt a = attempts.get(tile);
            if (a != null && (a.count >= policy.maximumAttempts || nowMs - a.lastAt < policy.cooldownMs)) continue;
            if (a == null) { a = new Attempt(); attempts.put(tile, a); }
            a.count++; a.lastAt = nowMs; lastMove = nowMs;
            try {
                if (!opened) { opened = true; loader.open(); }
                pending = tile; pendingAt = nowMs; pendingGeneration = snapshot.generation; requests++;
                loader.move(tile, RouteVectorTilePlan.tileCenter(tile)); state = "pending_observed_tile";
            } catch (RuntimeException e) { fail("offscreen_failure: " + e); }
            return;
        }
        state = "missing_retry_budget_or_cooldown";
    }
    Map<String, Object> status() {
        owner(); Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", "denza-yandex-vector-prefetch-experimental-v1"); m.put("state", state); m.put("closed", closed); m.put("routeEpoch", routeEpoch);
        m.put("requests", requests); m.put("observedRequestedTiles", observed); m.put("timeouts", timeouts);
        m.put("pendingTile", pending == null ? null : pending.toString()); m.put("trackedAttempts", attempts.size()); m.put("error", error);
        m.put("requiredTiles", lastWindow == null ? null : lastWindow.required.size()); m.put("missingTiles", lastWindow == null ? null : lastWindow.missing.size());
        m.put("windowTruncated", lastWindow != null && lastWindow.truncated);
        m.put("coverageMeaning", "sampled forward tile corridor only; requests are not geometry or complete-route coverage");
        m.put("nativeFetchVerified", false);
        return Collections.unmodifiableMap(m);
    }
    private void owner() { if (Thread.currentThread() != owner) throw new IllegalStateException("prefetch owner thread required"); }
    private void fail(String reason) { error = reason; state = "failed"; closeOwned(); }
    @Override public void close() { owner(); if (closed) return; state = "closed"; closeOwned(); }
    private void closeOwned() {
        closed = true; pending = null; attempts.clear();
        if (opened) try { loader.close(); } catch (RuntimeException e) { error = "offscreen_close: " + e; }
    }
    static Loader newLoader(Reflect api) { return new ReflectLoader(api); }
    private static final class ReflectLoader implements Loader {
        private static final int MAXIMUM_PROCESS_CREATIONS = 3;
        private static final AtomicInteger processCreations = new AtomicInteger();
        private final Reflect api; private Object offscreen, window, map;
        ReflectLoader(Reflect api) { if (api == null) throw new IllegalArgumentException("reflection bridge required"); this.api = api; }
        @Override public void open() {
            Object kit = api.call(api.type("com.yandex.mapkit.MapKitFactory"), "getInstance");
            // No explicit SDK destroy exists. A failed create/setup consumes the
            // process quota too, since a native window may already exist.
            for (;;) {
                int count = processCreations.get();
                if (count >= MAXIMUM_PROCESS_CREATIONS) throw new IllegalStateException("offscreen process creation budget exhausted");
                if (processCreations.compareAndSet(count,count + 1)) break;
            }
            offscreen = api.call(kit, "createOffscreenMapWindow", 768, 768, 1f);
            window = api.call(offscreen, "getMapWindow"); map = api.call(window, "getMap");
            api.call(window, "setMaxFps", 1); api.call(map, "set2DMode", true);
            api.call(map, "setModelsEnabled", false); api.call(map, "setIndoorEnabled", false);
        }
        @Override public void move(VectorRoadTile.TileId id, RouteVectorTilePlan.Center center) {
            Object point = api.make("com.yandex.mapkit.geometry.Point", center.lat, center.lon);
            api.call(map, "move", api.make("com.yandex.mapkit.map.CameraPosition", point, (float) id.z, 0f, 0f));
        }
        @Override public void close() {
            // Pinned OffscreenMapWindow has no destroy/stop method. Release owned
            // Java references; native cleanup and already issued requests belong to SDK.
            map = null; window = null; offscreen = null;
        }
    }
}
