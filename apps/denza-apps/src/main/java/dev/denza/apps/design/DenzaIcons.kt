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
        "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0",
        "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0",
        "M3.2 10.5 L9.1 10.9 M20.8 10.5 L14.9 10.9 M12 15 L12 21",
    )

    /** Projection onto the other screens. */
    val Simulcast: ImageVector = icon(
        "denza_simulcast",
        "M2 20h0.01",
        "M2 16a4 4 0 0 1 4 4",
        "M2 12a8 8 0 0 1 8 8",
        "M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6",
    )

    /** The turn-indicator cameras: what the car is watching, not what it is recording. */
    val Mirrors: ImageVector = icon(
        "denza_mirrors",
        "M2 12s3.5-6.5 10-6.5S22 12 22 12s-3.5 6.5-10 6.5S2 12 2 12z",
        "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0",
    )

    /** One surface cut in two. */
    val Split: ImageVector = icon(
        "denza_split",
        "M5 4.5 H19 A2 2 0 0 1 21 6.5 V17.5 A2 2 0 0 1 19 19.5 H5 A2 2 0 0 1 3 17.5 V6.5 " +
            "A2 2 0 0 1 5 4.5 Z",
        "M12 4.5v15",
    )

    /** The head-up display: a windscreen with something projected on it, not a map. */
    val Hud: ImageVector = icon(
        "denza_hud",
        "M6 13.5l6-6 6 6",
        "M5 19h14",
    )

    /** The motorised speaker cover, matching the dashboard board. */
    val Speaker: ImageVector = icon(
        "denza_speaker",
        "M8 3 H16 A2 2 0 0 1 18 5 V19 A2 2 0 0 1 16 21 H8 A2 2 0 0 1 6 19 V5 " +
            "A2 2 0 0 1 8 3 Z",
        "M15.2 14a3.2 3.2 0 1 1-6.4 0a3.2 3.2 0 1 1 6.4 0",
        "M13.2 7a1.2 1.2 0 1 1-2.4 0a1.2 1.2 0 1 1 2.4 0",
    )

    /** An application sent to the passenger's screen. */
    val Passenger: ImageVector = icon(
        "denza_passenger",
        "M7 2.5 H17 A2 2 0 0 1 19 4.5 V19.5 A2 2 0 0 1 17 21.5 H7 A2 2 0 0 1 5 19.5 V4.5 " +
            "A2 2 0 0 1 7 2.5 Z",
        "M12 6.5v7",
        "M9 10.5l3 3 3-3",
        "M9 17.5h6",
    )

    /** The button on the wheel, which the car itself marks with a star. */
    val SteeringWheelButton: ImageVector = icon(
        "denza_steering_button",
        "M12 3.5l2.6 5.5 5.9 0.8-4.3 4.2 1 5.9-5.2-2.8-5.2 2.8 1-5.9L3.5 9.8l5.9-0.8z",
    )

    /** Russian in the car's own settings. */
    val Locale: ImageVector = icon(
        "denza_locale",
        "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0",
        "M3.4 9.5h17.2M3.4 14.5h17.2",
        "M12 3a15 15 0 0 0 0 18 15 15 0 0 0 0-18z",
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
        "M4 7h16M4 12h16M4 17h16",
        "M17 7a2 2 0 1 1-4 0a2 2 0 1 1 4 0",
        "M10 12a2 2 0 1 1-4 0a2 2 0 1 1 4 0",
        "M19 17a2 2 0 1 1-4 0a2 2 0 1 1 4 0",
    )

    /**
     * One stroked vector on the board's 24-unit grid.
     *
     * The stroke is black and the caller tints it: [androidx.compose.material3.Icon] applies its
     * tint as a colour filter over the whole vector, so an icon here never names a colour and can
     * never drift from the palette.
     */
    private fun icon(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = DenzaMetrics.Component.TILE_ICON,
            defaultHeight = DenzaMetrics.Component.TILE_ICON,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        )
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
        return builder.build()
    }

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
