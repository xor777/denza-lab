package dev.denza.apps.feature.hud

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Where Yandex is on the main screen, read from the shared accessibility service's window list and
 * kept for [maxAgeMs].
 *
 * The list costs a Binder call for itself and one more into the process of every application
 * window whose root is read, so it is not read per frame: a mirrored frame arriving within
 * [maxAgeMs] of the last read reuses it. A window that moved since then is caught by the next read.
 * Only application windows have their root read - their package is the one thing needed from it.
 *
 * Called from the map's own thread alone.
 */
internal class HudMapWindowReader(
    private val service: AccessibilityService,
    private val maxAgeMs: Long = MAX_AGE_MS,
) {
    private val bounds = Rect()
    private var readAt = Long.MIN_VALUE
    private var placement: YandexMapPlacement = YandexMapPlacement.NotOnMainScreen

    /** Yandex's place at [nowMs] on a monotonic clock, read again if the last read is too old. */
    fun placement(nowMs: Long): YandexMapPlacement {
        if (readAt == Long.MIN_VALUE || nowMs - readAt >= maxAgeMs) {
            readAt = nowMs
            placement = HudMapWindows.place(windows(), HudGuidanceSettings.NAVIGATOR_PACKAGE)
        }
        return placement
    }

    /** The next [placement] reads the list again. */
    fun forget() {
        readAt = Long.MIN_VALUE
    }

    /** The main screen's windows, as `AccessibilityService.getWindows` lists the default display. */
    private fun windows(): List<HudMapWindow> {
        val windows = try {
            service.windows
        } catch (_: RuntimeException) {
            null
        } ?: return emptyList()
        return windows.mapNotNull { window ->
            window ?: return@mapNotNull null
            window.getBoundsInScreen(bounds)
            val type = window.type
            val packageName = if (type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                val root = window.root
                try {
                    root?.packageName?.toString()
                } finally {
                    root?.recycle()
                }
            } else {
                null
            }
            HudMapWindow(
                packageName = packageName,
                type = type,
                layer = window.layer,
                bounds = PxRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                title = window.title?.toString(),
            )
        }
    }

    companion object {
        const val MAX_AGE_MS = 250L
    }
}
