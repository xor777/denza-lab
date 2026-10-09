package dev.denza.apps.platform.accessibility

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * The shared accessibility service as every feature that rides on it asks after it: switched on in
 * the car's `enabled_accessibility_services`, and bound to this process now.
 *
 * A service the setting lists but nobody bound - crashed, or never brought back after an update - is
 * as useless to a rider as one switched off, so [ready] wants both, for every feature alike, and a
 * feature that is not ready asks [AccessibilityRepair] to repair it. Until 2026-10-09 each feature
 * had its own rule: the speakers asked only whether it was switched on and so never repaired a
 * service that was on but gone; the ★ key and HUD guidance asked for both; the projection also for
 * its overlay grant - its own extra requirement, which is what [ready]'s argument is for.
 */
data class AccessibilityHealth(
    val enabled: Boolean,
    val connected: Boolean,
    /**
     * How long ago the system created a service instance that has not connected yet, or null when no
     * instance is on its way ([AccessibilityHost.bindingForMs]).
     */
    val bindingForMs: Long? = null,
) {
    /** Whether a rider can work now; [extra] is the one further thing a feature needs of its own. */
    fun ready(extra: Boolean = true): Boolean = enabled && connected && extra

    /**
     * How long a repair waits before it rewrites the setting: the rest of [BIND_GRACE_MS] while the
     * system is binding a listed service by itself - after an APK update or a boot, when the riders'
     * checks can run before the bind lands - and 0 for anything else. A service the firmware left
     * crashed is bound by nobody and has no instance, so its repair runs at once, as it always did.
     */
    fun bindWaitMs(): Long {
        val since = bindingForMs ?: return 0L
        if (!enabled || connected) return 0L
        return (BIND_GRACE_MS - since).coerceAtLeast(0L)
    }

    companion object {
        /**
         * Two seconds: the time the repair itself has always given the system to bind the shared
         * service after writing it to the setting (`DenzaAccessibilityRepairController`, the pause
         * before the split's observer is added), the order the car has kept since 2026-08-22
         * (split-screen-findings.md, "Live acceptance status"). A bind the system started by itself
         * is held to the same bound. Without the wait, a rider that checked in that window started a
         * repair that took both Denza services down for about three seconds.
         */
        const val BIND_GRACE_MS = 2_000L

        fun read(context: Context): AccessibilityHealth = AccessibilityHealth(
            enabled = isEnabled(context),
            connected = AccessibilityHost.isConnected(),
            bindingForMs = AccessibilityHost.bindingForMs(SystemClock.elapsedRealtime()),
        )

        private fun isEnabled(context: Context): Boolean = SharedAccessibilityAccess.isEnabled(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        )
    }
}
