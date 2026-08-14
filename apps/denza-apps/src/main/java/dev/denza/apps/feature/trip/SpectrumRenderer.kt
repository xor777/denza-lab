package dev.denza.apps.feature.trip

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The spectrum analyser that fills the panel, drawn into a caller-given rect.
 *
 * Built the way a demo would build it, in layers: a bloom behind the bars that
 * breathes with the music, the bars themselves under one shared vertical
 * gradient, a mirrored reflection below the baseline, peak markers that hang and
 * then fall, and a scanline overlay across the lot.
 *
 * The scanlines do double duty and are the reason the bars read as segmented LED
 * columns. They are a single tiled bitmap drawn once per frame in near-black:
 * over the panel's own near-black background they are invisible, and over a lit
 * bar they cut it into segments. Drawing real segment rectangles instead would
 * cost a rectangle per segment per band, every frame.
 *
 * Colour is keyed to absolute height, not to each bar's own height: the gradient
 * is anchored once from the baseline to the ceiling, so a short bar shows only
 * its cool end and only a bar that genuinely peaks reaches the hot end. That is
 * what makes the display readable at a glance instead of a wall of colour.
 */
class SpectrumRenderer {

    private val magnitudes = DoubleArray(SpectrumSource.BAND_COUNT)
    private val targets = FloatArray(SpectrumSource.BAND_COUNT)
    private val dynamics = SpectrumDynamics(SpectrumSource.BAND_COUNT)
    private val levels = SpectrumLevels(SpectrumSource.BAND_COUNT)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.SANS_SERIF
        textAlign = Paint.Align.CENTER
    }
    private val bar = RectF()
    private val glyph = Path()
    private val idlePath = Path()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.SANS_SERIF
        textAlign = Paint.Align.LEFT
    }

    /** Tap targets for the transport controls, in canvas coordinates. */
    private val controlBounds = Array(3) { RectF() }

    private var barShader: LinearGradient? = null
    private var reflectShader: LinearGradient? = null
    private var bloomShader: LinearGradient? = null
    private var idleShader: LinearGradient? = null
    private var scanShader: BitmapShader? = null
    private var scanlines: Bitmap? = null

    private var laidOutLeft = Float.NaN
    private var laidOutRight = Float.NaN
    private var laidOutTop = Float.NaN
    private var laidOutBottom = Float.NaN
    private var laidOutStrip = false
    private var preparedMap = false
    private var idlePhase = 0.0
    private var marqueePhase = 0f
    private var labelTitle: String? = null
    private var labelArtist: String? = null
    private var labelTextSize = 0f
    private var trackLabel = ""
    private var trackLabelWidth = 0f

    private var baselineY = 0f
    private var barTopY = 0f
    private var reflectBottomY = 0f

    fun draw(
        canvas: Canvas,
        source: SpectrumSource,
        nowPlaying: NowPlayingSource,
        frameTimeSec: Double,
        dtSec: Double,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        unit: Float,
    ) {
        val strip = nowPlaying.hasTrack
        layout(left, right, top, bottom, unit, strip)
        if (!preparedMap) {
            val map = source.centreHz
            if (map != null) {
                levels.prepare(
                    SpectrumBandMap(
                        bandCount = SpectrumSource.BAND_COUNT,
                        captureSize = FFT_CAPTURE_SIZE,
                        sampleRateHz = SpectrumSource.CALIBRATED_RATE_HZ,
                        minHz = SpectrumSource.MIN_HZ,
                        maxHz = SpectrumSource.MAX_HZ,
                    ),
                )
                preparedMap = true
            }
        }

        val fresh = source.snapshot(magnitudes)
        val playing = if (fresh) {
            levels.normalise(magnitudes, targets, dtSec)
            levels.hasSignal()
        } else {
            false
        }
        if (playing) {
            dynamics.update(targets, dtSec)
        } else {
            dynamics.settle(dtSec)
        }

        var sum = 0f
        for (value in dynamics.bars) sum += value
        val energy = sum / SpectrumSource.BAND_COUNT

        drawBloom(canvas, left, right, energy)
        drawGrid(canvas, left, right, unit)
        drawBars(canvas, left, right, unit)
        drawBaseline(canvas, left, right, unit, energy)
        drawScanlines(canvas, left, right, top, unit)
        if (strip) {
            drawNowPlaying(canvas, nowPlaying, left, right, bottom, unit, dtSec)
        } else {
            controlBounds.forEach { it.setEmpty() }
        }
        if (!playing) {
            drawIdle(canvas, source, left, right, frameTimeSec, dtSec, unit)
        }
    }

    /** Rebuilds the geometry-dependent shaders only when the panel size changes. */
    private fun layout(
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        unit: Float,
        strip: Boolean,
    ) {
        // Compared field by field rather than through a formatted key: this runs
        // on every frame, and building a String here was 30 short-lived objects a
        // second for a check that almost always says "unchanged".
        if (left == laidOutLeft && right == laidOutRight && top == laidOutTop &&
            bottom == laidOutBottom && strip == laidOutStrip
        ) {
            return
        }
        laidOutLeft = left
        laidOutRight = right
        laidOutTop = top
        laidOutBottom = bottom
        laidOutStrip = strip

        // With no track to show there is nothing to reserve room for, so the
        // analyser runs all the way down to the panel's edge instead of leaving
        // an empty band.
        val axisSpace = if (strip) STRIP_UNITS * unit else 0f
        baselineY = top + (bottom - axisSpace - top) * BASELINE_FRACTION
        barTopY = top
        reflectBottomY = bottom - axisSpace

        barShader = LinearGradient(
            0f, baselineY, 0f, barTopY,
            intArrayOf(DEEP, MINT, AMBER, CORAL),
            floatArrayOf(0f, 0.3f, 0.68f, 1f),
            Shader.TileMode.CLAMP,
        )
        reflectShader = LinearGradient(
            0f, baselineY, 0f, reflectBottomY,
            intArrayOf(alpha(MINT, 0.34f), alpha(AMBER, 0.12f), alpha(CORAL, 0f)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        bloomShader = LinearGradient(
            0f, baselineY, 0f, barTopY,
            intArrayOf(alpha(MINT, 0.20f), alpha(MINT, 0.05f), alpha(MINT, 0f)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        idleShader = LinearGradient(
            left, 0f, right, 0f,
            intArrayOf(alpha(MINT, 0f), alpha(MINT, 0.22f), alpha(MINT, 0f)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        buildScanlines(unit)
    }

    /**
     * A 1-pixel-wide tile: transparent, with a dark band at its foot. Tiled down
     * the panel it becomes the scanline/LED grid.
     */
    private fun buildScanlines(unit: Float) {
        val pitch = (SCAN_PITCH_UNITS * unit).roundToInt().coerceAtLeast(3)
        val dark = (pitch * SCAN_DARK_FRACTION).roundToInt().coerceIn(1, pitch - 1)
        scanlines?.recycle()
        val bitmap = Bitmap.createBitmap(1, pitch, Bitmap.Config.ARGB_8888)
        val tileCanvas = Canvas(bitmap)
        tileCanvas.drawColor(Color.TRANSPARENT)
        val tilePaint = Paint()
        tilePaint.color = alpha(Color.BLACK, SCAN_ALPHA)
        tileCanvas.drawRect(0f, (pitch - dark).toFloat(), 1f, pitch.toFloat(), tilePaint)
        scanlines = bitmap
        scanShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.REPEAT)
    }

    private fun drawBloom(canvas: Canvas, left: Float, right: Float, energy: Float) {
        val shader = bloomShader ?: return
        fill.shader = shader
        fill.alpha = (BLOOM_BASE_ALPHA + energy * BLOOM_GAIN).coerceIn(0f, 1f).times(255).toInt()
        canvas.drawRect(left, barTopY, right, baselineY, fill)
        fill.shader = null
        fill.alpha = 255
    }

    /** Faint horizontal rules, so tall bars can be judged against something. */
    private fun drawGrid(canvas: Canvas, left: Float, right: Float, unit: Float) {
        stroke.shader = null
        stroke.strokeWidth = unit * 1f
        stroke.color = alpha(MUTED, 0.10f)
        var step = 1
        while (step <= 3) {
            val y = baselineY - (baselineY - barTopY) * (step / 4f)
            canvas.drawLine(left, y, right, y, stroke)
            step++
        }
    }

    private fun drawBars(canvas: Canvas, left: Float, right: Float, unit: Float) {
        val bandCount = SpectrumSource.BAND_COUNT
        val width = (right - left) / bandCount * BAR_WIDTH_FRACTION
        // Bars are spread so the first one starts exactly on the left edge and
        // the last ends exactly on the right, with the spacing living only
        // between them. Laying them out on fixed slots left half a gap at each
        // end, which read as the whole analyser sitting inset from the panel.
        val step = if (bandCount > 1) (right - left - width) / (bandCount - 1) else 0f
        val radius = width * 0.22f
        val fullHeight = baselineY - barTopY
        val stub = unit * 2.2f
        val reflectHeight = reflectBottomY - baselineY

        for (band in 0 until bandCount) {
            val x = left + step * band
            val level = dynamics.bars[band]
            val height = (fullHeight * level).coerceAtLeast(stub)

            // Reflection first, so the bar's rounded foot sits over it.
            reflectShader?.let { shader ->
                fill.shader = shader
                bar.set(x, baselineY, x + width, baselineY + reflectHeight * level * REFLECT_FRACTION)
                canvas.drawRoundRect(bar, radius, radius, fill)
            }

            barShader?.let { shader ->
                fill.shader = shader
                bar.set(x, baselineY - height, x + width, baselineY)
                canvas.drawRoundRect(bar, radius, radius, fill)
            }

            // A brighter cap sitting on the column keeps the leading edge legible
            // once the scanlines have cut the body into segments.
            fill.shader = null
            fill.color = alpha(Color.WHITE, 0.30f)
            bar.set(x, baselineY - height, x + width, baselineY - height + unit * 2f)
            canvas.drawRoundRect(bar, radius, radius, fill)

            val peak = dynamics.peaks[band]
            if (peak > 0.02f) {
                val peakY = baselineY - fullHeight * peak
                fill.color = alpha(peakColour(peak), 0.9f)
                bar.set(x, peakY - unit * 1.6f, x + width, peakY + unit * 1.0f)
                canvas.drawRoundRect(bar, radius * 0.5f, radius * 0.5f, fill)
            }
        }
        fill.shader = null
        fill.color = Color.WHITE
    }

    private fun drawBaseline(canvas: Canvas, left: Float, right: Float, unit: Float, energy: Float) {
        stroke.strokeWidth = unit * 1.6f
        stroke.color = alpha(MINT, (0.28f + energy * 0.5f).coerceIn(0f, 0.85f))
        canvas.drawLine(left, baselineY, right, baselineY, stroke)
    }

    private fun drawScanlines(canvas: Canvas, left: Float, right: Float, top: Float, unit: Float) {
        val shader = scanShader ?: return
        fill.shader = shader
        fill.alpha = 255
        canvas.drawRect(left, top, right, reflectBottomY, fill)
        fill.shader = null
    }

    /** Which transport control a touch landed on, if any. */
    enum class Control { PREVIOUS, TOGGLE, NEXT }

    /**
     * Maps a touch to a control. Uses the bounds recorded by the last frame, so
     * it always matches what the driver can actually see.
     */
    fun hitTest(x: Float, y: Float): Control? {
        if (controlBounds[0].contains(x, y)) return Control.PREVIOUS
        if (controlBounds[1].contains(x, y)) return Control.TOGGLE
        if (controlBounds[2].contains(x, y)) return Control.NEXT
        return null
    }

    /**
     * The track strip: transport controls, then the title, scrolled only when it
     * is genuinely too long. A marquee that runs when it does not need to is
     * just movement in the corner of a driver's eye.
     */
    private fun drawNowPlaying(
        canvas: Canvas,
        nowPlaying: NowPlayingSource,
        left: Float,
        right: Float,
        bottom: Float,
        unit: Float,
        dtSec: Double,
    ) {
        val stripTop = bottom - STRIP_UNITS * unit
        val centreY = (stripTop + bottom) / 2f
        val target = unit * 42f
        val step = target + unit * 8f
        var x = left + unit * 2f
        for (index in 0..2) {
            controlBounds[index].set(x, centreY - target / 2f, x + target, centreY + target / 2f)
            x += step
        }

        fill.shader = null
        fill.color = alpha(INK, 0.82f)
        drawSkipGlyph(canvas, controlBounds[0], unit, forward = false)
        drawToggleGlyph(canvas, controlBounds[1], unit, nowPlaying.playing)
        drawSkipGlyph(canvas, controlBounds[2], unit, forward = true)

        val textLeft = x + unit * 14f
        if (textLeft >= right) return
        stroke.shader = null
        stroke.strokeWidth = unit * 1f
        stroke.color = alpha(MUTED, 0.18f)
        canvas.drawLine(
            textLeft - unit * 8f, centreY - target * 0.42f,
            textLeft - unit * 8f, centreY + target * 0.42f, stroke,
        )

        trackPaint.textSize = unit * 24f
        trackPaint.color = alpha(INK, if (nowPlaying.playing) 0.95f else 0.6f)
        // Composed and measured only when the track actually changes. Doing it
        // per frame meant a fresh String plus a full text-shaping pass 30 times a
        // second to answer a question whose answer changes once a song.
        val text = trackLabel(nowPlaying, trackPaint.textSize)
        val textWidth = trackLabelWidth
        val available = right - textLeft
        val baseline = centreY + unit * 8f

        canvas.save()
        canvas.clipRect(textLeft, stripTop, right, bottom)
        if (textWidth <= available) {
            marqueePhase = 0f
            canvas.drawText(text, textLeft, baseline, trackPaint)
        } else {
            val gap = unit * MARQUEE_GAP_UNITS
            val cycle = textWidth + gap
            marqueePhase += (dtSec * unit * MARQUEE_SPEED_UNITS_PER_SEC).toFloat()
            if (marqueePhase >= cycle) marqueePhase -= cycle
            canvas.drawText(text, textLeft - marqueePhase, baseline, trackPaint)
            canvas.drawText(text, textLeft - marqueePhase + cycle, baseline, trackPaint)
        }
        canvas.restore()
    }

    private fun trackLabel(nowPlaying: NowPlayingSource, textSize: Float): String {
        if (nowPlaying.title == labelTitle &&
            nowPlaying.artist == labelArtist &&
            textSize == labelTextSize
        ) {
            return trackLabel
        }
        labelTitle = nowPlaying.title
        labelArtist = nowPlaying.artist
        labelTextSize = textSize
        val title = labelTitle.orEmpty()
        val artist = labelArtist.orEmpty()
        trackLabel = if (artist.isBlank()) title else "$title \u00b7 $artist"
        trackLabelWidth = trackPaint.measureText(trackLabel)
        return trackLabel
    }

    private fun drawToggleGlyph(canvas: Canvas, bounds: RectF, unit: Float, playing: Boolean) {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val size = bounds.height() * 0.42f
        if (playing) {
            val barWidth = size * 0.34f
            bar.set(cx - size * 0.55f, cy - size, cx - size * 0.55f + barWidth, cy + size)
            canvas.drawRoundRect(bar, unit * 1.5f, unit * 1.5f, fill)
            bar.set(cx + size * 0.55f - barWidth, cy - size, cx + size * 0.55f, cy + size)
            canvas.drawRoundRect(bar, unit * 1.5f, unit * 1.5f, fill)
        } else {
            glyph.rewind()
            glyph.moveTo(cx - size * 0.5f, cy - size)
            glyph.lineTo(cx + size * 0.8f, cy)
            glyph.lineTo(cx - size * 0.5f, cy + size)
            glyph.close()
            canvas.drawPath(glyph, fill)
        }
    }

    private fun drawSkipGlyph(canvas: Canvas, bounds: RectF, unit: Float, forward: Boolean) {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val size = bounds.height() * 0.34f
        val direction = if (forward) 1f else -1f
        glyph.rewind()
        for (wedge in 0..1) {
            val offset = (wedge - 0.5f) * size * 1.05f * direction
            glyph.moveTo(cx + offset - size * 0.5f * direction, cy - size)
            glyph.lineTo(cx + offset + size * 0.5f * direction, cy)
            glyph.lineTo(cx + offset - size * 0.5f * direction, cy + size)
            glyph.close()
        }
        canvas.drawPath(glyph, fill)
        bar.set(
            cx + direction * size * 1.35f - unit * 1.6f, cy - size,
            cx + direction * size * 1.35f + unit * 1.6f, cy + size,
        )
        canvas.drawRoundRect(bar, unit * 1.2f, unit * 1.2f, fill)
    }

    /** A slow travelling ripple, so a silent car still shows a living panel. */
    private fun drawIdle(
        canvas: Canvas,
        source: SpectrumSource,
        left: Float,
        right: Float,
        frameTimeSec: Double,
        dtSec: Double,
        unit: Float,
    ) {
        idlePhase += dtSec
        // One stroked path under a horizontal gradient, rather than a line
        // segment per step with its own colour. The idle state is when the panel
        // should be at its cheapest, and it was costing more draw calls than the
        // active one.
        val amplitude = unit * 5f
        val stepX = (right - left) / IDLE_STEPS
        idlePath.rewind()
        var index = 0
        while (index <= IDLE_STEPS) {
            val u = index.toFloat() / IDLE_STEPS
            val wave = sin(frameTimeSec * 1.1 + u * 7.0) * sin(frameTimeSec * 0.37 + u * 2.0)
            val x = left + stepX * index
            val y = baselineY - (wave * amplitude).toFloat()
            if (index == 0) idlePath.moveTo(x, y) else idlePath.lineTo(x, y)
            index++
        }
        stroke.strokeWidth = unit * 1.4f
        stroke.shader = idleShader
        stroke.alpha = 255
        canvas.drawPath(idlePath, stroke)
        stroke.shader = null

        val failure = source.lastFailure
        if (failure != null) {
            labelPaint.textSize = unit * 15f
            labelPaint.color = alpha(MUTED, 0.7f)
            canvas.drawText(failure, (left + right) / 2f, baselineY - unit * 26f, labelPaint)
        }
    }

    private fun peakColour(peak: Float): Int = when {
        peak > 0.82f -> CORAL
        peak > 0.5f -> AMBER
        else -> MINT
    }

    private fun alpha(color: Int, a: Float): Int {
        val clamped = (a.coerceIn(0f, 1f) * 255).toInt()
        return (color and 0x00FFFFFF) or (clamped shl 24)
    }

    private companion object {
        /** Matches the Visualizer's maximum capture size, used for the axis map. */
        const val FFT_CAPTURE_SIZE = 1024

        const val BASELINE_FRACTION = 0.70f
        const val BAR_WIDTH_FRACTION = 0.66f
        const val REFLECT_FRACTION = 1.0f
        const val STRIP_UNITS = 62f
        const val MARQUEE_SPEED_UNITS_PER_SEC = 42f
        const val MARQUEE_GAP_UNITS = 90f
        const val SCAN_PITCH_UNITS = 7f
        const val SCAN_DARK_FRACTION = 0.34f
        const val SCAN_ALPHA = 0.42f
        const val BLOOM_BASE_ALPHA = 0.25f
        const val BLOOM_GAIN = 1.4f
        const val IDLE_STEPS = 72

        val DEEP = 0xFF2E8F76.toInt()
        val MINT = 0xFF73E0BD.toInt()
        val AMBER = 0xFFF2C46D.toInt()
        val CORAL = 0xFFFF9E7A.toInt()
        val MUTED = 0xFF9AA7AD.toInt()
        val INK = 0xFFF3F7F8.toInt()
    }
}
