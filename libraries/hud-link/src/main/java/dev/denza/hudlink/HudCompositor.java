package dev.denza.hudlink;

import java.util.List;

/**
 * The HUD's one writer (docs/hud-output-architecture.md, 1-2): sources publish into it as a
 * {@link HudSink}; each {@link #pump} composes what they published ({@link HudScene}) and hands
 * the session ({@link HudSession}) the road packet, the clear and the map window's frame. Plain
 * Java: the Android side ({@link HudOutput}) gives it a clock, the Binder port and a renderer,
 * and pumps it on one thread.
 */
public final class HudCompositor implements HudSink {
    /** Draws a layered window into one PNG; Android's Canvas on the car ({@code HudCanvasRenderer}). */
    public interface Renderer {
        /** {@code layers} bottom first on black; null when it could not be drawn. */
        byte[] render(HudWindow window, List<HudLayer> layers);

        /** An all-black PNG of the window's size: black is what the HUD does not draw. */
        byte[] black(HudWindow window);
    }

    /** The road's limit as the composition has it, for the car's own sign; on the pump's thread. */
    public interface LimitListener {
        /** The limit changed: km/h, or null when no source publishes one. */
        void onLimit(Integer kmh);
    }

    private final HudClock clock;
    private final Renderer renderer;
    /** The map window's picture as composed, for a screen beside the HUD; on the pump's thread. */
    public interface FrameListener {
        /** The window's picture changed: a PNG of its size, or null when it shows nothing. */
        void onFrame(HudWindow window, byte[] png);
    }

    private LimitListener limitListener;
    private Integer limit;
    private FrameListener frameListener;
    private byte[] picture;
    private final HudScene scene = new HudScene();
    private final HudSession session;
    private long frameVersion = -1L;
    private byte[] frame;

    public HudCompositor(HudClock clock, HudSession.Port port, HudSession.Listener listener,
            Renderer renderer) {
        this.clock = clock;
        this.renderer = renderer;
        this.session = new HudSession(clock, port, listener,
                HudProtocol.mapPayload(renderer.black(HudWindow.MAP)));
    }

    @Override
    public void layer(HudSource source, String id, HudRole role, HudRect rect, HudImage image,
            float opacity, long ttlMs) {
        scene.layer(source, id, role, rect, image, opacity, ttlMs, clock.now());
    }

    @Override
    public void removeLayer(HudSource source, String id) {
        scene.removeLayer(source, id);
    }

    @Override
    public void fields(HudSource source, HudRole role, HudFields fields, long ttlMs) {
        scene.fields(source, role, fields, ttlMs, clock.now());
    }

    @Override
    public void withdraw(HudSource source, HudRole role) {
        scene.withdraw(source, role);
    }

    @Override
    public void withdraw(HudSource source) {
        scene.withdraw(source);
    }

    /**
     * Another writer takes the HUD over: everything published here goes and the session stops
     * without the clear ({@link HudSession#handOver}).
     */
    public void handOver() {
        for (HudSource source : HudSource.values()) {
            scene.withdraw(source);
        }
        frameVersion = -1L;
        frame = null;
        picture = null;
        if (frameListener != null) {
            frameListener.onFrame(HudWindow.MAP, null);
        }
        session.handOver();
    }

    /** Who hears {@link HudComposition#roadLimitKmh} change; told the current value at once. */
    public void setLimitListener(LimitListener listener) {
        limitListener = listener;
        if (listener != null) {
            listener.onLimit(limit);
        }
    }

    /** Who sees the map window's picture change; told the current one at once. */
    public void setFrameListener(FrameListener listener) {
        frameListener = listener;
        if (listener != null) {
            listener.onFrame(HudWindow.MAP, picture);
        }
    }

    /** The service answered the session's binding. */
    public void onConnected() {
        session.onConnected();
    }

    /** The service of the session's binding went away. */
    public void onDisconnected() {
        session.onDisconnected();
    }

    /**
     * Composes what is published now and lets the session act on it. Returns when to pump again
     * - the session's next send or the first thing published dying - or {@link HudSession#IDLE}.
     */
    public long pump() {
        HudComposition now = scene.compose(clock.now());
        long version = now.version(HudWindow.MAP);
        if (version != frameVersion) {
            frameVersion = version;
            picture = picture(now);
            frame = picture == null ? null : HudProtocol.mapPayload(picture);
            if (frameListener != null) {
                frameListener.onFrame(HudWindow.MAP, picture);
            }
        }
        session.show(now.road(), now.clear(), frame, version);
        Integer kmh = now.roadLimitKmh();
        if (kmh == null ? limit != null : !kmh.equals(limit)) {
            limit = kmh;
            if (limitListener != null) {
                limitListener.onLimit(kmh);
            }
        }
        return Math.min(session.pump(), now.nextChange);
    }

    /** The PNG of what the window shows; null when it shows nothing. */
    private byte[] picture(HudComposition composition) {
        List<HudLayer> layers = composition.layers(HudWindow.MAP);
        if (layers.isEmpty()) {
            return null;
        }
        HudLayer alone = composition.passThrough(HudWindow.MAP);
        return alone != null ? alone.image.png() : renderer.render(HudWindow.MAP, layers);
    }
}
