package dev.denza.adasplayer.probe;

import android.content.Context;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.os.IBinder;
import android.os.Parcel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import dev.denza.hudlink.HudSomeIp;

/**
 * The player's own SOME/IP server session over the stock {@code SomeIpServerService}, through
 * {@code dev.denza.hudlink.HudSomeIp} (the same Binder calls the stock map and the HUD use), with
 * this process's package as the session key. Server start/stop/fire go here; the ODD region-code
 * request is delegated to the client {@link AdasAnswerListener}. Never fires 13/0x0001.
 */
final class AndroidTransport implements Transport, AutoCloseable {
    private final Context context;
    private final AdasAnswerListener listener;
    private final String session;
    private final CountDownLatch connected = new CountDownLatch(1);
    private volatile IBinder remote;
    private volatile boolean bound;

    AndroidTransport(Context context, AdasAnswerListener listener, String session) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.session = session;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) { remote = binder; connected.countDown(); }
        public void onServiceDisconnected(ComponentName name) { remote = null; }
        public void onNullBinding(ComponentName name) { connected.countDown(); }
        public void onBindingDied(ComponentName name) { remote = null; connected.countDown(); }
    };

    boolean bind(long timeoutMs) throws InterruptedException {
        bound = context.bindService(HudSomeIp.bindIntent(session), connection, Context.BIND_AUTO_CREATE);
        if (!bound) return false;
        return connected.await(timeoutMs, TimeUnit.MILLISECONDS) && remote != null;
    }

    private static final String SERVER_DESCRIPTOR = "ts.car.someip.sdk.ISomeIpServerInterface";
    private static final int TX_START = 4, TX_STOP = 5;

    public int startServer(long serviceTopic) { return serverCall(TX_START, serviceTopic); }

    public int stopServer(long serviceTopic) { return serverCall(TX_STOP, serviceTopic); }

    public int fire(long topic, byte[] payload) { return HudSomeIp.fire(remote, topic, payload, "DenzaParkedPlayer"); }

    /** startSomeIpService / stopSomeIpService on a specific service id (HudSomeIp.start only drives
     * the fixed HUD service). Same descriptor and {@code transact(4|5, long)} the stock map uses. */
    private int serverCall(int transaction, long serviceTopic) {
        IBinder binder = remote;
        if (binder == null) return HudSomeIp.NO_BINDER;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVER_DESCRIPTOR);
            data.writeLong(serviceTopic);
            if (!binder.transact(transaction, data, reply, 0)) return HudSomeIp.NOT_HANDLED;
            reply.readException();
            return reply.readInt();
        } catch (Exception failure) {
            return HudSomeIp.FAILED;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public int oddRequest() { return listener.oddRequest(); }

    @Override public void close() {
        if (bound) try { context.unbindService(connection); } catch (RuntimeException ignored) { }
        bound = false;
        remote = null;
    }
}
