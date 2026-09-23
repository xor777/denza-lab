package dev.denza.apps.feature.trip

import android.graphics.Path
import dev.denza.apps.design.luminofor.LightPen
import dev.denza.apps.design.luminofor.LuminoforSpec.Head
import dev.denza.apps.design.luminofor.LuminoforSpec.HeadInk
import dev.denza.apps.design.luminofor.LuminoforSpec.Light

/**
 * The board's head-unit verbs on top of the [LightPen]: a caption, a reading, the variometer's
 * arrow, and the two text fittings the board never needed because its strings were chosen.
 *
 * `luminofor.js` draws every figure on both pages through one function, `reading()` - a caption,
 * the wide figures, the unit small on their baseline, and for the altitude an arrow and a rate - so
 * the sound page and the car page share it here rather than each keeping a copy. Nothing in a frame
 * allocates: the paths are held and reset, and a fitted or wrapped string is recomputed only when
 * its source or its room has changed.
 */
internal class StripInk(val pen: LightPen) {

    private val mark = Path()
    private val arrow = Path()

    /** The pen's own measurement, for [StripGeometry]. */
    val measure = StripGeometry.Measure { text, size, strong ->
        pen.textWidth(text, size, if (strong) LightPen.Face.SANS_STRONG else LightPen.Face.SANS)
    }

    /** The board's `lab`: Roboto 400 in white, at an intensity. Returns its width. */
    fun label(text: String, x: Float, baseline: Float, size: Float, intensity: Float = 1f): Float =
        pen.text(text, x, baseline, size, HeadInk.WHITE, intensity, LightPen.Face.SANS)

    /**
     * The board's `reading()`: the caption on [capY] - behind the blue mark when the reading names
     * a source - and on [valY] the figure, its unit and, for the altitude, the arrow and the rate.
     *
     * The unit takes the figure's colour at 0.9 and a size of 0.42 of it, 0.2 of it away; the
     * arrow and the rate are white whichever way the road goes. A [StripReading.hint] is drawn
     * fainter. A pack with no direction to name is white like any calm figure (`main-car-neutral`):
     * «Батарея» over it already says there is no direction, and the cluster's hero is ink there too.
     */
    fun reading(r: StripReading, x: Float, capY: Float, valY: Float, size: Float, lpx: Float) {
        if (!r.present) return
        if (r.dot) {
            mark.reset()
            mark.addCircle(x + MARK_X, capY - lpx * MARK_RISE, MARK_RADIUS, Path.Direction.CW)
            pen.glowFill(mark, HeadInk.BLUE, 1f, MARK_BLUR)
            label(r.caption, x + StripGeometry.MARK_INDENT, capY, lpx)
        } else {
            label(r.caption, x, capY, lpx, if (r.hint) HINT else 1f)
        }
        val figure = r.figure ?: return
        val light: Light = if (r.blue) HeadInk.BLUE else HeadInk.WHITE
        var ux = x + pen.figures(figure, x, valY, size, light, 1f)
        val unit = r.unit
        if (unit != null) {
            val gap = size * Head.Reading.UNIT_GAP_RATIO
            ux += gap + pen.text(
                unit, ux + gap, valY, StripGeometry.unitSize(size), light,
                Head.Reading.UNIT_ALPHA, LightPen.Face.SANS,
            )
        }
        val rate = r.rate ?: return
        val ax = ux + size * Head.Reading.RATE_GAP_RATIO
        variometer(ax, valY, size * ARROW_RISE, 5f, 5f, ARROW_STROKE, r.rateUp)
        pen.figures(
            rate, ax + StripGeometry.ARROW_ROOM, valY, StripGeometry.rateSize(size),
            HeadInk.WHITE, RATE_INTENSITY,
        )
    }

    /**
     * The variometer's arrow: a shaft [height] tall standing on [baseline], its head [half] wide
     * each side and [wing] deep - pointing up as the board draws it, or the same arrow turned over.
     */
    fun variometer(
        x: Float,
        baseline: Float,
        height: Float,
        half: Float,
        wing: Float,
        stroke: Float,
        up: Boolean,
    ) {
        val bottom = baseline - 1f
        val topY = baseline - height
        arrow.reset()
        if (up) {
            arrow.moveTo(x + half, bottom)
            arrow.lineTo(x + half, topY)
            arrow.moveTo(x, topY + wing)
            arrow.lineTo(x + half, topY)
            arrow.lineTo(x + 2f * half, topY + wing)
        } else {
            arrow.moveTo(x + half, topY)
            arrow.lineTo(x + half, bottom)
            arrow.moveTo(x, bottom - wing)
            arrow.lineTo(x + half, bottom)
            arrow.lineTo(x + 2f * half, bottom - wing)
        }
        pen.beam(arrow, stroke, HeadInk.WHITE, RATE_INTENSITY)
    }

    /**
     * A line of text cut to [room] with an ellipsis, remembered: the same text in the same room
     * comes back as the same string, so only a new title or a new room costs anything.
     */
    class Fitted {
        internal var source: String? = null
        internal var size = 0f
        internal var strong = false
        internal var room = Float.NaN
        internal var text: String = ""
    }

    fun fit(fitted: Fitted, text: String, size: Float, strong: Boolean, room: Float): String {
        if (fitted.source == text && fitted.size == size && fitted.strong == strong && fitted.room == room) {
            return fitted.text
        }
        fitted.source = text
        fitted.size = size
        fitted.strong = strong
        fitted.room = room
        fitted.text = ellipsize(text, size, strong, room)
        return fitted.text
    }

    private fun ellipsize(text: String, size: Float, strong: Boolean, room: Float): String {
        if (measure.sans(text, size, strong) <= room) return text
        // The longest head that still fits beside the ellipsis, found by halving: a title is a few
        // dozen characters and this runs once per track.
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (measure.sans(text.substring(0, mid).trimEnd() + ELLIPSIS, size, strong) <= room) {
                low = mid
            } else {
                high = mid - 1
            }
        }
        return if (low == 0) "" else text.substring(0, low).trimEnd() + ELLIPSIS
    }

    /**
     * A message broken into lines at its spaces, each no wider than [room], remembered like
     * [Fitted]. A word wider than the room on its own is cut with an ellipsis rather than overrun.
     */
    class Wrapped {
        internal var source: String? = null
        internal var size = 0f
        internal var room = Float.NaN
        internal val lines = ArrayList<String>(4)
    }

    fun wrap(wrapped: Wrapped, text: String, size: Float, room: Float): List<String> {
        if (wrapped.source == text && wrapped.size == size && wrapped.room == room) return wrapped.lines
        wrapped.source = text
        wrapped.size = size
        wrapped.room = room
        wrapped.lines.clear()
        var line = ""
        for (word in text.split(' ')) {
            if (word.isEmpty()) continue
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isEmpty() || measure.sans(candidate, size, false) <= room) {
                line = candidate
            } else {
                wrapped.lines += ellipsize(line, size, false, room)
                line = word
            }
        }
        if (line.isNotEmpty()) wrapped.lines += ellipsize(line, size, false, room)
        return wrapped.lines
    }

    companion object {
        /** The blue mark: a 3.4 dp dot 4 dp in and 0.35 of the caption above its baseline. */
        const val MARK_X = 4f
        const val MARK_RISE = 0.35f
        const val MARK_RADIUS = 3.4f
        const val MARK_BLUR = 6f

        /** The arrow: a shaft of 0.34 of its figure's size, a 1.8 dp stroke, and the rate at 0.9. */
        const val ARROW_RISE = 0.34f
        const val ARROW_STROKE = 1.8f
        const val RATE_INTENSITY = 0.9f

        /** A hint standing where a reading would be: the location's, at `reading()`'s 0.6. */
        const val HINT = 0.6f

        const val ELLIPSIS = "…"
    }
}
