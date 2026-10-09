package dev.denza.apps.feature.hud

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import java.io.ByteArrayOutputStream

/** What the pipeline made of one picture. */
internal sealed interface HudMapResult {
    /** [by] lies over the cut: nothing is sent. */
    data class Covered(val by: HudMapWindow) : HudMapResult

    /** The map window's frame as its SOME/IP payload, and where its cursor came from. */
    class Frame(val payload: ByteArray, val anchor: HudMapAnchorSource) : HudMapResult
}

/**
 * One frame of the map window from one picture of the screen, whatever took the picture.
 *
 * The cursor is looked for in Yandex's window reduced to [HudMapGeometry.ANALYSIS_WIDTH] - not on
 * every frame, but every [SEARCH_INTERVAL_MS], whenever the window moves, and on every frame kept
 * for tuning; the frames between reuse the last answer, since Yandex keeps its cursor still on the
 * screen while it follows the car. The cut around it ([HudMapGeometry.crop]) is drawn at twice the
 * frame, halved, keyed to black ([HudMapKey]), encoded as PNG and wrapped in the map event's
 * payload, all here, so the main thread only fires it.
 *
 * Built for 10 frames a second and more: the screen is never copied whole into memory - the GPU
 * draws the two small images ([HudMapScaler]) and only they are read back - and the pixel arrays,
 * the cursor search's scratch, the key's histogram and the PNG's bitmap stay from one frame to the
 * next. Owned by the frame thread; [close] frees what it holds.
 */
internal class HudMapPipeline(private val capture: HudMapCapture?) : AutoCloseable {
    private val anchors = HudMapAnchorTracker()
    private val search = HudMapArrowSearch()
    private val key = HudMapKey()
    private val png = HudMapPng()
    private var analysisScaler: HudMapScaler? = null
    private var mapScaler: HudMapScaler? = null
    private var analysisPixels = IntArray(0)
    private val renderPixels = IntArray(HudMapGeometry.RENDER_WIDTH * HudMapGeometry.RENDER_HEIGHT)
    private val mapPixels = IntArray(HudMapGeometry.MAP_WIDTH * HudMapGeometry.MAP_HEIGHT)
    private var searchedAt = Long.MIN_VALUE
    private var searchedWindow: PxRect? = null

    /** The frame for [frame], taken at [nowMs] on a monotonic clock. */
    fun process(frame: HudMapFrame, nowMs: Long): HudMapResult {
        val window = frame.window
        val keep = capture?.due() == true
        var analysisPng: ByteArray? = null
        val anchor = if (keep || window != searchedWindow || nowMs - searchedAt >= SEARCH_INTERVAL_MS) {
            searchedAt = nowMs
            searchedWindow = window
            val width = HudMapGeometry.analysisWidth(window)
            val height = HudMapGeometry.analysisHeight(window)
            val scaler = analysisScaler?.takeIf { it.width == width && it.height == height }
                ?: HudMapScaler(width, height).also { fresh ->
                    analysisScaler?.close()
                    analysisScaler = fresh
                }
            if (analysisPixels.size < width * height) analysisPixels = IntArray(width * height)
            if (scaler.draw(frame.image, window, analysisPixels)) {
                val scale = width.toFloat() / window.width
                val expected = anchors.current(window)
                val blob = search.find(
                    analysisPixels,
                    width,
                    height,
                    scale * frame.density,
                    (expected.x - window.left) * scale,
                    (expected.y - window.top) * scale,
                )
                if (keep) analysisPng = png.encode(analysisPixels, width, height)
                anchors.resolve(
                    window,
                    frame.density,
                    blob?.let { window.left + it.x / scale },
                    blob?.let { window.top + it.y / scale },
                )
            } else {
                anchors.current(window)
            }
        } else {
            anchors.current(window)
        }
        val crop = HudMapGeometry.crop(window, frame.density, anchor.x, anchor.y)
        val depth = (HudMapWindows.COVER_DEPTH_DP * frame.density).toInt()
        HudMapWindows.coverer(crop, frame.above, depth)?.let { return HudMapResult.Covered(it) }

        val scaler = mapScaler
            ?: HudMapScaler(HudMapGeometry.RENDER_WIDTH, HudMapGeometry.RENDER_HEIGHT).also { mapScaler = it }
        check(scaler.draw(frame.image, crop, renderPixels)) { "the GPU gave no image of the cut" }
        HudMapPixels.halve(renderPixels, HudMapGeometry.RENDER_WIDTH, HudMapGeometry.RENDER_HEIGHT, mapPixels)
        key.apply(mapPixels, key.ground(mapPixels))
        val image = png.encode(mapPixels, HudMapGeometry.MAP_WIDTH, HudMapGeometry.MAP_HEIGHT)
        if (keep) capture?.save(analysisPng, image)
        return HudMapResult.Frame(HudSomeIpClient.mapPayload(image), anchor.source)
    }

    /** Forgets the cursor, as a new route or a new start of the map should. */
    fun reset() {
        anchors.reset()
        searchedWindow = null
        searchedAt = Long.MIN_VALUE
    }

    override fun close() {
        analysisScaler?.close()
        analysisScaler = null
        mapScaler?.close()
        mapScaler = null
        png.close()
    }

    companion object {
        /** How often the cursor is looked for while the window stays where it is. */
        const val SEARCH_INTERVAL_MS = 300L
    }
}

/**
 * A part of a picture drawn at a fixed size by the GPU and read back into ARGB pixels.
 *
 * The picture is usually a hardware bitmap of the whole screen, which no software canvas may draw
 * and whose pixels the CPU may not read. It is recorded into a [Picture] and rendered by the GPU
 * into a hardware bitmap of [width]×[height] (`Bitmap.createBitmap(Picture, …, HARDWARE)`), and only
 * that small bitmap is copied out: the screen never leaves the GPU.
 *
 * The two small bitmaps are made per frame and recycled at once - at most 600×360, under 1 MB each,
 * none of it on the Java heap. A renderer drawing into a kept `ImageReader` would have reused them,
 * but on the API 35 emulator it drew nothing a reader could see (2026-10-09), and this path is the
 * platform's own.
 *
 * The [Picture] is emptied right after the render: it holds the screen's buffer otherwise, until
 * the next frame records over it.
 */
internal class HudMapScaler(val width: Int, val height: Int) : AutoCloseable {
    private val picture = Picture()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val from = Rect()
    private val to = Rect(0, 0, width, height)

    /** Draws [part] of [image] into [out] as ARGB, row by row; false when the GPU gave no image. */
    fun draw(image: Bitmap, part: PxRect, out: IntArray): Boolean {
        from.set(part.left, part.top, part.right, part.bottom)
        picture.beginRecording(width, height).drawBitmap(image, from, to, paint)
        picture.endRecording()
        val drawn = try {
            Bitmap.createBitmap(picture, width, height, Bitmap.Config.HARDWARE)
        } finally {
            forget()
        }
        val read = try {
            drawn.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            drawn.recycle()
        }
        read ?: return false
        try {
            read.getPixels(out, 0, width, 0, 0, width, height)
        } finally {
            read.recycle()
        }
        return true
    }

    override fun close() = forget()

    private fun forget() {
        picture.beginRecording(1, 1)
        picture.endRecording()
    }
}

/** ARGB pixels to PNG bytes, through one bitmap kept for the map's own size. */
internal class HudMapPng : AutoCloseable {
    private var bitmap: Bitmap? = null
    private val out = ByteArrayOutputStream(64 * 1024)

    fun encode(pixels: IntArray, width: Int, height: Int): ByteArray {
        val kept = width == HudMapGeometry.MAP_WIDTH && height == HudMapGeometry.MAP_HEIGHT
        val target = if (kept) {
            bitmap ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        try {
            target.setPixels(pixels, 0, width, 0, 0, width, height)
            out.reset()
            target.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        } finally {
            if (!kept) target.recycle()
        }
    }

    override fun close() {
        bitmap?.recycle()
        bitmap = null
    }

    companion object {
        /**
         * The map event with an all-black frame: black is what the HUD does not draw, so this is
         * how the window is emptied. Made once, on first use.
         */
        @JvmStatic
        val blankPayload: ByteArray by lazy {
            val black = IntArray(HudMapGeometry.MAP_WIDTH * HudMapGeometry.MAP_HEIGHT) { 0xFF000000.toInt() }
            HudMapPng().use { encoder ->
                HudSomeIpClient.mapPayload(encoder.encode(black, HudMapGeometry.MAP_WIDTH, HudMapGeometry.MAP_HEIGHT))
            }
        }
    }
}
