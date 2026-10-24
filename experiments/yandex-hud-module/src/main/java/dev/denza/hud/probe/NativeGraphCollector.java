package dev.denza.hud.probe;

import android.content.Context;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Exact Navigator build only. All calls belong on the evidence worker thread. */
public final class NativeGraphCollector {
    private static final String SOURCE_SHA256 =
            "c2842633cc3db8301f252aff52df86aab4c64a8b10b826f80f4f922a327d340f";
    private static final String[] STAT_NAMES = {
            "state", "seen", "captured", "polled", "dropped", "invalid", "oversize",
            "capturedBytes", "queueReady", "selfTestFailures", "queueSlots", "maxTileBytes", "installError"
    };
    private static boolean sourceVerified;
    private static boolean loaded;
    private static boolean started;
    private static String detail = "not_started";

    private NativeGraphCollector() { }

    /** Idempotent; verifies APK bytes, native build ID/prefix and synthetic ABI test. */
    public static synchronized boolean start(Context context) {
        if (started) return true;
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
                System.loadLibrary("denza_yandex_graph");
                loaded = true;
            }
            int result = nativeStart();
            started = result > 0;
            detail = describe(result);
            return started;
        } catch (Throwable failure) {
            detail = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
            return false;
        }
    }

    /** One untouched online graph tile; null means the bounded queue is empty. */
    public static synchronized byte[] poll() {
        return started ? nativePoll() : null;
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

    public static synchronized String status() { return detail; }

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
            case 2: return "installed_restore_rx_failed";
            case -2: return "maps_library_not_loaded";
            case -3: return "native_build_id_or_mapping_mismatch";
            case -4: return "decoder_prefix_mismatch";
            case -5: return "near_relay_unavailable";
            case -6: return "entry_mprotect_denied";
            case -7: return "entry_changed_during_install";
            case -9: return "install_in_progress";
            case -10: return "unsupported_page_size";
            case -11: return "synthetic_abi_test_failed";
            default: return "native_error_" + result;
        }
    }

    private static native int nativeStart();
    private static native byte[] nativePoll();
    private static native long[] nativeStats();
}
