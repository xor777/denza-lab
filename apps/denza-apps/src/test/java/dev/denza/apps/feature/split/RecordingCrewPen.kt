package dev.denza.apps.feature.split

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A [SplitCrewScene.Pen] that keeps what a frame drew instead of drawing it: every stroke with its
 * subpaths as polylines (arcs flattened), and every other verb as one line of [ops], in order.
 *
 * It follows the canvas's path rules as the view's pen does - an arc joins the current point, a
 * rectangle is its own closed subpath and leaves the current point at its corner, a line to the
 * current point adds nothing - so a recorded frame has the geometry the screen shows. [actor] is a
 * label the test sets before it asks the scene for one part of the frame.
 */
internal class RecordingCrewPen : SplitCrewScene.Pen {

    class Stroke(
        val actor: String,
        val alpha: Double,
        val width: Double,
        val ink: SplitCrewScene.Ink,
        val polylines: List<List<Pt>>,
    ) {
        val segments: List<Seg>
            get() = polylines.flatMap { line -> line.zipWithNext { a, b -> Seg(a, b) } }

        val points: List<Pt> get() = polylines.flatten()

        /** The stroked area's box: every point widened by half the line, as round caps reach. */
        val box: Box
            get() {
                val pts = points
                val h = width / 2
                return Box(pts.minOf { it.x } - h, pts.minOf { it.y } - h, pts.maxOf { it.x } + h, pts.maxOf { it.y } + h)
            }
    }

    data class Pt(val x: Double, val y: Double)

    data class Seg(val a: Pt, val b: Pt)

    data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        fun intersects(o: Box) = left <= o.right && o.left <= right && top <= o.bottom && o.top <= bottom
    }

    var actor = ""
    val strokes = mutableListOf<Stroke>()
    val ops = mutableListOf<String>()

    private val lines = mutableListOf<MutableList<Pt>>()
    private var current: MutableList<Pt>? = null

    override fun ground() {
        ops += "ground"
    }

    override fun haze(alpha: Double) {
        ops += "haze $alpha"
    }

    override fun beginPath() {
        lines.clear()
        current = null
    }

    override fun moveTo(x: Double, y: Double) {
        current = mutableListOf(Pt(x, y)).also { lines += it }
    }

    override fun lineTo(x: Double, y: Double) {
        val line = current ?: return moveTo(x, y)
        val p = Pt(x, y)
        if (line.last() != p) line += p
    }

    override fun rect(x: Double, y: Double, w: Double, h: Double) {
        lines += mutableListOf(Pt(x, y), Pt(x + w, y), Pt(x + w, y + h), Pt(x, y + h), Pt(x, y))
        moveTo(x, y)
    }

    override fun arc(cx: Double, cy: Double, r: Double, start: Double, end: Double) {
        var sweep = end - start
        if (sweep < 2 * PI) {
            sweep %= 2 * PI
            if (sweep < 0) sweep += 2 * PI
        } else {
            sweep = 2 * PI
        }
        val n = max(8, ceil(sweep / (2 * PI) * 96).toInt())
        for (i in 0..n) {
            val a = start + sweep * i / n
            lineTo(cx + r * cos(a), cy + r * sin(a))
        }
    }

    override fun stroke(alpha: Double, width: Double, ink: SplitCrewScene.Ink) {
        // Only what the canvas would draw: subpaths with at least one line in them.
        val drawn = lines.filter { it.size > 1 }.map { it.toList() }
        strokes += Stroke(actor, alpha, width, ink, drawn)
        ops += "stroke $actor"
    }

    override fun save() {
        ops += "save"
    }

    override fun clipCircle(cx: Double, cy: Double, r: Double) {
        ops += "clip $cx $cy $r"
    }

    override fun restore() {
        ops += "restore"
    }

    override fun caption(text: String, x: Double, y: Double, size: Double, alpha: Double) {
        ops += "caption $text $x $y $size $alpha"
    }

    companion object {
        /** The shortest distance between two segments. */
        fun distance(s: Seg, t: Seg): Double {
            if (crosses(s, t)) return 0.0
            return min(min(toSegment(s.a, t), toSegment(s.b, t)), min(toSegment(t.a, s), toSegment(t.b, s)))
        }

        private fun toSegment(p: Pt, s: Seg): Double {
            val dx = s.b.x - s.a.x
            val dy = s.b.y - s.a.y
            val len = dx * dx + dy * dy
            val k = if (len == 0.0) 0.0 else (((p.x - s.a.x) * dx + (p.y - s.a.y) * dy) / len).coerceIn(0.0, 1.0)
            return hypot(p.x - (s.a.x + k * dx), p.y - (s.a.y + k * dy))
        }

        private fun crosses(s: Seg, t: Seg): Boolean {
            fun side(a: Pt, b: Pt, c: Pt) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
            val d1 = side(t.a, t.b, s.a)
            val d2 = side(t.a, t.b, s.b)
            val d3 = side(s.a, s.b, t.a)
            val d4 = side(s.a, s.b, t.b)
            return d1 * d2 < 0 && d3 * d4 < 0 && abs(d1 - d2) > 0 && abs(d3 - d4) > 0
        }
    }
}
