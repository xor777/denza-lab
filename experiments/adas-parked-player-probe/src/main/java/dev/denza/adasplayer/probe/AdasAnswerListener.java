package dev.denza.adasplayer.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A read-only client session of {@code SomeIpClientService} that records ADAS's answers while the
 * player feeds the car: PilotStatus (13/0x8002), alarms (13/0x8003), NOA mode (13/0x8005),
 * MMTPathSelected (44/0x8002) and PathMatch (44/0x8001). Its own session (package + random UUID),
 * so it never merges with the stock map's or the player's server session; on close it removes only
 * its own subscriptions and client, and never fires a server event or a driving-control request.
 * Purpose-built for this probe (not the overlay's AdasPassiveClient, which another session is
 * rewriting); it also carries the single ODD region-code request the stock sends before a route.
 */
final class AdasAnswerListener implements AutoCloseable {
    private static final String CLIENT = "ts.car.someip.sdk.ISomeIpClientInterface";
    private static final String CALLBACK = "ts.car.someip.sdk.ISomeIpCallback";
    private static final ComponentName COMPONENT =
            new ComponentName("com.ts.car.someip.service", "com.ts.car.someip.service.manager.SomeIpClientService");
    private static final int TX_REGISTER = 1, TX_UNREGISTER = 2, TX_REQUEST = 10,
            TX_START = 4, TX_STOP = 5, TX_SUBSCRIBE = 8, TX_UNSUBSCRIBE = 9;
    private static final long SERVICE_PILOT = 0x000B000D000D0000L, SERVICE_PATH = 0x000B002C002C0000L;
    private static final long[] ANSWER_TOPICS = {
            0x0004000D000D8002L, 0x0004000D000D8003L, 0x0004000D000D8005L,
            0x0004002C002C8002L, 0x0004002C002C8001L};

    private final Context context;
    private final Recorder recorder;
    private final String session;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile IBinder remote;
    private volatile boolean bound, registered, pilotStarted;

    AdasAnswerListener(Context context, Recorder recorder) {
        this.context = context.getApplicationContext();
        this.recorder = recorder;
        this.session = context.getPackageName() + ".answers-" + UUID.randomUUID();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) { remote = binder; setup(); }
        public void onServiceDisconnected(ComponentName name) { remote = null; }
    };

    boolean start() {
        Intent intent = new Intent("com.ts.car.someip.SomeIpClientService").setComponent(COMPONENT).setType(session);
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        recorder.note("answer_listener", "bind=" + bound + " session=" + session);
        return bound;
    }

    private void setup() {
        try {
            registerCallback(true);
            registered = true;
            int p = integerCall(TX_START, SERVICE_PILOT);
            pilotStarted = p == 0 || p == 13;
            int path = integerCall(TX_START, SERVICE_PATH);
            recorder.note("answer_start", "pilot=" + p + " path=" + path);
            for (long topic : ANSWER_TOPICS) {
                int s = integerCall(TX_SUBSCRIBE, topic);
                recorder.note("answer_subscribe", Long.toHexString(topic) + ":" + s);
            }
        } catch (Exception failure) {
            recorder.note("answer_setup_error", failure.getClass().getSimpleName());
        }
    }

    /** The stock ODD region-code request (service 13 / method 0x0002); the SDK call result, or -1. */
    boolean oddReady() { return !closed.get() && remote != null && pilotStarted; }

    int oddRequest() {
        IBinder binder = remote;
        if (closed.get() || binder == null || !pilotStarted) return -1;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CLIENT);
            data.writeInt(1);                                 // SomeIpData present
            data.writeLong(ScenarioPlan.ODD_TOPIC);
            data.writeLong(System.currentTimeMillis());
            data.writeInt(0);                                  // empty ODDRegionCodeReq payload
            data.writeByteArray(new byte[0]);                  // SomeIpData writes the array too
            data.writeInt(1);                                  // new SomeIpData(), response present
            data.writeLong(0); data.writeLong(0); data.writeInt(-1);
            if (!binder.transact(TX_REQUEST, data, reply, 0)) return -201;
            reply.readException();
            int result = reply.readInt();
            readSomeIpData(reply);                             // inout request echo
            byte[] response = readSomeIpData(reply);
            if (result == 0 && response == null) throw new IllegalArgumentException("Successful ODD response missing");
            recorder.note("odd_response", response == null ? "null" : hex(response));
            recorder.note("odd_region_response", "result=" + result + " payloadBase64="
                    + (response == null ? "null" : java.util.Base64.getEncoder().encodeToString(response)));
            return result;
        } catch (Exception failure) {
            recorder.note("odd_request_error", failure.getClass().getSimpleName());
            return -200;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static byte[] readSomeIpData(Parcel data) {
        if (data.readInt() == 0) return null;
        data.readLong(); data.readLong();
        int size = data.readInt();
        if (size < -1 || size > 65536) throw new IllegalArgumentException("SomeIpData payload bound");
        byte[] bytes = size < 0 ? null : new byte[size];
        if (bytes != null) data.readByteArray(bytes);
        return bytes;
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2], digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) { out[i * 2] = digits[(bytes[i] >>> 4) & 15]; out[i * 2 + 1] = digits[bytes[i] & 15]; }
        return new String(out);
    }

    private int integerCall(int transaction, long value) throws RemoteException {
        IBinder binder = remote;
        if (binder == null) throw new RemoteException("disconnected");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CLIENT);
            data.writeLong(value);
            if (!binder.transact(transaction, data, reply, 0)) throw new RemoteException("rejected");
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void registerCallback(boolean yes) throws RemoteException {
        IBinder binder = remote;
        if (binder == null) throw new RemoteException("disconnected");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CLIENT);
            data.writeStrongBinder(callback);
            if (!binder.transact(yes ? TX_REGISTER : TX_UNREGISTER, data, reply, 0)) throw new RemoteException("rejected");
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private final Binder callback = new Binder() {
        { attachInterface(null, CALLBACK); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(CALLBACK); return true; }
            if (code == 1 || code == 3) {
                data.enforceInterface(CALLBACK);
                long topic = 0, timestamp = 0;
                byte[] payload = null;
                if (data.readInt() != 0) {
                    topic = data.readLong();
                    timestamp = data.readLong();
                    int length = data.readInt();
                    if (length >= 0 && length <= 65536) { payload = new byte[length]; data.readByteArray(payload); }
                }
                if (payload != null && !closed.get())
                    recorder.answer(topic, payload, SystemClock.elapsedRealtime(), System.currentTimeMillis());
                if (reply != null) {
                    reply.writeNoException();
                    if (code == 3) reply.writeInt(0);
                    reply.writeInt(0);
                }
                return true;
            }
            if (code == 2) { data.enforceInterface(CALLBACK); data.readInt(); if (reply != null) reply.writeNoException(); return true; }
            return super.onTransact(code, data, reply, flags);
        }
    };

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            for (long topic : ANSWER_TOPICS) try { integerCall(TX_UNSUBSCRIBE, topic); } catch (Exception ignored) { }
            try { integerCall(TX_STOP, SERVICE_PILOT); } catch (Exception ignored) { }
            try { integerCall(TX_STOP, SERVICE_PATH); } catch (Exception ignored) { }
            if (registered) try { registerCallback(false); } catch (Exception ignored) { }
        } finally {
            if (bound) try { context.unbindService(connection); } catch (RuntimeException ignored) { }
            bound = false;
            recorder.note("answer_listener_closed", session);
        }
    }
}
