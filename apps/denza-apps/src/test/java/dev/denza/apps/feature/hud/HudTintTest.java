package dev.denza.apps.feature.hud;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The red mixed into the HUD's pictures: black stays black, shapes keep their brightness. */
public final class HudTintTest {
    @Test
    public void noTintLeavesAPictureAsItIs() {
        int[] green = {0x22, 0xE0, 0x6A};
        assertArrayEquals(green, apply(0f, green));
    }

    @Test
    public void blackStaysWhatTheHudDoesNotDraw() {
        assertArrayEquals(new int[] {0, 0, 0}, apply(1f, new int[] {0, 0, 0}));
    }

    @Test
    public void aFullTintTurnsWhiteRedAndTheRouteRedAsBrightAsItWas() {
        assertArrayEquals(new int[] {255, 31, 31}, apply(1f, new int[] {255, 255, 255}));
        int[] route = apply(1f, new int[] {0x22, 0xE0, 0x6A});
        assertTrue(route[0] > 180 && route[1] < 25 && route[2] < 25);
    }

    @Test
    public void halfATintIsHalfWayAndOutOfRangeIsClamped() {
        int[] half = apply(0.5f, new int[] {0, 200, 0});
        assertEquals(Math.round(200 * 0.587f * HudTint.RED * 0.5f), half[0]);
        assertArrayEquals(HudTint.matrix(1f), HudTint.matrix(3f), 0f);
        assertArrayEquals(HudTint.matrix(0f), HudTint.matrix(-1f), 0f);
    }

    /** What Android's ColorMatrix does to an opaque pixel: the matrix, then each channel clamped. */
    private static int[] apply(float amount, int[] rgb) {
        float[] m = HudTint.matrix(amount);
        int[] out = new int[3];
        for (int row = 0; row < 3; row++) {
            float value = m[row * 5] * rgb[0] + m[row * 5 + 1] * rgb[1] + m[row * 5 + 2] * rgb[2]
                    + m[row * 5 + 4];
            out[row] = Math.max(0, Math.min(255, Math.round(value)));
        }
        return out;
    }
}
