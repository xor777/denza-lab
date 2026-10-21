package dev.denza.tools;

import android.content.Context;
import android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import dev.denza.apps.platform.shell.ShellProxyBootstrap;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Finite shell-UID scalar listener. No CAN table writes, setters, UDS or APK install. */
public final class WasherWarningEventProbe extends AbsBYDAutoInstrumentListener {
    private static final int TEXT_INFO = 0x26100008;
    private final AtomicBoolean finishing = new AtomicBoolean();
    private final AtomicLong events = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong doorEvents = new AtomicLong();
    private boolean doorControl;
    private Object device;
    private Method getter;
    private boolean registered;

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2 || (args.length == 2 && !"doors".equals(args[1])))
            throw new IllegalArgumentException("duration [doors]");
        int seconds = Integer.parseInt(args[0]);
        if (seconds < 10 || seconds > 120) throw new IllegalArgumentException("duration: 10..120");
        WasherWarningEventProbe probe = new WasherWarningEventProbe();
        probe.doorControl = args.length == 2;
        // Independent of the main looper and any blocked framework call.
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep((seconds + 20L) * 1000L); }
            catch (InterruptedException ignored) { return; }
            // Avoid stdout locks on the hard-exit path. Missing DONE is failure.
            Runtime.getRuntime().halt(124);
        }, "washer-warning-deadline");
        watchdog.setDaemon(true);
        watchdog.start();
        probe.run(seconds);
    }

    private void run(int seconds) {
        out("START uid=" + Process.myUid() + " duration_s=" + seconds);
        try {
            if (Process.myUid() != 2000) throw new IllegalStateException("shell UID required");
            ShellProxyBootstrap.exemptHiddenApis();
            ShellProxyBootstrap.prepareMainLooper();
            Context context = ShellProxyBootstrap.systemContext();
            Class<?> type = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
            device = type.getMethod("getInstance", Context.class).invoke(null, context);
            getter = type.getMethod("getTextInfo");
            snapshot("before_register");
            type.getMethod("registerListener", AbsBYDAutoInstrumentListener.class, int[].class)
                    .invoke(device, this, doorControl
                            ? new int[]{TEXT_INFO, 0x26100014, 0x26100015, 0x26100016, 0x26100017}
                            : new int[]{TEXT_INFO});
            registered = true;
            out("REGISTER_RETURN fid=0x26100008 t_ns=" + SystemClock.elapsedRealtimeNanos());
            if (doorControl) doorSnapshot("after_register");
            out("READY doors=" + doorControl + " t_ns=" + SystemClock.elapsedRealtimeNanos());
            Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(this::finish, seconds * 1000L);
            handler.post(new Runnable() {
                @Override public void run() {
                    if (finishing.get()) return;
                    out("HEARTBEAT t_ns=" + SystemClock.elapsedRealtimeNanos());
                    handler.postDelayed(this, 5000L);
                }
            });
            Looper.loop();
        } catch (Throwable error) {
            errors.incrementAndGet();
            out("FAIL " + describe(error));
            finish();
        }
    }

    @Override public void onTextInfoChanged(int value) {
        if (finishing.get()) return;
        out(String.format(Locale.US, "EVENT seq=%d t_ns=%d fid=0x26100008 value=%d hex=0x%X",
                events.incrementAndGet(), SystemClock.elapsedRealtimeNanos(), value, value));
        if (value < 0 || value > 1023) errors.incrementAndGet();
    }

    @Override public void onError(int code, String message) {
        errors.incrementAndGet();
        out("LISTENER_ERROR code=" + code); // No arbitrary vendor text in the capture.
    }

    @Override public void onDoorStateChanged(int area, int state) {
        if (finishing.get()) return;
        out("DOOR_EVENT seq=" + doorEvents.incrementAndGet() + " t_ns="
                + SystemClock.elapsedRealtimeNanos() + " area=" + area + " state=" + state);
        if (!doorControl || area < 1 || area > 4 || state < 0 || state > 1) errors.incrementAndGet();
        try { snapshot("door_callback"); }
        catch (Throwable error) { errors.incrementAndGet(); out("GET_FAIL " + describe(error)); }
    }

    private void doorSnapshot(String phase) throws Exception {
        Method method = device.getClass().getMethod("getDoorState", int.class);
        for (int area = 1; area <= 4; area++) {
            int value = (Integer) method.invoke(device, area);
            out("DOOR_GET phase=" + phase + " t_ns=" + SystemClock.elapsedRealtimeNanos()
                    + " area=" + area + " state=" + value);
            if (value < 0 || value > 1) throw new IllegalStateException("invalid door state");
        }
    }

    private void snapshot(String phase) throws Exception {
        int value = (Integer) getter.invoke(device);
        out(String.format(Locale.US, "GET phase=%s t_ns=%d value=%d hex=0x%X",
                phase, SystemClock.elapsedRealtimeNanos(), value, value));
        if (value < 0 || value > 1023) throw new IllegalStateException("invalid warning GET");
    }

    private void finish() {
        if (!finishing.compareAndSet(false, true)) return;
        if (device != null) {
            try {
                snapshot("before_unregister");
                if (doorControl) doorSnapshot("before_unregister");
            }
            catch (Throwable error) { errors.incrementAndGet(); out("GET_FAIL " + describe(error)); }
            try {
                device.getClass().getMethod("unregisterListener", AbsBYDAutoInstrumentListener.class)
                        .invoke(device, this);
                out("UNREGISTERED");
            } catch (Throwable error) {
                errors.incrementAndGet(); out("UNREGISTER_FAIL " + describe(error));
            }
        }
        out("DOOR_DONE events=" + doorEvents.get());
        out("DONE registered=" + registered + " events=" + events.get() + " errors=" + errors.get());
        System.exit(registered && errors.get() == 0 ? 0 : 1);
    }

    private static String describe(Throwable error) {
        while (error.getCause() != null && error.getCause() != error) error = error.getCause();
        return error.toString().replace('\n', ' ').replace('\r', ' ');
    }

    private static void out(String message) {
        System.out.println("[WasherWarningEventProbe] " + message);
    }
}
