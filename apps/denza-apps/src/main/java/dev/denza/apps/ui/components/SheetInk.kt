package dev.denza.apps.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.PathParser
import dev.denza.apps.design.DenzaGlyph
import dev.denza.apps.design.DenzaIcons
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.design.luminofor.LuminoforSpec.Sheet

/**
 * What every settings surface is drawn with: the board's `words()`, `lineGlyph()` and `toggle()`.
 *
 * The settings are the Luminofor board's `drawSheet()` (`tools/design-canvas/luminofor/`) - the car's
 * own BYD widget kit in Luminofor's grounds - and the board places every word by its baseline. So does
 * this: [BaselineText] puts a line's first baseline where the board puts it, whatever leading Compose
 * would have given it, because a row whose words sit two dp lower than the board's is a row that no
 * longer lines up with the switch beside it. Words are white at an alpha, laid over their surface -
 * Compose's own source-over, which is what the board's `words()` does too.
 */
internal object SheetInk {

    /** White at [alpha]: every word and line on a settings surface. */
    fun white(alpha: Float): Color = Color.White.copy(alpha = alpha)

    /**
     * Roboto - the car's system sans - at [size] dp, laid out the way the board measures it: linear
     * advances (so a line is as long as Chrome's), no font padding, the first line's top and the last
     * line's bottom trimmed to the glyphs, and plain greedy breaking.
     */
    fun style(size: Float, weight: Int = 400, color: Color = Color.White, leading: Float = 0f): TextStyle = TextStyle(
        color = color,
        fontSize = size.sp,
        fontWeight = FontWeight(weight),
        letterSpacing = 0.sp,
        lineHeight = if (leading > 0f) (size * leading).sp else TextStyle.Default.lineHeight,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineBreak = LineBreak.Simple,
        textMotion = TextMotion.Animated,
    )
}

/**
 * One line whose first baseline stands [baseline] below the top of this composable.
 *
 * The composable is as tall as the line reaches below that - baseline plus the line's descent - so
 * a column of these stacks by baselines, and one alone in a fixed box lands where the board draws it.
 */
@Composable
internal fun BaselineText(
    text: String,
    style: TextStyle,
    baseline: Dp,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        style = if (textAlign != null) style.copy(textAlign = textAlign) else style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0))
            val first = placeable[FirstBaseline]
            val top = baseline.roundToPx() - first
            layout(placeable.width, (top + placeable.height).coerceAtLeast(0)) {
                placeable.place(0, top)
            }
        },
    )
}

/**
 * A paragraph at [size] whose first baseline stands at the board's ascent below its top and whose
 * foot is the board's descent below its last baseline - the board's `paragraph()`, measured from
 * baselines rather than from the leading Compose trims, which put a whole note a pixel or two off.
 */
@Composable
internal fun ParagraphText(
    text: String,
    style: TextStyle,
    size: Float,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0))
            val first = placeable[FirstBaseline]
            val last = placeable[LastBaseline]
            val ascent = (size * Sheet.Roboto.ASCENT).dp.roundToPx()
            val descent = (size * Sheet.Roboto.DESCENT).dp.roundToPx()
            val top = ascent - first
            layout(placeable.width, ascent + (last - first) + descent) { placeable.place(0, top) }
        },
    )
}

/** A line centred on [centre]: its baseline [Sheet.Roboto.CENTRE] of its size below the point. */
internal fun centredBaseline(centre: Float, size: Float): Dp = (centre + Sheet.Roboto.CENTRE * size).dp

/**
 * A stroked glyph at [size], white at [alpha], from path strings on the 24-unit grid - the board's
 * `lineGlyph()`: the way out, the way back, a row's chevron.
 */
@Composable
internal fun LineGlyph(paths: List<String>, size: Dp, alpha: Float, modifier: Modifier = Modifier) {
    val path = remember(paths) {
        android.graphics.Path().apply { paths.forEach { addPath(PathParser.createPathFromPathData(it)) } }
    }
    val paint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
    }
    Canvas(modifier.size(size)) {
        val unit = this.size.width / DenzaIcons.VIEWPORT
        paint.color = android.graphics.Color.argb((alpha * 255f).toInt(), 255, 255, 255)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.scale(unit, unit)
            paint.strokeWidth = DenzaMetrics.Stroke.ICON
            native.drawPath(path, paint)
            native.restore()
        }
    }
}

/**
 * A tile's glyph standing on its own - a panel's header - white at [alpha], its ink centred in [size]
 * rather than hung on the tiles' left edge (the board's `glyphAt()`). A fader's knob cuts its line
 * with a disc of [ground], as it does on a tile.
 */
@Composable
internal fun NamedGlyph(
    glyph: DenzaGlyph,
    size: Dp,
    alpha: Float,
    ground: Color,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    val paint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
    }
    val knob = remember { android.graphics.Path() }
    Canvas(modifier.size(size)) {
        val unit = this.size.width / DenzaIcons.VIEWPORT
        val ink = glyph.inkBounds
        val half = DenzaIcons.VIEWPORT / 2f
        val white = tint.copy(alpha = alpha).toArgbInt()
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.scale(unit, unit)
            native.translate(half - ink.centerX(), half - ink.centerY())
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = DenzaMetrics.Stroke.ICON
            paint.color = white
            native.drawPath(glyph.strokePath, paint)
            glyph.knobs.forEach { k ->
                val cx = k.cx + glyph.shift
                paint.style = android.graphics.Paint.Style.FILL
                paint.color = ground.toArgbInt()
                native.drawCircle(cx, k.cy, k.r + TileFace.KNOB_MASK, paint)
                paint.style = android.graphics.Paint.Style.STROKE
                paint.color = white
                knob.rewind()
                knob.addCircle(cx, k.cy, k.r, android.graphics.Path.Direction.CW)
                native.drawPath(knob, paint)
            }
            native.restore()
        }
    }
}

/** The stock switch (`byd_pvt_switch_*_dark`): a blue or grey track, a white thumb. */
@Composable
internal fun SheetSwitch(checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val w = Sheet.Switch
    val a = if (enabled) 1f else w.DISABLED_ALPHA
    Canvas(modifier.size(w.WIDTH.dp, w.HEIGHT.dp)) {
        val h = size.height
        val track = if (checked) Color(w.ON).copy(alpha = a) else Color(w.OFF).copy(alpha = w.OFF_ALPHA * a)
        drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f, h / 2f))
        val pad = (w.HEIGHT - w.THUMB) / 2f * density
        val thumb = w.THUMB * density
        val left = if (checked) size.width - pad - thumb else pad
        drawCircle(
            Color(w.THUMB_COLOR).copy(alpha = a),
            radius = thumb / 2f,
            center = androidx.compose.ui.geometry.Offset(left + thumb / 2f, h / 2f),
        )
    }
}

/** The path strings the board draws its small line glyphs from. */
internal object SheetGlyphs {
    val CLOSE = listOf("M6 6l12 12M18 6L6 18")
    val BACK = listOf("M15 5l-7 7 7 7")
    val FORWARD = listOf("M9 5l7 7-7 7")
    const val CHECK = "M5.5 10.2l3 3 6-6.2"
}

/** A box of [size] that draws nothing: the room a glyph would take. */
@Composable
internal fun GlyphRoom(size: Dp) = Box(Modifier.size(size))

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255f).toInt(), (red * 255f).toInt(), (green * 255f).toInt(), (blue * 255f).toInt(),
)
