package dev.denza.adaslink;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only shell-UID ADAS integer snapshot. This decodes hardware predicates, not
 * publication permission, remote SOME/IP availability or NOA activation.
 * Values come from device 1038, autoservice transaction 5; transaction 7 is float.
 */
public final class AdasGateSnapshot {
    public static final String SCHEMA = "denza-adas-gates-v1";
    public static final int HNP_CONFIG = 1126170651, UNP_CONFIG = 1126170658,
            E2E_CONFIG = 1126170664, HNP_SWITCH = 700448824, UNP_SWITCH = 700448826,
            E2E_SWITCH = 481296416, ASSIST_MODE = 535830576, NOA_UI_TYPE = 535838789,
            SLEEP_STATUS = 535842878, DISCONNECTION_STATUS = -1728052359;
    /** NoaState.r -> BydAutoNoaProxy.G/a0: device 1038 INT, support is exactly value 2. */
    public static final int TIP_ISLAND_SUPPORT = 230686742;
    public static final int VEHICLE_SPEED_FID = -1807745016, NOA_QUIT_FID = 535830556;
    public static final List<Integer> REQUIRED_FIDS = Collections.unmodifiableList(Arrays.asList(
            HNP_CONFIG, UNP_CONFIG, E2E_CONFIG, HNP_SWITCH, UNP_SWITCH, E2E_SWITCH,
            ASSIST_MODE, NOA_UI_TYPE, SLEEP_STATUS));
    public static final List<Integer> ALL_FIDS = Collections.unmodifiableList(Arrays.asList(
            HNP_CONFIG, UNP_CONFIG, E2E_CONFIG, HNP_SWITCH, UNP_SWITCH, E2E_SWITCH,
            ASSIST_MODE, NOA_UI_TYPE, SLEEP_STATUS, DISCONNECTION_STATUS, TIP_ISLAND_SUPPORT));
    public static final int MAX_JSON_CHARS = 16384;
    // Root: ten base keys and four optional keys. Each FID adds its map key and
    // up to four entry fields; the two scalar reads have eight fields each,
    // and the two optional reader side-channel summaries have five each.
    private static final int MAX_JSON_MEMBERS = 10 + 4 + ALL_FIDS.size() * (1 + 4) + 2 * 8 + 2 * 5;
    public static final long MAX_AGE_MS = 5000;
    /** The optional key of tools/adas_bridge/AdasGateReader.NaviSt's view. */
    public static final String NAVI_ST = "naviSt", INSTRUMENT_NAVI_STATUS = "instrumentNaviStatus";

    public static final class Snapshot {
        public final boolean valid;
        public final String reason, outageReason, readerSession;
        public final long sequence, startedAtElapsedMs, completedAtElapsedMs,
                startedAtUptimeMs, completedAtUptimeMs;
        public final Boolean dnpService, dnpSwitchRaw, startupDnpSwitchRaw,
                noSleep, stockNoSleep, downtime;
        public final Map<Integer, Integer> rawValues;
        /**
         * The reader's NaviSt side channel (the arm's {@code naviSt} switch): its state and recent
         * read/write events, each with its number; null when the reader does not drive it.
         */
        public final Map<String, Object> naviSt, instrumentNaviStatus, vehicleSpeed, noaQuit;

        private Snapshot(String error) {
            valid = false; reason = error; outageReason = "snapshot_invalid"; readerSession = null;
            sequence = startedAtElapsedMs = completedAtElapsedMs = startedAtUptimeMs = completedAtUptimeMs = -1;
            dnpService = dnpSwitchRaw = startupDnpSwitchRaw = noSleep = stockNoSleep = downtime = null;
            rawValues = Collections.emptyMap(); naviSt = instrumentNaviStatus = vehicleSpeed = noaQuit = null;
        }

        private Snapshot(Map<String, Object> root, Map<Integer, Integer> raw, String outage) {
            valid = true; reason = "ok"; outageReason = outage;
            readerSession = (String) root.get("readerSession"); sequence = integer(root, "sequence");
            startedAtElapsedMs = integer(root, "startedAtElapsedMs");
            completedAtElapsedMs = integer(root, "completedAtElapsedMs");
            startedAtUptimeMs = integer(root, "startedAtUptimeMs");
            completedAtUptimeMs = integer(root, "completedAtUptimeMs");
            rawValues = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
            naviSt = root.containsKey(NAVI_ST) ? Collections.unmodifiableMap(object(root.get(NAVI_ST))) : null;
            instrumentNaviStatus = root.containsKey(INSTRUMENT_NAVI_STATUS) ? Collections.unmodifiableMap(object(root.get(INSTRUMENT_NAVI_STATUS))) : null;
            vehicleSpeed = optionalRead(root, "vehicleSpeed");
            noaQuit = optionalRead(root, "noaQuit");
            int h = raw.get(HNP_CONFIG), u = raw.get(UNP_CONFIG), e = raw.get(E2E_CONFIG),
                    hs = raw.get(HNP_SWITCH), us = raw.get(UNP_SWITCH), es = raw.get(E2E_SWITCH),
                    mode = raw.get(ASSIST_MODE), ui = raw.get(NOA_UI_TYPE), sleep = raw.get(SLEEP_STATUS);
            // BydAutoNoaProxy z(), A(), w(), E(), H(), f0(). Startup and later
            // switch recomputation are distinct paths in the factory code.
            boolean hConfig = (ui != 2 ? mode == 3 : h == 3 || h == 4) || h == 0;
            boolean hSwitch = hs == 1 || mode == 3 || mode == 4;
            boolean citySwitch = (ui != 2 ? mode == 3 : mode == 4) || us == 1;
            dnpService = u == 0 || e == 0 || hConfig;
            startupDnpSwitchRaw = us == 1 || es == 1 || hSwitch;
            dnpSwitchRaw = (hSwitch && hConfig) || es == 1 || (citySwitch && u == 0);
            stockNoSleep = sleep != 1;
            // CarSetting disables sleep values 2/3. Preserve the factory raw
            // predicate above, but do not turn those transition states into ON.
            noSleep = sleep == 0 ? Boolean.TRUE : sleep == 1 ? Boolean.FALSE : null;
            downtime = raw.containsKey(DISCONNECTION_STATUS) ? raw.get(DISCONNECTION_STATUS) == 2 : null;
        }

        /** Hardware predicate only. Missing optional outage is reported, not fabricated OFF. */
        public boolean hardwareGateOpen() {
            return valid && Boolean.TRUE.equals(dnpService) && Boolean.TRUE.equals(dnpSwitchRaw)
                    && Boolean.TRUE.equals(noSleep) && !Boolean.TRUE.equals(downtime);
        }

        /** Actual float getter, in km/h. A held/failed reply never refreshes its age. */
        public Double vehicleSpeedKmh(long nowElapsedMs, long maxAgeMs) {
            if (!valid || vehicleSpeed == null || maxAgeMs <= 0) return null;
            try {
                Map<String,Object> r = vehicleSpeed;
                if (!"ok".equals(r.get("status")) || integer(r,"device") != 1013
                        || integer(r,"fid") != VEHICLE_SPEED_FID || integer(r,"transact") != 7
                        || !"km/h".equals(r.get("unit"))) return null;
                long start = integer(r,"startedAtElapsedMs"), end = integer(r,"completedAtElapsedMs");
                if (start < startedAtElapsedMs || end < start || end > completedAtElapsedMs
                        || nowElapsedMs < end || nowElapsedMs - start > maxAgeMs) return null;
                Object v = r.get("value");
                if (!(v instanceof Number)) return null;
                double value = ((Number)v).doubleValue();
                return Double.isFinite(value) ? value : null;
            } catch (IllegalArgumentException malformed) { return null; }
        }
        private static Map<String,Object> optionalRead(Map<String,Object> root, String key) {
            return root.get(key) instanceof Map ? Collections.unmodifiableMap(new LinkedHashMap<>(object(root.get(key)))) : null;
        }
    }

    /** One tracker per explicitly armed consumer process and reader session. Never auto-rebind. */
    public static final class Tracker {
        private final String boot, consumer;
        private final long ttl;
        private Snapshot last;
        private String seenReader, seenCanonical;
        private long seenSequence;
        private Snapshot seenResult;

        public Tracker(String expectedBootId, String expectedConsumerSession, long maxAgeMs) {
            if (maxAgeMs <= 0 || maxAgeMs > MAX_AGE_MS) throw new IllegalArgumentException("snapshot TTL");
            boot = expectedBootId; consumer = expectedConsumerSession; ttl = maxAgeMs;
        }

        public synchronized Snapshot read(String text, long nowElapsedMs) {
            boolean currentBatch = false;
            try {
                if (!uuid(boot)) return invalid("boot_identity_unknown");
                if (!uuid(consumer)) return invalid("consumer_identity_unknown");
                String canonical = compact(text);
                Object parsed = Json.parse(canonical);
                // Restricts the wire to the writer's canonical JSON, also rejecting
                // duplicate keys and integral-looking floats/overflowed numbers.
                if (!canonical.equals(Json.write(parsed))) return invalid("noncanonical_json");
                Map<String, Object> root = object(parsed);
                // The eleventh key exists only when the reader drives NaviSt, and is then an object.
                boolean side = root.containsKey(NAVI_ST), instrument = root.containsKey(INSTRUMENT_NAVI_STATUS);
                boolean speed = root.containsKey("vehicleSpeed"), quit = root.containsKey("noaQuit");
                if (root.size() != 10 + (side ? 1 : 0) + (instrument ? 1 : 0) + (speed ? 1 : 0) + (quit ? 1 : 0) || !SCHEMA.equals(root.get("schema"))) return invalid("schema");
                if (speed && !(root.get("vehicleSpeed") instanceof Map) || quit && !(root.get("noaQuit") instanceof Map)) return invalid("vehicle_read");
                if (side && !(root.get(NAVI_ST) instanceof Map)) return invalid("navi_st");
                if (instrument && !(root.get(INSTRUMENT_NAVI_STATUS) instanceof Map)) return invalid("instrument_navi_status");
                if (!boot.equals(root.get("bootId"))) return invalid("boot_mismatch");
                if (!consumer.equals(root.get("consumerSession"))) return invalid("consumer_mismatch");
                if (!(root.get("readerSession") instanceof String) || !uuid((String) root.get("readerSession")))
                    return invalid("reader_identity");
                long seq = integer(root, "sequence"), start = integer(root, "startedAtElapsedMs"),
                        end = integer(root, "completedAtElapsedMs"), upStart = integer(root, "startedAtUptimeMs"),
                        upEnd = integer(root, "completedAtUptimeMs");
                if (seq < 1 || start < 0 || end < start || upStart < 0 || upEnd < upStart
                        || upStart > start || upEnd > end || upEnd - upStart > end - start + 2)
                    return invalid("clock_order");
                String reader = (String) root.get("readerSession");
                boolean duplicate = false;
                if (seenReader != null) {
                    if (!seenReader.equals(reader)) return invalid("reader_changed");
                    if (seq < seenSequence) return invalid("sequence_rollback");
                    if (seq == seenSequence) {
                        if (!canonical.equals(seenCanonical)) return invalid("sequence_mutated");
                        duplicate = true;
                    }
                }
                if (!duplicate) {
                    seenReader = reader; seenSequence = seq; seenCanonical = canonical; seenResult = null;
                }
                currentBatch = true;
                // A failed source batch reserves its identity too. A future or
                // failed held file must not mature into a new valid observation.
                if (duplicate && seenResult != null && !seenResult.valid) return seenResult;
                if (nowElapsedMs < end) return remember(invalid("future_snapshot"));
                if (nowElapsedMs - start > ttl) return remember(invalid("expired_snapshot"));
                if (duplicate && seenResult != null) return seenResult;
                Map<String, Object> values = object(root.get("values"));
                if (values.size() < REQUIRED_FIDS.size() || values.size() > ALL_FIDS.size()) return remember(invalid("fid_count"));
                for (String key : values.keySet()) {
                    boolean known = false;
                    for (int fid : ALL_FIDS) if (Integer.toString(fid).equals(key)) known = true;
                    if (!known) return remember(invalid("fid_not_allowed"));
                }
                Map<Integer, Integer> raw = new LinkedHashMap<>();
                for (int fid : REQUIRED_FIDS) {
                    Integer value = readValue(values.get(Integer.toString(fid)));
                    if (value == null) return remember(invalid("required_read_unknown:" + fid));
                    if (!accepted(fid, value)) return remember(invalid("required_domain_unknown:" + fid));
                    raw.put(fid, value);
                }
                String outage = "missing";
                if (values.containsKey(Integer.toString(DISCONNECTION_STATUS))) {
                    Integer value = readValue(values.get(Integer.toString(DISCONNECTION_STATUS)));
                    if (value != null && value >= 0 && value <= 3) {
                        raw.put(DISCONNECTION_STATUS, value); outage = "ok";
                    } else outage = "read_or_domain_unknown";
                }
                // Optional for historical snapshots; the factory product path requires its presence.
                Integer tipIsland = readValue(values.get(Integer.toString(TIP_ISLAND_SUPPORT)));
                if (tipIsland != null && tipIsland >= 0) raw.put(TIP_ISLAND_SUPPORT, tipIsland);
                Snapshot candidate = new Snapshot(root, raw, outage);
                if (last != null) {
                    if (start < last.startedAtElapsedMs || end < last.completedAtElapsedMs
                            || upStart < last.startedAtUptimeMs || upEnd < last.completedAtUptimeMs)
                        return remember(invalid("capture_time_rollback"));
                }
                last = candidate;
                return remember(candidate);
            } catch (RuntimeException malformed) {
                Snapshot bad = invalid("malformed_snapshot");
                return currentBatch ? remember(bad) : bad;
            }
        }
        private Snapshot remember(Snapshot value) { seenResult = value; return value; }
    }

    /** Accepted policy values, not a claim that the vendor enums are exhaustive. */
    private static boolean accepted(int fid, int value) {
        if (value < 0) return false;
        if (fid == HNP_SWITCH || fid == UNP_SWITCH || fid == E2E_SWITCH) return value <= 1;
        if (fid == NOA_UI_TYPE) return value <= 2;
        if (fid == SLEEP_STATUS) return value <= 3;
        return value <= 4;
    }

    private static Integer readValue(Object entry) {
        try {
            Map<String, Object> value = object(entry);
            if (!"ok".equals(value.get("status")) || integer(value, "transact") != 5) return null;
            for (String key : value.keySet())
                if (!key.equals("status") && !key.equals("transact") && !key.equals("value") && !key.equals("error")) return null;
            long raw = integer(value, "value");
            return raw < 0 || raw > Integer.MAX_VALUE ? null : (int) raw;
        } catch (RuntimeException missing) { return null; }
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("object expected");
        return (Map<String, Object>) value;
    }
    private static long integer(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Long)) throw new IllegalArgumentException("integer expected");
        return (Long) value;
    }
    private static boolean uuid(String value) {
        if (value == null || value.length() != 36) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException bad) { return false; }
    }
    private static Snapshot invalid(String reason) { return new Snapshot(reason); }

    /** Bound nesting before using the shared fixture JSON reader. Arrays are not in this schema. */
    private static String compact(String text) {
        if (text == null || text.length() > MAX_JSON_CHARS) throw new IllegalArgumentException("JSON cap");
        StringBuilder out = new StringBuilder(text.length());
        boolean quoted = false, escaped = false;
        int depth = 0, objects = 0, members = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                out.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else {
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue;
                if (c == '[' || c == ']') throw new IllegalArgumentException("arrays forbidden");
                if (c == '{' && (++depth > 4 || ++objects > 24)) throw new IllegalArgumentException("object cap");
                if (c == '}' && --depth < 0) throw new IllegalArgumentException("unbalanced JSON");
                if (c == ':' && ++members > MAX_JSON_MEMBERS) throw new IllegalArgumentException("member cap");
                if (c == '"') quoted = true;
                out.append(c);
            }
        }
        if (quoted || depth != 0) throw new IllegalArgumentException("truncated JSON");
        return out.toString();
    }
    private AdasGateSnapshot() { }
}
