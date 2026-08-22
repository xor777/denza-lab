package dev.denza.apps.feature.split

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/** Exact event source for the stock picker created by the SmartMulti divider gesture. */
class SplitNativePickerAccessibilityService : AccessibilityService() {
    private val interactionBlocker by lazy {
        SplitNativePickerInteractionBlocker(this)
    }

    override fun onServiceConnected() {
        Log.i(TAG, "service connected")
        if (SplitScreenSettings.isEnabled(this)) {
            // Also handles a stock picker that was already visible while the service was binding.
            SplitScreenCoordinator.onNativePickerVisible(this)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != STOCK_PICKER_PACKAGE ||
            event.className?.toString() != STOCK_PICKER_ACTIVITY
        ) {
            return
        }
        if (interactionBlocker.isShowing()) return

        interactionBlocker.show(event.source?.let { source ->
            Rect().also(source::getBoundsInScreen).takeIf { it.hasArea() }
        })
        val scheduled = SplitScreenCoordinator.onNativePickerVisible(this) {
            interactionBlocker.hide()
        }
        if (!scheduled) {
            interactionBlocker.hide()
        }
    }

    override fun onInterrupt() {
        interactionBlocker.hide()
    }

    override fun onDestroy() {
        interactionBlocker.hide()
        super.onDestroy()
    }

    private fun Rect.hasArea(): Boolean = width() > 0 && height() > 0

    private companion object {
        const val TAG = "DenzaSplitPickerA11y"
        const val STOCK_PICKER_PACKAGE = "com.android.launcher3"
        const val STOCK_PICKER_ACTIVITY = "com.android.launcher3.SplitScreenListActivity"
    }
}

/** Owns only the dedicated split-picker accessibility component entry. */
internal class SplitNativePickerAccessController(
    private val shell: (String) -> String,
    private val leaseStore: SplitNativePickerAccessLeaseStore,
) {
    fun enable() {
        val current = read()
        if (current.any(ALIASES::contains)) return
        write(current.filterNot(ALIASES::contains) + COMPONENT)
        check(read().any(ALIASES::contains)) {
            "Система не включила наблюдение за штатным picker"
        }
        check(leaseStore.setOwned(true)) {
            "Не удалось сохранить владение наблюдением за picker"
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
        val ALIASES = setOf(
            COMPONENT,
            "dev.denza.apps/.feature.split.SplitNativePickerAccessibilityService",
        )
    }
}

internal interface SplitNativePickerAccessLeaseStore {
    fun isOwned(): Boolean
    fun setOwned(owned: Boolean): Boolean
}
