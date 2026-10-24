package dev.denza.adaslink;

/** Clock-domain and held-fix regressions, using injected clocks; no Android or device required. */
public final class FixFreshnessTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++; if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        final long offset = 36_757_978, wall = 1_792_795_000_000L;
        final long relative = 28_700_000, elapsed = relative + offset + 20;
        FixFreshness.Tracker tracker = new FixFreshness.Tracker();
        FixFreshness.Sample car = tracker.observe(relative, wall - 20, relative + 20, elapsed, wall);
        check(car.ageMs(elapsed, wall) == 20, "ten hours of prior suspend is not fix age");
        check(car.isFresh("FINE", elapsed, wall, 1000), "fresh car FINE fix passes driver gate");
        check(car.isFresh("FINE", elapsed + 980, wall + 980, 1000), "1000 ms inclusive");
        check(!car.isFresh("FINE", elapsed + 981, wall + 981, 1000), "1001 ms fails driver gate");
        check(car.isFresh("FINE", elapsed + 2480, wall + 2480, 2500), "2500 ms inclusive");
        check(!car.isFresh("FINE", elapsed + 2481, wall + 2481, 2500), "2501 ms fails snapshot gate");
        check(!car.isFresh("EXTRAPOLATED", elapsed, wall, 2500), "prediction never passes as FINE");
        check(!car.isFresh(null, elapsed, wall, 2500), "unknown class rejects");
        FixFreshness.Sample duplicate = tracker.observe(relative, wall - 20, relative + 50, elapsed + 100_000, wall + 100_000);
        check(duplicate == car && duplicate.ageMs(elapsed + 100_000, wall + 100_000) == 100_020,
                "duplicate after suspend keeps original elapsed anchor");
        check(!duplicate.isFresh("FINE", elapsed + 100_000, wall + 100_000, 2500), "held pre-sleep fix rejects");
        check(duplicate.ageMs(elapsed + 100_000, wall) == FixFreshness.UNKNOWN, "held fix plus wall rollback rejects");
        check(FixFreshness.capture(relative, wall - 100_000, relative + 20, elapsed, wall)
                .ageMs(elapsed, wall) == 100_000, "cold start cached pre-sleep fix vetoed by absolute age");
        check(FixFreshness.capture(relative - 2501, wall - 20, relative + 20, elapsed, wall)
                .ageMs(elapsed, wall) == 2521, "stale native age is not hidden by fresh absolute time");
        check(car.ageMs(elapsed - 1, wall) == FixFreshness.UNKNOWN, "elapsed clock reversal rejects");
        check(car.ageMs(elapsed + 100, wall + 100_000) == FixFreshness.UNKNOWN, "wall clock jump forward unknown");
        check(car.ageMs(elapsed + 100, wall - 100_000) == FixFreshness.UNKNOWN, "wall clock jump backward unknown");
        check(car.ageMs(elapsed + 100, wall + 120) == 140, "bounded sequential clock-read skew accepted conservatively");
        check(car.ageMs(elapsed + 100, wall + 121) == FixFreshness.UNKNOWN, "larger clock skew rejects");

        FixFreshness.Sample b = tracker.observe(relative + 100, wall - 99_920, relative + 120, elapsed + 100, wall - 99_900);
        check(b.isFresh("FINE", elapsed + 100, wall - 99_900, 1000), "new FINE relative timestamp recovers after wall rollback");
        check(tracker.observe(relative, wall - 20, relative + 130, elapsed + 110, wall - 99_890)
                .ageMs(elapsed + 110, wall - 99_890) == FixFreshness.UNKNOWN, "A to B to old A rejects");
        check(tracker.observe(relative + 100, wall - 99_919, relative + 130, elapsed + 110, wall - 99_890)
                .ageMs(elapsed + 110, wall - 99_890) == FixFreshness.UNKNOWN, "mutated absolute time cannot re-anchor same relative identity");
        check(tracker.observe(relative + 100, wall - 99_920, relative + 130, elapsed + 110, wall - 99_890) == b,
                "original duplicate still has original anchor");

        FixFreshness.Tracker future = new FixFreshness.Tracker();
        FixFreshness.Sample bad = future.observe(2000, 999, 1000, 1000, 1000);
        check(bad.ageMs(1000, 1000) == FixFreshness.UNKNOWN, "future relative timestamp rejects");
        check(future.observe(2000, 999, 3000, 3000, 3000).ageMs(3000, 3000) == FixFreshness.UNKNOWN,
                "future duplicate cannot mature into fresh");
        check(!future.observe(1500, 2990, 3000, 3000, 3000).isFresh("FINE", 3000, 3000, 2500),
                "bad forward identity conservatively blocks regressing identities");
        check(future.observe(3001, 3001, 3002, 3002, 3002).isFresh("FINE", 3002, 3002, 1000),
                "strictly newer valid identity can recover");
        FixFreshness.Tracker absFuture = new FixFreshness.Tracker();
        check(!absFuture.observe(1, 2000, 2, 2, 1000).isFresh("FINE", 2, 1000, 2500), "absolute future rejects");
        check(!absFuture.observe(1, 2000, 3, 3, 2001).isFresh("FINE", 3, 2001, 2500), "absolute future duplicate remains rejected");
        check(FixFreshness.capture(-1, 1, 2, 2, 2).ageMs(2, 2) == FixFreshness.UNKNOWN, "missing SDK time rejects");
        check(FixFreshness.capture(1, -1, 2, 2, 2).ageMs(2, 2) == FixFreshness.UNKNOWN, "missing absolute time rejects");
        check(FixFreshness.capture(0, 0, Long.MAX_VALUE - 1, 0, 0).ageMs(2, 2) == FixFreshness.UNKNOWN,
                "age overflow rejects");
        recordedSamples();
        System.out.println("FixFreshnessTest: " + checks + " checks passed");
    }

    private static void recordedSamples() {
        java.util.Map<String, Object> location = Json.object(), sample = Json.object();
        location.put("longitude", 37.6); location.put("latitude", 55.7);
        location.put("relativeTimestamp", 1000L); location.put("absoluteTimestamp", 100_000L);
        sample.put("location", location); sample.put("mode", "guidance"); sample.put("locationClass", "FINE");
        sample.put("capturedUptimeMs", 36_759_000L); sample.put("capturedAtMs", 100_020L);
        sample.put("capturedSdkUptimeMs", 1020L); sample.put("relativeTimestampClock", FixFreshness.RELATIVE_CLOCK);
        sample.put("lastFineFixAgeMs", 20L);
        NavigatorDriver.Observation o = NavigatorDriver.fromSample(sample, 36_759_010L, null);
        check(o.fixAgeMs == 30 && o.stamp == 1000, "recorded car offset uses same-domain age and relative identity");
        location.put("relativeTimestamp", 2000L); location.put("absoluteTimestamp", 90_000L);
        sample.put("capturedSdkUptimeMs", 2020L); sample.put("capturedAtMs", 90_020L);
        sample.put("capturedUptimeMs", 36_760_000L);
        o = NavigatorDriver.fromSample(sample, 36_760_010L, null);
        check(o.fixAgeMs == 30 && o.stamp == 2000, "recorded new monotonic fix survives absolute rollback");
        sample.remove("capturedSdkUptimeMs");
        check(NavigatorDriver.fromSample(sample, 36_760_010L, null).fixAgeMs == FixFreshness.UNKNOWN,
                "declared domain without current SDK clock rejects");
        sample.put("capturedSdkUptimeMs", Double.NaN);
        check(NavigatorDriver.fromSample(sample, 36_760_010L, null).fixAgeMs == FixFreshness.UNKNOWN, "NaN clock rejects");
        sample.put("capturedSdkUptimeMs", 2020L); sample.put("relativeTimestampClock", "elapsedRealtime");
        check(NavigatorDriver.fromSample(sample, 36_760_010L, null).fixAgeMs == FixFreshness.UNKNOWN, "wrong declared clock rejects");
        sample.put("relativeTimestampClock", FixFreshness.RELATIVE_CLOCK); sample.put("lastFineFixAgeMs", null);
        check(NavigatorDriver.fromSample(sample, 36_760_010L, null).fixAgeMs == FixFreshness.UNKNOWN,
                "invalid tracked age cannot be reconstructed as fresh from clocks alone");
        sample.remove("relativeTimestampClock"); sample.remove("capturedSdkUptimeMs");
        check(NavigatorDriver.fromSample(sample, 36_760_010L, null).fixAgeMs == FixFreshness.UNKNOWN,
                "legacy relative timestamp without explicit age never falls back to elapsed subtraction");
        sample.put("fixAgeMs", 20L);
        check(NavigatorDriver.fromSample(sample, 36_760_010L, null).fixAgeMs == 30, "explicit legacy age is aged for historical replay");
        sample.put("lastFineFixAgeMs", 36_757_978L);
        check(NavigatorDriver.fromSample(sample, 36_760_010L, null).fixAgeMs > 1000, "known build7 bad age remains rejected");
        sample.put("lastFineFixAgeMs", 20L); location.put("absoluteTimestamp", 90_021L);
        check(NavigatorDriver.fromSample(sample, 36_760_010L, null).fixAgeMs == FixFreshness.UNKNOWN, "absolute future veto also applies to legacy replay");
    }
}
