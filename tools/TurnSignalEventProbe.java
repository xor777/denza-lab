package dev.denza.tools;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Subscribes only to the two vendor turn-signal FIDs as a shell-UID diagnostic probe.
 *
 * <p>No APK is installed and no setter is available in this program. The process has a hard
 * timeout, unregisters its listener, and accepts {@code MARK label} and {@code STOP} on stdin.
 */
public final class TurnSignalEventProbe {
    private static final int LIGHT_TURN_SIGNAL_LIGHT_SWITCH_STATE = 0x1330002C;
    private static final int LIGHT_TURN_SIGNAL_LIGHT = 0x38A0002C;
    private static final int DEFAULT_DURATION_SECONDS = 90;
    private static final int MAX_DURATION_SECONDS = 120;

    private TurnSignalEventProbe() {
    }

    public static void main(String[] args) {
        int durationSeconds = duration(args);
        AtomicBoolean finishing = new AtomicBoolean();
        AtomicLong events = new AtomicLong();
        AtomicLong errors = new AtomicLong();
        Object device = null;
        Object listener = null;

        out("START uid=" + Process.myUid() + " duration_s=" + durationSeconds);
        exemptHiddenApis();
        try {
            Context context = systemContext();
            Class<?> deviceClass = Class.forName(
                    "android.hardware.bydauto.light.BYDAutoLightDevice");
            Class<?> listenerClass = Class.forName("android.hardware.IBYDAutoListener");
            Class<?> eventClass = Class.forName("android.hardware.IBYDAutoEvent");
            Method getDeviceType = eventClass.getMethod("getDeviceType");
            Method getEventType = eventClass.getMethod("getEventType");
            Method getValue = eventClass.getMethod("getValue");

            device = deviceClass.getMethod("getInstance", Context.class).invoke(null, context);
            InvocationHandler invocationHandler = (proxy, method, methodArgs) -> {
                String name = method.getName();
                if ("onDataChanged".equals(name)
                        && methodArgs != null
                        && methodArgs.length > 0
                        && methodArgs[0] != null) {
                    try {
                        Object event = methodArgs[0];
                        int deviceType = (Integer) getDeviceType.invoke(event);
                        int eventType = (Integer) getEventType.invoke(event);
                        if (eventType == LIGHT_TURN_SIGNAL_LIGHT_SWITCH_STATE
                                || eventType == LIGHT_TURN_SIGNAL_LIGHT) {
                            long sequence = events.incrementAndGet();
                            int value = (Integer) getValue.invoke(event);
                            out(String.format(
                                    Locale.US,
                                    "EVENT seq=%d t_ns=%d device=%d fid=0x%08X value=%d",
                                    sequence,
                                    SystemClock.elapsedRealtimeNanos(),
                                    deviceType,
                                    eventType,
                                    value));
                        }
                    } catch (Throwable error) {
                        errors.incrementAndGet();
                        out("CALLBACK_FAIL " + describe(error));
                    }
                    return null;
                }
                if ("onDataEventChanged".equals(name)) return null;
                if ("onError".equals(name)) {
                    errors.incrementAndGet();
                    out("LISTENER_ERROR");
                    return null;
                }
                if ("toString".equals(name)) return "TurnSignalEventProbeListener";
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name)) {
                    return methodArgs != null && methodArgs.length == 1 && proxy == methodArgs[0];
                }
                return defaultValue(method.getReturnType());
            };

            listener = Proxy.newProxyInstance(
                    TurnSignalEventProbe.class.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    invocationHandler);
            deviceClass.getMethod("registerListener", listenerClass, int[].class).invoke(
                    device,
                    listener,
                    new int[]{LIGHT_TURN_SIGNAL_LIGHT_SWITCH_STATE, LIGHT_TURN_SIGNAL_LIGHT});

            final Object finalDevice = device;
            final Object finalListener = listener;
            Handler mainHandler = new Handler(Looper.getMainLooper());
            Runnable finish = () -> finish(
                    finalDevice, finalListener, finishing, events, errors);
            startCommandReader(mainHandler, finish, finishing);
            mainHandler.postDelayed(finish, durationSeconds * 1000L);
            out("READY fids=0x1330002C,0x38A0002C t_ns="
                    + SystemClock.elapsedRealtimeNanos());
            Looper.loop();
        } catch (Throwable error) {
            out("FAIL " + describe(error));
            error.printStackTrace(System.out);
            finish(device, listener, finishing, events, errors);
        }
    }

    private static void startCommandReader(
            Handler mainHandler,
            Runnable finish,
            AtomicBoolean finishing) {
        Thread thread = new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                String line;
                while (!finishing.get() && (line = reader.readLine()) != null) {
                    String command = line.trim();
                    if ("STOP".equals(command)) {
                        mainHandler.post(finish);
                        return;
                    }
                    if (command.startsWith("MARK ")) {
                        String label = sanitizeLabel(command.substring(5));
                        if (!label.isEmpty()) {
                            out("MARK t_ns=" + SystemClock.elapsedRealtimeNanos()
                                    + " label=" + label);
                        }
                    }
                }
            } catch (Throwable error) {
                out("COMMAND_READER_FAIL " + describe(error));
            }
        }, "turn-signal-command-reader");
        thread.setDaemon(true);
        thread.start();
    }

    private static void finish(
            Object device,
            Object listener,
            AtomicBoolean finishing,
            AtomicLong events,
            AtomicLong errors) {
        if (!finishing.compareAndSet(false, true)) return;
        if (device != null && listener != null) {
            try {
                Class<?> listenerClass = Class.forName("android.hardware.IBYDAutoListener");
                device.getClass().getMethod("unregisterListener", listenerClass)
                        .invoke(device, listener);
                out("UNREGISTERED");
            } catch (Throwable error) {
                out("UNREGISTER_FAIL " + describe(error));
            }
        }
        out("DONE events=" + events.get() + " errors=" + errors.get());
        System.exit(0);
    }

    private static int duration(String[] args) {
        if (args.length == 0) return DEFAULT_DURATION_SECONDS;
        int value = Integer.parseInt(args[0]);
        if (value < 10 || value > MAX_DURATION_SECONDS) {
            throw new IllegalArgumentException("durationSeconds must be between 10 and 120");
        }
        return value;
    }

    private static Context systemContext() throws Exception {
        try {
            Looper.prepareMainLooper();
        } catch (IllegalStateException alreadyPrepared) {
            // app_process may already have prepared it.
        }
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
        } catch (Throwable error) {
            out("HIDDEN_API_EXEMPTION_FAIL " + describe(error));
        }
    }

    private static String sanitizeLabel(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length() && result.length() < 48; index++) {
            char character = value.charAt(index);
            if ((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '_'
                    || character == '-') {
                result.append(character);
            }
        }
        return result.toString();
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
        return current.toString();
    }

    private static void out(String message) {
        System.out.println("[TurnSignalEventProbe] " + message);
    }
}
