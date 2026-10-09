package dev.denza.apps.platform.accessibility

import android.content.Context
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.apps.feature.split.SplitScreenSettings
import dev.denza.apps.platform.shell.shellQuote
import java.util.concurrent.Executors

/**
 * Brings the app's accessibility services back over the local shell, for every feature that rides
 * on the shared one: the overlay grant first, then [DenzaAccessibilityRepairController] - the shared
 * service, and the split's own last while the split is on - under [AccessibilitySettingsMutationLock].
 * Every owner that asks while a repair runs joins it ([AccessibilityRepairSingleFlight]) and hears
 * its one result.
 *
 * It knows the split's switch and lease because one transaction re-adds both services in a fixed
 * order (see the controller); that is the only feature it reads.
 */
internal object AccessibilityRepair {
    private val executor = Executors.newSingleThreadExecutor()
    private val flight = AccessibilityRepairSingleFlight()

    fun repair(context: Context, onComplete: (Throwable?) -> Unit) = repair(context, { true }, onComplete)

    /**
     * Repairs the overlay grant and the accessibility services, once for every owner that asks while
     * it runs; [stillWanted] lets an owner drop out, and the repair stops only when none still wants
     * it. [AccessibilityRepairSingleFlight] marks the steering wheel's row as a repair starts and
     * settles.
     */
    fun repair(context: Context, stillWanted: () -> Boolean, onComplete: (Throwable?) -> Unit) {
        if (!flight.join(onComplete, stillWanted)) return
        try {
            executor.execute {
                val failure = runCatching { repairNow(context, flight::isStillWanted) }.exceptionOrNull()
                flight.complete(failure)
            }
        } catch (error: RuntimeException) {
            flight.complete(error)
        }
    }

    fun isRunning(): Boolean = flight.isRunning()

    /**
     * The overlay grant the repair sends first. The package goes in quotes, as this repair has
     * always sent it; `ShellGrants.appop` and `OverlayGrant.command` leave it bare - the same word
     * to the shell, other bytes.
     */
    internal fun overlayGrantCommand(packageName: String): String =
        "cmd appops set ${shellQuote(packageName)} SYSTEM_ALERT_WINDOW allow"

    private fun repairNow(context: Context, stillWanted: () -> Boolean) {
        if (!stillWanted()) return
        val adb = DenzaLocalAdb.client(context).openPersistentShell()
        try {
            if (!stillWanted()) return
            adb.shell(overlayGrantCommand(context.packageName))
            DenzaAccessibilityRepairController(
                shell = adb::shell,
                splitLeaseStore = SplitScreenSettings.nativePickerAccessLeaseStore(context),
            ).repair(
                ensureSplit = SplitScreenSettings.isEnabled(context),
                stillWanted = stillWanted,
            )
        } finally {
            adb.close()
        }
    }
}
