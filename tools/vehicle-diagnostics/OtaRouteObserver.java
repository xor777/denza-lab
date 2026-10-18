package dev.denza.diagnostics.probe;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Standalone app_process logger. No OTA, diagnostic, vehicle or network API. */
public final class OtaRouteObserver {
    static final String[] TAGS = {"OTA_GCY_UPGRADE_DiagnosePresenter", "OTA_GCY_UPGRADE_CanDiagnostic"};
    static final String[] KEYS = {"log.tag." + TAGS[0], "log.tag." + TAGS[1]};
    static final Path BASE = Paths.get("/data/local/tmp/denza-ota-route-observer");
    static final long CLEANUP_MILLIS = 10000;
    static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;
    static final Pattern NONCE = Pattern.compile("[0-9a-f]{32}");

    interface Clock { long millis(); }
    interface Properties { String get(String key) throws Exception; void set(String key, String value) throws Exception; }
    interface Child { boolean exited(); boolean stop() throws Exception; String streamFailure(); }
    interface Source { Child start() throws Exception; }
    interface StateSink { void save(State state) throws Exception; }
    interface StopSignal { boolean requested() throws Exception; }

    static final class State {
        final String nonce, status, reason;
        final int pid, durationSeconds;
        final long elapsedMs;
        final Map<String, String> originals;
        final boolean restoredVerified, childExited;
        State(String nonce, int pid, int seconds, String status, String reason, long elapsed,
              Map<String, String> originals, boolean restored, boolean childExited) {
            this.nonce = nonce; this.pid = pid; this.durationSeconds = seconds;
            this.status = status; this.reason = reason; this.elapsedMs = elapsed;
            this.originals = new LinkedHashMap<>(originals);
            this.restoredVerified = restored; this.childExited = childExited;
        }
        String json() {
            StringBuilder s = new StringBuilder("{\"schemaVersion\":1,\"nonce\":").append(quote(nonce));
            s.append(",\"pid\":").append(pid).append(",\"durationSeconds\":").append(durationSeconds);
            s.append(",\"status\":").append(quote(status)).append(",\"reason\":").append(quote(reason));
            s.append(",\"elapsedMs\":").append(elapsedMs).append(",\"originals\":{");
            boolean first = true;
            for (Map.Entry<String, String> e : originals.entrySet()) {
                if (!first) s.append(','); first = false;
                s.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
            }
            return s.append("},\"restoredVerified\":").append(restoredVerified)
                .append(",\"childExited\":").append(childExited).append("}\n").toString();
        }
    }

    /** One monitor owns every mutation; finish closes the gate before restoring. */
    static final class Session {
        final Properties props; final Clock clock; final Source source; final StateSink sink;
        final StopSignal stop; final String nonce; final int pid, seconds;
        final long startedAt, deadline;
        final Map<String, String> originals = new LinkedHashMap<>();
        Child child;
        boolean closed, changed;
        int exitCode = 2;
        State lastState;

        Session(Properties props, Clock clock, Source source, StateSink sink, StopSignal stop,
                String nonce, int pid, int seconds) {
            if (!NONCE.matcher(nonce).matches() || seconds < 1 || seconds > 350) throw new IllegalArgumentException();
            this.props = props; this.clock = clock; this.source = source; this.sink = sink;
            this.stop = stop; this.nonce = nonce; this.pid = pid; this.seconds = seconds;
            startedAt = clock.millis(); deadline = startedAt + seconds * 1000L;
        }
        State state(String status, String reason, boolean restored, boolean childExited) {
            return new State(nonce, pid, seconds, status, reason, Math.max(0, clock.millis() - startedAt),
                             originals, restored, childExited);
        }
        void save(String status, String reason, boolean restored, boolean childExited) throws Exception {
            State next = state(status, reason, restored, childExited);
            sink.save(next); lastState = next;
        }
        synchronized void start() {
            if (closed) return;
            try {
                for (String key : KEYS) {
                    String value = props.get(key);
                    if (value == null || value.length() > 91) throw new IllegalStateException();
                    originals.put(key, value);
                }
                // Originals reach durable storage before the first property write.
                save("STARTING", "starting", false, true);
                if (clock.millis() >= deadline || stop.requested()) { finish("stop_before_enable", false); return; }
                for (String key : KEYS) {
                    changed = true; // A setter may mutate and then throw.
                    props.set(key, "D");
                    if (!"D".equals(props.get(key))) throw new IllegalStateException();
                    if (clock.millis() >= deadline || stop.requested()) { finish("stop_during_enable", false); return; }
                }
                child = source.start();
                if (child == null || child.exited()) throw new IllegalStateException();
                if (clock.millis() >= deadline || stop.requested()) { finish("stop_during_start", false); return; }
                save("OBSERVING", "observing", false, false);
            } catch (Exception failure) { finish("setup_failed", true); }
        }
        synchronized void tick() {
            if (closed) return;
            try {
                if (clock.millis() >= deadline) { finish("deadline", false); return; }
                if (stop.requested()) { finish("stop_requested", false); return; }
                if (child != null) {
                    String failure = child.streamFailure();
                    if (failure != null) { finish(failure, true); return; }
                    if (child.exited()) finish("logcat_exited", true);
                }
            } catch (Exception failure) { finish("stop_io_failed", true); }
        }
        synchronized void finish(String reason, boolean failed) {
            if (closed) return;
            closed = true;
            boolean childExited = child == null;
            try { save("RESTORING", reason, false, childExited); } catch (Exception ignored) { }
            // Stop only the Process object created by this session, never a name/PID search.
            if (child != null) {
                try { childExited = child.stop(); } catch (Exception ignored) { childExited = false; }
            }
            boolean restored = true;
            if (changed) {
                for (String key : KEYS) {
                    String original = originals.get(key);
                    boolean verified = false;
                    for (int attempt = 0; attempt < 3 && !verified; attempt++) {
                        try { props.set(key, original); } catch (Exception ignored) { }
                        try { verified = original.equals(props.get(key)); } catch (Exception ignored) { }
                    }
                    restored &= verified;
                }
            }
            exitCode = restored && childExited ? (failed ? 2 : 0) : 3;
            try { save(exitCode == 3 ? "RESTORE_FAILED" : "STOPPED", reason, restored, childExited); }
            catch (Exception failure) { exitCode = 3; }
        }
        synchronized boolean isClosed() { return closed; }
        synchronized int result() { return exitCode; }
    }

    static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 32 || c > 126) result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else result.append(c);
        }
        return result.append('"').toString();
    }

    static final class AndroidProperties implements Properties {
        final Method get, set;
        AndroidProperties() throws Exception {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            get = cls.getMethod("get", String.class); set = cls.getMethod("set", String.class, String.class);
        }
        public String get(String key) throws Exception { return (String) get.invoke(null, key); }
        public void set(String key, String value) throws Exception { set.invoke(null, key, value); }
    }

    static final class AndroidClock implements Clock {
        final Method elapsed;
        AndroidClock() throws Exception { elapsed = Class.forName("android.os.SystemClock").getMethod("elapsedRealtime"); }
        public long millis() {
            try { return ((Number) elapsed.invoke(null)).longValue(); }
            catch (Exception e) { throw new IllegalStateException("clock_failed"); }
        }
    }

    static final class LogcatChild implements Child {
        final Process process;
        volatile String failure;
        LogcatChild(Process process, final OutputStream output) {
            this.process = process;
            Thread pump = new Thread(() -> {
                byte[] buffer = new byte[1024]; int total = 0;
                try {
                    InputStream input = process.getInputStream();
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_OUTPUT_BYTES) { failure = "output_limit"; return; }
                        output.write(buffer, 0, count); output.flush();
                    }
                    failure = "logcat_eof";
                } catch (Exception e) { failure = "stream_failed"; }
            }, "ota-route-stdout");
            pump.setDaemon(true); pump.start();
            Thread discard = new Thread(() -> {
                try { byte[] b = new byte[256]; while (process.getErrorStream().read(b) != -1) { } }
                catch (Exception ignored) { }
            }, "ota-route-stderr-discard");
            discard.setDaemon(true); discard.start();
        }
        public String streamFailure() { return failure; }
        public boolean exited() {
            try { process.exitValue(); return true; } catch (IllegalThreadStateException e) { return false; }
        }
        public boolean stop() throws Exception {
            process.destroy();
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(1000, TimeUnit.MILLISECONDS);
            }
            // Closing a pipe while another thread writes stdout can block; process death is sufficient.
            return exited();
        }
    }

    static Child launchLogcat() throws Exception {
        Process process = new ProcessBuilder("/system/bin/logcat", "-b", "main", "-v", "threadtime", "-T", "1",
            TAGS[0] + ":D", TAGS[1] + ":D", "*:S").start();
        try { return new LogcatChild(process, new FileOutputStream(FileDescriptor.out)); }
        catch (RuntimeException | Error failure) { process.destroyForcibly(); throw failure; }
    }

    static final class FilesState implements StateSink, StopSignal, AutoCloseable {
        final Path base; final String nonce;
        FileChannel channel; FileLock lock;
        FilesState(Path base, String nonce) throws Exception {
            this.base = base; this.nonce = nonce;
            if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(base, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            }
            if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(base)) throw new IllegalStateException();
            if (!Files.getPosixFilePermissions(base, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------"))) throw new IllegalStateException();
            Path lease = base.resolve("lease.lock");
            try {
                channel = FileChannel.open(lease, new java.util.HashSet<>(Arrays.asList(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                lock = channel.tryLock();
                if (lock == null) throw new IllegalStateException();
                Path state = base.resolve("state.json");
                if (Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
                    String previous = new String(readSmall(state, 8192), StandardCharsets.UTF_8);
                    if (!previous.contains("\"status\":\"STOPPED\"") || !previous.contains("\"restoredVerified\":true") ||
                        !previous.contains("\"childExited\":true") || previous.contains("\"nonce\":" + quote(nonce))) throw new IllegalStateException();
                }
            } catch (Exception e) { close(); throw e; }
        }
        public void save(State state) throws Exception {
            if (lock == null || !lock.isValid()) throw new IllegalStateException();
            Path temporary = base.resolve("state.tmp");
            // Fixed private directory + owned lease; never follow a leftover link.
            if (Files.isSymbolicLink(temporary)) throw new IllegalStateException();
            try (FileChannel file = FileChannel.open(temporary,
                    new java.util.HashSet<>(Arrays.asList(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                ByteBuffer buffer = ByteBuffer.wrap(state.json().getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Files.move(temporary, base.resolve("state.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        static byte[] readSmall(Path path, int bound) throws Exception {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) || Files.size(path) > bound) throw new IllegalStateException();
            try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer b = ByteBuffer.allocate(bound + 1);
                while (b.hasRemaining() && file.read(b) != -1) { }
                if (b.position() > bound) throw new IllegalStateException();
                return Arrays.copyOf(b.array(), b.position());
            }
        }
        public boolean requested() throws Exception {
            Path path = base.resolve("stop.request");
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
            String value = new String(readSmall(path, 33), StandardCharsets.US_ASCII);
            return value.equals(nonce) || value.equals(nonce + "\n");
        }
        public void close() throws java.io.IOException {
            try { if (lock != null && lock.isValid()) lock.release(); }
            finally { if (channel != null) channel.close(); }
        }
    }

    static int duration(String[] args) {
        if (args.length != 4 || !"--duration-seconds".equals(args[0]) || !"--nonce".equals(args[2]) ||
                !args[1].matches("[1-9][0-9]{0,2}") || !NONCE.matcher(args[3]).matches()) throw new IllegalArgumentException();
        int seconds = Integer.parseInt(args[1]);
        if (seconds > 350) throw new IllegalArgumentException();
        return seconds;
    }

    public static void main(String[] args) {
        int result = 2;
        try {
            int seconds = duration(args);
            Class<?> processClass = Class.forName("android.os.Process");
            if (((Number) processClass.getMethod("myUid").invoke(null)).intValue() != 2000) throw new IllegalStateException();
            int pid = ((Number) processClass.getMethod("myPid").invoke(null)).intValue();
            AndroidClock clock = new AndroidClock();
            AndroidProperties properties = new AndroidProperties();
            try (FilesState files = new FilesState(BASE, args[3])) {
                final Session session = new Session(properties, clock, OtaRouteObserver::launchLogcat,
                    files, files, args[3], pid, seconds);
                try {
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> session.finish("shutdown", true), "ota-route-shutdown"));
                    Thread deadline = new Thread(() -> {
                        while (!session.isClosed()) {
                            session.tick();
                            try { Thread.sleep(50); } catch (InterruptedException ignored) { }
                        }
                    }, "ota-route-deadline");
                    deadline.setDaemon(true); deadline.start();
                    // Independent of the session monitor: a wedged native call cannot extend the process forever.
                    Thread hardDeadline = new Thread(() -> {
                        while (clock.millis() < session.deadline + CLEANUP_MILLIS) {
                            try { Thread.sleep(50); } catch (InterruptedException ignored) { }
                        }
                        Runtime.getRuntime().halt(3); // State remains nonterminal if cleanup could not complete.
                    }, "ota-route-hard-deadline");
                    hardDeadline.setDaemon(true); hardDeadline.start();
                    session.start();
                    while (!session.isClosed()) Thread.sleep(50);
                } finally {
                    // Restoration must finish before releasing the FileLock, including interruption.
                    session.finish("main_finally", true);
                    result = session.result();
                }
            }
        } catch (Exception e) {
            // Do not emit exception text: state/stream contents never belong on stderr.
        }
        System.err.println(result == 0 ? "OTA_OBSERVER_STOPPED" : result == 3 ? "OTA_OBSERVER_RESTORE_FAILED" : "OTA_OBSERVER_REFUSED_OR_FAILED");
        System.exit(result);
    }
}
