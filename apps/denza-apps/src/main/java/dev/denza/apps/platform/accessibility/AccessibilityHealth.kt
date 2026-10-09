package dev.denza.apps.platform.accessibility

import android.content.Context
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
) {
    /** Whether a rider can work now; [extra] is the one further thing a feature needs of its own. */
    fun ready(extra: Boolean = true): Boolean = enabled && connected && extra

    companion object {
        fun read(context: Context): AccessibilityHealth = AccessibilityHealth(
            enabled = isEnabled(context),
            connected = AccessibilityHost.isConnected(),
        )

        private fun isEnabled(context: Context): Boolean = SharedAccessibilityAccess.isEnabled(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        )
    }
}
