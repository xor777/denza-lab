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
     * not running. The instruments are a source of their own ([HudMapClusterSource]); this one
     * sends no frame.
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
     * The share of the cut's width and of its height, on each side, that another window may lie
     * over without hiding the map: only a window over the middle hides it.
     *
     * The split on this car keeps a system window over the boundary of its panes - the divider's
     * handle, 176 px wide at x 780-956 on 2026-10-11 - that reaches 76 px into either pane. A cut
     * that followed the cursor to a pane's inner edge took it for a cover and blanked the map;
     * a rule of a few dp could not tell that strip from a window over the map, and a margin can.
     * What lies in the margin is drawn into the frame as it is on the screen.
     */
    const val COVER_MARGIN = 0.15f

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

    /** The first of [above] over the middle of [crop] ([COVER_MARGIN]), or null when the cut is clear. */
    fun coverer(crop: PxRect, above: List<HudMapWindow>): HudMapWindow? {
        val marginX = (crop.width * COVER_MARGIN).toInt()
        val marginY = (crop.height * COVER_MARGIN).toInt()
        val middle = PxRect(crop.left + marginX, crop.top + marginY, crop.right - marginX, crop.bottom - marginY)
        return above.firstOrNull { it.bounds.overlaps(middle) }
    }
}
