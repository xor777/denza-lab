package dev.denza.hudlink;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;

public class HudRadarWarningTest {
    private HudRadarWarning warning() {
        return new HudRadarWarning(1000, true, 42, 5, "Опасный участок", 350, 60, 1, false, -1);
    }
    @Test public void roundTripKeepsTypeUnitsAndOriginalSampleTime() {
        HudRadarWarning copy = HudRadarWarning.decode(warning().encode());
        assertNotNull(copy);
        assertEquals(5, copy.type); assertEquals(60, copy.limit); assertEquals(350, copy.meters);
        assertEquals(1, copy.unit); assertEquals("Опасный участок", copy.title);
        assertEquals(1, copy.remainingMs(3999)); assertEquals(0, copy.remainingMs(4000));
        assertEquals(0, copy.remainingMs(999)); // Future sample, clock-domain mismatch.
        assertFalse(HudRadarWarning.decode(HudRadarWarning.clear(1000).encode()).active);
    }
    @Test public void malformedOrNewerPacketsAreRejected() {
        byte[] good = warning().encode();
        for (int length=0; length<good.length; length++) assertNull(HudRadarWarning.decode(Arrays.copyOf(good, length)));
        assertNull(HudRadarWarning.decode(Arrays.copyOf(good, good.length+1)));
        assertNull(HudRadarWarning.decode(new byte[513]));
        good[3]=2; assertNull(HudRadarWarning.decode(good));
    }
    @Test(expected=IllegalArgumentException.class) public void unknownUnitIsNotDisplayedAsKmh() {
        new HudRadarWarning(1000, true, 1, 1, "Камера", 100, 60, 3, false, -1);
    }
}
