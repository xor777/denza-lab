package dev.denza.diagnostics.probe;

import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** One stock SDK byte[] snapshot and one bounded, target-specific subscription. No vehicle setters. */
public final class ChassisBufferReadProbe {
    static final int QUEUE_CAPACITY = 128;
    static final int MAX_BUFFER_BYTES = 64;
    static final int MAX_RECORDS = 1024;
    static final long CLEANUP_NS = 2_000_000_000L;
    static final String LOCK_PATH = "/data/local/tmp/denza-dtc-read.lock";
    static final String PREFIX = "[ChassisBufferReadProbe] ";

    enum Target {
        WHEELS("wheels", 1049, 0x99000059, 12,
            "android.hardware.bydauto.special.BYDAutoSpecialDevice"),
        STEERING("steering", 1023, 0x99000198, 6,
            "android.hardware.bydauto.setting.BYDAutoSettingDevice");
        final String name, deviceClass;
        final int device, fid, bytes;
        Target(String name, int device, int fid, int bytes, String cls) {
            this.name = name; this.device = device; this.fid = fid; this.bytes = bytes;
            this.deviceClass = cls;
        }
        static Target named(String name) {
            for (Target target : values()) if (target.name.equals(name)) return target;
            throw new IllegalArgumentException("target must be wheels or steering");
        }
    }

    static final class Config {
        final Target target;
        final int seconds;
        Config(Target target, int seconds) { this.target = target; this.seconds = seconds; }
        static Config parse(String[] args) {
            if (args.length != 4 || !"--target".equals(args[0]) || !"--seconds".equals(args[2])
                    || !args[3].matches("[1-9][0-9]?")) throw new IllegalArgumentException("invalid arguments");
            int seconds = Integer.parseInt(args[3]);
            if (seconds < 3 || seconds > 20) throw new IllegalArgumentException("seconds must be 3..20");
            return new Config(Target.named(args[1]), seconds);
        }
    }

    interface Clock { long nanos(); }
    interface Receiver { void data(int fid, byte[] value, Integer sdkInt); void error(int code); }
    interface Backend {
        void open(Target target, Receiver receiver) throws Exception;
        Value snapshot() throws Exception;
        void register() throws Exception;
        void unregister() throws Exception;
        default void close() throws Exception { }
    }
    static final class Value {
        final byte[] bytes;
        final Integer sdkInt;
        Value(byte[] bytes, Integer sdkInt) { this.bytes = bytes; this.sdkInt = sdkInt; }
    }
    static final class Record {
        final long sequence, startNs, endNs;
        final String source;
        final Integer sdkInt;
        final int originalLength;
        final byte[] bytes;
        Record(long sequence, String source, long start, long end, Value value) {
            this.sequence = sequence; this.source = source; this.startNs = start; this.endNs = end;
            this.sdkInt = value.sdkInt;
            this.originalLength = value.bytes == null ? -1 : value.bytes.length;
            // Never clone an oversized callback. Its size and explicit rejection remain visible.
            this.bytes = value.bytes == null || originalLength > MAX_BUFFER_BYTES
                ? null : Arrays.copyOf(value.bytes, value.bytes.length);
        }
        String quality(Target target) {
            if (originalLength == -1) return "null_buffer";
            if (originalLength > MAX_BUFFER_BYTES) return "oversized_buffer";
            if (Arrays.equals(bytes, "MCU_OFFLINE".getBytes(StandardCharsets.US_ASCII))) return "mcu_offline";
            if (originalLength == 4) return "status_word_or_unqualified_4bytes";
            if (originalLength != target.bytes) return "unexpected_length";
            if (target == Target.WHEELS) {
                for (int i = 0; i < 8; i += 2) if ((bytes[i] & 0xf0) != 0) return "wheel_word_outside_producer_domain";
                for (int i = 8; i < 12; i++) if ((bytes[i] & 255) > 1) return "wheel_status_outside_producer_domain";
                for (int i = 8; i < 12; i++) if (bytes[i] != 0) return "wheel_status_gate_blocked";
                return "wheel_shape_and_status_gate_only";
            }
            return "steering_shape_only";
        }
        String json(Target target) {
            return "{\"kind\":\"BUFFER\",\"seq\":" + sequence + ",\"source\":\"" + source
                + "\",\"target\":\"" + target.name + "\",\"device\":" + target.device
                + ",\"fid\":\"" + fid(target.fid) + "\",\"readStartNs\":" + startNs
                + ",\"receivedNs\":" + endNs + ",\"length\":" + originalLength
                + ",\"sdkIntField\":" + (sdkInt == null ? "null" : sdkInt)
                + ",\"quality\":\"" + quality(target) + "\",\"raw\":\"" + hex(bytes)
                + "\",\"acquisitionTimeKnown\":false,\"physicalUnit\":null}";
        }
    }

    /** SDK calls serialize on apiLock; callbacks only take dataLock and never write stdout. */
    static final class Session implements Receiver {
        final Config config;
        final Clock clock;
        final Backend backend;
        final long startedNs, softDeadlineNs, hardDeadlineNs;
        final Object apiLock = new Object(), dataLock = new Object();
        final ArrayBlockingQueue<Record> queue;
        final AtomicBoolean closing = new AtomicBoolean();
        final AtomicReference<String> failure = new AtomicReference<>();
        final AtomicLong received = new AtomicLong(), dropped = new AtomicLong(), callbackErrors = new AtomicLong();
        final AtomicLong unexpected = new AtomicLong();
        volatile boolean ready, closed, registerAttempted, unregisterReturned, ownershipReleased;
        volatile String reason = "not_started";
        volatile int exitCode = 2;

        Session(Config config, Clock clock, Backend backend, int queueCapacity) {
            this.config = config; this.clock = clock; this.backend = backend;
            this.startedNs = clock.nanos();
            this.hardDeadlineNs = startedNs + config.seconds * 1_000_000_000L;
            this.softDeadlineNs = hardDeadlineNs - CLEANUP_NS;
            this.queue = new ArrayBlockingQueue<>(queueCapacity);
        }
        void start() {
            try {
                synchronized (apiLock) {
                    if (expiredOrClosing()) return;
                    backend.open(config.target, this);
                    if (expiredOrClosing()) return;
                    long before = clock.nanos();
                    Value initial = backend.snapshot();
                    capture("snapshot", before, clock.nanos(), initial);
                    if (expiredOrClosing()) return;
                    registerAttempted = true; // Registration may take effect and then throw.
                    backend.register();
                    if (expiredOrClosing()) return;
                    ready = true;
                }
            } catch (Throwable error) { fail("setup_" + errorName(error)); }
        }
        boolean expiredOrClosing() {
            if (failure.get() != null) { closing.set(true); reason = failure.get(); }
            if (clock.nanos() >= softDeadlineNs) { closing.set(true); reason = "deadline_during_setup"; }
            return closing.get();
        }
        public void data(int fid, byte[] bytes, Integer sdkInt) {
            if (closing.get()) return;
            if (fid != config.target.fid) { unexpected.incrementAndGet(); fail("unexpected_callback_id"); return; }
            long now = clock.nanos();
            capture("callback", now, now, new Value(bytes, sdkInt));
        }
        public void error(int code) {
            if (closing.get()) return;
            callbackErrors.incrementAndGet();
            fail("sdk_callback_error_" + code);
        }
        void capture(String source, long before, long after, Value value) {
            synchronized (dataLock) {
                if (closing.get()) return;
                long sequence = received.incrementAndGet();
                if (sequence > MAX_RECORDS) { dropped.incrementAndGet(); fail("record_limit"); return; }
                Record record = new Record(sequence, source, before, after,
                    value == null ? new Value(null, null) : value);
                if (!queue.offer(record)) { dropped.incrementAndGet(); fail("queue_overflow"); }
                if (record.originalLength > MAX_BUFFER_BYTES) fail("oversized_buffer");
            }
        }
        void fail(String why) { failure.compareAndSet(null, why); }
        void tick() {
            if (closed) return;
            String problem = failure.get();
            if (problem != null) finish(problem);
            else if (closing.get()) finish(reason);
            else if (clock.nanos() >= softDeadlineNs) finish("deadline");
        }
        void finish(String why) {
            closing.set(true); // Reject callbacks before waiting for an in-flight SDK call.
            synchronized (apiLock) {
                if (closed) return;
                reason = why;
                if (registerAttempted) {
                    try { backend.unregister(); unregisterReturned = true; }
                    catch (Throwable error) { failure.compareAndSet(null, "unregister_" + errorName(error)); }
                }
                try { backend.close(); ownershipReleased = true; }
                catch (Throwable error) { failure.compareAndSet(null, "ownership_release_" + errorName(error)); }
                synchronized (dataLock) {
                    exitCode = ready && failure.get() == null && (!registerAttempted || unregisterReturned) ? 0 : 2;
                    closed = true;
                }
            }
        }
        String doneJson() {
            return "{\"kind\":\"DONE\",\"reason\":\"" + reason + "\",\"exitCode\":" + exitCode
                + ",\"ready\":" + ready + ",\"received\":" + received.get() + ",\"dropped\":" + dropped.get()
                + ",\"callbackErrors\":" + callbackErrors.get() + ",\"unexpectedCallbacks\":" + unexpected.get()
                + ",\"queued\":" + queue.size() + ",\"registerAttempted\":" + registerAttempted
                + ",\"unregisterReturned\":" + unregisterReturned + ",\"ownershipReleased\":" + ownershipReleased + ",\"failure\":"
                + (failure.get() == null ? "null" : "\"" + failure.get() + "\"") + "}";
        }
    }

    /** Same process-held advisory lock as the diagnostic readers. The path is never removed. */
    static final class ReaderLease implements AutoCloseable {
        final FileChannel channel;
        final FileLock lock;
        private ReaderLease(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
        static ReaderLease acquire(Path path) throws Exception {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) throw new IllegalStateException("reader_busy");
                return new ReaderLease(channel, lock);
            } catch (Exception failure) { channel.close(); throw failure; }
        }
        public void close() throws java.io.IOException {
            try { if (lock.isValid()) lock.release(); } finally { channel.close(); }
        }
    }

    static final class StockSdk implements Backend {
        Object device, listener;
        ReaderLease lease;
        Class<?> listenerClass;
        Target target;
        StockSdk() { }
        public void open(Target target, Receiver receiver) throws Exception {
            this.target = target;
            lease = ReaderLease.acquire(Paths.get(LOCK_PATH));
            exemptHiddenApis();
            Class<?> looper = Class.forName("android.os.Looper");
            if (looper.getMethod("getMainLooper").invoke(null) == null) looper.getMethod("prepareMainLooper").invoke(null);
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object activity = activityThread.getMethod("systemMain").invoke(null);
            Object context = activityThread.getMethod("getSystemContext").invoke(activity);
            Class<?> contextClass = Class.forName("android.content.Context");
            Class<?> cls = Class.forName(target.deviceClass);
            device = cls.getMethod("getInstance", contextClass).invoke(null, context);
            if (device == null) throw new IllegalStateException("null_device");
            listenerClass = Class.forName("android.hardware.IBYDAutoListener");
            Class<?> valueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            listener = Proxy.newProxyInstance(ChassisBufferReadProbe.class.getClassLoader(), new Class<?>[]{listenerClass},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("onDataEventChanged".equals(name)) {
                        try {
                            if (args == null || args.length != 2 || !(args[0] instanceof Integer)
                                    || args[1] == null || !valueClass.isInstance(args[1])) {
                                receiver.error(-1); return null;
                            }
                            byte[] bytes = (byte[]) valueClass.getField("bufferDataValue").get(args[1]);
                            int sdkInt = valueClass.getField("intValue").getInt(args[1]);
                            receiver.data((Integer) args[0], bytes, sdkInt);
                        } catch (Throwable error) { receiver.error(-2); }
                        return null;
                    }
                    // AbsBYDAutoDevice emits both callback forms. Read only the typed DTO form once.
                    if ("onDataChanged".equals(name)) return null;
                    if ("onError".equals(name)) { receiver.error(args != null && args.length > 0 && args[0] instanceof Integer ? (Integer) args[0] : -3); return null; }
                    if ("toString".equals(name)) return "ChassisBufferReadProbeListener";
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    if ("equals".equals(name)) return args != null && args.length == 1 && proxy == args[0];
                    return null;
                });
        }
        public Value snapshot() throws Exception {
            if (target == Target.WHEELS) {
                return new Value((byte[]) device.getClass().getMethod("getWheelSpeed").invoke(device), null);
            }
            Object value = device.getClass().getMethod("get", int[].class, Class.class)
                .invoke(device, new int[]{target.fid}, byte[].class);
            if (value == null) return new Value(null, null);
            return new Value((byte[]) value.getClass().getField("bufferDataValue").get(value),
                value.getClass().getField("intValue").getInt(value));
        }
        public void register() throws Exception {
            device.getClass().getMethod("registerListener", listenerClass, int[].class)
                .invoke(device, listener, new int[]{target.fid});
        }
        public void unregister() throws Exception {
            device.getClass().getMethod("unregisterListener", listenerClass).invoke(device, listener);
        }
        public void close() throws Exception { if (lease != null) lease.close(); }
    }

    static String fid(int value) { return String.format(Locale.ROOT, "0x%08X", value); }
    static String hex(byte[] bytes) {
        if (bytes == null) return "";
        char[] digits = "0123456789abcdef".toCharArray(), result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) { int b = bytes[i] & 255; result[2*i] = digits[b >>> 4]; result[2*i+1] = digits[b & 15]; }
        return new String(result);
    }
    static String errorName(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        return error.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "_");
    }
    static void exemptHiddenApis() throws Exception {
        Class<?> vm = Class.forName("dalvik.system.VMRuntime");
        Object runtime = vm.getMethod("getRuntime").invoke(null);
        vm.getMethod("setHiddenApiExemptions", String[].class).invoke(runtime, (Object) new String[]{"Landroid/", "Ldalvik/system/VMRuntime;"});
    }
    static void emit(PrintStream out, String json) throws Exception {
        out.println(PREFIX + json); out.flush();
        if (out.checkError()) throw new IllegalStateException("output_failed");
    }
    static void writer(Session session, PrintStream out) {
        try {
            emit(out, "{\"kind\":\"START\",\"target\":\"" + session.config.target.name
                + "\",\"device\":" + session.config.target.device + ",\"fid\":\"" + fid(session.config.target.fid)
                + "\",\"hardLimitSeconds\":" + session.config.seconds + ",\"startedNs\":" + session.startedNs
                + ",\"softDeadlineNs\":" + session.softDeadlineNs + ",\"hardDeadlineNs\":" + session.hardDeadlineNs
                + ",\"queueCapacity\":" + QUEUE_CAPACITY + ",\"maxBufferBytes\":" + MAX_BUFFER_BYTES
                + ",\"recordLimit\":" + MAX_RECORDS + "}");
            boolean readyWritten = false;
            while (!session.closed || !session.queue.isEmpty()) {
                if (!readyWritten && session.ready) {
                    emit(out, "{\"kind\":\"READY\",\"target\":\"" + session.config.target.name + "\"}"); readyWritten = true;
                }
                Record record = session.queue.poll(25, TimeUnit.MILLISECONDS);
                if (record != null) emit(out, record.json(session.config.target));
            }
            if (session.registerAttempted) emit(out, "{\"kind\":\"" + (session.unregisterReturned ? "UNREGISTERED" : "UNREGISTER_FAILED")
                + "\",\"sdkVoidReturned\":" + session.unregisterReturned + "}");
            emit(out, session.doneJson());
            System.exit(session.exitCode);
        } catch (Throwable error) {
            session.fail("output_failed");
            // The watchdog owns unregister; a missing DONE is never a successful capture.
        }
    }
    static void commands(InputStream input, Session session) {
        Thread command = new Thread(() -> {
            byte[] word = new byte[5]; int used = 0;
            try {
                for (int value; !session.closing.get() && (value = input.read()) != -1;) {
                    if (value == '\n') {
                        if (used == 4 && word[0]=='S' && word[1]=='T' && word[2]=='O' && word[3]=='P') { session.reason="stdin_stop"; session.closing.set(true); return; }
                        session.fail("invalid_stdin_command"); return;
                    }
                    if (used >= 4 || value < 32 || value > 126) { session.fail("invalid_stdin_command"); return; }
                    word[used++] = (byte) value;
                }
                if (!session.closing.get()) { session.reason="stdin_eof"; session.closing.set(true); }
            } catch (Throwable error) { session.fail("stdin_failed"); }
        }, "chassis-buffer-stdin");
        command.setDaemon(true); command.start();
    }
    static Thread startHardDeadline(long remainingNs, Runnable halt) {
        // The independent Java monotonic clock cannot wait on an SDK or Android reflection call.
        final long deadline = System.nanoTime() + Math.max(0L, remainingNs);
        Thread hard = new Thread(() -> {
            while (System.nanoTime() < deadline) { try { Thread.sleep(5); } catch (InterruptedException ignored) { } }
            halt.run();
        }, "chassis-buffer-hard-deadline");
        hard.setDaemon(true); hard.start(); return hard;
    }
    public static void main(String[] args) {
        try {
            Config config = Config.parse(args); // Fail closed before loading Android/vehicle SDK classes.
            Class<?> process = Class.forName("android.os.Process");
            if (((Number) process.getMethod("myUid").invoke(null)).intValue() != 2000) throw new IllegalArgumentException("shell_uid_required");
            Method elapsed = Class.forName("android.os.SystemClock").getMethod("elapsedRealtimeNanos");
            Clock clock = () -> { try { return ((Number) elapsed.invoke(null)).longValue(); } catch (Exception e) { throw new IllegalStateException(e); } };
            Session session = new Session(config, clock, new StockSdk(), QUEUE_CAPACITY);
            // No shutdown hook waits for a wedged SDK call. Hard deadline also covers construction and stdout.
            startHardDeadline(session.hardDeadlineNs - clock.nanos(), () -> Runtime.getRuntime().halt(3));
            Thread log = new Thread(() -> writer(session, System.out), "chassis-buffer-writer");
            log.setDaemon(true); log.start();
            Thread stop = new Thread(() -> {
                while (!session.closed) { session.tick(); try { Thread.sleep(10); } catch (InterruptedException ignored) { } }
            }, "chassis-buffer-deadline");
            stop.setDaemon(true); stop.start();
            commands(System.in, session);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> session.finish("shutdown"), "chassis-buffer-cleanup"));
            session.start();
            session.tick();
            if (!session.closed) Class.forName("android.os.Looper").getMethod("loop").invoke(null);
            while (true) Thread.sleep(25); // Writer exits after terminal output; watchdog bounds every failure path.
        } catch (Throwable error) {
            System.err.println(PREFIX + "{\"kind\":\"REFUSED\",\"errorClass\":\"" + errorName(error) + "\"}");
            System.exit(2);
        }
    }
}
