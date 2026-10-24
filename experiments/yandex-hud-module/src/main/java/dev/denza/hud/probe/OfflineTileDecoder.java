package dev.denza.hud.probe;

import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdException;
import com.github.luben.zstd.util.Native;
import dev.denza.adaslink.OfflineVectorPackage;

/**
 * Independent offline tile decompressor. No Navigator native library, fixed addresses or hooks.
 * Dependency: official com.github.luben:zstd-jni:1.5.7-12 Android AAR, pinned and packaged by
 * tools/yandex_hud_build/offline_zstd.py. JNI class/library names must remain unchanged.
 *
 * <p>The Java frame check precedes native loading/allocation: one regular known-size frame,
 * at most 2 MiB of compressed data, dictionary, output and declared window. In particular the
 * one-shot native API cannot bypass the window limit with a large window descriptor. A decoded
 * buffer remains raw source data; it says nothing about road access, ADAS enums or eligibility.
 */
public final class OfflineTileDecoder implements OfflineVectorPackage.Decoder {
    public static final String DEPENDENCY = "com.github.luben:zstd-jni:1.5.7-12@aar";
    public static final String DEPENDENCY_SHA256 = "d941f350817e48af4cc72f9f473a3ce400727e3e93c8d85a3f623d2907d99bf2";
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_BLOCK_BYTES = 128 * 1024, MAX_BLOCKS = 16384;
    private static boolean loaded;

    @Override public byte[] decode(byte[] compressed, byte[] dictionary, int maximumOutputBytes) {
        if (compressed == null || compressed.length < 6 || compressed.length > MAX_BYTES || dictionary == null
                || dictionary.length > MAX_BYTES || maximumOutputBytes < 1 || maximumOutputBytes > MAX_BYTES)
            throw invalid("compressed/dictionary/output byte budget");
        // Own the bytes whose header and payload are checked; a caller cannot race the native read.
        byte[] input = compressed.clone(), dict = dictionary.clone();
        Frame frame = inspectFrame(input, maximumOutputBytes);
        load();
        byte[] output = new byte[frame.contentBytes];
        try (ZstdDecompressCtx context = new ZstdDecompressCtx()) {
            if (dict.length != 0) context.loadDict(dict);
            int actual = context.decompressByteArray(output, 0, output.length, input, 0, input.length);
            if (actual != output.length) throw invalid("decoded size differs from declared size");
            return output;
        } catch (ZstdException malformed) {
            throw new IllegalArgumentException("offline tile zstd: dictionary/frame rejected", malformed);
        }
    }

    private static synchronized void load() {
        if (loaded) return;
        // Load the AAR's versioned Android .so, then disable the upstream JAR extraction fallback.
        System.loadLibrary("zstd-jni-1.5.7-12");
        Native.assumeLoaded();
        loaded = true;
    }

    static final class Frame {
        final int contentBytes;
        final long windowBytes;
        final int blocks;
        Frame(int contentBytes, long windowBytes, int blocks) {
            this.contentBytes = contentBytes; this.windowBytes = windowBytes; this.blocks = blocks;
        }
    }

    /** Format framing only, not decompression or checksum verification (the native decoder does that). */
    static Frame inspectFrame(byte[] input, int maximumOutputBytes) {
        if (input == null || input.length < 6 || input.length > MAX_BYTES
                || maximumOutputBytes < 1 || maximumOutputBytes > MAX_BYTES) throw invalid("frame byte budget");
        if (u8(input, 0) != 0x28 || u8(input, 1) != 0xb5 || u8(input, 2) != 0x2f || u8(input, 3) != 0xfd)
            throw invalid("regular frame magic required");
        int descriptor = u8(input, 4), position = 5, sizeFlag = descriptor >>> 6;
        if ((descriptor & 8) != 0) throw invalid("reserved frame bit");
        boolean singleSegment = (descriptor & 0x20) != 0;
        long window = 0;
        if (!singleSegment) {
            int description = u8(input, position++), exponent = 10 + (description >>> 3);
            if (exponent > 21) throw invalid("declared window exceeds 2 MiB");
            long base = 1L << exponent;
            window = base + (base >>> 3) * (description & 7);
            if (window > MAX_BYTES) throw invalid("declared window exceeds 2 MiB");
        }
        int dictionaryFlag = descriptor & 3, dictionaryBytes = dictionaryFlag == 3 ? 4 : dictionaryFlag;
        span(input, position, dictionaryBytes); position += dictionaryBytes;
        int sizeBytes = sizeFlag == 0 ? singleSegment ? 1 : 0 : 1 << sizeFlag;
        if (sizeBytes == 0) throw invalid("unknown decompressed size");
        long content = little(input, position, sizeBytes); position += sizeBytes;
        if (sizeBytes == 2) content += 256;
        if (content < 0 || content > maximumOutputBytes) throw invalid("declared output byte budget");
        if (singleSegment) window = content;
        if (window > MAX_BYTES) throw invalid("declared window byte budget");

        boolean last = false; int blocks = 0;
        while (!last) {
            if (++blocks > MAX_BLOCKS) throw invalid("frame block budget");
            int block = (int) little(input, position, 3); position += 3;
            last = (block & 1) != 0;
            int type = (block >>> 1) & 3, length = block >>> 3;
            if (type == 3 || length > MAX_BLOCK_BYTES) throw invalid("reserved/oversized block");
            int stored = type == 1 ? 1 : length;
            span(input, position, stored); position += stored;
        }
        if ((descriptor & 4) != 0) { span(input, position, 4); position += 4; }
        if (position != input.length) throw invalid("trailing data or multiple frames");
        return new Frame((int) content, window, blocks);
    }
    private static long little(byte[] bytes, int position, int width) {
        span(bytes, position, width); long value = 0;
        for (int i = 0; i < width; i++) {
            int octet = u8(bytes, position + i);
            if (i == 7 && octet >= 128) throw invalid("unsigned size exceeds supported range");
            value |= (long) octet << (i * 8);
        }
        return value;
    }
    private static int u8(byte[] bytes, int position) { span(bytes, position, 1); return bytes[position] & 255; }
    private static void span(byte[] bytes, int position, int size) {
        if (position < 0 || size < 0 || position > bytes.length || size > bytes.length - position) throw invalid("truncated frame");
    }
    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("offline tile zstd: " + reason);
    }
}
