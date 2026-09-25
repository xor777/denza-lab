package dev.denza.tools.runtime;

import android.content.Context;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import java.io.IOException;
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
    public static final int DATA_SUBSCRIPTION = 0xaa000023;
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
            require(serial != null && serial.length <= 91, "serial length");
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
            (device == CHARGING && (fid == CHARGE || fid == 0x47002011 || fid == 0x47002012)) ||
            (device == 1014 && fid == SPEED) ||
            (device == 1023 && (fid == VEHICLE_MODE || fid == 0x2f400000 || fid == 0x90900118 ||
                fid == 0x2940002a || fid == 0x4540000c)) ||
            (device == 1025 && fid == 0x4f401038) ||
            (device == 1031 && fid == 0x4f401000) ||
            (device == 1000 && (fid == AC_POWER || fid == TARGET_TEMPERATURE)), "unsupported integer getter");
        synchronized (this) { require(!closed && !failed, "platform closed or failed"); }
        return backend.getInt(device, fid);
    }
    public BufferResult getNativeBuffer(int device,int fid)throws Exception{
        require(device==1027&&(fid==0x99000002||fid==0x9900021a||fid==0x99000035||fid==0x99000402),"unsupported buffer getter");
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
    /** Alpha excludes stock service/repair modes and pending version-cache resets.
     * Read these before pausing the stock client; never acknowledge its global resets.
     */
    public void requireAwakeStartup() throws Exception {
        if (!inactive(property("persist.sys.cloudtest")) ||
            !inactive(property("persist.sys.repair_mode.enable")) ||
            !inactive(property("persist.sys.repair_mode_record")) ||
            "0".equals(property("persist.sys.cloud_enable")) ||
            !integerPresent(property("persist.sys.energytype")) ||
            !property("apps.setting.product.outswver").equals(property("persist.sys.version")) ||
            !property("mcu_version").equals(property("persist.sys.mcu_version")))
            throw new CloudSessionLoop.PermanentFailure(CloudRuntimeSupervisor.Code.UNSUPPORTED_FIRMWARE);
    }
    private static boolean inactive(String value) { return "".equals(value) || "0".equals(value); }
    private static boolean integerPresent(String value) {
        try { Integer.parseInt(value); return true; }
        catch(NumberFormatException missing) { return false; }
    }
    /** Exact profile-specific stock gone event; local OFF never sends factory ready. */
    public synchronized void stockGate(int value) throws Exception {
        require(!closed && !failed, "platform closed or failed");
        require(value == -5 || value == -2, "stock gate value");
        backend.stockGate(value);
    }
    public String property(String key) throws Exception {
        // The pinned firmware owns property names and fallback semantics.
        // Reading a new property must not require a new vehicle-specific rule.
        // Identity and client-local state are intercepted before this boundary;
        // this admits no additional shared writes.
        require(key!=null && key.matches("[A-Za-z0-9_.-]{1,96}"), "invalid property name");
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
    /** Explicit policy refusal for a known global configuration outside this session. */
    public synchronized int sendNativePropertyStatus(String key,String value)throws Exception {
        require(!closed&&!failed,"platform closed or failed");
        require("persist.sys.edge.enable.sre".equals(key)&&("0".equals(value)||"1".equals(value)),
            "unsupported property status setter");
        // This alpha does not own the stock edge service's persistent setting.
        // Deny the write through the native API's failure result. The original
        // caller ignores the failure and continues; no successful write is claimed.
        return -1;
    }
    /** Relay an unchanged original-firmware output for an identified setter. */
    public synchronized void sendNativeSubscription(byte[] nativeBytes) throws Exception {
        require(!closed && !failed, "platform unavailable");
        require(nativeBytes != null && nativeBytes.length >= 8 && nativeBytes.length <= 512 &&
            nativeBytes.length % 8 == 0, "native subscription bound");
        require(backend.setBuffer(YUN, DATA_SUBSCRIPTION, nativeBytes.clone()) == 0,
            "vehicle subscription setter refused");
    }
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
        private static final class RuntimeState {
            final Context context;
            final Object auto;
            final Map<Integer,Object> devices;
            RuntimeState(Context context,Object auto,Map<Integer,Object> devices) {
                this.context=context;this.auto=auto;this.devices=devices;
            }
        }
        private static volatile RuntimeState runtimeState;
        private final Object auto;
        private final Map<Integer,Object> devices;
        private final Class<?> listenerType, eventType;
        private final Method getDeviceType, getEventType, getBufferData, getValue;
        /** app_process has no ActivityThread until its own main Looper creates it. */
        static synchronized void initializeOnMainThread(boolean listeners) throws Exception {
            require(Looper.myLooper()!=null && Looper.myLooper()==Looper.getMainLooper(),
                "Android SDK initialization requires main Looper thread");
            RuntimeState current=runtimeState;
            if(current!=null && (!listeners || !current.devices.isEmpty()))return;
            if(current==null){
                Class<?> runtime = Class.forName("dalvik.system.VMRuntime");
                Object vm = runtime.getMethod("getRuntime").invoke(null);
                runtime.getMethod("setHiddenApiExemptions", String[].class).invoke(vm, (Object)new String[]{"L"});
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object thread = at.getMethod("systemMain").invoke(null);
                Context context=(Context)at.getMethod("getSystemContext").invoke(thread);
                require(context!=null,"system context unavailable");
                Object auto=context.getSystemService("auto");require(auto!=null,"auto SDK unavailable");
                current=new RuntimeState(context,auto,Collections.emptyMap());
                runtimeState=current;
            }
            if(listeners){
                Map<Integer,Object> devices=new HashMap<>();
                for(int id:new int[]{YUN,POWER,BODYWORK,CHARGING})
                    devices.put(id,createDevice(id,current.context));
                runtimeState=new RuntimeState(current.context,current.auto,Collections.unmodifiableMap(devices));
            }
        }
        public AndroidBackend() throws Exception {
            RuntimeState state=runtimeState;
            require(state!=null,"Android SDK not initialized on main thread");
            auto=state.auto;devices=state.devices;
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
        static void satisfyRemoteControlZero(String key,String value,Backend backend) throws Exception {
            if(!"sys.cloud.remote_controling".equals(key)||!"0".equals(value))
                throw new IllegalArgumentException("unsupported shared property write");
            // The matching CloudReboot reader polls the current value; it has
            // no write-event dependency in the inspected source.
            // The original requested result is already present, so no shared write
            // or crash-cleanup debt is needed. Unknown and nonzero values fail closed.
            if(!"0".equals(backend.property(key)))
                throw new IOException("shared_property_original_unqualified");
        }
        public void setProperty(String key, String value) throws Exception {
            satisfyRemoteControlZero(key,value,this);
        }
        public String serial() throws Exception {
            return (String)Class.forName("android.os.SystemProperties").getMethod("get", String.class)
                .invoke(null, "debug.ro.serialno");
        }
        private static Object createDevice(int id,Context context) throws Exception {
            String name;
            if (id == YUN) name = "android.hardware.bydauto.yun.BYDAutoYunDevice";
            else if (id == POWER) name = "android.hardware.bydauto.power.BYDAutoPowerDevice";
            else if (id == BODYWORK) name = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice";
            else if (id == CHARGING) name = "android.hardware.bydauto.charging.BYDAutoChargingDevice";
            else throw new IllegalArgumentException("device");
            Object result = Class.forName(name).getMethod("getInstance", Context.class).invoke(null, context);
            require(result != null, "SDK device unavailable"); return result;
        }
        private Object device(int id) {
            Object result=devices.get(id);
            require(result!=null,"SDK listener device not initialized on main thread");
            return result;
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
