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
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Passive snapshot of existing stock YUN callbacks (0x99000021), shell UID.
 * Based on the repository's bounded RawCanTurnProbe. No collection-table write,
 * setters, CAN transmission, restart or cloud request. Unregisters at exit.
 * Only CAN IDs referenced by stock report 512 are retained, at most 64 payload bytes.
 */
public final class CloudCanSnapshotProbe {
    private static final int YUN_DYNAMIC_DATA_CALLBACK = 0x99000021;
    private static final int DEVICE_YUN = 1034;
    private static final int DEFAULT_DURATION_SECONDS = 30;
    private static final int MAX_DURATION_SECONDS = 40;
    private static final int DEFAULT_QUEUE_CAPACITY = 4096;
    private static final int MAX_QUEUE_CAPACITY = 8192;
    private static boolean opaqueOutput;

    private CloudCanSnapshotProbe() {
    }

    public static void main(String[] args) {
        if (args.length > 3 || (args.length == 3 && !"--opaque".equals(args[2]))) {
            throw new IllegalArgumentException("Optional third argument must be --opaque");
        }
        opaqueOutput = args.length == 3;
        int durationSeconds = boundedArg(
                args, 0, DEFAULT_DURATION_SECONDS, 10, MAX_DURATION_SECONDS, "durationSeconds");
        int queueCapacity = boundedArg(
                args, 1, DEFAULT_QUEUE_CAPACITY, 128, MAX_QUEUE_CAPACITY, "queueCapacity");

        AtomicBoolean finishing = new AtomicBoolean();
        AtomicLong received = new AtomicLong();
        AtomicLong dropped = new AtomicLong();
        AtomicLong callbackErrors = new AtomicLong();
        ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(queueCapacity);

        out("START uid=" + Process.myUid()
                + " duration_s=" + durationSeconds
                + " queue_capacity=" + queueCapacity);
        exemptHiddenApis();

        Object device = null;
        Object listener = null;
        Thread writer = null;
        try {
            Context context = systemContext();
            Class<?> deviceClass = Class.forName(
                    "android.hardware.bydauto.yun.BYDAutoYunDevice");
            Class<?> listenerClass = Class.forName("android.hardware.IBYDAutoListener");
            Class<?> eventClass = Class.forName("android.hardware.IBYDAutoEvent");
            Method getDeviceType = eventClass.getMethod("getDeviceType");
            Method getEventType = eventClass.getMethod("getEventType");
            Method getBufferData = eventClass.getMethod("getBufferData");

            device = deviceClass.getMethod("getInstance", Context.class).invoke(null, context);
            writer = startWriter(queue, finishing, dropped);

            InvocationHandler handler = (proxy, method, methodArgs) -> {
                String name = method.getName();
                if ("onDataChanged".equals(name)
                        && methodArgs != null
                        && methodArgs.length > 0
                        && methodArgs[0] != null) {
                    try {
                        Object event = methodArgs[0];
                        int deviceType = (Integer) getDeviceType.invoke(event);
                        int eventType = (Integer) getEventType.invoke(event);
                        if (deviceType == DEVICE_YUN
                                && eventType == YUN_DYNAMIC_DATA_CALLBACK) {
                            long sequence = received.incrementAndGet();
                            byte[] data = (byte[]) getBufferData.invoke(event);
                            if (data == null || data.length < 18 || data.length > 74) {
                                callbackErrors.incrementAndGet();
                            } else if (allowedCanId(unsignedInt(data, 0)) && !queue.offer(new Frame(
                                    sequence,
                                    SystemClock.elapsedRealtimeNanos(),
                                    Arrays.copyOf(data, data.length)))) {
                                dropped.incrementAndGet();
                            }
                        }
                    } catch (Throwable error) {
                        callbackErrors.incrementAndGet();
                    }
                    return null;
                }
                if ("onDataEventChanged".equals(name)) return null;
                if ("onError".equals(name)) {
                    callbackErrors.incrementAndGet();
                    return null;
                }
                if ("toString".equals(name)) return "CloudCanSnapshotProbeListener";
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name)) {
                    return methodArgs != null && methodArgs.length == 1 && proxy == methodArgs[0];
                }
                return defaultValue(method.getReturnType());
            };

            listener = Proxy.newProxyInstance(
                    CloudCanSnapshotProbe.class.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    handler);
            deviceClass.getMethod("registerListener", listenerClass, int[].class)
                    .invoke(device, listener, new int[]{YUN_DYNAMIC_DATA_CALLBACK});

            final Object finalDevice = device;
            final Object finalListener = listener;
            final Thread finalWriter = writer;
            Handler mainHandler = new Handler(Looper.getMainLooper());
            Runnable finish = () -> finish(
                    finalDevice,
                    finalListener,
                    finalWriter,
                    queue,
                    finishing,
                    received,
                    dropped,
                    callbackErrors);

            startCommandReader(mainHandler, finish, finishing);
            mainHandler.postDelayed(finish, durationSeconds * 1000L);
            out(String.format(
                    Locale.US,
                    "READY callback=0x%08X t_ns=%d",
                    YUN_DYNAMIC_DATA_CALLBACK,
                    SystemClock.elapsedRealtimeNanos()));
            Looper.loop();
        } catch (Throwable error) {
            out("FAIL " + describe(error));
            error.printStackTrace(System.out);
            finish(
                    device,
                    listener,
                    writer,
                    queue,
                    finishing,
                    received,
                    dropped,
                    callbackErrors);
        }
    }

    private static Thread startWriter(
            ArrayBlockingQueue<Frame> queue,
            AtomicBoolean finishing,
            AtomicLong dropped) {
        Thread thread = new Thread(() -> {
            while (!finishing.get() || !queue.isEmpty()) {
                try {
                    Frame frame = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (frame != null) writeFrame(frame);
                } catch (InterruptedException ignored) {
                    // Re-check the queue and finishing flag.
                } catch (Throwable error) {
                    dropped.incrementAndGet();
                }
            }
        }, "raw-can-writer");
        thread.setDaemon(true);
        thread.start();
        return thread;
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
        }, "raw-can-command-reader");
        thread.setDaemon(true);
        thread.start();
    }

    private static void finish(
            Object device,
            Object listener,
            Thread writer,
            ArrayBlockingQueue<Frame> queue,
            AtomicBoolean finishing,
            AtomicLong received,
            AtomicLong dropped,
            AtomicLong callbackErrors) {
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

        if (writer != null) {
            writer.interrupt();
            try {
                writer.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        out("DONE received=" + received.get()
                + " dropped=" + dropped.get()
                + " callback_errors=" + callbackErrors.get()
                + " queued=" + queue.size());
        System.exit(0);
    }

    static boolean allowedCanId(long id) {
        switch ((int) id) {
            case 0x3cd: case 0x3d9: case 0x12d: case 0x4a5: case 0x294:
            case 0x41a: case 0x2c0: case 0x26f: case 0x447: case 0x445:
            case 0x344: case 0x388: case 0x4b9: case 0x48e: case 0x417:
                return true;
            default: return false;
        }
    }

    private static void writeFrame(Frame frame) {
        byte[] data = frame.data;
        if (opaqueOutput) {
            // Preserve the exact SDK callback buffer for native replay. No
            // vehicle-field decoding or reconstructed callback headers.
            out("OPAQUE seq=" + frame.sequence + " t_ns=" + frame.elapsedNanos
                    + " bytes=" + data.length + " raw=" + hex(data, 0, data.length));
            return;
        }
        if (data.length < 10) {
            out("FRAME seq=" + frame.sequence
                    + " t_ns=" + frame.elapsedNanos
                    + " short_len=" + data.length
                    + " raw=" + hex(data, 0, data.length));
            return;
        }
        long id = unsignedInt(data, 0);
        int subId = data[4] & 0xff;
        int channel = data[5] & 0xff;
        long counter = unsignedInt(data, 6);
        out(String.format(
                Locale.US,
                "FRAME seq=%d t_ns=%d id=0x%X sub=0x%02X ch=%d cnt=%d len=%d payload=%s",
                frame.sequence,
                frame.elapsedNanos,
                id,
                subId,
                channel,
                counter,
                data.length - 10,
                hex(data, 10, data.length - 10)));
    }

    private static long unsignedInt(byte[] data, int offset) {
        return (((long) data[offset] & 0xff) << 24)
                | (((long) data[offset + 1] & 0xff) << 16)
                | (((long) data[offset + 2] & 0xff) << 8)
                | ((long) data[offset + 3] & 0xff);
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

    private static int boundedArg(
            String[] args,
            int index,
            int defaultValue,
            int minimum,
            int maximum,
            String name) {
        if (args.length <= index) return defaultValue;
        int value = Integer.parseInt(args[index]);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
        return value;
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

    private static String hex(byte[] data, int offset, int length) {
        char[] digits = "0123456789abcdef".toCharArray();
        int end = Math.min(data.length, offset + length);
        char[] output = new char[Math.max(0, end - offset) * 2];
        int outputIndex = 0;
        for (int index = offset; index < end; index++) {
            int value = data[index] & 0xff;
            output[outputIndex++] = digits[value >>> 4];
            output[outputIndex++] = digits[value & 0x0f];
        }
        return new String(output);
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
        System.out.println("[CloudCanSnapshotProbe] " + message);
    }

    private static final class Frame {
        private final long sequence;
        private final long elapsedNanos;
        private final byte[] data;

        private Frame(long sequence, long elapsedNanos, byte[] data) {
            this.sequence = sequence;
            this.elapsedNanos = elapsedNanos;
            this.data = data;
        }
    }
}
