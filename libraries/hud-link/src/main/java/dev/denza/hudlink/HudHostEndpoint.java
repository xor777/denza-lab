package dev.denza.hudlink;

import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * The host's Binder end ({@link HudHostProtocol}): what a source in another app publishes goes
 * into the host's own {@link HudSink} as the source its signature makes it, on {@code handler}'s
 * thread. A source that detaches or dies takes everything it published with it. One app per
 * source: a second attach as the same source replaces the first.
 */
public final class HudHostEndpoint extends Binder {
    /** Decides who a caller is; on a Binder thread. */
    public interface Gate {
        /** The source {@code uid} publishes as, or null to refuse it. */
        HudSource admit(int uid);
    }

    /** What the host hears about its sources; on {@code handler}'s thread. */
    public interface Listener {
        void onAttached(HudSource source, int uid);

        void onDetached(HudSource source, int uid);
    }

    private final Handler handler;
    private final HudSink sink;
    private final Gate gate;
    private final Listener listener;
    private final String tag;
    // On handler's thread: the attached caller of each source.
    private final Map<HudSource, Attached> attached = new HashMap<>();

    public HudHostEndpoint(Handler handler, HudSink sink, Gate gate, Listener listener, String tag) {
        this.handler = handler;
        this.sink = sink;
        this.gate = gate;
        this.listener = listener;
        this.tag = tag;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            return super.onTransact(code, data, reply, flags);
        }
        final int uid = getCallingUid();
        switch (code) {
            case HudHostProtocol.TX_ATTACH: {
                data.enforceInterface(HudHostProtocol.DESCRIPTOR);
                int version = data.readInt();
                final IBinder token = data.readStrongBinder();
                int answer;
                final HudSource source = version == HudHostProtocol.VERSION ? gate.admit(uid) : null;
                if (version != HudHostProtocol.VERSION) {
                    answer = HudHostProtocol.REFUSED_VERSION;
                } else if (source == null || token == null) {
                    answer = HudHostProtocol.REFUSED_CALLER;
                } else {
                    answer = HudHostProtocol.ACCEPTED;
                    handler.post(() -> attach(source, uid, token));
                }
                if (answer != HudHostProtocol.ACCEPTED) {
                    Log.w(tag, "source refused: uid=" + uid + " answer=" + answer);
                }
                reply.writeNoException();
                reply.writeInt(answer);
                return true;
            }
            case HudHostProtocol.TX_CALLS: {
                data.enforceInterface(HudHostProtocol.DESCRIPTOR);
                final byte[] batch = data.createByteArray();
                handler.post(() -> play(uid, batch));
                return true;
            }
            case HudHostProtocol.TX_DETACH:
                data.enforceInterface(HudHostProtocol.DESCRIPTOR);
                handler.post(() -> detach(uid, null));
                return true;
            default:
                return super.onTransact(code, data, reply, flags);
        }
    }

    private void attach(HudSource source, int uid, IBinder token) {
        Attached previous = attached.remove(source);
        if (previous != null) {
            previous.unlink();
            sink.withdraw(source);
        }
        Attached next = new Attached(source, uid, token);
        try {
            token.linkToDeath(next, 0);
        } catch (RemoteException gone) {
            return;
        }
        attached.put(source, next);
        Log.i(tag, "source attached: " + source + " uid=" + uid);
        listener.onAttached(source, uid);
    }

    private void play(int uid, byte[] batch) {
        Attached caller = byUid(uid);
        if (caller == null || batch == null) {
            return;
        }
        if (!HudHostProtocol.replay(batch, caller.source, sink)) {
            Log.w(tag, "unreadable batch from " + caller.source);
        }
    }

    /** {@code token}: the attach that died, or null for whichever this uid holds. */
    private void detach(int uid, IBinder token) {
        Attached caller = byUid(uid);
        if (caller == null || (token != null && caller.token != token)) {
            return;
        }
        attached.remove(caller.source);
        caller.unlink();
        sink.withdraw(caller.source);
        Log.i(tag, "source detached: " + caller.source + " uid=" + uid);
        listener.onDetached(caller.source, uid);
    }

    private Attached byUid(int uid) {
        for (Attached each : attached.values()) {
            if (each.uid == uid) {
                return each;
            }
        }
        return null;
    }

    private final class Attached implements IBinder.DeathRecipient {
        final HudSource source;
        final int uid;
        final IBinder token;

        Attached(HudSource source, int uid, IBinder token) {
            this.source = source;
            this.uid = uid;
            this.token = token;
        }

        @Override
        public void binderDied() {
            handler.post(() -> detach(uid, token));
        }

        void unlink() {
            try {
                token.unlinkToDeath(this, 0);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
        }
    }
}
