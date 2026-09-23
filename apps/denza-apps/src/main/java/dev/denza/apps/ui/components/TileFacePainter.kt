package dev.denza.apps.ui.components

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import dev.denza.apps.design.DenzaGlyph
import dev.denza.apps.design.DenzaIcons
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.design.luminofor.LightPen

/**
 * A feature's plate and its glyph, drawn the way `tileFace()` draws them: in one canvas, the glyph
 * added onto the plate.
 *
 * The plate is an ordinary fill. The glyph is the board's beam - [LightPen.beam], additive, with
 * the halo `head.icon.onGlow` asks for on a lit face - and it has to land in the same canvas as the
 * plate it is added to, because `lighter` over a plate is the plate plus the light, and anything
 * that put the glyph in a layer of its own would add it to transparent black and then lay the
 * result over the plate, which is ordinary alpha and a duller glyph. Drawing both here keeps that
 * true whatever layers Compose puts around the tile.
 *
 * A fader's knob is drawn as the board draws it: a disc of the plate's own colour, 1.1 units wider
 * than the knob, painted over the line (and whatever halo is under it), then the knob stroked on
 * top. That is how a knob cuts its line on the board, and it needs the plate's colour, which is why
 * the knob is only a real mask here and an outline everywhere else.
 *
 * The plate is a plain rectangle filling the draw area, and its corners are the caller's clip: a
 * tile is always drawn inside `clip(RoundedCornerShape)` - the clip is what bounds its ripple - and
 * a rounded plate inside a rounded clip is antialiased twice, which darkens the rim of every corner
 * against the board's single edge. One edge, the clip's, is the board's `roundRect`.
 *
 * One per tile, reused frame to frame; nothing here allocates while drawing.
 */
internal class TileFacePainter {

    /** The pen only ever draws beams here, so it needs no faces of its own. */
    private val pen = LightPen(Typeface.DEFAULT, Typeface.DEFAULT, Typeface.DEFAULT)
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val knob = Path()

    /**
     * The plate, filling the whole draw area, and [glyph] in a [glyphSize]-square box whose
     * top-left is [glyphAt] - all in pixels.
     *
     * [plate] is passed separately from [face] because it is animated between the two faces; the
     * knob masks take the same colour, so a knob never shows a ring of the other plate mid-fade.
     */
    fun draw(
        scope: DrawScope,
        face: TileFace,
        plate: Color,
        glyph: DenzaGlyph,
        glyphAt: Offset,
        glyphSize: Float,
    ) = with(scope) {
        drawRect(color = plate)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val unit = glyphSize / DenzaIcons.VIEWPORT
            native.save()
            native.translate(glyphAt.x, glyphAt.y)
            pen.begin(native, unit)
            pen.beam(glyph.strokePath, DenzaMetrics.Stroke.ICON, face.glyph, face.glyphIntensity, face.glyphGlow, face.glyphOver)
            mask.color = plate.toArgb()
            glyph.knobs.forEach { k ->
                val cx = k.cx + glyph.shift
                native.drawCircle(cx * unit, k.cy * unit, (k.r + TileFace.KNOB_MASK) * unit, mask)
                knob.rewind()
                knob.addCircle(cx, k.cy, k.r, Path.Direction.CW)
                pen.beam(knob, DenzaMetrics.Stroke.ICON, face.glyph, face.glyphIntensity, 0f, face.glyphOver)
            }
            native.restore()
        }
    }
}
