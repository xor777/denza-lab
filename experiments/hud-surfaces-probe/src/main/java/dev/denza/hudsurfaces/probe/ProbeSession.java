package dev.denza.hudsurfaces.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;
import dev.denza.hudlink.HudSomeIp;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One SOME/IP session: a binding to the stock service under one intent type, and service 266
 * started through it. Blocking calls, made from the runner's one worker thread.
 */
final class ProbeSession {
    /** bindService refused the intent. */
    static final int BIND_REFUSED = -1;
    /** No onServiceConnected within the timeout. */
    static final int BIND_TIMEOUT = -2;
    /** tx 4: started; firmware also registers this session when another one already started it. */
    static final int STARTED = 0;
    static final int ALREADY_STARTED = 13;

    final SessionId id;
    final String type;
    private final Context context;
    private final CountDownLatch connected = new CountDownLatch(1);
    private volatile IBinder binder;
    private boolean bound;
    private boolean started;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            connected.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            Log.w(SurfacesActivity.TAG, "session " + id + ": stock SOME/IP service disconnected");
        }
    };

    ProbeSession(Context context, SessionId id) {
        this.context = context.getApplicationContext();
        this.id = id;
        this.type = id.type(this.context.getPackageName());
    }

    static boolean offerStarted(int result) {
        return result == STARTED || result == ALREADY_STARTED;
    }

    /** Binds and starts service 266; the tx 4 result, or a negative local error. */
    int open(long timeoutMs) throws InterruptedException {
        bound = context.bindService(HudSomeIp.bindIntent(type), connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            return BIND_REFUSED;
        }
        if (!connected.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return BIND_TIMEOUT;
        }
        int result = HudSomeIp.start(binder, SurfacesActivity.TAG);
        started = offerStarted(result);
        return result;
    }

    int fire(long topic, byte[] payload) {
        return HudSomeIp.fire(binder, topic, payload, SurfacesActivity.TAG);
    }

    /** tx 5 for this session alone; the service withdraws the offer when it was the last one. */
    int stop() {
        int result = HudSomeIp.stop(binder, SurfacesActivity.TAG);
        started = false;
        return result;
    }

    boolean isBound() {
        return bound;
    }

    boolean isStarted() {
        return started;
    }

    void unbind() {
        if (bound) {
            try {
                context.unbindService(connection);
            } catch (RuntimeException error) {
                Log.w(SurfacesActivity.TAG, "session " + id + ": unbind failed", error);
            }
        }
        bound = false;
        started = false;
        binder = null;
    }
}
