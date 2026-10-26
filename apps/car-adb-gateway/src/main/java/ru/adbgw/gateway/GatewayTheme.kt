package ru.adbgw.gateway

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Car ADB Gateway's tokens: the sister app's Luminofor grounds (`tools/design-canvas/luminofor`,
 * `spec.json` → `colors.head` and `sheet`) - a black ground, flat plates in the car's two greys,
 * words in white at the stock 90 % and 54 %, and light (the dock's blue, the car's own orange and
 * red) only on what is live.
 */
internal object GatewayInk {
    val Ground = Color(0xFF000000)
    val Plate = Color(0xFF17161B)
    val LitPlate = Color(0xFF2C2B33)
    val Hairline = Color.White.copy(alpha = 0.08f)
    val Title = Color.White.copy(alpha = 0.90f)
    val Summary = Color.White.copy(alpha = 0.54f)
    val Footnote = Color.White.copy(alpha = 0.40f)
    val Dim = Color.White.copy(alpha = 0.28f)
    val Unlit = Color.White.copy(alpha = 0.12f)

    /** The dock's blue - the halo of a live line, and live words. */
    val Blue = Color(0xFF439FFC)

    /** The pale core of a live line, as Luminofor's `head.blue` light draws it. */
    val BlueCore = Color(0xFF80BEFF)
    val Orange = Color(0xFFFF9F19)
    val Red = Color(0xFFFF4046)
    val Primary = Color(0xFF296DCC)
    val Quiet = Color.White.copy(alpha = 0.06f)
    val Scrim = Color.Black.copy(alpha = 0.6f)
}

/** Roboto Mono, for the one line a person types on a computer. */
internal val GatewayMono = FontFamily(Font(R.font.roboto_mono_regular, FontWeight.Normal))

/**
 * Words as the car sets them: the system sans (Roboto on this car), no tracking, no font padding,
 * sentence case.
 */
internal fun gatewayText(
    size: TextUnit,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
    lineHeight: TextUnit = TextUnit.Unspecified,
    family: FontFamily = FontFamily.SansSerif,
) = TextStyle(
    color = color,
    fontFamily = family,
    fontSize = size,
    fontWeight = weight,
    letterSpacing = 0.sp,
    lineHeight = lineHeight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/**
 * True in the debug build's fixture: anything that moves (the working ring, the activity pulses,
 * the busy ring in a button) is held at one representative frame so a screenshot is the same every
 * time.
 */
internal val LocalStillFrame = staticCompositionLocalOf { false }

@Composable
internal fun GatewayTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = GatewayInk.Primary,
        onPrimary = Color.White,
        secondary = GatewayInk.Blue,
        onSecondary = Color.White,
        background = GatewayInk.Ground,
        onBackground = GatewayInk.Title,
        surface = GatewayInk.Plate,
        onSurface = GatewayInk.Title,
        surfaceVariant = GatewayInk.LitPlate,
        onSurfaceVariant = GatewayInk.Summary,
        outline = GatewayInk.Hairline,
        error = GatewayInk.Red,
        onError = Color.White,
    )
    MaterialTheme(colorScheme = colors, content = content)
}

// ---------------------------------------------------------------------------------------- light

private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}
private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
private val additive = PorterDuffXfermode(PorterDuff.Mode.ADD)
private val blurs = HashMap<Int, BlurMaskFilter>()

private fun blur(radiusPx: Float): BlurMaskFilter? {
    if (radiusPx < 0.5f) return null
    val key = (radiusPx * 4f).roundToInt()
    return blurs.getOrPut(key) { BlurMaskFilter(key / 4f, BlurMaskFilter.Blur.NORMAL) }
}

private fun Color.argb(alpha: Float): Int = copy(alpha = (this.alpha * alpha).coerceIn(0f, 1f)).toArgb()

/**
 * A line of light, as the car's charging screen draws one: a soft blurred haze in [halo], two
 * wider faint passes of it, and the [core] on top - all added onto what is under them, so on black
 * it is plain light and over a plate it brightens the plate rather than covering it.
 *
 * [glow] scales the haze (0 draws the core alone); [intensity] dims the whole beam.
 */
internal fun DrawScope.beam(
    path: android.graphics.Path,
    width: Float,
    core: Color,
    halo: Color,
    glow: Float = 1f,
    intensity: Float = 1f,
) = drawIntoCanvas { canvas ->
    val c = canvas.nativeCanvas
    val p = beamPaint
    p.xfermode = additive
    p.shader = null
    if (glow > 0f) {
        p.strokeWidth = width * 3f
        p.color = halo.argb(0.55f * glow * intensity)
        p.maskFilter = blur(min(28f, width * 4f))
        c.drawPath(path, p)
        p.strokeWidth = width * 2f
        p.color = halo.argb(0.30f * glow * intensity)
        p.maskFilter = blur(min(10f, width * 1.2f))
        c.drawPath(path, p)
        p.maskFilter = null
    }
    p.strokeWidth = width
    p.color = core.argb(intensity)
    c.drawPath(path, p)
    p.xfermode = null
}

/**
 * A filled shape of light (a glyph): a blurred haze in [halo] under it, then the shape in [core].
 * [over] lays the core over the plate instead of adding it - Luminofor's rule for the car's orange
 * and red, which come out yellow and pink when added.
 */
internal fun DrawScope.glowFill(
    paths: List<android.graphics.Path>,
    core: Color,
    halo: Color,
    glow: Float,
    blurPx: Float,
    over: Boolean = false,
    intensity: Float = 1f,
) = drawIntoCanvas { canvas ->
    val c = canvas.nativeCanvas
    val p = fillPaint
    p.shader = null
    if (glow > 0f) {
        p.xfermode = additive
        p.color = halo.argb(0.75f * glow * intensity)
        p.maskFilter = blur(blurPx)
        paths.forEach { c.drawPath(it, p) }
        p.color = halo.argb(0.35f * glow * intensity)
        p.maskFilter = blur(blurPx * 2.6f)
        paths.forEach { c.drawPath(it, p) }
        p.maskFilter = null
    }
    p.xfermode = if (over) null else additive
    p.color = core.argb(intensity)
    paths.forEach { c.drawPath(it, p) }
    p.xfermode = null
}

/** A soft dot of light: the head of an activity pulse. */
internal fun DrawScope.glowDot(x: Float, y: Float, radius: Float, core: Color, halo: Color, intensity: Float) =
    drawIntoCanvas { canvas ->
        val c = canvas.nativeCanvas
        val p = fillPaint
        p.shader = null
        p.xfermode = additive
        p.color = halo.argb(0.9f * intensity)
        p.maskFilter = blur(radius * 3f)
        c.drawCircle(x, y, radius * 2.2f, p)
        p.color = halo.argb(0.6f * intensity)
        p.maskFilter = blur(radius)
        c.drawCircle(x, y, radius * 1.4f, p)
        p.maskFilter = null
        p.color = core.argb(intensity)
        c.drawCircle(x, y, radius, p)
        p.xfermode = null
    }

/** A fading tail behind a pulse, from (x0) at full [core] to (x1) at nothing, on one row. */
internal fun DrawScope.glowTail(x0: Float, x1: Float, y: Float, width: Float, core: Color, intensity: Float) =
    drawIntoCanvas { canvas ->
        val c = canvas.nativeCanvas
        val p = beamPaint
        p.xfermode = additive
        p.maskFilter = null
        p.strokeWidth = width
        p.color = android.graphics.Color.WHITE
        p.shader = android.graphics.LinearGradient(
            x0, y, x1, y,
            core.argb(intensity), core.argb(0f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        c.drawLine(x0, y, x1, y, p)
        p.shader = null
        p.xfermode = null
    }

/** A material glyph's filled outlines in pixels, [sizePx] square with its top-left at ([x], [y]). */
internal fun ImageVector.toGlyphPaths(sizePx: Float, x: Float = 0f, y: Float = 0f): List<android.graphics.Path> {
    val out = mutableListOf<android.graphics.Path>()
    val matrix = android.graphics.Matrix().apply {
        setScale(sizePx / viewportWidth, sizePx / viewportHeight)
        postTranslate(x, y)
    }
    fun walk(group: VectorGroup) {
        for (node in group) when (node) {
            is VectorPath -> {
                val path = PathParser().addPathNodes(node.pathData).toPath().asAndroidPath()
                path.fillType = if (node.pathFillType == PathFillType.EvenOdd) {
                    android.graphics.Path.FillType.EVEN_ODD
                } else {
                    android.graphics.Path.FillType.WINDING
                }
                path.transform(matrix)
                out += path
            }
            is VectorGroup -> walk(node)
        }
    }
    walk(root)
    return out
}

// --------------------------------------------------------------------------------- motion

/**
 * The working ring's turn in degrees: once a second, as the sister app's tiles turn theirs; held at
 * twelve o'clock in a still frame.
 */
@Composable
internal fun ringTurn(): Float {
    if (LocalStillFrame.current) return 0f
    val turn by rememberInfiniteTransition(label = "ringTurn").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_000, easing = LinearEasing)),
        label = "ringTurnValue",
    )
    return turn
}

/** A quarter of a circle that only turns: "busy", not a count. Flat - it sits on a button. */
@Composable
internal fun BusyRing(color: Color, size: Dp = 22.dp, stroke: Dp = 2.5.dp) {
    val turn = ringTurn()
    Canvas(Modifier.size(size)) {
        val sw = stroke.toPx()
        drawArc(
            color = color,
            startAngle = -90f + turn,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(sw / 2, sw / 2),
            size = androidx.compose.ui.geometry.Size(this.size.width - sw, this.size.height - sw),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
    }
}

// --------------------------------------------------------------------------------- the mark

/**
 * The app's mark, as the launcher's: an arch of blue light over a white shell prompt. Geometry from
 * `ic_launcher_monochrome.xml` (108-unit viewport), framed on the 52 units round it.
 */
@Composable
internal fun AppMark(size: Dp = 44.dp) {
    Canvas(Modifier.size(size)) {
        val unit = this.size.width / MARK_FRAME
        fun ux(v: Float) = (v - MARK_LEFT) * unit
        fun uy(v: Float) = (v - MARK_TOP) * unit
        val arch = android.graphics.Path().apply {
            moveTo(ux(33f), uy(77f))
            lineTo(ux(33f), uy(55f))
            arcTo(RectF(ux(33f), uy(34f), ux(75f), uy(76f)), 180f, 180f, false)
            lineTo(ux(75f), uy(77f))
        }
        val prompt = android.graphics.Path().apply {
            moveTo(ux(45f), uy(54.5f))
            lineTo(ux(53.5f), uy(62f))
            lineTo(ux(45f), uy(69.5f))
            moveTo(ux(57.5f), uy(70f))
            lineTo(ux(65f), uy(70f))
        }
        beam(arch, MARK_STROKE * unit, GatewayInk.BlueCore, GatewayInk.Blue, glow = 1f)
        beam(prompt, MARK_STROKE * unit, Color.White, Color.White, glow = 0.25f, intensity = 0.95f)
    }
}

private const val MARK_FRAME = 52f
private const val MARK_LEFT = 28f
private const val MARK_TOP = 29.5f
private const val MARK_STROKE = 4f

// --------------------------------------------------------------------------------- buttons

internal enum class ButtonKind { Primary, Danger, Quiet, QuietDanger }

/**
 * The stock large button: `#296DCC` (or the car's red for a destructive confirm) at radius 12, or a
 * quiet one at white 0.06 whose words are white - red when it takes something away. [busy] turns a
 * ring where the words were.
 */
@Composable
internal fun GatewayButton(
    text: String,
    onClick: () -> Unit,
    kind: ButtonKind,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    height: Dp = 64.dp,
    textSize: TextUnit = 18.sp,
) {
    val fill = when (kind) {
        ButtonKind.Primary -> GatewayInk.Primary
        ButtonKind.Danger -> GatewayInk.Red
        ButtonKind.Quiet, ButtonKind.QuietDanger -> GatewayInk.Quiet
    }
    val ink = when (kind) {
        ButtonKind.Primary, ButtonKind.Danger -> Color.White
        ButtonKind.Quiet -> GatewayInk.Title
        ButtonKind.QuietDanger -> GatewayInk.Red
    }
    Surface(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(12.dp),
        color = fill,
        contentColor = ink,
        modifier = modifier
            .height(height)
            .alpha(if (enabled || busy) 1f else 0.5f),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(ButtonPadding)) {
            if (busy) {
                BusyRing(color = ink)
            } else {
                Text(
                    text,
                    style = gatewayText(textSize, ink, FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val ButtonPadding = PaddingValues(horizontal = 24.dp)
