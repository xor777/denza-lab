package dev.denza.apps.feature.adb

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

internal interface WifiDialogNode {
    val checked: Boolean
    fun find(id: String): WifiDialogNode?
    fun click(): Boolean
}

internal enum class WifiDialogOutcome { IGNORED, NO_ALLOW_BUTTON, ALLOW_CLICK_REJECTED, ALLOWED, ALLOWED_ONCE }

/** Selection policy independent of Android nodes. Other SystemUI dialogs cannot match. */
internal object WifiDebuggingDialogPolicy {
    const val ALLOW_ID = "android:id/button1"
    val CHECKBOX_IDS = listOf("android:id/alwaysUse", "com.android.internal:id/alwaysUse")

    fun matches(packageName: String?, className: String?): Boolean =
        packageName == "com.android.systemui" &&
            (className == "WifiDebuggingActivity" || className?.endsWith(".WifiDebuggingActivity") == true)

    fun allow(enabled: Boolean, packageName: String?, className: String?, root: WifiDialogNode?): WifiDialogOutcome {
        if (!enabled || !matches(packageName, className)) return WifiDialogOutcome.IGNORED
        val allow = root?.find(ALLOW_ID) ?: return WifiDialogOutcome.NO_ALLOW_BUTTON
        val checkbox = CHECKBOX_IDS.firstNotNullOfOrNull { root.find(it) }
        val remembered = checkbox?.let { it.checked || it.click() } ?: false
        if (!allow.click()) return WifiDialogOutcome.ALLOW_CLICK_REJECTED
        return if (remembered) WifiDialogOutcome.ALLOWED else WifiDialogOutcome.ALLOWED_ONCE
    }
}

internal object WifiDebuggingDialogAutoAllow {
    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableMapOf<Int, Runnable>() // accessibility callbacks run on main

    fun onEvent(service: AccessibilityService, event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString()
        val className = event.className?.toString()
        if (!AdbRestore.isEnabled(service) || !WifiDebuggingDialogPolicy.matches(packageName, className)) return
        val windowId = event.windowId
        val outcome = click(service, packageName, className, windowId, event.source)
        if (outcome == WifiDialogOutcome.NO_ALLOW_BUTTON && windowId !in pending) {
            // A window event may arrive before its nodes. Recheck the wish and the exact window,
            // never rootInActiveWindow (which can point at the cluster's projected display).
            val retry = Runnable {
                pending.remove(windowId)
                if (AdbRestore.isEnabled(service)) click(service, packageName, className, windowId, null)
            }
            pending[windowId] = retry
            handler.postDelayed(retry, 300)
        } else if (outcome != WifiDialogOutcome.NO_ALLOW_BUTTON) {
            pending.remove(windowId)?.let(handler::removeCallbacks)
        }
    }

    @Suppress("DEPRECATION")
    private fun click(service: AccessibilityService, packageName: String?, className: String?, windowId: Int,
        source: AccessibilityNodeInfo?): WifiDialogOutcome {
        val retained = mutableListOf<AccessibilityNodeInfo>()
        try {
            var root = source?.also { retained.add(it) }
            if (root?.windowId != windowId) root = null
            if (root == null) root = service.windows.firstOrNull { it.id == windowId }?.root?.also { retained.add(it) }
            while (root != null) {
                val parent = root.parent ?: break
                retained.add(parent)
                if (parent.windowId != windowId) break
                root = parent
            }
            val validRoot = root?.takeIf { it.windowId == windowId && it.packageName?.toString() == packageName }
            fun wrap(node: AccessibilityNodeInfo): WifiDialogNode = object : WifiDialogNode {
                override val checked: Boolean get() = node.isChecked
                override fun find(id: String): WifiDialogNode? = node.findAccessibilityNodeInfosByViewId(id)
                    .onEach { retained.add(it) }
                    .firstOrNull { it.windowId == windowId && it.isVisibleToUser && it.isEnabled && it.isClickable }
                    ?.let(::wrap)
                override fun click(): Boolean = AdbRestore.isEnabled(service) &&
                    node.windowId == windowId && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            val outcome = WifiDebuggingDialogPolicy.allow(AdbRestore.isEnabled(service), packageName, className,
                validRoot?.let(::wrap))
            AdbRestore.recordAutoAllow(outcome.name.lowercase())
            if (outcome == WifiDialogOutcome.ALLOWED || outcome == WifiDialogOutcome.ALLOWED_ONCE) AdbRestore.trigger("dialog-allowed")
            return outcome
        } catch (_: Exception) {
            AdbRestore.recordAutoAllow("window-unavailable")
            return WifiDialogOutcome.NO_ALLOW_BUTTON
        } finally {
            retained.distinct().forEach { it.recycle() }
        }
    }
}
