package dev.denza.adasstatus.probe;

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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Client-only transaction allowlist 1/2/4/5/8/9; no server or setter API exists. */
final class PassiveClient implements AutoCloseable {
    interface Sink {
        boolean enqueue(Runnable task);
        void state(String event, String detail);
        void event(long topic, long timestamp, long receivedAt, byte[] payload);
    }
    private static final String CLIENT = "ts.car.someip.sdk.ISomeIpClientInterface";
    private static final String CALLBACK = "ts.car.someip.sdk.ISomeIpCallback";
    private static final ComponentName COMPONENT = new ComponentName("com.ts.car.someip.service", "com.ts.car.someip.service.manager.SomeIpClientService");
    private final Context context;
    private final Sink sink;
    private final String session;
    private final List<Long> subscriptions = new ArrayList<>(), clients = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile IBinder remote;
    private volatile boolean bound;
    private boolean registered;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong connectionEpoch = new AtomicLong();
    private int retries;
    private volatile boolean accepting = true;
    private volatile boolean halKnown, halAvailable;
    private volatile long invalidatedAt = -1;
    boolean transportAlive() { return !closed.get() && accepting && remote != null && (!halKnown || halAvailable); }
    boolean binderAlive() { return !closed.get() && accepting && remote != null; }
    Boolean halState() { return halKnown ? halAvailable : null; }
    long invalidatedAt() { return invalidatedAt; }

    PassiveClient(Context context, Sink sink) {
        this.context = context.getApplicationContext(); this.sink = sink;
        session = context.getPackageName() + ".session-" + UUID.randomUUID();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed.get()) return;
            remote = binder;
            invalidatedAt = SystemClock.elapsedRealtime();
            final long epoch = connectionEpoch.incrementAndGet();
            sink.enqueue(() -> {
                if (closed.get() || !accepting || connectionEpoch.get() != epoch) return;
                sink.state("binder_connected", session);
                try {
                    register(true); registered = true;
                    for (long service : new long[]{0x000B000D000D0000L, 0x000B002C002C0000L}) {
                        if (closed.get() || !accepting || connectionEpoch.get() != epoch) return;
                        int result = integerCall(4, service);
                        sink.state("start_client", StatusProtocol.hex(service) + ":" + result);
                        if (result == 0 || result == 13) clients.add(service);
                    }
                    for (long topic : StatusProtocol.TOPICS) {
                        if (closed.get() || !accepting || connectionEpoch.get() != epoch) return;
                        int result = integerCall(8, topic);
                        sink.state("subscribe", StatusProtocol.hex(topic) + ":" + result);
                        if (result == 0) subscriptions.add(topic);
                    }
                } catch (Exception failure) { sink.state("client_setup_error", failure.getClass().getSimpleName()); }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            remote = null;
            invalidatedAt = SystemClock.elapsedRealtime();
            connectionEpoch.incrementAndGet();
            sink.enqueue(() -> sink.state("binder_disconnected", "current availability UNKNOWN"));
        }
        @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); retryBrokenBinding(); }
        @Override public void onNullBinding(ComponentName name) {
            remote = null; sink.enqueue(() -> sink.state("null_binding", "current availability UNKNOWN"));
            connectionEpoch.incrementAndGet(); retryBrokenBinding();
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
    void stopAdmission() { accepting = false; }

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
        accepting = false; connectionEpoch.incrementAndGet(); main.removeCallbacksAndMessages(null);
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
                    if (code == 1 && frame != null && !closed.get() && accepting) {
                        final Frame accepted = frame;
                        final long receivedAt = SystemClock.elapsedRealtime();
                        final long epoch = connectionEpoch.get();
                        sink.enqueue(() -> {
                            if (!closed.get() && accepting && epoch == connectionEpoch.get())
                                sink.event(accepted.topic, accepted.timestamp, receivedAt, accepted.payload);
                            else sink.state("late_callback_discarded", StatusProtocol.hex(accepted.topic));
                        });
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
                if (!available) invalidatedAt = SystemClock.elapsedRealtime();
                sink.enqueue(() -> sink.state("hal_available", String.valueOf(available)));
                if (reply != null) reply.writeNoException(); return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private static Frame readFrame(Parcel data) {
        if (data.readInt() == 0) return null;
        long topic = data.readLong(), timestamp = data.readLong(); int length = data.readInt();
        if (length < 0 || length > StatusProtocol.MAX_PAYLOAD) throw new IllegalArgumentException("Callback payload cap");
        byte[] payload = new byte[length]; data.readByteArray(payload); // validates encoded byte-array length without attacker-sized allocation.
        return new Frame(topic, timestamp, payload);
    }
    private static void writeFrame(Parcel reply, Frame frame) {
        reply.writeInt(frame == null ? 0 : 1);
        if (frame != null) { reply.writeLong(frame.topic); reply.writeLong(frame.timestamp); reply.writeInt(frame.payload.length); reply.writeByteArray(frame.payload); }
    }
    private static final class Frame {
        final long topic, timestamp; final byte[] payload;
        Frame(long topic, long timestamp, byte[] payload) { this.topic = topic; this.timestamp = timestamp; this.payload = payload; }
    }
}
