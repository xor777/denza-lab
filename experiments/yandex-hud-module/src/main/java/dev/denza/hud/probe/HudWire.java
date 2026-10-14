package dev.denza.hud.probe;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

/** Same protobuf envelopes as Denza Apps HudSomeIpClient. No maneuver, speed or vehicle setters. */
final class HudWire {
    static final String DESCRIPTOR = "ts.car.someip.sdk.ISomeIpServerInterface";
    static final long SERVICE = 3097367205183488L;
    static final long ROAD = 1127042368241665L, MAP = 1127042368241667L;
    static byte[] road(boolean active) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(8);
        varint(body, 2 << 3); varint(body, 2);
        varint(body, 16 << 3); varint(body, active ? 2 : 1);
        return field(1, body.toByteArray());
    }
    static byte[] map(byte[] png) { return field(1, Base64.getEncoder().encode(png)); }
    private static byte[] field(int field, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length + 8);
        varint(out, (field << 3) | 2); varint(out, value.length); out.write(value, 0, value.length);
        return out.toByteArray();
    }
    private static void varint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0) { out.write((int) (value & 0x7fL) | 0x80); value >>>= 7; }
        out.write((int) value);
    }
}
