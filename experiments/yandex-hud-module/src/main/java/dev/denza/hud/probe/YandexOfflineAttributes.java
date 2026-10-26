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

/** Whole selected-route acquisition. Only a complete bounded corridor read is readReady.
 * Category gaps remain explicit. The coordinator owns publication of a successful snapshot;
 * changed SDK package versions allow a fresh attempt even on an unchanged selected route. */
final class YandexOfflineAttributes {
    static final int MAX_TILES = 4096, MAX_SAMPLES = 500000, MAX_PACKAGES = 64;
    static final long MAX_DECODED_BYTES = 256L * 1024 * 1024, MAX_WORK_MS = 20000;
    static final long MAX_PATH_WAIT_MS = 10000;
    static final class View {
        final boolean settled;
        final boolean readReady;
        final String sourceKey, preparationKey;
        final RouteAttributes attributes;
        final Map<String,Object> status;
        View(boolean done, boolean ready, RouteAttributes attributes, Map<String,Object> status) {
            settled = done; readReady = ready; this.attributes = attributes;
            sourceKey = (String) status.get("sourceKey"); preparationKey = (String) status.get("preparationKey");
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
        this(backgroundWorker(), new OfflineTileDecoder());
    }
    YandexOfflineAttributes(Handler worker, OfflineVectorPackage.Decoder decoder) {
        if (worker == null || decoder == null) throw new IllegalArgumentException("worker and decoder required");
        this.worker = worker; this.decoder = decoder;
    }
    private static Handler backgroundWorker() {
        HandlerThread thread = new HandlerThread("denza-offline-attributes", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start(); return new Handler(thread.getLooper());
    }

    /** No filesystem access here. Fresh SDK snapshots also cancel obsolete background requests. */
    void sample(String routeId, long epoch, String geometry, YandexOfflineCachePath.PathSnapshot path,
                YandexOfflineInventory.Snapshot inventory) {
        Input next = new Input(routeId, epoch, geometry, path, inventory);
        synchronized (lock) {
            if (current == null || !next.sourceKey.equals(current.sourceKey)) sourceWaitStartedAt = SystemClock.elapsedRealtime();
            current = next;
            if (!next.sourceKey.equals(completedSourceKey)) { completed = null; completedKey = null; completedSourceKey = null; }
            if (wanted != null && !wanted.input.sourceKey.equals(next.sourceKey)) wanted = null;
        }
    }

    String sourceKey() {
        Input input = current;
        return input == null ? null : input.sourceKey;
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
            long now = SystemClock.elapsedRealtime();
            if (input.inventory == null || !input.inventory.ready(now))
                return view(input.inventory != null && !input.inventory.valid, null,
                        input.inventory != null && !input.inventory.valid ? input.inventory.reasonCode : "waiting_for_offline_inventory", input);
            if (input.path == null || !input.path.ready(now)) {
                String reason = input.path == null ? "not_requested" : input.path.reasonCode;
                boolean terminal = !"not_requested".equals(reason) && !"path_stale".equals(reason) && !"ready".equals(reason);
                if (terminal || now - sourceWaitStartedAt >= MAX_PATH_WAIT_MS) {
                    completedKey = input.routeKey; completedSourceKey = input.sourceKey;
                    completed = view(true, null, terminal ? reason : "offline_path_wait_budget", input); return completed;
                }
                return view(false, null, "waiting_for_offline_path", input);
            }
            if (completed != null && input.sourceKey.equals(completedSourceKey)) return completed;
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
                detail.put("failureCode", failure.getMessage());
                result = new View(true, false, null, detail);
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
        Map<String,Object> detail = new LinkedHashMap<>();
        detail.put("tilesRequested", plan.required.size());
        detail.put("coverageScope", "conservative_segment_tile_corridor");
        detail.put("neighborRadius", 1);
        Input latest = current;
        YandexOfflineGraphFiles.Snapshot files = YandexOfflineGraphFiles.discoverVectors(latest.path, latest.inventory, started);
        detail.put("packageDiscovery", files.status());
        if (!files.valid) return report(null, files.reasonCode, work.input, detail);
        if (files.rejectedCount != 0) return report(null, "offline_package_unreadable_or_unsupported", work.input, detail);
        if (files.files.size() > MAX_PACKAGES) return report(null, "package_budget", work.input, detail);
        RouteVectorAttributes.Builder builder = new RouteVectorAttributes.Builder(work.route);
        Set<VectorRoadTile.TileId> found = new HashSet<>();
        List<Map<String,Object>> packages = new ArrayList<>();
        detail.put("packages", packages);
        long[] decodedBytes = {0}; int[] decodedTiles = {0};
        for (YandexOfflineGraphFiles.Package source : files.files) {
            check(work, started);
            // Even duplicate tile IDs in overlapping regions are consumed: a second version
            // may contradict the first. Never silently choose by directory iteration order.
            Map<String,Object> receipt;
            try { receipt = YandexOfflineGraphFiles.mapStable(source, bytes -> {
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
                row.put("packagePath", source.file.getPath());
                row.put("version", catalog.version); row.put("downloadedRelease", source.downloadedRelease);
                row.put("tilesDecoded", hits); row.put("dictionarySha256", hex(sha().digest(dictionary)));
                row.put("tileEvidenceSha256", hex(evidence.digest())); return row;
            }); } catch (IOException | RuntimeException | LinkageError failure) {
                Map<String,Object> rejected = new LinkedHashMap<>();
                rejected.put("regionId", source.regionId); rejected.put("packagePath", source.file.getPath());
                rejected.put("packageIdentity", source.identity); rejected.put("errorType", failure.getClass().getSimpleName());
                rejected.put("error", failure.getMessage()); rejected.put("rejected", true); packages.add(rejected);
                detail.put("failureCode", failure.getMessage());
                return report(null, "offline_attributes_rejected", work.input, detail);
            }
            packages.add(receipt);
        }
        check(work, started);
        // A rename/update of any earlier package invalidates the entire accumulated result.
        latest = current;
        YandexOfflineGraphFiles.Snapshot after = YandexOfflineGraphFiles.discoverVectors(latest.path, latest.inventory, SystemClock.elapsedRealtime());
        if (!after.valid || after.rejectedCount != 0 || !files.identity.equals(after.identity)) {
            detail.put("packageDiscoveryAfter", after.status());
            return report(null, "offline_packages_changed", work.input, detail);
        }
        RouteVectorAttributes.Result attributes = builder.finish();
        check(work, started);
        detail.put("tilesFound", found.size()); detail.put("tilesMissing", plan.required.size() - found.size());
        List<Map<String,Object>> missing = new ArrayList<>();
        for (VectorRoadTile.TileId tile : plan.required) if (!found.contains(tile) && missing.size() < 64) {
            Map<String,Object> row = new LinkedHashMap<>(); row.put("x", tile.x); row.put("y", tile.y); row.put("z", tile.z);
            RouteVectorTilePlan.Center center = RouteVectorTilePlan.tileCenter(tile);
            row.put("longitude", center.lon); row.put("latitude", center.lat); missing.add(row);
        }
        detail.put("missingTiles", missing); detail.put("missingTileDiagnosticsLimit", 64);
        detail.put("missingTileDiagnosticsTruncated", plan.required.size() - found.size() > missing.size());
        detail.put("decodedTiles", decodedTiles[0]); detail.put("decodedBytes", decodedBytes[0]);
        detail.put("elapsedMs", SystemClock.elapsedRealtime() - started);
        if (found.size() != plan.required.size()) return report(null, "offline_route_tiles_missing", work.input, detail);
        if (attributes.truncated) return report(null, "attribute_match_budget", work.input, detail);
        detail.put("attributes", attributes.summary()); detail.put("fieldEvidence", attributes.evidence);
        int knownCategories = 0;
        for (RouteVectorAttributes.Span span : attributes.spans)
            if (!span.ambiguous && span.rendererClass.functionalClass() != null) knownCategories++;
        detail.put("categorySpansKnown", knownCategories); detail.put("categorySpansTotal", attributes.spans.size());
        detail.put("categoriesComplete", !attributes.spans.isEmpty() && knownCategories == attributes.spans.size());
        detail.put("packageIdentity", files.identity);
        detail.put("routeTileCoverageChecked", true);
        return report(attributes, "prepared", work.input, detail);
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
        boolean ready = attributes != null && "prepared".equals(reason);
        status.put("readReady", ready); status.put("state", ready ? "ready" : settled ? "blocked" : "pending");
        status.put("reason", reason); status.put("source", "yandex_downloaded_vector");
        status.put("reasonRussian", russian(reason));
        status.put("routeId", input == null ? null : input.routeId);
        status.put("epoch", input == null ? -1 : input.epoch);
        status.put("geometrySha256", input == null ? null : input.geometry);
        status.put("coverageAdmissionRequired", true); status.put("fullRouteCoverageProved", false);
        status.put("routeTileCoverageChecked", false);
        status.put("categoriesComplete", false); status.put("staticAttributesFrozen", false);
        status.put("sourceKey", input == null ? null : input.sourceKey);
        status.put("preparationKey", input == null ? null : input.sourceKey);
        return new View(settled, ready, attributes, status);
    }
    private static View report(RouteAttributes attributes, String reason, Input input, Map<String,Object> detail) {
        View base = view(true, attributes, reason, input);
        Map<String,Object> status = new LinkedHashMap<>(base.status); status.putAll(detail);
        return new View(true, base.readReady, attributes, status);
    }
    private static String russian(String reason) {
        if ("prepared".equals(reason)) return "Офлайн-карты всего маршрута прочитаны";
        if ("offline_route_tiles_missing".equals(reason) || "no_supported_package".equals(reason)
                || "no_usable_regions".equals(reason))
            return "Загрузите офлайн-карты всего выбранного маршрута в Яндексе и дождитесь окончания загрузки";
        if ("reading_offline_attributes".equals(reason)) return "Проверяем офлайн-карты всего выбранного маршрута";
        if (reason.startsWith("waiting_for_")) return "Ожидаем выбранный маршрут и сведения об офлайн-картах";
        if (reason.contains("budget")) return "Маршрут превышает пределы безопасного чтения офлайн-карт";
        if (reason.contains("changed") || reason.contains("superseded")) return "Офлайн-карты обновились; требуется повторная проверка";
        return "Не удалось достоверно прочитать офлайн-карты всего маршрута. Проверьте загрузку и обновление карт в Яндексе";
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
