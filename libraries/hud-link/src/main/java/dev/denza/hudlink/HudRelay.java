package dev.denza.hudlink;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A source's way to the HUD when the host may be another app (docs/hud-output-architecture.md, 5):
 * calls go to the host while one is attached and to the source's own compositor otherwise, and
 * never to both. What was published and has not died is kept, so whichever side takes over gets
 * the whole picture at once rather than a field at a time:
 *
 * <ul>
 * <li>Waiting for a host being bound: calls are only kept, so a route's start is not written by
 *     the source for the moment it takes the host to answer.
 * <li>The host attaches: the own compositor hands over - stops without the clear - and the host
 *     gets everything still alive in one batch.
 * <li>The host goes, or never came: everything still alive goes to the own compositor, which
 *     starts writing.
 * </ul>
 *
 * <p>Plain Java on one thread; {@link HudLink} is the Android side.
 */
public final class HudRelay implements HudSink {
    /** The source's own compositor ({@link HudOutput} on the car). */
    public interface Local extends HudSink {
        /** Another writer takes over: drop everything, stop without the clear. */
        void handOver();
    }

    /** The attached host. */
    public interface Remote {
        /** Sends one batch ({@link HudHostProtocol.Batch}); false when the host is gone. */
        boolean send(byte[] batch);
    }

    /** Where calls go. */
    public enum State {
        LOCAL,
        WAITING,
        REMOTE,
    }

    private static final HudSink NOWHERE = new HudSink() {
        @Override
        public void layer(HudSource source, String id, HudRole role, HudRect rect, HudImage image,
                float opacity, long ttlMs) {
        }

        @Override
        public void removeLayer(HudSource source, String id) {
        }

        @Override
        public void fields(HudSource source, HudRole role, HudFields fields, long ttlMs) {
        }

        @Override
        public void withdraw(HudSource source, HudRole role) {
        }

        @Override
        public void withdraw(HudSource source) {
        }
    };

    private final HudClock clock;
    private final Local local;
    private State state = State.LOCAL;
    private Remote remote;
    private HudHostProtocol.Batch pending = new HudHostProtocol.Batch();

    // What is alive, by source and layer id, and by source and role.
    private final Map<String, Layer> layers = new LinkedHashMap<>();
    private final Map<String, Fields> fields = new LinkedHashMap<>();

    public HudRelay(HudClock clock, Local local) {
        this.clock = clock;
        this.local = local;
    }

    public State state() {
        return state;
    }

    /**
     * A host is being bound and the own compositor is not writing: keep calls until it answers.
     * Ignored while the own compositor writes something alive, which goes on until the host
     * attaches.
     */
    public void waitForHost() {
        if (state == State.LOCAL && !alive()) {
            state = State.WAITING;
        }
    }

    /** The host is attached: the own compositor hands over and the host gets what is alive. */
    public void attach(Remote host) {
        // Also from WAITING: a clear the own compositor still has under way must not follow the
        // host's first picture.
        local.handOver();
        state = State.REMOTE;
        remote = host;
        pending = new HudHostProtocol.Batch();
        replay(pending);
        flush();
    }

    /** The host is gone or never came: what is alive goes to the own compositor. */
    public void useLocal() {
        if (state == State.LOCAL) {
            return;
        }
        state = State.LOCAL;
        remote = null;
        pending = new HudHostProtocol.Batch();
        replay(local);
    }

    /** Sends what this turn published to the host; the host gone, it goes to the own compositor. */
    public void flush() {
        if (state != State.REMOTE || pending.isEmpty()) {
            return;
        }
        byte[] batch = pending.toByteArray();
        pending = new HudHostProtocol.Batch();
        if (!remote.send(batch)) {
            useLocal();
        }
    }

    @Override
    public void layer(HudSource source, String id, HudRole role, HudRect rect, HudImage image,
            float opacity, long ttlMs) {
        layers.put(key(source, id), new Layer(source, id, role, rect, image, opacity, ttlMs, clock.now()));
        target().layer(source, id, role, rect, image, opacity, ttlMs);
    }

    @Override
    public void removeLayer(HudSource source, String id) {
        layers.remove(key(source, id));
        target().removeLayer(source, id);
    }

    @Override
    public void fields(HudSource source, HudRole role, HudFields values, long ttlMs) {
        fields.put(key(source, role.name()), new Fields(source, role, values, ttlMs, clock.now()));
        target().fields(source, role, values, ttlMs);
    }

    @Override
    public void withdraw(HudSource source, HudRole role) {
        fields.remove(key(source, role.name()));
        for (Iterator<Layer> each = layers.values().iterator(); each.hasNext(); ) {
            Layer layer = each.next();
            if (layer.source == source && layer.role == role) {
                each.remove();
            }
        }
        target().withdraw(source, role);
    }

    @Override
    public void withdraw(HudSource source) {
        for (Iterator<Layer> each = layers.values().iterator(); each.hasNext(); ) {
            if (each.next().source == source) {
                each.remove();
            }
        }
        for (Iterator<Fields> each = fields.values().iterator(); each.hasNext(); ) {
            if (each.next().source == source) {
                each.remove();
            }
        }
        target().withdraw(source);
    }

    private HudSink target() {
        switch (state) {
            case REMOTE:
                return pending;
            case WAITING:
                return NOWHERE;
            default:
                return local;
        }
    }

    /** Whether anything published has not died. */
    private boolean alive() {
        long now = clock.now();
        for (Layer layer : layers.values()) {
            if (layer.left(now) > 0) {
                return true;
            }
        }
        for (Fields published : fields.values()) {
            if (published.left(now) > 0) {
                return true;
            }
        }
        return false;
    }

    /** Everything alive into {@code sink}, each with the lifetime it has left. */
    private void replay(HudSink sink) {
        long now = clock.now();
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, Layer> entry : layers.entrySet()) {
            Layer layer = entry.getValue();
            long left = layer.left(now);
            if (left <= 0) {
                dead.add(entry.getKey());
            } else {
                sink.layer(layer.source, layer.id, layer.role, layer.rect, layer.image, layer.opacity, left);
            }
        }
        for (String key : dead) {
            layers.remove(key);
        }
        dead.clear();
        for (Map.Entry<String, Fields> entry : fields.entrySet()) {
            Fields published = entry.getValue();
            long left = published.left(now);
            if (left <= 0) {
                dead.add(entry.getKey());
            } else {
                sink.fields(published.source, published.role, published.values, left);
            }
        }
        for (String key : dead) {
            fields.remove(key);
        }
    }

    private static String key(HudSource source, String name) {
        return source.name() + "/" + name;
    }

    private static final class Layer {
        final HudSource source;
        final String id;
        final HudRole role;
        final HudRect rect;
        final HudImage image;
        final float opacity;
        final long ttlMs;
        final long publishedAt;

        Layer(HudSource source, String id, HudRole role, HudRect rect, HudImage image, float opacity,
                long ttlMs, long publishedAt) {
            this.source = source;
            this.id = id;
            this.role = role;
            this.rect = rect;
            this.image = image;
            this.opacity = opacity;
            this.ttlMs = ttlMs;
            this.publishedAt = publishedAt;
        }

        long left(long now) {
            return publishedAt + ttlMs - now;
        }
    }

    private static final class Fields {
        final HudSource source;
        final HudRole role;
        final HudFields values;
        final long ttlMs;
        final long publishedAt;

        Fields(HudSource source, HudRole role, HudFields values, long ttlMs, long publishedAt) {
            this.source = source;
            this.role = role;
            this.values = values;
            this.ttlMs = ttlMs;
            this.publishedAt = publishedAt;
        }

        long left(long now) {
            return publishedAt + ttlMs - now;
        }
    }
}
