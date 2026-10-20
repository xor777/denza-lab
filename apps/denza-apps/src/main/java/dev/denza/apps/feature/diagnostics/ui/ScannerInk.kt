package dev.denza.apps.feature.diagnostics.ui

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.denza.apps.R
import dev.denza.apps.design.luminofor.LightPen
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Style
import kotlin.math.roundToInt

/*
 * What the scanner's screens are drawn with: `scanner.js`'s `text`, `runs`, `para`, `fill`, `edge`,
 * `rule` and `glow`, as Compose. Every word stands in a line box of its style's height with its
 * baseline where the board puts it, so a row laid out here lines up with the board's row whatever
 * leading Compose would have given it.
 */

/** Roboto Mono, the same files the board inlines: figures, addresses, raw bytes. */
internal val ScannerMono = FontFamily(
    Font(R.font.roboto_mono_regular, FontWeight.Normal),
    Font(R.font.roboto_mono_medium, FontWeight.Medium),
)

internal fun ink(argb: Int): Color = Color(argb)

/** The style as Compose sets it: Roboto (the system's) or Roboto Mono, linear advances, no padding. */
internal fun Style.text(color: Int, shadow: Shadow? = null): TextStyle = TextStyle(
    color = ink(color),
    fontFamily = if (mono) ScannerMono else FontFamily.Default,
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = track.em,
    lineHeight = lh.sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineBreak = LineBreak.Simple,
    textMotion = TextMotion.Animated,
    shadow = shadow,
)

private fun Style.span(color: Int): SpanStyle = SpanStyle(
    color = ink(color),
    fontFamily = if (mono) ScannerMono else FontFamily.Default,
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = track.em,
)

private const val ARROW = '→'
private const val ARROW_ID = "arrow"

/** Room for «→», as wide as the board's `arrowW`; the arrow itself is drawn by [arrows]. */
private fun arrowRoom(style: Style): Map<String, InlineTextContent> = mapOf(
    ARROW_ID to InlineTextContent(
        Placeholder((style.arrowWidth / style.size).em, 0.5.em, PlaceholderVerticalAlign.AboveBaseline),
    ) {},
)

/**
 * «→» as the board draws it - a line with a head, [Style.arrowWidth] wide, 0.3 em over the
 * baseline of its own line - into every placeholder of the laid-out text.
 */
private fun Modifier.arrows(layout: () -> TextLayoutResult?, style: Style, color: Int): Modifier = drawWithContent {
    drawContent()
    val l = layout() ?: return@drawWithContent
    val a = ScannerSpec.Fonts.Arrow
    val z = style.size.sp.toPx()
    val c = ink(color)
    val sw = a.STROKE * z
    l.placeholderRects.forEach { r ->
        if (r == null) return@forEach
        val line = l.getLineForVerticalPosition(r.center.y)
        val y = l.getLineBaseline(line) - a.Y * z
        val w = r.width - style.track * z
        val x0 = r.left + 0.07f * z
        val x1 = r.left + w - 0.07f * z
        drawLine(c, Offset(x0, y), Offset(x1, y), sw, StrokeCap.Round)
        drawLine(c, Offset(x1 - a.HEAD * z, y - a.SPREAD * z), Offset(x1, y), sw, StrokeCap.Round)
        drawLine(c, Offset(x1 - a.HEAD * z, y + a.SPREAD * z), Offset(x1, y), sw, StrokeCap.Round)
    }
}

private fun withArrows(parts: List<Pair<String, SpanStyle?>>): AnnotatedString = buildAnnotatedString {
    parts.forEach { (str, span) ->
        val run: AnnotatedString.Builder.() -> Unit = {
            str.split(ARROW).forEachIndexed { i, p ->
                if (i > 0) appendInlineContent(ARROW_ID, ARROW.toString())
                append(p)
            }
        }
        if (span != null) withStyle(span) { run() } else run()
    }
}

/**
 * The line box: as tall as the style's line height, the first baseline [baseline] below its top
 * (the style's own unless given - a count on its name's baseline), whatever Compose's leading.
 */
private fun Modifier.lineBox(style: Style, baseline: Float?, laid: Array<TextLayoutResult?>): Modifier = layout { m, c ->
    val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val first = p[FirstBaseline]
    val b = (baseline ?: style.baseline).dp.toPx().roundToInt()
    // as wide as the board measures the words - their advances and every letter's spacing, the
    // last one's too, which Android leaves off a line - not as the box Compose rounds them up
    // into, so a right-aligned or centred line ends where the board's does
    val track = style.track * style.size.sp.toPx()
    val w = laid[0]?.let { (it.getLineRight(0) - it.getLineLeft(0) + track).roundToInt() } ?: p.width
    layout(w, style.lh.dp.roundToPx()) { p.place(0, b - first) }
}

/** A glow as the board's `glow()`: Chrome's shadowBlur in dp, as a text shadow. */
@Composable
private fun glowShadow(color: Int?, blur: Float): Shadow? {
    if (color == null) return null
    val px = with(LocalDensity.current) { blur.dp.toPx() }
    return Shadow(ink(color), Offset.Zero, LightPen.blurRadius(px))
}

/**
 * One line of text in its line box (`scanner.js` `text()`): [maxWidth] is the room - a longer
 * line ends in an ellipsis, as the board's `fit()`.
 */
@Composable
internal fun ScanText(
    text: String,
    style: Style,
    color: Int,
    modifier: Modifier = Modifier,
    maxWidth: Float? = null,
    baseline: Float? = null,
    glow: Int? = null,
    glowBlur: Float = 0f,
) {
    val shadow = glowShadow(glow, glowBlur)
    val annotated = remember(text) { withArrows(listOf(text to null)) }
    val arrow = ARROW in text
    val laid = remember { mutableStateOf<TextLayoutResult?>(null) }
    val held = remember { arrayOfNulls<TextLayoutResult>(1) }
    BasicText(
        text = annotated,
        modifier = modifier
            .then(if (maxWidth != null) Modifier.widthIn(max = maxWidth.coerceAtLeast(0f).dp) else Modifier)
            .lineBox(style, baseline, held)
            .then(if (arrow) Modifier.arrows({ laid.value }, style, color) else Modifier),
        style = style.text(color, shadow),
        onTextLayout = { held[0] = it; if (arrow) laid.value = it },
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        maxLines = 1,
        inlineContent = if (arrow) remember(style) { arrowRoom(style) } else emptyMap(),
    )
}

/** Runs of different styles on one baseline, [base]'s (`scanner.js` `runs()`). */
@Composable
internal fun ScanRuns(
    runs: List<Triple<String, Style, Int>>,
    modifier: Modifier = Modifier,
    maxWidth: Float? = null,
    base: Style = runs.first().second,
) {
    val first = base
    val annotated = remember(runs) { withArrows(runs.map { (s, st, c) -> s to st.span(c) }) }
    val arrow = runs.any { ARROW in it.first }
    val laid = remember { mutableStateOf<TextLayoutResult?>(null) }
    val held = remember { arrayOfNulls<TextLayoutResult>(1) }
    BasicText(
        text = annotated,
        modifier = modifier
            .then(if (maxWidth != null) Modifier.widthIn(max = maxWidth.coerceAtLeast(0f).dp) else Modifier)
            .lineBox(first, null, held)
            .then(if (arrow) Modifier.arrows({ laid.value }, runs.first().second, runs.first().third) else Modifier),
        onTextLayout = { held[0] = it; if (arrow) laid.value = it },
        style = runs.first().second.text(runs.first().third),
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        maxLines = 1,
        inlineContent = if (arrow) remember(first) { arrowRoom(runs.first().second) } else emptyMap(),
    )
}

/**
 * A paragraph broken at spaces into [width] (`scanner.js` `para()`): lines of the style's height,
 * the first baseline where a single line's would be; as tall as its lines.
 */
@Composable
internal fun ScanParagraph(text: String, style: Style, color: Int, width: Float, modifier: Modifier = Modifier) {
    val arrow = ARROW in text
    val laid = remember { mutableStateOf<TextLayoutResult?>(null) }
    BasicText(
        text = remember(text) { withArrows(listOf(text to null)) },
        onTextLayout = { if (arrow) laid.value = it },
        inlineContent = if (arrow) remember(style) { arrowRoom(style) } else emptyMap(),
        modifier = modifier.width(width.dp).layout { m, c ->
            val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val first = p[FirstBaseline]
            val last = p[LastBaseline]
            val b = style.baseline.dp.toPx().roundToInt()
            val lh = style.lh.dp.toPx()
            layout(p.width, (last - first + lh).roundToInt()) { p.place(0, b - first) }
        }.then(if (arrow) Modifier.arrows({ laid.value }, style, color) else Modifier),
        style = style.text(color),
    )
}

/**
 * How wide a line of text is, in dp and unrounded. A row of words placed composable after
 * composable drifts by up to a pixel a word (each one's box is a whole number of pixels); the board
 * adds the widths themselves, so rows of words are placed from these.
 */
@Composable
internal fun rememberWidths(): (String, Style) -> Float {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density) {
        { text, style ->
            val r = measurer.measure(AnnotatedString(text), style.text(0), softWrap = false, maxLines = 1, density = density)
            (r.getLineRight(0) - r.getLineLeft(0)) / density.density + style.track * style.size
        }
    }
}

/* ------------------------------------------------------------- placing */

/**
 * Places at [x] px with its fraction kept: on the whole pixel below and the rest as the layer's
 * translation, so text whose board column starts at 36.4 dp is not rounded to 36.5 or 36.
 */
private fun Placeable.PlacementScope.placeAt(p: Placeable, x: Float, y: Int) {
    val whole = kotlin.math.floor(x)
    val frac = x - whole
    if (frac < 0.02f) p.place(whole.toInt(), y) else p.placeWithLayer(whole.toInt(), y) { translationX = frac }
}

/** At [x], [y] dp of the parent's top-left. */
internal fun Modifier.at(x: Float, y: Float): Modifier = layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
    layout(p.width, p.height) { placeAt(p, x.dp.toPx(), y.dp.roundToPx()) }
}

/** Its end at [x]: the board's `align: 'right'`. */
internal fun Modifier.endAt(x: Float, y: Float): Modifier = layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
    layout(p.width, p.height) { placeAt(p, x.dp.toPx() - p.width, y.dp.roundToPx()) }
}

/** Its middle at [x]: the board's `align: 'center'`. */
internal fun Modifier.centreAt(x: Float, y: Float): Modifier = layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
    layout(p.width, p.height) { placeAt(p, x.dp.toPx() - p.width / 2f, y.dp.roundToPx()) }
}

/** A tap with no ripple: the scanner's surfaces are flat. */
@Composable
internal fun Modifier.tap(onClick: (() -> Unit)?): Modifier =
    if (onClick == null) this else clickable(remember { MutableInteractionSource() }, null, onClick = onClick)

/* -------------------------------------------------------------- shapes */

/**
 * A plate: [fill] under, then a 1 dp line on the inner edge (`fill()` then `edge()`), dashed 3/3
 * when [dash].
 */
internal fun Modifier.plate(fill: Int?, line: Int? = null, r: Float = 0f, dash: Boolean = false): Modifier = drawBehind {
    val rad = r.dp.toPx()
    if (fill != null) drawRoundRect(ink(fill), cornerRadius = CornerRadius(rad, rad))
    if (line != null) edge(line, r, dash)
}

internal fun DrawScope.edge(line: Int, r: Float, dash: Boolean = false, x: Float = 0f, y: Float = 0f, w: Float = size.width, h: Float = size.height) {
    val half = 0.5.dp.toPx()
    val rr = ((r - 0.5f).coerceAtLeast(0f)).dp.toPx()
    val d = 3.dp.toPx()
    if (dash) {
        // the canvas's roundRect(): from the end of the top-left corner, clockwise, so the dashes
        // start where the board's do
        val l = x + half
        val t = y + half
        val r = x + w - half
        val b = y + h - half
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(l + rr, t)
            lineTo(r - rr, t)
            arcTo(androidx.compose.ui.geometry.Rect(r - 2 * rr, t, r, t + 2 * rr), -90f, 90f, false)
            lineTo(r, b - rr)
            arcTo(androidx.compose.ui.geometry.Rect(r - 2 * rr, b - 2 * rr, r, b), 0f, 90f, false)
            lineTo(l + rr, b)
            arcTo(androidx.compose.ui.geometry.Rect(l, b - 2 * rr, l + 2 * rr, b), 90f, 90f, false)
            lineTo(l, t + rr)
            arcTo(androidx.compose.ui.geometry.Rect(l, t, l + 2 * rr, t + 2 * rr), 180f, 90f, false)
            close()
        }
        drawPath(path, ink(line), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(d, d))))
        return
    }
    drawRoundRect(
        ink(line),
        topLeft = Offset(x + half, y + half),
        size = Size(w - 2 * half, h - 2 * half),
        cornerRadius = CornerRadius(rr, rr),
        style = Stroke(1.dp.toPx(), pathEffect = if (dash) PathEffect.dashPathEffect(floatArrayOf(d, d)) else null),
    )
}

/** A horizontal 1 dp rule (`rule()`), [w] dp wide, at [x], [y]. */
@Composable
internal fun Rule(x: Float, y: Float, w: Float, color: Int = ScannerSpec.Ink.RULE) {
    Box(Modifier.at(x, y).size(w.dp, 1.dp).plate(color))
}

/** A vertical 1 dp rule (`vrule()`). */
@Composable
internal fun VRule(x: Float, y: Float, h: Float, color: Int = ScannerSpec.Ink.RULE) {
    Box(Modifier.at(x, y).size(1.dp, h.dp).plate(color))
}

/** A shape's glow (`glow()`): the same shape blurred in [color] under it; [blur] is Chrome's, in dp. */
internal fun DrawScope.glowUnder(color: Int, blur: Float, shape: (android.graphics.Canvas, android.graphics.Paint) -> Unit) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        val r = LightPen.blurRadius(blur.dp.toPx())
        if (r > 0f) maskFilter = BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL)
    }
    drawIntoCanvas { shape(it.nativeCanvas, paint) }
}
