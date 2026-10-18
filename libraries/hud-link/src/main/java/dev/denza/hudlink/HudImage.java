package dev.denza.hudlink;

/**
 * A layer's picture: a PNG, with the size read from its header. A source that publishes the same
 * image object again renews its layer without changing what is drawn, so the compositor neither
 * draws nor sends it anew.
 */
public final class HudImage {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private final byte[] png;
    public final int width;
    public final int height;

    private HudImage(byte[] png, int width, int height) {
        this.png = png;
        this.width = width;
        this.height = height;
    }

    /** The PNG as it is, not copied: the caller hands it over and does not change it after. */
    public static HudImage png(byte[] png) {
        // Signature, then the IHDR chunk: length, type, width and height big-endian.
        if (png == null || png.length < 24) {
            throw new IllegalArgumentException("not a PNG");
        }
        for (int index = 0; index < SIGNATURE.length; index++) {
            if (png[index] != SIGNATURE[index]) {
                throw new IllegalArgumentException("not a PNG");
            }
        }
        if (png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') {
            throw new IllegalArgumentException("PNG without IHDR first");
        }
        return new HudImage(png, bigEndian(png, 16), bigEndian(png, 20));
    }

    /** The PNG bytes; not a copy. */
    public byte[] png() {
        return png;
    }

    private static int bigEndian(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }
}
