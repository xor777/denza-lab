package dev.denza.hudlink;

/**
 * What a source sees of the HUD (docs/hud-output-architecture.md, 6): it publishes layers and
 * fields by role and never learns the mode, the other sources or the session. Everything
 * published has a lifetime and dies unless published again; publishing the same layer id or role
 * again replaces it. Called on the thread that owns the sink.
 */
public interface HudSink {
    /** Shows or replaces layer {@code id} of {@code source} in {@code role}'s window and z. */
    void layer(HudSource source, String id, HudRole role, HudRect rect, HudImage image,
            float opacity, long ttlMs);

    /** Takes layer {@code id} of {@code source} away. */
    void removeLayer(HudSource source, String id);

    /** Sets {@code role}'s fields from {@code source}. */
    void fields(HudSource source, HudRole role, HudFields fields, long ttlMs);

    /** Takes away whatever {@code source} published for {@code role}. */
    void withdraw(HudSource source, HudRole role);

    /** Takes away everything {@code source} published. */
    void withdraw(HudSource source);
}
