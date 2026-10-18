package dev.denza.hudlink;

/** PNG headers for tests: what {@link HudImage} reads, with no pixels behind it. */
final class Pngs {
    private Pngs() {
    }

    static byte[] header(int width, int height) {
        return new byte[] {
                (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n',
                0, 0, 0, 13, 'I', 'H', 'D', 'R',
                (byte) (width >>> 24), (byte) (width >>> 16), (byte) (width >>> 8), (byte) width,
                (byte) (height >>> 24), (byte) (height >>> 16), (byte) (height >>> 8), (byte) height,
                8, 6, 0, 0, 0,
        };
    }

    static HudImage image(int width, int height) {
        return HudImage.png(header(width, height));
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format("%02x", b & 0xff));
        }
        return out.toString();
    }
}
