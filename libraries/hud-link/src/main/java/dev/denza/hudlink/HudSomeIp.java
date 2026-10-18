package dev.denza.hudlink;

import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * The Binder calls to the stock SOME/IP service. Session policy - when to bind, retry, repeat a
 * clear, give up - stays with the caller; this is only the wire. Each caller binds with its own
 * package as the intent type, which is what keeps two apps' sessions apart in the service.
 */
public final class HudSomeIp {
    /** No binder to call. */
    public static final int NO_BINDER = -100;
    /** The call threw; logged under the caller's tag. */
    public static final int FAILED = -200;
    /** {@link IBinder#transact} returned false: the service did not handle the code. */
    public static final int NOT_HANDLED = -201;

    private HudSomeIp() {
    }

    public static Intent bindIntent(String callerPackage) {
        return new Intent(HudProtocol.ACTION)
                .setComponent(new ComponentName(HudProtocol.PACKAGE, HudProtocol.SERVICE_CLASS))
                .setType(callerPackage);
    }

    /** Starts the HUD navigation session; the service's result, {@code 0} when started. */
    public static int start(IBinder binder, String tag) {
        return call(binder, HudProtocol.TX_START, HudProtocol.SERVICE_HUD_NAVI, null, tag);
    }

    public static int stop(IBinder binder, String tag) {
        return call(binder, HudProtocol.TX_STOP, HudProtocol.SERVICE_HUD_NAVI, null, tag);
    }

    /** Fires one event of the started session: {@link HudProtocol#TOPIC_ROAD} or {@code TOPIC_MAP}. */
    public static int fire(IBinder binder, long topic, byte[] payload, String tag) {
        return call(binder, HudProtocol.TX_FIRE, topic, payload, tag);
    }

    private static int call(IBinder binder, int code, long id, byte[] payload, String tag) {
        if (binder == null) {
            return NO_BINDER;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HudProtocol.DESCRIPTOR);
            if (code == HudProtocol.TX_FIRE) {
                data.writeInt(1);
                data.writeLong(id);
                data.writeLong(0L);
                data.writeInt(payload.length);
                data.writeByteArray(payload);
            } else {
                data.writeLong(id);
            }
            if (!binder.transact(code, data, reply, 0)) {
                return NOT_HANDLED;
            }
            reply.readException();
            return reply.readInt();
        } catch (RuntimeException | RemoteException error) {
            Log.w(tag, "HUD transaction " + code + " failed", error);
            return FAILED;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
