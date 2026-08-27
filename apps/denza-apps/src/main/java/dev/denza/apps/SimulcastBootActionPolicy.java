package dev.denza.apps;

/** Exact allowlist for the non-exported runtime-recovery receiver. */
final class SimulcastBootActionPolicy {
    private static final String BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
    private static final String LOCKED_BOOT_COMPLETED =
            "android.intent.action.LOCKED_BOOT_COMPLETED";
    private static final String MY_PACKAGE_REPLACED =
            "android.intent.action.MY_PACKAGE_REPLACED";

    private SimulcastBootActionPolicy() {
    }

    static boolean shouldRecover(String action) {
        return BOOT_COMPLETED.equals(action)
                || LOCKED_BOOT_COMPLETED.equals(action)
                || MY_PACKAGE_REPLACED.equals(action);
    }
}
