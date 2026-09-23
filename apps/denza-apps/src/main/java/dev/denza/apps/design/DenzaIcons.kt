package dev.denza.apps.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.core.graphics.PathParser

/**
 * The icons the design boards draw, drawn.
 *
 * The first cut of the tile grid reached into `Icons.Outlined` for whatever came closest by name,
 * and the screen ended up wearing a speedometer where the board draws a steering wheel, a video
 * camera where it draws an eye, and a folded map where it draws a windscreen. Only two of six
 * matched. Material's set is also mixed weight - some of it filled, some of it stroked at its own
 * thickness - so the row read as six icons borrowed from six places, which is what it was.
 *
 * These are the board's own paths, lifted from `Main.dc.html` and `Config.dc.html` unchanged and
 * carried by Luminofor's `fixtures.js` as the same eleven. Each is kept as its sources - a
 * [DenzaGlyph] - because the dashboard and the panels draw them two different ways. A panel wears
 * the [ImageVector], tinted like any other icon. A tile or a chip draws the paths through the
 * board's own beam, lit and haloed and added onto the plate (see
 * [dev.denza.apps.ui.components.DenzaTile]), and a vector cannot be drawn that way: its tint is a
 * colour filter over the whole picture, not a light.
 *
 * Every one is stroked at [DenzaMetrics.Stroke.ICON] viewport units - Luminofor's 1.5 on the
 * 24-unit grid, whatever size it is drawn at. Add an icon here only after the board has one, and
 * copy its path rather than approximating it.
 */
object DenzaIcons {

    /** The driver's own screen. A steering wheel, because what goes there is not always a map. */
    val ClusterGlyph = DenzaGlyph(
        "denza_cluster",
        inkLeft = 3.0f,
        "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0",
        "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0",
        "M3.2 10.5 L9.1 10.9 M20.8 10.5 L14.9 10.9 M12 15 L12 21",
    )

    /** Projection onto the other screens. */
    val SimulcastGlyph = DenzaGlyph(
        "denza_simulcast",
        inkLeft = 2.0f,
        "M2 20h0.01",
        "M2 16a4 4 0 0 1 4 4",
        "M2 12a8 8 0 0 1 8 8",
        "M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6",
    )

    /** The turn-indicator cameras: what the car is watching, not what it is recording. */
    val MirrorsGlyph = DenzaGlyph(
        "denza_mirrors",
        inkLeft = 2.0f,
        "M2 12s3.5-6.5 10-6.5S22 12 22 12s-3.5 6.5-10 6.5S2 12 2 12z",
        "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0",
    )

    /** One surface cut in two. */
    val SplitGlyph = DenzaGlyph(
        "denza_split",
        inkLeft = 3.0f,
        "M5 4.5 H19 A2 2 0 0 1 21 6.5 V17.5 A2 2 0 0 1 19 19.5 H5 A2 2 0 0 1 3 17.5 V6.5 " +
            "A2 2 0 0 1 5 4.5 Z",
        "M12 4.5v15",
    )

    /** The head-up display: a windscreen with something projected on it, not a map. */
    val HudGlyph = DenzaGlyph(
        "denza_hud",
        inkLeft = 5.0f,
        "M6 13.5l6-6 6 6",
        "M5 19h14",
    )

    /** The motorised speaker cover, matching the dashboard board. */
    val SpeakerGlyph = DenzaGlyph(
        "denza_speaker",
        inkLeft = 6.0f,
        "M8 3 H16 A2 2 0 0 1 18 5 V19 A2 2 0 0 1 16 21 H8 A2 2 0 0 1 6 19 V5 " +
            "A2 2 0 0 1 8 3 Z",
        "M15.2 14a3.2 3.2 0 1 1-6.4 0a3.2 3.2 0 1 1 6.4 0",
        "M13.2 7a1.2 1.2 0 1 1-2.4 0a1.2 1.2 0 1 1 2.4 0",
    )

    /** An application sent to the passenger's screen. */
    val PassengerGlyph = DenzaGlyph(
        "denza_passenger",
        inkLeft = 5.0f,
        "M7 2.5 H17 A2 2 0 0 1 19 4.5 V19.5 A2 2 0 0 1 17 21.5 H7 A2 2 0 0 1 5 19.5 V4.5 " +
            "A2 2 0 0 1 7 2.5 Z",
        "M12 6.5v7",
        "M9 10.5l3 3 3-3",
        "M9 17.5h6",
    )

    /**
     * The three application roles and the selected launch target, as DefaultApps.dc.html draws it.
     *
     * The one glyph the boards do not hang where this file does. `Main.dc.html` drew it on an
     * unshifted viewBox and Luminofor's `fixtures.js` copied that, so on the board its ink starts
     * at 3 where the other ten start at 2 - 1.25 dp further right at 30. This file keeps the rule
     * the owner asked for on the car, one left edge for every glyph; the board is the record that
     * has to move.
     */
    val ApplicationsGlyph = DenzaGlyph(
        "denza_applications",
        inkLeft = 3.0f,
        "M5 3H8A2 2 0 0 1 10 5V8A2 2 0 0 1 8 10H5A2 2 0 0 1 3 8V5A2 2 0 0 1 5 3Z",
        "M16 3H19A2 2 0 0 1 21 5V8A2 2 0 0 1 19 10H16A2 2 0 0 1 14 8V5A2 2 0 0 1 16 3Z",
        "M5 14H8A2 2 0 0 1 10 16V19A2 2 0 0 1 8 21H5A2 2 0 0 1 3 19V16A2 2 0 0 1 5 14Z",
        "M14.5 17.5l2 2 4-5",
    )

    /** The language the whole car speaks. */
    val LocaleGlyph = DenzaGlyph(
        "denza_locale",
        inkLeft = 3.0f,
        "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0",
        "M3.4 9.5h17.2M3.4 14.5h17.2",
        "M12 3a15 15 0 0 0 0 18 15 15 0 0 0 0-18z",
    )

    /** Weather: the sun the app supplies, half behind the cloud that is the car's own widget. */
    val WeatherGlyph = DenzaGlyph(
        "denza_weather",
        inkLeft = 2.4f,
        "M8 4.8a3.2 3.2 0 1 1 0 6.4a3.2 3.2 0 1 1 0-6.4",
        "M8 2.4v1.4M8 12.2v1.4M2.4 8h1.4M12.2 8h1.4M4.6 4.6l1 1M11.4 4.6l-1 1M4.6 11.4l1-1",
        "M17 20.5H9.5a3.5 3.5 0 0 1 0-7 4.6 4.6 0 0 1 8.7-1 3.9 3.9 0 0 1-1.2 8z",
    )

    /**
     * The cloud link: the car's report going up to the cloud the phone reads it from.
     *
     * A cloud open underneath with an arrow rising through the gap - not the weather's cloud, which
     * is closed and has the sun behind it, so the two tiles in one row cannot be taken for each
     * other. Its ink starts at 2, like the board's.
     */
    val CloudGlyph = DenzaGlyph(
        "denza_cloud",
        inkLeft = 2.0f,
        "M4 14.9A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.24",
        "M12 13v8",
        "M8 17l4-4 4 4",
    )

    /**
     * Service: the car's own state and the things that keep the app talking to it.
     *
     * Three faders, as the board draws them: three lines and a knob on each. On a tile the knob
     * cuts the line it sits on - the board fills a disc 1.1 units wider than the knob with the
     * plate's own colour before it strokes the knob - and the tile does exactly that. A tinted
     * vector has no way to paint with the surface behind it, so in a panel the knobs are outlines
     * over the line instead; that is the one place a glyph here is not drawn as the board draws it,
     * and the reason is technical rather than a decision.
     */
    val ServiceGlyph = DenzaGlyph(
        "denza_service",
        inkLeft = 4.0f,
        strokes = listOf("M4 7h16M4 12h16M4 17h16"),
        knobs = listOf(
            DenzaGlyph.Knob(15f, 7f, 2f),
            DenzaGlyph.Knob(8f, 12f, 2f),
            DenzaGlyph.Knob(17f, 17f, 2f),
        ),
    )

    /** The mark on a line that explains rather than instructs. */
    val NoteGlyph = DenzaGlyph(
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
    val CloseGlyph = DenzaGlyph.centred(
        "denza_close",
        "M6 6l12 12M18 6 6 18",
    )

    /**
     * The way back from a page to the panel it was opened from.
     *
     * It shares the header's leading slot with a tile's own icon and never appears beside it: a
     * chooser opened from a row is not the panel for a tile, so there is no tile to repeat. Drawn
     * on the same centred box as [Close], because the pair sits at the two ends of one row and a
     * glyph hung on a text column at one end would read as the lower of the two.
     */
    val BackGlyph = DenzaGlyph.centred(
        "denza_back",
        "M15 6l-6 6 6 6",
    )

    /**
     * The chevron on a row that opens a page - [Back] the other way round, which is the point.
     *
     * A row whose value line is icons and a fragment says nothing about being touchable; this is
     * the whole of what says so, and it has to be the same shape the way out will be.
     */
    val ForwardGlyph = DenzaGlyph.centred(
        "denza_forward",
        "M9 6l6 6-6 6",
    )

    val Cluster: ImageVector get() = ClusterGlyph.vector
    val Applications: ImageVector get() = ApplicationsGlyph.vector
    val Service: ImageVector get() = ServiceGlyph.vector
    val Note: ImageVector get() = NoteGlyph.vector
    val Close: ImageVector get() = CloseGlyph.vector
    val Back: ImageVector get() = BackGlyph.vector
    val Forward: ImageVector get() = ForwardGlyph.vector

    /**
     * The left edge every icon is hung on, in viewport units.
     *
     * Two, which is where the widest of them already began, so nothing had to be moved right and
     * nothing can be clipped. Half a stroke of 1.5 sits outside the path, so the ink itself lands
     * 1.25 units - 1.6 dp at 30 - from the box edge, near enough flush with the text below.
     */
    const val ALIGNED_INK_LEFT = 2f

    /** The grid every glyph is drawn on. */
    const val VIEWPORT = 24f
}

/**
 * One glyph on the board's 24-unit grid, kept as its sources.
 *
 * [inkLeft] is where this glyph's paths actually begin inside the grid, measured rather than
 * assumed, and [shift] is what moves that onto [DenzaIcons.ALIGNED_INK_LEFT] - so the eleven on the
 * dashboard start at one x instead of eleven.
 *
 * They did not. The paths span 2.0 (the cast and the eye) to 6.0 (the speaker), which at 30 dp is
 * five dp of scatter, and the first row of the dashboard ran 3.0, 2.0, 2.0, 3.0, 5.0, 2.4 while the
 * words underneath all started at the same place. The owner picked it out on the car without a
 * ruler, which is what a misaligned column does. Reshaping the outliers was the other option and is
 * the wrong one: a speaker is a tall narrow box and a windscreen is a wide flat one, and their ink
 * boxes differ because the drawings do. What can be made the same is where they hang - and the
 * boards hang them there too, as a shifted viewBox on the old boards and as shifted coordinates in
 * Luminofor's fixtures.
 *
 * [strokes] and [knobs] are in the glyph's own coordinates, before the shift.
 */
class DenzaGlyph(
    val name: String,
    val inkLeft: Float,
    val strokes: List<String>,
    val knobs: List<Knob> = emptyList(),
) {

    constructor(name: String, inkLeft: Float, vararg strokes: String) :
        this(name, inkLeft, strokes.toList())

    /** A fader's knob: a disc that cuts the line it sits on. */
    class Knob(val cx: Float, val cy: Float, val r: Float) {
        /** The knob as a stroked circle, for a drawing that cannot mask. */
        fun outline(): String = "M${cx + r} ${cy}a$r $r 0 1 1 ${-2 * r} 0a$r $r 0 1 1 ${2 * r} 0"
    }

    /** How far the glyph moves right (negative: left) to hang on the shared edge. */
    val shift: Float get() = DenzaIcons.ALIGNED_INK_LEFT - inkLeft

    /**
     * The glyph for a panel: a stroked vector, black, tinted by whoever draws it.
     *
     * [androidx.compose.material3.Icon] applies its tint as a colour filter over the whole vector,
     * so an icon here never names a colour and can never drift from the palette. The knobs are
     * outlines here, for the reason [DenzaIcons.ServiceGlyph] gives.
     */
    val vector: ImageVector by lazy {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = DenzaMetrics.Component.TILE_ICON,
            defaultHeight = DenzaMetrics.Component.TILE_ICON,
            viewportWidth = DenzaIcons.VIEWPORT,
            viewportHeight = DenzaIcons.VIEWPORT,
        )
        builder.addGroup(name = name, translationX = shift)
        (strokes + knobs.map(Knob::outline)).forEach { data ->
            builder.addPath(
                pathData = addPathNodes(data),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = DenzaMetrics.Stroke.ICON,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        builder.clearGroup()
        builder.build()
    }

    /**
     * Every stroke as one path, already shifted, in grid units.
     *
     * One path and not one per stroke, because the board strokes them as one: where two strokes
     * cross - the wheel's spokes on its rim, the split's line on its frame - a single stroke covers
     * the crossing once, and two additive strokes would light it twice.
     */
    val strokePath: android.graphics.Path by lazy {
        val out = android.graphics.Path()
        strokes.forEach { out.addPath(PathParser.createPathFromPathData(it)) }
        out.offset(shift, 0f)
        out
    }

    companion object {
        /** A glyph that answers to its own box rather than to a column of text. */
        fun centred(name: String, vararg strokes: String): DenzaGlyph =
            DenzaGlyph(name, DenzaIcons.ALIGNED_INK_LEFT, strokes.toList())
    }
}
