package dev.denza.hud.probe;

/** The evidence recorder's decisions that tools/adas_bridge relies on: generation, sequence, slot, archive retry, back-pressure. */
public final class EvidencePolicyTest {
    private static int checks;

    public static void main(String[] args) {
        EvidencePolicy p = new EvidencePolicy();
        check(p.due("guidance", 0), "first sample due");
        EvidencePolicy.Decision a = p.take("guidance", "route-a", 0);
        check(a.generation == 1 && a.sequence == 0 && a.slot == 0 && a.archive, "first route: generation 1, archive attempted");
        check(!p.due("guidance", 2000), "pending sample blocks the next (back-pressure)");
        p.done();
        check(!p.due("guidance", 500), "same mode within a second is not due");
        check(p.due("off", 500), "a mode change is due at once (cancellation)");
        check(p.due("guidance", 1000), "due again after a second");
        EvidencePolicy.Decision b = take(p, "guidance", "route-a", 1000);
        check(b.generation == 1 && b.sequence == 1 && !b.archive, "same mode and route: same generation, no archive within 10 s");
        // Route switch in the same mode bumps the generation and archives the new route.
        EvidencePolicy.Decision c = take(p, "guidance", "route-b", 2000);
        check(c.generation == 2 && c.archive, "same-mode route switch bumps generation and archives");
        // guidance -> lost -> guidance on the same route: two bumps.
        EvidencePolicy.Decision d = take(p, "lost", "route-b", 3000);
        EvidencePolicy.Decision e = take(p, "guidance", "route-b", 4000);
        check(d.generation == 3 && e.generation == 4, "lost and back are new generations");
        // A failed save does not poison the cache: retried after 10 s, cached after success.
        p.archived("route-b", false, false);
        check(!take(p, "guidance", "route-b", 5000).archive, "failed save waits for the retry gate");
        check(take(p, "guidance", "route-b", 12_001).archive, "failed save retried after 10 s");
        p.archived("route-b", true, false);
        check(!take(p, "guidance", "route-b", 30_000).archive, "a saved route is not archived again");
        // Cancel: off with no route.
        EvidencePolicy.Decision off = take(p, "off", null, 31_000);
        check(off.generation == 5 && !off.archive, "cancel is its own generation without archive");
        // A missing serializer API stops retries for good.
        p.archived("route-c", false, true);
        check(!take(p, "guidance", "route-c", 40_000).archive && !take(p, "guidance", "route-c", 60_000).archive,
                "unsupported serializer is never retried");
        // Sequence and ring slot.
        EvidencePolicy q = new EvidencePolicy();
        EvidencePolicy.Decision last = null;
        for (int i = 0; i < 121; i++) last = take(q, "guidance", "r", i * 1000L);
        check(last.sequence == 120 && last.slot == 0, "120-slot ring wraps");
        System.out.println("EvidencePolicyTest: " + checks + " checks passed");
    }

    private static EvidencePolicy.Decision take(EvidencePolicy p, String mode, String route, long now) {
        check(p.due(mode, now), "due at " + now);
        EvidencePolicy.Decision d = p.take(mode, route, now);
        p.done();
        return d;
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
}
