package dev.denza.alicewheel.probe;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoicePressTest {
    @Test public void ownsUpEvenWhenForegroundOrArmExpires() {
        VoicePress p = new VoicePress();
        assertTrue(p.event(0, 0, 2, 10, true, false).consumed());
        assertFalse(p.event(0, 1, 2, 10, false, false).click());
        var up = p.event(1, 0, 2, 10, false, false);
        assertTrue(up.consumed()); assertTrue(up.click());
        assertFalse(p.event(1, 0, 2, 10, true, false).consumed());
    }
    @Test public void neverStealsUpAfterPassedDown() {
        VoicePress p = new VoicePress();
        assertFalse(p.event(0, 0, 2, 10, false, false).consumed());
        assertFalse(p.event(1, 0, 2, 10, true, false).consumed());
    }
    @Test public void cancelConsumesWithoutClickAndOtherDevicePasses() {
        VoicePress p = new VoicePress();
        p.event(0, 0, 2, 10, true, false);
        assertFalse(p.event(1, 0, 3, 10, false, false).consumed());
        var up = p.event(1, 0, 2, 10, false, true);
        assertTrue(up.consumed()); assertFalse(up.click());
    }
}
