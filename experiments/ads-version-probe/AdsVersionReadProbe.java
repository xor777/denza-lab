package dev.denza.adsversion.probe;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import java.io.RandomAccessFile;
import java.lang.reflect.Proxy;
import java.nio.channels.FileLock;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** One allowlisted OEM ADS identity read; no arbitrary address/payload, retries or session changes. */
public final class AdsVersionReadProbe {
    private static final int RX = 0x9900010A, TX = 0xAA00010A;
    private static final String IDLE = "r@-1_0&w@-1_0";
    private static final AtomicBoolean done = new AtomicBoolean();
    private static Object device, listener, auto;
    private static Class<?> listenerClass;
    private static RandomAccessFile lockFile;
    private static FileLock lock;
    private static Handler handler;
    private static boolean sent;
    private static int did, frames;

    private static void out(String line) { System.out.println(line); System.out.flush(); }
    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte b : value) result.append(String.format(Locale.ROOT, "%02X", b & 255));
        return result.toString();
    }
    private static String prop(String name) throws Exception {
        return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, name);
    }
    private static void finish(String reason, int result) {
        if (!done.compareAndSet(false, true)) return;
        out("END " + reason + " frames=" + frames);
        try {
            if (device != null && listener != null) {
                device.getClass().getMethod("unregisterListener", listenerClass).invoke(device, listener);
                out("UNREGISTERED");
            }
        } catch (Throwable error) { out("UNREGISTER_ERROR " + error.getClass().getSimpleName()); }
        try { if (lock != null) lock.release(); if (lockFile != null) lockFile.close(); }
        catch (Exception ignored) { }
        System.exit(result);
    }
    private static boolean stationary() throws Exception {
        Class<?> type = auto.getClass();
        int gear = (Integer) type.getMethod("getInt", int.class, int.class).invoke(auto, 1011, 555745336);
        int parkBrake = (Integer) type.getMethod("getInt", int.class, int.class).invoke(auto, 1011, 89129008);
        float speed = ((Number) type.getMethod("getDouble", int.class, int.class).invoke(auto, 1013, -1807745016)).floatValue();
        out("STATIONARY gear=" + gear + " park_brake=" + parkBrake + " speed=" + speed);
        return gear == 1 && parkBrake == 1 && speed == 0f;
    }
    private static void received(byte[] buffer) {
        if (done.get()) return;
        frames++;
        if (!sent) { finish("PREEXISTING_TRAFFIC_NO_REQUEST", 2); return; }
        if (buffer == null || buffer.length < 7 || buffer.length > 1024 || frames > 16) {
            finish("INVALID_ENVELOPE", 2); return;
        }
        int length = buffer[3] & 255;
        int total = buffer[4] & 255, index = buffer[5] & 255;
        int address = ((buffer[1] & 255) << 8) | (buffer[2] & 255);
        if (buffer[0] != 5 || address != 0x71A) { finish("FOREIGN_TRAFFIC", 2); return; }
        if (total != 1 || index != 1 || length > buffer.length - 7) {
            // Do not guess multi-frame layout. Preserve only header, not unrelated trailing bytes.
            out("HEADER " + hex(Arrays.copyOf(buffer, 7)));
            finish("UNSUPPORTED_FRAGMENTATION_OR_LENGTH", 2); return;
        }
        byte[] payload = Arrays.copyOfRange(buffer, 7, 7 + length);
        out("RESPONSE status=" + (buffer[6] & 255) + " payload=" + hex(payload));
        if (buffer[6] != 1 || length < 3 || payload[0] != 0x62
                || (payload[1] & 255) != (did >> 8) || (payload[2] & 255) != (did & 255)) {
            finish("NONPOSITIVE_OR_MISMATCHED_RESPONSE", 2); return;
        }
        out("VALUE did=" + String.format(Locale.ROOT, "%04X", did)
                + " bytes=" + hex(Arrays.copyOfRange(payload, 3, payload.length)));
        finish("COMPLETE", 0);
    }
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("One fixed version name required");
        switch (args[0]) {
            case "software": did = 0xF195; break;
            case "hardware": did = 0xF193; break;
            // ADS-specific DIDs supplied by the authenticated OEM getEcuList
            // response, 2026-10-18. Config identifiers, not configuration writes.
            case "software-code": did = 0xF194; break;
            case "config-version": did = 0xFF95; break;
            case "config-code": did = 0xFF94; break;
            case "soc-app": did = 0x01A0; break;
            case "soc": did = 0x0178; break;
            case "mcu": did = 0x0170; break;
            case "mcu-app": did = 0x0172; break;
            case "bootloader": did = 0xF180; break;
            default: throw new IllegalArgumentException("Unknown version name");
        }
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(15000); } catch (InterruptedException ignored) { return; }
            out("HARD_TIMEOUT"); System.exit(3);
        }, "ads-version-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            if (Process.myUid() != 2000) { finish("NOT_SHELL", 2); return; }
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            vm.getMethod("setHiddenApiExemptions", String[].class).invoke(
                    vm.getMethod("getRuntime").invoke(null), (Object) new String[]{"L"});
            if (!"170".equals(prop("persist.sys.AutoType")) || !IDLE.equals(prop("sys.diagmgrsrv.task_bk"))) {
                finish("PROFILE_OR_MANAGER_NOT_BASELINE", 2); return;
            }
            // Cooperates with the existing app's DTC reader; leaves its lock inode in place.
            lockFile = new RandomAccessFile("/data/local/tmp/denza-dtc-read.lock", "rw");
            lock = lockFile.getChannel().tryLock();
            if (lock == null) { finish("OTHER_READER_ACTIVE", 2); return; }
            Looper.prepareMainLooper();
            Class<?> at = Class.forName("android.app.ActivityThread");
            Context context = (Context) at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
            auto = context.getSystemService("auto");
            if (auto == null || !stationary()) { finish("NOT_STATIONARY", 2); return; }
            handler = new Handler(Looper.getMainLooper());
            Class<?> dc = Class.forName("android.hardware.bydauto.mqtt.BYDAutoMqttDevice");
            Class<?> valueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            listenerClass = Class.forName("android.hardware.IBYDAutoListener");
            device = dc.getMethod("getInstance", Context.class).invoke(null, context);
            listener = Proxy.newProxyInstance(AdsVersionReadProbe.class.getClassLoader(), new Class<?>[]{listenerClass}, (proxy, method, values) -> {
                if (method.getName().equals("onDataEventChanged") && ((Integer) values[0]) == RX) {
                    byte[] bytes = (byte[]) valueClass.getField("bufferDataValue").get(values[1]);
                    byte[] copy = bytes == null ? null : bytes.clone();
                    handler.post(() -> received(copy));
                } else if (method.getName().equals("onError")) {
                    handler.post(() -> finish("LISTENER_ERROR", 2));
                } else if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                else if (method.getName().equals("equals")) return proxy == values[0];
                else if (method.getName().equals("toString")) return "AdsVersionReadProbeListener";
                return null;
            });
            dc.getMethod("registerListener", listenerClass, int[].class).invoke(device, listener, new int[]{RX});
            out("REGISTERED passive_ms=2000 version=" + args[0]);
            handler.postDelayed(() -> {
                try {
                    if (done.get()) return;
                    if (frames != 0 || !IDLE.equals(prop("sys.diagmgrsrv.task_bk")) || !stationary()) {
                        finish("PREFLIGHT_CHANGED_NO_REQUEST", 2); return;
                    }
                    byte[] payload = {5, 7, 0x12, 3, 0x22, (byte)(did >> 8), (byte)did};
                    Object value = valueClass.getConstructor().newInstance();
                    valueClass.getField("bufferDataValue").set(value, payload);
                    sent = true;
                    out("REQUEST " + hex(payload));
                    Object rc = dc.getMethod("set", int[].class, valueClass).invoke(device, new int[]{TX}, value);
                    out("TRANSPORT_RESULT " + rc);
                    if (!Integer.valueOf(0).equals(rc)) finish("TRANSPORT_REJECTED_NO_RETRY", 2);
                } catch (Throwable error) { out("SEND_ERROR " + error); finish("NO_RETRY", 2); }
            }, 2000);
            handler.postDelayed(() -> finish("RESPONSE_TIMEOUT_NO_RETRY", 2), 10000);
            Looper.loop();
        } catch (Throwable error) { out("FAILED " + error); finish("NO_RETRY", 2); }
    }
}
