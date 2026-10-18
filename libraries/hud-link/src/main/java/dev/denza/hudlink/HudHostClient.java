package dev.denza.hudlink;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * A source's Binder end to the host's service ({@link HudHostProtocol}). It binds, attaches with
 * a token whose death tells the host the source is gone, and sends batches one-way. Everything on
 * {@code handler}'s thread, the two-way attach too: a source's own worker, never the main thread.
 */
public final class HudHostClient {
    public interface Listener {
        /** The host accepted the source; batches go to it from now on. */
        void onAttached();

        /** The host is gone, refused the source, or the binding never came. */
        void onLost();
    }

    private final Context context;
    private final Handler handler;
    private final String tag;
    private final Listener listener;
    private final IBinder token = new Binder();
    private final IBinder.DeathRecipient death = () -> postLost("host died");

    private ServiceConnection connection;
    private IBinder host;
    private int generation;

    public HudHostClient(Context context, Handler handler, String tag, Listener listener) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.tag = tag;
        this.listener = listener;
    }

    /** Whether a binding is asked for or held. */
    public boolean connecting() {
        return connection != null;
    }

    /** Asks for the host; false when there is none to bind - not installed, or not visible. */
    public boolean connect() {
        if (connection != null) {
            return true;
        }
        final int token = ++generation;
        connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                handler.post(() -> attach(token, service));
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                handler.post(() -> lost(token, "host disconnected"));
            }

            @Override
            public void onBindingDied(ComponentName name) {
                handler.post(() -> lost(token, "host binding died"));
            }

            @Override
            public void onNullBinding(ComponentName name) {
                handler.post(() -> lost(token, "host gave no binder"));
            }
        };
        Intent intent = new Intent().setComponent(
                new ComponentName(HudHostProtocol.PACKAGE, HudHostProtocol.SERVICE_CLASS));
        boolean bound;
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException error) {
            Log.w(tag, "host bind failed", error);
            bound = false;
        }
        if (!bound) {
            release();
        }
        return bound;
    }

    /** One batch, one-way; false when the host is gone (the loss also reaches the listener). */
    public boolean send(byte[] batch) {
        IBinder target = host;
        if (target == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(HudHostProtocol.DESCRIPTOR);
            data.writeByteArray(batch);
            target.transact(HudHostProtocol.TX_CALLS, data, null, IBinder.FLAG_ONEWAY);
            return true;
        } catch (RemoteException | RuntimeException error) {
            Log.w(tag, "host send failed", error);
            postLost("host send failed");
            return false;
        } finally {
            data.recycle();
        }
    }

    /** Tells the host the source is done and lets the binding go. */
    public void disconnect() {
        IBinder target = host;
        if (target != null) {
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken(HudHostProtocol.DESCRIPTOR);
                target.transact(HudHostProtocol.TX_DETACH, data, null, IBinder.FLAG_ONEWAY);
            } catch (RemoteException | RuntimeException ignored) {
                // Gone already: its death ends what this source published.
            } finally {
                data.recycle();
            }
        }
        generation++;
        release();
    }

    private void attach(int token, IBinder service) {
        if (token != generation) {
            return;
        }
        int answer;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HudHostProtocol.DESCRIPTOR);
            data.writeInt(HudHostProtocol.VERSION);
            data.writeStrongBinder(this.token);
            if (!service.transact(HudHostProtocol.TX_ATTACH, data, reply, 0)) {
                answer = -1;
            } else {
                reply.readException();
                answer = reply.readInt();
            }
        } catch (RemoteException | RuntimeException error) {
            Log.w(tag, "host attach failed", error);
            answer = -1;
        } finally {
            reply.recycle();
            data.recycle();
        }
        if (answer != HudHostProtocol.ACCEPTED) {
            Log.w(tag, "host refused the source: " + answer);
            lost(token, "host refused");
            return;
        }
        try {
            service.linkToDeath(death, 0);
        } catch (RemoteException gone) {
            lost(token, "host died while attaching");
            return;
        }
        host = service;
        Log.i(tag, "host attached");
        listener.onAttached();
    }

    private void postLost(String what) {
        final int token = generation;
        handler.post(() -> lost(token, what));
    }

    private void lost(int token, String what) {
        if (token != generation || connection == null) {
            return;
        }
        Log.w(tag, what);
        generation++;
        release();
        listener.onLost();
    }

    private void release() {
        IBinder previous = host;
        host = null;
        if (previous != null) {
            try {
                previous.unlinkToDeath(death, 0);
            } catch (RuntimeException ignored) {
                // Not linked.
            }
        }
        ServiceConnection held = connection;
        connection = null;
        if (held != null) {
            try {
                context.unbindService(held);
            } catch (RuntimeException ignored) {
                // Not bound.
            }
        }
    }
}
