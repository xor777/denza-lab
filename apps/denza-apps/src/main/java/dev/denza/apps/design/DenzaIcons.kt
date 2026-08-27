package dev.denza.apps.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes

/**
 * The icons the design boards draw, drawn.
 *
 * The first cut of the tile grid reached into `Icons.Outlined` for whatever came closest by name,
 * and the screen ended up wearing a speedometer where the board draws a steering wheel, a video
 * camera where it draws an eye, and a folded map where it draws a windscreen. Only two of six
 * matched. Material's set is also mixed weight - some of it filled, some of it stroked at its own
 * thickness - so the row read as six icons borrowed from six places, which is what it was.
 *
 * These are the board's own paths, lifted from `Main.dc.html` and `Config.dc.html` unchanged.
 * Every one is a stroke of
 * [DenzaMetrics.Stroke.ICON_WEIGHT] at [DenzaMetrics.Component.TILE_ICON], which is the ladder's
 * own definition of optical weight: a 1.6 stroke in a 24 viewport drawn at 30 dp. Add an icon here
 * only after the board has one, and copy its path rather than approximating it.
 */
object DenzaIcons {

    /** The driver's own screen. A steering wheel, because what goes there is not always a map. */
    val Cluster: ImageVector = icon(
        "denza_cluster",
        inkLeft = 3.0f,
        "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0",
        "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0",
        "M3.2 10.5 L9.1 10.9 M20.8 10.5 L14.9 10.9 M12 15 L12 21",
    )

    /** Projection onto the other screens. */
    val Simulcast: ImageVector = icon(
        "denza_simulcast",
        inkLeft = 2.0f,
        "M2 20h0.01",
        "M2 16a4 4 0 0 1 4 4",
        "M2 12a8 8 0 0 1 8 8",
        "M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6",
    )

    /** The turn-indicator cameras: what the car is watching, not what it is recording. */
    val Mirrors: ImageVector = icon(
        "denza_mirrors",
        inkLeft = 2.0f,
        "M2 12s3.5-6.5 10-6.5S22 12 22 12s-3.5 6.5-10 6.5S2 12 2 12z",
        "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0",
    )

    /** One surface cut in two. */
    val Split: ImageVector = icon(
        "denza_split",
        inkLeft = 3.0f,
        "M5 4.5 H19 A2 2 0 0 1 21 6.5 V17.5 A2 2 0 0 1 19 19.5 H5 A2 2 0 0 1 3 17.5 V6.5 " +
            "A2 2 0 0 1 5 4.5 Z",
        "M12 4.5v15",
    )

    /** The head-up display: a windscreen with something projected on it, not a map. */
    val Hud: ImageVector = icon(
        "denza_hud",
        inkLeft = 5.0f,
        "M6 13.5l6-6 6 6",
        "M5 19h14",
    )

    /** The motorised speaker cover, matching the dashboard board. */
    val Speaker: ImageVector = icon(
        "denza_speaker",
        inkLeft = 6.0f,
        "M8 3 H16 A2 2 0 0 1 18 5 V19 A2 2 0 0 1 16 21 H8 A2 2 0 0 1 6 19 V5 " +
            "A2 2 0 0 1 8 3 Z",
        "M15.2 14a3.2 3.2 0 1 1-6.4 0a3.2 3.2 0 1 1 6.4 0",
        "M13.2 7a1.2 1.2 0 1 1-2.4 0a1.2 1.2 0 1 1 2.4 0",
    )

    /** An application sent to the passenger's screen. */
    val Passenger: ImageVector = icon(
        "denza_passenger",
        inkLeft = 5.0f,
        "M7 2.5 H17 A2 2 0 0 1 19 4.5 V19.5 A2 2 0 0 1 17 21.5 H7 A2 2 0 0 1 5 19.5 V4.5 " +
            "A2 2 0 0 1 7 2.5 Z",
        "M12 6.5v7",
        "M9 10.5l3 3 3-3",
        "M9 17.5h6",
    )

    /** The three application roles and the selected launch target, as DefaultApps.dc.html draws it. */
    val Applications: ImageVector = icon(
        "denza_applications",
        inkLeft = 3.0f,
        "M5 3H8A2 2 0 0 1 10 5V8A2 2 0 0 1 8 10H5A2 2 0 0 1 3 8V5A2 2 0 0 1 5 3Z",
        "M16 3H19A2 2 0 0 1 21 5V8A2 2 0 0 1 19 10H16A2 2 0 0 1 14 8V5A2 2 0 0 1 16 3Z",
        "M5 14H8A2 2 0 0 1 10 16V19A2 2 0 0 1 8 21H5A2 2 0 0 1 3 19V16A2 2 0 0 1 5 14Z",
        "M14.5 17.5l2 2 4-5",
    )

    /** Russian in the car's own settings. */
    val Locale: ImageVector = icon(
        "denza_locale",
        inkLeft = 3.0f,
        "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0",
        "M3.4 9.5h17.2M3.4 14.5h17.2",
        "M12 3a15 15 0 0 0 0 18 15 15 0 0 0 0-18z",
    )

    /** Weather: the sun the app supplies, half behind the cloud that is the car's own widget. */
    val Weather: ImageVector = icon(
        "denza_weather",
        inkLeft = 2.4f,
        "M8 4.8a3.2 3.2 0 1 1 0 6.4a3.2 3.2 0 1 1 0-6.4",
        "M8 2.4v1.4M8 12.2v1.4M2.4 8h1.4M12.2 8h1.4M4.6 4.6l1 1M11.4 4.6l-1 1M4.6 11.4l1-1",
        "M17 20.5H9.5a3.5 3.5 0 0 1 0-7 4.6 4.6 0 0 1 8.7-1 3.9 3.9 0 0 1-1.2 8z",
    )

    /**
     * Service: the car's own state and the things that keep the app talking to it.
     *
     * Three faders, as the board draws them. The board fills each knob with the tile's own
     * background so it cuts the line it sits on; a tinted vector has no way to paint with the
     * surface behind it, so the knobs are drawn as outlines instead. It is the one place in this
     * file where the path is not the board's, and the reason is technical rather than a decision.
     */
    val Service: ImageVector = icon(
        "denza_service",
        inkLeft = 4.0f,
        "M4 7h16M4 12h16M4 17h16",
        "M17 7a2 2 0 1 1-4 0a2 2 0 1 1 4 0",
        "M10 12a2 2 0 1 1-4 0a2 2 0 1 1 4 0",
        "M19 17a2 2 0 1 1-4 0a2 2 0 1 1 4 0",
    )

    /** The mark on a line that explains rather than instructs. */
    val Note: ImageVector = icon(
        "denza_note",
        inkLeft = 3.0f,
        "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0",
        "M12 11v5.5",
        "M12 7.6h0.01",
    )

    /**
     * The way out of a settings panel.
     *
     * The one icon that is not hung on a left edge. It sits at the far right of a panel header with
     * nothing under it to line up with, so what matters is that it is centred in the box a finger
     * aims at - and shifting it left with the rest would have unbalanced exactly that.
     */
    val Close: ImageVector = centredIcon(
        "denza_close",
        "M6 6l12 12M18 6 6 18",
    )

    /**
     * One stroked vector on the board's 24-unit grid.
     *
     * The stroke is black and the caller tints it: [androidx.compose.material3.Icon] applies its
     * tint as a colour filter over the whole vector, so an icon here never names a colour and can
     * never drift from the palette.
     */
    /**
     * One icon, hung on the same left edge as every other.
     *
     * [inkLeft] is where this icon's paths actually begin inside the 24 grid, measured rather than
     * assumed - each set is translated so that becomes [ALIGNED_INK_LEFT], and the twelve of them
     * then start at one x instead of twelve.
     *
     * They did not. The paths span 2.0 (the cast and the eye) to 6.0 (the speaker), which at 30 dp
     * is five dp of scatter, and the first row of the dashboard ran 3.0, 2.0, 2.0, 3.0, 5.0, 2.4
     * while the words underneath all started at the same place. The owner picked it out on the car
     * without a ruler, which is what a misaligned column does.
     *
     * Reshaping the outliers was the other option and is the wrong one: a speaker is a tall narrow
     * box and a windscreen is a wide flat one, and their ink boxes differ because the drawings do.
     * What can be made the same is where they hang.
     */
    /** An icon that answers to its own box rather than to a column of text. */
    private fun centredIcon(name: String, vararg paths: String): ImageVector =
        icon(name, ALIGNED_INK_LEFT, *paths)

    private fun icon(name: String, inkLeft: Float, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = DenzaMetrics.Component.TILE_ICON,
            defaultHeight = DenzaMetrics.Component.TILE_ICON,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        )
        builder.addGroup(name = name, translationX = ALIGNED_INK_LEFT - inkLeft)
        paths.forEach { data ->
            builder.addPath(
                pathData = addPathNodes(data),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeInViewport(),
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        builder.clearGroup()
        return builder.build()
    }

    /**
     * The left edge every icon is hung on, in viewport units.
     *
     * Two, which is where the widest of them already began, so nothing had to be moved right and
     * nothing can be clipped. Half a stroke of 1.6 sits outside the path, so the ink itself lands
     * 1.2 units - 1.5 dp at 30 - from the box edge, near enough flush with the text below.
     */
    const val ALIGNED_INK_LEFT = 2f

    /**
     * The ladder's optical weight expressed in viewport units.
     *
     * [DenzaMetrics.Stroke.ICON_WEIGHT] is the thickness the eye is meant to see at the drawn size,
     * so the number the path needs depends on how far the 24-unit grid is being stretched. At 30 dp
     * that is 1.6, which is what the board writes.
     */
    private fun strokeInViewport(): Float =
        DenzaMetrics.Stroke.ICON_WEIGHT * VIEWPORT / DenzaMetrics.Component.TILE_ICON.value

    private const val VIEWPORT = 24f
}
