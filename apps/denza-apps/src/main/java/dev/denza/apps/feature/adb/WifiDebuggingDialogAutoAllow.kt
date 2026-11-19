package dev.denza.apps.feature.adb

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

internal interface WifiDialogNode {
    val checked: Boolean
    fun find(id: String): WifiDialogNode?
    /** Whether a visible node of this window says exactly [text]. */
    fun says(text: String): Boolean
    fun click(): Boolean
}

internal enum class WifiDialogOutcome { IGNORED, NO_ALLOW_BUTTON, ALLOW_CLICK_REJECTED, ALLOWED, ALLOWED_ONCE }

/** Selection policy independent of Android nodes. Other SystemUI dialogs cannot match. */
internal object WifiDebuggingDialogPolicy {
    const val SYSTEM_UI = "com.android.systemui"
    const val ALLOW_ID = "android:id/button1"
    val CHECKBOX_IDS = listOf("android:id/alwaysUse", "com.android.internal:id/alwaysUse")

    /** SystemUI's own resource for the dialog's title, read in the system's language. */
    const val TITLE_RESOURCE = "wifi_debugging_title"

    fun matches(packageName: String?, className: String?): Boolean =
        packageName == SYSTEM_UI &&
            (className == "WifiDebuggingActivity" || className?.endsWith(".WifiDebuggingActivity") == true)

    /**
     * Whether [root] is SystemUI's wireless-debugging network dialog. A window-state event names the
     * activity, and that is the test. A window found already up when the service connects names
     * none: it is SystemUI's, and one of its nodes says exactly SystemUI's own [TITLE_RESOURCE]
     * ([title]). The USB authorization dialog has the same button and checkbox ids and its own
     * title, so neither entry can reach it.
     */
    fun recognizes(packageName: String?, className: String?, root: WifiDialogNode?, title: String?): Boolean = when {
        className != null -> matches(packageName, className)
        else -> packageName == SYSTEM_UI && !title.isNullOrBlank() && root?.says(title) == true
    }

    fun allow(enabled: Boolean, packageName: String?, className: String?, root: WifiDialogNode?,
        title: String? = null): WifiDialogOutcome {
        if (!enabled || !recognizes(packageName, className, root, title)) return WifiDialogOutcome.IGNORED
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

    /**
     * The service has just connected: a dialog that came up before it did sent its window-state event
     * to nobody, and no other comes while it stands. Each SystemUI window already up is offered once
     * to the same policy, recognised by SystemUI's own title in place of the activity name the event
     * would have carried. Once, here - no polling. Since 2026-11-19: a dialog wave writes once, so
     * a late service no longer gets a fresh dialog 15 s later to press.
     */
    @Suppress("DEPRECATION")
    fun onConnected(service: AccessibilityService) {
        if (!AdbRestore.isEnabled(service)) return
        val title = systemTitle(service) ?: return
        val windows = try { service.windows } catch (_: Exception) { return }
        for (window in windows) {
            val root = try { window.root } catch (_: Exception) { null } ?: continue
            if (root.packageName?.toString() != WifiDebuggingDialogPolicy.SYSTEM_UI) { root.recycle(); continue }
            click(service, WifiDebuggingDialogPolicy.SYSTEM_UI, null, window.id, root, title)
        }
    }

    /** SystemUI's dialog title in the system's language, not the app's own choice of language. */
    private fun systemTitle(context: Context): String? = try {
        val system = context.createPackageContext(WifiDebuggingDialogPolicy.SYSTEM_UI, 0)
            .createConfigurationContext(Configuration(Resources.getSystem().configuration))
        val id = system.resources.getIdentifier(WifiDebuggingDialogPolicy.TITLE_RESOURCE, "string",
            WifiDebuggingDialogPolicy.SYSTEM_UI)
        if (id == 0) null else system.resources.getString(id)
    } catch (_: Exception) { null }

    /**
     * Offers one window to the policy and records what came of it. [className] is the event's
     * activity, or null for a window found on connecting, which is recognised by [title] instead and
     * records nothing when it is not the dialog - SystemUI has other windows.
     */
    @Suppress("DEPRECATION")
    private fun click(service: AccessibilityService, packageName: String?, className: String?, windowId: Int,
        source: AccessibilityNodeInfo?, title: String? = null): WifiDialogOutcome {
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
            fun visible(node: AccessibilityNodeInfo) = node.windowId == windowId && node.isVisibleToUser
            fun wrap(node: AccessibilityNodeInfo): WifiDialogNode = object : WifiDialogNode {
                override val checked: Boolean get() = node.isChecked
                override fun find(id: String): WifiDialogNode? = node.findAccessibilityNodeInfosByViewId(id)
                    .onEach { retained.add(it) }
                    .firstOrNull { visible(it) && it.isEnabled && it.isClickable }
                    ?.let(::wrap)
                override fun says(text: String): Boolean = node.findAccessibilityNodeInfosByText(text)
                    .onEach { retained.add(it) }
                    .any { visible(it) && it.text?.toString() == text }
                override fun click(): Boolean = AdbRestore.isEnabled(service) &&
                    node.windowId == windowId && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            val outcome = WifiDebuggingDialogPolicy.allow(AdbRestore.isEnabled(service), packageName, className,
                validRoot?.let(::wrap), title)
            if (className == null && outcome == WifiDialogOutcome.IGNORED) return outcome
            AdbRestore.recordAutoAllow(outcome.name.lowercase())
            if (outcome == WifiDialogOutcome.ALLOWED || outcome == WifiDialogOutcome.ALLOWED_ONCE) AdbRestore.trigger("dialog-allowed")
            return outcome
        } catch (_: Exception) {
            if (className != null) AdbRestore.recordAutoAllow("window-unavailable")
            return WifiDialogOutcome.NO_ALLOW_BUTTON
        } finally {
            retained.distinct().forEach { it.recycle() }
        }
    }
}
