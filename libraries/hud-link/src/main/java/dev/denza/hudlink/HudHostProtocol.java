package dev.denza.hudlink;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Between a source in another app and the host that writes the HUD (docs/hud-output-architecture.md,
 * 4-5): the host's service, its Binder transactions, and the {@link HudSink} calls a source makes,
 * written into one batch per turn of its thread. A batch names no source - the host decides who
 * the caller is from its signature, so a caller cannot publish as another source.
 *
 * <p>Plain Java; the Binder ends are {@link HudHostClient} and {@link HudHostEndpoint}.
 */
public final class HudHostProtocol {
    /** Denza Apps, the host when it is installed. */
    public static final String PACKAGE = "dev.denza.apps";
    public static final String SERVICE_CLASS = "dev.denza.apps.feature.hud.HudHostService";
    public static final String DESCRIPTOR = "dev.denza.hudlink.HudHost";
    /** The batch format; a host refuses a source that writes another. */
    public static final int VERSION = 1;

    /** Two-way: the version and the source's token, whose death ends what it published. */
    public static final int TX_ATTACH = 1;
    /** One-way: a batch of calls. */
    public static final int TX_CALLS = 2;
    /** One-way: the source is done; everything it published goes. */
    public static final int TX_DETACH = 3;

    /** {@link #TX_ATTACH}'s answers. */
    public static final int ACCEPTED = 0;
    public static final int REFUSED_CALLER = 1;
    public static final int REFUSED_VERSION = 2;

    private static final int OP_LAYER = 1;
    private static final int OP_REMOVE_LAYER = 2;
    private static final int OP_FIELDS = 3;
    private static final int OP_WITHDRAW_ROLE = 4;
    private static final int OP_WITHDRAW_ALL = 5;

    private static final int KIND_NUMBER = 0;
    private static final int KIND_DECIMAL = 1;
    private static final int KIND_TEXT = 2;
    private static final int KIND_BYTES = 3;

    private HudHostProtocol() {
    }

    /**
     * {@link HudSink} calls written down in order, to go as one {@link #TX_CALLS}. The source
     * argument is not written. Roles go by name, so a host that does not know one skips its call.
     */
    public static final class Batch implements HudSink {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
        private final DataOutputStream out = new DataOutputStream(bytes);
        private int calls;

        public Batch() {
            try {
                out.writeInt(VERSION);
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        @Override
        public void layer(HudSource source, String id, HudRole role, HudRect rect, HudImage image,
                float opacity, long ttlMs) {
            write(() -> {
                out.writeByte(OP_LAYER);
                out.writeUTF(id);
                out.writeUTF(role.name());
                out.writeInt(rect.x);
                out.writeInt(rect.y);
                out.writeInt(rect.width);
                out.writeInt(rect.height);
                byte[] png = image.png();
                out.writeInt(png.length);
                out.write(png);
                out.writeFloat(opacity);
                out.writeLong(ttlMs);
            });
        }

        @Override
        public void removeLayer(HudSource source, String id) {
            write(() -> {
                out.writeByte(OP_REMOVE_LAYER);
                out.writeUTF(id);
            });
        }

        @Override
        public void fields(HudSource source, HudRole role, HudFields fields, long ttlMs) {
            write(() -> {
                out.writeByte(OP_FIELDS);
                out.writeUTF(role.name());
                int[] numbers = fields.fields();
                out.writeInt(numbers.length);
                for (int field : numbers) {
                    out.writeInt(field);
                    Object value = fields.value(field);
                    if (value instanceof Long) {
                        out.writeByte(KIND_NUMBER);
                        out.writeLong((Long) value);
                    } else if (value instanceof Double) {
                        out.writeByte(KIND_DECIMAL);
                        out.writeDouble((Double) value);
                    } else if (value instanceof String) {
                        out.writeByte(KIND_TEXT);
                        byte[] text = ((String) value).getBytes("UTF-8");
                        out.writeInt(text.length);
                        out.write(text);
                    } else {
                        out.writeByte(KIND_BYTES);
                        byte[] data = (byte[]) value;
                        out.writeInt(data.length);
                        out.write(data);
                    }
                }
                out.writeLong(ttlMs);
            });
        }

        @Override
        public void withdraw(HudSource source, HudRole role) {
            write(() -> {
                out.writeByte(OP_WITHDRAW_ROLE);
                out.writeUTF(role.name());
            });
        }

        @Override
        public void withdraw(HudSource source) {
            write(() -> out.writeByte(OP_WITHDRAW_ALL));
        }

        /** Whether any call was written. */
        public boolean isEmpty() {
            return calls == 0;
        }

        public byte[] toByteArray() {
            return bytes.toByteArray();
        }

        private void write(Write write) {
            try {
                write.run();
            } catch (IOException impossible) {
                // A ByteArrayOutputStream does not fail.
                throw new IllegalStateException(impossible);
            }
            calls++;
        }

        private interface Write {
            void run() throws IOException;
        }
    }

    /**
     * Plays {@code batch} into {@code sink} as {@code source}, in order. Returns false, having
     * played nothing more, at a batch of another version or one that does not read.
     */
    public static boolean replay(byte[] batch, HudSource source, HudSink sink) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(batch));
        try {
            if (in.readInt() != VERSION) {
                return false;
            }
            while (in.available() > 0) {
                int op = in.readByte();
                switch (op) {
                    case OP_LAYER: {
                        String id = in.readUTF();
                        HudRole role = role(in.readUTF());
                        HudRect rect = new HudRect(in.readInt(), in.readInt(), in.readInt(), in.readInt());
                        byte[] png = new byte[in.readInt()];
                        in.readFully(png);
                        float opacity = in.readFloat();
                        long ttl = in.readLong();
                        if (role != null && role.layered()) {
                            sink.layer(source, id, role, rect, HudImage.png(png), opacity, ttl);
                        }
                        break;
                    }
                    case OP_REMOVE_LAYER:
                        sink.removeLayer(source, in.readUTF());
                        break;
                    case OP_FIELDS: {
                        HudRole role = role(in.readUTF());
                        HudFields.Builder fields = new HudFields.Builder();
                        int count = in.readInt();
                        for (int index = 0; index < count; index++) {
                            int field = in.readInt();
                            int kind = in.readByte();
                            if (kind == KIND_NUMBER) {
                                fields.number(field, in.readLong());
                            } else if (kind == KIND_DECIMAL) {
                                fields.decimal(field, in.readDouble());
                            } else {
                                byte[] data = new byte[in.readInt()];
                                in.readFully(data);
                                if (kind == KIND_TEXT) {
                                    fields.text(field, new String(data, "UTF-8"));
                                } else {
                                    fields.bytes(field, data);
                                }
                            }
                        }
                        long ttl = in.readLong();
                        if (role != null && !role.layered()) {
                            sink.fields(source, role, fields.build(), ttl);
                        }
                        break;
                    }
                    case OP_WITHDRAW_ROLE: {
                        HudRole role = role(in.readUTF());
                        if (role != null) {
                            sink.withdraw(source, role);
                        }
                        break;
                    }
                    case OP_WITHDRAW_ALL:
                        sink.withdraw(source);
                        break;
                    default:
                        return false;
                }
            }
            return true;
        } catch (IOException | IllegalArgumentException unreadable) {
            return false;
        }
    }

    private static HudRole role(String name) {
        try {
            return HudRole.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
