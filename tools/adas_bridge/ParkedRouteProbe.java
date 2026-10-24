package dev.denza.hud.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import android.util.Base64;
import dev.denza.hudlink.HudSomeIp;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** One parked route/selection publication, then bounded observation and own-session cleanup. */
public final class ParkedRouteProbe {
    private static final long ROUTE_SERVICE = 0x000B002B002B0000L;
    private static final long SELECT_SERVICE = 0x000B002D002D0000L;
    private static final long ROUTE_TOPIC = 0x0004002B002B8002L;
    private static final long SELECT_TOPIC = 0x0004002D002D8001L;
    private static final long[] REQUIRED_ANSWERS = {0x0004000D000D8002L, 0x0004000D000D8003L,
            0x0004000D000D8005L, 0x0004002C002C8002L, 0x0004002C002C8001L};
    private static final int MAX_ROUTE_BYTES = 524288, MAX_ROUTES = 8;
    private final Context context;
    private final Handler main;
    private final String session = "com.android.shell.denza-adas-parked-" + UUID.randomUUID();
    private final CountDownLatch connected = new CountDownLatch(1);
    private final CountDownLatch observerReady = new CountDownLatch(1);
    private final AtomicInteger responseMask = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger eventCount = new AtomicInteger();
    private final ThreadPoolExecutor events = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(128), r -> { Thread t = new Thread(r, "adas-listener"); t.setDaemon(true); return t; });
    private volatile IBinder remote;
    private volatile boolean bound, routeAttempted, selectAttempted;
    private AdasPassiveClient passive;

    private ParkedRouteProbe(Context context) { this.context = context; main = new Handler(Looper.getMainLooper()); }

    /** Registered-APK entry point: private files only; no shell identity or Context bootstrap. */
    public static void startInApplication(Context context, File directory, int routeCount) throws Exception {
        if (routeCount < 1 || routeCount > MAX_ROUTES) throw new IllegalArgumentException("1..8 route chunks");
        List<byte[]> routes = new ArrayList<>();
        for (int i = 0; i < routeCount; i++) routes.add(read(new File(directory, "route-" + i + ".pb").getPath(), MAX_ROUTE_BYTES));
        byte[] selected = read(new File(directory, "selection.pb").getPath(), 65536);
        ParkedRouteProbe probe = new ParkedRouteProbe(context.getApplicationContext());
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(25000); probe.finish("25s watchdog"); Thread.sleep(4500); }
            catch (InterruptedException ignored) { return; }
            log("hard_deadline", "Own-session cleanup attempted"); Process.killProcess(Process.myPid());
        }, "adas-app-deadline");
        watchdog.setDaemon(true); watchdog.start();
        try {
            log("manifest", "registered APK, routeChunks=" + routes.size() + ", listenSeconds=8");
            probe.start();
            new Thread(() -> probe.publish(routes, selected, 8), "adas-app-publish-once").start();
        } catch (Throwable failure) {
            log("main_failure", failure.getClass().getName() + ":" + failure.getMessage());
            failure.printStackTrace(System.out); System.out.flush(); probe.finish("registered APK startup failure");
            throw new RuntimeException(failure);
        }
    }

    public static void main(String[] args) throws Exception {
        List<byte[]> routes = new ArrayList<>(); byte[] selection = null; int listenSeconds = 8;
        boolean parked = false, validate = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--parked-once": parked = true; break;
                case "--validate-only": validate = true; break;
                case "--route":
                    if (++i == args.length || routes.size() == MAX_ROUTES) throw new IllegalArgumentException("1..8 --route files required");
                    routes.add(read(args[i], MAX_ROUTE_BYTES)); break;
                case "--selection":
                    if (++i == args.length || selection != null) throw new IllegalArgumentException("Exactly one selection required");
                    selection = read(args[i], 65536); break;
                case "--listen-seconds":
                    if (++i == args.length) throw new IllegalArgumentException("Missing seconds");
                    listenSeconds = Integer.parseInt(args[i]); break;
                default: throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
        if (routes.isEmpty() || selection == null || listenSeconds < 1 || listenSeconds > 15 || (!parked && !validate))
            throw new IllegalArgumentException("--parked-once --route FILE [--route FILE...] --selection FILE [--listen-seconds 1..15]; --validate-only never binds");
        log("manifest", "routeChunks=" + routes.size() + ", selectionBytes=" + selection.length + ", listenSeconds=" + listenSeconds);
        if (validate) { log("validated", "No Android context, binding or transaction performed"); return; }
        if (Process.myUid() != 2000) throw new SecurityException("Run only as shell uid 2000");
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Class<?> activity = Class.forName("android.app.ActivityThread");
        Object thread = activity.getMethod("systemMain").invoke(null);
        Context system = (Context) activity.getMethod("getSystemContext").invoke(thread);
        Context shell = new ContextWrapper(system.createPackageContext("com.android.shell", 0)) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public Context getApplicationContext() { return this; }
        };
        ParkedRouteProbe probe = new ParkedRouteProbe(shell);
        final byte[] selected = selection; final int seconds = listenSeconds;
        // A blocked vendor Binder cannot defeat the process deadline. Binder death also drops this unique session.
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(25000); probe.finish("25s watchdog"); Thread.sleep(4500); }
            catch (InterruptedException ignored) { return; }
            log("hard_deadline", "Exit after own-session cleanup attempt; vendor Binder may have stalled");
            Process.killProcess(Process.myPid());
        }, "adas-deadline");
        watchdog.setDaemon(true); watchdog.start();
        try {
            probe.start();
            new Thread(() -> probe.publish(routes, selected, seconds), "adas-publish-once").start();
            Looper.loop();
        } catch (Throwable failure) {
            // Emit synchronously before cleanup can exit the process or obscure the startup cause.
            log("main_failure", failure.getClass().getName() + ":" + failure.getMessage());
            failure.printStackTrace(System.out);
            System.out.flush();
            throw new RuntimeException("Parked route probe main failed", failure);
        } finally { probe.finish("main exit"); }
    }

    private void start() {
        passive = new AdasPassiveClient(context, new AdasPassiveClient.Sink() {
            public boolean enqueue(Runnable task) {
                if (closed.get()) return false;
                try { events.execute(task); return true; }
                catch (RuntimeException full) { log("listener_queue_rejected", full.getClass().getSimpleName()); return false; }
            }
            public void state(String event, String detail) {
                log("listener_" + event, detail);
                if ("subscribe".equals(event)) {
                    int separator = detail.lastIndexOf(':');
                    if (separator > 2 && "0".equals(detail.substring(separator + 1))) {
                        try {
                            long topic = Long.parseLong(detail.substring(2, separator), 16);
                            for (int i = 0; i < REQUIRED_ANSWERS.length; i++) if (topic == REQUIRED_ANSWERS[i]) {
                                final int bit = 1 << i;
                                int mask = responseMask.updateAndGet(previous -> previous | bit);
                                if (mask == 31) observerReady.countDown();
                            }
                        } catch (NumberFormatException malformed) { log("subscribe_detail_rejected", detail); }
                    }
                }
            }
            public void event(long topic, long timestamp, long receivedAt, byte[] payload) {
                int n = eventCount.incrementAndGet();
                if (n <= 256) log("callback", "topic=" + hex(topic) + ", timestamp=" + timestamp + ", receivedAt=" + receivedAt
                        + ", bytes=" + payload.length + ", base64=" + Base64.encodeToString(payload, Base64.NO_WRAP));
                else if (n == 257) log("callback_budget", "Further payloads omitted");
            }
        });
        passive.start();
        bound = context.bindService(HudSomeIp.bindIntent(session), connection, Context.BIND_AUTO_CREATE);
        log("server_binding", session + ", accepted=" + bound);
        if (!bound) connected.countDown();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) { remote = binder; connected.countDown(); log("server_connected", session); }
        public void onServiceDisconnected(ComponentName name) { remote = null; log("server_disconnected", session); }
        public void onNullBinding(ComponentName name) { connected.countDown(); log("server_null_binding", session); }
        public void onBindingDied(ComponentName name) { remote = null; connected.countDown(); finish("binding died"); }
    };

    private void publish(List<byte[]> routes, byte[] selected, int seconds) {
        try {
            if (!connected.await(5, TimeUnit.SECONDS) || remote == null) throw new IllegalStateException("No server binding");
            // Let the independent read-only observer finish registering before the only publication.
            if (!observerReady.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Missing required ADAS response subscriptions mask=" + responseMask.get() + "; no publication");
            if (closed.get()) return;
            routeAttempted = true; int route = service(4, ROUTE_SERVICE); log("start", hex(ROUTE_SERVICE) + ":" + route);
            if (route != 0 && route != 13) throw new IllegalStateException("Route service rejected");
            if (closed.get()) return;
            selectAttempted = true; int select = service(4, SELECT_SERVICE); log("start", hex(SELECT_SERVICE) + ":" + select);
            if (select != 0 && select != 13) throw new IllegalStateException("Selection service rejected");
            for (int i = 0; i < routes.size(); i++) {
                if (closed.get()) return;
                int result = fire(ROUTE_TOPIC, routes.get(i)); log("route_fire", "chunk=" + i + ", result=" + result);
                if (result != 0) throw new IllegalStateException("Route fire rejected; selection not sent");
            }
            if (closed.get()) return;
            int result = fire(SELECT_TOPIC, selected); log("selection_fire", "result=" + result);
            if (result != 0) throw new IllegalStateException("Selection fire rejected");
            Thread.sleep(seconds * 1000L);
        } catch (Exception failure) { log("probe_error", failure.getClass().getSimpleName() + ":" + failure.getMessage()); }
        finally { finish("publication/listen complete"); }
    }

    private int fire(long topic, byte[] payload) {
        if (topic != ROUTE_TOPIC && topic != SELECT_TOPIC) throw new IllegalArgumentException("Topic outside allowlist");
        return HudSomeIp.fire(remote, topic, payload, "DenzaParkedRouteProbe");
    }
    private int service(int transaction, long id) throws Exception {
        if ((transaction != 4 && transaction != 5) || (id != ROUTE_SERVICE && id != SELECT_SERVICE))
            throw new IllegalArgumentException("Service transaction outside allowlist");
        IBinder binder = remote; if (binder == null) return -100;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("ts.car.someip.sdk.ISomeIpServerInterface"); data.writeLong(id);
            if (!binder.transact(transaction, data, reply, 0)) return -201;
            reply.readException(); return reply.readInt();
        } finally { reply.recycle(); data.recycle(); }
    }
    private void finish(String why) {
        if (!closed.compareAndSet(false, true)) return;
        log("closing", why);
        new Thread(() -> {
            try {
                if (selectAttempted) try { log("stop", hex(SELECT_SERVICE) + ":" + service(5, SELECT_SERVICE)); } catch (Exception e) { log("stop_error", e.toString()); }
                if (routeAttempted) try { log("stop", hex(ROUTE_SERVICE) + ":" + service(5, ROUTE_SERVICE)); } catch (Exception e) { log("stop_error", e.toString()); }
                // Queue cleanup behind the bounded callback/setup worker, preserving client list ownership.
                events.getQueue().clear();
                events.execute(() -> { if (passive != null) passive.close(); main.post(() -> {
                    if (bound) try { context.unbindService(connection); } catch (RuntimeException e) { log("unbind_error", e.toString()); }
                    bound = false; remote = null; log("closed", "Own services stopped/unbound; no route clear or switches sent");
                    events.shutdown(); System.exit(0);
                }); });
            } catch (RuntimeException e) { log("cleanup_error", e.toString()); }
        }, "adas-cleanup").start();
    }
    private static byte[] read(String path, int max) throws Exception {
        File file = new File(path); long size = file.length();
        if (!file.isFile() || size < 1 || size > max) throw new IllegalArgumentException("Payload size outside 1.." + max + ":" + path);
        byte[] bytes = new byte[(int) size];
        try (FileInputStream in = new FileInputStream(file)) {
            int at = 0, count; while (at < bytes.length && (count = in.read(bytes, at, bytes.length - at)) != -1) at += count;
            if (at != bytes.length || in.read() != -1) throw new IllegalArgumentException("Payload changed while reading");
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder sha = new StringBuilder();
        for (byte b : digest) sha.append(String.format("%02x", b & 255));
        log("payload", "path=" + file.getCanonicalPath() + ", bytes=" + bytes.length + ", sha256=" + sha);
        return bytes;
    }
    private static String hex(long value) { return String.format("0x%016x", value); }
    private static synchronized void log(String event, String detail) {
        System.out.println("{\"event\":\"" + escape(event) + "\",\"detail\":\"" + escape(detail) + "\"}"); System.out.flush();
    }
    private static String escape(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"); }
}
