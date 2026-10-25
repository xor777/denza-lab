package dev.denza.adaslink;

import java.util.Map;

/** Hardware gate, read provenance and held-file regressions; no Android or vehicle access. */
public final class AdasGateSnapshotTest {
    private static final String BOOT = "56e1b912-893e-41bc-9e81-a6c38eb7d28d",
            CONSUMER = "364db69e-b53c-4969-a242-1fb6505af45e",
            READER = "58f6842c-6735-4ec6-9e49-b9b56a33eb22";
    private static int checks;

    public static void main(String[] args) {
        Map<String, Object> row = row();
        AdasGateSnapshot.Snapshot on = read(row, 1200);
        check(on.valid && on.hardwareGateOpen(), "explicit HNP config/switch plus awake");
        check(on.dnpService && on.dnpSwitchRaw && on.startupDnpSwitchRaw && on.stockNoSleep,
                "factory HNP raw predicates");
        check(on.downtime == null && "missing".equals(on.outageReason), "optional outage not invented false");
        check(on.rawValues.size() == 9, "all required raw values retained");
        try { on.rawValues.put(0, 1); throw new AssertionError("mutable raw values"); }
        catch (UnsupportedOperationException expected) { checks++; }
        try { AdasGateSnapshot.ALL_FIDS.add(0); throw new AssertionError("mutable whitelist"); }
        catch (UnsupportedOperationException expected) { checks++; }

        Map<String,Object> tip = row();
        set(tip, AdasGateSnapshot.TIP_ISLAND_SUPPORT, 2);
        check(read(tip,1200).rawValues.get(AdasGateSnapshot.TIP_ISLAND_SUPPORT)==2,
                "actual optional tip-island getter retained independently of UI type");
        entry(tip,AdasGateSnapshot.TIP_ISLAND_SUPPORT).put("status","error");
        check(!read(tip,1200).rawValues.containsKey(AdasGateSnapshot.TIP_ISLAND_SUPPORT),
                "failed optional getter stays absent, never inferred from other UI fields");

        set(row, AdasGateSnapshot.HNP_SWITCH, 0);
        check(!read(row, 1200).hardwareGateOpen(), "actual switch OFF blocks");
        row = row(); set(row, AdasGateSnapshot.HNP_CONFIG, 1);
        check(!read(row, 1200).dnpService && !read(row, 1200).hardwareGateOpen(), "unsupported config blocks");
        set(row, AdasGateSnapshot.E2E_CONFIG, 0);
        AdasGateSnapshot.Snapshot distinct = read(row, 1200);
        check(distinct.dnpService && distinct.startupDnpSwitchRaw && !distinct.dnpSwitchRaw
                && !distinct.hardwareGateOpen(), "startup H() differs from later f0(), cannot be conflated");
        set(row, AdasGateSnapshot.E2E_SWITCH, 1);
        check(read(row, 1200).hardwareGateOpen(), "explicit E2E switch enables recomputed aggregate");
        row = row(); set(row, AdasGateSnapshot.HNP_CONFIG, 1); set(row, AdasGateSnapshot.HNP_SWITCH, 0);
        set(row, AdasGateSnapshot.UNP_CONFIG, 0); set(row, AdasGateSnapshot.ASSIST_MODE, 4);
        check(read(row, 1200).hardwareGateOpen(), "UI2 mode4 enables configured city NOA");
        set(row, AdasGateSnapshot.ASSIST_MODE, 3);
        check(!read(row, 1200).hardwareGateOpen(), "UI2 city is mode4 and HNP still lacks config");
        set(row, AdasGateSnapshot.NOA_UI_TYPE, 0);
        check(read(row, 1200).hardwareGateOpen(), "older UI mode3 supplies HNP and city branches");
        row = row(); set(row, AdasGateSnapshot.SLEEP_STATUS, 1);
        check(read(row, 1200).valid && !read(row, 1200).stockNoSleep
                && !read(row, 1200).hardwareGateOpen(), "sleep1 blocks");
        for (int transition : new int[]{2, 3}) {
            set(row, AdasGateSnapshot.SLEEP_STATUS, transition);
            AdasGateSnapshot.Snapshot sleep = read(row, 1200);
            check(sleep.valid && sleep.stockNoSleep && sleep.noSleep == null && !sleep.hardwareGateOpen(),
                    "sleep transition stays unknown despite legacy raw !=1 predicate");
        }
        row = row(); set(row, AdasGateSnapshot.DISCONNECTION_STATUS, 2);
        check(read(row, 1200).downtime && !read(row, 1200).hardwareGateOpen(), "explicit outage2 blocks");
        set(row, AdasGateSnapshot.DISCONNECTION_STATUS, 0);
        check(read(row, 1200).hardwareGateOpen(), "outage0 raw retained");
        set(row, AdasGateSnapshot.DISCONNECTION_STATUS, 999);
        check(read(row, 1200).valid && read(row, 1200).downtime == null, "unknown optional outage recorded");
        entry(row, AdasGateSnapshot.DISCONNECTION_STATUS).put("status", "error");
        check(read(row, 1200).valid && read(row, 1200).downtime == null, "optional read error explicit unknown");

        check(read(row(), 6000).valid, "TTL5000 inclusive from oldest batch reading");
        check(!read(row(), 6001).valid, "TTL5001 expires");
        check(!read(row(), 1099).valid, "completion in future rejects");
        row = row(); row.put("completedAtElapsedMs", 999L);
        check(!read(row, 1200).valid, "elapsed reversed batch rejects");
        row = row(); row.put("completedAtUptimeMs", 799L);
        check(!read(row, 1200).valid, "uptime reversed batch rejects");
        row = row(); row.put("completedAtUptimeMs", 1101L);
        check(!read(row, 1200).valid, "uptime beyond elapsed rejects");
        row = row(); row.put("completedAtUptimeMs", 1000L);
        check(!read(row, 1200).valid, "uptime cannot advance much faster than elapsed");
        row = row(); row.put("completedAtElapsedMs", 6100L);
        check(!read(row, 6100).valid, "slow batch cannot freshen oldest readings");
        check(!new AdasGateSnapshot.Tracker(null, CONSUMER, 5000).read(Json.write(row()), 1200).valid,
                "unknown actual boot identity fails closed");
        row = row(); row.put("bootId", CONSUMER);
        check(!read(row, 1200).valid, "previous boot rejects even with plausible elapsed time");
        row = row(); row.put("consumerSession", READER);
        check(!read(row, 1200).valid, "another process nonce rejects");
        row = row(); row.put("readerSession", "not-a-uuid");
        check(!read(row, 1200).valid, "reader identity required");
        row = row(); entry(row, AdasGateSnapshot.HNP_CONFIG).put("transact", 7L);
        check(!read(row, 1200).valid, "float transaction7 cannot masquerade as integer read");
        row = row(); entry(row, AdasGateSnapshot.HNP_CONFIG).put("status", "error");
        check(!read(row, 1200).valid, "required read error has no default0");
        for (long unknown : new long[]{-1, -10013, Integer.MAX_VALUE, 5, 2147483648L}) {
            row = row(); set(row, AdasGateSnapshot.HNP_CONFIG, unknown);
            check(!read(row, 1200).valid, "negative/sentinel/out of accepted domain rejects");
        }
        row = row(); set(row, AdasGateSnapshot.HNP_SWITCH, 3);
        check(!read(row, 1200).valid, "unproved switch3 is unknown, not OFF");
        row = row(); entry(row, AdasGateSnapshot.HNP_CONFIG).put("value", 0.0);
        check(!read(row, 1200).valid, "float-shaped number rejects");
        row = row(); values(row).remove(Integer.toString(AdasGateSnapshot.HNP_CONFIG));
        check(!read(row, 1200).valid, "required missing field has no default0");
        row = row(); values(row).put("123", Json.object());
        check(!read(row, 1200).valid, "unapproved FID rejects");

        AdasGateSnapshot.Tracker tracker = tracker();
        String held = Json.write(row());
        AdasGateSnapshot.Snapshot first = tracker.read(held, 1200);
        check(tracker.read("  " + held + "\n", 5900) == first, "held unchanged file keeps same capture");
        check(!tracker.read(held, 6001).valid, "reading held file does not renew TTL");
        row = row(); set(row, AdasGateSnapshot.SLEEP_STATUS, 1);
        check("sequence_mutated".equals(tracker.read(Json.write(row), 1300).reason), "same sequence cannot mutate");
        row = row(); row.put("sequence", 2L); row.put("startedAtElapsedMs", 1200L);
        row.put("completedAtElapsedMs", 1300L); row.put("startedAtUptimeMs", 1000L);
        row.put("completedAtUptimeMs", 1100L);
        check(tracker.read(Json.write(row), 1400).valid, "new sequence advances capture");
        check("sequence_rollback".equals(tracker.read(held, 1500).reason), "older sequence never reanchors");
        row.put("readerSession", BOOT); row.put("sequence", 3L);
        check("reader_changed".equals(tracker.read(Json.write(row), 1400).reason), "reader cannot silently rebind");
        row.put("readerSession", READER); row.put("startedAtElapsedMs", 1199L);
        check("capture_time_rollback".equals(tracker.read(Json.write(row), 1400).reason), "time rollback rejects");

        AdasGateSnapshot.Tracker future = tracker();
        check(!future.read(held, 999).valid, "future batch initially rejects");
        check(!future.read(held, 1200).valid, "future held batch never matures into a fresh source read");
        Map<String, Object> newer = row(); newer.put("sequence", 2L);
        check(future.read(Json.write(newer), 1200).valid, "strictly newer valid source can recover");
        AdasGateSnapshot.Tracker failedBatch = tracker();
        check(failedBatch.read(held, 1200).valid, "initial good batch before failed read");
        Map<String, Object> failed = row(); failed.put("sequence", 2L);
        entry(failed, AdasGateSnapshot.HNP_SWITCH).put("status", "error");
        check(!failedBatch.read(Json.write(failed), 1300).valid, "new read failure closes gate");
        check("sequence_rollback".equals(failedBatch.read(held, 1400).reason), "failed newer batch reserves sequence");
        check("sequence_mutated".equals(failedBatch.read(Json.write(newer), 1400).reason),
                "failed batch cannot be repaired by mutating a held sequence");
        newer.put("sequence", 3L);
        check(failedBatch.read(Json.write(newer), 1400).valid, "new valid source sequence recovers after failure");

        check(!tracker().read("[]", 1200).valid, "root array rejected");
        check(!tracker().read("{", 1200).valid, "truncated input caught");
        check(!tracker().read("{\"x\":{\"x\":{\"x\":{\"x\":{}}}}}", 1200).valid, "nesting bounded before parse");
        check(!tracker().read(held.replace("\"sequence\":1", "\"sequence\":1,\"sequence\":1"), 1200).valid,
                "duplicate keys rejected");
        check(!tracker().read(held.replace("\"sequence\":1", "\"sequence\":01"), 1200).valid,
                "noncanonical integer rejected");
        check(!tracker().read(new String(new char[16385]), 1200).valid, "text bounded");
        try { new AdasGateSnapshot.Tracker(BOOT, CONSUMER, 5001); throw new AssertionError("TTL weakened"); }
        catch (IllegalArgumentException expected) { checks++; }

        // The reader's NaviSt side channel (arm variant naviSt) rides along as one optional object.
        AdasGateSnapshot.Snapshot plain = read(row(), 1200);
        check(plain.valid && plain.naviSt == null, "no side channel: no naviSt");
        Map<String, Object> side = row(), navi = Json.object();
        navi.put("state", "WRITTEN"); navi.put("original", 1L); navi.put("dirty", true);
        navi.put("reason", "guidance on a confirmed path"); navi.put("n", 4L);
        side.put(AdasGateSnapshot.NAVI_ST, navi);
        for (int fid : AdasGateSnapshot.ALL_FIDS) if (!values(side).containsKey(Integer.toString(fid))) set(side, fid, 0);
        AdasGateSnapshot.Snapshot withSide = read(side, 1200);
        check(withSide.valid && withSide.hardwareGateOpen() && "WRITTEN".equals(withSide.naviSt.get("state")),
                "the naviSt summary is carried beside all ten values and changes no gate: " + withSide.reason);
        side.put(AdasGateSnapshot.INSTRUMENT_NAVI_STATUS, navi);
        check(read(side, 1200).valid && read(side, 1200).instrumentNaviStatus != null, "both channels carried independently");
        side.remove(AdasGateSnapshot.NAVI_ST);
        check(read(side, 1200).valid && read(side, 1200).naviSt == null, "instrument alone leaves MPC absent");
        side.put(AdasGateSnapshot.INSTRUMENT_NAVI_STATUS, "IDLE");
        check(!read(side, 1200).valid, "instrument summary must be an object");
        side.remove(AdasGateSnapshot.INSTRUMENT_NAVI_STATUS);
        side.put(AdasGateSnapshot.NAVI_ST, "IDLE");
        check(!read(side, 1200).valid, "naviSt must be an object");
        side.remove(AdasGateSnapshot.NAVI_ST); side.put("other", Json.object());
        check(!read(side, 1200).valid, "no other eleventh key");
        System.out.println("AdasGateSnapshotTest: " + checks + " checks passed");
    }

    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static AdasGateSnapshot.Tracker tracker() { return new AdasGateSnapshot.Tracker(BOOT, CONSUMER, 5000); }
    private static AdasGateSnapshot.Snapshot read(Map<String, Object> row, long now) {
        return tracker().read(Json.write(row), now);
    }
    private static Map<String, Object> row() {
        Map<String, Object> row = Json.object();
        row.put("schema", AdasGateSnapshot.SCHEMA); row.put("bootId", BOOT); row.put("consumerSession", CONSUMER);
        row.put("readerSession", READER); row.put("sequence", 1L);
        row.put("startedAtElapsedMs", 1000L); row.put("completedAtElapsedMs", 1100L);
        row.put("startedAtUptimeMs", 800L); row.put("completedAtUptimeMs", 900L);
        row.put("values", Json.object());
        for (int fid : AdasGateSnapshot.REQUIRED_FIDS) set(row, fid, 0);
        set(row, AdasGateSnapshot.HNP_CONFIG, 3); set(row, AdasGateSnapshot.HNP_SWITCH, 1);
        set(row, AdasGateSnapshot.UNP_CONFIG, 1); set(row, AdasGateSnapshot.E2E_CONFIG, 1);
        set(row, AdasGateSnapshot.NOA_UI_TYPE, 2);
        return row;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> values(Map<String, Object> row) {
        return (Map<String, Object>) row.get("values");
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> entry(Map<String, Object> row, int fid) {
        return (Map<String, Object>) values(row).get(Integer.toString(fid));
    }
    private static void set(Map<String, Object> row, int fid, long raw) {
        Map<String, Object> value = Json.object();
        value.put("status", "ok"); value.put("transact", 5L); value.put("value", raw);
        values(row).put(Integer.toString(fid), value);
    }
}
