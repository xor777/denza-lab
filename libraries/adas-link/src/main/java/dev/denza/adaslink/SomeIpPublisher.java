package dev.denza.adaslink;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import java.util.Map;

/**
 * The real path to ADAS: the exported {@code SomeIpServerService} Binder the stock map's
 * {@code SomeIpServer} uses, with this process's own package as the session key ({@code
 * startSomeIpService} = 4, {@code fireEvent} = 6, as {@code dev.denza.hudlink.HudSomeIp}).
 *
 * <p><b>Hard-off.</b> {@link #LIVE_ALLOWED} is a compile-time {@code false}: the constructor
 * throws, so no instance can exist in this build and nothing in the tree creates one. Enabling
 * it is a source change with its own review, after a stationary single-writer car test plan.
 */
public final class SomeIpPublisher implements Publisher {
    /** Compile-time switch; nothing in this build sets it. */
    public static final boolean LIVE_ALLOWED = false;

    static final String PACKAGE = "com.ts.car.someip.service";
    static final String SERVICE_CLASS = "com.ts.car.someip.service.manager.SomeIpServerService";
    static final String ACTION = "com.ts.car.someip.SomeIpServerService";
    static final String DESCRIPTOR = "ts.car.someip.sdk.ISomeIpServerInterface";
    static final int TX_START = 4, TX_FIRE = 6;

    private final Context context;
    private volatile IBinder binder;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) { binder = service; }
        @Override public void onServiceDisconnected(ComponentName name) { binder = null; }
    };

    public SomeIpPublisher(Context context) {
        if (!LIVE_ALLOWED) throw new IllegalStateException("Live ADAS publication is disabled in this build");
        this.context = context;
        Intent intent = new Intent(ACTION).setComponent(new ComponentName(PACKAGE, SERVICE_CLASS)).setType(context.getPackageName());
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override public BridgeStatus.Mode mode() { return BridgeStatus.Mode.LIVE; }

    @Override public int startService(long serviceTopic) { return call(TX_START, serviceTopic, null); }

    @Override public int fire(long topic, byte[] payload, long monotonicMs) { return call(TX_FIRE, topic, payload); }

    @Override public void note(String kind, Map<String, Object> fields, long monotonicMs) { }

    private int call(int code, long id, byte[] payload) {
        if (!LIVE_ALLOWED) return -1;
        IBinder b = binder;
        if (b == null) return -100;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (code == TX_FIRE) {
                data.writeInt(1);
                data.writeLong(id);
                data.writeLong(0L);
                data.writeInt(payload.length);
                data.writeByteArray(payload);
            } else data.writeLong(id);
            if (!b.transact(code, data, reply, 0)) return -201;
            reply.readException();
            return reply.readInt();
        } catch (Exception e) {
            Log.w("DenzaAdasLink", "SOME/IP call " + code + " failed", e);
            return -200;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
