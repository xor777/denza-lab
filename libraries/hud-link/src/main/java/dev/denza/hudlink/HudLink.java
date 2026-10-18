package dev.denza.hudlink;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

/**
 * The HUD for a source that may have a host (docs/hud-output-architecture.md, 4-5): the YandexHUD
 * module. Its calls go through Denza Apps while Denza Apps hosts it, and to its own
 * {@link HudOutput} when there is no host, the host refused or went away - without the module
 * knowing which ({@link HudRelay}).
 *
 * <ul>
 * <li>The host is asked for at the first publish and again every {@link #RECONNECT_MS} while
 *     there is none. While it is being bound, calls wait for it at most {@link #HOST_WAIT_MS};
 *     then the source writes itself.
 * <li>A host lost mid-route: the source writes itself at once, and goes back to the host when it
 *     attaches again, its own session stopping without the clear.
 * </ul>
 *
 * <p>Everything on {@code handler}'s thread, which must not be the main thread: attaching is a
 * two-way Binder call. Calls made in one turn of the thread go to the host as one batch.
 */
public final class HudLink implements HudSink {
    public static final long RECONNECT_MS = 5_000L;
    public static final long HOST_WAIT_MS = 1_000L;

    private final Handler handler;
    private final String tag;
    private final HudOutput own;
    private final HudRelay relay;
    private final HudHostClient host;
    private final Runnable flush = this::flushNow;
    private final Runnable giveUpWaiting = this::giveUpWaiting;
    private boolean flushQueued;
    private long nextConnect;
    private boolean closed;

    /** {@code listener}: the own session's events, as for {@link HudOutput}. */
    public HudLink(Context context, Handler handler, String tag, HudSession.Listener listener) {
        this.handler = handler;
        this.tag = tag;
        this.own = new HudOutput(context, handler, tag, listener);
        this.relay = new HudRelay(SystemClock::uptimeMillis, new OwnOutput());
        this.host = new HudHostClient(context, handler, tag, new HudHostClient.Listener() {
            @Override
            public void onAttached() {
                handler.removeCallbacks(giveUpWaiting);
                Log.i(tag, "HUD through the host");
                relay.attach(HudLink.this.host::send);
            }

            @Override
            public void onLost() {
                handler.removeCallbacks(giveUpWaiting);
                nextConnect = SystemClock.uptimeMillis() + RECONNECT_MS;
                if (relay.state() != HudRelay.State.LOCAL) {
                    Log.i(tag, "HUD by itself: no host");
                }
                relay.useLocal();
            }
        });
    }

    /** Where calls go now. */
    public HudRelay.State state() {
        return relay.state();
    }

    @Override
    public void layer(HudSource source, String id, HudRole role, HudRect rect, HudImage image,
            float opacity, long ttlMs) {
        lookForHost();
        relay.layer(source, id, role, rect, image, opacity, ttlMs);
        queueFlush();
    }

    @Override
    public void removeLayer(HudSource source, String id) {
        relay.removeLayer(source, id);
        queueFlush();
    }

    @Override
    public void fields(HudSource source, HudRole role, HudFields fields, long ttlMs) {
        lookForHost();
        relay.fields(source, role, fields, ttlMs);
        queueFlush();
    }

    @Override
    public void withdraw(HudSource source, HudRole role) {
        relay.withdraw(source, role);
        queueFlush();
    }

    @Override
    public void withdraw(HudSource source) {
        relay.withdraw(source);
        queueFlush();
    }

    /** The source is going: the host forgets it, the own output clears what it shows. */
    public void close() {
        closed = true;
        handler.removeCallbacks(giveUpWaiting);
        for (HudSource source : HudSource.values()) {
            relay.withdraw(source);
        }
        flushNow();
        host.disconnect();
        relay.useLocal();
    }

    private void lookForHost() {
        if (closed || relay.state() == HudRelay.State.REMOTE || host.connecting()) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now < nextConnect) {
            return;
        }
        if (host.connect()) {
            relay.waitForHost();
            if (relay.state() == HudRelay.State.WAITING) {
                handler.postDelayed(giveUpWaiting, HOST_WAIT_MS);
            }
        } else {
            nextConnect = now + RECONNECT_MS;
        }
    }

    private void giveUpWaiting() {
        if (relay.state() == HudRelay.State.WAITING) {
            Log.i(tag, "HUD by itself: the host did not answer");
            relay.useLocal();
        }
    }

    private void queueFlush() {
        if (!flushQueued) {
            flushQueued = true;
            handler.post(flush);
        }
    }

    private void flushNow() {
        flushQueued = false;
        handler.removeCallbacks(flush);
        relay.flush();
    }

    private final class OwnOutput implements HudRelay.Local {
        @Override
        public void layer(HudSource source, String id, HudRole role, HudRect rect, HudImage image,
                float opacity, long ttlMs) {
            own.layer(source, id, role, rect, image, opacity, ttlMs);
        }

        @Override
        public void removeLayer(HudSource source, String id) {
            own.removeLayer(source, id);
        }

        @Override
        public void fields(HudSource source, HudRole role, HudFields fields, long ttlMs) {
            own.fields(source, role, fields, ttlMs);
        }

        @Override
        public void withdraw(HudSource source, HudRole role) {
            own.withdraw(source, role);
        }

        @Override
        public void withdraw(HudSource source) {
            own.withdraw(source);
        }

        @Override
        public void handOver() {
            own.handOver();
        }
    }
}
