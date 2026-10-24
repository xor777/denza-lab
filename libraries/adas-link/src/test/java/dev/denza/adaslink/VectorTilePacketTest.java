package dev.denza.adaslink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class VectorTilePacketTest {
    static int checks;
    static byte[] packet() {
        ByteBuffer out = ByteBuffer.allocate(75).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x31545659).putShort((short) 1).putShort((short) 72).putInt(3).putInt(1).putLong(99).putLong(111);
        for (int i = 0; i < 3; i++) out.putInt(12).putInt(13).putInt(15);
        out.putInt(0).put(new byte[]{1, 2, 3}); return out.array();
    }
    static void reject(byte[] bytes) {
        try { VectorTilePacket.decode(bytes); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("invalid envelope accepted");
    }
    public static void main(String[] args) {
        byte[] bytes = packet(); VectorTilePacket decoded = VectorTilePacket.decode(bytes);
        if (decoded.sequence != 99 || decoded.threadKey != 111 || decoded.wrapper.x != 12
                || !Arrays.equals(decoded.protobuf, new byte[]{1, 2, 3})) throw new AssertionError("wire fields");
        checks++;
        bytes[72] = 44;
        if (decoded.protobuf[0] != 1) throw new AssertionError("input retained"); checks++;
        for (int offset : new int[]{0, 4, 6, 8, 12, 32, 44, 56, 68}) {
            byte[] bad = packet(); bad[offset] ^= 4; reject(bad);
        }
        reject(null); reject(new byte[0]); reject(new byte[72]); reject(new byte[72 + 2 * 1024 * 1024 + 1]);
        byte[] invalidId = packet(); ByteBuffer.wrap(invalidId).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 31); reject(invalidId);
        System.out.println("VectorTilePacketTest: " + checks + " checks");
    }
}
