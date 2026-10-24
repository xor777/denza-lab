package dev.denza.adaslink;

/** Inventory-only admission, exact route identity and monotonic freshness; no coverage claim. */
public final class OfflineMapGateTest {
    static int checks;
    static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    public static void main(String[] args) {
        String a = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String b = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        OfflineMapGate.Subject route = new OfflineMapGate.Subject("route", 1, a);
        OfflineMapGate.Snapshot available = OfflineMapGate.Snapshot.available(route, 10000, a);
        check(OfflineMapGate.check(route, available, 10000).ready, "fresh inventory permits current route without coverage proof");
        check(available.state == OfflineMapGate.State.AVAILABLE && available.proofFingerprint == null,
                "inventory-only state never fabricates a coverage proof");
        check(available.code.equals("offline_inventory_ready") && available.reason.equals("Офлайн-карта загружена"),
                "production inventory status is explicit");
        check(!OfflineMapGate.check(route, null, 10000).ready, "default is blocked");
        check(!OfflineMapGate.check(new OfflineMapGate.Subject("new", 1, a), available, 10000).ready, "ID bound");
        check(!OfflineMapGate.check(new OfflineMapGate.Subject("route", 2, a), available, 10000).ready, "epoch bound");
        check(!OfflineMapGate.check(new OfflineMapGate.Subject("route", 1, b), available, 10000).ready, "ordered geometry bound");
        check(!OfflineMapGate.check(new OfflineMapGate.Subject("route", 1, null), available, 10000).ready, "partial geometry blocks immediately");
        check(!OfflineMapGate.check(new OfflineMapGate.Subject("route", 1, "invalid"), available, 10000).ready, "invalid geometry hash blocks");
        check(!OfflineMapGate.check(new OfflineMapGate.Subject(null, 2, null), available, 10000).ready, "cancel bound");
        check(!OfflineMapGate.check(route, available, 9999).ready, "future monotonic time rejected");
        check(OfflineMapGate.check(route, available, 14999).ready, "fresh TTL edge");
        check(!OfflineMapGate.check(route, available, 15000).ready, "exact stale boundary");
        check(!OfflineMapGate.check(route, OfflineMapGate.Snapshot.pending(route, 10000, a, "pending", "Проверка карты"), 10000).ready,
                "explicit pending state does not admit");
        check(!OfflineMapGate.check(route, OfflineMapGate.Snapshot.unavailable(route, 10000, a, "missing", "Карта отсутствует"), 10000).ready,
                "explicit disappearance blocks");
        check(!OfflineMapGate.check(route, OfflineMapGate.Snapshot.available(route, 10000, null), 10000).ready, "inventory fingerprint required");
        check(!OfflineMapGate.check(route, OfflineMapGate.Snapshot.available(route, 10000, "invalid"), 10000).ready, "invalid inventory fingerprint rejected");
        check(OfflineMapGate.check(route, OfflineMapGate.Snapshot.verified(route, 10000, a, b), 10000).ready,
                "future explicit coverage state remains supported");
        check(!OfflineMapGate.check(route, OfflineMapGate.Snapshot.verified(route, 10000, a, null), 10000).ready,
                "explicit coverage state still requires its real proof fingerprint");
        System.out.println("OfflineMapGateTest: " + checks + " checks passed");
    }
}
