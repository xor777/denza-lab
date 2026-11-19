package dev.denza.apps.design.luminofor

import android.graphics.Path
import android.graphics.RectF
import dev.denza.apps.design.luminofor.LuminoforSpec.ClusterInk
import dev.denza.apps.design.luminofor.LuminoforSpec.Light
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The five temperature glyphs - the pack, the three motors seen from above, the front drive's
 * inverter - as the Luminofor board draws them. On the cluster they are the temperatures' captions;
 * on the car page they are the same, so one glyph means one cell on both screens.
 *
 * The outline is always the calm grey; what carries the reading's colour is the lit part (the
 * pack's cell, a motor's wheels and axle, the inverter's wave). A cell out of line lights its part
 * with a blur; a calm one draws it flat.
 */
object ThermalGlyphs {

    /** The readings' order, which is the data's: pack, front motor, rear left, rear right, inverter. */
    enum class Cell { PACK, FRONT, REAR_LEFT, REAR_RIGHT, INVERTER }

    /**
     * The row's order, left to right (`spec.json` → `cluster.glyph.row`): the pack, the front drive -
     * its motor, then the inverter that feeds it - and the rear motors left and right. A renderer
     * draws slot `i` as `ROW[i]` over the reading at `ROW[i].ordinal`, so a value cannot leave its
     * glyph however the row is ordered.
     */
    val ROW: List<Cell> = listOf(Cell.PACK, Cell.FRONT, Cell.INVERTER, Cell.REAR_LEFT, Cell.REAR_RIGHT)

    private val outline = Path()
    private val lit = Path()
    private val extra = Path()
    private val rect = RectF()

    fun draw(pen: LightPen, cell: Cell, x: Float, base: Float, light: Light, level: Float, hot: Boolean) {
        val g = LuminoforSpec.Cluster.Glyph
        val blur = if (hot) 6f else 0.01f
        outline.reset(); lit.reset(); extra.reset()
        when (cell) {
            Cell.PACK -> {
                round(outline, x, base - 17f, 20f, 13f, 2.6f)
                outline.moveTo(x + 22.5f, base - 13f)
                outline.lineTo(x + 22.5f, base - 8f)
                round(lit, x + 3f, base - 14f, 9f + 5f * min(1f, level), 7f, 1f)
                pen.beam(outline, g.OUTLINE, ClusterInk.GREY, 0.9f)
                pen.glowFill(lit, light, level, blur)
            }
            Cell.INVERTER -> inverter(pen, x, base, light, level, hot)
            else -> motor(pen, cell, x, base, light, level, blur)
        }
    }

    /**
     * The converter's own symbol: a box cut by its diagonal, direct current «=» in the upper
     * triangle, alternating «~» out in the lower one and lit. The wave starts downward, so its crest
     * stands where the triangle is tall.
     */
    private fun inverter(pen: LightPen, x: Float, base: Float, light: Light, level: Float, hot: Boolean) {
        val i = LuminoforSpec.Cluster.Glyph.Inverter
        val b = i.BOX
        round(outline, x, base - b, b, b, i.RADIUS)
        outline.moveTo(x + i.INSET, base - i.INSET)
        outline.lineTo(x + b - i.INSET, base - b + i.INSET)
        for (h in i.DC) {
            outline.moveTo(x + i.DC_FROM, base - h)
            outline.lineTo(x + i.DC_TO, base - h)
        }
        extra.moveTo(x + i.WAVE_FROM, base - i.WAVE_MID)
        for (step in 1..WAVE_STEPS) {
            val u = step / WAVE_STEPS.toFloat()
            extra.lineTo(
                x + i.WAVE_FROM + u * (i.WAVE_TO - i.WAVE_FROM),
                base - i.WAVE_MID + sin(u * PI * 2).toFloat() * i.WAVE_AMP,
            )
        }
        pen.beam(outline, LuminoforSpec.Cluster.Glyph.OUTLINE, ClusterInk.GREY, 0.9f)
        pen.beam(extra, i.WAVE_STROKE, light, level, if (hot) 1f else 0f)
    }

    /**
     * A motor is the car from above with its drive lit where it is: both front wheels and the axle
     * between them for the front motor, one rear wheel and its half-axle for a rear motor. The light
     * stands outside the body, on the side it names, so left and right part at a glance.
     */
    private fun motor(pen: LightPen, cell: Cell, x: Float, base: Float, light: Light, level: Float, blur: Float) {
        val g = LuminoforSpec.Cluster.Glyph
        val bx = x + 4f
        val bw = 13f
        val top = base - 23f
        val bh = 22f
        round(outline, bx, top, bw, bh, 4f)
        val left = bx - WHEEL_W - WHEEL_GAP
        val right = bx + bw + WHEEL_GAP
        val front = top + 2.5f
        val rear = top + bh - 9f
        wheel(if (cell == Cell.FRONT) lit else extra, left, front)
        wheel(if (cell == Cell.FRONT) lit else extra, right, front)
        wheel(if (cell == Cell.REAR_LEFT) lit else extra, left, rear)
        wheel(if (cell == Cell.REAR_RIGHT) lit else extra, right, rear)
        val axle = WHEEL_L / 2f - AXLE / 2f
        when (cell) {
            Cell.FRONT -> round(lit, left + WHEEL_W, front + axle, right - left - WHEEL_W, AXLE, 1f)
            Cell.REAR_LEFT -> round(lit, left + WHEEL_W, rear + axle, bw / 2f + WHEEL_GAP, AXLE, 1f)
            else -> round(lit, bx + bw / 2f, rear + axle, bw / 2f + WHEEL_GAP, AXLE, 1f)
        }
        pen.beam(outline, g.OUTLINE, ClusterInk.GREY, 0.9f)
        pen.beam(extra, g.WHEEL, ClusterInk.GREY, 0.7f)
        pen.glowFill(lit, light, level, blur)
    }

    private fun wheel(path: Path, x: Float, y: Float) = round(path, x, y, WHEEL_W, WHEEL_L, 1f)

    private fun round(path: Path, x: Float, y: Float, w: Float, h: Float, r: Float) {
        rect.set(x, y, x + w, y + h)
        val rr = min(r, min(w, h) / 2f)
        path.addRoundRect(rect, rr, rr, Path.Direction.CW)
    }

    private const val WHEEL_W = 3.2f
    private const val WHEEL_L = 6.6f
    private const val WHEEL_GAP = 0.6f
    private const val AXLE = 3.2f
    private const val WAVE_STEPS = 32
}
