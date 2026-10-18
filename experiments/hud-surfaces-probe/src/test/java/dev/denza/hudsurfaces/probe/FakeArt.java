package dev.denza.hudsurfaces.probe;

/**
 * PNG headers with no pixels behind them, one per picture and each different, so a test can tell
 * which picture went where by its bytes alone.
 */
final class FakeArt implements Plans.Art {
    static final byte[] ARROW = png(80, 80, 1);
    static final byte[] CAMERA = png(80, 80, 2);
    static final byte[] LETTER_A = png(80, 80, 3);
    static final byte[] LETTER_B = png(80, 80, 4);
    static final byte[] MAP_A = png(300, 180, 5);
    static final byte[] MAP_B = png(300, 180, 6);
    static final byte[] BLACK = png(300, 180, 7);

    @Override
    public byte[] arrow() {
        return ARROW;
    }

    @Override
    public byte[] camera() {
        return CAMERA;
    }

    @Override
    public byte[] letter(SessionId session) {
        return session == SessionId.A ? LETTER_A : LETTER_B;
    }

    @Override
    public byte[] map(SessionId session) {
        return session == SessionId.A ? MAP_A : MAP_B;
    }

    @Override
    public byte[] blackMap() {
        return BLACK;
    }

    /** Signature, IHDR with the size, and one marker byte in place of the bit depth. */
    static byte[] png(int width, int height, int marker) {
        return new byte[] {
                (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n',
                0, 0, 0, 13, 'I', 'H', 'D', 'R',
                (byte) (width >>> 24), (byte) (width >>> 16), (byte) (width >>> 8), (byte) width,
                (byte) (height >>> 24), (byte) (height >>> 16), (byte) (height >>> 8), (byte) height,
                (byte) marker, 6, 0, 0, 0,
        };
    }
}
