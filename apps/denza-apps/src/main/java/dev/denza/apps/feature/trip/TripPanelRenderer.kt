package dev.denza.apps.feature.trip

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import dev.denza.apps.design.luminofor.LightPen
import dev.denza.apps.design.luminofor.LuminoforSpec
import dev.denza.apps.design.luminofor.LuminoforSpec.Head
import dev.denza.apps.design.luminofor.LuminoforSpec.HeadInk
import dev.denza.apps.feature.vehicle.VehicleTelemetry

/**
 * The strip under the dashboard, as the Luminofor board draws it: two pages in three widths.
 *
 * `tools/design-canvas/luminofor/luminofor.js`, `drawHead()`, less the tiles, the chips and the
 * pane's handle - the strip box and what is in it - ported line for line and drawn with the board's
 * own verbs ([LightPen]): additive light on black, the wide square figures, Roboto for words.
 *
 *  - **The sound page**: the track - a play mark and the artist on the caption line, the title in
 *    Roboto 500 under it - and the trip's readings, right-aligned on the full screen, in a row on
 *    the two-thirds pane, as rows on the one-third; under them the analyser ([SpectrumRenderer]),
 *    36 columns across the full screen, 24 and 12 in the panes.
 *  - **The car page** ([VehiclePageRenderer]): the pack's flow, its volts, five temperatures, the
 *    engine and the trip, and the last ten kilometres under them.
 *  - **Two dots** at the foot say there are two pages and which one this is.
 *
 * ### Window dp on the strip's own box
 *
 * Every position in `spec.json` is in the window's dp, and the strip's view is laid over the spec's
 * strip box ([box]). So the pen's origin is put on the box's corner and the spec's numbers are used
 * as they are - no scale, no virtual space. **Everything hangs from the box's top, except the
 * analyser's floor and the dots, which keep their distance from its bottom** (spec, `anchoring`):
 * a pane a few dp taller or shorter than the board's 680 moves the floor with the foot and leaves
 * the readings where they are. The right edge is the view's own, for the same reason.
 *
 * ### A model, not the sources
 *
 * The renderer draws a [StripModel] and nothing else ([drawModel]). The live strip fills one from
 * its sources every frame ([draw], through [StripReadings]); the debug build fills one from the
 * board's own fixtures - so a screenshot of the app and a board PNG are two drawings of one scene.
 */
class TripPanelRenderer {

    private val pen = LightPen(
        Typeface.DEFAULT,
        Typeface.create(Typeface.SANS_SERIF, LuminoforSpec.Type.HEAD_WEIGHT, false),
        Typeface.create(Typeface.SANS_SERIF, LuminoforSpec.Type.HEAD_STRONG, false),
    )
    private val ink = StripInk(pen)
    private val spectrum = SpectrumRenderer()
    private val car = VehiclePageRenderer()

    private val model = StripModel()
    private val readings = StripReadings()
    private val meter = SpectrumMeter()

    private val tripX = FloatArray(StripModel.TRIP_READINGS)
    private val play = Path()
    private val title = StripInk.Fitted()
    private val artist = StripInk.Fitted()
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * One live frame: the sources into the model, the model onto the canvas.
     *
     * Only the page on screen is read. The analyser is advanced only while its page is up, as it
     * always was; the car is read only on its own page, which is also the only time the view claims
     * the vehicle hub.
     *
     * @param density the window's pixels per dp, which is the pen's scale
     * @param dtSec seconds since the previous drawn frame, for the analyser's motion
     * @param showLocationHint the hint stands where the altitude would have
     * @param vehicle the vehicle hub's last snapshot, read by the car page and ignored by the other
     */
    internal fun draw(
        canvas: Canvas,
        w: Float,
        h: Float,
        density: Float,
        engine: TripEngine,
        spectrum: SpectrumSource,
        nowPlaying: NowPlayingSource,
        dtSec: Double,
        showLocationHint: Boolean,
        layout: TripPanelLayout,
        page: StripPage,
        vehicle: VehicleTelemetry,
    ) {
        when (page) {
            StripPage.SOUND -> {
                meter.advance(spectrum, dtSec)
                readings.sound(model, engine, nowPlaying, meter, showLocationHint)
            }
            StripPage.VEHICLE -> readings.car(model, vehicle)
        }
        drawModel(canvas, w, h, density, layout, page, model)
    }

    /**
     * A model onto a canvas the size of the strip box: [wPx] x [hPx] pixels at [density] pixels per
     * dp, in the composition [layout] asks for, on [page].
     *
     * The public way in for anything that is not the live strip - the debug build's fixtures, a
     * screenshot harness - and the one the live strip goes through as well.
     */
    fun drawModel(
        canvas: Canvas,
        wPx: Float,
        hPx: Float,
        density: Float,
        layout: TripPanelLayout,
        page: StripPage,
        model: StripModel,
    ) {
        if (wPx <= 0f || hPx <= 0f || density <= 0f) return
        val box = box(layout)
        pen.begin(canvas, density, originX = box.left, originY = box.top)
        val left = box.left
        val right = box.left + wPx / density
        // How far the view's foot stands from the box's: the floor and the dots move with it.
        val foot = box.top + hPx / density - box.bottom
        when (page) {
            StripPage.SOUND -> sound(layout, model, left, right, foot)
            StripPage.VEHICLE -> car.draw(ink, layout, model, left, right)
        }
        dots(page, (left + right) / 2f, dotsY(layout) + foot)
    }

    // ------------------------------------------------------------------------------ sound page

    private fun sound(layout: TripPanelLayout, model: StripModel, left: Float, right: Float, foot: Float) {
        when (layout) {
            TripPanelLayout.WIDE -> {
                val s = Head.Full.Strip
                val rowLeft = StripGeometry.fullTrip(model, right, ink.measure, tripX)
                val room = StripGeometry.fullTitleRoom(left, right, rowLeft, model.tripCount > 0)
                track(model, left, s.CAPTION, s.VALUE, s.TITLE_SIZE, s.LABEL_SIZE, room)
                for (k in 0 until model.tripCount) {
                    ink.reading(model.trip[k], tripX[k], s.CAPTION, s.VALUE, s.VALUE_SIZE, s.LABEL_SIZE)
                }
                spectrum.draw(pen, left, s.SPECTRUM_TOP, right - left, s.FLOOR + foot, s.BARS, model.levels, model.crowns)
            }
            TripPanelLayout.MEDIUM -> {
                val s = Head.Two.Sound
                track(model, left, s.TRACK_CAPTION, s.TRACK_VALUE, s.TITLE_SIZE, s.LABEL_SIZE, right - left)
                StripGeometry.twoTrip(model, left, ink.measure, tripX)
                for (k in 0 until model.tripCount) {
                    ink.reading(model.trip[k], tripX[k], s.CAPTION, s.VALUE, s.VALUE_SIZE, s.LABEL_SIZE)
                }
                spectrum.draw(pen, left, s.SPECTRUM_TOP, right - left, s.FLOOR + foot, s.BARS, model.levels, model.crowns)
            }
            TripPanelLayout.NARROW -> {
                val s = Head.One.Sound
                track(model, left, s.TRACK_CAPTION, s.TRACK_VALUE, s.TITLE_SIZE, s.LABEL_SIZE, right - left)
                for (k in 0 until model.tripCount) row(model.trip[k], left, s.ROWS_TOP + k * s.ROW_PITCH)
                spectrum.draw(pen, left, s.SPECTRUM_TOP, right - left, s.FLOOR + foot, s.BARS, model.levels, model.crowns)
            }
        }
    }

    /**
     * The board's `trackBlock`: a play mark and the artist on the caption line, the title in Roboto
     * 500 on the value line. Both are cut to [room] with an ellipsis - on the full screen that is
     * the room the readings leave, so a long title stops a reading gap short of them.
     *
     * Nothing playing, no block. A paused track is the same block at [PAUSED]: the ticker it
     * replaces dimmed the same way, and the board has no paused scene to say otherwise.
     */
    private fun track(
        model: StripModel,
        x: Float,
        capY: Float,
        valY: Float,
        tpx: Float,
        lpx: Float,
        room: Float,
    ) {
        val name = model.title ?: return
        val intensity = if (model.playing) 1f else PAUSED
        play.reset()
        play.moveTo(x + 1f, capY - lpx * PLAY_TOP)
        play.lineTo(x + lpx * PLAY_TIP, capY - lpx * PLAY_MIDDLE)
        play.lineTo(x + 1f, capY - 1f)
        play.close()
        pen.beam(play, PLAY_STROKE, HeadInk.WHITE, PLAY_INTENSITY * intensity)
        val artistX = x + lpx * ARTIST_INDENT
        val by = ink.fit(artist, model.artist, lpx, false, room - lpx * ARTIST_INDENT)
        pen.text(by, artistX, capY, lpx, HeadInk.WHITE, intensity, LightPen.Face.SANS)
        val cut = ink.fit(title, name, tpx, true, room)
        pen.text(cut, x, valY, tpx, HeadInk.WHITE, intensity, LightPen.Face.SANS_STRONG)
    }

    /**
     * One row of the one-third pane: the caption at the left in white at 0.85, the figure at the
     * row's value column, its unit six dp after it, and the altitude's arrow and rate a fixed 36
     * past the figure - the board's own small arithmetic for a 30 dp figure.
     */
    private fun row(r: StripReading, left: Float, y: Float) {
        val s = Head.One.Sound
        ink.label(r.caption, left, y, s.LABEL_SIZE, if (r.hint) StripInk.HINT else ROW_LABEL)
        val figure = r.figure ?: return
        val vx = left + s.VALUE_X
        val ux = vx + pen.figures(figure, vx, y, s.VALUE_SIZE, HeadInk.WHITE, 1f)
        r.unit?.let {
            pen.text(
                it, ux + StripGeometry.ROW_UNIT_GAP, y, StripGeometry.ROW_SMALL, HeadInk.WHITE,
                Head.Reading.UNIT_ALPHA, LightPen.Face.SANS,
            )
        }
        val rate = r.rate ?: return
        val ax = ux + StripGeometry.ROW_RATE_OFFSET
        ink.variometer(ax, y, ROW_ARROW_HEIGHT, ROW_ARROW_HALF, ROW_ARROW_HALF, ROW_ARROW_STROKE, r.rateUp)
        pen.figures(
            rate, ax + StripGeometry.ROW_ARROW_ROOM, y, StripGeometry.ROW_SMALL, HeadInk.WHITE,
            StripInk.RATE_INTENSITY,
        )
    }

    // ------------------------------------------------------------------------------------ dots

    /**
     * Two dots, and the whole of the affordance: white for the page that is up, white at 0.28 for
     * the other, fourteen dp apart. Painted over rather than added, as the board paints them.
     */
    private fun dots(page: StripPage, centre: Float, y: Float) {
        val canvas = pen.canvas
        val pages = StripPage.entries
        for (index in 0 until pages.size) {
            dot.color = if (pages[index] == page) HeadInk.DOT_ON else HeadInk.DOT_OFF
            canvas.drawCircle(
                pen.x(centre - DOT_HALF_PITCH + index * 2f * DOT_HALF_PITCH),
                pen.y(y),
                pen.px(DOT_RADIUS),
                dot,
            )
        }
    }

    companion object {
        /** Where the strip stands in the window, which is where its view is laid. */
        fun box(layout: TripPanelLayout): Head.Box = when (layout) {
            TripPanelLayout.WIDE -> Head.Full.STRIP_BOX
            TripPanelLayout.MEDIUM -> Head.Two.STRIP_BOX
            TripPanelLayout.NARROW -> Head.One.STRIP_BOX
        }

        /** Where the dots stand in the board's window, before the view's foot moves them. */
        fun dotsY(layout: TripPanelLayout): Float = when (layout) {
            TripPanelLayout.WIDE -> Head.Full.Strip.DOTS_Y
            TripPanelLayout.MEDIUM -> Head.Two.DOTS_Y
            TripPanelLayout.NARROW -> Head.One.DOTS_Y
        }

        /** The board's dots: radius three, fourteen apart. */
        const val DOT_RADIUS = 3f
        const val DOT_HALF_PITCH = 7f

        /** The play mark, in fractions of the caption's size, and its stroke and light. */
        const val PLAY_TOP = 0.72f
        const val PLAY_TIP = 0.6f
        const val PLAY_MIDDLE = 0.38f
        const val PLAY_STROKE = 1.2f
        const val PLAY_INTENSITY = 0.9f
        const val ARTIST_INDENT = 0.95f

        /** A paused track, which the board does not draw. */
        const val PAUSED = 0.5f

        /** The one-third rows: the caption at 0.85, the arrow a 12 dp shaft with 4 dp wings. */
        const val ROW_LABEL = 0.85f
        const val ROW_ARROW_HEIGHT = 12f
        const val ROW_ARROW_HALF = 4f
        const val ROW_ARROW_STROKE = 1.5f
    }
}
