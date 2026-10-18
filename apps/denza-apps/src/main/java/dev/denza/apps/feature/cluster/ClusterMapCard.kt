package dev.denza.apps.feature.cluster

/**
 * The HUD's map in the instruments' right card (docs/hud-output-architecture.md, 13).
 *
 * In one of its modes the right card of the instruments shows a small map: the instrument asks for
 * it, and the stock projection service starts the stock map's `MeterSmallScreenActivity` on the
 * overlay display, `shared_fission_bg_XDJAScreenProjection_1`. The stock map is empty in Russia, and
 * nobody but the stock map may be that card's provider. So while the card shows the stock map,
 * this app lays the HUD's map window over it, on the same display it already draws its camera on.
 *
 * Plain decisions here; [ClusterMapCardRider] finds the card, [ClusterMapCardRuntime] draws.
 */
internal object ClusterMapCard {
    /** The stock map: its window on the overlay display is the card. */
    const val STOCK_PACKAGE = "com.byd.launchermap"

    /** The stock small map's own view, `R.id.small_map_layout` of `instrument_small_screen_layout`. */
    const val MAP_VIEW_ID = "com.byd.launchermap:id/small_map_layout"

    /**
     * Where the stock small map draws on the 2560x720 overlay display when its view does not tell:
     * `instrument_small_screen_map_width`, 600 px, at the display's right edge, full height
     * (`MeterSmallScreenActivity`, the layout's end constraints; BydLaunchermap 34.1.33).
     */
    val STOCK_BOX = Box(1960, 0, 2560, 720)

    /** A rectangle in the overlay display's pixels. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** One window or view on the overlay display, as accessibility reports it. */
    data class Seen(val packageName: String?, val viewId: String?, val bounds: Box)

    /**
     * The card among what the overlay display shows: the stock small map's view where it reports
     * itself, else [STOCK_BOX] while a stock map window is there at all; null without one - the
     * card shows something else, or nothing.
     */
    fun find(seen: List<Seen>): Box? {
        val ours = seen.filter { it.packageName == STOCK_PACKAGE }
        if (ours.isEmpty()) return null
        return ours.firstOrNull { it.viewId == MAP_VIEW_ID && it.bounds.width > 0 && it.bounds.height > 0 }?.bounds
            ?: STOCK_BOX
    }

    /**
     * Whether the card shows the HUD's map: switched on, the stock map's card there, a picture to
     * draw, and no camera on the same display - a turn signal's camera always comes first.
     */
    fun shows(enabled: Boolean, card: Box?, cameraOnScreen: Boolean, hasFrame: Boolean): Boolean =
        enabled && card != null && !cameraOnScreen && hasFrame

    /** Whether the camera layer holds the overlay display: from its start until it has gone. */
    fun cameraOnScreen(phase: CameraRuntimePhase): Boolean =
        phase == CameraRuntimePhase.STARTING || phase == CameraRuntimePhase.READY ||
            phase == CameraRuntimePhase.STOPPING

    /**
     * Where the HUD's picture stands in the card: as wide as the card, its own proportions,
     * centred top to bottom, and never taller than the card - the top of the map, the road ahead,
     * is the part that matters, so nothing of it is cut.
     */
    fun frameBox(card: Box, frameWidth: Int, frameHeight: Int): Box {
        if (frameWidth <= 0 || frameHeight <= 0) return card
        val scale = minOf(card.width.toFloat() / frameWidth, card.height.toFloat() / frameHeight)
        val w = Math.round(frameWidth * scale)
        val h = Math.round(frameHeight * scale)
        val left = card.left + (card.width - w) / 2
        val top = card.top + (card.height - h) / 2
        return Box(left, top, left + w, top + h)
    }
}
