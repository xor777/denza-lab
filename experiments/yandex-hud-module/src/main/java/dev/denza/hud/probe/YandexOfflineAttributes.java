package dev.denza.hud.probe;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import dev.denza.adaslink.OfflineVectorPackage;
import dev.denza.adaslink.RouteAttributes;
import dev.denza.adaslink.RouteVectorAttributes;
import dev.denza.adaslink.RouteVectorTilePlan;
import dev.denza.adaslink.VectorRoadTile;
import dev.denza.adaslink.YandexRoute;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Whole selected-route attribute acquisition. SDK observations enter from main; bounded file
 * work owns a separate worker. A completed attempt is frozen for the route, including explicit
 * partial/empty results. Completeness is not an additional offline-map admission gate. */
final class YandexOfflineAttributes {
    static final int MAX_TILES = 4096, MAX_SAMPLES = 500000, MAX_PACKAGES = 64;
    static final long MAX_DECODED_BYTES = 256L * 1024 * 1024, MAX_WORK_MS = 20000;
    static final long MAX_PATH_WAIT_MS = 10000;
    static final class View {
        final boolean settled;
        final RouteAttributes attributes;
        final Map<String,Object> status;
        View(boolean done, RouteAttributes attributes, Map<String,Object> status) {
            settled = done; this.attributes = attributes;
            this.status = Collections.unmodifiableMap(new LinkedHashMap<>(status));
        }
    }
    private static final class Input {
        final String routeId, geometry, routeKey, sourceKey;
        final long epoch;
        final YandexOfflineCachePath.PathSnapshot path;
        final YandexOfflineInventory.Snapshot inventory;
        Input(String id, long epoch, String geometry, YandexOfflineCachePath.PathSnapshot path,
              YandexOfflineInventory.Snapshot inventory) {
            routeId = id; this.epoch = epoch; this.geometry = geometry; this.path = path; this.inventory = inventory;
            routeKey = id + "|" + epoch + "|" + geometry;
            sourceKey = routeKey + "|" + (path == null ? -1 : path.generation) + "|"
                    + (inventory == null ? "" : inventory.fingerprint);
        }
    }
    private static final class Work {
        final Input input;
        final YandexRoute route;
        Work(Input input, YandexRoute route) { this.input = input; this.route = route; }
    }
    private final Object lock = new Object();
    private final Handler worker;
    private final OfflineVectorPackage.Decoder decoder;
    private volatile Input current;
    private Work wanted, running;
    private boolean scheduled;
    private String completedKey, completedSourceKey;
    private long sourceWaitStartedAt;
    private View completed;
    private YandexRoute checkedRoute;
    private String checkedGeometry;

    YandexOfflineAttributes() {
        decoder = new OfflineTileDecoder();
        HandlerThread thread = new HandlerThread("denza-offline-attributes", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start(); worker = new Handler(thread.getLooper());
    }

    /** No filesystem access here. Fresh SDK snapshots also cancel obsolete background requests. */
    void sample(String routeId, long epoch, String geometry, YandexOfflineCachePath.PathSnapshot path,
                YandexOfflineInventory.Snapshot inventory) {
        Input next = new Input(routeId, epoch, geometry, path, inventory);
        synchronized (lock) {
            if (current == null || !next.routeKey.equals(current.routeKey)) sourceWaitStartedAt = SystemClock.elapsedRealtime();
            current = next;
            if (!next.routeKey.equals(completedKey)) { completed = null; completedKey = null; completedSourceKey = null; }
            if (wanted != null && !wanted.input.sourceKey.equals(next.sourceKey)) wanted = null;
        }
    }

    /** ADAS conversion worker; never waits for disk or decompression. */
    View prepare(YandexRoute route) {
        synchronized (lock) {
            Input input = current;
            if (input == null || route == null || !route.routeId.equals(input.routeId) || input.geometry == null)
                return view(false, null, "waiting_for_route", null);
            if (route != checkedRoute) {
                checkedGeometry = RouteVectorAttributes.geometryFingerprint(route); checkedRoute = route;
            }
            if (!input.geometry.equals(checkedGeometry)) return view(false, null, "waiting_for_current_geometry", input);
            if (completed != null && input.routeKey.equals(completedKey)) {
                Map<String,Object> report = new LinkedHashMap<>(completed.status);
                report.put("staticAttributesFrozen", true);
                report.put("sourceChangedSincePreparation", !input.sourceKey.equals(completedSourceKey));
                return new View(true, completed.attributes, report);
            }
            long now = SystemClock.elapsedRealtime();
            if (input.inventory == null || !input.inventory.ready(now))
                return view(false, null, "waiting_for_offline_inventory", input);
            if (input.path == null || !input.path.ready(now)) {
                String reason = input.path == null ? "not_requested" : input.path.reasonCode;
                boolean terminal = !"not_requested".equals(reason) && !"path_stale".equals(reason) && !"ready".equals(reason);
                if (terminal || now - sourceWaitStartedAt >= MAX_PATH_WAIT_MS) {
                    completedKey = input.routeKey; completedSourceKey = input.sourceKey;
                    completed = view(true, null, terminal ? reason : "offline_path_wait_budget", input); return completed;
                }
                return view(false, null, "waiting_for_offline_path", input);
            }
            if ((running == null || !running.input.sourceKey.equals(input.sourceKey))
                    && (wanted == null || !wanted.input.sourceKey.equals(input.sourceKey))) wanted = new Work(input, route);
            if (!scheduled && wanted != null) { scheduled = true; worker.post(this::drain); }
            return view(false, null, "reading_offline_attributes", input);
        }
    }

    private void drain() {
        while (true) {
            Work work;
            synchronized (lock) {
                work = wanted; wanted = null; running = work;
                if (work == null) { scheduled = false; return; }
            }
            View result;
            try { result = read(work); }
            catch (IOException | RuntimeException | LinkageError failure) {
                result = view(true, null, "offline_attributes_rejected", work.input);
                Map<String,Object> detail = new LinkedHashMap<>(result.status);
                detail.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
                result = new View(true, null, detail);
            }
            synchronized (lock) {
                if (current != null && current.sourceKey.equals(work.input.sourceKey)
                        && current.path != null && current.path.ready(SystemClock.elapsedRealtime())
                        && current.inventory != null && current.inventory.ready(SystemClock.elapsedRealtime())) {
                    completed = result; completedKey = work.input.routeKey; completedSourceKey = work.input.sourceKey;
                }
                running = null;
            }
        }
    }

    private View read(Work work) throws IOException {
        long started = SystemClock.elapsedRealtime(); check(work, started);
        RouteVectorTilePlan.Window plan = new RouteVectorTilePlan(work.route.lon, work.route.lat).wholeRoute(MAX_TILES, MAX_SAMPLES);
        if (plan.truncated) return view(true, null, "route_tile_budget", work.input);
        Input latest = current;
        YandexOfflineGraphFiles.Snapshot files = YandexOfflineGraphFiles.discoverVectors(latest.path, latest.inventory, started);
        if (!files.valid) return view(true, null, files.reasonCode, work.input);
        if (files.rejectedCount != 0) return view(true, null, "offline_package_unreadable_or_unsupported", work.input);
        if (files.files.size() > MAX_PACKAGES) return view(true, null, "package_budget", work.input);
        RouteVectorAttributes.Builder builder = new RouteVectorAttributes.Builder(work.route);
        Set<VectorRoadTile.TileId> found = new HashSet<>();
        List<Map<String,Object>> packages = new ArrayList<>();
        long[] decodedBytes = {0}; int[] decodedTiles = {0};
        for (YandexOfflineGraphFiles.Package source : files.files) {
            check(work, started);
            // Even duplicate tile IDs in overlapping regions are consumed: a second version
            // may contradict the first. Never silently choose by directory iteration order.
            Map<String,Object> receipt = YandexOfflineGraphFiles.mapStable(source, bytes -> {
                OfflineVectorPackage catalog = new OfflineVectorPackage(bytes);
                byte[] dictionary = catalog.dictionary(); int hits = 0;
                MessageDigest evidence = sha();
                evidence.update(sha().digest(dictionary));
                for (VectorRoadTile.TileId tile : plan.required) {
                    check(work, started);
                    OfflineVectorPackage.Entry entry = catalog.find(tile);
                    if (entry == null) continue;
                    byte[] compressed = catalog.compressed(entry);
                    byte[] raw = decoder.decode(compressed, dictionary, OfflineVectorPackage.MAX_DECODED_BYTES);
                    decodedBytes[0] += raw.length;
                    if (decodedBytes[0] > MAX_DECODED_BYTES) throw new IOException("decoded_byte_budget");
                    VectorRoadTile data = VectorRoadTile.decode(raw, tile, tile, tile, new VectorRoadTile.Limits());
                    if (!builder.accept(data)) throw new IOException("attribute_match_budget");
                    evidence.update(ByteBuffer.allocate(8).putLong(entry.packedId).array());
                    evidence.update(sha().digest(compressed)); evidence.update(sha().digest(raw));
                    found.add(tile); hits++; decodedTiles[0]++;
                }
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("regionId", source.regionId); row.put("packageIdentity", source.identity);
                row.put("version", catalog.version); row.put("downloadedRelease", source.downloadedRelease);
                row.put("tilesDecoded", hits); row.put("dictionarySha256", hex(sha().digest(dictionary)));
                row.put("tileEvidenceSha256", hex(evidence.digest())); return row;
            });
            packages.add(receipt);
        }
        check(work, started);
        // A rename/update of any earlier package invalidates the entire accumulated result.
        latest = current;
        YandexOfflineGraphFiles.Snapshot after = YandexOfflineGraphFiles.discoverVectors(latest.path, latest.inventory, SystemClock.elapsedRealtime());
        if (!after.valid || !files.identity.equals(after.identity)) throw new IOException("offline_packages_changed");
        RouteVectorAttributes.Result attributes = builder.finish();
        check(work, started);
        Map<String,Object> report = new LinkedHashMap<>(view(true, attributes, "prepared", work.input).status);
        report.put("tilesRequested", plan.required.size()); report.put("tilesFound", found.size());
        report.put("tilesMissing", plan.required.size() - found.size()); report.put("decodedTiles", decodedTiles[0]);
        report.put("decodedBytes", decodedBytes[0]); report.put("packages", packages);
        report.put("elapsedMs", SystemClock.elapsedRealtime() - started); report.put("attributes", attributes.summary());
        report.put("fieldEvidence", attributes.evidence);
        report.put("fullRouteCoverageProved", false); report.put("staticAttributesFrozen", true);
        return new View(true, attributes, report);
    }

    private void check(Work work, long started) throws IOException {
        Input latest = current; long now = SystemClock.elapsedRealtime();
        if (latest == null || !latest.sourceKey.equals(work.input.sourceKey)) throw new IOException("superseded_route_or_maps");
        if (latest.path == null || !latest.path.ready(now) || latest.inventory == null || !latest.inventory.ready(now))
            throw new IOException("source_observation_expired");
        if (now < started || now - started > MAX_WORK_MS) throw new IOException("attribute_time_budget");
    }
    private static View view(boolean settled, RouteAttributes attributes, String reason, Input input) {
        Map<String,Object> status = new LinkedHashMap<>();
        status.put("schema", "denza-offline-route-attributes-v1"); status.put("settled", settled);
        status.put("reason", reason); status.put("source", "yandex_downloaded_vector");
        status.put("routeId", input == null ? null : input.routeId);
        status.put("epoch", input == null ? -1 : input.epoch);
        status.put("geometrySha256", input == null ? null : input.geometry);
        status.put("coverageAdmissionRequired", false);
        return new View(settled, attributes, status);
    }
    private static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) { out.append(Character.forDigit((value >>> 4) & 15, 16)); out.append(Character.forDigit(value & 15, 16)); }
        return out.toString();
    }
}
