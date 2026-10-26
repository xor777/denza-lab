package dev.denza.adaslink;

import java.util.Objects;

/** Route-scoped offline admission. Available inventory does not establish route coverage. */
public final class OfflineMapGate {
    public static final long MAX_AGE_MS = 5000;
    public enum State { PENDING, UNAVAILABLE, AVAILABLE, VERIFIED, PREPARED }

    public static final class Subject {
        public final String routeId, geometrySha256;
        public final long epoch;
        /** A null ID is inactive; an active ID with null geometry blocks while geometry is read. */
        public Subject(String routeId, long epoch, String geometrySha256) {
            if (epoch < 0 || routeId != null && routeId.isEmpty()) throw new IllegalArgumentException("route identity required");
            if (routeId == null && geometrySha256 != null) throw new IllegalArgumentException("inactive geometry");
            this.routeId = routeId; this.epoch = epoch; this.geometrySha256 = geometrySha256;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Subject)) return false;
            Subject s = (Subject) other;
            return epoch == s.epoch && Objects.equals(routeId, s.routeId) && Objects.equals(geometrySha256, s.geometrySha256);
        }
        @Override public int hashCode() { return Objects.hash(routeId, epoch, geometrySha256); }
    }

    public static final class Snapshot {
        public final Subject subject;
        public final long observedAtMs;
        public final String inventoryFingerprint, proofFingerprint, code, reason;
        public final State state;
        private Snapshot(Subject subject, long observedAtMs, String inventoryFingerprint, State state,
                         String code, String reason, String proofFingerprint) {
            this.subject = subject; this.observedAtMs = observedAtMs; this.inventoryFingerprint = inventoryFingerprint;
            this.state = state; this.code = code; this.reason = reason; this.proofFingerprint = proofFingerprint;
        }
        public static Snapshot pending(Subject subject, long observedAtMs, String inventoryFingerprint, String code, String reason) {
            return new Snapshot(subject, observedAtMs, inventoryFingerprint, State.PENDING, code, reason, null);
        }
        public static Snapshot unavailable(Subject subject, long observedAtMs, String inventoryFingerprint, String code, String reason) {
            return new Snapshot(subject, observedAtMs, inventoryFingerprint, State.UNAVAILABLE, code, reason, null);
        }
        /** At least one usable installed region; coverage of this route has not been checked. */
        public static Snapshot available(Subject subject, long observedAtMs, String inventoryFingerprint) {
            return new Snapshot(subject, observedAtMs, inventoryFingerprint, State.AVAILABLE,
                    "offline_inventory_ready", "Офлайн-карта загружена", null);
        }
        /** Regional discovery and a completed bounded route-tile read; not proof that every category is known. */
        public static Snapshot prepared(Subject subject, long observedAtMs, String inventoryFingerprint, String proofFingerprint) {
            return new Snapshot(subject, observedAtMs, inventoryFingerprint, State.PREPARED,
                    "offline_route_prepared", "Офлайн-карты маршрута прочитаны", proofFingerprint);
        }
        /** Only verified local coverage of this chosen online route may call this; an alternate route is no proof. */
        public static Snapshot verified(Subject subject, long observedAtMs, String inventoryFingerprint, String proofFingerprint) {
            return new Snapshot(subject, observedAtMs, inventoryFingerprint, State.VERIFIED,
                    "verified_offline_route", "Офлайн-карта текущего маршрута подтверждена", proofFingerprint);
        }
    }

    public static final class Decision {
        public final boolean ready;
        public final String code, reason;
        private Decision(boolean ready, String code, String reason) { this.ready = ready; this.code = code; this.reason = reason; }
    }

    public static Decision check(Subject expected, Snapshot snapshot, long now) {
        if (expected == null || expected.routeId == null) return no("route_unavailable", "Ожидание маршрута Яндекса");
        if (!sha(expected.geometrySha256)) return no("geometry_unavailable", "Ожидание геометрии маршрута для проверки офлайн-карты");
        if (snapshot == null) return no("offline_unavailable", "Список загруженных офлайн-карт ещё не проверен");
        if (!expected.equals(snapshot.subject)) return no("route_mismatch", "Предпроверка офлайн-карт относится к другому маршруту");
        if (snapshot.observedAtMs < 0 || snapshot.observedAtMs > now || now - snapshot.observedAtMs >= MAX_AGE_MS)
            return no("offline_stale", "Нет свежего списка загруженных офлайн-карт");
        if (snapshot.state != State.AVAILABLE && snapshot.state != State.VERIFIED && snapshot.state != State.PREPARED)
            return no(snapshot.code == null ? "offline_unavailable" : snapshot.code,
                    snapshot.reason == null || snapshot.reason.isEmpty() ? "Офлайн-карта недоступна" : snapshot.reason);
        if (!sha(snapshot.inventoryFingerprint)) return no("inventory_unavailable", "Нет достоверного списка загруженных офлайн-карт");
        if ((snapshot.state == State.VERIFIED || snapshot.state == State.PREPARED) && !sha(snapshot.proofFingerprint))
            return no("proof_unavailable", "Нет доказательства офлайн-покрытия текущего маршрута");
        return new Decision(true, snapshot.code, snapshot.reason);
    }
    private static Decision no(String code, String reason) { return new Decision(false, code, reason); }
    private static boolean sha(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private OfflineMapGate() { }
}
