package dev.denza.hud.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import dev.denza.adaslink.BridgeStatus;
import dev.denza.adaslink.Publisher;
import dev.denza.adaslink.Topics;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** App-owned map-event transport. NativeTransportAdmission owns only prerequisites, never packet contents. */
final class LiveMapTransport implements Publisher, AutoCloseable {
    interface Listener { void changed(String state); }
    interface Admission { boolean allow(long topic); }
    private static final ComponentName COMPONENT = new ComponentName("com.ts.car.someip.service",
            "com.ts.car.someip.service.manager.SomeIpServerService");
    private static final String DESCRIPTOR = "ts.car.someip.sdk.ISomeIpServerInterface";
    private static final int MAX_PAYLOAD = 2 * 1024 * 1024;
    private final Context context;
    private final Handler worker;
    private final Listener listener;
    private final Publisher audit;
    private final Admission admission;
    private final String session;
    private final Set<Long> requested = new LinkedHashSet<>(), offered = new LinkedHashSet<>();
    private volatile IBinder binder;
    private volatile boolean closed;
    private boolean bound;
    private volatile long generation;
    private volatile long lastActualSend = -1;
    boolean isClosed() { return closed; }
    long lastActualSend() { return lastActualSend; }

    LiveMapTransport(Context context, Handler worker, Listener listener, Publisher audit, Admission admission) {
        this.context = context.getApplicationContext(); this.worker = worker; this.listener = listener; this.audit = audit;
        this.admission=admission;
        session = context.getPackageName() + ".adas-live-" + UUID.randomUUID();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder remote) {
            if (closed) return;
            binder = remote; final long epoch = ++generation;
            try { remote.linkToDeath(() -> lost(remote, "server_binder_died"), 0); }
            catch (Exception failed) { lost(remote, "server_binder_dead"); return; }
            worker.post(() -> { if (!closed && binder == remote && generation == epoch) listener.changed("server_connected"); });
        }
        @Override public void onServiceDisconnected(ComponentName name) { lost(binder, "server_disconnected"); }
        @Override public void onBindingDied(ComponentName name) { lost(binder, "server_binding_died"); }
        @Override public void onNullBinding(ComponentName name) { lost(binder, "server_null_binding"); }
    };

    void start() {
        if (closed || bound) return;
        Intent intent = new Intent("com.ts.car.someip.SomeIpServerService").setComponent(COMPONENT).setType(session);
        try { bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE); }
        catch (RuntimeException failed) { bound = false; }
        if (!bound) listener.changed("server_bind_failed");
    }

    private void lost(IBinder expected, String reason) {
        if (binder != expected) return;
        binder = null; generation++;
        worker.post(() -> { if (!closed) listener.changed(reason); });
    }

    /** Real local HAL readiness, distinct from the remote ADAS service availability. */
    boolean ready() {
        IBinder remote = binder;
        if (closed || remote == null || !remote.isBinderAlive()) return false;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!remote.transact(3, data, reply, 0)) return false;
            reply.readException();
            return reply.readInt() != 0 && binder == remote && !closed;
        } catch (Exception failed) { return false; }
        finally { reply.recycle(); data.recycle(); }
    }
    long generation() { return generation; }
    boolean servicesOffered() { return offered.size() == Topics.SERVICES.length && ready(); }
    @Override public BridgeStatus.Mode mode() { return BridgeStatus.Mode.LIVE; }

    @Override public int startService(long topic) {
        if (!serviceAllowed(topic) || !ready()) return -100;
        requested.add(topic);
        int result = call(binder, 4, topic, null);
        // Firmware explicitly adds this session to its reference set for both 0 and 13.
        if (result == 0 || result == 13) { offered.add(topic); return 0; }
        return result;
    }

    int stopService(long topic) {
        if(!serviceAllowed(topic))return -100;
        int result=call(binder,5,topic,null);
        if(result==0){offered.remove(topic);requested.remove(topic);}
        return result;
    }

    @Override public int fire(long topic, byte[] payload, long now) {
        IBinder remote=binder;
        if (!eventAllowed(topic) || payload == null || payload.length > MAX_PAYLOAD || closed
                || remote==null || !remote.isBinderAlive()) return -101;
        long service = (0xBL << 48) | ((long) Topics.service(topic) << 32) | ((long) Topics.service(topic) << 16);
        if (!offered.contains(service)) return -102;
        // Guard already queried the actual HAL. Do not insert another blocking query
        // between its freshness decision and TX6; recheck the leases after marshalling.
        int result=call(remote, 6, topic, payload);
        if (result==0) { lastActualSend=android.os.SystemClock.elapsedRealtime(); audit.fire(topic,payload,lastActualSend); }
        return result;
    }
    @Override public void note(String kind, Map<String, Object> fields, long now) { audit.note(kind, fields, now); }

    private static boolean serviceAllowed(long topic) {
        for (long allowed : Topics.SERVICES) if (topic == allowed) return true;
        return false;
    }
    static boolean eventAllowed(long topic) {
        if (topic == Topics.PATH_SELECT_CONFIRM) return false; // Inbound, never fire it.
        int service = Topics.service(topic);
        return (service == 7 || service == 43 || service == 45 || service == 0x8202)
                && (topic >>> 48) == 4;
    }

    /** The only transactions here are ready(3), start(4), stop-own-service(5) and fire-map-event(6). */
    private int call(IBinder remote, int transaction, long topic, byte[] payload) {
        if (remote == null || transaction != 4 && transaction != 5 && transaction != 6) return -200;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (transaction == 6) {
                data.writeInt(1); data.writeLong(topic); data.writeLong(System.currentTimeMillis());
                data.writeInt(payload.length); data.writeByteArray(payload);
            } else data.writeLong(topic);
            if(transaction==6 && (closed || binder!=remote || !admission.allow(topic))) return -203;
            if (!remote.transact(transaction, data, reply, 0)) return -201;
            reply.readException(); return reply.readInt();
        } catch (Exception failed) { return -202; }
        finally { reply.recycle(); data.recycle(); }
    }

    @Override public void close() {
        if (closed) return;
        closed = true; generation++;
        IBinder remote = binder; binder = null;
        // A start with an unknown outcome may still own a reference: release only this session's set.
        for (long topic : requested) call(remote, 5, topic, null);
        offered.clear(); requested.clear();
        if (bound) try { context.unbindService(connection); } catch (RuntimeException ignored) { }
        bound = false;
    }
}
