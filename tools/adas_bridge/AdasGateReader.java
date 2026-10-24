package dev.denza.adasprobe;

import android.os.Process;
import android.os.SystemClock;
import dev.denza.adaslink.AdasGateSnapshot;
import dev.denza.adaslink.BootIdentity;
import dev.denza.adaslink.Json;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded shell-UID reader for this vehicle's ten integer ADAS properties.
 * No SOME/IP publisher, generic command input or network listener. Two fixed arm switches drive
 * independent stock side channels: MPC {@code naviSt} ({@link NaviSt}, device 1023) and
 * {@code instrumentNaviStatus} ({@link InstrumentNaviStatus}, device 1007). Each records its
 * original before writing and restores that original on every way out it controls.
 * The debuggable Navigator consumes an atomic private-file snapshot. The heartbeat
 * names the boot's consumer, which a restarted Navigator process keeps: a restart
 * shorter than {@link #HEARTBEAT_MAX_AGE_MS} keeps this reader for the resumed arm.
 * A longer silence stops it; an old process cannot rearm it.
 */
public final class AdasGateReader {
    static final String PACKAGE = "ru.yandex.yandexnavi";
    static final String HEARTBEAT = "files/denza-adas-consumer.json";
    static final String SNAPSHOT = "files/denza-adas-gates.json";
    static final String ISOLATION = "files/denza-adas-stock-isolation.json";
    static final String ARM = "files/denza-adas-live-arm.json", STATE = "files/denza-adas-live-state.json",
            NAVI_ST_LEDGER = "files/denza-adas-navi-st.json",
            INSTRUMENT_NAVI_STATUS_LEDGER = "files/denza-adas-instrument-navi-status.json";
    static final String NAVI_ST_ARG = "navi-st", INSTRUMENT_NAVI_STATUS_ARG = "instrument-navi-status", BOTH_ARG = "navi-st+instrument-navi-status";
    /** Covers a Navigator restart (12 s on 2026-10-24); the consumer re-checks every input. */
    static final long HEARTBEAT_MAX_AGE_MS = 60000, MAX_LIFETIME_MS = 4L * 60 * 60 * 1000;
    /** Navigator's live state is written every second; older than this, guidance is not established. */
    static final long STATE_MAX_AGE_MS = 5000;
    private static final int MAX_OUTPUT = 16384;
    private static final Pattern PARCEL = Pattern.compile("^\\s*Result:\\s*Parcel\\(([0-9a-fA-F]{8})\\s+([0-9a-fA-F]{8})\\s*(?:'[^'\\r\\n]*')?\\)\\s*$");
    private AdasGateReader() { }

    public static void main(String[] args) throws Exception {
        boolean side = args.length == 5;
        boolean naviSt = side && (NAVI_ST_ARG.equals(args[4]) || BOTH_ARG.equals(args[4]));
        boolean instrument = side && (INSTRUMENT_NAVI_STATUS_ARG.equals(args[4]) || BOTH_ARG.equals(args[4]));
        if (!(args.length == 3 || side && uuid(args[3]) && (naviSt || instrument))
                || !uuid(args[0]) || !uuid(args[1]) || !args[2].matches("[0-9]{1,8}"))
            throw new IllegalArgumentException("consumerSession readerSession lifetimeMs [armId navi-st|instrument-navi-status|navi-st+instrument-navi-status]");
        if (Process.myUid() != 2000) throw new SecurityException("shell UID required");
        long lifetime = Long.parseLong(args[2]);
        if (lifetime < 1000 || lifetime > MAX_LIFETIME_MS) throw new IllegalArgumentException("lifetime bound");
        final String consumer = args[0], reader = args[1], boot = bootId(), armId = side ? args[3] : null;
        final long began = SystemClock.elapsedRealtime();
        long sequence = 0;
        System.out.println("ADAS integer reader started " + reader + (naviSt ? " with NaviSt for arm " + armId : ""));
        final NaviSt channel = naviSt ? new NaviSt(AdasGateReader::command, SystemClock::elapsedRealtime,
                line -> System.out.println("NaviSt " + line), row -> writeLedger(row, boot, consumer, reader, armId, false)) : null;
        final NaviSt instrumentChannel = instrument ? new InstrumentNaviStatus(AdasGateReader::command, SystemClock::elapsedRealtime,
                line -> System.out.println("InstrumentNaviStatus " + line),
                row -> writeLedger(row, boot, consumer, reader, armId, true)) : null;
        String exit = "lifetime over";
        for (NaviSt owned : new NaviSt[] {channel, instrumentChannel}) if (owned != null) {
            // Read and record the original at arm time (the host writes the arm right after this reader starts).
            owned.start();
            // Every exit through the runtime (System.exit, an uncaught error) restores here too; whether
            // ART runs hooks on SIGTERM is not established, and SIGKILL never does: live_session.py restore.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> owned.close("reader exit (shutdown hook)")));
        }
        try {
            while (SystemClock.elapsedRealtime() - began < lifetime) {
                long cycle = SystemClock.elapsedRealtime();
                if (!consumerAlive(consumer, boot)) { exit = "consumer gone"; break; }
                if (stopRequested(consumer, reader, boot)) { exit = "stop requested"; break; }
                Map<String, Object> row = Json.object(), values = Json.object();
                row.put("schema", AdasGateSnapshot.SCHEMA);
                row.put("bootId", boot); row.put("consumerSession", consumer); row.put("readerSession", reader);
                row.put("sequence", ++sequence);
                row.put("startedAtElapsedMs", SystemClock.elapsedRealtime());
                row.put("startedAtUptimeMs", SystemClock.uptimeMillis());
                for (int fid : AdasGateSnapshot.ALL_FIDS) {
                    Map<String, Object> value = Json.object();
                    try {
                        Integer raw = parseInteger(command(null, "/system/bin/service", "call", "autoservice", "5",
                                "i32", "1038", "i32", Integer.toString(fid)));
                        if (raw == null || raw < 0) throw new IllegalArgumentException("not a valid integer result");
                        value.put("status", "ok"); value.put("transact", 5L); value.put("value", raw.longValue());
                    } catch (Exception failed) {
                        value.put("status", "error"); value.put("error", failed.getClass().getSimpleName());
                    }
                    values.put(Integer.toString(fid), value);
                    // Do not turn a slow/hung batch into apparently simultaneous current signals.
                    if (SystemClock.elapsedRealtime() - cycle > 2500) break;
                }
                row.put("values", values);
                row.put("completedAtUptimeMs", SystemClock.uptimeMillis());
                row.put("completedAtElapsedMs", SystemClock.elapsedRealtime());
                // The side channel's summary; Navigator journals the ledger's events when its number moves.
                if (channel != null) row.put("naviSt", channel.snapshot());
                if (instrumentChannel != null) row.put("instrumentNaviStatus", instrumentChannel.snapshot());
                if (!consumerAlive(consumer, boot)) { exit = "consumer gone"; break; }
                byte[] body = (Json.write(row) + "\n").getBytes(StandardCharsets.UTF_8);
                command(body, "/system/bin/run-as", PACKAGE, "/system/bin/sh", "-c",
                        "umask 077; cat > " + SNAPSHOT + ".tmp && mv " + SNAPSHOT + ".tmp " + SNAPSHOT);
                Map<String,Object> isolation = Json.object();
                isolation.put("schema", "denza-adas-stock-isolation-v1");
                isolation.put("bootId", boot); isolation.put("consumerSession", consumer);
                isolation.put("readerSession", reader);
                isolation.put("startedAtElapsedMs", SystemClock.elapsedRealtime());
                boolean absent = false;
                try { absent = stockAbsent(command(null, "/system/bin/ps", "-A", "-w", "-o", "NAME")); }
                catch (Exception failed) { isolation.put("error", failed.getClass().getSimpleName()); }
                isolation.put("stockAbsent", absent);
                isolation.put("completedAtElapsedMs", SystemClock.elapsedRealtime());
                command((Json.write(isolation)+"\n").getBytes(StandardCharsets.UTF_8),
                        "/system/bin/run-as", PACKAGE, "/system/bin/sh", "-c",
                        "umask 077; cat > " + ISOLATION + ".tmp && mv " + ISOLATION + ".tmp " + ISOLATION);
                // After the snapshot is handed over: the side channel follows the bridge's guidance.
                if (channel != null) channel.step(notGuiding(privateJson(ARM), privateJson(STATE), boot, consumer, reader, armId,
                        SystemClock.elapsedRealtime()));
                if (instrumentChannel != null) instrumentChannel.step(notGuiding(privateJson(ARM), privateJson(STATE), boot, consumer, reader, armId,
                        SystemClock.elapsedRealtime(), "instrumentNaviStatus"));
                long remaining = 1000 - (SystemClock.elapsedRealtime() - cycle);
                if (remaining > 0) Thread.sleep(remaining);
            }
        } catch (Exception | Error failed) {
            exit = "error " + failed.getClass().getSimpleName();
            throw failed;
        } finally {
            if (channel != null) channel.close(exit);
            if (instrumentChannel != null) instrumentChannel.close(exit);
            System.out.println("ADAS integer reader stopped " + reader + " snapshots=" + sequence + " (" + exit + ")");
        }
    }

    /** Navigator's private JSON file, or null when it is absent, unreadable or not JSON. */
    private static Object privateJson(String name) {
        try { return Json.parse(command(null, "/system/bin/run-as", PACKAGE, "cat", name)); }
        catch (Exception absent) { return null; }
    }
    /** The side channel's durable record in Navigator's files, for the host's restore. A failed write only logs. */
    private static void writeLedger(Map<String, Object> ledger, String boot, String consumer, String reader, String armId, boolean instrument) {
        String path = instrument ? INSTRUMENT_NAVI_STATUS_LEDGER : NAVI_ST_LEDGER;
        Map<String, Object> row = Json.object();
        row.put("schema", instrument ? InstrumentNaviStatus.SCHEMA : NaviSt.SCHEMA); row.put("bootId", boot); row.put("consumerSession", consumer);
        row.put("readerSession", reader); row.put("armId", armId);
        row.putAll(ledger);
        try {
            command((Json.write(row) + "\n").getBytes(StandardCharsets.UTF_8), "/system/bin/run-as", PACKAGE, "/system/bin/sh", "-c",
                    "umask 077; cat > " + path + ".tmp && mv " + path + ".tmp " + path);
        } catch (Exception failed) { System.out.println((instrument ? "InstrumentNaviStatus" : "NaviSt") + " ledger write failed: " + failed.getClass().getSimpleName()); }
    }

    /**
     * Null while Navigator guides on a confirmed path under this reader's unexpired naviSt arm (the
     * stock writes NaviSt 2 at its guidance start); otherwise why not. Pure: the files as parsed, the clock.
     */
    @SuppressWarnings("unchecked")
    static String notGuiding(Object armFile, Object stateFile, String boot, String consumer, String reader, String armId, long now) {
        return notGuiding(armFile, stateFile, boot, consumer, reader, armId, now, "naviSt");
    }
    @SuppressWarnings("unchecked")
    static String notGuiding(Object armFile, Object stateFile, String boot, String consumer, String reader, String armId, long now, String key) {
        if (!(armFile instanceof Map)) return "no arm";
        Map<String, Object> arm = (Map<String, Object>) armFile;
        if (!"denza-adas-live-arm-v1".equals(arm.get("schema")) || !boot.equals(arm.get("bootId"))
                || !consumer.equals(arm.get("consumerSession")) || !reader.equals(arm.get("readerSession"))
                || !armId.equals(arm.get("armId"))) return "another arm";
        Object expires = arm.get("expiresAtElapsedMs");
        if (!(expires instanceof Long) || now >= (Long) expires) return "arm expired";
        Object variant = arm.get("variant");
        if (!(variant instanceof Map) || !Boolean.TRUE.equals(((Map<String, Object>) variant).get(key))) return "arm without " + key;
        if (!(stateFile instanceof Map)) return "no live state";
        Map<String, Object> state = (Map<String, Object>) stateFile;
        if (!"denza-adas-live-state-v1".equals(state.get("schema")) || !boot.equals(state.get("bootId"))
                || !consumer.equals(state.get("consumerSession")) || !armId.equals(state.get("armId"))) return "live state of another session";
        Object stamp = state.get("elapsedMs");
        if (!(stamp instanceof Long) || now < (Long) stamp || now - (Long) stamp > STATE_MAX_AGE_MS) return "live state stale";
        if (!"LIVE".equals(state.get("state"))) return "session " + state.get("state");
        if (!Boolean.TRUE.equals(state.get("guidanceActive"))) return "guidance ended";
        Object path = state.get("confirmedPathId");
        if (!(path instanceof Long) || (Long) path < 0) return "path not confirmed";
        return null;
    }

    /**
     * The stock's SETTING_MPC_IFC_NAVI_ST_SET (device 1023, FID 1285554251 = 0x4CA0004B) under the arm's
     * {@code naviSt} switch, with the command strings of tools/adas_bridge/parked_session.py. The stock
     * writes 2 at guidance start (NaviService.setNaviTypeAndInit) and 0 at its stop; here the original is
     * read once at arm time, 2 is written when Navigator guides on a confirmed path, and the original is
     * written back when that ends, the arm ends or expires, or the reader exits. Every write is read back.
     * A failed or unexpected read means no write; any failure restores what was written and disables
     * the channel for the rest of the reader's life. Every read and write is an event with its number
     * in the private ledger ({@link #NAVI_ST_LEDGER}, the last {@link #KEPT}); the gates file carries
     * the flat summary (state, original, the last number), which the hardened gates parser admits, and
     * Navigator journals the ledger's new events when that number moves.
     */
    static class NaviSt {
        static final String SCHEMA = "denza-adas-navi-st-v1";
        static final int DEVICE = 1023, FID = 1285554251, GUIDANCE = 2, KEPT = 48;
        /** The values the stock writes: 0 stopped, 1 cruise, 2 GPS navigation (parked_session.NAVI_ST_VALUES). */
        static final int MAX_VALUE = 2;
        interface Shell { String run(byte[] input, String... args) throws Exception; }
        interface Ledger { void write(Map<String, Object> ledger); }
        enum State { STARTING, IDLE, WRITTEN, DISABLED, CLOSED }

        private final Shell shell;
        private final boolean instrument;
        private final LongSupplier clock;
        private final Consumer<String> log;
        private final Ledger ledger;
        private final List<Map<String, Object>> events = new ArrayList<>();
        private State state = State.STARTING;
        private Integer original;
        /** A write of 2 may be on the car and no verified restore followed it. */
        private boolean dirty;
        private long n;
        private String reason = "starting";

        NaviSt(Shell shell, LongSupplier clock, Consumer<String> log, Ledger ledger) {
            this(shell, clock, log, ledger, false);
        }
        private NaviSt(Shell shell, LongSupplier clock, Consumer<String> log, Ledger ledger, boolean instrument) {
            this.shell = shell; this.clock = clock; this.log = log; this.ledger = ledger; this.instrument = instrument;
        }

        static String[] readCommand() {
            return new String[] {"/system/bin/service", "call", "autoservice", "5", "i32", Integer.toString(DEVICE), "i32", Integer.toString(FID)};
        }
        static String[] writeCommand(int value) {
            return new String[] {"/system/bin/service", "call", "autoservice", "6", "i32", Integer.toString(DEVICE), "i32", Integer.toString(FID),
                    "i32", Integer.toString(value), "null"};
        }

        private String[] channelReadCommand() {
            if (!instrument) return readCommand();
            // IVI HAL registers this exact INT event in get and set maps, sharing the same cache.
            return new String[] {"/system/bin/service", "call", "autoservice", "5", "i32", "1007", "i32", "1138753594"};
        }
        private String[] channelWriteCommand(int value) {
            if (!instrument) return writeCommand(value);
            return new String[] {"/system/bin/service", "call", "autoservice", "6", "i32", "1007", "i32", "1138753594", "i32", Integer.toString(value), "null"};
        }

        /** Arm time: the original value. Without it the channel never writes. */
        synchronized void start() {
            if (state != State.STARTING) return;
            Integer value = read("original");
            if (value == null) { disable("original read failed: no write"); }
            else { original = value; state = State.IDLE; reason = "original " + value; }
            publish();
        }

        /** One reader cycle: {@code notGuiding} null writes 2 (once per guidance), non-null restores. */
        synchronized void step(String notGuiding) {
            if (state == State.IDLE && notGuiding == null) {
                Integer before = read("before write");
                if (before == null || !before.equals(original)) {
                    disable(before == null ? "read before write failed: no write" : "value changed to " + before + " since arm time: no write");
                } else {
                    dirty = true;
                    boolean sent = write(GUIDANCE, "guidance start");
                    Integer back = read("readback");
                    if (sent && back != null && back == GUIDANCE) { state = State.WRITTEN; reason = "guidance on a confirmed path"; }
                    else { restore("write not verified"); disable("write not verified"); }
                }
                publish();
            } else if (state == State.WRITTEN && notGuiding != null) {
                state = restore(notGuiding) ? State.IDLE : State.DISABLED;
                reason = state == State.IDLE ? "restored: " + notGuiding : "restore not verified: " + notGuiding;
                publish();
            }
        }

        /** Every way out: restores what may be written, then nothing more. Idempotent. */
        synchronized void close(String why) {
            if (state == State.CLOSED) return;
            if (dirty && !restore(why)) restore(why + " (retry)");
            state = State.CLOSED; reason = (dirty ? "closed, restore NOT verified: " : "closed: ") + why;
            log.accept(reason);
            publish();
        }

        /** The gates file's flat summary: state, original, dirty, reason and the last event number. */
        synchronized Map<String, Object> snapshot() {
            Map<String, Object> v = Json.object();
            v.put("state", state.name()); v.put("original", original == null ? null : (Object) (long) original);
            v.put("dirty", dirty); v.put("reason", reason); v.put("n", n);
            return v;
        }
        synchronized State state() { return state; }
        synchronized boolean dirty() { return dirty; }

        private boolean restore(String why) {
            if (original == null) return !dirty;
            boolean sent = write(original, "restore: " + why);
            Integer back = read("restore readback");
            if (sent && back != null && back.equals(original)) { dirty = false; return true; }
            return false;
        }
        private void disable(String why) { state = State.DISABLED; reason = why; log.accept("disabled: " + why); }

        private Integer read(String purpose) {
            Map<String, Object> e = event("read", purpose);
            Integer value = null;
            try {
                value = parseInteger(shell.run(null, channelReadCommand()));
                if (value == null && !e.containsKey("error")) e.put("error", "reply not a status-0 integer");
                else if (value < 0 || value > (instrument ? InstrumentNaviStatus.MAX_VALUE : MAX_VALUE)) { e.put("error", "unexpected value"); e.put("raw", (long) value); value = null; }
            } catch (Exception failed) { e.put("error", failed.getClass().getSimpleName()); }
            e.put("ok", value != null);
            if (value != null) e.put("value", (long) value);
            log.accept(Json.write(e));
            return value;
        }
        private boolean write(int value, String purpose) {
            Map<String, Object> e = event("write", purpose);
            e.put("value", (long) value);
            boolean ok = false;
            try {
                ok = parseInteger(shell.run(null, channelWriteCommand(value))) != null;
                if (!ok) e.put("error", "reply not a status-0 parcel");
            } catch (Exception failed) { e.put("error", failed.getClass().getSimpleName()); }
            e.put("ok", ok);
            log.accept(Json.write(e));
            return ok;
        }
        private Map<String, Object> event(String op, String purpose) {
            Map<String, Object> e = Json.object();
            e.put("n", ++n); e.put("op", op); e.put("purpose", purpose); e.put("elapsedMs", clock.getAsLong());
            e.put("device", (long) (instrument ? InstrumentNaviStatus.DEVICE : DEVICE));
            e.put("fid", (long) (instrument ? InstrumentNaviStatus.FID : FID));
            events.add(e);
            if (events.size() > KEPT) events.remove(0);
            return e;
        }
        private void publish() {
            Map<String, Object> v = snapshot();
            v.put("events", new ArrayList<Object>(events));
            v.put("updatedAtElapsedMs", clock.getAsLong());
            try { ledger.write(v); } catch (RuntimeException failed) { log.accept("ledger: " + failed.getClass().getSimpleName()); }
        }
    }

    /**
     * Independent instrument channel: PlatformApiImpl.setNaviState -> instrument device 1007,
     * event 0x43E0003A. Stock guidance writes 2; stop writes 1. The framework admits 0..4.
     * IVI HAL's parameter factory registers this event in both get and set maps with one INT
     * parameter/cache (initial 65535, rejected here). Shell UID is admitted by native permissions.
     * Every original/readback must be 0..4; this proves the local cache, not remote ECU acceptance.
     */
    static final class InstrumentNaviStatus extends NaviSt {
        static final String SCHEMA = "denza-adas-instrument-navi-status-v1";
        static final int DEVICE = 1007, FID = 1138753594, MAX_VALUE = 4;
        InstrumentNaviStatus(Shell shell, LongSupplier clock, Consumer<String> log, Ledger ledger) {
            super(shell, clock, log, ledger, true);
        }
    }

    interface HeartbeatReader { String read() throws Exception; }
    private static boolean consumerAlive(String consumer, String boot) {
        return consumerAlive(consumer, boot,
                () -> command(null, "/system/bin/run-as", PACKAGE, "cat", HEARTBEAT), SystemClock::elapsedRealtime);
    }
    @SuppressWarnings("unchecked")
    static boolean consumerAlive(String consumer, String boot, HeartbeatReader reader, LongSupplier clock) {
        try {
            Object parsed = Json.parse(reader.read());
            // The file can be refreshed while run-as/cat is in flight. Compare
            // with the clock AFTER reading, not a pre-read value that makes a
            // genuinely fresh heartbeat look as if it came from the future.
            long now = clock.getAsLong();
            if (!(parsed instanceof Map)) return false;
            Map<String, Object> value = (Map<String, Object>) parsed;
            Object stamp = value.get("elapsedMs");
            if (!"denza-adas-consumer-v1".equals(value.get("schema")) || !consumer.equals(value.get("consumerSession"))
                    || !boot.equals(value.get("bootId")) || !(stamp instanceof Long)) return false;
            long captured = (Long) stamp;
            return captured >= 0 && now >= captured && now - captured <= HEARTBEAT_MAX_AGE_MS;
        } catch (Exception failed) { return false; }
    }

    static Integer parseInteger(String text) {
        if (text == null || text.length() > MAX_OUTPUT) return null;
        Matcher found = PARCEL.matcher(text);
        if (!found.matches() || !found.group(1).equals("00000000")) return null;
        return (int) Long.parseLong(found.group(2), 16);
    }
    static boolean stockAbsent(String text) {
        if (text == null || text.length() > MAX_OUTPUT) return false;
        String[] lines = text.trim().split("\\r?\\n");
        if (lines.length < 2 || !"NAME".equals(lines[0].trim())) return false;
        boolean fullConsumer=false;
        for (int i=1;i<lines.length;i++) {
            String name=lines[i].trim();
            if(PACKAGE.equals(name))fullConsumer=true;
            if (name.isEmpty() || name.contains(" ") || name.contains("\t")
                    || name.equals("com.byd.launchermap") || name.startsWith("com.byd.launchermap:")) return false;
        }
        // A full live Navigator name proves this column is not a truncated comm field.
        return fullConsumer;
    }
    @SuppressWarnings("unchecked")
    private static boolean stopRequested(String consumer, String reader, String boot) {
        try {
            Object p=Json.parse(command(null,"/system/bin/run-as",PACKAGE,"cat","files/denza-adas-reader-stop.json"));
            if (!(p instanceof Map)) return false;
            Map<String,Object> m=(Map<String,Object>)p;
            return "denza-adas-reader-stop-v1".equals(m.get("schema")) && consumer.equals(m.get("consumerSession"))
                    && reader.equals(m.get("readerSession")) && boot.equals(m.get("bootId"));
        } catch (Exception absent) { return false; }
    }

    private static String bootId() throws Exception {
        return BootIdentity.fromCount(command(null,"/system/bin/settings","get","global","boot_count").trim());
    }
    private static boolean uuid(String value) {
        if (value == null || value.length() != 36) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException bad) { return false; }
    }

    /** Only the fixed callers above supply arguments. A blocked child is killed before reading. */
    private static String command(byte[] input, String... args) throws Exception {
        java.lang.Process child = new ProcessBuilder(args).redirectErrorStream(true).start();
        try {
            try (OutputStream stdin = child.getOutputStream()) { if (input != null) stdin.write(input); }
            // Android's timed Process.waitFor polls at coarse intervals. Ten
            // short getters then cost about a second before file handoff, and
            // the next snapshot can miss the unchanged 2.5 s freshness lease.
            // Bound the same child lifetime with a short exit check instead.
            long deadline = SystemClock.elapsedRealtime() + 1000;
            while (child.isAlive()) {
                if (SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("read timeout");
                Thread.sleep(5);
            }
            byte[] output = read(child.getInputStream(), MAX_OUTPUT);
            if (child.exitValue() != 0) throw new IllegalStateException("read failed");
            return new String(output, StandardCharsets.UTF_8);
        } finally { child.destroy(); if (child.isAlive()) child.destroyForcibly(); }
    }
    private static byte[] read(InputStream in, int maximum) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024]; int count;
        while ((count = in.read(buffer)) != -1) {
            if (count > maximum - out.size()) throw new IllegalArgumentException("read cap");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
}
