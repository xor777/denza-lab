package dev.denza.hud.probe;

import dev.denza.adaslink.RouteVectorTilePlan;
import dev.denza.adaslink.VectorRoadTileCache;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Supplier;

/** Main-thread route-driven warmup of one private offscreen map; neither map coverage nor an ADAS gate. */
final class YandexRouteVectorSource implements AutoCloseable {
    static final long FAILURE_COOLDOWN_MS = 30000, MINIMUM_MOVE_INTERVAL_MS = 1000;
    static final int MAXIMUM_PHYSICAL_CREATIONS = 3;
    interface LoaderFactory { YandexVectorPrefetch.Loader create(Reflect api); }
    private final Thread owner = Thread.currentThread();
    private final Supplier<VectorRoadTileCache.Snapshot> snapshots;
    private final LoaderFactory factory;
    private RouteVectorTilePlan plan;
    private YandexVectorPrefetch prefetch;
    private YandexVectorPrefetch.Loader physicalLoader;
    private boolean physicalOpened, closed;
    private String routeId, geometrySha, state = "waiting_route", error;
    private long epoch = -1, highestEpoch = -1, lastNow = -1, retryAt, lastPhysicalMove = -1;
    private long routeChanges, staleEpochs, failures, logicalStarts, physicalOpens, physicalMoves;
    private int physicalCreations, maximumScalar = -1;

    YandexRouteVectorSource(Supplier<VectorRoadTileCache.Snapshot> snapshots) { this(snapshots, YandexVectorPrefetch::newLoader); }
    YandexRouteVectorSource(Supplier<VectorRoadTileCache.Snapshot> snapshots, LoaderFactory factory) {
        if (snapshots == null || factory == null) throw new IllegalArgumentException("vector snapshot supplier and loader factory required");
        this.snapshots = snapshots; this.factory = factory;
    }
    /** Caller supplies the current source geometry identity, never a guessed region or bounding box. */
    void sample(Reflect api, String incomingId, long incomingEpoch, String incomingSha, Map<String,Object> copy,
                double scalar, boolean offlineAvailable, long now) {
        owner(); if (closed) return;
        if (incomingEpoch < highestEpoch) { staleEpochs++; return; }
        if (now < 0 || lastNow >= 0 && now < lastNow) { fail("clock_invalid", now < 0 ? 0 : now); return; }
        lastNow = now;
        if (incomingEpoch < 0 || incomingId != null && incomingId.isEmpty()) { fail("route_identity_invalid", now); return; }
        highestEpoch = incomingEpoch;
        boolean changed = epoch != incomingEpoch || !Objects.equals(routeId,incomingId) || !Objects.equals(geometrySha,incomingSha);
        if (changed) {
            cancelPlan(); routeId = incomingId; epoch = incomingEpoch; geometrySha = incomingSha;
            routeChanges++; retryAt = 0; error = null;
        }
        if (incomingId == null) { cancelPlan(); state = "waiting_route"; return; }
        if (incomingSha == null || !incomingSha.matches("[0-9a-f]{64}")) { cancelPlan(); state = "waiting_geometry_identity"; return; }
        if (!offlineAvailable) { cancelPrefetch(); state = "offline_inventory_unavailable"; return; }
        if (now < retryAt) { state = "failure_cooldown"; return; }
        if (copy != null) {
            try {
                Geometry geometry = read(copy,incomingId);
                if (!geometry.sha.equals(incomingSha)) throw new IllegalArgumentException("route copy disagrees with current geometry identity");
                if (plan == null) {
                    plan = new RouteVectorTilePlan(geometry.lon,geometry.lat,
                            new RouteVectorTilePlan.Config(15,3000,120,1,24,2048));
                    maximumScalar = geometry.lon.length - 1;
                }
            } catch (RuntimeException unreadable) { plan = null; fail("route_geometry_invalid",now); return; }
        }
        if (plan == null) { state = "waiting_route_copy"; return; }
        if (!Double.isFinite(scalar) || scalar < 0 || scalar > maximumScalar) {
            cancelPrefetch(); state = "waiting_progress"; return;
        }
        if (prefetch == null && lastPhysicalMove >= 0 && now - lastPhysicalMove < MINIMUM_MOVE_INTERVAL_MS) {
            state = "rate_limited_after_route_change"; return;
        }
        try {
            VectorRoadTileCache.Snapshot snapshot = snapshots.get();
            if (snapshot == null || snapshot.released) { cancelPrefetch(); state = "waiting_vector_source"; return; }
            if (prefetch == null) {
                if (physicalLoader == null) {
                    if (physicalCreations >= MAXIMUM_PHYSICAL_CREATIONS) { state = "window_budget_exhausted"; return; }
                    physicalCreations++; physicalLoader = factory.create(api);
                }
                if (physicalLoader == null) throw new IllegalStateException("loader factory returned null");
                // Each route cancels only its scheduling. The same physical window is
                // reused because the pinned SDK exposes no explicit offscreen destroy.
                YandexVectorPrefetch.Loader logical = new YandexVectorPrefetch.Loader() {
                    @Override public void open() {
                        if (!physicalOpened) { physicalLoader.open(); physicalOpened = true; physicalOpens++; }
                    }
                    @Override public void move(dev.denza.adaslink.VectorRoadTile.TileId tile,RouteVectorTilePlan.Center center) {
                        lastPhysicalMove = lastNow; physicalMoves++; physicalLoader.move(tile,center);
                    }
                    @Override public void close() { /* Only cancel this route's scheduling; SDK window stays owned here. */ }
                };
                prefetch = new YandexVectorPrefetch(logical,plan,epoch,new YandexVectorPrefetch.Policy()); logicalStarts++;
            }
            prefetch.tick(epoch,scalar,now,snapshot);
            Map<String,Object> probe = prefetch.status();
            if (Boolean.TRUE.equals(probe.get("closed"))) { fail("prefetch_stopped: " + probe.get("state"),now); return; }
            state = String.valueOf(probe.get("state"));
        } catch (RuntimeException failed) { fail("offscreen_unavailable",now); }
    }
    Map<String,Object> status() {
        owner(); Map<String,Object> m = new LinkedHashMap<>();
        m.put("schema","denza-yandex-route-vector-source-v1"); m.put("state",state); m.put("closed",closed);
        m.put("routeId",routeId); m.put("routeEpoch",epoch); m.put("geometrySha256",geometrySha); m.put("sampledElapsedMs",lastNow);
        m.put("routeChanges",routeChanges); m.put("staleEpochsIgnored",staleEpochs); m.put("failures",failures); m.put("retryAtElapsedMs",retryAt);
        m.put("logicalStarts",logicalStarts); m.put("physicalOpens",physicalOpens); m.put("physicalMoves",physicalMoves);
        m.put("physicalCreationAttempts",physicalCreations); m.put("maximumPhysicalCreationAttempts",MAXIMUM_PHYSICAL_CREATIONS);
        m.put("hasPlan",plan != null); m.put("hasPhysicalWindow",physicalOpened); m.put("error",error);
        m.put("aheadMetres",3000); m.put("maximumTiles",24); m.put("nativeFetchVerified",false);
        m.put("prefetch",prefetch == null ? null : prefetch.status());
        m.put("meaning","offscreen warmup of a bounded moving route window; not an Amap acquisition rule or offline/full-route coverage proof");
        m.put("cancellation","stops future camera moves; already issued SDK requests may complete");
        return Collections.unmodifiableMap(m);
    }
    private void cancelPrefetch() { if (prefetch != null) prefetch.close(); prefetch = null; }
    private void cancelPlan() { cancelPrefetch(); plan = null; maximumScalar = -1; }
    private void fail(String reason,long now) {
        cancelPrefetch(); releasePhysical(); failures++; error = reason; state = "failed";
        retryAt = now > Long.MAX_VALUE - FAILURE_COOLDOWN_MS ? Long.MAX_VALUE : now + FAILURE_COOLDOWN_MS;
    }
    private void releasePhysical() {
        if (physicalLoader != null) try { physicalLoader.close(); } catch (RuntimeException ignored) { /* Owner is dropping it regardless. */ }
        physicalLoader = null; physicalOpened = false;
    }
    private void owner() { if (Thread.currentThread() != owner) throw new IllegalStateException("route vector source owner thread required"); }
    @Override public void close() { owner(); if (closed) return; closed = true; cancelPlan(); releasePhysical(); state = "closed"; }

    private static final class Geometry {
        final double[] lon,lat; final String sha;
        Geometry(double[] lon,double[] lat,String sha){this.lon=lon;this.lat=lat;this.sha=sha;}
    }
    private static Geometry read(Map<String,Object> copy,String id) {
        if (!id.equals(copy.get("routeId")) || !"wgs84".equals(copy.get("coordinateFrame")) || !"lon,lat".equals(copy.get("pointOrder"))
                || !(copy.get("points") instanceof List)) throw new IllegalArgumentException("current WGS84 route copy required");
        List<?> points=(List<?>)copy.get("points");
        if(points.size()<2 || points.size()>200000)throw new IllegalArgumentException("route point budget");
        double[] lon=new double[points.size()],lat=new double[points.size()];
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");ByteBuffer pair=ByteBuffer.allocate(16);
            for(int i=0;i<points.size();i++) {
                Object point=points.get(i);if(!(point instanceof List)||((List<?>)point).size()!=2)throw new IllegalArgumentException("route point");
                List<?> p=(List<?>)point;if(!(p.get(0)instanceof Number)||!(p.get(1)instanceof Number))throw new IllegalArgumentException("route coordinate");
                lon[i]=((Number)p.get(0)).doubleValue();lat[i]=((Number)p.get(1)).doubleValue();
                if(!Double.isFinite(lon[i])||!Double.isFinite(lat[i])||Math.abs(lon[i])>180||Math.abs(lat[i])>90)throw new IllegalArgumentException("coordinate bounds");
                pair.clear();pair.putDouble(lon[i]).putDouble(lat[i]);digest.update(pair.array());
            }
            StringBuilder hash=new StringBuilder(64);for(byte b:digest.digest()){hash.append(Character.forDigit((b>>>4)&15,16));hash.append(Character.forDigit(b&15,16));}
            return new Geometry(lon,lat,hash.toString());
        } catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
