package dev.denza.hudlink;

import java.util.Arrays;

/**
 * One session with the stock SOME/IP service and the one policy every HUD writer keeps on it
 * (docs/hud-output-architecture.md, 6). Before the compositor Denza Apps and the YandexHUD module
 * each had their own; this is both, made one:
 *
 * <ul>
 * <li>Bind and start when there is something to show. A refused bind, a failed start, a lost
 *     connection or a failed send drops the binding and tries again after {@link #RETRY_MS}; a
 *     binding that has not started within {@link #BIND_DEADLINE_MS} is dropped the same way.
 * <li>The road packet goes at once when it changed, but not more often than every
 *     {@link #ROAD_MIN_INTERVAL_MS}, and again every {@link #ROAD_HEARTBEAT_MS} when it did not.
 * <li>A new map frame goes at most every {@link #MAP_MIN_INTERVAL_MS}, the newest one when
 *     several came between; the last one goes again every {@link #MAP_REPEAT_MS}, as the stock
 *     navigator repeats its own. A map window that stops being fed gets {@link #CLEAR_SENDS}
 *     black frames {@link #CLEAR_REPEAT_MS} apart: the event travels over UDP, and one lost black
 *     frame would leave the last map on the glass.
 * <li>Nothing left to show: the clear - a black frame and the inactive road packet,
 *     {@link #CLEAR_SENDS} times {@link #CLEAR_REPEAT_MS} apart - then stop and unbind once
 *     {@link #STOP_DELAY_MS} have passed and the last clear is out. Something to show again
 *     before that ends the clear and sends at once.
 * <li>Another writer takes over ({@link #handOver}): stop and unbind at once, without the
 *     clear, so the glass keeps the last picture until the other writer's comes.
 * </ul>
 *
 * <p>No thread and no clock of its own: the owner calls {@link #pump} whenever something changed
 * and again at the time it returns, all on one thread. That is what lets the policy run on the
 * JVM against a test clock and a recording port.
 */
public final class HudSession {
    public static final long RETRY_MS = 1_000L;
    public static final long BIND_DEADLINE_MS = 3_000L;
    public static final long ROAD_HEARTBEAT_MS = 500L;
    /** Four a second at most. */
    public static final long ROAD_MIN_INTERVAL_MS = 250L;
    /** Twelve a second at most, the rate Denza Apps' map sources take pictures at. */
    public static final long MAP_MIN_INTERVAL_MS = 83L;
    public static final long MAP_REPEAT_MS = 1_000L;
    public static final int CLEAR_SENDS = 3;
    public static final long CLEAR_REPEAT_MS = 120L;
    public static final long STOP_DELAY_MS = 350L;
    /** What {@link #pump} returns when nothing is due until something changes. */
    public static final long IDLE = Long.MAX_VALUE;

    private static final long NEVER = Long.MIN_VALUE / 2;
    private static final long NO_FRAME = Long.MIN_VALUE;

    /** The stock service, Binder on the car ({@link HudSomeIp}) and a recording fake in tests. */
    public interface Port {
        /**
         * Asks for the service; false when the request was refused. The answer comes later, as
         * {@link HudSession#onConnected} or {@link HudSession#onDisconnected}, for this binding
         * only.
         */
        boolean bind();

        void unbind();

        /** The service's answer, {@code 0} when started. */
        int start();

        int stop();

        int fire(long topic, byte[] payload);
    }

    /** Why the session dropped its binding while there was something to show. */
    public enum Trouble {
        BIND_REFUSED,
        BIND_FAILED,
        BIND_TIMEOUT,
        DISCONNECTED,
        START_FAILED,
        FIRE_FAILED,
    }

    /** What the session did, for a status line and a log. No route data. */
    public interface Listener {
        void onBinding();

        void onStarted(int result);

        /** A send and the service's answer; {@code clearing} for the sends of the clear. */
        void onFired(long topic, int result, boolean clearing);

        /** The binding was dropped; it is tried again after {@link #RETRY_MS}. */
        void onTrouble(Trouble trouble, int result);

        /** Nothing left to show: the clear has begun. */
        void onClearing();

        /** The session is stopped and unbound. */
        void onStopped();
    }

    private final HudClock clock;
    private final Port port;
    private final Listener listener;
    private final byte[] blankMap;

    // What to show, from show().
    private byte[] road;
    private byte[] clear = HudRoadPacket.clear(false);
    private byte[] map;
    private long mapVersion;

    private boolean bound;
    private boolean connected;
    private boolean started;
    private long bindingSince;
    private long retryAt = NEVER;

    private byte[] sentRoad;
    private long roadAt = NEVER;
    private long sentMapVersion = NO_FRAME;
    private long mapAt = NEVER;
    private int blanksLeft;
    private long blankAt;

    private int clearsLeft;
    private long clearAt;
    private long stopAt = NEVER;

    /** {@code blankMap}: the map event with an all-black frame, what empties the window. */
    public HudSession(HudClock clock, Port port, Listener listener, byte[] blankMap) {
        this.clock = clock;
        this.port = port;
        this.listener = listener;
        this.blankMap = blankMap;
    }

    /**
     * What the HUD should show. {@code road}: the road packet while navigating, null for nothing
     * to show. {@code clear}: the clear that empties what {@code road} opens; null keeps the last.
     * {@code map}: the map event, null for an empty window; {@code mapVersion} changes with it.
     */
    public void show(byte[] road, byte[] clear, byte[] map, long mapVersion) {
        this.road = road;
        if (clear != null) {
            this.clear = clear;
        }
        this.map = map;
        this.mapVersion = mapVersion;
    }

    /** The service answered the current binding. */
    public void onConnected() {
        if (bound) {
            connected = true;
        }
    }

    /** The service of the current binding went away, or the binding died. */
    public void onDisconnected() {
        if (!bound) {
            return;
        }
        if (road != null) {
            recover(clock.now(), Trouble.DISCONNECTED, 0);
        } else {
            finish();
        }
    }

    /**
     * Another writer takes the HUD over (docs/hud-output-architecture.md, 5): the session stops
     * and unbinds now and sends no clear, which would blank the glass between the two writers.
     * What was to be shown is dropped; {@link #show} starts a new session.
     */
    public void handOver() {
        road = null;
        map = null;
        if (started) {
            port.stop();
        }
        if (bound || stopAt != NEVER) {
            finish();
        }
        retryAt = NEVER;
    }

    /** Does what is due now; returns when something next will be, or {@link #IDLE}. */
    public long pump() {
        long now = clock.now();
        return road != null ? show(now) : quiet(now);
    }

    private long show(long now) {
        if (stopAt != NEVER) {
            // Something to show again while the clear was under way: it ends, and the glass, now
            // blank, gets everything at once.
            clearsLeft = 0;
            stopAt = NEVER;
            forgetSent();
        }
        if (!bound) {
            if (now < retryAt) {
                return retryAt;
            }
            bind(now);
            if (!bound) {
                return retryAt;
            }
        }
        if (!started) {
            if (connected) {
                start(now);
            } else if (now - bindingSince >= BIND_DEADLINE_MS) {
                recover(now, Trouble.BIND_TIMEOUT, 0);
            }
            if (!started) {
                return bound ? bindingSince + BIND_DEADLINE_MS : retryAt;
            }
        }
        long next = sendRoad(now);
        if (started) {
            next = Math.min(next, sendMap(now));
        }
        return started ? next : retryAt;
    }

    private long sendRoad(long now) {
        boolean changed = !Arrays.equals(road, sentRoad);
        long since = now - roadAt;
        if (since < ROAD_HEARTBEAT_MS && !(changed && since >= ROAD_MIN_INTERVAL_MS)) {
            return roadAt + (changed ? ROAD_MIN_INTERVAL_MS : ROAD_HEARTBEAT_MS);
        }
        if (!fire(now, HudProtocol.TOPIC_ROAD, road)) {
            return retryAt;
        }
        sentRoad = road;
        roadAt = now;
        return now + ROAD_HEARTBEAT_MS;
    }

    private long sendMap(long now) {
        if (map != null) {
            blanksLeft = 0;
            long since = now - mapAt;
            if (mapVersion != sentMapVersion) {
                if (since < MAP_MIN_INTERVAL_MS) {
                    return mapAt + MAP_MIN_INTERVAL_MS;
                }
            } else if (since < MAP_REPEAT_MS) {
                return mapAt + MAP_REPEAT_MS;
            }
            if (!fire(now, HudProtocol.TOPIC_MAP, map)) {
                return retryAt;
            }
            sentMapVersion = mapVersion;
            mapAt = now;
            return now + MAP_REPEAT_MS;
        }
        if (sentMapVersion != NO_FRAME) {
            // A frame of ours is on the glass and none follows it.
            sentMapVersion = NO_FRAME;
            blanksLeft = CLEAR_SENDS;
            blankAt = now;
        }
        if (blanksLeft == 0) {
            return IDLE;
        }
        if (now < blankAt) {
            return blankAt;
        }
        if (!fire(now, HudProtocol.TOPIC_MAP, blankMap)) {
            return retryAt;
        }
        blanksLeft--;
        blankAt = now + CLEAR_REPEAT_MS;
        return blanksLeft > 0 ? blankAt : IDLE;
    }

    private long quiet(long now) {
        if (started && stopAt == NEVER) {
            clearsLeft = CLEAR_SENDS;
            clearAt = now;
            stopAt = now + STOP_DELAY_MS;
            listener.onClearing();
        }
        if (stopAt == NEVER) {
            // Nothing of ours on the glass: a binding still under way just goes.
            if (bound) {
                finish();
            }
            return IDLE;
        }
        if (clearsLeft > 0 && now >= clearAt) {
            listener.onFired(HudProtocol.TOPIC_MAP, port.fire(HudProtocol.TOPIC_MAP, blankMap), true);
            listener.onFired(HudProtocol.TOPIC_ROAD, port.fire(HudProtocol.TOPIC_ROAD, clear), true);
            clearsLeft--;
            clearAt = now + CLEAR_REPEAT_MS;
        }
        if (clearsLeft > 0) {
            return clearAt;
        }
        if (now < stopAt) {
            return stopAt;
        }
        port.stop();
        finish();
        return IDLE;
    }

    private void bind(long now) {
        listener.onBinding();
        bindingSince = now;
        connected = false;
        boolean taken;
        try {
            taken = port.bind();
        } catch (RuntimeException error) {
            recover(now, Trouble.BIND_FAILED, 0);
            return;
        }
        if (taken) {
            bound = true;
        } else {
            recover(now, Trouble.BIND_REFUSED, 0);
        }
    }

    private void start(long now) {
        int result = port.start();
        listener.onStarted(result);
        // 13: another session - the other writer just handing over, a probe - started the
        // service; this one shares it.
        if (result == 0 || result == HudProtocol.ALREADY_STARTED) {
            started = true;
            forgetSent();
        } else {
            recover(now, Trouble.START_FAILED, result);
        }
    }

    private boolean fire(long now, long topic, byte[] payload) {
        int result = port.fire(topic, payload);
        listener.onFired(topic, result, false);
        if (result != 0) {
            recover(now, Trouble.FIRE_FAILED, result);
            return false;
        }
        return true;
    }

    /** Drops the binding and tries again after {@link #RETRY_MS}, while there is something to show. */
    private void recover(long now, Trouble trouble, int result) {
        listener.onTrouble(trouble, result);
        drop();
        retryAt = now + RETRY_MS;
    }

    /** Ends the session, the clear done or cut short: stopped and unbound, nothing scheduled. */
    private void finish() {
        drop();
        clearsLeft = 0;
        stopAt = NEVER;
        listener.onStopped();
    }

    private void drop() {
        if (bound) {
            port.unbind();
        }
        bound = false;
        connected = false;
        started = false;
        forgetSent();
    }

    /** Nothing sent on this session yet: the next road packet and frame go at once. */
    private void forgetSent() {
        sentRoad = null;
        roadAt = NEVER;
        sentMapVersion = NO_FRAME;
        mapAt = NEVER;
        blanksLeft = 0;
    }
}
