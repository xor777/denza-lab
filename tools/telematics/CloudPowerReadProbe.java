package dev.denza.tools;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Twelve-second read-only POWER getter/listener check. No setters or cloud calls. */
public final class CloudPowerReadProbe {
    private static final int DEVICE = 1005, ACC = 0x99000037, MCU = 0x99000003;
    private static final AtomicBoolean done = new AtomicBoolean();
    private static final AtomicInteger events = new AtomicInteger(), errors = new AtomicInteger();
    private static Object power, listener;
    private static Class<?> listenerType;

    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("no_arguments");
        Thread timeout = new Thread(() -> {
            try { Thread.sleep(20_000); } catch (InterruptedException ignored) { return; }
            out("TIMEOUT"); Runtime.getRuntime().halt(74);
        }, "power-probe-timeout");
        timeout.setDaemon(true);
        timeout.start();
        try {
            Looper.prepareMainLooper();
            Class<?> vmClass = Class.forName("dalvik.system.VMRuntime");
            Object vm = vmClass.getMethod("getRuntime").invoke(null);
            vmClass.getMethod("setHiddenApiExemptions", String[].class)
                .invoke(vm, (Object)new String[]{"L"});
            Class<?> activity = Class.forName("android.app.ActivityThread");
            Object thread = activity.getMethod("systemMain").invoke(null);
            Context context = (Context)activity.getMethod("getSystemContext").invoke(thread);
            Class<?> powerType = Class.forName("android.hardware.bydauto.power.BYDAutoPowerDevice");
            power = powerType.getMethod("getInstance", Context.class).invoke(null, context);
            listenerType = Class.forName("android.hardware.IBYDAutoListener");
            Class<?> eventType = Class.forName("android.hardware.IBYDAutoEvent");
            Method getDevice = eventType.getMethod("getDeviceType");
            Method getEvent = eventType.getMethod("getEventType");
            Method getValue = eventType.getMethod("getValue");
            listener = Proxy.newProxyInstance(CloudPowerReadProbe.class.getClassLoader(),
                new Class<?>[]{listenerType}, (proxy, method, values) -> {
                    String name = method.getName();
                    if (name.equals("onDataChanged")) {
                        try {
                            Object event = values[0];
                            int device = (Integer)getDevice.invoke(event);
                            int fid = (Integer)getEvent.invoke(event);
                            if (device == DEVICE && (fid == ACC || fid == MCU)) {
                                int value = (Integer)getValue.invoke(event);
                                events.incrementAndGet();
                                out("EVENT fid=" + Integer.toHexString(fid) + " value=" + value);
                            } else errors.incrementAndGet();
                        } catch (Exception e) { errors.incrementAndGet(); }
                    } else if (name.equals("onError")) errors.incrementAndGet();
                    else if (name.equals("hashCode")) return System.identityHashCode(proxy);
                    else if (name.equals("equals")) return values != null && values.length == 1 && proxy == values[0];
                    else if (name.equals("toString")) return "CloudPowerReadProbeListener";
                    return null;
                });
            sample();
            powerType.getMethod("registerListener", listenerType, int[].class)
                .invoke(power, listener, new int[]{ACC, MCU});
            out("REGISTERED");
            Handler handler = new Handler(Looper.getMainLooper());
            for (int i = 1; i <= 5; i++) handler.postDelayed(() -> {
                try { sample(); } catch (Exception e) { errors.incrementAndGet(); finish(); }
            }, i * 2_000L);
            handler.postDelayed(CloudPowerReadProbe::finish, 12_000);
            Looper.loop();
        } catch (Exception e) { errors.incrementAndGet(); out("FAIL type=" + e.getClass().getSimpleName()); finish(); }
    }

    private static int get(int fid) throws Exception {
        IBinder service = (IBinder)Class.forName("android.os.ServiceManager")
            .getMethod("getService", String.class).invoke(null, "autoservice");
        if (service == null) throw new IllegalStateException("autoservice_missing");
        Parcel query = Parcel.obtain(), reply = Parcel.obtain();
        try {
            query.writeInterfaceToken(service.getInterfaceDescriptor());
            query.writeInt(DEVICE); query.writeInt(fid);
            if (!service.transact(5, query, reply, 0) || reply.readInt() != 0)
                throw new IllegalStateException("getter_failed");
            return reply.readInt();
        } finally { query.recycle(); reply.recycle(); }
    }
    private static void sample() throws Exception { out("READ acc=" + get(ACC) + " mcu=" + get(MCU)); }
    private static void finish() {
        if (!done.compareAndSet(false, true)) return;
        boolean clean = false;
        try {
            if (power != null && listener != null) {
                power.getClass().getMethod("unregisterListener", listenerType).invoke(power, listener);
                clean = true; out("UNREGISTERED");
            }
        } catch (Exception e) { errors.incrementAndGet(); }
        out("DONE events=" + events.get() + " errors=" + errors.get() + " cleanup=" + clean);
        System.exit(clean && errors.get() == 0 ? 0 : 1);
    }
    private static void out(String value) {
        System.out.println("POWER_PROBE t=" + SystemClock.elapsedRealtime() + " " + value);
        System.out.flush();
    }
}
