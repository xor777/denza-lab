package dev.denza.apps.feature.hud;

/**
 * Red mixed into a picture for the HUD - HUD Speed's warning at a fine's speed
 * (docs/hud-output-architecture.md, 7.5): each pixel moves
 * towards a red as bright as it is, so the picture reads red as a whole and keeps its shapes.
 * Linear and without an offset, so black stays black - what the HUD does not draw - and no
 * rectangle lights up around the picture.
 *
 * <p>A 4×5 matrix in Android's {@code ColorMatrix} order, so plain Java can hold it to its rule.
 */
final class HudTint {
    /** The red a white pixel becomes, per channel: brighter than its brightness, so dim lines still read red. */
    static final float RED = 1.25f;
    static final float GREEN_BLUE = 0.12f;
    private static final float LUMA_R = 0.299f;
    private static final float LUMA_G = 0.587f;
    private static final float LUMA_B = 0.114f;

    private HudTint() {
    }

    /** {@code amount} 0 leaves a picture as it is, 1 makes it all red; clamped to that. */
    static float[] matrix(float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        float keep = 1f - a;
        float r = a * RED;
        float gb = a * GREEN_BLUE;
        return new float[] {
                keep + r * LUMA_R, r * LUMA_G, r * LUMA_B, 0f, 0f,
                gb * LUMA_R, keep + gb * LUMA_G, gb * LUMA_B, 0f, 0f,
                gb * LUMA_R, gb * LUMA_G, keep + gb * LUMA_B, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
        };
    }
}
