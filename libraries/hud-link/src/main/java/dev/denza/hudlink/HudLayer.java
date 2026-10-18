package dev.denza.hudlink;

/**
 * One picture in a layered window (docs/hud-output-architecture.md, 3.1): its owner, a stable id
 * (publishing the same id again replaces it), the window and z its role gives it, a rectangle, a
 * picture, an opacity and the time it dies unless published again. Immutable.
 */
public final class HudLayer {
    public final HudSource source;
    public final String id;
    public final HudRole role;
    public final HudRect rect;
    public final HudImage image;
    /** 0 to 1. */
    public final float opacity;
    public final long publishedAt;
    public final long expiresAt;
    /** Changes only when what is drawn changes; a renewal keeps it. */
    final long serial;
    /** When the id was first published, to keep layers at one z in a stable order. */
    final long order;

    HudLayer(HudSource source, String id, HudRole role, HudRect rect, HudImage image, float opacity,
            long publishedAt, long expiresAt, long serial, long order) {
        this.source = source;
        this.id = id;
        this.role = role;
        this.rect = rect;
        this.image = image;
        this.opacity = opacity;
        this.publishedAt = publishedAt;
        this.expiresAt = expiresAt;
        this.serial = serial;
        this.order = order;
    }

    public HudWindow window() {
        return role.window;
    }

    public int z() {
        return role.z;
    }

    /** Whether a publish with these values draws what this layer draws: same place, picture object and opacity. */
    boolean looksLike(HudRole role, HudRect rect, HudImage image, float opacity) {
        return this.role == role && this.rect.equals(rect) && this.image == image
                && this.opacity == opacity;
    }

    /** The only layer of its window, drawn as it is: its PNG can go to the HUD untouched. */
    boolean coversAlone() {
        HudWindow window = role.window;
        return opacity >= 1f && rect.equals(HudRect.whole(window))
                && image.width == window.width && image.height == window.height;
    }
}
