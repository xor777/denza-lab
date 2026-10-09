package dev.denza.apps.feature.hud

/**
 * One window of a screen as the accessibility service lists it: the package of its root node (read
 * only for application windows, where it names the application), its `AccessibilityWindowInfo`
 * type and layer, and its bounds in the screen's pixels.
 */
data class HudMapWindow(
    val packageName: String?,
    val type: Int,
    val layer: Int,
    val bounds: PxRect,
    val title: String? = null,
) {
    /** How «Сервис» names the window that hides the map: title or package, type and bounds. */
    fun describe(): String =
        "${title ?: packageName ?: "окно"}, тип $type, [${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"
}

/** Where Yandex's map is drawn, as far as the map window is concerned. */
sealed interface YandexMapPlacement {
    /**
     * Yandex has no application window on the main screen: it is on the instruments, minimised or
     * not running. The instruments are a source of their own, not built yet; for now this sends no
     * frame.
     */
    data object NotOnMainScreen : YandexMapPlacement

    /** Yandex's application window on the main screen, and every window drawn over any part of it. */
    data class MainScreen(val window: PxRect, val above: List<HudMapWindow>) : YandexMapPlacement
}

/** Reads Yandex's place out of a screen's window list, and whether anything hides the cut. */
object HudMapWindows {
    /** `AccessibilityWindowInfo.TYPE_APPLICATION`. */
    const val TYPE_APPLICATION = 1

    /**
     * How deep, in dp, another window must reach into the cut to hide it. A window that only
     * grazes its edge - the split's divider beside a one-third pane, where the cut is the pane's
     * whole width - would otherwise blank the map for good.
     */
    const val COVER_DEPTH_DP = 4f

    /**
     * Yandex among [windows]: its largest application window, and the windows layered over it that
     * reach into it. Its own other windows count among them - a dialog of Yandex's over the map
     * hides the map as much as anyone's.
     */
    fun place(windows: List<HudMapWindow>, navigator: String): YandexMapPlacement {
        val yandex = windows
            .filter { it.type == TYPE_APPLICATION && it.packageName == navigator && !it.bounds.isEmpty }
            .maxByOrNull { it.bounds.width.toLong() * it.bounds.height }
            ?: return YandexMapPlacement.NotOnMainScreen
        val above = windows.filter { other ->
            other !== yandex && other.layer > yandex.layer && other.bounds.overlaps(yandex.bounds)
        }
        return YandexMapPlacement.MainScreen(yandex.bounds, above)
    }

    /** The first of [above] that reaches [depth] pixels into [crop], or null when the cut is clear. */
    fun coverer(crop: PxRect, above: List<HudMapWindow>, depth: Int): HudMapWindow? =
        above.firstOrNull { it.bounds.overlaps(crop, depth.coerceAtLeast(1)) }
}
