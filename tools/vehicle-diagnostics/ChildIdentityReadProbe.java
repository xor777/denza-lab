package dev.denza.diagnostics.probe;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import dev.denza.apps.platform.shell.ShellProxyBootstrap;
import java.io.RandomAccessFile;
import java.lang.reflect.Proxy;
import java.nio.channels.FileLock;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated shell probe: the eight retained child software-code DIDs, once each.
 * No arbitrary input, session change, retry, DTC request, VIN or product dependency.
 * The caller must keep stdin open; EOF cancels. See the offline build/run companion.
 */
public final class ChildIdentityReadProbe {
    static final int TX = 0xAA00010A, RX = 0x9900010A;
    static final int REQUEST_MS = 4_000, SILENCE_MS = 2_000, GAP_MS = 500;
    static final int OVERALL_MS = 50_000, MAX_CALLBACKS = 128, MAX_QUEUED = 32;
    static final String LOCK_PATH = "/data/local/tmp/denza-dtc-read.lock";
    static final class Step {
        final String ecu, cached;
        final int address, did;
        Step(String ecu, int address, int did, String cached) {
            this.ecu = ecu; this.address = address; this.did = did; this.cached = cached;
        }
        byte[] request() {
            return new byte[]{0x0A, (byte) (address >> 8), (byte) address, 3,
                0x22, (byte) (did >> 8), (byte) did};
        }
    }
    // Exact ecu-child-identification-candidates-20261021.json order; no F194 control added.
    static final Step[] STEPS = {
        new Step("HTH-2021_SMF_L_DFL_00100001", 0x750, 0xF8C4, "47380011DFE3000102"),
        new Step("HTH-2021_SMF_R_DFL_00100001", 0x7B5, 0xF8D4, "47380011DFE2000200"),
        new Step("HTH-2021_SMR_L_DFL_00100001", 0x7D2, 0xF8E4, "47380011DFE0001C00"),
        new Step("HTH-2021_SMR_R_DFL_00100001", 0x7D2, 0xF8F4, "47380011DFE1001D00"),
        new Step("HTH-2021_RL_L_BYD_00200001", 0x726, 0xF204, "48330011DFC5000201"),
        new Step("HTH-2021_RL_R_BYD_00200001", 0x726, 0xF214, "48330011DFC6000202"),
        new Step("HTH-2021_TL_L_BYD_00200001", 0x726, 0xF064, "48330011DFB2000203"),
        new Step("HTH-2021_TL_R_BYD_00200001", 0x726, 0xF234, "48330011DFD5000204"),
    };
    static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format("%02X", b & 255));
        return s.toString();
    }
    interface Host {
        long nanos();
        boolean safe() throws Exception;
        void later(Runnable task, int delayMs);
        void send(byte[] request) throws Exception;
        void out(String line);
        void finish(String reason, int requests, int completed);
    }
    /** Main-looper state machine; also exercised on the host with a fake clock/transport. */
    static final class Engine {
        final Host host;
        volatile boolean done, open;
        boolean quiet = true;
        int step = -1, requests, completed, callbacks;
        volatile long sentAt;
        long deadline;
        byte[] lastAccepted, lastCompleted;
        Engine(Host host) { this.host = host; }
        void start() {
            deadline = host.nanos() + OVERALL_MS * 1_000_000L;
            host.out("START steps=8 passive_ms=2000 request_ms=4000 overall_ms=50000");
            host.later(() -> abort("OVERALL_TIMEOUT"), OVERALL_MS);
            host.later(this::next, SILENCE_MS);
        }
        void abort(String reason) {
            if (done) return;
            done = true; open = false;
            host.finish(reason, requests, completed);
        }
        void next() {
            if (done) return;
            if (host.nanos() >= deadline) { abort("OVERALL_TIMEOUT"); return; }
            quiet = false;
            if (++step == STEPS.length) { abort("PLAN_DONE"); return; }
            try {
                if (!host.safe()) { abort("VEHICLE_GUARD"); return; }
                if (done) return;
                if (host.nanos() >= deadline) { abort("OVERALL_TIMEOUT"); return; }
                Step s = STEPS[step];
                byte[] request = s.request();
                // Even an accidental source edit cannot use a different service/shape.
                if (request.length != 7 || request[4] != 0x22 || request[3] != 3
                        || s.address < 0x700 || s.address > 0x7F7 || s.address == 0x7DF)
                    throw new IllegalStateException("fixed plan invariant");
                lastAccepted = null;
                sentAt = host.nanos(); open = true; requests++;
                final int expectedStep = step;
                host.later(() -> {
                    if (!done && open && step == expectedStep) end("REQUEST_TIMEOUT");
                }, REQUEST_MS);
                host.out("REQUEST step=" + step + " ecu=" + s.ecu + " t_ns=" + sentAt
                    + " raw=" + hex(request));
                if (host.nanos() >= sentAt + REQUEST_MS * 1_000_000L) {
                    abort("DISPATCH_DEADLINE"); return;
                }
                host.send(request);
            } catch (Exception error) { abort("SEND_OR_GUARD_FAILED"); }
        }
        void end(String state) {
            if (!open || done) return;
            open = false; completed++; lastCompleted = lastAccepted;
            host.out("STEP_END step=" + step + " state=" + state);
            if ("GATEWAY_BUSY".equals(state)) { abort("GATEWAY_BUSY"); return; }
            host.later(this::next, GAP_MS);
        }
        void received(byte[] bytes, long at) {
            if (done) return;
            if (++callbacks > MAX_CALLBACKS) { abort("CALLBACK_BOUND"); return; }
            if (quiet) { abort("PREEXISTING_DIAGNOSTIC_TRAFFIC"); return; }
            if (bytes == null || bytes.length < 7 || bytes.length > 1024) {
                abort("FRAME_SHAPE"); return;
            }
            int length = bytes[3] & 255;
            if (bytes[0] != 0x0A || bytes[4] != 1 || bytes[5] != 1
                    || 7 + length > bytes.length) { abort("FRAME_SHAPE"); return; }
            byte[] frame = Arrays.copyOf(bytes, 7 + length); // Ignore only unused transport tail.
            if (!open) {
                if (Arrays.equals(frame, lastCompleted)) { host.out("DUPLICATE gap=1"); return; }
                abort("UNEXPECTED_DIAGNOSTIC_TRAFFIC"); return;
            }
            Step s = STEPS[step];
            int response = ((frame[1] & 255) << 8) | (frame[2] & 255);
            if (response != s.address + 8 || at < sentAt) {
                abort("UNEXPECTED_DIAGNOSTIC_TRAFFIC"); return;
            }
            if (at >= sentAt + REQUEST_MS * 1_000_000L) {
                abort("LATE_RESPONSE"); return;
            }
            if (Arrays.equals(frame, lastAccepted)) { host.out("DUPLICATE gap=0"); return; }
            lastAccepted = frame;
            host.out("FRAME step=" + step + " t_ns=" + at + " raw=" + hex(frame));
            int result = frame[6] & 255;
            byte[] uds = Arrays.copyOfRange(frame, 7, frame.length);
            if (result == 3 || result == 4 || result == 5) {
                end(result == 3 ? "NO_RESPONSE" : result == 4 ? "GATEWAY_TIMEOUT" : "GATEWAY_BUSY");
                return;
            }
            if (result != 1 && result != 2) { abort("GATEWAY_RESULT"); return; }
            if (uds.length == 3 && uds[0] == 0x7F && uds[1] == 0x22) {
                if ((uds[2] & 255) == 0x78) {
                    host.out("PENDING step=" + step); // Original request deadline; no resend/rearm.
                } else end("UDS_NEGATIVE_" + String.format("%02X", uds[2] & 255));
                return;
            }
            if (result != 1 || uds.length != 12 || uds[0] != 0x62
                    || (uds[1] & 255) != s.did >> 8 || (uds[2] & 255) != (s.did & 255)) {
                abort("RESPONSE_MISMATCH"); return;
            }
            byte[] code = Arrays.copyOfRange(uds, 3, 12);
            boolean nonzero = false;
            for (byte b : code) nonzero |= b != 0;
            String actual = hex(code);
            host.out("IDENTITY step=" + step + " code=" + actual + " cache_match=" + s.cached.equals(actual));
            end(!nonzero ? "ZERO_PLACEHOLDER" : s.cached.equals(actual) ? "CACHE_MATCH" : "CACHE_MISMATCH");
        }
    }

    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger callbackCount = new AtomicInteger();
    private final AtomicBoolean callbackRejected = new AtomicBoolean();
    private final AtomicBoolean callerClosed = new AtomicBoolean();
    private volatile Handler main;
    private volatile Engine engine;
    private RandomAccessFile lockFile;
    private FileLock lock;
    private IBinder auto;
    private Object device, listener;
    private Class<?> deviceClass, listenerClass, valueClass;
    private boolean registered;
    static String prop(String key) throws Exception {
        return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, key);
    }
    int word(int tx, int dev, int fid) throws Exception {
        Parcel a = Parcel.obtain(), b = Parcel.obtain();
        try {
            a.writeInterfaceToken(auto.getInterfaceDescriptor()); a.writeInt(dev); a.writeInt(fid);
            if (!auto.transact(tx, a, b, 0)) throw new IllegalStateException("guard refused");
            b.readException();
            if (b.dataAvail() != 4) throw new IllegalStateException("guard shape");
            return b.readInt();
        } finally { a.recycle(); b.recycle(); }
    }
    boolean safe() throws Exception {
        return "170".equals(prop("persist.sys.AutoType"))
            && "CANFD".equals(prop("sys.car.protocol"))
            && "r@-1_0&w@-1_0".equals(prop("sys.diagmgrsrv.task_bk"))
            && word(5, 1011, 89129008) == 1
            && Float.intBitsToFloat(word(7, 1013, -1807745016)) == 0f;
    }
    static void out(String s) { System.out.println(s); System.out.flush(); }
    void finish(String reason, int requests, int completed) {
        if (!closing.compareAndSet(false, true)) return;
        boolean cleaned = true;
        try {
            if (registered) {
                deviceClass.getMethod("unregisterListener", listenerClass).invoke(device, listener);
                registered = false; out("UNREGISTERED");
            }
        } catch (Throwable e) { cleaned = false; out("UNREGISTER_FAILED"); }
        try {
            if (lock != null) lock.release();
            if (lockFile != null) lockFile.close();
        } catch (Exception e) { cleaned = false; }
        if (queued.get() != 0) cleaned = false;
        out("END reason=" + reason + " requests=" + requests + " completed=" + completed
            + " cleanup=" + cleaned + " callbacks=" + callbackCount.get() + " queued=" + queued.get());
        System.exit(cleaned && "PLAN_DONE".equals(reason) && requests == 8 && completed == 8 ? 0 : 2);
    }
    void deliver(byte[] b) {
        long at = SystemClock.elapsedRealtimeNanos();
        if (closing.get() || callbackRejected.get()) return;
        if (callbackCount.incrementAndGet() > MAX_CALLBACKS || b == null || b.length > 1024
                || queued.incrementAndGet() > MAX_QUEUED) {
            if (callbackRejected.compareAndSet(false, true))
                main.post(() -> engine.abort("CALLBACK_BOUND_OR_SHAPE"));
            return;
        }
        byte[] copy = b.clone();
        main.post(() -> { queued.decrementAndGet(); engine.received(copy, at); });
    }
    void callbackFailure(String why) {
        if (callbackRejected.compareAndSet(false, true)) main.post(() -> engine.abort(why));
    }
    void run() {
        // Independent physical deadline also handles a stuck main looper or Binder call.
        Thread watchdog = new Thread(() -> {
            final long started = SystemClock.elapsedRealtimeNanos();
            while (true) {
                long now = SystemClock.elapsedRealtimeNanos();
                Engine e = engine;
                if (e != null && e.open && now - e.sentAt >= (REQUEST_MS + 2_000L) * 1_000_000L) {
                    Runtime.getRuntime().halt(3); // No IO: a blocked stdout cannot delay the bound.
                }
                if (now - started >= (OVERALL_MS + 2_000L) * 1_000_000L) {
                    Runtime.getRuntime().halt(3);
                }
                if (now - started >= OVERALL_MS * 1_000_000L && main != null && e != null)
                    main.post(() -> e.abort("OVERALL_TIMEOUT"));
                try { Thread.sleep(50); } catch (InterruptedException ignored) { return; }
            }
        }, "child-identity-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        try {
            if (Process.myUid() != 2000) { finish("WRONG_UID", 0, 0); return; }
            lockFile = new RandomAccessFile(LOCK_PATH, "rw");
            lock = lockFile.getChannel().tryLock();
            if (lock == null) { finish("LOCK_BUSY", 0, 0); return; }
            ShellProxyBootstrap.exemptHiddenApis();
            auto = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "autoservice");
            if (auto == null || !safe()) { finish("INITIAL_GUARD", 0, 0); return; }
            ShellProxyBootstrap.prepareMainLooper(); main = new Handler(Looper.getMainLooper());
            engine = new Engine(new Host() {
                public long nanos() { return SystemClock.elapsedRealtimeNanos(); }
                public boolean safe() throws Exception { return !callerClosed.get() && ChildIdentityReadProbe.this.safe(); }
                public void later(Runnable r, int ms) { main.postDelayed(r, ms); }
                public void send(byte[] request) throws Exception {
                    if (callerClosed.get()) throw new IllegalStateException("caller closed");
                    Object v = valueClass.getConstructor().newInstance();
                    valueClass.getField("bufferDataValue").set(v, request);
                    Object rc = deviceClass.getMethod("set", int[].class, valueClass).invoke(device, new int[]{TX}, v);
                    out("TRANSPORT_RESULT step=" + engine.step + " result=" + rc);
                    if (!Integer.valueOf(0).equals(rc)) throw new IllegalStateException("transport refused");
                }
                public void out(String line) { ChildIdentityReadProbe.out(line); }
                public void finish(String why, int req, int complete) { ChildIdentityReadProbe.this.finish(why, req, complete); }
            });
            Context context = ShellProxyBootstrap.systemContext();
            deviceClass = Class.forName("android.hardware.bydauto.mqtt.BYDAutoMqttDevice");
            listenerClass = Class.forName("android.hardware.IBYDAutoListener");
            valueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            Class<?> eventClass = Class.forName("android.hardware.IBYDAutoEvent");
            device = deviceClass.getMethod("getInstance", Context.class).invoke(null, context);
            listener = Proxy.newProxyInstance(ChildIdentityReadProbe.class.getClassLoader(), new Class<?>[]{listenerClass}, (p, m, a) -> {
                try {
                    if (m.getName().equals("onDataEventChanged") && a != null && a.length > 1 && Integer.valueOf(RX).equals(a[0]))
                        deliver((byte[]) valueClass.getField("bufferDataValue").get(a[1]));
                    else if (m.getName().equals("onDataChanged") && a != null && a.length > 0 && a[0] != null
                            && Integer.valueOf(RX).equals(eventClass.getMethod("getEventType").invoke(a[0])))
                        deliver((byte[]) eventClass.getMethod("getBufferData").invoke(a[0]));
                    else if (m.getName().equals("onError")) callbackFailure("CALLBACK_ERROR");
                    else if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                    else if (m.getName().equals("equals")) return a != null && a.length == 1 && p == a[0];
                    else if (m.getName().equals("toString")) return "ChildIdentityReadProbe";
                } catch (Throwable error) { callbackFailure("CALLBACK_EXCEPTION"); }
                return null;
            });
            registered = true; // Cleanup is attempted even if registration partially fails.
            deviceClass.getMethod("registerListener", listenerClass, int[].class).invoke(device, listener, new int[]{RX});
            out("REGISTERED");
            Thread input = new Thread(() -> {
                try { System.in.read(); } catch (Exception ignored) { }
                callerClosed.set(true);
                main.post(() -> engine.abort("CALLER_CLOSED_OR_INPUT"));
            }, "child-identity-caller");
            input.setDaemon(true); input.start();
            engine.start(); Looper.loop();
        } catch (Throwable error) { finish("START_OR_RUNTIME_FAILED", engine == null ? 0 : engine.requests, engine == null ? 0 : engine.completed); }
    }
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("No arguments: exactly eight fixed code reads");
        new ChildIdentityReadProbe().run();
    }
}
