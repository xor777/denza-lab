package dev.denza.hudframes.probe;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

/** Decode independently to check recovered wire tags and isolate the two candidate inputs. */
public class HudLanePacketTest {
    private static final byte[] PNG = { (byte) 137, 80, 78, 71, 13, 10, 26, 10 };
    private static final byte[] MARKER = { 1, 2, 3 };

    @Test public void alreadyStartedRegistersOurClientInsteadOfAbortingTrial() {
        // First live control returned 13, not 0. ResultValue + serverIdMapBinder confirm
        // ALREADY_STARTED registers a second client; errors must still stop the trial.
        assertTrue(HudSomeIpSender.offerStarted(0));
        assertTrue(HudSomeIpSender.offerStarted(13));
        for (int error : new int[] {-200, -2, 1, 3, 4, 5, 6, 8, 11, 14, 15}) {
            assertFalse(HudSomeIpSender.offerStarted(error));
        }
    }

    @Test public void idsTrialDoesNotAccidentallySupplyLanePicture() {
        Map<Integer, Object> p = packet("ids");
        assertEquals(3L, p.get(5));
        assertFalse(p.containsKey(7));
        assertEquals("1,255|0,0|3,255|", new String((byte[]) p.get(29), StandardCharsets.UTF_8));
    }

    @Test public void imageTrialDoesNotAccidentallySupplyLaneIds() {
        Map<Integer, Object> p = packet("image");
        assertArrayEquals(PNG, (byte[]) p.get(7));
        assertFalse(p.containsKey(29));
        assertEquals(3L, p.get(5));
    }

    @Test public void combinedTrialsChangeOnlyRoadClassAndLabel() {
        Map<Integer, Object> main = packet("both"), city = packet("both-city");
        assertEquals(7L, main.remove(6));
        assertEquals(6L, city.remove(6));
        main.remove(10); city.remove(10);
        assertEquals(main.keySet(), city.keySet());
        for (int key : main.keySet()) {
            if (main.get(key) instanceof byte[]) {
                assertArrayEquals((byte[]) main.get(key), (byte[]) city.get(key));
            } else assertEquals(main.get(key), city.get(key));
        }
    }

    @Test public void controlKeepsKnownManeuverSlotWithoutLaneData() {
        Map<Integer, Object> p = packet("control");
        assertEquals(0L, p.get(5));
        assertFalse(p.containsKey(7)); assertFalse(p.containsKey(29));
        assertArrayEquals(MARKER, (byte[]) p.get(8));
        assertEquals(2L, p.get(16)); assertEquals(11L, p.get(28));
    }

    @Test public void clearDropsBothLaneInputsAndGuidingState() {
        Map<Integer, Object> p = decode((byte[]) decode(HudLanePacket.clear()).get(1));
        assertEquals(1L, p.get(16)); assertEquals(0L, p.get(5));
        assertEquals(-1L, p.get(6)); // signed int32 is encoded as a ten-byte varint
        for (int tag : new int[] {7, 8, 10, 28, 29}) assertFalse(p.containsKey(tag));
    }

    @Test(expected = IllegalArgumentException.class) public void missingImageFailsBeforeSending() {
        HudLanePacket.build("image", null, MARKER);
    }

    @Test(expected = IllegalArgumentException.class) public void unknownTrialFailsBeforeSending() {
        HudLanePacket.build("unknown", PNG, MARKER);
    }

    private static Map<Integer, Object> packet(String mode) {
        Map<Integer, Object> envelope = decode(HudLanePacket.build(mode, PNG, MARKER));
        assertEquals(1, envelope.size());
        return decode((byte[]) envelope.get(1));
    }

    private static Map<Integer, Object> decode(byte[] bytes) {
        Map<Integer, Object> fields = new LinkedHashMap<>();
        int[] cursor = {0};
        while (cursor[0] < bytes.length) {
            long tag = varint(bytes, cursor);
            int field = (int) (tag >>> 3);
            assertFalse("duplicate field " + field, fields.containsKey(field));
            if ((tag & 7) == 0) fields.put(field, varint(bytes, cursor));
            else if ((tag & 7) == 2) {
                int size = (int) varint(bytes, cursor);
                assertTrue(size >= 0 && cursor[0] + size <= bytes.length);
                fields.put(field, Arrays.copyOfRange(bytes, cursor[0], cursor[0] + size));
                cursor[0] += size;
            } else throw new AssertionError("wire type " + (tag & 7));
        }
        return fields;
    }

    private static long varint(byte[] bytes, int[] cursor) {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            assertTrue(cursor[0] < bytes.length);
            int b = bytes[cursor[0]++] & 255;
            value |= (long) (b & 127) << shift;
            if ((b & 128) == 0) return value;
        }
        throw new AssertionError("unterminated varint");
    }
}
