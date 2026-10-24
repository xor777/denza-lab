package dev.denza.hud.probe;

import android.os.Looper;
import dev.denza.adaslink.OfflineMapGate;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only offline inventory and current-route prerequisite. No route calculation or download. */
final class YandexOfflineMapSource {
    private final LiveMapBridge live;
    private final YandexOfflineInventory inventory = new YandexOfflineInventory();
    private OfflineMapGate.Subject subject;
    private Map<String,Object> status = Collections.emptyMap();

    YandexOfflineMapSource(LiveMapBridge live) { this.live = live; }

    /** Cheap revocation before route copying, hashing or SDK inventory reads. */
    void routeIdentity(String routeId, long epoch) {
        if (subject == null || subject.epoch != epoch || !Objects.equals(subject.routeId, routeId)) {
            subject = new OfflineMapGate.Subject(routeId, epoch, null);
            live.sourceRoute(subject);
        }
    }
    void geometryUnavailable(String routeId, long epoch) {
        subject = new OfflineMapGate.Subject(routeId, epoch, null);
        live.sourceRoute(subject);
    }

    /** Called before an observation can enter the conversion/sender queues. */
    void sample(Reflect api, String routeId, long epoch, Map<String,Object> routeCopy, long now) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("offline source main thread required");
        // An unavailable geometry still revokes the previous route before reading the inventory.
        routeIdentity(routeId, epoch);
        if (routeCopy != null) {
            try { subject = new OfflineMapGate.Subject(routeId, epoch, geometryHash(routeCopy, routeId)); }
            catch (RuntimeException unreadable) { geometryUnavailable(routeId, epoch); }
        }
        live.sourceRoute(subject);
        YandexOfflineInventory.Snapshot maps = inventory.poll(api, now);
        OfflineMapGate.Snapshot snapshot = admissionSnapshot(subject, maps, now);
        live.offlineMap(snapshot);
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("schema", "denza-offline-map-prerequisite-v2"); report.put("capturedElapsedMs", now);
        report.put("routeId", subject.routeId); report.put("routeEpoch", subject.epoch);
        report.put("geometrySha256", subject.geometrySha256);
        report.put("state", String.valueOf(snapshot.state)); report.put("code", snapshot.code); report.put("reason", snapshot.reason);
        report.put("inventory", maps.status());
        report.put("mode", "inventory_only");
        report.put("scope", "INVENTORY_ONLY");
        report.put("coverageSource", "inventory_only");
        report.put("completeRouteCoverageChecked", false);
        report.put("alternateOfflineRouteEqualityRequired", false);
        status = Collections.unmodifiableMap(report);
    }

    /** Pure policy seam: installed-region presence only, scoped to the current route observation. */
    static OfflineMapGate.Snapshot admissionSnapshot(OfflineMapGate.Subject subject,
                                                    YandexOfflineInventory.Snapshot maps, long now) {
        if (!maps.ready(now)) {
            return OfflineMapGate.Snapshot.unavailable(subject, maps.observedAt, maps.fingerprint,
                    maps.valid ? "offline_stale" : maps.reasonCode,
                    maps.valid ? "Нет свежего списка загруженных офлайн-карт" : maps.reasonRussian);
        } else if (subject == null || subject.routeId == null) {
            return OfflineMapGate.Snapshot.pending(subject, maps.observedAt, maps.fingerprint,
                    "route_not_started", "Ожидаем маршрут для проверки офлайн-карты");
        } else if (subject.geometrySha256 == null || !subject.geometrySha256.matches("[0-9a-f]{64}")) {
            return OfflineMapGate.Snapshot.unavailable(subject, maps.observedAt, maps.fingerprint,
                    "route_geometry_unavailable", "Не удалось прочитать геометрию маршрута");
        }
        return OfflineMapGate.Snapshot.available(subject, maps.observedAt, maps.fingerprint);
    }

    Map<String,Object> status() { return status; }
    /** Immutable SDK catalog copy for the background regional reader; no SDK work off main. */
    YandexOfflineInventory.Snapshot inventorySnapshot() { return inventory.snapshot(); }
    /** Current installed-map presence for prefetch; independent of active route and geometry. */
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
