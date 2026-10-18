package dev.denza.hudsurfaces.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A protobuf reader of its own for the tests, written apart from the probe's log summary, so a
 * builder and the reader do not share a mistake. Varints read as Long, length-delimited fields
 * as byte[], fixed64 as Double; a field written twice fails the test.
 */
final class Proto {
    private Proto() {
    }

    /** The inner road packet: the envelope must hold field 1 and nothing else. */
    static Map<Integer, Object> road(byte[] payload) {
        Map<Integer, Object> envelope = decode(payload);
        assertEquals("envelope fields", 1, envelope.size());
        assertTrue("envelope is field 1", envelope.containsKey(1));
        return decode((byte[]) envelope.get(1));
    }

    /** Field numbers in wire order. */
    static List<Integer> order(byte[] message) {
        List<Integer> order = new ArrayList<>();
        int[] position = {0};
        while (position[0] < message.length) {
            int tag = (int) varint(message, position);
            order.add(tag >>> 3);
            value(message, position, tag & 7);
        }
        return order;
    }

    static Map<Integer, Object> decode(byte[] message) {
        Map<Integer, Object> fields = new LinkedHashMap<>();
        int[] position = {0};
        while (position[0] < message.length) {
            int tag = (int) varint(message, position);
            Object value = value(message, position, tag & 7);
            Object previous = fields.put(tag >>> 3, value);
            assertEquals("field " + (tag >>> 3) + " written twice", null, previous);
        }
        return fields;
    }

    static String text(Object value) {
        return new String((byte[]) value, StandardCharsets.UTF_8);
    }

    private static Object value(byte[] message, int[] position, int wireType) {
        switch (wireType) {
            case 0:
                return varint(message, position);
            case 1: {
                long bits = 0L;
                for (int index = 0; index < 8; index++) {
                    bits |= (long) (message[position[0]++] & 0xff) << (8 * index);
                }
                return Double.longBitsToDouble(bits);
            }
            case 2: {
                int length = (int) varint(message, position);
                byte[] value = new byte[length];
                System.arraycopy(message, position[0], value, 0, length);
                position[0] += length;
                return value;
            }
            default:
                throw new AssertionError("unexpected wire type " + wireType);
        }
    }

    private static long varint(byte[] message, int[] position) {
        long value = 0L;
        int shift = 0;
        while (true) {
            byte next = message[position[0]++];
            value |= (long) (next & 0x7f) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
    }
}
