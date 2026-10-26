package dev.denza.hud.probe;

import android.os.Looper;
import dev.denza.adaslink.OfflineMapGate;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Main-thread coordinator of manual map installation, regional discovery and completed local reads. */
final class YandexOfflineMapSource {
    private final LiveMapBridge live;
    private final YandexOfflineInventory inventory = new YandexOfflineInventory();
    private final YandexRouteRegions regions = new YandexRouteRegions();
    private OfflineMapGate.Subject subject;
    private Map<String,Object> geometryCopy;
    private Map<String,Object> status = Collections.emptyMap();
    private volatile YandexOfflineAttributes.View readerView;
    private String preparationKey;
    private boolean mayRead;

    YandexOfflineMapSource(LiveMapBridge live) { this.live = live; }

    /** Cheap revocation before route copying, hashing or SDK inventory reads. */
    void routeIdentity(String routeId, long epoch) {
        if (subject == null || subject.epoch != epoch || !Objects.equals(subject.routeId, routeId)) {
            subject = new OfflineMapGate.Subject(routeId, epoch, null);
            geometryCopy = null; readerView = null; mayRead = false;
            live.sourceRoute(subject);
        }
    }
    void geometryUnavailable(String routeId, long epoch) {
        subject = new OfflineMapGate.Subject(routeId, epoch, null);
        geometryCopy = null; readerView = null; mayRead = false;
        live.sourceRoute(subject);
    }

    /** This is the only writer of live admission. Background results never grant their own admission. */
    void sample(Reflect api, String routeId, long epoch, Map<String,Object> routeCopy,
                YandexOfflineCachePath.PathSnapshot path, YandexOfflineAttributes reader, long now) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("offline source main thread required");
        routeIdentity(routeId, epoch);
        if (routeCopy != null) {
            try {
                subject = new OfflineMapGate.Subject(routeId, epoch, geometryHash(routeCopy, routeId));
                geometryCopy = routeCopy;
            } catch (RuntimeException unreadable) { geometryUnavailable(routeId, epoch); }
        }
        live.sourceRoute(subject);
        YandexOfflineInventory.Snapshot maps = inventory.poll(api, now);
        YandexRouteRegions.Snapshot required = regions.sample(api, routeId, epoch,
                subject.geometrySha256, geometryCopy, maps, now);
        reader.sample(routeId, epoch, subject.geometrySha256, path, maps);
        String nextKey = reader.sourceKey();
        if (!Objects.equals(preparationKey, nextKey)) readerView = null;
        preparationKey = nextKey;
        // Invalidates queued/retained callbacks even when only a package version changed.
        live.sourcePreparation(subject, preparationKey);
        mayRead = required.ready(now) && maps.ready(now);
        YandexOfflineAttributes.View attributes = readerView;
        OfflineMapGate.Snapshot snapshot = admissionSnapshot(subject, maps, required, attributes, preparationKey,
                path != null && path.ready(now), now);
        live.offlineMap(snapshot);
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("schema", "denza-offline-map-prerequisite-v3"); report.put("capturedElapsedMs", now);
        report.put("routeId", subject.routeId); report.put("routeEpoch", subject.epoch);
        report.put("geometrySha256", subject.geometrySha256); report.put("preparationKey", preparationKey);
        report.put("state", String.valueOf(snapshot.state)); report.put("code", snapshot.code); report.put("reason", snapshot.reason);
        report.put("inventory", maps.status()); report.put("routeRegions", required.status());
        report.put("reader", attributes != null && Objects.equals(preparationKey, attributes.sourceKey) ? attributes.status : null);
        report.put("mode", "manual_download_then_route_read"); report.put("automaticDownload", false);
        report.put("categoriesComplete", attributes != null && snapshot.state == OfflineMapGate.State.PREPARED
                && Boolean.TRUE.equals(attributes.status.get("categoriesComplete")));
        report.put("routeTileCoverageChecked", attributes != null && snapshot.state == OfflineMapGate.State.PREPARED
                && Boolean.TRUE.equals(attributes.status.get("routeTileCoverageChecked")));
        report.put("completeRouteCoverageChecked", false);
        report.put("alternateOfflineRouteEqualityRequired", false);
        status = Collections.unmodifiableMap(report);
    }

    /** Immutable worker result; consumed only after checking the current main-thread source key. */
    void readerResult(YandexOfflineAttributes.View view) { readerView = view; }

    static OfflineMapGate.Snapshot admissionSnapshot(OfflineMapGate.Subject subject,
            YandexOfflineInventory.Snapshot maps, YandexRouteRegions.Snapshot regions,
            YandexOfflineAttributes.View attributes, String sourceKey, boolean pathReady, long now) {
        long observed = maps == null ? now : maps.observedAt;
        String fingerprint = maps == null ? null : maps.fingerprint;
        if (subject == null || subject.routeId == null)
            return OfflineMapGate.Snapshot.pending(subject, observed, fingerprint,
                    "route_not_started", "Ожидаем маршрут для проверки офлайн-карт");
        if (subject.geometrySha256 == null || !subject.geometrySha256.matches("[0-9a-f]{64}"))
            return OfflineMapGate.Snapshot.unavailable(subject, observed, fingerprint,
                    "route_geometry_unavailable", "Не удалось прочитать геометрию маршрута");
        if (regions == null || !Objects.equals(subject.routeId, regions.routeId) || subject.epoch != regions.epoch
                || !Objects.equals(subject.geometrySha256, regions.geometrySha256)
                || !Objects.equals(fingerprint, regions.inventoryFingerprint))
            return OfflineMapGate.Snapshot.pending(subject, observed, fingerprint,
                    "regions_pending", "Проверяем загруженные регионы выбранного маршрута");
        if (!regions.ready(now))
            return OfflineMapGate.Snapshot.unavailable(subject, observed, fingerprint, regions.code, regions.reason);
        if (maps == null || !maps.ready(now))
            return OfflineMapGate.Snapshot.unavailable(subject, observed, fingerprint,
                    "offline_stale", "Нет свежего списка загруженных офлайн-карт");
        if (!pathReady)
            return OfflineMapGate.Snapshot.unavailable(subject, observed, fingerprint,
                    "offline_path_unavailable", "Ожидаем доступ к загруженным картам Яндекса");
        if (attributes == null || sourceKey == null || !sourceKey.equals(attributes.sourceKey))
            return OfflineMapGate.Snapshot.pending(subject, observed, fingerprint,
                    "offline_read_pending", "Проверяем офлайн-карты всего выбранного маршрута");
        if (!attributes.readReady || attributes.attributes == null)
            return OfflineMapGate.Snapshot.unavailable(subject, observed, fingerprint,
                    String.valueOf(attributes.status.get("reason")), String.valueOf(attributes.status.get("reasonRussian")));
        return OfflineMapGate.Snapshot.prepared(subject, observed, fingerprint,
                hashText(sourceKey + "|" + regions.fingerprint + "|" + attributes.status.get("packageIdentity")));
    }

    private static String hashText(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) { out.append(Character.forDigit((b >>> 4) & 15, 16)); out.append(Character.forDigit(b & 15, 16)); }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    Map<String,Object> status() { return status; }
    boolean mayRead() { return mayRead; }
    String preparationKey() { return preparationKey; }
    YandexOfflineInventory.Snapshot inventorySnapshot() { return inventory.snapshot(); }
    boolean available(long now) { return inventory.ready(now); }
    String geometrySha256() { return subject == null ? null : subject.geometrySha256; }

    static String geometryHash(Map<String,Object> copy, String routeId) {
        if (routeId == null || !routeId.equals(copy.get("routeId")) || !"wgs84".equals(copy.get("coordinateFrame"))
                || !"lon,lat".equals(copy.get("pointOrder")) || !(copy.get("points") instanceof List))
            throw new IllegalArgumentException("current WGS84 route geometry required");
        List<?> points = (List<?>) copy.get("points");
        if (points.size() < 2 || points.size() > 200000) throw new IllegalArgumentException("route geometry size");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer pair = ByteBuffer.allocate(16);
            for (Object value : points) {
                if (!(value instanceof List) || ((List<?>) value).size() != 2) throw new IllegalArgumentException("route point");
                List<?> point = (List<?>) value;
                if (!(point.get(0) instanceof Number) || !(point.get(1) instanceof Number)) throw new IllegalArgumentException("route coordinate");
                double lon = ((Number) point.get(0)).doubleValue(), lat = ((Number) point.get(1)).doubleValue();
                if (!Double.isFinite(lon) || !Double.isFinite(lat) || Math.abs(lon) > 180 || Math.abs(lat) > 90)
                    throw new IllegalArgumentException("route coordinate bounds");
                pair.clear(); pair.putDouble(lon).putDouble(lat); digest.update(pair.array());
            }
            StringBuilder out = new StringBuilder();
            for (byte b : digest.digest()) { out.append(Character.forDigit((b >>> 4) & 15, 16)); out.append(Character.forDigit(b & 15, 16)); }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
