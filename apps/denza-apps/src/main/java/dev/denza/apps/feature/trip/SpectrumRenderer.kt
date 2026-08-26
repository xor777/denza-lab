package dev.denza.apps.feature.trip

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import dev.denza.apps.design.DenzaPalette
import dev.denza.apps.feature.panel.PanelPalette
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
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
    private val barPath = Path()
    private val cornerRadii = FloatArray(8)
    private var scanPitch = 0f
    private val idlePath = Path()
    private val tickerPaint = Paint()
    private val gridMatrix = Matrix()

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
    private var marqueeDots = 0f
    private var tickerTitle: String? = null
    private var tickerArtist: String? = null
    private var tickerPitch = 0f
    private var tickerDots = 0
    private var tickerBitmap: Bitmap? = null

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
        drawBars(canvas, left, right, unit)
        drawScanlines(canvas, left, right, unit)
        if (strip) {
            drawTicker(canvas, nowPlaying, left, right, top, unit, dtSec)
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
        // The title sits above the analyser, not below it, because that is where the board puts
        // it - and because a strip under the reflection reads as a caption for the reflection.
        val axisSpace = if (strip) STRIP_UNITS * unit else 0f
        barTopY = top + axisSpace
        reflectBottomY = bottom
        baselineY = barTopY + (bottom - barTopY) * BASELINE_FRACTION

        // The board's ramp - dark champagne at the foot, full champagne at 62 per cent of the
        // way up, the peak tint at the crown - built once in a unit space and stretched onto each
        // bar in turn.
        //
        // That last part is the whole of it. This gradient used to be anchored to the field, so
        // colour meant absolute height and a short bar was drawn entirely out of the dark end: on
        // a real spectrum, where most bands sit low, two thirds of the analyser went to near-black
        // and the display read as four loud bars and a shadow. The board gives every bar the full
        // ramp over its own height, so a quiet band is a small bright column rather than an
        // absence. The old version ran on into amber and coral at the top as well, which spent
        // the car's two alarm colours on a loud chorus.
        barShader = LinearGradient(
            0f, 0f, 0f, 1f,
            intArrayOf(PEAK, ACCENT, DEEP),
            floatArrayOf(0f, 0.38f, 1f),
            Shader.TileMode.CLAMP,
        )
        bloomShader = LinearGradient(
            0f, baselineY, 0f, barTopY,
            intArrayOf(alpha(ACCENT, 0.20f), alpha(ACCENT, 0.05f), alpha(ACCENT, 0f)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        idleShader = LinearGradient(
            left, 0f, right, 0f,
            intArrayOf(alpha(ACCENT, 0f), alpha(ACCENT, 0.22f), alpha(ACCENT, 0f)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        buildScanlines(unit)
    }

    /**
     * A 1-pixel-wide tile: transparent, with a band of the panel's own background at its foot.
     * Tiled up from the baseline it cuts every lit bar into LED segments.
     *
     * The band is the background colour at full opacity, not a wash of black. A translucent black
     * over a champagne bar leaves a grey-green stripe rather than a gap, which is why the segments
     * read as dirt on the bars instead of as the space between them.
     */
    private fun buildScanlines(unit: Float) {
        val pitch = (SCAN_PITCH_UNITS * unit).roundToInt().coerceAtLeast(3)
        val dark = (pitch * SCAN_DARK_FRACTION).roundToInt().coerceIn(1, pitch - 1)
        scanlines?.recycle()
        val bitmap = Bitmap.createBitmap(1, pitch, Bitmap.Config.ARGB_8888)
        val tileCanvas = Canvas(bitmap)
        tileCanvas.drawColor(Color.TRANSPARENT)
        val tilePaint = Paint()
        tilePaint.color = DenzaPalette.BACKGROUND
        tileCanvas.drawRect(0f, (pitch - dark).toFloat(), 1f, pitch.toFloat(), tilePaint)
        scanlines = bitmap
        scanShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.REPEAT)
        scanPitch = pitch.toFloat()
    }

    private fun drawBloom(canvas: Canvas, left: Float, right: Float, energy: Float) {
        val shader = bloomShader ?: return
        fill.shader = shader
        fill.alpha = (BLOOM_BASE_ALPHA + energy * BLOOM_GAIN).coerceIn(0f, 1f).times(255).toInt()
        canvas.drawRect(left, barTopY, right, baselineY, fill)
        fill.shader = null
        fill.alpha = 255
    }

    /**
     * The columns, their crowns and their reflection, as the board draws them.
     *
     * Each bar is filled by stretching the unit ramp onto its own height, gets the board's two-unit
     * radius on its top corners only, and carries a floating peak marker four units tall in the
     * peak tint. The bar itself has no cap: the previous version painted a white bar over the top
     * of every column, which flattened the crown the gradient had just made and washed the colour
     * out of exactly the part the eye lands on.
     */
    private fun drawBars(canvas: Canvas, left: Float, right: Float, unit: Float) {
        val bandCount = SpectrumSource.BAND_COUNT
        val width = (right - left) / bandCount * BAR_WIDTH_FRACTION
        // Bars are spread so the first one starts exactly on the left edge and the last ends
        // exactly on the right, with the spacing living only between them. Laying them out on
        // fixed slots left half a gap at each end, which read as the whole analyser sitting inset.
        val step = if (bandCount > 1) (right - left - width) / (bandCount - 1) else 0f
        val radius = unit * BAR_RADIUS_UNITS
        val fullHeight = baselineY - barTopY
        val stub = unit * 2.2f
        val reflectLimit = (reflectBottomY - baselineY).coerceAtMost(unit * REFLECT_UNITS)
        val shader = barShader ?: return

        for (band in 0 until bandCount) {
            val x = left + step * band
            val level = dynamics.bars[band]
            val height = (fullHeight * level).coerceAtLeast(stub)
            val top = baselineY - height

            // Mirrored about the baseline: the pixel under it answers the pixel over it, so the
            // reflection starts at the bar's own dark foot and is cropped rather than faded.
            val reflectHeight = height.coerceAtMost(reflectLimit)
            if (reflectHeight > 0f) {
                gridMatrix.setScale(1f, -height)
                gridMatrix.postTranslate(0f, baselineY + height)
                shader.setLocalMatrix(gridMatrix)
                fill.shader = shader
                fill.alpha = REFLECT_ALPHA
                canvas.drawRect(x, baselineY, x + width, baselineY + reflectHeight, fill)
                fill.alpha = 255
            }

            gridMatrix.setScale(1f, height)
            gridMatrix.postTranslate(0f, top)
            shader.setLocalMatrix(gridMatrix)
            fill.shader = shader
            barPath.reset()
            bar.set(x, top, x + width, baselineY)
            barPath.addRoundRect(bar, topCorners(radius), Path.Direction.CW)
            canvas.drawPath(barPath, fill)
            fill.shader = null

            val peak = dynamics.peaks[band]
            if (peak > 0.02f) {
                val peakY = baselineY - fullHeight * peak
                // A halo under the marker, standing in for the board's 7-unit shadow: two flat
                // rects cost nothing, where a real blur costs a mask filter per bar per frame.
                fill.color = alpha(ACCENT, PEAK_HALO_ALPHA)
                bar.set(
                    x - unit, peakY - unit * (PEAK_UNITS / 2f + 1.4f),
                    x + width + unit, peakY + unit * (PEAK_UNITS / 2f + 1.4f),
                )
                canvas.drawRoundRect(bar, radius, radius, fill)
                fill.color = peakColour(peak)
                bar.set(x, peakY - unit * PEAK_UNITS / 2f, x + width, peakY + unit * PEAK_UNITS / 2f)
                canvas.drawRoundRect(bar, radius, radius, fill)
            }
        }
        fill.shader = null
        fill.color = Color.WHITE
    }

    /** Radii for [Path.addRoundRect]: the board rounds a bar's crown and squares its foot. */
    private fun topCorners(radius: Float): FloatArray {
        cornerRadii[0] = radius; cornerRadii[1] = radius
        cornerRadii[2] = radius; cornerRadii[3] = radius
        return cornerRadii
    }

    /**
     * The segment grid, over the bars and not over their reflection, with a band boundary landing
     * exactly on the baseline so the lowest segment of every bar is a whole one.
     */
    private fun drawScanlines(canvas: Canvas, left: Float, right: Float, unit: Float) {
        val shader = scanShader ?: return
        val pitch = if (scanPitch > 0f) scanPitch else SCAN_PITCH_UNITS * unit
        gridMatrix.setTranslate(0f, baselineY - pitch * ceil(baselineY / pitch))
        shader.setLocalMatrix(gridMatrix)
        fill.shader = shader
        fill.alpha = 255
        canvas.drawRect(left, barTopY, right, baselineY, fill)
        fill.shader = null
    }

    /**
     * The track title as a dot-matrix ticker.
     *
     * Two bitmaps do the work. The unlit dot grid is a single cell tiled across
     * the strip, so the dark matrix costs one rectangle a frame. The lit text is
     * rendered dot by dot once, when the track changes, into its own bitmap and
     * then simply blitted — drawing a rectangle per lit dot every frame would be
     * hundreds of draw calls for something that changes once a song.
     *
     * The scroll advances in whole dots rather than smoothly. That is what the
     * displays this imitates did, and it is also what keeps the lit dots sitting
     * exactly on the unlit grid instead of sliding between its holes.
     */
    private fun drawTicker(
        canvas: Canvas,
        nowPlaying: NowPlayingSource,
        left: Float,
        right: Float,
        top: Float,
        unit: Float,
        dtSec: Double,
    ) {
        // Whole pixels per dot: a fractional pitch would blur the matrix and
        // knock the lit dots off the grid.
        val pitch = max(2f, (GLYPH_HEIGHT_UNITS * unit / PixelFont.ROWS).roundToInt().toFloat())
        val gridHeight = pitch * PixelFont.ROWS
        val stripHeight = STRIP_UNITS * unit
        val gridTop = top + (stripHeight - gridHeight) / 2f

        ensureTicker(nowPlaying, pitch)

        // The board draws a play mark and then the title, and nothing else: no field of unlit
        // dots behind it. That field was the analyser's own width in dim grey, and at a glance it
        // read as a second, empty instrument sitting above the real one.
        val markSize = unit * PLAY_MARK_UNITS
        val markTop = gridTop + (gridHeight - markSize) / 2f
        glyph.reset()
        glyph.moveTo(left, markTop)
        glyph.lineTo(left + markSize * 0.86f, markTop + markSize / 2f)
        glyph.lineTo(left, markTop + markSize)
        glyph.close()
        fill.shader = null
        fill.color = if (nowPlaying.playing) ACCENT else alpha(ACCENT, 0.45f)
        canvas.drawPath(glyph, fill)
        val textLeft = left + markSize + unit * PLAY_MARK_GAP

        val bitmap = tickerBitmap ?: return
        val visibleDots = ((right - textLeft) / pitch).toInt()
        val scrolls = tickerDots > visibleDots
        val cycleDots = tickerDots + TICKER_GAP_DOTS
        if (scrolls) {
            marqueeDots += (dtSec * TICKER_DOTS_PER_SEC).toFloat()
            if (marqueeDots >= cycleDots) marqueeDots -= cycleDots
        } else {
            marqueeDots = 0f
        }
        val offset = floor(marqueeDots) * pitch

        canvas.save()
        canvas.clipRect(textLeft, gridTop, right, gridTop + gridHeight)
        // The board writes the title in full champagne under a soft drop shadow. It was held at
        // 150 of 255 here on the reasoning that the analyser is the subject - which it is, and
        // which the analyser now says for itself by being lit; a title at 59 per cent over a black
        // panel just reads as olive.
        val x = textLeft - offset
        val wrapped = if (scrolls) x + cycleDots * pitch else Float.NaN
        // The board's `drop-shadow(0 0 7px ...)`, drawn as four offset copies. A real blur here
        // means a mask filter on a bitmap on every frame; four extra blits do not.
        tickerPaint.alpha = if (nowPlaying.playing) GLOW_ALPHA else GLOW_ALPHA / 2
        val halo = unit * GLOW_UNITS
        for (dx in -1..1) {
            for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                canvas.drawBitmap(bitmap, x + dx * halo, gridTop + dy * halo, tickerPaint)
                if (scrolls) {
                    canvas.drawBitmap(bitmap, wrapped + dx * halo, gridTop + dy * halo, tickerPaint)
                }
            }
        }
        tickerPaint.alpha = if (nowPlaying.playing) 255 else TICKER_ALPHA_PAUSED
        canvas.drawBitmap(bitmap, x, gridTop, tickerPaint)
        if (scrolls) canvas.drawBitmap(bitmap, wrapped, gridTop, tickerPaint)
        canvas.restore()
    }

    /** Redraws the dot bitmaps only when the track or the dot pitch changes. */
    private fun ensureTicker(nowPlaying: NowPlayingSource, pitch: Float) {
        if (nowPlaying.title == tickerTitle &&
            nowPlaying.artist == tickerArtist &&
            pitch == tickerPitch
        ) {
            return
        }
        val pitchChanged = pitch != tickerPitch
        tickerTitle = nowPlaying.title
        tickerArtist = nowPlaying.artist
        tickerPitch = pitch
        marqueeDots = 0f
        if (pitchChanged) {
        }

        val title = tickerTitle.orEmpty()
        val artist = tickerArtist.orEmpty()
        val text = PixelFont.prepare(if (artist.isBlank()) title else "$title · $artist")
        tickerDots = PixelFont.widthInDots(text)
        tickerBitmap?.recycle()
        tickerBitmap = null
        if (tickerDots <= 0) return

        val width = (tickerDots * pitch).toInt().coerceAtLeast(1)
        val height = (PixelFont.ROWS * pitch).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val target = Canvas(bitmap)
        val dot = Paint()
        dot.color = ACCENT
        val size = pitch * DOT_FILL
        var column = 0
        for (character in text) {
            val glyphRows = PixelFont.glyph(character)
            for (row in 0 until PixelFont.ROWS) {
                val bits = glyphRows[row]
                for (col in 0 until PixelFont.COLUMNS) {
                    if ((bits shr (PixelFont.COLUMNS - 1 - col)) and 1 == 1) {
                        val x = (column + col) * pitch
                        val y = row * pitch
                        target.drawRect(x, y, x + size, y + size, dot)
                    }
                }
            }
            column += PixelFont.COLUMNS + PixelFont.TRACKING
        }
        tickerBitmap = bitmap
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

    /** The board draws every peak cap in one colour, the peak tint, whatever the bar is doing. */
    private fun peakColour(peak: Float): Int = PEAK

    private fun alpha(color: Int, a: Float): Int {
        val clamped = (a.coerceIn(0f, 1f) * 255).toInt()
        return (color and 0x00FFFFFF) or (clamped shl 24)
    }

    /** Read by `SpectrumBoardContractTest`, which measures these against `Main.dc.html`. */
    internal companion object {
        /** Matches the Visualizer's maximum capture size, used for the axis map. */
        const val FFT_CAPTURE_SIZE = 1024

        // The baseline sits low in its area: the reflection only needs a little
        // room, and higher up the analyser floated clear of the ticker with dead
        // space between them.
        const val BASELINE_FRACTION = 0.839f
        const val BAR_WIDTH_FRACTION = 0.7097f

        /** The board's `border-radius:2px 2px 0 0`, its 4-unit cap and its 40-unit crop. */
        const val BAR_RADIUS_UNITS = 2f
        const val PEAK_UNITS = 4f
        const val PEAK_HALO_ALPHA = 0.30f
        const val REFLECT_UNITS = 40f
        const val REFLECT_ALPHA = 36
        const val STRIP_UNITS = 48f

        /** Cap height of the ticker. */
        const val GLYPH_HEIGHT_UNITS = 34f

        /** How much of a dot cell the dot itself fills, leaving the matrix gaps. */
        const val DOT_FILL = 0.78f
        const val TICKER_ALPHA_PAUSED = 95
        const val GLOW_ALPHA = 46
        const val GLOW_UNITS = 2.2f
        const val PLAY_MARK_UNITS = 15f
        const val PLAY_MARK_GAP = 12f
        const val TICKER_GAP_DOTS = 20
        const val TICKER_DOTS_PER_SEC = 8.0
        const val SCAN_PITCH_UNITS = 11f
        const val SCAN_DARK_FRACTION = 0.273f
        const val BLOOM_BASE_ALPHA = 0.25f
        const val BLOOM_GAIN = 1.4f
        const val IDLE_STEPS = 72

        // The bar ramp, quiet to clipping. It is a reading, so it climbs through ink rather than
        // through the interface accent: champagne marks what you can press, and a spectrum is not
        // something you press.
        /** The board's three gradient stops: `#4A4222`, `#FEEFAB`, `#FFF8DA`. */
        val DEEP = DenzaPalette.accent(0.28f)
        val ACCENT = DenzaPalette.ACCENT
        val PEAK = DenzaPalette.DATA_PEAK
        val MUTED = PanelPalette.MUTED
        val INK = PanelPalette.INK
    }
}
