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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The diagnostic section's one shell-UID helper: the car's diagnostic channel (device 1033) for as
 * long as the section is open, one request at a time (docs/vehicle-diagnostics-architecture.md,
 * 3.2 and 3.3).
 *
 * <p>Packed with {@link DiagnosticWhitelist} and {@link DiagnosticFrameAssembler} and nothing else
 * of the app, and run by {@code app_process} on an ADB stream of its own. It takes
 * {@code x <seq> <address> <uds> <timeout-ms>}, {@code g <gap-ms>}, {@code s gb}, {@code u gb} and
 * {@code quit}; every other word is refused. The whitelist is checked here again, because this
 * process, not the app, is the one that can write to the bus.
 *
 * <p>{@code g <ms>} sets the gap between exchanges, 0 to 500 ms, from the next exchange on, and is
 * answered with the same {@code g <ms>} between BEGIN and {@code END ok}: the gap now in force. The
 * gap a helper starts with is its launch parameter; the parameter page asks for less while it polls
 * one PID (architecture, 6.2).
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
 *
 * <p>{@code s gb} and {@code u gb} subscribe to and leave the BMS's pages of the GB stream (device
 * 1039, event {@code 9900001F}; architecture 6.3), each answered with the same line. Only while
 * subscribed is a listener registered there; it forwards the records of CAN ids 43A, 43C and 43D
 * at sub 0, channel 2 as pushed lines, {@code DENZA_SERVE_<nonce>:PUSH b <ns> <id> <payload>},
 * printed whole between answers and never inside one. Nothing is sent to the car for them, so
 * the per-request checks do not apply; the helper itself only starts parked. While subscribed the
 * idle clock stands - the app reading the pages is the activity - and a session that ends while
 * subscribed pushes {@code e <CODE> <why>} first. The listener goes on {@code u gb}, on
 * {@code quit}, on the idle exit and on every other way out ({@link #release}).
 */
@SuppressLint({"PrivateApi", "BlockedPrivateApi", "DiscouragedPrivateApi"})
public final class DiagnosticBusMain {
    static final int TX = 0xAA00010A;
    static final int RX = 0x9900010A;
    static final String LOCK_PATH = "/data/local/tmp/denza-dtc-read.lock";
    static final String MANAGER_AT_REST = "r@-1_0&w@-1_0";
    static final int SILENCE_MS = 2_000;
    static final int IDLE_EXIT_MS = 60_000;
    /**
     * How long the start waits for the lock: the app drops an idle helper ten seconds before that
     * helper's own clock ends it, and the one it starts next must not find the lock still held by
     * the one that is on its way out (seen on the car 2026-10-20: 0.6 s apart).
     */
    static final int LOCK_WAIT_MS = 3_000;
    static final int LOCK_POLL_MS = 100;
    static final int DEFAULT_GAP_MS = 100;
    static final int MAX_GAP_MS = 500;
    static final int MAX_TIMEOUT_MS = 6_000;
    /** How much longer than its own timeout the reader waits for the main looper. */
    static final int STALL_MS = 5_000;
    /** The GB stream: its device, its event, and the BMS pages of it that are forwarded (architecture, 6.3). */
    static final String GB_DEVICE_CLASS = "android.hardware.bydauto.gb.BYDAutoGBDevice";
    static final int GB_DEVICE = 1039;
    static final int GB_EVENT = 0x9900001F;
    static final int PAGE_COUNTS = 0x43A;
    static final int PAGE_PROBES = 0x43C;
    static final int PAGE_CELLS = 0x43D;
    /** The ten-byte envelope before a record's payload: id (4, big-endian), sub, channel, counter (4). */
    static final int ENVELOPE = 10;
    static final int MAX_PAGE_PAYLOAD = 64;
    /** Pushed lines waiting for the stream; a full queue drops pages, and the app sees the gap. */
    static final int PUSH_QUEUE = 1_024;

    private final String begin;
    private final String end;
    private final String ready;
    private final String push;
    /** Every write to stdout holds it: an answer and a pushed line never interleave. */
    private final Object out = new Object();
    private final ArrayBlockingQueue<String> pushes = new ArrayBlockingQueue<>(PUSH_QUEUE);
    /** True while the app is subscribed to the pages; written on the main looper. */
    private volatile boolean subscribed;
    private Context context;
    private Object gbDevice;
    private Object gbListener;
    private Class<?> gbClass;
    private Class<?> eventClass;
    /** Set by the request thread between two exchanges, read by the main looper at the next one. */
    private volatile int gapMs;
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
    private final Runnable idleExit = () -> {
        if (!subscribed) exit(0);
    };

    private DiagnosticBusMain(String nonce, int gapMs) {
        begin = "DENZA_SERVE_" + nonce + ":BEGIN";
        end = "DENZA_SERVE_" + nonce + ":END";
        ready = "DENZA_SERVE_" + nonce + ":READY";
        push = "DENZA_SERVE_" + nonce + ":PUSH ";
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
            long lockDeadline = SystemClock.uptimeMillis() + LOCK_WAIT_MS;
            lock = lockFile.getChannel().tryLock();
            while (lock == null && SystemClock.uptimeMillis() < lockDeadline) {
                SystemClock.sleep(LOCK_POLL_MS);
                lock = lockFile.getChannel().tryLock();
            }
            if (lock == null) return "LOCK_BUSY another reader holds the diagnostic lock";
            ShellProxyBootstrap.exemptHiddenApis();
            autoService = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "autoservice");
            if (autoService == null) return "HELPER_FAILED no autoservice";
            String guard = guard();
            if (guard != null) return guard;
            context = ShellProxyBootstrap.systemContext();
            deviceClass = Class.forName("android.hardware.bydauto.mqtt.BYDAutoMqttDevice");
            listenerClass = Class.forName("android.hardware.IBYDAutoListener");
            valueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            eventClass = Class.forName("android.hardware.IBYDAutoEvent");
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
        emit(ready + "\n");
        main.postDelayed(idleExit, IDLE_EXIT_MS);
        Thread reader = new Thread(this::serve, "denza-diag-bus-requests");
        reader.setDaemon(true);
        reader.start();
        Thread writer = new Thread(this::pushAll, "denza-diag-bus-pushes");
        writer.setDaemon(true);
        writer.start();
    }

    /** Writes [text] whole: an answer block and a pushed line each go out under one lock and one flush. */
    private void emit(String text) {
        synchronized (out) {
            System.out.print(text);
            System.out.flush();
        }
    }

    /** The pushed lines, in order, off the binder threads that queued them: a slow stream never holds up a binder thread or the main looper. */
    private void pushAll() {
        try {
            while (true) emit(pushes.take() + "\n");
        } catch (InterruptedException ignored) {
            // The process is going.
        }
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
        if (subscribed) {
            // The app may be sending nothing while it reads the pages: the end reaches it as a pushed line.
            subscribed = false;
            String last = push + "e " + verdict.replace('\n', ' ').replace('\r', ' ');
            while (!pushes.offer(last)) pushes.poll();
        }
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
                String word = request.split(" +")[0];
                boolean gapLine = word.equals("g");
                boolean pagesLine = word.equals("s") || word.equals("u");
                Exchange exchange = gapLine ? gap(request) : pagesLine ? pages(request) : parse(request);
                if (!gapLine && !pagesLine && exchange.error == null) {
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
                if (exchange.error == null) {
                    emit(begin + "\n" + exchange.answer + "\n" + end + " ok\n");
                } else {
                    emit(begin + "\n" + end + " err " + exchange.error.replace('\n', ' ').replace('\r', ' ') + "\n");
                }
                if (exchange.exits) break;
            }
        } catch (Throwable error) {
            // The stream is the session; without it there is nobody to answer.
        }
        exit(0);
    }

    /**
     * {@code g <ms>}: the gap from the next exchange on, answered {@code g <ms>}. A session that is
     * already over answers its verdict, as an {@code x} line would; a malformed line is refused
     * like any unknown one.
     */
    private Exchange gap(String line) {
        Exchange exchange = new Exchange();
        int ms = gapOf(line);
        String verdict = fatal;
        if (verdict != null) {
            exchange.finish(null, verdict, true);
        } else if (ms < 0) {
            exchange.finish(null, "REFUSED g <ms> with ms 0..500 required", false);
        } else {
            gapMs = ms;
            exchange.finish("g " + ms, null, false);
        }
        return exchange;
    }

    /** The gap a {@code g <ms>} line asks for, or -1 for any other line. */
    static int gapOf(String line) {
        String[] words = line.trim().split(" +");
        if (words.length != 2 || !"g".equals(words[0]) || !words[1].matches("[0-9]{1,3}")) return -1;
        int ms = Integer.parseInt(words[1]);
        return ms <= MAX_GAP_MS ? ms : -1;
    }

    /**
     * {@code s gb} / {@code u gb}: the BMS pages from now on, or no more; answered with the same
     * line. A session that is already over answers its verdict; a malformed line is refused like
     * any unknown one. The listener is registered and dropped on the main looper.
     */
    private Exchange pages(String line) throws InterruptedException {
        Exchange exchange = new Exchange();
        String word = pagesOf(line);
        String verdict = fatal;
        if (verdict != null) {
            exchange.finish(null, verdict, true);
        } else if (word == null) {
            exchange.finish(null, "REFUSED s gb or u gb required", false);
        } else {
            main.post(() -> {
                if ("s".equals(word)) subscribe(exchange); else leave(exchange);
            });
            if (!exchange.done.await(STALL_MS, TimeUnit.MILLISECONDS)) {
                exchange.finish(null, "HELPER_FAILED the helper stalled", true);
            }
        }
        return exchange;
    }

    /** {@code s} or {@code u} of a well-formed {@code s gb} / {@code u gb} line, or null for any other line. */
    static String pagesOf(String line) {
        String[] words = line.trim().split(" +");
        if (words.length != 2 || !"gb".equals(words[1])) return null;
        return "s".equals(words[0]) || "u".equals(words[0]) ? words[0] : null;
    }

    /** On the main looper: the GB listener, once; the idle clock stands while the pages flow. */
    private void subscribe(Exchange exchange) {
        if (fatal != null) {
            exchange.finish(null, fatal, true);
            return;
        }
        try {
            if (gbListener == null) registerPages();
        } catch (Throwable error) {
            fail("HELPER_FAILED " + describe(error));
            exchange.finish(null, fatal, true);
            return;
        }
        subscribed = true;
        main.removeCallbacks(idleExit);
        exchange.finish("s gb", null, false);
    }

    /** On the main looper: the GB listener goes and the idle clock starts again. */
    private void leave(Exchange exchange) {
        if (fatal != null) {
            exchange.finish(null, fatal, true);
            return;
        }
        subscribed = false;
        unregisterPages();
        idleClock();
        exchange.finish("u gb", null, false);
    }

    private void registerPages() throws Exception {
        gbClass = Class.forName(GB_DEVICE_CLASS);
        Object device = gbClass.getMethod("getInstance", Context.class).invoke(null, context);
        java.lang.reflect.Method deviceType = eventClass.getMethod("getDeviceType");
        java.lang.reflect.Method eventType = eventClass.getMethod("getEventType");
        java.lang.reflect.Method buffer = eventClass.getMethod("getBufferData");
        Object pagesListener = Proxy.newProxyInstance(DiagnosticBusMain.class.getClassLoader(),
                new Class<?>[]{listenerClass}, (proxy, method, a) -> {
                    switch (method.getName()) {
                        case "onDataChanged":
                            // A binder thread: filter and queue, nothing more.
                            try {
                                if (subscribed && a != null && a.length > 0 && a[0] != null
                                        && Integer.valueOf(GB_DEVICE).equals(deviceType.invoke(a[0]))
                                        && Integer.valueOf(GB_EVENT).equals(eventType.invoke(a[0]))) {
                                    long at = SystemClock.elapsedRealtimeNanos();
                                    String line = pageLine((byte[]) buffer.invoke(a[0]), at);
                                    if (line != null) pushes.offer(push + line);
                                }
                            } catch (Exception ignored) {
                                // One record lost; the app sees the gap in its pages.
                            }
                            return null;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return a != null && a.length == 1 && proxy == a[0];
                        case "toString":
                            return "DenzaDiagnosticPagesListener";
                        default:
                            return null;
                    }
                });
        gbClass.getMethod("registerListener", listenerClass, int[].class)
                .invoke(device, pagesListener, new int[]{GB_EVENT});
        synchronized (this) {
            gbDevice = device;
            gbListener = pagesListener;
        }
    }

    /** Drops the GB listener, if there is one; from the main looper or [release]. */
    private synchronized void unregisterPages() {
        try {
            if (gbDevice != null && gbListener != null) {
                gbClass.getMethod("unregisterListener", listenerClass).invoke(gbDevice, gbListener);
            }
        } catch (Throwable ignored) {
            // The process goes with it at the latest.
        }
        gbDevice = null;
        gbListener = null;
    }

    /**
     * {@code b <ns> <id> <payload>} for a GB record the app asked for - CAN id 43A, 43C or 43D at
     * sub 0, channel 2, behind the ten-byte envelope (id, sub, channel, counter) - and null for
     * any other record. The payload goes as it came, upper-case hex.
     */
    static String pageLine(byte[] record, long atNanos) {
        if (record == null || record.length <= ENVELOPE || record.length > ENVELOPE + MAX_PAGE_PAYLOAD) return null;
        int id = ((record[0] & 0xFF) << 24) | ((record[1] & 0xFF) << 16) | ((record[2] & 0xFF) << 8) | (record[3] & 0xFF);
        if (id != PAGE_COUNTS && id != PAGE_PROBES && id != PAGE_CELLS) return null;
        if (record[4] != 0 || record[5] != 2) return null;
        byte[] payload = new byte[record.length - ENVELOPE];
        System.arraycopy(record, ENVELOPE, payload, 0, payload.length);
        return "b " + atNanos + " " + String.format("%03X", id) + " " + hex(payload);
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
        idleClock();
    }

    /** The idle clock from now; it stands while the app is subscribed to the pages. */
    private void idleClock() {
        main.removeCallbacks(idleExit);
        if (!subscribed) main.postDelayed(idleExit, IDLE_EXIT_MS);
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

    /** Lets go of both listeners and the lock; the process may still be answering its last line. */
    private synchronized void release() {
        subscribed = false;
        unregisterPages();
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
