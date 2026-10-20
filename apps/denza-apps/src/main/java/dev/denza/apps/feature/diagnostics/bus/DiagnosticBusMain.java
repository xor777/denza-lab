package dev.denza.apps.feature.diagnostics.bus;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;

import dev.denza.apps.platform.shell.ShellProxyBootstrap;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.lang.reflect.Proxy;
import java.nio.channels.FileLock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The diagnostic section's one shell-UID helper: the car's diagnostic channel (device 1033) for as
 * long as the section is open, one request at a time (docs/vehicle-diagnostics-architecture.md,
 * 3.2 and 3.3).
 *
 * <p>Packed with {@link DiagnosticWhitelist} and {@link DiagnosticFrameAssembler} and nothing else
 * of the app, and run by {@code app_process} on an ADB stream of its own. It takes
 * {@code x <seq> <address> <uds> <timeout-ms>} and {@code quit}; every other word is refused. The
 * whitelist is checked here again, because this process, not the app, is the one that can write
 * to the bus.
 *
 * <p>It starts by taking the shared reader lock for its whole life, checking that the car is
 * parked, registering for the answers and then listening for two seconds: a frame in that silence
 * means somebody else is talking to the ECUs. Only then does it print READY. A start that fails
 * still prints READY, after letting go of everything it took, and answers the first line with the
 * reason as {@code END err <code>}: the ADB stream reports a helper that exits before READY only
 * as a closed stream, and the app must tell "the lock is taken" and "not in P" from a lost link.
 *
 * <p>Before every request: the whitelist, profile 170, P, 0 km/h, the stock diagnostic manager at
 * rest and the gap since the previous exchange. The listener only copies the buffer and its
 * arrival time and posts them to the main looper; everything else happens there.
 */
@SuppressLint({"PrivateApi", "BlockedPrivateApi", "DiscouragedPrivateApi"})
public final class DiagnosticBusMain {
    static final int TX = 0xAA00010A;
    static final int RX = 0x9900010A;
    static final String LOCK_PATH = "/data/local/tmp/denza-dtc-read.lock";
    static final String MANAGER_AT_REST = "r@-1_0&w@-1_0";
    static final int SILENCE_MS = 2_000;
    static final int IDLE_EXIT_MS = 60_000;
    static final int DEFAULT_GAP_MS = 100;
    static final int MAX_GAP_MS = 500;
    static final int MAX_TIMEOUT_MS = 6_000;
    /** How much longer than its own timeout the reader waits for the main looper. */
    static final int STALL_MS = 5_000;

    private final String begin;
    private final String end;
    private final String ready;
    private final int gapMs;
    private final DiagnosticFrameAssembler assembler = new DiagnosticFrameAssembler();
    private Handler main;
    private RandomAccessFile lockFile;
    private FileLock lock;
    private IBinder autoService;
    private Object device;
    private Object listener;
    private Class<?> deviceClass;
    private Class<?> listenerClass;
    private Class<?> valueClass;
    private boolean announced;
    /** The session's verdict once it is over: {@code <CODE> <why>}. Main looper only. */
    private volatile String fatal;
    private Exchange current;
    private long lastEndUptime = -1;
    private final Runnable idleExit = () -> exit(0);

    private DiagnosticBusMain(String nonce, int gapMs) {
        begin = "DENZA_SERVE_" + nonce + ":BEGIN";
        end = "DENZA_SERVE_" + nonce + ":END";
        ready = "DENZA_SERVE_" + nonce + ":READY";
        this.gapMs = gapMs;
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3 || !"serve".equals(args[0]) || !args[1].matches("[0-9a-f]+")) {
            throw new IllegalArgumentException("serve <hex-nonce> [<gap-ms>] required");
        }
        int gap = DEFAULT_GAP_MS;
        if (args.length == 3) {
            gap = args[2].matches("[0-9]{1,3}") ? Integer.parseInt(args[2]) : -1;
            if (gap < 0 || gap > MAX_GAP_MS) throw new IllegalArgumentException("gap outside 0..500 ms");
        }
        new DiagnosticBusMain(args[1], gap).run();
    }

    private void run() {
        ShellProxyBootstrap.prepareMainLooper();
        main = new Handler(Looper.getMainLooper());
        String refused = start();
        if (refused != null) {
            refuse(refused);
        } else {
            main.postDelayed(this::announce, SILENCE_MS);
        }
        Looper.loop();
    }

    /** Null when the helper may listen; otherwise {@code <CODE> <why>}. */
    private String start() {
        try {
            int uid = Process.myUid();
            if (uid != 2000) return "WRONG_UID uid " + uid;
            lockFile = new RandomAccessFile(LOCK_PATH, "rw");
            lock = lockFile.getChannel().tryLock();
            if (lock == null) return "LOCK_BUSY another reader holds the diagnostic lock";
            ShellProxyBootstrap.exemptHiddenApis();
            autoService = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "autoservice");
            if (autoService == null) return "HELPER_FAILED no autoservice";
            String guard = guard();
            if (guard != null) return guard;
            Context context = ShellProxyBootstrap.systemContext();
            deviceClass = Class.forName("android.hardware.bydauto.mqtt.BYDAutoMqttDevice");
            listenerClass = Class.forName("android.hardware.IBYDAutoListener");
            valueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            Class<?> eventClass = Class.forName("android.hardware.IBYDAutoEvent");
            device = deviceClass.getMethod("getInstance", Context.class).invoke(null, context);
            listener = Proxy.newProxyInstance(DiagnosticBusMain.class.getClassLoader(),
                    new Class<?>[]{listenerClass}, (proxy, method, a) -> {
                        try {
                            switch (method.getName()) {
                                case "onDataEventChanged":
                                    if (a != null && a.length > 1 && Integer.valueOf(RX).equals(a[0])) {
                                        deliver((byte[]) valueClass.getField("bufferDataValue").get(a[1]));
                                    }
                                    return null;
                                case "onDataChanged":
                                    if (a != null && a.length > 0 && a[0] != null && Integer.valueOf(RX)
                                            .equals(eventClass.getMethod("getEventType").invoke(a[0]))) {
                                        deliver((byte[]) eventClass.getMethod("getBufferData").invoke(a[0]));
                                    }
                                    return null;
                                case "onError":
                                    main.post(() -> fail("HELPER_FAILED the channel reported an error"));
                                    return null;
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                case "equals":
                                    return a != null && a.length == 1 && proxy == a[0];
                                case "toString":
                                    return "DenzaDiagnosticBusListener";
                                default:
                                    return null;
                            }
                        } catch (Exception error) {
                            main.post(() -> fail("HELPER_FAILED " + describe(error)));
                            return null;
                        }
                    });
            deviceClass.getMethod("registerListener", listenerClass, int[].class)
                    .invoke(device, listener, new int[]{RX});
            return null;
        } catch (Throwable error) {
            return "HELPER_FAILED " + describe(error);
        }
    }

    /** On a binder thread: the buffer is copied with its arrival time, and judged on the main looper. */
    private void deliver(byte[] data) {
        long at = SystemClock.elapsedRealtimeNanos();
        byte[] copy = data == null ? null : data.clone();
        main.post(() -> received(copy, at));
    }

    /** The two seconds of silence passed: the helper answers from now on. */
    private void announce() {
        if (announced) return;
        announced = true;
        System.out.println(ready);
        System.out.flush();
        main.postDelayed(idleExit, IDLE_EXIT_MS);
        Thread reader = new Thread(this::serve, "denza-diag-bus-requests");
        reader.setDaemon(true);
        reader.start();
    }

    /** A start that failed: nothing is held, and the first line learns why. */
    private void refuse(String verdict) {
        fatal = verdict;
        release();
        announce();
    }

    /** Ends the session from the main looper: an open exchange gets the verdict, otherwise the next line. */
    private void fail(String verdict) {
        if (fatal != null) return;
        fatal = verdict;
        release();
        if (current != null) {
            Exchange exchange = current;
            current = null;
            assembler.end();
            exchange.finish(null, verdict, true);
        }
        announce();
    }

    private void serve() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = reader.readLine()) != null) {
                String request = line.trim();
                if (request.isEmpty()) continue;
                if ("quit".equals(request)) break;
                Exchange exchange = parse(request);
                if (exchange.error == null) {
                    String verdict = fatal;
                    if (verdict != null) {
                        exchange.finish(null, verdict, true);
                    } else {
                        main.post(() -> send(exchange));
                        if (!exchange.done.await(exchange.timeoutMs + gapMs + STALL_MS, TimeUnit.MILLISECONDS)) {
                            exchange.finish(null, "HELPER_FAILED the helper stalled", true);
                        }
                    }
                }
                System.out.println(begin);
                if (exchange.error == null) {
                    System.out.println(exchange.answer);
                    System.out.println(end + " ok");
                } else {
                    System.out.println(end + " err " + exchange.error.replace('\n', ' ').replace('\r', ' '));
                }
                System.out.flush();
                if (exchange.exits) break;
            }
        } catch (Throwable error) {
            // The stream is the session; without it there is nobody to answer.
        }
        exit(0);
    }

    private Exchange parse(String line) {
        String[] words = line.split(" +");
        Exchange exchange = new Exchange();
        try {
            if (words.length != 5 || !"x".equals(words[0])) throw new IllegalArgumentException("x <seq> <address> <uds> <timeout> required");
            exchange.seq = Integer.parseInt(words[1]);
            if (exchange.seq < 0) throw new IllegalArgumentException("negative seq");
            if (!words[2].matches("[0-9A-Fa-f]{3}")) throw new IllegalArgumentException("address is three hex digits");
            exchange.address = Integer.parseInt(words[2], 16);
            if (!words[3].matches("([0-9A-Fa-f]{2}){1,7}")) throw new IllegalArgumentException("uds is 1..7 hex bytes");
            exchange.uds = bytes(words[3]);
            exchange.timeoutMs = Integer.parseInt(words[4]);
            if (exchange.timeoutMs < 1 || exchange.timeoutMs > MAX_TIMEOUT_MS) throw new IllegalArgumentException("timeout outside 1..6000 ms");
            String refusal = DiagnosticWhitelist.refusal(exchange.address, exchange.uds);
            if (refusal != null) throw new IllegalArgumentException(refusal);
        } catch (IllegalArgumentException refused) {
            exchange.finish(null, "REFUSED " + refused.getMessage(), false);
        }
        return exchange;
    }

    /** On the main looper: the checks, then the request onto the bus. */
    private void send(Exchange exchange) {
        main.removeCallbacks(idleExit);
        if (fatal != null) {
            exchange.finish(null, fatal, true);
            return;
        }
        long wait = lastEndUptime < 0 ? 0 : lastEndUptime + gapMs - SystemClock.uptimeMillis();
        if (wait > 0) {
            main.postDelayed(() -> send(exchange), wait);
            return;
        }
        try {
            String guard = guard();
            if (guard != null) {
                if (guard.startsWith("MANAGER_BUSY")) {
                    fail(guard);
                    exchange.finish(null, guard, true);
                } else {
                    closed();
                    exchange.finish(null, guard, false);
                }
                return;
            }
            byte[] request = new byte[4 + exchange.uds.length];
            request[0] = (byte) DiagnosticFrameAssembler.OWNER;
            request[1] = (byte) (exchange.address >> 8);
            request[2] = (byte) exchange.address;
            request[3] = (byte) exchange.uds.length;
            System.arraycopy(exchange.uds, 0, request, 4, exchange.uds.length);
            Object value = valueClass.getConstructor().newInstance();
            valueClass.getField("bufferDataValue").set(value, request);
            assembler.begin(exchange.address, exchange.uds);
            current = exchange;
            exchange.timeout = () -> {
                if (current != exchange) return;
                current = null;
                assembler.end();
                long now = SystemClock.elapsedRealtimeNanos();
                closed();
                exchange.finish("n " + exchange.seq + " T " + exchange.millisTo(now) + " " + now, null, false);
            };
            main.postDelayed(exchange.timeout, exchange.timeoutMs);
            exchange.sentNanos = SystemClock.elapsedRealtimeNanos();
            Object rc = deviceClass.getMethod("set", int[].class, valueClass).invoke(device, new int[]{TX}, value);
            if (!Integer.valueOf(0).equals(rc)) fail("TRANSPORT_REJECTED set returned " + rc);
        } catch (Throwable error) {
            fail("HELPER_FAILED " + describe(error));
            if (!exchange.isDone()) exchange.finish(null, fatal, true);
        }
    }

    /** On the main looper: one RX buffer and the moment it arrived. */
    private void received(byte[] buffer, long atNanos) {
        if (fatal != null) return;
        if (!announced) {
            fail("FOREIGN_TRAFFIC a frame during the start's silence");
            return;
        }
        DiagnosticFrameAssembler.Outcome outcome = assembler.accept(buffer);
        switch (outcome.kind) {
            case WAITING:
                return;
            case SHAPE:
                fail("FRAME_SHAPE " + outcome.detail);
                return;
            case FOREIGN:
                fail("FOREIGN_TRAFFIC " + outcome.detail);
                return;
            default:
                break;
        }
        Exchange exchange = current;
        current = null;
        if (exchange == null) return;
        main.removeCallbacks(exchange.timeout);
        closed();
        String timing = exchange.millisTo(atNanos) + " " + atNanos;
        if (outcome.kind == DiagnosticFrameAssembler.Kind.ANSWER) {
            exchange.finish("a " + exchange.seq + " " + outcome.frames + " " + timing + " " + hex(outcome.uds), null, false);
        } else {
            String result = String.valueOf(outcome.gatewayResult);
            exchange.finish("n " + exchange.seq + " " + result + " " + timing, null, false);
        }
    }

    /** An exchange ended: the gap and the idle clock start now. */
    private void closed() {
        lastEndUptime = SystemClock.uptimeMillis();
        main.removeCallbacks(idleExit);
        main.postDelayed(idleExit, IDLE_EXIT_MS);
    }

    /** Null when a request may go out; otherwise {@code <CODE> <why>}. */
    private String guard() throws Exception {
        String profile = property("persist.sys.AutoType");
        if (!"170".equals(profile)) return "GUARD_PROFILE profile " + profile;
        int gear = readWord(5, 1011, 89129008);
        if (gear != 1) return "GUARD_PARK gear " + gear;
        float speed = Float.intBitsToFloat(readWord(7, 1013, -1807745016));
        if (speed != 0f) return "GUARD_SPEED speed " + speed;
        String manager = property("sys.diagmgrsrv.task_bk");
        if (!MANAGER_AT_REST.equals(manager)) return "MANAGER_BUSY manager " + manager;
        return null;
    }

    private static String property(String key) throws Exception {
        return (String) Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class).invoke(null, key);
    }

    private int readWord(int transaction, int deviceId, int fid) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel result = Parcel.obtain();
        try {
            data.writeInterfaceToken(autoService.getInterfaceDescriptor());
            data.writeInt(deviceId);
            data.writeInt(fid);
            if (!autoService.transact(transaction, data, result, 0)) throw new IllegalStateException("autoservice refused GET");
            result.readException();
            if (result.dataAvail() != 4) throw new IllegalStateException("autoservice GET shape");
            return result.readInt();
        } finally {
            data.recycle();
            result.recycle();
        }
    }

    /** Lets go of the listener and the lock; the process may still be answering its last line. */
    private synchronized void release() {
        try {
            if (device != null && listener != null) {
                deviceClass.getMethod("unregisterListener", listenerClass).invoke(device, listener);
            }
        } catch (Throwable ignored) {
            // The process is about to go, and the listener with it.
        }
        device = null;
        listener = null;
        try {
            if (lock != null) lock.release();
            if (lockFile != null) lockFile.close();
        } catch (Exception ignored) {
            // Closing the process closes the file and its lock.
        }
        lock = null;
        lockFile = null;
    }

    private void exit(int status) {
        release();
        System.out.flush();
        System.exit(status);
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) s.append(String.format("%02X", b & 0xFF));
        return s.toString();
    }

    private static byte[] bytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }

    /** One line from the app and what becomes of it; finished exactly once, from either thread. */
    private static final class Exchange {
        final CountDownLatch done = new CountDownLatch(1);
        int seq;
        int address;
        byte[] uds;
        int timeoutMs;
        long sentNanos;
        Runnable timeout;
        volatile String answer;
        volatile String error;
        volatile boolean exits;

        synchronized void finish(String answer, String error, boolean exits) {
            if (done.getCount() == 0) return;
            this.answer = answer;
            this.error = error;
            this.exits = exits;
            done.countDown();
        }

        boolean isDone() {
            return done.getCount() == 0;
        }

        long millisTo(long nanos) {
            return Math.max(0, (nanos - sentNanos) / 1_000_000L);
        }
    }
}
