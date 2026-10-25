package dev.denza.hud.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The ADAS overlay's listener: a client session of the stock SOME/IP client service, ported from
 * experiments/adas-status-probe (PassiveClient) unchanged in its rules. Client-only transaction
 * allowlist 1/2/4/5/8/9 (register, unregister, startClient, stopClient, subscribe, unsubscribe);
 * no server, setAttribute or fire API exists here. Bridge mode alone also permits the fixed
 * read-only ODD region-list request (13/0x0002, {@link #requestOddRegionCodes}), which only the
 * live guard's stock start makes; passive mode permits no requests. Its own session (package + random
 * UUID as the intent type), so it never merges into the stock map's or the HUD's session; on close
 * it removes only its own subscriptions and clients.
 *
 * <p>Services: 13 and 44 (ADAS's answers), and 7 and 0x8202 (the IVI's own map feed, which the
 * stock map offers and fires; read here to see when it says "no route" over ours). See AdasWire.
 */
final class AdasPassiveClient implements AutoCloseable {
    interface Sink {
        /** Lifecycle and setup work: always essential to a bridge receiver. */
        boolean enqueue(Runnable task);
        /** One received frame's work, by topic, so a bridge receiver can keep answers and shed status. */
        default boolean enqueue(long topic, Runnable task) { return enqueue(task); }
        void state(String event, String detail);
        void event(long topic, long timestamp, long receivedAt, byte[] payload);
    }
    private static final String CLIENT = "ts.car.someip.sdk.ISomeIpClientInterface";
    private static final String CALLBACK = "ts.car.someip.sdk.ISomeIpCallback";
    private static final ComponentName COMPONENT = new ComponentName("com.ts.car.someip.service", "com.ts.car.someip.service.manager.SomeIpClientService");
    private final Context context;
    private final Sink sink;
    private final String session;
    private final boolean bridgeMode, coreMode;
    private volatile boolean pilotAvailable, pathAvailable, requiredSubscribed;
    private static final long PILOT_AVAILABLE = 0x0001000D000D0001L, PILOT_UNAVAILABLE = 0x0001000D000D0002L;
    private static final long PATH_AVAILABLE = 0x0001002C002C0001L, PATH_UNAVAILABLE = 0x0001002C002C0002L;
    private final List<Long> subscriptions = new java.util.concurrent.CopyOnWriteArrayList<>(), clients = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile IBinder remote;
    private volatile boolean bound;
    private boolean registered;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong connectionEpoch = new AtomicLong();
    private final AtomicLong readinessEpoch = new AtomicLong();
    private int retries;
    private volatile boolean accepting = true;
    private volatile boolean halKnown, halAvailable;
    private volatile long invalidatedAt = -1;
    boolean transportAlive() { return !closed.get() && accepting && remote != null && (!halKnown || halAvailable); }
    boolean binderAlive() { return !closed.get() && accepting && remote != null; }
    Boolean halState() { return halKnown ? halAvailable : null; }
    long invalidatedAt() { return invalidatedAt; }

    AdasPassiveClient(Context context, Sink sink) {
        this(context, sink, false);
    }

    AdasPassiveClient(Context context, Sink sink, boolean bridgeMode) { this(context,sink,bridgeMode,false); }

    AdasPassiveClient(Context context, Sink sink, boolean bridgeMode, boolean coreMode) {
        this.context = context.getApplicationContext(); this.sink = sink;
        this.bridgeMode = bridgeMode; this.coreMode=coreMode;
        session = context.getPackageName() + ".session-" + UUID.randomUUID();
    }

    long epoch() { return readinessEpoch.get(); }
    boolean isClosed() { return closed.get(); }
    boolean liveAnswersReady() {
        if (!bridgeMode || !requiredSubscribed || !pilotAvailable || !pathAvailable || !transportAlive()) return false;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            IBinder binder = remote;
            if (binder == null || !binder.isBinderAlive()) return false;
            data.writeInterfaceToken(CLIENT);
            if (!binder.transact(3, data, reply, 0)) return false;
            reply.readException();
            return reply.readInt() != 0 && binder == remote && !closed.get() && accepting
                    && requiredSubscribed && pilotAvailable && pathAvailable && (!halKnown || halAvailable);
        } catch (Exception failed) { return false; }
        finally { reply.recycle(); data.recycle(); }
    }

    /** The stock's ODD region-code read: service 13, method 0x0002 (Topics.ODD_REGION_REQUEST). */
    static final long ODD_REGION_REQUEST = 0x0002000D000D0002L;
    /**
     * The bridge session's one client request: the stock's ODD region-list read (13/0x0002) on its
     * own started service-13 client, transaction 10. A list read, not a driving-control command;
     * there is no topic parameter and no 13/0x0001. Only the live guard calls it, once per fresh
     * guidance start and only under the arm's {@code startProtocol: stock}; the overlay's client
     * can never make it.
     *
     * <p>Both SomeIpData arguments are inout. SomeIpClientManagerImpl.request unconditionally fills
     * the second object, so null crashes it (the old parcel's NullPointerException). SomeIpData also
     * writes BOTH its explicit payload length and writeByteArray's own length. The raw answer is
     * recorded as base64, bounded at 64 KiB.
     */
    int requestOddRegionCodes() {
        if (!bridgeMode || closed.get() || !accepting || !clients.contains(AdasWire.SERVICES[0]) || !liveAnswersReady()) return -1;
        IBinder binder = remote;
        if (binder == null) return -1;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CLIENT);
            writeSomeIpData(data, ODD_REGION_REQUEST, System.currentTimeMillis(), new byte[0]);
            writeSomeIpData(data, 0, 0, null); // new SomeIpData(), present response object
            if (!binder.transact(10, data, reply, 0)) return -201;
            reply.readException();
            int result = reply.readInt();
            readSomeIpData(reply); // mutated/echoed request, then the response
            SomeIpReply response = readSomeIpData(reply);
            if (result == 0 && (response == null || response.payload == null))
                throw new IllegalArgumentException("Successful ODD response missing");
            sink.state("odd_region_response", "result=" + result + " topic="
                    + (response == null ? "absent" : Long.toHexString(response.topic)) + " payloadBase64="
                    + (response == null || response.payload == null ? "null" : Base64.getEncoder().encodeToString(response.payload)));
            return result;
        } catch (Exception failure) {
            sink.state("odd_region_error", failure.getClass().getSimpleName());
            return -200;
        } finally { reply.recycle(); data.recycle(); }
    }

    private static void writeSomeIpData(Parcel data, long topic, long stamp, byte[] payload) {
        data.writeInt(1); data.writeLong(topic); data.writeLong(stamp);
        data.writeInt(payload == null ? -1 : payload.length);
        if (payload != null) data.writeByteArray(payload);
    }
    private static final class SomeIpReply {
        final long topic; final byte[] payload;
        SomeIpReply(long topic, byte[] payload) { this.topic = topic; this.payload = payload; }
    }
    private static SomeIpReply readSomeIpData(Parcel data) {
        if (data.readInt() == 0) return null;
        long topic = data.readLong(); data.readLong();
        int size = data.readInt();
        if (size < -1 || size > 65536) throw new IllegalArgumentException("SomeIpData payload bound");
        byte[] payload = size < 0 ? null : new byte[size];
        if (payload != null) data.readByteArray(payload);
        return new SomeIpReply(topic, payload);
    }

    private void invalidateReadiness() {
        requiredSubscribed = false; pilotAvailable = false; pathAvailable = false;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed.get()) return;
            invalidateReadiness();
            halKnown=false; halAvailable=false;
            remote = binder;
            invalidatedAt = SystemClock.elapsedRealtime();
            final long epoch = connectionEpoch.incrementAndGet();
            readinessEpoch.incrementAndGet();
            sink.enqueue(() -> {
                if (closed.get() || !accepting || connectionEpoch.get() != epoch) return;
                sink.state("binder_connected", session);
                try {
                    register(true); registered = true;
                    boolean setupOk = true;
                    // A fresh HAL rejects subscribe until startClient exists (result11).
                    // Subscribing AVAILABLE after start replays its current exact topic,
                    // including an empty payload, so an already available service is not missed.
                    for (long service : serviceTopics()) {
                        if (closed.get() || !accepting || connectionEpoch.get() != epoch) return;
                        int result = integerCall(4, service);
                        sink.state("start_client", hex(service) + ":" + result);
                        if (result == 0 || result == 13) clients.add(service);
                        else setupOk = false;
                    }
                    for (long topic : subscriptionTopics()) {
                        if (closed.get() || !accepting || connectionEpoch.get() != epoch) return;
                        int result = integerCall(8, topic);
                        sink.state("subscribe", hex(topic) + ":" + result);
                        if (result == 0) subscriptions.add(topic);
                        else setupOk = false;
                    }
                    requiredSubscribed = setupOk && remote==binder && connectionEpoch.get()==epoch && !closed.get() && accepting;
                    sink.state("subscribed", subscriptions.size() + "/" + subscriptionTopics().length);
                } catch (Exception failure) { sink.state("client_setup_error", failure.getClass().getSimpleName()); }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            remote = null;
            invalidateReadiness();
            invalidatedAt = SystemClock.elapsedRealtime();
            connectionEpoch.incrementAndGet();
            readinessEpoch.incrementAndGet();
            sink.enqueue(() -> sink.state("binder_disconnected", "current availability UNKNOWN"));
        }
        @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); retryBrokenBinding(); }
        @Override public void onNullBinding(ComponentName name) {
            remote = null; sink.enqueue(() -> sink.state("null_binding", "current availability UNKNOWN"));
            invalidateReadiness();
            connectionEpoch.incrementAndGet(); readinessEpoch.incrementAndGet(); retryBrokenBinding();
        }
    };

    void start() {
        if (bound || closed.get()) return;
        Intent intent = new Intent().setComponent(COMPONENT).setType(session);
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        sink.enqueue(() -> sink.state(bound ? "binding" : "bind_failed", session));
        if (!bound) retryBrokenBinding();
    }
    private void retryBrokenBinding() {
        if (closed.get()) return;
        if (bound) try { context.unbindService(connection); } catch (RuntimeException ignored) { }
        bound = false;
        if (++retries <= 3) main.postDelayed(() -> { if (!closed.get()) start(); }, 1000L * retries);
        else sink.enqueue(() -> sink.state("rebind_exhausted", "manual restart required"));
    }
    void stopAdmission() { accepting = false; invalidateReadiness(); readinessEpoch.incrementAndGet(); }

    /** Dedicated core SDK connection: preserve computed map/array iteration and own references. */
    void nativeStart(java.util.Map<Long,Long[]> values) {
        if(!coreMode)throw new IllegalStateException("core client required");
        if(!transportAlive())throw new IllegalStateException("core client unavailable");
        try {
            for(java.util.Map.Entry<Long,Long[]> entry:values.entrySet()){
                long service=entry.getKey();int started=0;
                if(!clients.contains(service)){
                    started=integerCall(4,service);
                    if(started!=0&&started!=13)continue;
                    clients.add(service);
                }
                if(entry.getValue()==null)throw new IllegalArgumentException("null subscription array");
                for(Long topic:entry.getValue()){
                    if(topic==null)throw new IllegalArgumentException("null subscription topic");
                    if(subscriptions.contains(topic))continue;
                    if(started==13)integerCall(9,topic);
                    subscriptions.add(topic);
                    if(integerCall(8,topic)!=0)subscriptions.remove(topic);
                }
            }
        }catch(android.os.RemoteException error){throw new IllegalStateException("native subscriptions",error);}
    }
    void nativeStop(java.util.Map<Long,Long[]> values){
        if(!coreMode)throw new IllegalStateException("core client required");
        try{for(java.util.Map.Entry<Long,Long[]> entry:values.entrySet()){
            integerCall(5,entry.getKey());clients.remove(entry.getKey());
            final int service=(int)(entry.getKey()>>>32)&0xffff;
            subscriptions.removeIf(topic->((int)(topic>>>32)&0xffff)==service);
        }}catch(android.os.RemoteException error){throw new IllegalStateException("native stop client",error);}
    }
    void nativeUnsubscribe(java.util.Map<Long,Long[]> values){
        if(!coreMode)throw new IllegalStateException("core client required");
        try{for(java.util.Map.Entry<Long,Long[]> entry:values.entrySet())for(Long topic:entry.getValue()){
            integerCall(9,topic);subscriptions.remove(topic);
        }}catch(android.os.RemoteException error){throw new IllegalStateException("native unsubscribe",error);}
    }

    private int integerCall(int transaction, long value) throws RemoteException {
        if (transaction != 4 && transaction != 5 && transaction != 8 && transaction != 9)
            throw new IllegalArgumentException("Client transaction outside allowlist");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            if ((closed.get() || !accepting) && (transaction == 4 || transaction == 8)) throw new RemoteException("Closed");
            IBinder binder = remote;
            if (binder == null) throw new RemoteException("Disconnected");
            data.writeInterfaceToken(CLIENT); data.writeLong(value);
            if (!binder.transact(transaction, data, reply, 0)) throw new RemoteException("Rejected");
            reply.readException(); return reply.readInt();
        } finally { reply.recycle(); data.recycle(); }
    }
    private void register(boolean yes) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            if (yes && (closed.get() || !accepting)) throw new RemoteException("Closed");
            IBinder binder = remote;
            if (binder == null) throw new RemoteException("Disconnected");
            data.writeInterfaceToken(CLIENT); data.writeStrongBinder(callback);
            if (!binder.transact(yes ? 1 : 2, data, reply, 0)) throw new RemoteException("Rejected callback");
            reply.readException();
        } finally { reply.recycle(); data.recycle(); }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        invalidateReadiness();
        accepting = false; connectionEpoch.incrementAndGet(); readinessEpoch.incrementAndGet(); main.removeCallbacksAndMessages(null);
        // Called on the bounded worker, never from a native event callback.
        for (int i = subscriptions.size()-1; i >= 0; i--) try { integerCall(9, subscriptions.get(i)); } catch (Exception ignored) { }
        for (int i = clients.size()-1; i >= 0; i--) try { integerCall(5, clients.get(i)); } catch (Exception ignored) { }
        if (registered) try { register(false); } catch (Exception ignored) { }
        if (bound) try { context.unbindService(connection); } catch (RuntimeException ignored) { }
        bound = false; remote = null;
        sink.state("closed", "own subscriptions and clients cleanup attempted");
    }

    private final Binder callback = new Binder() {
        { attachInterface(null, CALLBACK); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(CALLBACK); return true; }
            if (code == 1 || code == 3) {
                data.enforceInterface(CALLBACK);
                Frame frame = null;
                try {
                    frame = readFrame(data);
                    if (code == 1 && frame != null && allowed(frame.topic) && !closed.get() && accepting) {
                        final Frame accepted = frame;
                        final long receivedAt = SystemClock.elapsedRealtime();
                        final long epoch = connectionEpoch.get();
                        // Availability is encoded by these exact topics, not by a data payload.
                        // Loss invalidates admission immediately, even if the bounded queue is full.
                        if (bridgeMode) {
                            if (frame.topic == PILOT_AVAILABLE) pilotAvailable = true;
                            else if (frame.topic == PILOT_UNAVAILABLE) { pilotAvailable = false; readinessEpoch.incrementAndGet(); }
                            else if (frame.topic == PATH_AVAILABLE) pathAvailable = true;
                            else if (frame.topic == PATH_UNAVAILABLE) { pathAvailable = false; readinessEpoch.incrementAndGet(); }
                        }
                        boolean queued = sink.enqueue(accepted.topic, () -> {
                            if (!closed.get() && accepting && epoch == connectionEpoch.get())
                                sink.event(accepted.topic, accepted.timestamp, receivedAt, accepted.payload);
                            else sink.state("late_callback_discarded", hex(accepted.topic));
                        });
                        if (!queued && bridgeMode) { invalidateReadiness(); readinessEpoch.incrementAndGet(); }
                    }
                } catch (RuntimeException failure) {
                    sink.enqueue(() -> sink.state("malformed_callback", failure.getClass().getSimpleName()));
                }
                // Firmware holds its callback-list lock. Only bounded read/enqueue
                // occurs above; decode, disk I/O and UI always happen later.
                if (reply != null) {
                    reply.writeNoException();
                    if (code == 3) writeFrame(reply, null);
                    writeFrame(reply, frame); // SDK in/out SomeIpData echo contract.
                }
                return true;
            }
            if (code == 2) {
                data.enforceInterface(CALLBACK); final boolean available = data.readInt() != 0;
                halKnown = true; halAvailable = available;
                if (!available) { invalidatedAt = SystemClock.elapsedRealtime(); invalidateReadiness(); readinessEpoch.incrementAndGet(); }
                sink.enqueue(() -> sink.state("hal_available", String.valueOf(available)));
                if (reply != null) reply.writeNoException(); return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    static long[] topics() {
        long[] all = new long[AdasWire.ANSWERS.length + AdasWire.STOCK.length];
        System.arraycopy(AdasWire.ANSWERS, 0, all, 0, AdasWire.ANSWERS.length);
        System.arraycopy(AdasWire.STOCK, 0, all, AdasWire.ANSWERS.length, AdasWire.STOCK.length);
        return all;
    }
    private long[] serviceTopics() {
        return coreMode ? new long[0] : bridgeMode ? new long[]{AdasWire.SERVICES[0], AdasWire.SERVICES[1]} : AdasWire.SERVICES;
    }
    private long[] subscriptionTopics() {
        if(coreMode)return new long[0];
        if (!bridgeMode) return topics();
        long[] topics = new long[AdasWire.ANSWERS.length + 4];
        System.arraycopy(AdasWire.ANSWERS, 0, topics, 0, AdasWire.ANSWERS.length);
        int at = AdasWire.ANSWERS.length;
        topics[at] = PILOT_AVAILABLE; topics[at+1] = PILOT_UNAVAILABLE;
        topics[at+2] = PATH_AVAILABLE; topics[at+3] = PATH_UNAVAILABLE;
        return topics;
    }
    private static long[] availabilityTopics() {
        return new long[]{PILOT_AVAILABLE,PILOT_UNAVAILABLE,PATH_AVAILABLE,PATH_UNAVAILABLE};
    }
    private static boolean isAvailability(long topic) {
        for (long value : availabilityTopics()) if (topic == value) return true;
        return false;
    }
    private boolean allowed(long topic) {
        if(coreMode)return subscriptions.contains(topic);
        if (!bridgeMode) return AdasWire.allowed(topic);
        for (long allowed : subscriptionTopics()) if (topic == allowed) return true;
        return false;
    }
    static String hex(long value) { return String.format("0x%013X", value); }

    private static Frame readFrame(Parcel data) {
        if (data.readInt() == 0) return null;
        long topic = data.readLong(), timestamp = data.readLong(); int length = data.readInt();
        // SomeIpData encodes a null availability payload as -1 without an array.
        // Data events still require actual bytes; preserve null for the SDK echo.
        if (length == -1 && (topic >>> 48) == 1) return new Frame(topic, timestamp, null);
        if (length < 0 || length > AdasWire.MAX_PAYLOAD) throw new IllegalArgumentException("Callback payload cap");
        byte[] payload = new byte[length]; data.readByteArray(payload); // validates encoded byte-array length without attacker-sized allocation.
        return new Frame(topic, timestamp, payload);
    }
    private static void writeFrame(Parcel reply, Frame frame) {
        reply.writeInt(frame == null ? 0 : 1);
        if (frame != null) {
            reply.writeLong(frame.topic); reply.writeLong(frame.timestamp);
            reply.writeInt(frame.payload == null ? -1 : frame.payload.length);
            if (frame.payload != null) reply.writeByteArray(frame.payload);
        }
    }
    private static final class Frame {
        final long topic, timestamp; final byte[] payload;
        Frame(long topic, long timestamp, byte[] payload) { this.topic = topic; this.timestamp = timestamp; this.payload = payload; }
    }
}
