package dev.denza.apps.platform.accessibility

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import dev.denza.apps.StateMarks

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
    /**
     * How long the service has stood listed in the setting without binding to this process, as
     * [AccessibilityBindWatch] counts it; 0 when it is bound, switched off, or only just seen unbound.
     */
    val unboundForMs: Long = 0L,
) {
    /** Whether a rider can work now; [extra] is the one further thing a feature needs of its own. */
    fun ready(extra: Boolean = true): Boolean = enabled && connected && extra

    /**
     * Whether a feature may still say it is waiting for the service: listed, and bound or within
     * [BIND_DEADLINE_MS] of binding. When it is not, the feature has no access, and its press repairs
     * - the same state, words and press as a service switched off.
     *
     * Until 2026-11-19 every feature but the projection read "listed" as "on its way": a service the
     * system never bound after an update kept the HUD tile on «Подключаю подсказки» for good, with the
     * panel's switch held disabled under it and no repair running.
     */
    val available: Boolean
        get() = enabled && (connected || unboundForMs < BIND_DEADLINE_MS)

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

        /**
         * Ten seconds. A bind takes milliseconds and a repair already waits [BIND_GRACE_MS] for one;
         * a service still unbound after ten is not coming, and only a repair brings it.
         */
        const val BIND_DEADLINE_MS = 10_000L

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        /**
         * The process's one bind clock. Its deadline reads again everything that reads the service
         * ([StateMarks.accessibilityChanged]), so a tile turns to no access without waiting for a
         * resume; a late bind marks the same slices as it connects, and clears it.
         */
        internal val bindWatch = AccessibilityBindWatch { delayMs ->
            mainHandler.postDelayed(
                { StateMarks.accessibilityChanged("access bind deadline") },
                delayMs,
            )
        }

        fun read(context: Context): AccessibilityHealth {
            val nowMs = SystemClock.elapsedRealtime()
            return bindWatch.read(
                enabled = isEnabled(context),
                connected = AccessibilityHost.isConnected(),
                bindingForMs = AccessibilityHost.bindingForMs(nowMs),
                nowMs = nowMs,
            )
        }

        private fun isEnabled(context: Context): Boolean = SharedAccessibilityAccess.isEnabled(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        )
    }
}

/**
 * How long the shared service has stood in the setting without binding, and one re-read
 * ([deadlineReached]) when that reaches [AccessibilityHealth.BIND_DEADLINE_MS].
 *
 * The clock starts the first time a read finds the service listed and unbound, and stops when it
 * binds or leaves the setting; a finished repair starts it again ([restart]), because the repair
 * rewrote the setting and the system binds anew. One clock for every feature: it was the
 * projection's own until 2026-11-19 (`SimulcastBindWatch`).
 */
internal class AccessibilityBindWatch(private val deadlineReached: (delayMs: Long) -> Unit) {
    private var unboundSinceMs: Long? = null

    @Synchronized
    fun read(enabled: Boolean, connected: Boolean, nowMs: Long, bindingForMs: Long? = null): AccessibilityHealth {
        val unboundForMs = if (!enabled || connected) {
            unboundSinceMs = null
            0L
        } else {
            val since = unboundSinceMs ?: nowMs.also {
                unboundSinceMs = it
                deadlineReached(AccessibilityHealth.BIND_DEADLINE_MS)
            }
            (nowMs - since).coerceAtLeast(0L)
        }
        return AccessibilityHealth(enabled, connected, bindingForMs, unboundForMs)
    }

    @Synchronized
    fun restart() {
        unboundSinceMs = null
    }
}
