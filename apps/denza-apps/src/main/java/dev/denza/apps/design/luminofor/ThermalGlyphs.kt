package dev.denza.apps.design.luminofor

import android.graphics.Path
import android.graphics.RectF
import dev.denza.apps.design.luminofor.LuminoforSpec.ClusterInk
import dev.denza.apps.design.luminofor.LuminoforSpec.Light
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The five temperature glyphs - the pack, the three motors seen from above, the inverter - as the
 * Luminofor board draws them. On the cluster they are the temperatures' captions; on the car page
 * they are the same, so one glyph means one cell on both screens.
 *
 * The outline is always the calm grey; what carries the reading's colour is the lit part (the
 * pack's cell, the motor block on its axle, the inverter's wave). A cell out of line lights its
 * part with a blur; a calm one draws it flat.
 */
object ThermalGlyphs {

    enum class Cell { PACK, FRONT, REAR_LEFT, REAR_RIGHT, INVERTER }

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
            Cell.INVERTER -> {
                round(outline, x, base - 22f, 21f, 21f, 3f)
                extra.moveTo(x + 4f, base - 11.5f)
                for (i in 0..26) {
                    val u = i / 26f
                    extra.lineTo(x + 4f + u * 13f, base - 11.5f - sin(u * PI * 2).toFloat() * 5f)
                }
                pen.beam(outline, g.OUTLINE, ClusterInk.GREY, 0.9f)
                pen.beam(extra, 1.8f, light, level, if (hot) 1f else 0f)
            }
            else -> {
                val bx = x + 4f
                val bw = 13f
                val top = base - 23f
                val bh = 22f
                round(outline, bx, top, bw, bh, 4f)
                round(extra, bx - 3.4f, top + 3f, 3f, 6f, 1f)
                round(extra, bx + bw + 0.4f, top + 3f, 3f, 6f, 1f)
                round(extra, bx - 3.4f, top + bh - 9f, 3f, 6f, 1f)
                round(extra, bx + bw + 0.4f, top + bh - 9f, 3f, 6f, 1f)
                when (cell) {
                    Cell.FRONT -> round(lit, bx + 2f, top + 4.5f, bw - 4f, 3.2f, 1f)
                    Cell.REAR_LEFT -> round(lit, bx + 2f, top + bh - 7.5f, (bw - 4f) / 2f - 0.5f, 3.2f, 1f)
                    else -> round(lit, bx + bw / 2f + 0.5f, top + bh - 7.5f, (bw - 4f) / 2f - 0.5f, 3.2f, 1f)
                }
                pen.beam(outline, g.OUTLINE, ClusterInk.GREY, 0.9f)
                pen.beam(extra, g.WHEEL, ClusterInk.GREY, 0.7f)
                pen.glowFill(lit, light, level, blur)
            }
        }
    }

    private fun round(path: Path, x: Float, y: Float, w: Float, h: Float, r: Float) {
        rect.set(x, y, x + w, y + h)
        val rr = min(r, min(w, h) / 2f)
        path.addRoundRect(rect, rr, rr, Path.Direction.CW)
    }
}
