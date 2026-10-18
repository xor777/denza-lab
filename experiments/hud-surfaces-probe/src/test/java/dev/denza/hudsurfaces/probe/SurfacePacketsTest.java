package dev.denza.hudsurfaces.probe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.denza.hudsurfaces.probe.SurfacePackets.CameraPhase;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** Each packet decoded and checked field by field against the stock meaning in the README. */
public class SurfacePacketsTest {
    @Test public void gpsOnlyDoesNotSendCameraLimitOrPretendThereIsAManeuver() {
        for (int phase = 0; phase < 3; phase++) {
            Map<Integer,Object> road = Proto.road(SurfacePackets.gpsOnly(phase, FakeArt.ARROW));
            assertEquals(phase == 0 ? 1L : 2L, road.get(16));
            assertEquals(0L, road.get(9));
            assertEquals(0L, road.get(28));
            assertEquals(phase == 2 ? " " : "", road.containsKey(10) ? Proto.text(road.get(10)) : "");
            assertFalse(road.containsKey(11));
            assertFalse(road.containsKey(17));
        }
    }
    /** Everything of the manoeuvre block and the route summary. */
    private static final int[] MANEUVER_BLOCK = {8, 9, 10, 26, 27, 28};
    private static final int[] CAMERA_INPUTS = {11, 13, 14, 15, 17, 18, 23};

    @Test
    public void stockFieldNumbersOfTheCameraInputs() {
        // SomeipHudNaviInfoService.java:811-839 in the IVI OTA's BydLaunchermap.
        assertEquals(13, SurfacePackets.SECTION_DRIVEN);
        assertEquals(14, SurfacePackets.SECTION_LENGTH);
        assertEquals(15, SurfacePackets.SECTION_LIMIT);
        assertEquals(17, SurfacePackets.CAMERA_AHEAD);
        assertEquals(18, SurfacePackets.CAMERA_DISTANCE);
        assertEquals(23, SurfacePackets.DANGER_SIGN);
        assertEquals(1, SurfacePackets.CAMERA_PRESENT);
        assertEquals(30, SurfacePackets.DANGER_ATTENTION);
        assertEquals(11, SurfacePackets.MANEUVER_STRAIGHT);
    }

    @Test
    public void lanesComeBeforeTheManoeuvreBlockInWireOrder() {
        byte[] lanes = FakeArt.png(123, 60, 8);
        Map<Integer, Object> p = Proto.road(SurfacePackets.lanes(FakeArt.ARROW, 3, lanes));
        assertEquals(Arrays.asList(2, 16, 5, 6, 7, 8, 9, 10, 26, 27, 28), keys(p));
        assertEquals(3L, p.get(5));
        assertEquals(7L, p.get(6));
        assertArrayEquals(lanes, (byte[]) p.get(7));
        assertArrayEquals(FakeArt.ARROW, (byte[]) p.get(8));
        assertEquals(800L, p.get(9));
        assertEquals("ПРОБА", Proto.text(p.get(10)));
        assertEquals(11L, p.get(28));
        Map<Integer, Object> none = Proto.road(SurfacePackets.lanes(FakeArt.ARROW, 0, null));
        assertEquals(Arrays.asList(2, 16, 5, 6, 8, 9, 10, 26, 27, 28), keys(none));
        assertEquals(0L, none.get(5));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aLaneCountNeedsItsPicture() {
        SurfacePackets.lanes(FakeArt.ARROW, 3, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aPictureNeedsALaneCount() {
        SurfacePackets.lanes(FakeArt.ARROW, 0, FakeArt.png(123, 60, 8));
    }

    @Test
    public void theLanesClearIsTheSharedSessionsClear() {
        assertArrayEquals(dev.denza.hudlink.HudRoadPacket.clear(true), SurfacePackets.clear(true));
        assertArrayEquals(dev.denza.hudlink.HudRoadPacket.clear(false), SurfacePackets.clear());
        assertEquals(0L, Proto.road(SurfacePackets.clear(true)).get(5));
    }

    @Test
    public void cameraWithoutNavigationIsTheCameraAndNothingElse() {
        Map<Integer, Object> p = Proto.road(
                SurfacePackets.cameraFields(false, CameraPhase.CAMERA, 0, null));
        assertEquals(Arrays.asList(2, 16, 17, 18), keys(p));
        assertEquals(2L, p.get(2));
        assertEquals(1L, p.get(16));
        assertEquals(1L, p.get(17));
        assertEquals(300L, p.get(18));
    }

    @Test
    public void cameraDistanceCountsDownTenMetresAPacket() {
        assertEquals(300, SurfacePackets.cameraMeters(0));
        assertEquals(210, SurfacePackets.cameraMeters(9));
        assertEquals(110, SurfacePackets.cameraMeters(19));
        assertEquals(20, SurfacePackets.cameraMeters(1000));
        Map<Integer, Object> p = Proto.road(
                SurfacePackets.cameraFields(false, CameraPhase.CAMERA, 9, null));
        assertEquals(210L, p.get(18));
    }

    @Test
    public void cameraLimitPhaseAddsOnlyFieldEleven() {
        Map<Integer, Object> p = Proto.road(
                SurfacePackets.cameraFields(false, CameraPhase.CAMERA_LIMIT, 3, null));
        assertEquals(Arrays.asList(2, 16, 11, 17, 18), keys(p));
        assertEquals(60L, p.get(11));
        assertEquals(1L, p.get(17));
        assertEquals(270L, p.get(18));
    }

    @Test
    public void sectionPhaseIsTheAverageSpeedSectionWithoutThePointCamera() {
        Map<Integer, Object> p = Proto.road(
                SurfacePackets.cameraFields(false, CameraPhase.SECTION, 9, null));
        assertEquals(Arrays.asList(2, 16, 13, 14, 15), keys(p));
        // Stock: 13 = length - remainDistance, so it never passes 14 (d.java:275-280).
        assertEquals(380L, p.get(13));
        assertEquals(2000L, p.get(14));
        assertEquals(60L, p.get(15));
        assertTrue((Long) p.get(13) <= (Long) p.get(14));
    }

    @Test
    public void dangerPhaseIsFieldTwentyThreeAlone() {
        Map<Integer, Object> p = Proto.road(
                SurfacePackets.cameraFields(false, CameraPhase.DANGER, 0, null));
        assertEquals(Arrays.asList(2, 16, 23), keys(p));
        assertEquals(30L, p.get(23));
    }

    @Test
    public void notNavigatingNeverOpensTheManeuverBlock() {
        for (CameraPhase phase : CameraPhase.values()) {
            Map<Integer, Object> p = Proto.road(
                    SurfacePackets.cameraFields(false, phase, 4, FakeArt.ARROW));
            assertEquals(1L, p.get(16));
            for (int field : MANEUVER_BLOCK) {
                assertFalse(phase + " writes " + field, p.containsKey(field));
            }
        }
    }

    @Test
    public void navigatingFillsTheWholeManeuverBlockAroundTheSameCameraInputs() {
        for (CameraPhase phase : CameraPhase.values()) {
            Map<Integer, Object> on = Proto.road(
                    SurfacePackets.cameraFields(true, phase, 4, FakeArt.ARROW));
            Map<Integer, Object> off = Proto.road(
                    SurfacePackets.cameraFields(false, phase, 4, null));
            assertEquals(2L, on.get(16));
            assertArrayEquals(FakeArt.ARROW, (byte[]) on.get(8));
            assertEquals(800L, on.get(9));
            assertEquals("ПРОБА", Proto.text(on.get(10)));
            assertEquals("5,0 км", Proto.text(on.get(26)));
            assertEquals("10 мин", Proto.text(on.get(27)));
            assertEquals(11L, on.get(28));
            for (int field : CAMERA_INPUTS) {
                assertEquals(phase + " field " + field, off.get(field), on.get(field));
            }
        }
    }

    @Test
    public void fieldsGoOutCounterAndStateFirstThenAscending() {
        byte[] inner = (byte[]) Proto.decode(
                SurfacePackets.cameraFields(true, CameraPhase.CAMERA_LIMIT, 0, FakeArt.ARROW)).get(1);
        List<Integer> order = Proto.order(inner);
        assertEquals(Arrays.asList(2, 16), order.subList(0, 2));
        List<Integer> rest = order.subList(2, order.size());
        for (int index = 1; index < rest.size(); index++) {
            assertTrue(rest.toString(), rest.get(index - 1) < rest.get(index));
        }
    }

    @Test
    public void warningFillsTheManeuverBlockWithItselfAndLeavesTheSummaryEmpty() {
        Map<Integer, Object> p = Proto.road(
                SurfacePackets.warningManeuver(FakeArt.CAMERA, 250, "60"));
        assertEquals(Arrays.asList(2, 16, 8, 9, 10, 28), keys(p));
        assertEquals(2L, p.get(16));
        assertArrayEquals(FakeArt.CAMERA, (byte[]) p.get(8));
        assertEquals(250L, p.get(9));
        assertEquals("60", Proto.text(p.get(10)));
        assertEquals(11L, p.get(28));
        assertFalse(p.containsKey(26));
        assertFalse(p.containsKey(27));
        for (int field : CAMERA_INPUTS) {
            assertFalse("warning writes stock camera field " + field, p.containsKey(field));
        }
    }

    @Test
    public void eachSessionNamesItselfInEveryReadableField() {
        Map<Integer, Object> a = Proto.road(
                SurfacePackets.sessionManeuver(SessionId.A, FakeArt.LETTER_A));
        Map<Integer, Object> b = Proto.road(
                SurfacePackets.sessionManeuver(SessionId.B, FakeArt.LETTER_B));
        assertEquals(Arrays.asList(2, 16, 8, 9, 10, 26, 27, 28), keys(a));
        assertEquals(keys(a), keys(b));
        assertEquals(2L, a.get(16));
        assertArrayEquals(FakeArt.LETTER_A, (byte[]) a.get(8));
        assertArrayEquals(FakeArt.LETTER_B, (byte[]) b.get(8));
        assertEquals(100L, a.get(9));
        assertEquals(200L, b.get(9));
        assertEquals("СЕССИЯ A", Proto.text(a.get(10)));
        assertEquals("СЕССИЯ B", Proto.text(b.get(10)));
        assertEquals("1,0 км", Proto.text(a.get(26)));
        assertEquals("2,0 км", Proto.text(b.get(26)));
        assertEquals("1 мин", Proto.text(a.get(27)));
        assertEquals("2 мин", Proto.text(b.get(27)));
        assertEquals(11L, a.get(28));
    }

    @Test
    public void clearIsNavigationOffWithEveryOtherFieldAtItsDefault() {
        Map<Integer, Object> p = Proto.road(SurfacePackets.clear());
        assertEquals(Arrays.asList(2, 16), keys(p));
        assertEquals(2L, p.get(2));
        assertEquals(1L, p.get(16));
    }

    @Test
    public void mapFrameIsBase64OfThePictureWithoutEnvelope() {
        Map<Integer, Object> p = Proto.decode(SurfacePackets.map(FakeArt.BLACK));
        assertEquals(1, p.size());
        byte[] text = (byte[]) p.get(1);
        assertArrayEquals(FakeArt.BLACK, Base64.getDecoder().decode(
                new String(text, StandardCharsets.US_ASCII)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void navigatingWithoutAPictureFailsBeforeSending() {
        SurfacePackets.cameraFields(true, CameraPhase.CAMERA, 0, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void warningWithoutAPictureFailsBeforeSending() {
        SurfacePackets.warningManeuver(new byte[0], 300, "60");
    }

    @Test
    public void sessionTypesDiffer() {
        Set<String> types = new HashSet<>();
        for (SessionId id : SessionId.values()) {
            types.add(id.type("dev.denza.hudsurfaces.probe"));
        }
        assertEquals(SessionId.values().length, types.size());
        assertEquals("dev.denza.hudsurfaces.probe", SessionId.A.type("dev.denza.hudsurfaces.probe"));
    }

    private static List<Integer> keys(Map<Integer, Object> fields) {
        return Arrays.asList(fields.keySet().toArray(new Integer[0]));
    }
}
