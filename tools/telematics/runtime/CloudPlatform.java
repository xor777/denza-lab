package dev.denza.tools.runtime;

import android.content.Context;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Shell app_process boundary for the original native cloud worker. No packet or vehicle schema. */
public final class CloudPlatform implements AutoCloseable {
    public static final int YUN = 1034, POWER = 1005, BODYWORK = 1001, CHARGING = 1009;
    public static final int YUN_DATA = 0x99000021, YUN_MCU = 0x99000004;
    public static final int MCU_STATE = 0x99000003, POWER_ACC = 0x99000037,
        ACC = 0x12d0002a, CHARGE = 0x34400018;
    public static final int MCU_ENVELOPE = 0xaa000004, MCU_SECONDARY = 0xaa00001e;
    public static final int MCU_WAKE = 0xaa00004a;
    public static final int POST_LOGIN_MARKER = 0xaa000102;
    public static final int SPEED = 0x14400008, VEHICLE_MODE = 0x2f4000fa;
    public static final int AC_POWER = 0x40400010, TARGET_TEMPERATURE = 0x40400028;
    public static final int SOC = 0x4a505038;
    private static final int MAX_QUEUE = 8192;

    public static final class SubscriptionCleanupUncertain extends java.io.IOException {
        public SubscriptionCleanupUncertain(Throwable cause) { super("subscription cleanup unresolved",cause); }
    }
    public interface Sink { void buffer(int device, int fid, byte[] bytes); void integer(int device, int fid, int value); void error(); }
    public interface Backend {
        byte[] getBuffer(int device, int fid) throws Exception;
        default BufferResult getBufferResult(int device,int fid)throws Exception{
            return new BufferResult(0,getBuffer(device,fid));
        }
        int getInt(int device, int fid) throws Exception;
        float getFloat(int device, int fid) throws Exception;
        int tcpState() throws Exception;
        void stockGate(int value) throws Exception;
        String property(String key) throws Exception;
        void setProperty(String key, String value) throws Exception;
        String serial() throws Exception;
        Object subscribe(int device, int[] fids, Sink sink) throws Exception;
        void unsubscribe(Object subscription) throws Exception;
        int setBuffer(int device, int fid, byte[] bytes) throws Exception;
        int setInt(int device, int fid, int value) throws Exception;
    }
    public static final class BufferResult {
        public final int status;
        public final byte[] bytes;
        public BufferResult(int status,byte[] bytes){
            this.status=status;this.bytes=bytes==null?null:bytes.clone();
        }
    }
    public static final class Identity {
        private final byte[] vin, key, uuid, serial, iccid, imsi;
        private Identity(byte[] vin, byte[] params, byte[] serial, String iccid, String imsi) {
            require(vin != null && vin.length == 17, "VIN length");
            require(params != null && params.length == 33 && params[0] != 0, "cloud parameters");
            require(serial != null && serial.length > 0 && serial.length <= 91, "serial length");
            require(iccid != null && iccid.matches("[0-9]{20}"), "ICCID shape");
            require(imsi != null && imsi.matches("[0-9]{15}"), "IMSI shape");
            this.vin = vin.clone(); this.key = Arrays.copyOfRange(params, 1, 17);
            this.uuid = Arrays.copyOfRange(params, 17, 33); this.serial = serial.clone();
            this.iccid = iccid.getBytes(StandardCharsets.US_ASCII);
            this.imsi = imsi.getBytes(StandardCharsets.US_ASCII);
        }
        public byte[] vin() { return vin.clone(); }
        public byte[] key() { return key.clone(); }
        public byte[] uuid() { return uuid.clone(); }
        public byte[] serial() { return serial.clone(); }
        public byte[] iccid() { return iccid.clone(); }
        public byte[] imsi() { return imsi.clone(); }
    }
    public static final class Event {
        public enum Kind { BUFFER, INTEGER }
        public final long generation;
        public final Kind kind;
        public final int device, fid, value;
        public final byte[] bytes;
        private Event(long generation, Kind kind, int device, int fid, int value, byte[] bytes) {
            this.generation = generation; this.kind = kind; this.device = device;
            this.fid = fid; this.value = value; this.bytes = bytes == null ? null : bytes.clone();
        }
    }
    private final Backend backend;
    private final ArrayBlockingQueue<Event> queue;
    private final List<Object> subscriptions = new ArrayList<>();
    private long generation = 1;
    private boolean closed, failed;
    private final Object closeLock=new Object();
    private Exception cleanupFailure;
    public final Identity identity;

    public static CloudPlatform open(CloudRuntimeSupervisor.Scope scope, String iccid, String imsi, int capacity) throws Exception {
        return open(scope, new AndroidBackend(), iccid, imsi, capacity);
    }
    public static CloudPlatform open(CloudRuntimeSupervisor.Scope scope, Backend backend, String iccid, String imsi, int capacity) throws Exception {
        CloudPlatform platform=scope.own(new CloudPlatform(backend,iccid,imsi,capacity));
        try { platform.startSubscriptions(); return platform; }
        catch(Exception|Error failure) {
            try { scope.retire(platform); } catch(Exception cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private CloudPlatform(Backend backend, String iccid, String imsi, int capacity) throws Exception {
        require(backend != null && capacity >= 1 && capacity <= MAX_QUEUE, "platform arguments");
        require(iccid != null && iccid.matches("[0-9]{20}"), "ICCID shape");
        require(imsi != null && imsi.matches("[0-9]{15}"), "IMSI shape");
        this.backend = backend; this.queue = new ArrayBlockingQueue<>(capacity);
        // The supplied pair is process-local. Never read or write radio properties.
        identity = new Identity(backend.getBuffer(BODYWORK, 0x9900021a),
            backend.getBuffer(YUN, 0x99000005),
            backend.serial().getBytes(StandardCharsets.US_ASCII), iccid, imsi);
    }
    private synchronized void startSubscriptions() throws Exception {
        require(!closed && !failed,"platform closed or failed");
        subscribe(YUN, new int[]{YUN_DATA, YUN_MCU});
        subscribe(POWER, new int[]{MCU_STATE,POWER_ACC});
        subscribe(BODYWORK, new int[]{ACC});
        subscribe(CHARGING, new int[]{CHARGE});
    }
    private void subscribe(int device, int[] fids) throws Exception {
        final long tag = generation;
        try { subscriptions.add(backend.subscribe(device, fids, new Sink() {
            public void buffer(int d, int f, byte[] b) {
                if (d != YUN || (f != YUN_DATA && f != YUN_MCU) || b == null ||
                    (f == YUN_DATA && (b.length < 18 || b.length > 74)) ||
                    (f == YUN_MCU && (b.length < 6 || b.length > 256))) { fail(tag); return; }
                offer(tag, new Event(tag, Event.Kind.BUFFER, d, f, 0, b));
            }
            public void integer(int d, int f, int v) {
                if (!((d == POWER && (f == MCU_STATE || f == POWER_ACC)) || (d == BODYWORK && f == ACC) ||
                      (d == CHARGING && f == CHARGE))) { fail(tag); return; }
                offer(tag, new Event(tag, Event.Kind.INTEGER, d, f, v, null));
            }
            public void error() { fail(tag); }
        })); }
        catch(SubscriptionCleanupUncertain failure) {
            failed=true;cleanupFailure=failure;throw failure;
        }
    }
    private synchronized void fail(long tag) { if (!closed && generation == tag) failed = true; }
    private synchronized void offer(long tag, Event event) {
        if (!closed && generation == tag && !queue.offer(event)) failed = true;
    }
    public synchronized boolean healthy() { return !closed && !failed; }
    public synchronized long generation() { return generation; }
    public Event poll(long timeoutMs) throws InterruptedException {
        require(timeoutMs >= 0 && timeoutMs <= 1000, "poll timeout");
        Event event = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        synchronized (this) { return closed || failed || event == null || event.generation != generation ? null : event; }
    }
    public int getInt(int device, int fid) throws Exception {
        require((device == POWER && (fid == MCU_STATE || fid == POWER_ACC)) || (device == BODYWORK && (fid == ACC || fid == 0x40d00010)) ||
            (device == BODYWORK && (fid == 0x47002000 || fid == 0x29400000 ||
                fid == 0x45400000 || fid == 0x40601022 || fid == 0x44f0001a)) ||
            (device == CHARGING && fid == CHARGE) || (device == 1014 && fid == SPEED) ||
            (device == 1023 && (fid == VEHICLE_MODE || fid == 0x2f400000 || fid == 0x90900118)) ||
            (device == 1031 && fid == 0x4f401000) ||
            (device == 1000 && (fid == AC_POWER || fid == TARGET_TEMPERATURE)), "unsupported integer getter");
        synchronized (this) { require(!closed && !failed, "platform closed or failed"); }
        return backend.getInt(device, fid);
    }
    public BufferResult getNativeBuffer(int device,int fid)throws Exception{
        require(device==1027&&(fid==0x99000002||fid==0x9900021a||fid==0x99000035),"unsupported buffer getter");
        synchronized(this){require(!closed&&!failed,"platform closed or failed");}
        BufferResult value=backend.getBufferResult(device,fid);
        require(value!=null,"buffer result missing");
        if(value.status==0)require(value.bytes!=null&&value.bytes.length<=512,"buffer result bound");
        else require(value.bytes==null||value.bytes.length==0,"buffer error data");
        return value;
    }
    public float getFloat(int device, int fid) throws Exception {
        require(device == 1014 && fid == SOC, "unsupported float getter");
        synchronized (this) { require(!closed && !failed, "platform closed or failed"); }
        return backend.getFloat(device, fid);
    }
    public int tcpState() throws Exception { return backend.tcpState(); }
    /** Exact profile-specific stock gone event; local OFF never sends factory ready. */
    public synchronized void stockGate(int value) throws Exception {
        require(!closed && !failed, "platform closed or failed");
        require(value == -5 || value == -2, "stock gate value");
        backend.stockGate(value);
    }
    public String property(String key) throws Exception {
        require("persist.sys.repair_mode.enable".equals(key) || "persist.sys.energytype".equals(key) ||
            "persist.sys.record_610_upload".equals(key) || "sys.cloud.unlock_index".equals(key) ||
            "persist.sys.vehicle_40d_code".equals(key) || "persist.sys.gpsinfo".equals(key) ||
            "persist.byd.telephony.networkType".equals(key) || "sys.signalstrength".equals(key) ||
            "persist.sys.cloud.last_vin".equals(key) ||
            "persist.sys.cloud_enable".equals(key) ||
            "persist.sys.mcu_func_record".equals(key) ||
            "persist.sys.cloudtest".equals(key) ||
            "persist.sys.cloud.token_flag".equals(key) ||
            "persist.sys.system_info".equals(key) ||
            "apps.setting.product.outswver".equals(key) || "mcu_version".equals(key) ||
            "persist.sys.version".equals(key) || "persist.sys.mcu_version".equals(key) ||
            "persist.sys.user_authentication_status".equals(key) ||
            "ro.vehicle.type.value".equals(key) || "ro.build.car.series".equals(key) ||
            "persist.sys.byd.apn_type".equals(key) ||
            "persist.sys.remotethemechange".equals(key) ||
            "persist.sys.sentrymode_feature".equals(key) ||
            "persist.sys.sentrymode_record".equals(key) ||
            "persist.sys.smart_charge_stage".equals(key) ||
            "persist.sys.smart_charge_support_limit".equals(key) ||
            "persist.sys.smart_charge_stage_record".equals(key) ||
            "persist.sys.record_499_upload".equals(key) ||
            "persist.sys.byd.ditrainer_state".equals(key) ||
            "persist.sys.flag.vent_heat_combined".equals(key) ||
            "persist.sys.remote_video_off_push".equals(key) ||
            "persist.sys.sentrymode_upload".equals(key) ||
            "persist.sys.cloud_fid_uploaded".equals(key) ||
            "persist.sys.record_421_notify".equals(key) ||
            "persist.sys.repair_mode_record".equals(key),
            "unsupported property");
        return backend.property(key);
    }
    /** Relay a supported native property write without interpreting its originating command. */
    public synchronized void sendNativeProperty(String key, String nativeValue) throws Exception {
        require(!closed && !failed, "platform closed or failed");
        // Other shared writes require independent ownership/cleanup qualification.
        require("sys.cloud.remote_controling".equals(key) && "0".equals(nativeValue),
            "unsupported property setter");
        backend.setProperty(key, nativeValue);
    }
    /** Relay an unchanged original-firmware output for an identified setter. */
    public synchronized void sendNativeBuffer(int device, int fid, byte[] nativeBytes) throws Exception {
        require(!closed && !failed, "platform closed or failed");
        require(device == YUN && (fid == MCU_ENVELOPE || fid == MCU_SECONDARY || fid == POST_LOGIN_MARKER),
            "unsupported buffer setter");
        require(nativeBytes != null &&
            (fid == POST_LOGIN_MARKER ? nativeBytes.length == 2 : nativeBytes.length >= 1 && nativeBytes.length <= 256),
            "native buffer size");
        require(backend.setBuffer(device, fid, nativeBytes.clone()) == 0, "vehicle buffer setter refused");
    }
    /** Relay a supported native integer write. Caller must supply the original output value. */
    public synchronized void sendNativeInt(int device, int fid, int nativeValue) throws Exception {
        require(!closed && !failed, "platform closed or failed");
        require(device == POWER && fid == MCU_WAKE && nativeValue == 1, "unsupported integer setter");
        require(backend.setInt(device, fid, nativeValue) == 0, "vehicle integer setter refused");
    }
    public void close() throws Exception {
        synchronized(closeLock) {
            List<Object> toRemove;
            synchronized (this) {
                if (closed) { if(cleanupFailure!=null)throw new java.io.IOException("platform cleanup unresolved"); return; }
                closed = true; generation++; queue.clear();
                toRemove = new ArrayList<>(subscriptions);
                subscriptions.clear();
            }
            Exception first = cleanupFailure;
            for (int i = toRemove.size() - 1; i >= 0; i--) {
                try { backend.unsubscribe(toRemove.get(i)); }
                catch (Exception e) { if (first == null) first = e; else if(e!=first)first.addSuppressed(e); }
            }
            if(first!=null){synchronized(this){cleanupFailure=first;}throw first;}
        }
    }
    private static void require(boolean ok, String reason) { if (!ok) throw new IllegalStateException(reason); }

    /** Uses the same autoservice getters and SDK listener interface observed in the firmware corpus. */
    public static final class AndroidBackend implements Backend {
        private final Context context;
        private final Object auto;
        private final Class<?> listenerType, eventType;
        private final Method getDeviceType, getEventType, getBufferData, getValue;
        public AndroidBackend() throws Exception {
            require(Looper.getMainLooper()!=null, "runtime main Looper required");
            Class<?> runtime = Class.forName("dalvik.system.VMRuntime");
            Object vm = runtime.getMethod("getRuntime").invoke(null);
            runtime.getMethod("setHiddenApiExemptions", String[].class).invoke(vm, (Object)new String[]{"L"});
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            context = (Context)at.getMethod("getSystemContext").invoke(thread);
            auto = context.getSystemService("auto"); require(auto != null, "auto SDK unavailable");
            listenerType = Class.forName("android.hardware.IBYDAutoListener");
            eventType = Class.forName("android.hardware.IBYDAutoEvent");
            getDeviceType = eventType.getMethod("getDeviceType");
            getEventType = eventType.getMethod("getEventType");
            getBufferData = eventType.getMethod("getBufferData");
            getValue = eventType.getMethod("getValue");
        }
        private static IBinder service(String name) throws Exception {
            return (IBinder)Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        }
        private static Parcel query(int transaction, int device, int fid) throws Exception {
            IBinder b = service("autoservice"); require(b != null, "autoservice unavailable");
            Parcel q = Parcel.obtain(), r = Parcel.obtain();
            try {
                q.writeInterfaceToken(b.getInterfaceDescriptor()); q.writeInt(device); q.writeInt(fid);
                require(b.transact(transaction, q, r, 0), "vehicle getter transaction");
                require(r.readInt() == 0, "vehicle getter status"); return r;
            } catch (Exception | Error e) { r.recycle(); throw e; } finally { q.recycle(); }
        }
        public byte[] getBuffer(int device, int fid) throws Exception {
            Parcel r = query(13, device, fid); try { return r.createByteArray(); } finally { r.recycle(); }
        }
        public BufferResult getBufferResult(int device,int fid)throws Exception{
            IBinder b=service("autoservice");require(b!=null,"autoservice unavailable");
            Parcel q=Parcel.obtain(),r=Parcel.obtain();
            try{
                q.writeInterfaceToken(b.getInterfaceDescriptor());q.writeInt(device);q.writeInt(fid);
                require(b.transact(13,q,r,0),"vehicle getter transaction");
                int status=r.readInt();
                if(status!=0)return new BufferResult(status,null);
                byte[] bytes=r.createByteArray();
                require(bytes!=null&&bytes.length<=512,"vehicle buffer result bound");
                return new BufferResult(status,bytes);
            }finally{q.recycle();r.recycle();}
        }
        public int getInt(int device, int fid) throws Exception {
            Parcel r = query(5, device, fid); try { return r.readInt(); } finally { r.recycle(); }
        }
        public float getFloat(int device, int fid) throws Exception {
            Parcel r = query(7, device, fid); try { return r.readFloat(); } finally { r.recycle(); }
        }
        public int tcpState() throws Exception {
            IBinder b = service("cloudmanager"); require(b != null, "cloudmanager unavailable");
            Parcel q = Parcel.obtain(), r = Parcel.obtain();
            try {
                q.writeInterfaceToken(b.getInterfaceDescriptor());
                require(b.transact(7, q, r, 0), "TCP transaction");
                require(r.readInt() == 0, "TCP status");
                int value = r.readInt(); require(value == 0 || value == 1, "TCP state"); return value;
            } finally { q.recycle(); r.recycle(); }
        }
        public void stockGate(int value) throws Exception {
            IBinder b = service("cloudmanager"); require(b != null, "cloudmanager unavailable");
            Parcel q = Parcel.obtain(), r = Parcel.obtain();
            try {
                q.writeInterfaceToken(b.getInterfaceDescriptor()); q.writeInt(value);
                require(b.transact(1, q, r, 0), "cloud notification");
                // A successful transact only means Binder delivered the request.
                // The stock service's reply must also report no exception.
                r.readException();
            } finally { q.recycle(); r.recycle(); }
        }
        public String property(String key) throws Exception {
            return (String)Class.forName("android.os.SystemProperties").getMethod("get", String.class)
                .invoke(null, key);
        }
        public void setProperty(String key, String value) throws Exception {
            if(!key.equals("sys.cloud.remote_controling")||!value.equals("0"))
                throw new IllegalArgumentException("unsupported shared property write");
            new CloudSharedPropertyJournal(CloudLocalControl.STATE.resolve("property.pending"))
                .beforeWrite(property(key));
            Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class)
                .invoke(null, key, value);
            require(value.equals(property(key)), "native property effect not observed");
        }
        public String serial() throws Exception {
            return (String)Class.forName("android.os.SystemProperties").getMethod("get", String.class)
                .invoke(null, "debug.ro.serialno");
        }
        private Object device(int id) throws Exception {
            String name;
            if (id == YUN) name = "android.hardware.bydauto.yun.BYDAutoYunDevice";
            else if (id == POWER) name = "android.hardware.bydauto.power.BYDAutoPowerDevice";
            else if (id == BODYWORK) name = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice";
            else if (id == CHARGING) name = "android.hardware.bydauto.charging.BYDAutoChargingDevice";
            else throw new IllegalArgumentException("device");
            Object result = Class.forName(name).getMethod("getInstance", Context.class).invoke(null, context);
            require(result != null, "SDK device unavailable"); return result;
        }
        private static final class Registration {
            final Object device, listener; Registration(Object d, Object l) { device = d; listener = l; }
        }
        public Object subscribe(int id, int[] fids, Sink sink) throws Exception {
            Object device = device(id);
            Object listener = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{listenerType},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("onDataChanged") && args != null && args.length == 1 && args[0] != null) {
                        try {
                            Object event = args[0]; int d = (Integer)getDeviceType.invoke(event);
                            int f = (Integer)getEventType.invoke(event);
                            if (id == YUN) sink.buffer(d, f, (byte[])getBufferData.invoke(event));
                            else sink.integer(d, f, (Integer)getValue.invoke(event));
                        } catch (Throwable e) { sink.error(); }
                        return null;
                    }
                    if (name.equals("onError")) { sink.error(); return null; }
                    if (name.equals("toString")) return "CloudPlatformListener";
                    if (name.equals("hashCode")) return System.identityHashCode(proxy);
                    if (name.equals("equals")) return args != null && args.length == 1 && proxy == args[0];
                    return null;
                });
            // The inherited IBYDAutoListener overload is used, as in the bounded YUN probe.
            try {
                device.getClass().getMethod("registerListener", listenerType, int[].class)
                    .invoke(device, listener, fids.clone());
            } catch (Exception | Error failure) {
                try { device.getClass().getMethod("unregisterListener", listenerType).invoke(device, listener); }
                catch (Exception cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); throw new SubscriptionCleanupUncertain(failure); }
                throw failure;
            }
            return new Registration(device, listener);
        }
        public void unsubscribe(Object object) throws Exception {
            Registration r = (Registration)object;
            r.device.getClass().getMethod("unregisterListener", listenerType).invoke(r.device, r.listener);
        }
        public int setBuffer(int device, int fid, byte[] bytes) throws Exception {
            return (Integer)auto.getClass().getMethod("setBuffer", int.class, int.class, byte[].class)
                .invoke(auto, device, fid, bytes);
        }
        public int setInt(int device, int fid, int value) throws Exception {
            return (Integer)auto.getClass().getMethod("setInt", int.class, int.class, int.class)
                .invoke(auto, device, fid, value);
        }
    }
}
