package dev.denza.apps.feature.split

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

internal enum class SplitAccessibilityEventTarget { STOCK_PICKER, HOME, IGNORE }

internal object SplitAccessibilityEventPolicy {
    fun target(packageName: String?, className: String?): SplitAccessibilityEventTarget = when {
        packageName == STOCK_PICKER_PACKAGE && className == STOCK_PICKER_ACTIVITY ->
            SplitAccessibilityEventTarget.STOCK_PICKER
        packageName == HOME_PACKAGE -> SplitAccessibilityEventTarget.HOME
        else -> SplitAccessibilityEventTarget.IGNORE
    }

    private const val STOCK_PICKER_PACKAGE = "com.android.launcher3"
    private const val STOCK_PICKER_ACTIVITY = "com.android.launcher3.SplitScreenListActivity"
    private const val HOME_PACKAGE = "com.byd.mycar"
}

/** Exact event source for the stock picker and an authority-checked Home gate suspension. */
class SplitNativePickerAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        Log.i(TAG, "service connected")
        if (SplitScreenSettings.isEnabled(this)) {
            // A reconnect may happen after Home was already shown. The coordinator still requires
            // firmware area 0 and an owned gate before it mutates anything.
            SplitScreenCoordinator.onHomeVisible(this)
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (
            SplitAccessibilityEventPolicy.target(
                packageName = event?.packageName?.toString(),
                className = event?.className?.toString(),
            )
        ) {
            SplitAccessibilityEventTarget.STOCK_PICKER ->
                SplitScreenCoordinator.onNativePickerVisible(this)
            SplitAccessibilityEventTarget.HOME ->
                SplitScreenCoordinator.onHomeVisible(this)
            SplitAccessibilityEventTarget.IGNORE -> Unit
        }
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val TAG = "DenzaSplitPickerA11y"
    }
}

/** Owns only the dedicated split-picker accessibility component entry. */
internal class SplitNativePickerAccessController(
    private val shell: (String) -> String,
    private val leaseStore: SplitNativePickerAccessLeaseStore,
) {
    fun enable() {
        val current = read()
        val alreadyEnabled = current.any(ALIASES::contains)
        if (alreadyEnabled && leaseStore.configurationVersion() >= CONFIGURATION_VERSION) return

        val withoutService = current.filterNot(ALIASES::contains)
        val wasOwned = leaseStore.isOwned()
        if (alreadyEnabled) write(withoutService)
        try {
            write(withoutService + COMPONENT)
            check(read().any(ALIASES::contains)) {
                "Система не включила наблюдение за штатным picker"
            }
            check(leaseStore.setOwned(true)) {
                "Не удалось сохранить владение наблюдением за picker"
            }
            check(leaseStore.setConfigurationVersion(CONFIGURATION_VERSION)) {
                "Не удалось сохранить версию наблюдения за picker"
            }
        } catch (error: Throwable) {
            if (alreadyEnabled) runCatching { write(current) }
            runCatching { leaseStore.setOwned(wasOwned) }
            throw error
        }
    }

    fun restore() {
        if (!leaseStore.isOwned()) return
        val current = read()
        if (current.any(ALIASES::contains)) {
            write(current.filterNot(ALIASES::contains))
            check(read().none(ALIASES::contains)) {
                "Система не выключила наблюдение за штатным picker"
            }
        }
        check(leaseStore.setOwned(false)) {
            "Не удалось завершить владение наблюдением за picker"
        }
    }

    private fun read(): List<String> = shell(
        "settings get secure enabled_accessibility_services",
    ).trim()
        .takeUnless { it.isEmpty() || it == "null" }
        ?.split(':')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.distinct()
        .orEmpty()

    private fun write(entries: List<String>) {
        val value = entries.distinct().joinToString(":")
        val output = shell(
            "settings put secure enabled_accessibility_services ${shellQuote(value)}; " +
                "settings put secure accessibility_enabled 1",
        )
        check(
            !output.contains("Error", ignoreCase = true) &&
                !output.contains("Exception", ignoreCase = true),
        ) { output.trim().ifBlank { "settings command failed" } }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private companion object {
        const val COMPONENT =
            "dev.denza.apps/dev.denza.apps.feature.split.SplitNativePickerAccessibilityService"
        const val CONFIGURATION_VERSION = 2
        val ALIASES = setOf(
            COMPONENT,
            "dev.denza.apps/.feature.split.SplitNativePickerAccessibilityService",
        )
    }
}

internal interface SplitNativePickerAccessLeaseStore {
    fun isOwned(): Boolean
    fun setOwned(owned: Boolean): Boolean
    fun configurationVersion(): Int = 0
    fun setConfigurationVersion(version: Int): Boolean = true
}
