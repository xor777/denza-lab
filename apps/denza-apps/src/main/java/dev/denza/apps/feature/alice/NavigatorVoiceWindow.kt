package dev.denza.apps.feature.alice

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.os.Handler
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Prefetch package identity off the key callback; recheck visible area and the actual node before click. */
@Suppress("DEPRECATION")
internal class NavigatorVoiceWindow(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val report: (String) -> Unit,
) {
    private var navigatorWindows: Set<Int> = emptySet()
    private var active = true
    private var refreshPending = false
    private var retryOnContent = true
    private val refresh = Runnable {
        refreshPending = false
        if (active) {
            navigatorWindows = runCatching {
                val windows = service.windows
                try {
                    retryOnContent = false
                    windows.filter { it.displayId == 0 && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                        .mapNotNull { window ->
                            val root = window.getRoot()
                            if (root == null) {
                                retryOnContent = true
                                null
                            } else try {
                                val packageName = root.packageName?.toString()
                                if (packageName == null || packageName == PACKAGE && !root.isVisibleToUser) {
                                    retryOnContent = true
                                }
                                window.id.takeIf { packageName == PACKAGE && root.isVisibleToUser }
                            } finally { root.recycle() }
                        }.toSet()
                } finally { windows.forEach { it.recycle() } }
            }.getOrElse { retryOnContent = true; emptySet() }
        }
    }

    fun refresh() {
        if (!active) return
        // Keep known package identities until the refresh runs. Fresh geometry below rejects
        // hidden/removed windows immediately, without a focus-change gap between split panes.
        retryOnContent = true
        if (!refreshPending) {
            refreshPending = true
            if (!handler.post(refresh)) refreshPending = false
        }
    }

    /** A null root during app opening is retried on its next content event, without a polling loop. */
    fun onNavigatorContent() { if (retryOnContent) refresh() }

    /** Only the small windows list is read during filtering; no node traversal or click IPC. */
    fun visibleNavigator(): Int? {
        if (!active) return null
        val windows = service.windows
        return try { selectVisible(windows) } finally { windows.forEach { it.recycle() } }
    }

    fun clickIfVisible(expectedWindow: Int): Boolean {
        if (!active) return false
        val target = withVisibleWindow(expectedWindow) { window ->
            if (window.id != expectedWindow) {
                note("control unavailable=window-hidden")
                return@withVisibleWindow null
            }
            val root = window.getRoot() ?: run {
                note("control unavailable=root-missing")
                return@withVisibleWindow null
            }
            try {
                if (root.packageName?.toString() != PACKAGE || !root.isVisibleToUser) return@withVisibleWindow null
                val matches = mutableListOf<AccessibilityNodeInfo>()
                try {
                    val selected = NavigatorVoiceControls.findUnique(
                        find = { id -> root.findAccessibilityNodeInfosByViewId(id).also(matches::addAll) },
                        usable = ::usable,
                    )
                    if (selected == null) note("control unavailable=target-count found=${matches.size} usable=${matches.count(::usable)}")
                    selected?.let(AccessibilityNodeInfo::obtain)
                } finally { matches.forEach { it.recycle() } }
            } finally { root.recycle() }
        } ?: return false
        return try {
            if (!target.refresh() || !usable(target)) {
                note("control unavailable=target-changed")
                false
            } else if (visibleNavigator() != expectedWindow) {
                note("control unavailable=window-hidden")
                false
            } else {
                // Semantic ACTION_CLICK is not a coordinate tap: the split divider's touch
                // region can cover this visible control's center (944 inside 780..956).
                // Keep touch-point occlusion checks for the reveal gesture only.
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK).also {
                    note("control action accepted=$it view=${target.viewIdResourceName}")
                }
            }
        } finally { target.recycle() }
    }

    /** Hidden route controls wake on a touch of the speed indicator (Navigator 29.8.1). */
    fun revealIfVisible(expectedWindow: Int, complete: (Boolean) -> Unit): Boolean {
        if (!active) return false
        val bounds = withVisibleWindow(expectedWindow) { window ->
            if (window.id != expectedWindow) return@withVisibleWindow null
            val root = window.getRoot() ?: return@withVisibleWindow null
            try {
                if (root.packageName?.toString() != PACKAGE || !root.isVisibleToUser) return@withVisibleWindow null
                // Missing is distinct from an ambiguous, disabled or rejected microphone.
                if (NavigatorVoiceControls.viewIds.any { hasNodes(root, it) }) return@withVisibleWindow null
                if (hasNodes(root, "$PACKAGE:id/alice_oknyx") ||
                    hasChildren(root, "$PACKAGE:id/maps_activity_alice_container") ||
                    hasChildren(root, "$PACKAGE:id/maps_activity_modal_container") ||
                    hasChildren(root, "$PACKAGE:id/navi_guidance_dialog_container")) return@withVisibleWindow null
                val route = uniqueVisible(root, ROUTE_TOUCH_CONTAINER) ?: return@withVisibleWindow null
                route.recycle()
                val speed = uniqueVisible(root, SPEED_VIEW) ?: return@withVisibleWindow null
                try {
                    if (!speed.refresh() || !speed.isVisibleToUser || !speed.isEnabled ||
                        !speed.isClickable || speed.viewIdResourceName != SPEED_VIEW ||
                        speed.packageName?.toString() != PACKAGE) return@withVisibleWindow null
                    Rect().also(speed::getBoundsInScreen).takeUnless { it.isEmpty }
                } finally { speed.recycle() }
            } finally { root.recycle() }
        } ?: return false
        if (!touchAllowed(expectedWindow, bounds)) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder().setDisplayId(0)
            .addStroke(GestureDescription.StrokeDescription(path, 0, 40)).build()
        return service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                note("reveal completed=true window=$expectedWindow")
                complete(visibleNavigator() == expectedWindow)
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                note("reveal completed=false window=$expectedWindow")
                complete(false)
            }
        }, handler).also { note("reveal accepted=$it view=$SPEED_VIEW window=$expectedWindow") }
    }

    private fun uniqueVisible(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByViewId(id)
        return try {
            nodes.filter { it.isVisibleToUser && it.isEnabled && it.packageName?.toString() == PACKAGE }
                .singleOrNull()?.let(AccessibilityNodeInfo::obtain)
        } finally { nodes.forEach { it.recycle() } }
    }

    private fun hasNodes(root: AccessibilityNodeInfo, id: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(id)
        return try { nodes.isNotEmpty() } finally { nodes.forEach { it.recycle() } }
    }

    private fun hasChildren(root: AccessibilityNodeInfo, id: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(id)
        return try { nodes.any { it.childCount > 0 } } finally { nodes.forEach { it.recycle() } }
    }

    /** Read fresh geometry immediately before dispatch; reject any overlapping higher window. */
    private fun touchAllowed(expectedWindow: Int, bounds: Rect): Boolean {
        val windows = service.windows
        return try {
            if (selectVisible(windows) != expectedWindow) return false
            val window = windows.first { it.id == expectedWindow }
            val windowBounds = Rect().also(window::getBoundsInScreen)
            if (bounds.isEmpty || !windowBounds.contains(bounds)) return false
            windows.none { other ->
                other.displayId == 0 && other.layer > window.layer &&
                    Region().also(other::getRegionInScreen).contains(bounds.centerX(), bounds.centerY())
            }
        } finally { windows.forEach { it.recycle() } }
    }

    fun close() {
        active = false
        navigatorWindows = emptySet()
        handler.removeCallbacks(refresh)
        refreshPending = false
    }

    private fun usable(node: AccessibilityNodeInfo): Boolean =
        node.packageName?.toString() == PACKAGE && node.viewIdResourceName in NavigatorVoiceControls.viewIds &&
            node.isVisibleToUser && node.isEnabled && node.isClickable

    private fun note(message: String) { runCatching { report(message) } }

    private fun selectVisible(windows: List<AccessibilityWindowInfo>): Int? =
        NavigatorWindowPolicy.select(windows.map { window ->
            NavigatorWindowCandidate(
                id = window.id, displayId = window.displayId, type = window.type,
                navigator = window.id in navigatorWindows,
                visibleArea = window.id in navigatorWindows && hasExposedArea(window, windows),
                focused = window.isFocused,
            )
        })

    private fun hasExposedArea(window: AccessibilityWindowInfo, windows: List<AccessibilityWindowInfo>): Boolean {
        val exposed = Region().also(window::getRegionInScreen)
        windows.filter { it.displayId == window.displayId && it.layer > window.layer }.forEach { other ->
            exposed.op(Region().also(other::getRegionInScreen), Region.Op.DIFFERENCE)
        }
        return !exposed.isEmpty
    }

    private fun <T> withVisibleWindow(expectedWindow: Int, read: (AccessibilityWindowInfo) -> T?): T? {
        val windows = service.windows
        return try {
            if (selectVisible(windows) == expectedWindow) read(windows.first { it.id == expectedWindow }) else null
        } finally { windows.forEach { it.recycle() } }
    }

    companion object {
        const val PACKAGE = "ru.yandex.yandexnavi"
        private const val SPEED_VIEW = "$PACKAGE:id/speedview_guidance"
        private const val ROUTE_TOUCH_CONTAINER = "$PACKAGE:id/navi_guidance_controls_touch_container"
    }
}
