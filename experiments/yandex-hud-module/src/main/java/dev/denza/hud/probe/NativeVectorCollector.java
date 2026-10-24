package dev.denza.hud.probe;

import android.content.Context;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Exact Navigator build only. All calls belong on the evidence worker thread. */
public final class NativeVectorCollector {
    private static final String SOURCE_SHA256 =
            "c2842633cc3db8301f252aff52df86aab4c64a8b10b826f80f4f922a327d340f";
    private static final String[] STAT_NAMES = {
            "state", "seen", "captured", "polled", "dropped", "invalid", "oversize",
            "capturedBytes", "queueReady", "selfTestFailures", "queueSlots", "maxTileBytes", "installError",
            "parseFailed", "mismatchedTileIds", "missingContext", "contextOverflow", "abandonedFrames",
            "reentrantCallbacks", "layerSeen", "wrapperSeen", "returnMismatch"
    };
    private static boolean sourceVerified;
    private static boolean loaded;
    private static boolean started;
    private static String detail = "not_started";

    private NativeVectorCollector() { }

    /** Idempotent; verifies APK bytes, native build ID/prefix and synthetic ABI test. */
    public static synchronized boolean start(Context context) {
        if (started) return stats()[0] == 1;
        if (!android.os.Process.is64Bit() || !"aarch64".equals(System.getProperty("os.arch"))) {
            detail = "unsupported_architecture";
            return false;
        }
        try {
            if (!sourceVerified) {
                verifySource(context);
                sourceVerified = true;
            }
            if (!loaded) {
                System.loadLibrary("denza_yandex_vector");
                loaded = true;
            }
            int result = nativeStart();
            started = result == 1;
            detail = describe(result);
            return started;
        } catch (Throwable failure) {
            detail = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
            return false;
        }
    }

    /**
     * One YVT1 packet; null means no successfully parsed tile is queued.
     * All integers are little endian: magic "YVT1"@0; u16 version=1@4,
     * headerBytes=72@6; u32 protobufBytes@8, flags=1@12; u64 sequence@16,
     * threadKey@24 (opaque TLS identity, never Linux TID). Three TileIds follow:
     * wrapper@32, render@44, source@56, each u32 x,y,z. Reserved u32@68 is zero.
     * The untouched native-success protobuf starts at offset 72. Native code
     * validates all three TileIds and equality before copying. No region filter.
     */
    public static synchronized byte[] poll() {
        return started && stats()[0] == 1 ? nativePoll() : null;
    }

    /** Values in the documented STAT_NAMES order; use statsMap for named metrics. */
    public static synchronized long[] stats() {
        if (loaded) return nativeStats();
        long[] values = new long[STAT_NAMES.length];
        values[0] = -100;
        values[9] = -1;
        values[10] = 8;
        values[11] = 2 * 1024 * 1024;
        return values;
    }

    public static synchronized Map<String, Long> statsMap() {
        long[] values = stats();
        Map<String, Long> result = new LinkedHashMap<String, Long>();
        for (int i = 0; i < STAT_NAMES.length && i < values.length; ++i)
            result.put(STAT_NAMES[i], Long.valueOf(values[i]));
        return result;
    }

    public static synchronized String status() {
        if (started) {
            long[] values = stats();
            if (values[0] != 1) detail = describe((int) values[0]);
        }
        return detail;
    }

    private static void verifySource(Context context) throws Exception {
        if (context == null) throw new IllegalArgumentException("context missing");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (ZipFile apk = new ZipFile(context.getApplicationInfo().sourceDir)) {
            ZipEntry entry = apk.getEntry("lib/arm64-v8a/libmaps-mobile.so");
            if (entry == null || entry.getSize() != 62862384L)
                throw new IllegalStateException("unsupported Navigator native source");
            try (InputStream input = apk.getInputStream(entry)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest.digest()) {
            int unsigned = value & 255;
            actual.append(Character.forDigit(unsigned >>> 4, 16));
            actual.append(Character.forDigit(unsigned & 15, 16));
        }
        if (!SOURCE_SHA256.equals(actual.toString()))
            throw new IllegalStateException("Navigator native SHA-256 mismatch");
    }

    private static String describe(int result) {
        switch (result) {
            case 1: return "installed";
            case -8: return "restore_rx_failed_collection_disabled";
            case -2: return "maps_library_not_loaded";
            case -3: return "native_build_id_or_mapping_mismatch";
            case -4: return "vector_hook_prefix_mismatch";
            case -5: return "near_relay_unavailable";
            case -6: return "entry_mprotect_denied";
            case -7: return "entry_changed_during_install";
            case -9: return "install_in_progress";
            case -10: return "unsupported_page_size";
            case -11: return "synthetic_abi_test_failed";
            case -12: return "context_overflow_collection_disabled";
            default: return "native_error_" + result;
        }
    }

    private static native int nativeStart();
    private static native byte[] nativePoll();
    private static native long[] nativeStats();
}
