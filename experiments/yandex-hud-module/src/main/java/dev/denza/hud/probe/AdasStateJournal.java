package dev.denza.hud.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The drive's change journal: one row per transition of what ADAS says (DNP, ICC, ACC, takeover,
 * NOA mode, alarm reason/stage/notice), every selection and PathMatch answer, availability, and
 * every change of our own feed's state. A few hundred bytes per transition, so a whole drive fits
 * a small file that never rotates while the drive lasts: a file is a drive's when its last row is
 * younger than {@link #DRIVE_GAP_MS}; older ones move to {@code .1}–{@code .3} at the next start.
 * Past {@link #LIMIT} rows are counted, never rotated in. Plain Java; thread-safe.
 */
final class AdasStateJournal {
    static final long LIMIT = 8L << 20, DRIVE_GAP_MS = 30L * 60 * 1000;
    static final int DRIVES = 4;
    private static final long PILOT_AVAILABLE = 0x0001000D000D0001L, PATH_AVAILABLE = 0x0001002C002C0001L;
    private final File file;
    private final long limit;
    private final Map<String, Map<String, Object>> last = new LinkedHashMap<>();
    private long written, dropped;
    private boolean full;

    /** Opens this drive's journal at {@code wallNowMs}: a previous drive's file moves aside first. */
    AdasStateJournal(File file, long wallNowMs) throws IOException { this(file, wallNowMs, LIMIT); }
    AdasStateJournal(File file, long wallNowMs, long limit) throws IOException {
        this.file = file; this.limit = limit;
        if (file.isFile() && file.length() > 0 && wallNowMs - file.lastModified() > DRIVE_GAP_MS) DriveEvidenceLog.rotate(file, DRIVES);
        written = file.isFile() ? file.length() : 0;
    }

    /** The transition this ADAS packet makes, or null when it repeats what is known. */
    synchronized Map<String, Object> observe(String receiver, long topic, byte[] payload, long receivedAt, long wallMs) {
        Map<String, Object> values = new LinkedHashMap<>();
        String what;
        if ((topic >>> 48) == 1) {
            boolean pilot = topic == PILOT_AVAILABLE || topic == PILOT_AVAILABLE + 1, path = topic == PATH_AVAILABLE || topic == PATH_AVAILABLE + 1;
            if (!pilot && !path) return null;
            what = pilot ? "pilotAvailability" : "pathAvailability";
            values.put("available", topic == PILOT_AVAILABLE || topic == PATH_AVAILABLE);
        } else {
            AdasWire.Event e;
            try { e = AdasWire.decode(topic, payload); }
            catch (RuntimeException undecodable) {
                values.put("undecodable", undecodable.getClass().getSimpleName() + ": " + undecodable.getMessage());
                return row(receiver, "undecodable:" + String.format("0x%013X", topic), values, null, receivedAt, wallMs);
            }
            switch (e.kind) {
                case PILOT: what = "pilot"; values.put("dnp", e.dnp); values.put("icc", e.icc); values.put("acc", e.acc); values.put("takeover", e.takeover); break;
                case MODE: what = "noaMode"; values.put("noaMode", e.noaMode); break;
                case ALARM: what = "alarm"; values.put("reason", e.alarmReason); values.put("stage", e.alarmStage); values.put("notice", e.notice); break;
                case SELECTED: what = "selected"; values.put("pathId", e.pathIdPresent ? e.pathId : null); values.put("statusCode", e.statusCodePresent ? e.statusCode : null); break;
                case MATCH:
                    what = "pathMatch"; values.put("code", e.matchCode);
                    StringBuilder routes = new StringBuilder();
                    for (AdasWire.RouteInfo r : e.routeInfo) routes.append(routes.length() == 0 ? "" : ";").append(r.pathId).append(':').append(r.routeBeginIdx)
                            .append('-').append(r.routeEndIdx).append(':').append(r.statusCode).append(':').append(r.totalNoaDistance);
                    values.put("routes", routes.toString()); break;
                default: return null; // The IVI's own feed is not ADAS state.
            }
        }
        // Answers are events: each one counts. States count only when they change.
        boolean event = what.equals("selected") || what.equals("pathMatch");
        Map<String, Object> previous = last.get(what);
        if (!event && values.equals(previous)) return null;
        last.put(what, values);
        return row(receiver, what, values, event ? null : previous, receivedAt, wallMs);
    }
    /** Our own feed's state, when it changes. */
    synchronized Map<String, Object> feed(String state, String reason, String detail, long elapsedMs, long wallMs) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("state", state); values.put("reason", reason);
        Map<String, Object> previous = last.get("feed");
        if (values.equals(previous)) return null;
        last.put("feed", values);
        Map<String, Object> row = row("live", "feed", values, previous, elapsedMs, wallMs);
        row.put("detail", detail);
        return row;
    }
    /** The live arm's effective experiment variant, when the arm or its variant changes. */
    synchronized Map<String, Object> variant(String armId, Map<String, Object> effective, long elapsedMs, long wallMs) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("armId", armId); values.put("variant", effective);
        Map<String, Object> previous = last.get("variant");
        if (values.equals(previous)) return null;
        last.put("variant", values);
        return row("live", "variant", values, null, elapsedMs, wallMs);
    }
    /** Each NaviSt read or write the gate reader reports (an event: every one counts, once by its number). */
    synchronized Map<String, Object> naviSt(String armId, Map<String, Object> event, long elapsedMs, long wallMs) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("armId", armId); values.putAll(event);
        Map<String, Object> previous = last.get("naviSt");
        if (previous != null && java.util.Objects.equals(previous.get("n"), values.get("n"))
                && java.util.Objects.equals(previous.get("armId"), armId)) return null;
        last.put("naviSt", values);
        return row("reader", "naviSt", values, null, elapsedMs, wallMs);
    }
    synchronized Map<String, Object> instrumentNaviStatus(String armId, Map<String, Object> event, long elapsedMs, long wallMs) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("armId", armId); values.putAll(event);
        Map<String, Object> previous = last.get("instrumentNaviStatus");
        if (previous != null && java.util.Objects.equals(previous.get("n"), values.get("n"))
                && java.util.Objects.equals(previous.get("armId"), armId)) return null;
        last.put("instrumentNaviStatus", values);
        return row("reader", "instrumentNaviStatus", values, null, elapsedMs, wallMs);
    }
    private static Map<String, Object> row(String receiver, String what, Map<String, Object> values, Map<String, Object> previous, long at, long wall) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", "adas_state"); row.put("what", what); row.put("receiver", receiver);
        row.put("elapsedMs", at); row.put("capturedAtMs", wall); row.putAll(values);
        if (previous != null) row.put("was", previous);
        return row;
    }

    /** Appends one row; past the limit the row is counted and one marker says so. Recorder worker only. */
    synchronized void append(String line) throws IOException {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        // The marker's room is kept, so a full journal always says it is full.
        if (full || written + bytes.length + FULL.length > limit) {
            dropped++;
            if (!full) { full = true; write(FULL); }
            return;
        }
        write(bytes);
    }
    private static final byte[] FULL = "{\"kind\":\"adas_state_journal_full\"}\n".getBytes(StandardCharsets.UTF_8);
    private void write(byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file, true)) { out.write(bytes); }
        written += bytes.length;
    }
    synchronized long dropped() { return dropped; }
    synchronized long written() { return written; }
    static boolean journal(Map<String, Object> row) { return "adas_state".equals(row.get("kind")); }
}
