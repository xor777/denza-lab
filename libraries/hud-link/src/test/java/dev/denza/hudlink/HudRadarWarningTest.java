package dev.denza.hudlink;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;

public class HudRadarWarningTest {
    private HudRadarWarning warning() {
        return new HudRadarWarning(1000, true, HudRadarWarning.GPS_FIX, true, 42, 5, "Опасный участок",
                350, 60, 1, false, -1, "cam_type_5", "cam_speed_box_red");
    }
    @Test public void roundTripKeepsTypeUnitsArtworkAndOriginalSampleTime() {
        HudRadarWarning copy = HudRadarWarning.decode(warning().encode());
        assertNotNull(copy);
        assertEquals(5, copy.type); assertEquals(60, copy.limit); assertEquals(350, copy.meters);
        assertEquals(1, copy.unit); assertEquals("Опасный участок", copy.title);
        assertEquals("cam_type_5", copy.icon); assertEquals("cam_speed_box_red", copy.frame);
        assertTrue(copy.running); assertEquals(HudRadarWarning.GPS_FIX, copy.gpsLevel);
        assertEquals(1, copy.remainingMs(3999)); assertEquals(0, copy.remainingMs(4000));
        assertEquals(0, copy.remainingMs(999)); // Future sample, clock-domain mismatch.
        assertFalse(HudRadarWarning.decode(HudRadarWarning.clear(1000).encode()).active);
    }
    @Test public void aRunningRadarWithoutAWarningKeepsItsGpsBars() {
        HudRadarWarning idle = HudRadarWarning.decode(HudRadarWarning.idle(1000, HudRadarWarning.GPS_SEARCHING).encode());
        assertTrue(idle.running); assertFalse(idle.active);
        assertEquals(HudRadarWarning.GPS_SEARCHING, idle.gpsLevel);
        HudRadarWarning stopped = HudRadarWarning.decode(HudRadarWarning.clear(1000).encode());
        assertFalse(stopped.running); assertEquals(HudRadarWarning.GPS_UNKNOWN, stopped.gpsLevel);
        assertEquals(5000, idle.resampled(5000).sampledAt);
    }
    @Test(expected=IllegalStateException.class) public void aWarningIsNeverMadeFreshAgain() {
        warning().resampled(2000);
    }
    @Test public void theFirstAdaptersVersionOneStillReads() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1); out.writeLong(1000); out.writeBoolean(true);
        out.writeInt(42); out.writeInt(5); out.writeUTF("Камера"); out.writeInt(350); out.writeInt(60);
        out.writeInt(1); out.writeBoolean(false); out.writeInt(-1);
        HudRadarWarning old = HudRadarWarning.decode(bytes.toByteArray());
        assertNotNull(old);
        assertTrue(old.running); assertTrue(old.active);
        assertEquals(HudRadarWarning.GPS_UNKNOWN, old.gpsLevel); assertEquals("", old.icon);
    }
    @Test public void malformedOrNewerPacketsAreRejected() {
        byte[] good = warning().encode();
        for (int length=0; length<good.length; length++) assertNull(HudRadarWarning.decode(Arrays.copyOf(good, length)));
        assertNull(HudRadarWarning.decode(Arrays.copyOf(good, good.length+1)));
        assertNull(HudRadarWarning.decode(new byte[513]));
        good[3]=3; assertNull(HudRadarWarning.decode(good));
    }
    @Test(expected=IllegalArgumentException.class) public void unknownUnitIsNotDisplayedAsKmh() {
        new HudRadarWarning(1000, true, 1, 1, "Камера", 100, 60, 3, false, -1);
    }
    @Test(expected=IllegalArgumentException.class) public void aDrawableNameIsOnlyAnEntryName() {
        new HudRadarWarning(1000, true, 4, true, 1, 1, "Камера", 100, 60, 1, false, -1, "../cam", "");
    }
}
