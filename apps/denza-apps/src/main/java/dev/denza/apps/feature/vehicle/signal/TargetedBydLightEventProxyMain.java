package dev.denza.apps.feature.vehicle.signal;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Narrow shell-UID source for two read-only BYD light events.
 *
 * <p>This class is packed alone and run by {@code app_process} on a dedicated ADB stream. It has
 * no setter and accepts only {@code next <timeout-ms>}. The Binder callback copies five scalars to
 * a bounded queue and returns; parsing and feature work remain in the Denza Apps process.
 */
@SuppressLint({"PrivateApi", "BlockedPrivateApi"})
public final class TargetedBydLightEventProxyMain {
    private static final int TURN_SWITCH_FID = 0x1330002C;
    private static final int TURN_MODE_FID = 0x38A0002C;
    private static final int WATCH_SWITCH = 1;
    private static final int WATCH_MODE = 2;
    private static final int MAX_QUEUE_SIZE = 32;
    private static final int MAX_WAIT_MS = 10_000;
    private static final int SNAPSHOT_TIMEOUT_MS = 1_000;

    private TargetedBydLightEventProxyMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3
                || !"serve".equals(args[0])
                || !args[1].matches("[0-9a-f]+")
                || !args[2].matches("[1-3]")) {
            throw new IllegalArgumentException("serve <hex-nonce> <signal-mask> required");
        }
        String nonce = args[1];
        int signalMask = Integer.parseInt(args[2]);
        boolean watchSwitch = (signalMask & WATCH_SWITCH) != 0;
        boolean watchMode = (signalMask & WATCH_MODE) != 0;
        prepareMainLooper();
        exemptHiddenApis();
        Context context = systemContext();
        QueueState queue = new QueueState();
        AtomicBoolean finishing = new AtomicBoolean();

        Class<?> deviceClass = Class.forName("android.hardware.bydauto.light.BYDAutoLightDevice");
        Class<?> listenerClass = Class.forName("android.hardware.IBYDAutoListener");
        Class<?> eventClass = Class.forName("android.hardware.IBYDAutoEvent");
        Method getDeviceType = eventClass.getMethod("getDeviceType");
        Method getEventType = eventClass.getMethod("getEventType");
        Method getValue = eventClass.getMethod("getValue");
        Object device = deviceClass.getMethod("getInstance", Context.class).invoke(null, context);

        InvocationHandler callback = (proxy, method, methodArgs) -> {
            String name = method.getName();
            if ("onDataChanged".equals(name)
                    && methodArgs != null
                    && methodArgs.length > 0
                    && methodArgs[0] != null) {
                try {
                    Object event = methodArgs[0];
                    int deviceType = (Integer) getDeviceType.invoke(event);
                    int eventType = (Integer) getEventType.invoke(event);
                    if (deviceType == 1004
                            && ((watchSwitch && eventType == TURN_SWITCH_FID)
                            || (watchMode && eventType == TURN_MODE_FID))) {
                        queue.event("E", eventType, (Integer) getValue.invoke(event));
                    }
                } catch (Exception error) {
                    queue.failure();
                }
                return null;
            }
            if ("onDataEventChanged".equals(name)) return null;
            if ("onError".equals(name)) {
                queue.failure();
                return null;
            }
            if ("toString".equals(name)) return "DenzaTargetedLightListener";
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name)) {
                return methodArgs != null && methodArgs.length == 1 && proxy == methodArgs[0];
            }
            return defaultValue(method.getReturnType());
        };
        Object listener = Proxy.newProxyInstance(
                TargetedBydLightEventProxyMain.class.getClassLoader(),
                new Class<?>[]{listenerClass},
                callback);
        int[] requestedFids;
        if (watchSwitch && watchMode) {
            requestedFids = new int[]{TURN_SWITCH_FID, TURN_MODE_FID};
        } else if (watchSwitch) {
            requestedFids = new int[]{TURN_SWITCH_FID};
        } else {
            requestedFids = new int[]{TURN_MODE_FID};
        }
        deviceClass.getMethod("registerListener", listenerClass, int[].class).invoke(
                device, listener, requestedFids);

        // Listener delivery is posted to the main Looper. These synchronous snapshots therefore
        // enter the queue before any later edge, closing the register/read race without guessing
        // that the current state is OFF.
        boolean initialSnapshotHealthy = true;
        if (watchSwitch) {
            initialSnapshotHealthy &= snapshot(
                    queue, deviceClass, device, "getTurnLightState", TURN_SWITCH_FID);
        }
        if (watchMode) {
            initialSnapshotHealthy &= snapshot(
                    queue, deviceClass, device, "getTurnLightFlashState", TURN_MODE_FID);
        }
        if (!initialSnapshotHealthy) {
            unregister(device, listenerClass, listener);
            throw new IllegalStateException("initial turn-signal snapshot failed");
        }

        Runnable refresh = () -> {
            if (watchSwitch) {
                snapshot(queue, deviceClass, device, "getTurnLightState", TURN_SWITCH_FID);
            }
            if (watchMode) {
                snapshot(queue, deviceClass, device, "getTurnLightFlashState", TURN_MODE_FID);
            }
        };
        Handler main = new Handler(Looper.getMainLooper());
        Runnable finish = () -> finish(device, listenerClass, listener, finishing);
        startRequestReader(nonce, queue, main, refresh, finish, finishing);
        System.out.println("DENZA_SERVE_" + nonce + ":READY");
        System.out.flush();
        Looper.loop();
    }

    private static boolean snapshot(
            QueueState queue,
            Class<?> deviceClass,
            Object device,
            String getter,
            int fid) {
        try {
            int value = (Integer) deviceClass.getMethod(getter).invoke(device);
            queue.event("S", fid, value);
            return true;
        } catch (Exception error) {
            queue.failure();
            return false;
        }
    }

    private static void startRequestReader(
            String nonce,
            QueueState queue,
            Handler main,
            Runnable refresh,
            Runnable finish,
            AtomicBoolean finishing) {
        Thread requests = new Thread(() -> {
            String begin = "DENZA_SERVE_" + nonce + ":BEGIN";
            String end = "DENZA_SERVE_" + nonce + ":END";
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                String line;
                while (!finishing.get() && (line = reader.readLine()) != null) {
                    String request = line.trim();
                    if (request.isEmpty()) continue;
                    if ("quit".equals(request)) {
                        main.post(finish);
                        return;
                    }
                    String failure = null;
                    List<String> answer = null;
                    try {
                        String[] words = request.split(" +");
                        if (words.length != 2 || !"next".equals(words[0])) {
                            throw new IllegalArgumentException("next <timeout-ms> required");
                        }
                        int timeoutMs = Integer.parseInt(words[1]);
                        if (timeoutMs < 1 || timeoutMs > MAX_WAIT_MS) {
                            throw new IllegalArgumentException("timeout outside 1..10000 ms");
                        }
                        answer = queue.next(timeoutMs);
                        if (answer.isEmpty()) {
                            refreshOnMain(main, refresh, queue);
                            answer = queue.drain();
                        }
                        if (answer.isEmpty()) {
                            answer.add("H " + SystemClock.elapsedRealtimeNanos());
                        }
                    } catch (Exception error) {
                        failure = describe(error);
                    }
                    System.out.println(begin);
                    if (failure == null) {
                        for (String record : answer) System.out.println(record);
                        System.out.println(end + " ok");
                    } else {
                        System.out.println(end + " err " + oneLine(failure));
                    }
                    System.out.flush();
                }
            } catch (Exception error) {
                queue.failure();
            } finally {
                main.post(finish);
            }
        }, "denza-turn-signal-requests");
        requests.setDaemon(true);
        requests.start();
    }

    /** A timeout re-reads demanded values; channel liveness alone never certifies retained data. */
    private static void refreshOnMain(Handler main, Runnable refresh, QueueState queue)
            throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        if (!main.post(() -> {
            try {
                refresh.run();
            } finally {
                done.countDown();
            }
        })) {
            queue.failure();
            return;
        }
        if (!done.await(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            queue.failure();
        }
    }

    private static void finish(
            Object device,
            Class<?> listenerClass,
            Object listener,
            AtomicBoolean finishing) {
        if (!finishing.compareAndSet(false, true)) return;
        unregister(device, listenerClass, listener);
        System.exit(0);
    }

    private static void unregister(
            Object device,
            Class<?> listenerClass,
            Object listener) {
        try {
            device.getClass().getMethod("unregisterListener", listenerClass).invoke(device, listener);
        } catch (Exception ignored) {
            // Closing the owned ADB stream still tears the process down. There is no retry loop
            // here that could keep a broken listener or the vehicle service busy.
        }
    }

    private static final class QueueState {
        private final Object lock = new Object();
        private final ArrayDeque<Record> records = new ArrayDeque<>();
        private long sequence;
        private long dropped;
        private boolean failed;

        void event(String kind, int fid, int value) {
            long observedAtNanos = SystemClock.elapsedRealtimeNanos();
            synchronized (lock) {
                if (records.size() >= MAX_QUEUE_SIZE) {
                    dropped += 1L;
                } else {
                    sequence += 1L;
                    records.addLast(new Record(kind, sequence, observedAtNanos, fid, value));
                }
                lock.notifyAll();
            }
        }

        void failure() {
            synchronized (lock) {
                failed = true;
                lock.notifyAll();
            }
        }

        List<String> next(int timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            synchronized (lock) {
                while (records.isEmpty() && dropped == 0L && !failed) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) break;
                    long millis = remaining / 1_000_000L;
                    int nanos = (int) (remaining % 1_000_000L);
                    lock.wait(millis, nanos);
                }
                return drainLocked();
            }
        }

        List<String> drain() {
            synchronized (lock) {
                return drainLocked();
            }
        }

        private ArrayList<String> drainLocked() {
            ArrayList<String> answer = new ArrayList<>();
            if (failed) {
                failed = false;
                records.clear();
                answer.add("X " + SystemClock.elapsedRealtimeNanos());
            } else if (dropped > 0L) {
                long count = dropped;
                dropped = 0L;
                records.clear();
                answer.add("O " + count + " " + SystemClock.elapsedRealtimeNanos());
            } else {
                while (!records.isEmpty()) answer.add(records.removeFirst().format());
            }
            return answer;
        }
    }

    private static final class Record {
        private final String kind;
        private final long sequence;
        private final long observedAtNanos;
        private final int fid;
        private final int value;

        Record(String kind, long sequence, long observedAtNanos, int fid, int value) {
            this.kind = kind;
            this.sequence = sequence;
            this.observedAtNanos = observedAtNanos;
            this.fid = fid;
            this.value = value;
        }

        String format() {
            return kind + " " + sequence + " " + observedAtNanos + " " + fid + " " + value;
        }
    }

    private static void prepareMainLooper() {
        try {
            Looper.prepareMainLooper();
        } catch (IllegalStateException alreadyPrepared) {
            // app_process can prepare it before entering this class.
        }
    }

    private static Context systemContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        return (Context) activityThread.getMethod("getSystemContext").invoke(thread);
    }

    private static void exemptHiddenApis() {
        try {
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vm.getMethod("getRuntime").invoke(null);
            vm.getMethod("setHiddenApiExemptions", String[].class)
                    .invoke(runtime, (Object) new String[]{"L"});
        } catch (Exception ignored) {
            // Registration will fail closed if the framework denies the reflective calls.
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == Void.TYPE) return null;
        if (type == Boolean.TYPE) return false;
        if (type == Character.TYPE) return (char) 0;
        if (type == Byte.TYPE) return (byte) 0;
        if (type == Short.TYPE) return (short) 0;
        if (type == Integer.TYPE) return 0;
        if (type == Long.TYPE) return 0L;
        if (type == Float.TYPE) return 0.0f;
        if (type == Double.TYPE) return 0.0d;
        return null;
    }

    private static String describe(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return String.valueOf(current);
    }

    private static String oneLine(String value) {
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
