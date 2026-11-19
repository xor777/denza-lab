package dev.denza.apps.feature.split.pill

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * Everything the pill draws that does not change from frame to frame, made once into bitmaps at
 * the screen's own pixels (decision 5): each app's icon at `icon.size`, its breathing contour, the
 * «+» button, the badges, the live mark with its glow, the lift shadows of the capsule as a
 * three-slice image and the press bloom. A frame only places and fades them: no blur, no path of
 * an icon, no allocation.
 *
 * Icons load off the main thread, once per package; until one arrives its place is a quiet
 * rounded square, and [onLoaded] asks for the frame that shows it.
 */
internal class SplitPillArt(
    private val context: Context,
    val scale: Double,
    private val onLoaded: () -> Unit,
) {
    private val icons = HashMap<String, Bitmap>()
    private val contours = HashMap<String, Bitmap>()
    private val loading = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())

    private fun px(dp: Double): Int = ceil(dp * scale - 1e-6).toInt()
    private fun f(dp: Double): Float = (dp * scale).toFloat()

    // region icons

    /** The app's icon at `icon.size`, corner `icon.radius`, or `null` while it loads. */
    fun icon(packageName: String): Bitmap? {
        icons[packageName]?.let { return it }
        load(packageName)
        return null
    }

    /** The missing app: a blue dashed outline round the app's glyph in blue, with its glow. */
    fun contour(packageName: String): Bitmap? = contours[packageName] ?: run {
        load(packageName)
        null
    }

    private fun load(packageName: String) {
        if (!loading.add(packageName)) return
        loader.execute {
            val drawable = runCatching { context.packageManager.getApplicationIcon(packageName) }
                .recoverCatching { error ->
                    if (error is PackageManager.NameNotFoundException) null else throw error
                }
                .getOrNull()
            val size = px(SplitPillSpec.Icon.SIZE)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val clip = Path().apply {
                addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), f(SplitPillSpec.Icon.RADIUS), f(SplitPillSpec.Icon.RADIUS), Path.Direction.CW)
            }
            canvas.clipPath(clip)
            if (drawable != null) {
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
            } else {
                canvas.drawColor(SplitPillSpec.Color.BTN)
            }
            val contour = makeContour(bitmap)
            main.post {
                icons[packageName] = bitmap
                contours[packageName] = contour
                onLoaded()
            }
        }
    }

    /**
     * The contour (README, "A pair chip"): a dashed outline `ghost.dash` at `ghost.stroke`,
     * `ghost.rimInset` in, corner `icon.radius - rimInset`, and the app drawn as a blue glyph - its
     * icon's light turned into the blue - all with a `ghost.glow` glow. A real app has no glyph of
     * its own, so its icon's luminance stands for one.
     */
    private fun makeContour(icon: Bitmap): Bitmap {
        val glow = f(SplitPillSpec.Ghost.GLOW)
        val pad = ceil(glow * 3).toInt()
        val size = icon.width + 2 * pad
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val inset = f(SplitPillSpec.Ghost.RIM_INSET)
        val radius = f(SplitPillSpec.Icon.RADIUS - SplitPillSpec.Ghost.RIM_INSET)
        val rim = RectF(pad + inset, pad + inset, pad + icon.width - inset, pad + icon.height - inset)
        val dash = DashPathEffect(floatArrayOf(f(SplitPillSpec.Ghost.DASH_ON), f(SplitPillSpec.Ghost.DASH_OFF)), 0f)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = f(SplitPillSpec.Ghost.STROKE)
            color = SplitPillSpec.Color.BLUE
            pathEffect = dash
        }
        val glowPaint = Paint(stroke).apply {
            color = SplitPillSpec.Color.BLUE_GLOW
            maskFilter = BlurMaskFilter(glow, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(rim, radius, radius, glowPaint)
        canvas.drawRoundRect(rim, radius, radius, stroke)
        // The glyph: the icon's luminance as the blue's alpha, inside the rim.
        val blue = SplitPillSpec.Color.BLUE
        val r = (blue shr 16 and 0xff).toFloat()
        val g = (blue shr 8 and 0xff).toFloat()
        val b = (blue and 0xff).toFloat()
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        0f, 0f, 0f, 0f, r,
                        0f, 0f, 0f, 0f, g,
                        0f, 0f, 0f, 0f, b,
                        0.2126f, 0.7152f, 0.0722f, 0f, 0f,
                    ),
                ),
            )
        }
        val shrink = f(SplitPillSpec.Ghost.STROKE) * 2
        val dst = RectF(pad + shrink, pad + shrink, pad + icon.width - shrink, pad + icon.height - shrink)
        val glyphGlow = Paint(glyph).apply { maskFilter = BlurMaskFilter(glow, BlurMaskFilter.Blur.NORMAL) }
        canvas.drawBitmap(icon, null, dst, glyphGlow)
        canvas.drawBitmap(icon, null, dst, glyph)
        return out
    }

    /** How far the contour bitmap reaches past the icon's square on each side, px. */
    val contourPad: Int get() = ceil(f(SplitPillSpec.Ghost.GLOW) * 3).toInt()

    // endregion

    // region fixed pieces

    /** «+»: `btn` white round, the page's glyph stroked in its 24-unit box. */
    val plus: Bitmap by lazy {
        val size = px(SplitPillSpec.Btn.SIZE)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SplitPillSpec.Color.BTN })
        val unit = f(SplitPillSpec.Btn.GLYPH / GLYPH_BOX)
        val off = f((SplitPillSpec.Btn.SIZE - SplitPillSpec.Btn.GLYPH) / 2)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = f(SplitPillSpec.Btn.STROKE)
            strokeCap = Paint.Cap.ROUND
            color = SplitPillSpec.Color.WHITE
        }
        canvas.drawPath(PillGlyphs.path(PillGlyphs.PLUS, unit, off, off), stroke)
        bitmap
    }

    /** The badge (README, "Pin badge"): dark disc, white ring, the star outlined or solid, its drop. */
    fun badge(solid: Boolean, lit: Boolean): Bitmap {
        val i = (if (solid) 1 else 0) + (if (lit) 2 else 0)
        return badges[i] ?: makeBadge(solid, lit).also { made -> badges[i] = made }
    }

    /** The four badges by index: solid + 2 · lit. */
    private val badges = arrayOfNulls<Bitmap>(4)

    /**
     * Makes every fixed piece now - «+», the four badges, the live mark, the bloom and the shadow
     * - so that no frame is the first to need one (decision 5). Called once, before the first draw.
     */
    fun prepare() {
        glass.prepare()
        plus
        liveMark
        bloom
        shadow
        for (solid in BOOLS) for (lit in BOOLS) badge(solid, lit)
    }

    /** The glass's sheen and rim gradients, one pair per whole-pixel capsule height. */
    val glass = PillGlass(scale)

    /** How far a badge bitmap reaches past its disc on each side, px. */
    val badgePad: Int get() = px(SplitPillSpec.Star.GLOW_FAR + SplitPillSpec.Badge.DROP_BLUR)

    private fun makeBadge(solid: Boolean, lit: Boolean): Bitmap {
        val disc = px(SplitPillSpec.Badge.SIZE)
        val pad = badgePad
        val size = disc + 2 * pad
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = size / 2f
        val r = disc / 2f
        val drop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SplitPillSpec.Color.BADGE_DROP
            maskFilter = blur(SplitPillSpec.Badge.DROP_BLUR)
        }
        canvas.drawCircle(c + f(SplitPillSpec.Badge.DROP_X), c + f(SplitPillSpec.Badge.DROP_Y), r, drop)
        canvas.drawCircle(c, c, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SplitPillSpec.Color.BADGE_DARK })
        val ringW = f(SplitPillSpec.Badge.RING)
        canvas.drawCircle(
            c,
            c,
            r - ringW / 2,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = ringW
                color = SplitPillSpec.Color.BADGE_RING
            },
        )
        val unit = f(SplitPillSpec.Badge.STAR / GLYPH_BOX)
        val off = c - f(SplitPillSpec.Badge.STAR) / 2
        val star = PillGlyphs.path(PillGlyphs.STAR, unit, off, off)
        val colour = if (lit) SplitPillSpec.Color.CROWN else SplitPillSpec.Color.WHITE
        if (lit) {
            for (g in listOf(SplitPillSpec.Star.GLOW_FAR, SplitPillSpec.Star.GLOW_NEAR)) {
                canvas.drawPath(
                    star,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = SplitPillSpec.Color.BLUE_GLOW
                        maskFilter = blur(g)
                    },
                )
            }
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colour
            strokeWidth = f(SplitPillSpec.Badge.STAR_STROKE)
            strokeJoin = Paint.Join.ROUND
            style = if (solid) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        }
        canvas.drawPath(star, paint)
        return bitmap
    }

    /** The live mark: a blue bar `mark.w` x `mark.h`, corner `mark.r`, glowing `mark.glow`. */
    val liveMark: Bitmap by lazy {
        val pad = px(SplitPillSpec.Mark.GLOW_FAR * 2)
        val w = px(SplitPillSpec.Mark.W) + 2 * pad
        val h = px(SplitPillSpec.Mark.H) + 2 * pad
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bar = RectF(pad.toFloat(), pad.toFloat(), (w - pad).toFloat(), (h - pad).toFloat())
        val r = f(SplitPillSpec.Mark.R)
        canvas.drawRoundRect(bar, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SplitPillSpec.Color.BLUE_HAZE; maskFilter = blur(SplitPillSpec.Mark.GLOW_FAR) })
        canvas.drawRoundRect(bar, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SplitPillSpec.Color.BLUE_GLOW; maskFilter = blur(SplitPillSpec.Mark.GLOW_NEAR) })
        canvas.drawRoundRect(bar, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SplitPillSpec.Color.BLUE })
        bitmap
    }

    val liveMarkPad: Int get() = px(SplitPillSpec.Mark.GLOW_FAR * 2)

    /** The press bloom: pale blue at the centre, blue at its middle stop, nothing at its last. */
    val bloom: Bitmap by lazy {
        val size = px(SplitPillSpec.Bloom.SIZE)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val half = size / 2f
        Canvas(bitmap).drawCircle(
            half,
            half,
            half,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    half,
                    half,
                    half,
                    intArrayOf(SplitPillSpec.Color.BLOOM_CORE, SplitPillSpec.Color.BLOOM_MID, SplitPillSpec.Color.BLUE_CLEAR),
                    floatArrayOf(
                        (SplitPillSpec.Bloom.STOP_CORE / 100).toFloat(),
                        (SplitPillSpec.Bloom.STOP_MID / 100).toFloat(),
                        (SplitPillSpec.Bloom.STOP_CLEAR / 100).toFloat(),
                    ),
                    Shader.TileMode.CLAMP,
                )
            },
        )
        bitmap
    }

    /**
     * The lift shadows round a capsule `pill.open.h` tall, as three slices: both ends scale with
     * the capsule's height (the shadow scales with the pill's size, up to the open pill's own), the
     * middle stretches. A CSS box-shadow blur B is a Gaussian of sigma B / 2; Skia's radius r means
     * sigma 0.57735 r + 0.5. An outer shadow is drawn only outside the box.
     */
    val shadow: PillShadow by lazy {
        val capH = SplitPillSpec.Pill.OPEN_H
        val mid = SHADOW_MID
        val left = px(reachL() + capH / 2)
        val right = left + px(mid)
        val width = right + px(capH / 2 + reachR())
        val height = px(reachT() + capH + reachB())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val capsule = RectF(f(reachL()), f(reachT()), f(reachL() + capH + mid), f(reachT() + capH))
        val r = capsule.height() / 2
        val lifts = listOf(
            doubleArrayOf(SplitPillSpec.Pill.LIFT_X, SplitPillSpec.Pill.LIFT_Y, SplitPillSpec.Pill.LIFT_BLUR) to SplitPillSpec.Pill.LIFT_COLOR,
            doubleArrayOf(SplitPillSpec.Pill.LIFT_TIGHT_X, SplitPillSpec.Pill.LIFT_TIGHT_Y, SplitPillSpec.Pill.LIFT_TIGHT_BLUR) to SplitPillSpec.Pill.LIFT_TIGHT_COLOR,
        )
        lifts.forEach { (l, colour) ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colour
                maskFilter = blur(l[2])
            }
            canvas.drawRoundRect(RectF(capsule).apply { offset(f(l[0]), f(l[1])) }, r, r, p)
        }
        canvas.drawRoundRect(capsule, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        PillShadow(bitmap, left, right, reachL(), reachT(), reachR(), reachB())
    }

    private fun reachL() = maxOf(SplitPillSpec.Pill.LIFT_BLUR - SplitPillSpec.Pill.LIFT_X, SplitPillSpec.Pill.LIFT_TIGHT_BLUR - SplitPillSpec.Pill.LIFT_TIGHT_X)
    private fun reachR() = maxOf(SplitPillSpec.Pill.LIFT_BLUR + SplitPillSpec.Pill.LIFT_X, SplitPillSpec.Pill.LIFT_TIGHT_BLUR + SplitPillSpec.Pill.LIFT_TIGHT_X)
    private fun reachT() = maxOf(SplitPillSpec.Pill.LIFT_BLUR - SplitPillSpec.Pill.LIFT_Y, SplitPillSpec.Pill.LIFT_TIGHT_BLUR - SplitPillSpec.Pill.LIFT_TIGHT_Y)
    private fun reachB() = maxOf(SplitPillSpec.Pill.LIFT_BLUR + SplitPillSpec.Pill.LIFT_Y, SplitPillSpec.Pill.LIFT_TIGHT_BLUR + SplitPillSpec.Pill.LIFT_TIGHT_Y)

    /** A CSS blur of [dp] as a mask filter, or none. */
    private fun blur(dp: Double): BlurMaskFilter? {
        val sigma = dp * scale / 2
        return if (sigma > 0.5) BlurMaskFilter(((sigma - 0.5) / 0.57735).toFloat(), BlurMaskFilter.Blur.NORMAL) else null
    }

    // endregion

    private companion object {
        /** The page's glyphs are drawn in a 24-unit box (SVG `viewBox="0 0 24 24"`). */
        const val GLYPH_BOX = 24.0

        /** The shadow bitmap's stretched middle, dp: any width stretches the same. */
        const val SHADOW_MID = 2.0

        val BOOLS = booleanArrayOf(false, true)

        val loader: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "split-pill-icons").apply { isDaemon = true }
        }
    }
}

/**
 * Which prepared glass a capsule is drawn with. The sheen and the rim are vertical gradients whose
 * stops are shares of the capsule's height, so each height needs its own; heights are taken to the
 * whole screen pixel, and every height the pill can reach - the disc pressed, the open pill pulled
 * into its band - has one made before the first frame.
 *
 * The gradients are drawn in the capsule's own dp, translated and never stretched, so the rim can
 * be a plain stroke of `pill.rim.w`. The review round's first cut filled an even-odd ring path
 * instead and emptied it each frame with `Path.rewind()`, which - unlike `reset()` - puts the fill
 * type back to winding: the ring became the whole capsule in the rim's gradient, a grey wash over
 * the glass with no rim left (the fixture on an emulator, 2026-11-19).
 */
internal object PillGlassPlan {
    /** The tallest capsule drawn, dp: the open pill pulled into its band, with a spring's overshoot. */
    val TALLEST: Double = (SplitPillSpec.Pill.OPEN_H + SplitPillSpec.Pull.BAND_CAP) * OVERSHOOT

    /** The size class of a capsule [hDp] tall at [scale]: its height in whole pixels, at least 1. */
    fun classOf(hDp: Double, scale: Double): Int =
        Math.round(hDp * scale).toInt().coerceIn(1, classes(scale))

    /** The largest size class at [scale]. */
    fun classes(scale: Double): Int = ceil(TALLEST * scale).toInt()

    /** A size class's height, dp: what its gradients span. */
    fun heightOf(cls: Int, scale: Double): Double = cls / scale

    /** Spring overshoot allowed past the pull band's top. */
    private const val OVERSHOOT = 1.25
}

/**
 * The glass's sheen and rim gradients for every size class of [PillGlassPlan], in dp from the
 * capsule's top (`pill.sheen`: white `glass-sheen` to nothing; `pill.rim.stops`: `rim-top`,
 * `rim-mid`, `rim-bot`). Made once in [prepare]; a frame only picks one.
 */
internal class PillGlass(private val scale: Double) {
    private val count = PillGlassPlan.classes(scale)
    private val sheens = arrayOfNulls<LinearGradient>(count + 1)
    private val rims = arrayOfNulls<LinearGradient>(count + 1)

    fun prepare() {
        for (cls in 1..count) {
            val h = PillGlassPlan.heightOf(cls, scale).toFloat()
            sheens[cls] = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(SplitPillSpec.Color.GLASS_SHEEN, SplitPillSpec.Color.GLASS_SHEEN and 0x00ffffff),
                floatArrayOf(0f, (SplitPillSpec.Pill.SHEEN / 100).toFloat()),
                Shader.TileMode.CLAMP,
            )
            rims[cls] = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(SplitPillSpec.Color.RIM_TOP, SplitPillSpec.Color.RIM_MID, SplitPillSpec.Color.RIM_BOT),
                floatArrayOf(
                    (SplitPillSpec.Pill.RIM_STOP_TOP / 100).toFloat(),
                    (SplitPillSpec.Pill.RIM_STOP_MID / 100).toFloat(),
                    (SplitPillSpec.Pill.RIM_STOP_BOT / 100).toFloat(),
                ),
                Shader.TileMode.CLAMP,
            )
        }
    }

    fun sheen(cls: Int): Shader = checkNotNull(sheens[cls]) { "glass not prepared" }

    fun rim(cls: Int): Shader = checkNotNull(rims[cls]) { "glass not prepared" }
}

/** The prepared shadow and where its slices and margins are (px for the slices, dp for the reach). */
internal class PillShadow(
    val bitmap: Bitmap,
    val leftEnd: Int,
    val rightStart: Int,
    val reachL: Double,
    val reachT: Double,
    val reachR: Double,
    val reachB: Double,
)

/**
 * The page's glyphs as straight-line SVG paths (M, L, H, V, Z, absolute and relative), in their
 * 24-unit box: `BTN.plus` and `STAR` of `split-pill.html`, verbatim; `SplitPillGlyphTest` checks
 * they still stand there.
 */
internal object PillGlyphs {
    const val PLUS = "M12 5.5v13M5.5 12h13"
    const val STAR = "M12 3.4l2.62 5.31 5.86.85-4.24 4.13 1 5.84L12 16.77l-5.24 2.76 1-5.84-4.24-4.13 5.86-.85Z"

    /** One subpath: x, y pairs, and whether it closes. */
    class Poly(val xy: FloatArray, val closed: Boolean)

    fun parse(d: String): List<Poly> {
        val out = mutableListOf<Poly>()
        var cur = mutableListOf<Float>()
        var x = 0f
        var y = 0f
        var sx = 0f
        var sy = 0f
        var cmd = ' '
        var pos = 0
        fun skip() {
            while (pos < d.length && (d[pos] == ' ' || d[pos] == ',')) pos++
        }
        fun number(): Float {
            skip()
            val start = pos
            var i = start
            if (i < d.length && (d[i] == '-' || d[i] == '+')) i++
            var dot = false
            while (i < d.length) {
                val c = d[i]
                if (c in '0'..'9') {
                    i++
                } else if (c == '.' && !dot) {
                    dot = true
                    i++
                } else {
                    break
                }
            }
            require(i > start) { "svg: number expected at $start in $d" }
            pos = i
            return d.substring(start, i).toFloat()
        }
        while (true) {
            skip()
            if (pos >= d.length) break
            val c = d[pos]
            if (c.isLetter()) {
                cmd = c
                pos++
                if (cmd == 'Z' || cmd == 'z') {
                    if (cur.isNotEmpty()) out += Poly(cur.toFloatArray(), true)
                    cur = mutableListOf()
                    x = sx
                    y = sy
                }
                continue
            }
            when (cmd) {
                'M', 'm' -> {
                    var nx = number()
                    var ny = number()
                    if (cmd == 'm') {
                        nx += x
                        ny += y
                    }
                    if (cur.isNotEmpty()) out += Poly(cur.toFloatArray(), false)
                    cur = mutableListOf(nx, ny)
                    x = nx
                    y = ny
                    sx = nx
                    sy = ny
                    cmd = if (cmd == 'm') 'l' else 'L'
                }
                'L', 'l' -> {
                    var nx = number()
                    var ny = number()
                    if (cmd == 'l') {
                        nx += x
                        ny += y
                    }
                    x = nx
                    y = ny
                    cur += x
                    cur += y
                }
                'H', 'h' -> {
                    val nx = number()
                    x = if (cmd == 'h') x + nx else nx
                    cur += x
                    cur += y
                }
                'V', 'v' -> {
                    val ny = number()
                    y = if (cmd == 'v') y + ny else ny
                    cur += x
                    cur += y
                }
                else -> throw IllegalArgumentException("svg: command $cmd in $d")
            }
        }
        if (cur.isNotEmpty()) out += Poly(cur.toFloatArray(), false)
        return out
    }

    /** The glyph as an Android path, each unit [unit] px, its box's corner at ([ox], [oy]). */
    fun path(d: String, unit: Float, ox: Float, oy: Float): Path {
        val path = Path()
        parse(d).forEach { poly ->
            var k = 0
            while (k < poly.xy.size) {
                val px = ox + poly.xy[k] * unit
                val py = oy + poly.xy[k + 1] * unit
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                k += 2
            }
            if (poly.closed) path.close()
        }
        return path
    }
}
