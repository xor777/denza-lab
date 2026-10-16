package dev.denza.tools;

import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Bounded shell-UID snapshot of the installed framework's scalar FID catalog.
 * Only the documented getInt/getFloat Binder transactions (5/7) are used.
 * No observers, setters, guessed IDs, array transactions or ECU requests.
 */
public final class VehicleStateSnapshot {
    private static final int GET_INT = 5;
    private static final int GET_FLOAT = 7;

    public static void main(String[] args) throws Exception {
        int passes = args.length == 0 ? 2 : Integer.parseInt(args[0]);
        if (passes < 1 || passes > 2) throw new IllegalArgumentException("passes: 1..2");
        Thread watchdog = new Thread(() -> {
            SystemClock.sleep(480000);
            System.out.println("ABORT\twatchdog");
            System.exit(2);
        });
        watchdog.setDaemon(true);
        watchdog.start();
        Class<?> vm = Class.forName("dalvik.system.VMRuntime");
        vm.getDeclaredMethod("setHiddenApiExemptions", String[].class).invoke(
                vm.getDeclaredMethod("getRuntime").invoke(null), (Object) new String[]{"L"});
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        IBinder service = (IBinder) serviceManager.getMethod("getService", String.class)
                .invoke(null, "autoservice");
        if (service == null) throw new IllegalStateException("autoservice absent");
        String descriptor = service.getInterfaceDescriptor();
        if (!"android.gui.BYDAutoServer".equals(descriptor)) {
            throw new IllegalStateException("unexpected Binder: " + descriptor);
        }

        Map<Integer, Set<String>> names = new TreeMap<>();
        collectNames(Class.forName("android.hardware.bydauto.BYDAutoFeatureIds"), names);
        Method features = Class.forName("android.hardware.bydauto.BYDAutoDeviceFeaturesMap")
                .getMethod("getFeatureIdsFromDevice", int.class);
        Set<Integer> devices = new TreeSet<>();
        for (Field f : Class.forName("android.hardware.bydauto.BYDAutoConstants").getFields()) {
            if (f.getName().startsWith("BYDAUTO_DEVICE_") && f.getType() == int.class
                    && Modifier.isStatic(f.getModifiers())) devices.add(f.getInt(null));
        }
        Map<Integer, List<Integer>> catalog = new TreeMap<>();
        int total = 0;
        for (int device : devices) {
            @SuppressWarnings("unchecked")
            Set<Integer> ids = (Set<Integer>) features.invoke(null, device);
            if (ids.isEmpty()) continue;
            List<Integer> ordered = new ArrayList<>(ids);
            Collections.sort(ordered);
            catalog.put(device, ordered);
            total += ordered.size();
            for (int fid : ordered) {
                Set<String> aliases = names.get(fid);
                System.out.println("CAT\t" + device + "\t" + hex(fid) + "\t"
                        + (aliases == null ? "" : String.join("|", aliases)));
            }
        }
        System.out.println("BEGIN\t" + catalog.size() + "\t" + total + "\t" + passes);
        int reads = 0;
        for (int pass = 1; pass <= passes; pass++) {
            System.out.println("PASS\t" + pass + "\t" + SystemClock.elapsedRealtimeNanos());
            for (Map.Entry<Integer, List<Integer>> entry : catalog.entrySet()) {
                for (int fid : entry.getValue()) {
                    int device = entry.getKey();
                    // Retain raw reply bits, including unsupported/invalid sentinels.
                    int intBits = read(service, descriptor, GET_INT, device, fid);
                    String floatBits = "";
                    if (intBits == -10013) {
                        floatBits = hex(read(service, descriptor, GET_FLOAT, device, fid));
                    }
                    System.out.println("FID\t" + pass + "\t" + SystemClock.elapsedRealtimeNanos()
                            + "\t" + device + "\t" + hex(fid) + "\t" + hex(intBits)
                            + "\t" + floatBits);
                    reads++;
                    SystemClock.sleep(10);
                }
            }
        }
        System.out.println("DONE\t" + reads + "\t" + SystemClock.elapsedRealtimeNanos());
    }

    private static int read(IBinder service, String descriptor, int code, int device, int fid)
            throws Exception {
        if (code != GET_INT && code != GET_FLOAT) throw new IllegalArgumentException("GET only");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeInt(device);
            data.writeInt(fid);
            if (!service.transact(code, data, reply, 0)) throw new IllegalStateException("transact false");
            reply.readException();
            if (reply.dataAvail() != 4) throw new IllegalStateException("unexpected scalar reply");
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static String hex(int value) {
        return String.format(Locale.US, "0x%08X", value);
    }

    private static void collectNames(Class<?> type, Map<Integer, Set<String>> names)
            throws IllegalAccessException {
        for (Field f : type.getFields()) {
            if (f.getType() == int.class && Modifier.isStatic(f.getModifiers())) {
                names.computeIfAbsent(f.getInt(null), key -> new TreeSet<>())
                        .add(type.getSimpleName() + "." + f.getName());
            }
        }
        for (Class<?> child : type.getDeclaredClasses()) collectNames(child, names);
    }
}
