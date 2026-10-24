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
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded shell-UID reader for this vehicle's ten integer ADAS properties.
 * No vehicle setters, SOME/IP publisher, generic command input or network listener.
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
    /** Covers a Navigator restart (12 s on 2026-10-24); the reader only reads, the consumer re-checks every input. */
    static final long HEARTBEAT_MAX_AGE_MS = 60000, MAX_LIFETIME_MS = 4L * 60 * 60 * 1000;
    private static final int MAX_OUTPUT = 16384;
    private static final Pattern PARCEL = Pattern.compile("^\\s*Result:\\s*Parcel\\(([0-9a-fA-F]{8})\\s+([0-9a-fA-F]{8})\\s*(?:'[^'\\r\\n]*')?\\)\\s*$");
    private AdasGateReader() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !uuid(args[0]) || !uuid(args[1]) || !args[2].matches("[0-9]{1,8}"))
            throw new IllegalArgumentException("consumerSession readerSession lifetimeMs");
        if (Process.myUid() != 2000) throw new SecurityException("shell UID required");
        long lifetime = Long.parseLong(args[2]);
        if (lifetime < 1000 || lifetime > MAX_LIFETIME_MS) throw new IllegalArgumentException("lifetime bound");
        final String consumer = args[0], reader = args[1], boot = bootId();
        final long began = SystemClock.elapsedRealtime();
        long sequence = 0;
        System.out.println("ADAS integer reader started " + reader);
        try {
            while (SystemClock.elapsedRealtime() - began < lifetime) {
                long cycle = SystemClock.elapsedRealtime();
                if (!consumerAlive(consumer, boot)) break;
                if (stopRequested(consumer, reader, boot)) break;
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
                if (!consumerAlive(consumer, boot)) break;
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
                long remaining = 1000 - (SystemClock.elapsedRealtime() - cycle);
                if (remaining > 0) Thread.sleep(remaining);
            }
        } finally {
            System.out.println("ADAS integer reader stopped " + reader + " snapshots=" + sequence);
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
