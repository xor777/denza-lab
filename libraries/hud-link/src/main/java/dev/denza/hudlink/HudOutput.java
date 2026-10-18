package dev.denza.hudlink;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/**
 * The compositor on an Android thread (docs/hud-output-architecture.md, 6): Binder to the stock
 * SOME/IP service under the caller's package, {@code handler} for the session's times, Canvas for
 * a window of several layers. The app that is the HUD's host creates one; its sources publish
 * through it as a {@link HudSink}.
 *
 * <p>Everything happens on {@code handler}'s thread, and sources call it there. Changes made in
 * one turn of the thread are composed once, on the next: a source that takes its map and its
 * hints away together sends one clear, not a black map first.
 */
public final class HudOutput implements HudSink {
    private final Context context;
    private final Handler handler;
    private final String tag;
    private final HudCompositor compositor;
    private final Runnable pumpNow = this::pump;
    private final Runnable pumpLater = this::pump;
    private boolean queued;

    // The binding: its ServiceConnection, and a token that a callback of an older one fails.
    private ServiceConnection connection;
    private IBinder binder;
    private int generation;

    /** {@code tag}: the caller's log tag; {@code listener}: the session's events, on {@code handler}. */
    public HudOutput(Context context, Handler handler, String tag, HudSession.Listener listener) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.tag = tag;
        this.compositor = new HudCompositor(
                SystemClock::uptimeMillis, new BinderPort(), listener, new HudCanvasRenderer(tag));
    }

    @Override
    public void layer(HudSource source, String id, HudRole role, HudRect rect, HudImage image,
            float opacity, long ttlMs) {
        compositor.layer(source, id, role, rect, image, opacity, ttlMs);
        changed();
    }

    @Override
    public void removeLayer(HudSource source, String id) {
        compositor.removeLayer(source, id);
        changed();
    }

    @Override
    public void fields(HudSource source, HudRole role, HudFields fields, long ttlMs) {
        compositor.fields(source, role, fields, ttlMs);
        changed();
    }

    @Override
    public void withdraw(HudSource source, HudRole role) {
        compositor.withdraw(source, role);
        changed();
    }

    @Override
    public void withdraw(HudSource source) {
        compositor.withdraw(source);
        changed();
    }

    private void changed() {
        if (!queued) {
            queued = true;
            handler.post(pumpNow);
        }
    }

    private void pump() {
        queued = false;
        // Whichever of the two comes first does the work of both.
        handler.removeCallbacks(pumpNow);
        handler.removeCallbacks(pumpLater);
        long next = compositor.pump();
        if (next != HudSession.IDLE) {
            handler.postAtTime(pumpLater, next);
        }
    }

    private void connected(int token, IBinder service) {
        if (token != generation) {
            return;
        }
        binder = service;
        Log.i(tag, "HUD service connected");
        compositor.onConnected();
        changed();
    }

    private void lost(int token, String what) {
        if (token != generation) {
            return;
        }
        binder = null;
        Log.w(tag, "HUD service " + what);
        compositor.onDisconnected();
        changed();
    }

    private void release() {
        ServiceConnection previous = connection;
        connection = null;
        binder = null;
        if (previous != null) {
            try {
                context.unbindService(previous);
            } catch (RuntimeException error) {
                Log.w(tag, "HUD unbind failed", error);
            }
        }
    }

    private final class BinderPort implements HudSession.Port {
        @Override
        public boolean bind() {
            final int token = ++generation;
            connection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    handler.post(() -> connected(token, service));
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    handler.post(() -> lost(token, "disconnected"));
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    handler.post(() -> lost(token, "binding died"));
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    handler.post(() -> lost(token, "gave no binder"));
                }
            };
            boolean bound;
            try {
                bound = context.bindService(HudSomeIp.bindIntent(context.getPackageName()),
                        connection, Context.BIND_AUTO_CREATE);
            } catch (RuntimeException error) {
                Log.e(tag, "HUD bind failed", error);
                connection = null;
                throw error;
            }
            Log.i(tag, "HUD bind=" + bound);
            if (!bound) {
                // Refused, yet the connection may stay registered; this releases it if so.
                ServiceConnection refused = connection;
                connection = null;
                try {
                    context.unbindService(refused);
                } catch (RuntimeException notRegistered) {
                    // Nothing was held.
                }
            }
            return bound;
        }

        @Override
        public void unbind() {
            generation++;
            release();
        }

        @Override
        public int start() {
            int result = HudSomeIp.start(binder, tag);
            Log.i(tag, "HUD start ret=" + result);
            return result;
        }

        @Override
        public int stop() {
            int result = HudSomeIp.stop(binder, tag);
            Log.i(tag, "HUD stop ret=" + result);
            return result;
        }

        @Override
        public int fire(long topic, byte[] payload) {
            return HudSomeIp.fire(binder, topic, payload, tag);
        }
    }
}
