package dev.denza.apps.feature.trip

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The whole current trip as one growing thread, drawn into a caller-given rect
 * (the centre of the collapsed trip panel).
 *
 * Layout: the horizontal axis is travelled distance at a FIXED scale
 * ([FULL_WIDTH_METERS] across the rect), so the thread starts at the left edge
 * and creeps right at a rate you can feel. Only once the trip outgrows the rect
 * does the scale compress, keeping the whole journey visible.
 *
 * Shape: the baseline is the elevation profile, smoothed and normalised over the
 * whole trip against a [MIN_SPAN_METERS] minimum span — that minimum is what
 * keeps flat terrain from being amplified into an oscilloscope trace. On top of
 * it ride the real turns (gyro heading minus its slow average, smoothed the same
 * way) and a small jitter from body motion.
 *
 * Weight: the core is a single filled outline whose half-width follows body
 * energy, and the halo is a few continuous strokes of the same centreline. Both
 * matter — stroking the thread segment by segment made round caps overlap and
 * beaded the line.
 *
 * Sparks fly radially: a burst when a real event fires, a light continuous
 * scatter on rough roads. Nothing here is persisted; the thread dies with the
 * session.
 */
class JourneyThreadDrawer {

    private val elapsed = FloatArray(ROUTE_MAX)
    private val dist = FloatArray(ROUTE_MAX)
    private val alt = FloatArray(ROUTE_MAX)
    private val color = FloatArray(ROUTE_MAX)
    private val energy = FloatArray(ROUTE_MAX)
    private val turn = FloatArray(ROUTE_MAX)
    private val calm = FloatArray(ROUTE_MAX)
    private val smoothAlt = FloatArray(ROUTE_MAX)
    private val smoothTurn = FloatArray(ROUTE_MAX)
    private val xs = FloatArray(ROUTE_MAX)
    private val ys = FloatArray(ROUTE_MAX)
    private val halfWidth = FloatArray(ROUTE_MAX)

    private val centre = Path()
    private val core = Path()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }

    // Radial sparks (structure of arrays: nothing is allocated per frame).
    private val pX = FloatArray(MAX_SPARKS)
    private val pY = FloatArray(MAX_SPARKS)
    private val pVX = FloatArray(MAX_SPARKS)
    private val pVY = FloatArray(MAX_SPARKS)
    private val pLife = FloatArray(MAX_SPARKS)
    private val pDecay = FloatArray(MAX_SPARKS)
    private val pSize = FloatArray(MAX_SPARKS)
    private var sparkCount = 0

    private val waveX = FloatArray(MAX_WAVES)
    private val waveY = FloatArray(MAX_WAVES)
    private val waveR = FloatArray(MAX_WAVES)
    private val waveLife = FloatArray(MAX_WAVES)
    private var waveCount = 0

    private var lastEventSeconds = -1.0
    private var headX = 0f
    private var headY = 0f

    // Rect and scale of the current frame (set at the top of draw()).
    private var left = 0f
    private var right = 0f
    private var top = 0f
    private var bottom = 0f
    private var unit = 1f

    // Cached core gradient; rebuilt only when its ends actually move.
    private var gradient: LinearGradient? = null
    private var gradFrom = 0
    private var gradTo = 0
    private var gradX0 = -1f
    private var gradX1 = -1f

    fun draw(
        canvas: Canvas,
        engine: TripEngine,
        frameTimeSec: Double,
        dtSec: Double,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        unit: Float,
    ) {
        this.left = left
        this.right = right
        this.top = top
        this.bottom = bottom
        this.unit = unit
        val count = engine.copyRouteInto(elapsed, dist, alt, color, energy, turn, calm)

        if (count < 3) {
            advanceSparks(dtSec)
            if (engine.hasFix()) {
                label(
                    canvas, "нить строится", (left + right) / 2f, (top + bottom) / 2f, 18f,
                    TripPalette.alpha(TripPalette.MUTED, 0.6f), Paint.Align.CENTER,
                )
            }
            return
        }

        smooth(smoothAlt, alt, count, ALT_SMOOTH_RADIUS, ALT_SMOOTH_PASSES)
        smooth(smoothTurn, turn, count, TURN_SMOOTH_RADIUS, TURN_SMOOTH_PASSES)
        layout(count)

        val tint = TripPalette.colorAt(color[count - 1].toDouble())
        val calmNow = calm[count - 1]
        drawHalo(canvas, count, tint, calmNow)
        drawCore(canvas, count, tint)
        drawWaves(canvas)
        drawEvents(canvas, engine, count)
        spawnForNewEvent(engine)
        emitAmbient(engine, dtSec)
        advanceSparks(dtSec)
        drawSparks(canvas)
        drawHead(canvas, engine, tint, frameTimeSec)
    }

    /** Scale a size authored in virtual (360-tall) units to canvas pixels. */
    private fun vs(size: Float): Float = size * unit

    private fun label(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        sizeV: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        labelPaint.textSize = vs(sizeV)
        labelPaint.color = color
        labelPaint.textAlign = align
        canvas.drawText(text, x, y, labelPaint)
    }

    /** Distance-based x at a fixed scale until the trip outgrows the rect. */
    private fun layout(count: Int) {
        val band = bottom - top

        var minAlt = Float.MAX_VALUE
        var maxAlt = -Float.MAX_VALUE
        for (i in 0 until count) {
            minAlt = min(minAlt, smoothAlt[i])
            maxAlt = max(maxAlt, smoothAlt[i])
        }
        val mid = (minAlt + maxAlt) / 2f
        val span = ThreadLayout.bandSpan(minAlt, maxAlt, MIN_SPAN_METERS)

        val travelled = (dist[count - 1] - dist[0]).coerceAtLeast(0f)
        val scale = ThreadLayout.horizontalScale(travelled, right - left, FULL_WIDTH_METERS)

        for (i in 0 until count) {
            xs[i] = left + (dist[i] - dist[0]) * scale
            val elevation = bottom - (0.5f + (smoothAlt[i] - mid) / span) * band
            val wiggle = (smoothTurn[i] * TURN_GAIN).coerceIn(-1f, 1f) * band * TURN_AMPLITUDE
            val jitter = (Math.random().toFloat() - 0.5f) * min(vs(2f), energy[i] * vs(1.1f))
            ys[i] = elevation + wiggle + jitter
            halfWidth[i] = vs(1f) + energy[i] * vs(2.4f)
        }
        headX = xs[count - 1]
        headY = ys[count - 1]
    }

    /** One continuous curve through all points — never per-segment, or it beads. */
    private fun buildCentre(count: Int) {
        centre.rewind()
        centre.moveTo(xs[0], ys[0])
        for (i in 1 until count - 1) {
            centre.quadTo(xs[i], ys[i], (xs[i] + xs[i + 1]) / 2f, (ys[i] + ys[i + 1]) / 2f)
        }
        centre.lineTo(xs[count - 1], ys[count - 1])
    }

    private fun drawHalo(canvas: Canvas, count: Int, tint: Int, calmNow: Float) {
        buildCentre(count)
        stroke.style = Paint.Style.STROKE
        stroke.shader = null
        for (pass in 0 until HALO_WIDTHS.size) {
            stroke.color = TripPalette.alpha(tint, HALO_ALPHAS[pass] + calmNow * HALO_CALM_BOOST[pass])
            stroke.strokeWidth = vs(HALO_WIDTHS[pass])
            canvas.drawPath(centre, stroke)
        }
    }

    private fun drawCore(canvas: Canvas, count: Int, tint: Int) {
        core.rewind()
        core.moveTo(xs[0], ys[0] - halfWidth[0])
        for (i in 1 until count) core.lineTo(xs[i], ys[i] - halfWidth[i])
        for (i in count - 1 downTo 0) core.lineTo(xs[i], ys[i] + halfWidth[i])
        core.close()

        val from = TripPalette.alpha(TripPalette.colorAt(color[0].toDouble()), 0.35f)
        val to = TripPalette.alpha(tint, 0.95f)
        if (gradient == null || from != gradFrom || to != gradTo ||
            xs[0] != gradX0 || xs[count - 1] != gradX1
        ) {
            gradient = LinearGradient(
                xs[0], 0f, max(xs[count - 1], xs[0] + 1f), 0f,
                from, to, Shader.TileMode.CLAMP,
            )
            gradFrom = from
            gradTo = to
            gradX0 = xs[0]
            gradX1 = xs[count - 1]
        }
        fill.style = Paint.Style.FILL
        fill.shader = gradient
        canvas.drawPath(core, fill)
        fill.shader = null
    }

    private fun drawEvents(canvas: Canvas, engine: TripEngine, count: Int) {
        val n = engine.eventCount()
        for (index in 0 until n) {
            val event = engine.eventAt(index)
            val text = when (event.kind) {
                TripEventKind.CLIMB -> "подъём +${event.value.roundToInt()} м"
                TripEventKind.STOP -> "остановка ${event.value.roundToInt()} мин"
                TripEventKind.SERPENTINE -> "серпантин"
                TripEventKind.CALM -> "ровный участок"
                else -> continue
            }
            // Anchor to the thread point the car was at when the event fired.
            val at = ThreadLayout.anchorIndex(elapsed, count, event.bornElapsedSeconds)
            val x = xs[at].coerceIn(left + vs(70f), right - vs(70f))
            val y = if (event.lane == 0) ys[at] - vs(24f) else ys[at] + vs(34f)
            label(canvas, text, x, y, 17f, TripPalette.alpha(TripPalette.MUTED, 0.85f), Paint.Align.CENTER)
            if (event.kind == TripEventKind.STOP) {
                stroke.style = Paint.Style.STROKE
                stroke.shader = null
                stroke.color = TripPalette.alpha(STOP_RING, 0.8f)
                stroke.strokeWidth = vs(2f)
                canvas.drawCircle(xs[at], ys[at], vs(8f), stroke)
            }
        }
    }

    private fun spawnForNewEvent(engine: TripEngine) {
        val n = engine.eventCount()
        if (n == 0) return
        val newest = engine.eventAt(n - 1)
        if (newest.bornElapsedSeconds <= lastEventSeconds) return
        lastEventSeconds = newest.bornElapsedSeconds
        when (newest.kind) {
            TripEventKind.CLIMB, TripEventKind.STOP,
            TripEventKind.SERPENTINE, TripEventKind.CREST,
            -> {
                emit(EVENT_SPARKS, 1.5f)
                addWave()
            }
            else -> Unit
        }
    }

    private fun emitAmbient(engine: TripEngine, dtSec: Double) {
        val rough = (engine.latestAgitation / AMBIENT_FULL_SCALE).coerceIn(0.0, 1.0)
        if (Math.random() < rough * dtSec * AMBIENT_RATE) emit(1, 0.5f)
    }

    private fun emit(n: Int, power: Float) {
        for (i in 0 until n) {
            if (sparkCount >= MAX_SPARKS) return
            val angle = Math.random() * 2.0 * Math.PI
            val speed = (vs(24f) + Math.random().toFloat() * vs(120f)) * power
            pX[sparkCount] = headX
            pY[sparkCount] = headY
            pVX[sparkCount] = (Math.cos(angle).toFloat()) * speed
            pVY[sparkCount] = (Math.sin(angle).toFloat()) * speed * 0.8f
            pLife[sparkCount] = 1f
            pDecay[sparkCount] = 0.45f + Math.random().toFloat() * 0.5f
            pSize[sparkCount] = vs(1.5f) + Math.random().toFloat() * vs(2.4f)
            sparkCount++
        }
    }

    private fun advanceSparks(dtSec: Double) {
        val dt = dtSec.coerceIn(0.0, 0.08).toFloat()
        val drag = Math.pow(0.62, dt.toDouble()).toFloat()
        var i = 0
        while (i < sparkCount) {
            pVX[i] *= drag
            pVY[i] = pVY[i] * drag + vs(34f) * dt
            pX[i] += pVX[i] * dt
            pY[i] += pVY[i] * dt
            pLife[i] -= dt * pDecay[i]
            if (pLife[i] <= 0f) {
                sparkCount--
                pX[i] = pX[sparkCount]; pY[i] = pY[sparkCount]
                pVX[i] = pVX[sparkCount]; pVY[i] = pVY[sparkCount]
                pLife[i] = pLife[sparkCount]; pDecay[i] = pDecay[sparkCount]
                pSize[i] = pSize[sparkCount]
            } else {
                i++
            }
        }
        var j = 0
        while (j < waveCount) {
            waveR[j] += vs(230f) * dt
            waveLife[j] -= dt * 1.4f
            if (waveLife[j] <= 0f) {
                waveCount--
                waveX[j] = waveX[waveCount]; waveY[j] = waveY[waveCount]
                waveR[j] = waveR[waveCount]; waveLife[j] = waveLife[waveCount]
            } else {
                j++
            }
        }
    }

    private fun drawSparks(canvas: Canvas) {
        fill.style = Paint.Style.FILL
        fill.shader = null
        for (i in 0 until sparkCount) {
            val a = pLife[i].coerceIn(0f, 1f)
            fill.color = TripPalette.alpha(SPARK, a * 0.9f)
            canvas.drawCircle(pX[i], pY[i], pSize[i] * 0.5f * (0.45f + a * 0.55f), fill)
        }
    }

    private fun addWave() {
        if (waveCount >= MAX_WAVES) return
        waveX[waveCount] = headX
        waveY[waveCount] = headY
        waveR[waveCount] = vs(5f)
        waveLife[waveCount] = 1f
        waveCount++
    }

    private fun drawWaves(canvas: Canvas) {
        stroke.style = Paint.Style.STROKE
        stroke.shader = null
        stroke.strokeWidth = vs(1.2f)
        for (i in 0 until waveCount) {
            stroke.color = TripPalette.alpha(TripPalette.AMBER, waveLife[i] * 0.32f)
            canvas.drawCircle(waveX[i], waveY[i], waveR[i], stroke)
        }
    }

    private fun drawHead(canvas: Canvas, engine: TripEngine, tint: Int, frameTimeSec: Double) {
        val e = engine.verticalEnergy().toFloat()
        fill.style = Paint.Style.FILL
        fill.shader = null
        fill.color = tint
        fill.setShadowLayer(vs(20f), 0f, 0f, tint)
        canvas.drawCircle(headX, headY, vs(4.2f) + min(vs(3f), e * vs(1.5f)), fill)
        fill.clearShadowLayer()
        stroke.style = Paint.Style.STROKE
        stroke.color = TripPalette.alpha(tint, 0.28f)
        stroke.strokeWidth = vs(1.4f)
        val halo = vs(13f) + (sin(frameTimeSec * 3.0).toFloat() * vs(2.5f)) + min(vs(9f), e * vs(6f))
        canvas.drawCircle(headX, headY, halo, stroke)
    }

    /** Box blur then binomial passes — the same treatment for altitude and turns. */
    private fun smooth(out: FloatArray, src: FloatArray, count: Int, radius: Int, passes: Int) {
        for (i in 0 until count) {
            var sum = 0f
            var used = 0
            for (k in -radius..radius) {
                val j = i + k
                if (j < 0 || j >= count) continue
                sum += src[j]
                used++
            }
            out[i] = sum / used
        }
        repeat(passes) {
            for (i in 1 until count - 1) {
                out[i] = (out[i - 1] + out[i] * 2f + out[i + 1]) / 4f
            }
        }
    }

    private companion object {
        const val ROUTE_MAX = 1200
        const val MAX_SPARKS = 140
        const val MAX_WAVES = 6
        const val EVENT_SPARKS = 20

        /** Travelled distance that fills the rect before the thread compresses. */
        const val FULL_WIDTH_METERS = 30_000f

        /** Elevation band never reads narrower than this, so flat roads stay calm. */
        const val MIN_SPAN_METERS = 60f

        const val ALT_SMOOTH_RADIUS = 3
        const val ALT_SMOOTH_PASSES = 2
        const val TURN_SMOOTH_RADIUS = 2
        const val TURN_SMOOTH_PASSES = 3
        const val TURN_GAIN = 1.3f
        const val TURN_AMPLITUDE = 0.11f

        const val AMBIENT_FULL_SCALE = 2.6
        const val AMBIENT_RATE = 30.0

        val HALO_WIDTHS = floatArrayOf(26f, 15f, 7f)
        val HALO_ALPHAS = floatArrayOf(0.030f, 0.048f, 0.075f)
        val HALO_CALM_BOOST = floatArrayOf(0.030f, 0.035f, 0f)

        val SPARK = 0xFFFFDE9B.toInt()
        val STOP_RING = 0xFFFF9E7A.toInt()
    }
}
