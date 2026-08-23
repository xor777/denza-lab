package dev.denza.apps.feature.split

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.denza.apps.AccessibilityServiceSettings
import dev.denza.apps.AccessibilitySettingsMutationLock

internal enum class SplitAccessibilityEventTarget { STOCK_PICKER, PRODUCT_PICKER, HOME, IGNORE }

internal object SplitAccessibilityEventPolicy {
    fun isTopologyHint(eventType: Int): Boolean =
        eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

    fun target(packageName: String?, className: String?): SplitAccessibilityEventTarget = when {
        packageName == STOCK_PICKER_PACKAGE && className == STOCK_PICKER_ACTIVITY ->
            SplitAccessibilityEventTarget.STOCK_PICKER
        packageName == PRODUCT_PICKER_PACKAGE && className == PRODUCT_PICKER_ACTIVITY ->
            SplitAccessibilityEventTarget.PRODUCT_PICKER
        packageName == HOME_PACKAGE -> SplitAccessibilityEventTarget.HOME
        else -> SplitAccessibilityEventTarget.IGNORE
    }

    private const val STOCK_PICKER_PACKAGE = "com.android.launcher3"
    private const val STOCK_PICKER_ACTIVITY = "com.android.launcher3.SplitScreenListActivity"
    private const val PRODUCT_PICKER_PACKAGE = "dev.denza.apps"
    private const val PRODUCT_PICKER_ACTIVITY =
        "dev.denza.apps.feature.split.SplitPickerActivity"
    private const val HOME_PACKAGE = "com.byd.mycar"
}

internal object SplitNativePickerAccessibilityAccess {
    const val COMPONENT =
        "dev.denza.apps/dev.denza.apps.feature.split.SplitNativePickerAccessibilityService"
    const val CONFIGURATION_VERSION = 6

    private val aliases = setOf(
        COMPONENT,
        "dev.denza.apps/.feature.split.SplitNativePickerAccessibilityService",
    )

    fun isEnabled(entries: List<String>): Boolean = entries.any(aliases::contains)

    fun withoutService(entries: List<String>): List<String> = entries
        .filterNot(aliases::contains)
        .distinct()

    fun withService(entries: List<String>): List<String> = buildList {
        addAll(withoutService(entries))
        add(COMPONENT)
    }
}

/** Exact event source for the stock picker and an authority-checked Home gate suspension. */
class SplitNativePickerAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        connected = true
        val packages = serviceInfo.packageNames?.joinToString().orEmpty().ifBlank { "<all>" }
        Log.i(TAG, "service connected packages=$packages")
        if (SplitScreenSettings.isEnabled(this)) {
            // Invariant 8, contract 4.2: reconnecting an observer is not an event on the screen.
            // This service is re-bound by the very lease an `OPEN` takes on its way in, so a Home
            // synthesised here would be a hint the product manufactured about the screen its own
            // launch started from - and it used to cancel that launch. The observer reports only
            // what it actually observes now.
            // Do not start the intentionally long drag-settle wait merely because the service
            // reconnected. Run it only when the exact stock picker is already in a visible window.
            val stockPickerVisible = windows.any { window ->
                val root = window.root ?: return@any false
                SplitAccessibilityEventPolicy.target(
                    packageName = root.packageName?.toString(),
                    className = root.className?.toString(),
                ) == SplitAccessibilityEventTarget.STOCK_PICKER
            }
            if (stockPickerVisible) SplitScreenCoordinator.onNativePickerVisible(this)
        }
    }

    /**
     * The filter comes first, always.
     *
     * A window storm used to reach the coordinator before anything had looked at who sent it
     * (A.3.5): every single `TYPE_WINDOWS_CHANGED` scheduled work on the one blocking executor.
     * Now the exact source decides what this event even is, and only then does a topology hint
     * become a coalesced reconcile that no-ops without a live product scene.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString()
        val className = event.className?.toString()
        val target = SplitAccessibilityEventPolicy.target(packageName, className)
        if (target != SplitAccessibilityEventTarget.IGNORE) {
            Log.i(TAG, "window event target=$target package=$packageName class=$className")
        }
        when (target) {
            SplitAccessibilityEventTarget.STOCK_PICKER ->
                SplitScreenCoordinator.onNativePickerVisible(this)
            SplitAccessibilityEventTarget.PRODUCT_PICKER ->
                SplitScreenCoordinator.onProductPickerVisible(this)
            SplitAccessibilityEventTarget.HOME ->
                SplitScreenCoordinator.onHomeVisible(this)
            SplitAccessibilityEventTarget.IGNORE -> Unit
        }
        // Hidden picker Activities do not consistently receive a configuration callback when BYD
        // collapses an edge, so a window change anywhere is still the signal that our own topology
        // may have moved underneath us. It is one coalesced hint, and it costs nothing at all
        // unless a live product scene exists to reconcile.
        if (SplitAccessibilityEventPolicy.isTopologyHint(event.eventType)) {
            SplitScreenCoordinator.onDividerResized(this)
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DenzaSplitPickerA11y"

        @Volatile
        private var connected = false

        internal fun isConnected(): Boolean = connected
    }
}

/** Owns only the dedicated split-picker accessibility component entry. */
internal class SplitNativePickerAccessController(
    private val shell: (String) -> String,
    private val leaseStore: SplitNativePickerAccessLeaseStore,
    private val pauseAfterDisable: (Long) -> Unit = Thread::sleep,
    private val isConnected: () -> Boolean = SplitNativePickerAccessibilityService::isConnected,
) {
    private val settings = AccessibilityServiceSettings(shell)

    fun enable() = AccessibilitySettingsMutationLock.withLock {
        val current = settings.read()
        val alreadyEnabled = SplitNativePickerAccessibilityAccess.isEnabled(current)
        if (
            alreadyEnabled &&
            leaseStore.configurationVersion() >=
            SplitNativePickerAccessibilityAccess.CONFIGURATION_VERSION &&
            isConnected()
        ) {
            return@withLock
        }

        val withoutService = SplitNativePickerAccessibilityAccess.withoutService(current)
        val wasOwned = leaseStore.isOwned()
        try {
            if (alreadyEnabled) {
                settings.write(withoutService, ensureAccessibilityEnabled = false)
                pauseAfterDisable(REBIND_SETTLE_MS)
            }
            settings.write(
                SplitNativePickerAccessibilityAccess.withService(withoutService),
                ensureAccessibilityEnabled = true,
            )
            check(SplitNativePickerAccessibilityAccess.isEnabled(settings.read())) {
                "Система не включила наблюдение за штатным picker"
            }
            check(leaseStore.setOwned(true)) {
                "Не удалось сохранить владение наблюдением за picker"
            }
            check(
                leaseStore.setConfigurationVersion(
                    SplitNativePickerAccessibilityAccess.CONFIGURATION_VERSION,
                ),
            ) {
                "Не удалось сохранить версию наблюдения за picker"
            }
        } catch (error: Throwable) {
            if (alreadyEnabled) {
                runCatching {
                    settings.write(current, ensureAccessibilityEnabled = true)
                }
            }
            runCatching { leaseStore.setOwned(wasOwned) }
            throw error
        }
    }

    fun restore() = AccessibilitySettingsMutationLock.withLock {
        if (!leaseStore.isOwned()) return@withLock
        val current = settings.read()
        if (SplitNativePickerAccessibilityAccess.isEnabled(current)) {
            settings.write(
                SplitNativePickerAccessibilityAccess.withoutService(current),
                ensureAccessibilityEnabled = false,
            )
            check(!SplitNativePickerAccessibilityAccess.isEnabled(settings.read())) {
                "Система не выключила наблюдение за штатным picker"
            }
        }
        check(leaseStore.setOwned(false)) {
            "Не удалось завершить владение наблюдением за picker"
        }
    }

    private companion object {
        // Version 3 removed the XML package filter. Versions 4-5 wait for the asynchronous
        // Android unbind before adding the component back; live firmware showed that 250 ms was
        // still racy, while one second consistently produced a new unfiltered service instance.
        // Version 6 leaves accessibility_enabled untouched during the unbind half. Re-enabling it
        // before Android completes the removal can produce a formally bound service that receives
        // no window events on this firmware.
        const val REBIND_SETTLE_MS = 1_000L
    }
}

internal interface SplitNativePickerAccessLeaseStore {
    fun isOwned(): Boolean
    fun setOwned(owned: Boolean): Boolean
    fun configurationVersion(): Int = 0
    fun setConfigurationVersion(version: Int): Boolean = true
}
