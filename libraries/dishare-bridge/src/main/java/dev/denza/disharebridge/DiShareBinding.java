package dev.denza.disharebridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

/**
 * One binding to a DiShare service, by action (DiShare's {@code onBind} checks it), released
 * exactly once. The rules are {@link DiShareBindingState}'s: a disconnect leaves the binding
 * owed, {@link #release()} always unbinds what {@link #bind()} registered, and only the first
 * connection reaches the listener.
 */
final class DiShareBinding {
    interface Listener {
        void onConnected(IBinder binder);

        /** DiShare's process went away while connected; the binding is still held. */
        void onDisconnected();
    }

    private static final String DISHARE_PACKAGE = "com.byd.dishare";

    private final Context context;
    private final String action;
    private final Listener listener;
    private final DiShareBindingState state = new DiShareBindingState();
    private IBinder binder;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!state.onConnected()) {
                return;
            }
            binder = service;
            listener.onConnected(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            if (state.onDisconnected()) {
                listener.onDisconnected();
            }
        }
    };

    DiShareBinding(Context context, String action, Listener listener) {
        this.context = context;
        this.action = action;
        this.listener = listener;
    }

    /**
     * Asks the system to bind; false when it refused. Either way the binding has to be
     * {@link #release() released}. Throws what {@code bindService} throws.
     */
    boolean bind() {
        Intent intent = new Intent();
        intent.setAction(action);
        intent.setPackage(DISHARE_PACKAGE);
        state.onBindRequested();
        return context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    /** The connected service, or null before the connection, after a disconnect or release. */
    IBinder binder() {
        return binder;
    }

    void release() {
        binder = null;
        if (!state.release()) {
            return;
        }
        try {
            context.unbindService(connection);
        } catch (RuntimeException ignored) {
            // Never registered: bindService threw, or the system already dropped it.
        }
    }
}
