package dev.denza.diagnostics.probe;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import dev.denza.apps.platform.shell.ShellProxyBootstrap;
import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Proxy;
import java.nio.channels.FileLock;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fixed, read-only address qualification and qualified-module DTC read.
 * No app install, diagnostic session,
 * security access, clear, routine, functional request or arbitrary address input.
 * Candidate addresses come from the CarCoding BYD scan map, not a Z9GT map;
 * 0x712 was independently read by the ADS investigation on this Z9GT.
 * A matching F194 from this car is required before assigning an ECU identity.
 * Uses the MCU's qualified request+8 route and the shared diagnostic-reader lock.
 */
public final class RemainingEcuReadProbe {
    static final int TX = 0xAA00010A, RX = 0x9900010A;
    static final int[] ADDRESSES = {
        0x7E0, // already qualified ECM control
        0x782, 0x783, 0x7F1, 0x712, 0x704, 0x7F2, 0x785, 0x780, 0x797, 0x756, 0x702
    };
    static final byte[] ECM_CODE = {
        0x32, 0x44, 0, 0x11, (byte) 0xE0, 0x04, 0, 0, 0
    };
    static final int[] DTC_ADDRESSES = {0x782, 0x783, 0x7F1, 0x712, 0x7F2};
    // Remaining published BYD scan-map entries. Names are intentionally not assigned:
    // for example 0x785 did not answer on this car despite the map's "rear MCU" name.
    // Groups contain <=18 candidates plus the ECM control; one F194 per address.
    static final int[] CATALOG = {
        0x72E, 0x743, 0x781, 0x787, 0x792, 0x793, 0x794, 0x7A7, 0x7B4,
        0x7C7, 0x7D0, 0x7D6, 0x700, 0x701, 0x705, 0x706, 0x710, 0x711,
        0x713, 0x714, 0x715, 0x721, 0x723, 0x724, 0x725, 0x731, 0x732,
        0x733, 0x735, 0x737, 0x741, 0x742, 0x746, 0x750, 0x751, 0x752,
        0x754, 0x760, 0x761, 0x763, 0x766, 0x770, 0x771, 0x773, 0x774,
        0x7A1, 0x7A3, 0x7AA, 0x7B3, 0x7B5, 0x7B6, 0x7BB, 0x7C2, 0x7C3,
        0x7C6, 0x7D1, 0x7D2, 0x7D4
    };
    static final String[] DTC_IDENTITIES = {
        "32330011DF0B000101", "43430011DE6F000000", "54500011E011000012",
        "48450011DF83000000", "474E0011DF70000000"
    };
    static final List<Step> plan = new ArrayList<>();
    static final class Step {
        final int address;
        final byte[] payload;
        final String identity;
        Step(int address, byte[] payload, String identity) {
            this.address = address; this.payload = payload; this.identity = identity;
        }
    }
    static byte[] identification() { return new byte[]{0x22, (byte) 0xF1, (byte) 0x94}; }
    static void catalogPlan(int group) {
        plan.add(new Step(0x7E0, identification(), hex(ECM_CODE)));
        for (int i = group * 18; i < Math.min(CATALOG.length, (group + 1) * 18); i++)
            plan.add(new Step(CATALOG[i], identification(), null));
    }
    static void obdNamePlan() {
        plan.add(new Step(0x7E0, identification(), hex(ECM_CODE)));
        // SAE Mode 09 supported information, calibration identifiers and ECU name.
        // Deliberately excludes VIN (09 02) and serial-number DIDs.
        for (int pid : new int[]{0, 4, 10})
            plan.add(new Step(0x7E5, new byte[]{9, (byte) pid}, null));
    }
    static void makePlan(boolean dtc) {
        if (!dtc) {
            for (int id : ADDRESSES)
                plan.add(new Step(id, identification(), id == 0x7E0 ? hex(ECM_CODE) : null));
            return;
        }
        plan.add(new Step(0x7E0, identification(), hex(ECM_CODE)));
        for (int i = 0; i < DTC_ADDRESSES.length; i++) {
            plan.add(new Step(DTC_ADDRESSES[i], identification(), DTC_IDENTITIES[i]));
            plan.add(new Step(DTC_ADDRESSES[i], new byte[]{0x19, 1, 9}, null));
            plan.add(new Step(DTC_ADDRESSES[i], new byte[]{0x19, 2, 9}, null));
        }
    }
    static final AtomicBoolean done = new AtomicBoolean();
    static final Set<String> seen = new HashSet<>();
    static final ByteArrayOutputStream reply = new ByteArrayOutputStream();
    static RandomAccessFile lockFile;
    static FileLock lock;
    static IBinder autoService;
    static Object device, listener;
    static Class<?> deviceClass, listenerClass, valueClass;
    static Handler main;
    static int step = -1, total, nextIndex, frameCount;
    static int countAddress = -1, dtcCount = -1, supportMask = -1;
    static Step current;
    static boolean open;
    static Runnable timeout;

    static void out(String s) { System.out.println(s); System.out.flush(); }
    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte v : b) s.append(String.format("%02X", v & 255));
        return s.toString();
    }
    static String prop(String key) throws Exception {
        return (String) Class.forName("android.os.SystemProperties")
            .getMethod("get", String.class).invoke(null, key);
    }
    static int readWord(int transaction, int deviceId, int fid) throws Exception {
        Parcel data = Parcel.obtain(), result = Parcel.obtain();
        try {
            data.writeInterfaceToken(autoService.getInterfaceDescriptor());
            data.writeInt(deviceId); data.writeInt(fid);
            if (!autoService.transact(transaction, data, result, 0))
                throw new IllegalStateException("GET transaction refused");
            result.readException();
            if (result.dataAvail() != 4) throw new IllegalStateException("GET shape");
            return result.readInt();
        } finally { data.recycle(); result.recycle(); }
    }
    static boolean safe() throws Exception {
        return "170".equals(prop("persist.sys.AutoType"))
            && "r@-1_0&w@-1_0".equals(prop("sys.diagmgrsrv.task_bk"))
            && readWord(5, 1011, 89129008) == 1
            && Float.intBitsToFloat(readWord(7, 1013, -1807745016)) == 0f;
    }
    static void finish(String reason) {
        if (!done.compareAndSet(false, true)) return;
        out("END " + reason + " frames=" + frameCount);
        try {
            if (device != null && listener != null) {
                deviceClass.getMethod("unregisterListener", listenerClass).invoke(device, listener);
                out("UNREGISTERED");
            }
        } catch (Throwable e) { out("UNREGISTER_FAILED " + e.getClass().getSimpleName()); }
        try { if (lock != null) lock.release(); if (lockFile != null) lockFile.close(); }
        catch (Exception ignored) {}
        System.exit("PLAN_DONE".equals(reason) ? 0 : 2);
    }
    static void endStep(String reason) {
        if (!open) return;
        open = false;
        main.removeCallbacks(timeout);
        out("STEP_END step=" + step + " why=" + reason);
        // A failed control means the remaining plan must not run.
        if (current.identity != null && !reason.equals("IDENTIFIED")) {
            finish("IDENTITY_GATE_FAILED"); return;
        }
        main.postDelayed(RemainingEcuReadProbe::next, 500);
    }
    static void received(byte[] b) {
        if (done.get()) return;
        if (b == null || b.length < 6 || b.length > 1024 || ++frameCount > 256) {
            finish("FRAME_BOUND"); return;
        }
        int length = b[3] & 255, count = b[4] & 255, index = b[5] & 255;
        int offset = index == 1 ? 7 : 6;
        if (count < 1 || count > 16 || index < 1 || index > count || b.length < offset + length) {
            finish("FRAME_SHAPE"); return;
        }
        // Transport buffers may carry unused tail bytes: compare/log only the declared frame.
        byte[] frame = Arrays.copyOf(b, offset + length);
        String raw = hex(frame);
        if (!seen.add(raw)) return;
        if (step < 0) { finish("PREEXISTING_DIAGNOSTIC_TRAFFIC"); return; }
        int id = ((b[1] & 255) << 8) | (b[2] & 255);
        if (!open || b[0] != 0x0A || id != current.address + 8) {
            finish("UNEXPECTED_DIAGNOSTIC_TRAFFIC"); return;
        }
        if (index != nextIndex || (index > 1 && count != total)) {
            finish("FRAME_SEQUENCE"); return;
        }
        out("FRAME step=" + step + " id=" + Integer.toHexString(id)
            + " total=" + count + " index=" + index + " raw=" + raw);
        if (index == 1) {
            total = count;
            int result = b[6] & 255;
            if (result != 1) { endStep("RESULT_" + result); return; }
        }
        reply.write(b, offset, length);
        nextIndex++;
        if (index != total) return;
        byte[] data = reply.toByteArray();
        if (current.payload[0] == 0x22) {
            if (data.length < 4 || data[0] != 0x62 || data[1] != (byte) 0xF1 || data[2] != (byte) 0x94) {
                finish("RESPONSE_MISMATCH"); return;
            }
            String code = hex(Arrays.copyOfRange(data, 3, data.length));
            if (current.identity != null && !current.identity.equals(code)) {
                finish("IDENTITY_MISMATCH"); return;
            }
            out("IDENTITY req=" + Integer.toHexString(current.address) + " F194=" + code);
            endStep("IDENTIFIED");
        } else if (current.payload[0] == 9) {
            if (data.length < 3 || data[0] != 0x49 || data[1] != current.payload[1]) {
                finish("OBD_RESPONSE_MISMATCH"); return;
            }
            out("OBD_INFO req=" + Integer.toHexString(current.address) + " payload=" + hex(data));
            endStep("OBD_COMPLETE");
        } else {
            if (data.length < 3 || data[0] != 0x59 || data[1] != current.payload[1]) {
                finish("DTC_RESPONSE_MISMATCH"); return;
            }
            if (data[1] == 1) {
                if (data.length != 6 || (data[2] & 9) != 9) {
                    finish("DTC_COUNT_SHAPE_OR_MASK"); return;
                }
                dtcCount = ((data[4] & 255) << 8) | (data[5] & 255);
                supportMask = data[2] & 255;
                countAddress = current.address;
                out("DTC_COUNT req=" + Integer.toHexString(current.address) + " mask=09 count=" + dtcCount
                    + " supported=" + supportMask + " format=" + (data[3] & 255));
                if (dtcCount > 64) { finish("DTC_COUNT_BOUND"); return; }
            } else {
                if ((data.length - 3) % 4 != 0 || (data.length - 3) / 4 != dtcCount
                        || (data[2] & 255) != supportMask) {
                    finish("DTC_LIST_COUNT_OR_MASK_MISMATCH"); return;
                }
                for (int i = 3; i < data.length; i += 4) {
                    if ((data[i + 3] & 9) == 0) { finish("DTC_FILTER_MISMATCH"); return; }
                }
                out("DTC_LIST req=" + Integer.toHexString(current.address) + " mask=09 payload=" + hex(data));
            }
            endStep("DTC_COMPLETE");
        }
    }
    static void next() {
        if (done.get()) return;
        try {
            if (!safe()) { finish("VEHICLE_GUARD"); return; }
            if (++step == plan.size()) { finish("PLAN_DONE"); return; }
            current = plan.get(step);
            if (current.payload[0] == 0x19 && current.payload[1] == 2
                    && (countAddress != current.address || dtcCount < 0 || dtcCount > 64)) {
                finish("DTC_LIST_WITHOUT_QUALIFIED_COUNT"); return;
            }
            seen.clear(); reply.reset(); total = 0; nextIndex = 1;
            int address = current.address;
            byte[] request = new byte[4 + current.payload.length];
            request[0] = 0x0A; request[1] = (byte) (address >> 8); request[2] = (byte) address;
            request[3] = (byte) current.payload.length;
            System.arraycopy(current.payload, 0, request, 4, current.payload.length);
            Object value = valueClass.getConstructor().newInstance();
            valueClass.getField("bufferDataValue").set(value, request);
            timeout = () -> endStep("TIMEOUT_4S");
            open = true;
            out("REQUEST step=" + step + " " + hex(request));
            main.postDelayed(timeout, 4000);
            Object rc = deviceClass.getMethod("set", int[].class, valueClass)
                .invoke(device, new int[]{TX}, value);
            out("TRANSPORT_RESULT step=" + step + " " + rc);
            if (!Integer.valueOf(0).equals(rc)) finish("TRANSPORT_FAILURE");
        } catch (Throwable e) { out("ERROR " + e.getClass().getSimpleName()); finish("NO_RETRY"); }
    }
    public static void main(String[] args) {
        boolean dtc = args.length == 1 && args[0].equals("dtc");
        boolean obdName = args.length == 1 && args[0].equals("obd-name");
        int group = -1;
        for (int i = 0; i < 4; i++)
            if (args.length == 1 && args[0].equals("catalog" + (i + 1))) group = i;
        if (args.length != 0 && !dtc && !obdName && group < 0)
            throw new IllegalArgumentException("Fixed identification, dtc, obd-name or catalog1..4 plan");
        if (obdName) obdNamePlan();
        else if (group >= 0) catalogPlan(group);
        else makePlan(dtc);
        new Thread(() -> {
            try { Thread.sleep(90000); } catch (InterruptedException ignored) {}
            out("HARD_TIMEOUT"); System.exit(3);
        }, "remaining-ecu-watchdog").start();
        try {
            if (Process.myUid() != 2000) { finish("WRONG_UID"); return; }
            lockFile = new RandomAccessFile("/data/local/tmp/denza-dtc-read.lock", "rw");
            lock = lockFile.getChannel().tryLock();
            if (lock == null) { finish("BUSY"); return; }
            ShellProxyBootstrap.exemptHiddenApis();
            autoService = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "autoservice");
            if (autoService == null || !safe()) { finish("INITIAL_GUARD"); return; }
            ShellProxyBootstrap.prepareMainLooper();
            Context context = ShellProxyBootstrap.systemContext();
            main = new Handler(Looper.getMainLooper());
            deviceClass = Class.forName("android.hardware.bydauto.mqtt.BYDAutoMqttDevice");
            listenerClass = Class.forName("android.hardware.IBYDAutoListener");
            valueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            Class<?> eventClass = Class.forName("android.hardware.IBYDAutoEvent");
            device = deviceClass.getMethod("getInstance", Context.class).invoke(null, context);
            listener = Proxy.newProxyInstance(RemainingEcuReadProbe.class.getClassLoader(),
                new Class<?>[]{listenerClass}, (proxy, method, a) -> {
                    byte[] data = null;
                    if (method.getName().equals("onDataEventChanged") && ((Integer) a[0]) == RX)
                        data = (byte[]) valueClass.getField("bufferDataValue").get(a[1]);
                    else if (method.getName().equals("onDataChanged")
                            && ((Integer) eventClass.getMethod("getEventType").invoke(a[0])) == RX)
                        data = (byte[]) eventClass.getMethod("getBufferData").invoke(a[0]);
                    else if (method.getName().equals("onError"))
                        main.post(() -> finish("CALLBACK_ERROR"));
                    else if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    else if (method.getName().equals("equals")) return proxy == a[0];
                    else if (method.getName().equals("toString")) return "RemainingEcuReadProbe";
                    if (data != null) {
                        byte[] copy = data.clone();
                        main.post(() -> received(copy));
                    }
                    return null;
                });
            deviceClass.getMethod("registerListener", listenerClass, int[].class)
                .invoke(device, listener, new int[]{RX});
            out("START uid=2000 steps=" + plan.size() + " passive_ms=2000");
            main.postDelayed(RemainingEcuReadProbe::next, 2000);
            Looper.loop();
        } catch (Throwable e) { out("ERROR " + e.getClass().getSimpleName()); finish("NO_RETRY"); }
    }
}
