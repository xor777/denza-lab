package dev.denza.hudsurfaces.probe;

import dev.denza.hudlink.HudProtocol;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * One line per packet for the log, decoded from the bytes that actually went out: the road packet
 * field by field in wire order ({@code 16=2 9=800 10="ПРОБА" 8=png80x80/412B}), the map frame as
 * the size of the PNG inside its Base64. Plain Java, so the JVM tests read the same summary.
 */
final class PacketSummary {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private PacketSummary() {
    }

    static String topicName(long topic) {
        if (topic == HudProtocol.TOPIC_ROAD) {
            return "road";
        }
        if (topic == HudProtocol.TOPIC_MAP) {
            return "map";
        }
        return Long.toString(topic);
    }

    static String of(long topic, byte[] payload) {
        try {
            if (topic == HudProtocol.TOPIC_ROAD) {
                return road(payload);
            }
            if (topic == HudProtocol.TOPIC_MAP) {
                return map(payload);
            }
            return "{" + payload.length + " B}";
        } catch (RuntimeException malformed) {
            return "{malformed " + payload.length + " B}";
        }
    }

    /** {@code HudRoadInfo_EG{1: HudRoadInfoNotifyStruct}}: the inner fields, space-separated. */
    static String road(byte[] payload) {
        Reader envelope = new Reader(payload);
        StringBuilder out = new StringBuilder("{");
        while (envelope.more()) {
            int tag = (int) envelope.varint();
            if (tag >>> 3 == 1 && (tag & 7) == 2) {
                fields(envelope.bytes(), out);
            } else {
                out.append(out.length() > 1 ? " " : "").append("outer").append(tag >>> 3);
                envelope.skip(tag & 7);
            }
        }
        return out.append('}').toString();
    }

    /** {@code HudNavigationmap{1: Base64 PNG}}: the picture's size. */
    static String map(byte[] payload) {
        Reader message = new Reader(payload);
        StringBuilder out = new StringBuilder("{");
        while (message.more()) {
            int tag = (int) message.varint();
            int field = tag >>> 3;
            if (out.length() > 1) {
                out.append(' ');
            }
            if (field == 1 && (tag & 7) == 2) {
                byte[] text = message.bytes();
                out.append("1=base64:").append(picture(Base64.getDecoder().decode(text)));
            } else {
                out.append(field).append("=?");
                message.skip(tag & 7);
            }
        }
        return out.append('}').toString();
    }

    private static void fields(byte[] body, StringBuilder out) {
        Reader reader = new Reader(body);
        while (reader.more()) {
            int tag = (int) reader.varint();
            int field = tag >>> 3;
            if (out.length() > 1) {
                out.append(' ');
            }
            out.append(field).append('=');
            switch (tag & 7) {
                case 0:
                    out.append(reader.varint());
                    break;
                case 1:
                    out.append(Double.longBitsToDouble(reader.fixed64()));
                    break;
                case 2:
                    out.append(value(reader.bytes()));
                    break;
                default:
                    throw new IllegalArgumentException("wire type " + (tag & 7));
            }
        }
    }

    private static String value(byte[] bytes) {
        if (isPng(bytes)) {
            return picture(bytes);
        }
        try {
            return '"' + StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString() + '"';
        } catch (CharacterCodingException binary) {
            return "bytes/" + bytes.length + "B";
        }
    }

    private static String picture(byte[] png) {
        if (!isPng(png) || png.length < 24) {
            return "bytes/" + png.length + "B";
        }
        return "png" + be32(png, 16) + "x" + be32(png, 20) + "/" + png.length + "B";
    }

    private static boolean isPng(byte[] bytes) {
        if (bytes.length < PNG.length) {
            return false;
        }
        for (int index = 0; index < PNG.length; index++) {
            if (bytes[index] != PNG[index]) {
                return false;
            }
        }
        return true;
    }

    private static int be32(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }

    private static final class Reader {
        private final byte[] bytes;
        private int position;

        Reader(byte[] bytes) {
            this.bytes = bytes;
        }

        boolean more() {
            return position < bytes.length;
        }

        long varint() {
            long value = 0L;
            for (int shift = 0; shift < 64; shift += 7) {
                byte next = bytes[position++];
                value |= (long) (next & 0x7f) << shift;
                if ((next & 0x80) == 0) {
                    return value;
                }
            }
            throw new IllegalArgumentException("varint too long");
        }

        long fixed64() {
            long value = 0L;
            for (int index = 0; index < 8; index++) {
                value |= (long) (bytes[position++] & 0xff) << (8 * index);
            }
            return value;
        }

        byte[] bytes() {
            int length = (int) varint();
            if (length < 0 || position + length > bytes.length) {
                throw new IllegalArgumentException("length " + length);
            }
            byte[] value = new byte[length];
            System.arraycopy(bytes, position, value, 0, length);
            position += length;
            return value;
        }

        void skip(int wireType) {
            switch (wireType) {
                case 0:
                    varint();
                    return;
                case 1:
                    position += 8;
                    return;
                case 2:
                    bytes();
                    return;
                case 5:
                    position += 4;
                    return;
                default:
                    throw new IllegalArgumentException("wire type " + wireType);
            }
        }
    }
}
