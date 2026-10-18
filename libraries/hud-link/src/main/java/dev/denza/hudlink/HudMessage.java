package dev.denza.hudlink;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A protobuf message for the HUD's SOME/IP topics. Fields go out in the order they are added, so a
 * sender keeps exactly the bytes it sent before this writer was shared. Empty text and empty bytes
 * are left out, as are doubles that are not finite: the HUD gets no field rather than a NaN.
 */
public final class HudMessage {
    private final ByteArrayOutputStream out;

    public HudMessage() {
        this(64);
    }

    public HudMessage(int capacity) {
        out = new ByteArrayOutputStream(capacity);
    }

    /** Varint field (wire type 0), always written. */
    public HudMessage number(int field, long value) {
        varint(field << 3);
        varint(value);
        return this;
    }

    /** Fixed64 field (wire type 1), little-endian IEEE 754; left out when not finite. */
    public HudMessage decimal(int field, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return this;
        }
        varint((field << 3) | 1);
        long bits = Double.doubleToRawLongBits(value);
        for (int index = 0; index < 8; index++) {
            out.write((int) (bits & 0xffL));
            bits >>>= 8;
        }
        return this;
    }

    /** UTF-8 string field (wire type 2); left out when null or empty. */
    public HudMessage text(int field, String value) {
        if (value == null || value.isEmpty()) {
            return this;
        }
        return bytes(field, value.getBytes(StandardCharsets.UTF_8));
    }

    /** Bytes field (wire type 2); left out when null or empty. */
    public HudMessage bytes(int field, byte[] value) {
        if (value == null || value.length == 0) {
            return this;
        }
        varint((field << 3) | 2);
        varint(value.length);
        out.write(value, 0, value.length);
        return this;
    }

    public byte[] toByteArray() {
        return out.toByteArray();
    }

    /**
     * This message as length-delimited field {@code field} of an outer message, written even when
     * empty: the road packet's envelope, which the map frame does not have.
     */
    public byte[] embedded(int field) {
        byte[] body = out.toByteArray();
        HudMessage envelope = new HudMessage(body.length + 8);
        envelope.varint((field << 3) | 2);
        envelope.varint(body.length);
        envelope.out.write(body, 0, body.length);
        return envelope.toByteArray();
    }

    private void varint(long value) {
        while ((value & ~0x7fL) != 0L) {
            out.write((int) ((value & 0x7fL) | 0x80L));
            value >>>= 7;
        }
        out.write((int) value);
    }
}
