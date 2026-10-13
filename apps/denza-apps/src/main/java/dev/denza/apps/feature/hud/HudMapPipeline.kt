package dev.denza.apps.feature.hud

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 * The cut ([HudMapGeometry.crop]) is placed at the cursor as last found, drawn by the GPU at twice
 * the map window's 300×180, keyed to black ([HudMapKey]), repainted for the projector and reduced
 * to 300×180 ([HudMapPalette]; a day map is only reduced), encoded as PNG and wrapped in the map
 * event's payload, all here, so the main thread only fires it. The cursor is looked for in Yandex's
 * window reduced to [HudMapGeometry.ANALYSIS_WIDTH] - not on every frame, but every
 * [SEARCH_INTERVAL_MS], whenever the window moves, and on every frame kept for tuning - and its
 * answer places the next frame's cut: Yandex keeps its cursor still on the screen while it follows
 * the car, so a frame's lag costs nothing.
 *
 * Built for 10 frames a second and more. The screen is never copied whole into memory, and each
 * frame is one GPU pass ([HudMapScaler]): the cut, and on a search frame Yandex's reduced window
 * under it in the same picture, so the price of a render and its read-back is paid once. A frame
 * drawn into a surface - the instruments' - is read the same way by PixelCopy, the cut and the
 * reduced window each scaled on the way ([HudMapSurfaceReader]). The pixel
 * arrays, the cursor search's scratch, the key's histogram and the PNG's bitmap stay from one frame
 * to the next. Owned by the frame thread; [close] frees what it holds.
 *
 * The cut is about 490 pixels wide in the sources it is made for - the main screen mirrored at half
 * size (density 1), the instruments' 160 dpi display - so the GPU's bilinear pass to 600 is ×1.22:
 * the palette sees the lines whole, and its own 2×2 reduction makes the window's frame.
 */
internal class HudMapPipeline(private val capture: HudMapCapture?) : AutoCloseable {
    private val anchors = HudMapAnchorTracker()
    private val search = HudMapArrowSearch()
    private val key = HudMapKey()
    private val palette = HudMapPalette()
    private val png = HudMapPng()
    private val scaler = HudMapScaler()
    private var surfaces: HudMapSurfaceReader? = null
    private var drawn = IntArray(0)
    private var analysisPixels = IntArray(0)
    private val cutPixels = IntArray(HudMapPalette.WIDTH * HudMapPalette.HEIGHT)
    private val mapPixels = IntArray(HudMapGeometry.MAP_WIDTH * HudMapGeometry.MAP_HEIGHT)
    private var searchedAt = Long.MIN_VALUE
    private var searchedWindow: PxRect? = null

    /** The frame for [frame], taken at [nowMs] on a monotonic clock. */
    fun process(frame: HudMapFrame, nowMs: Long): HudMapResult {
        val window = frame.window
        val anchor = anchors.current(window)
        val crop = HudMapGeometry.crop(window, frame.density, anchor.x, anchor.y)
        val covered = HudMapWindows.coverer(crop, frame.above)
        val keep = covered == null && capture?.due() == true
        val searching = keep || window != searchedWindow || nowMs - searchedAt >= SEARCH_INTERVAL_MS
        if (pass(covered != null, searching) == HudMapPass.SKIP) return HudMapResult.Covered(covered!!)
        val cutWidth = HudMapPalette.WIDTH
        val cutHeight = HudMapPalette.HEIGHT
        val analysisWidth = HudMapGeometry.analysisWidth(window)
        val analysisHeight = HudMapGeometry.analysisHeight(window)
        val width = if (searching) maxOf(cutWidth, analysisWidth) else cutWidth
        val height = if (searching) cutHeight + analysisHeight else cutHeight
        if (drawn.size < width * height) drawn = IntArray(width * height)
        val whole = if (searching) window else null
        when (val image = frame.image) {
            is HudMapImage.Screen -> {
                val drewAll = scaler.draw(image.bitmap, width, height, crop, whole, drawn)
                check(drewAll) { "the GPU gave no image of the cut" }
            }
            is HudMapImage.Drawn -> (surfaces ?: HudMapSurfaceReader().also { surfaces = it })
                .draw(image.surface, width, crop, whole, drawn)
        }

        var analysisPng: ByteArray? = null
        if (searching) {
            searchedAt = nowMs
            searchedWindow = window
            if (analysisPixels.size < analysisWidth * analysisHeight) {
                analysisPixels = IntArray(analysisWidth * analysisHeight)
            }
            for (row in 0 until analysisHeight) {
                System.arraycopy(drawn, (cutHeight + row) * width, analysisPixels, row * analysisWidth, analysisWidth)
            }
            val scale = analysisWidth.toFloat() / window.width
            val blob = search.find(
                analysisPixels,
                analysisWidth,
                analysisHeight,
                scale * frame.density,
                (anchor.x - window.left) * scale,
                (anchor.y - window.top) * scale,
            )
            anchors.resolve(
                window,
                frame.density,
                blob?.let { window.left + it.x / scale },
                blob?.let { window.top + it.y / scale },
            )
            if (keep) analysisPng = png.encode(analysisPixels, analysisWidth, analysisHeight)
        }
        if (covered != null) return HudMapResult.Covered(covered)

        for (row in 0 until cutHeight) {
            System.arraycopy(drawn, row * width, cutPixels, row * cutWidth, cutWidth)
        }
        val ground = key.ground(cutPixels)
        key.apply(cutPixels, ground)
        if (HudMapPalette.isNight(ground)) palette.apply(cutPixels, mapPixels) else HudMapPalette.reduce(cutPixels, mapPixels)
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
        scaler.close()
        surfaces?.close()
        surfaces = null
        png.close()
    }

    companion object {
        /** How often the cursor is looked for while the window stays where it is. */
        const val SEARCH_INTERVAL_MS = 300L

        /**
         * What one picture is used for. A hidden cut still searches when a search is due: the cut
         * is placed by the cursor, so only a search can take it out from under what hides it. On
         * 2026-10-11 the hidden check came first and returned before the search; the owner moved
         * the map, the cut followed the cursor under the split's divider, and the window stayed
         * empty after Yandex had brought its cursor back.
         */
        internal fun pass(covered: Boolean, searching: Boolean): HudMapPass = when {
            !covered -> HudMapPass.FRAME
            searching -> HudMapPass.SEARCH_ONLY
            else -> HudMapPass.SKIP
        }
    }
}

/** What [HudMapPipeline.pass] decided one picture is for. */
internal enum class HudMapPass {
    /** Hidden, and no search due: nothing is drawn. */
    SKIP,

    /** Hidden: the cursor is looked for, nothing is sent. */
    SEARCH_ONLY,

    /** The map window's frame, and the search if one is due. */
    FRAME,
}

/**
 * Parts of a picture drawn by the GPU into one small image and read back into ARGB pixels.
 *
 * The picture is usually a hardware bitmap of the whole screen, which no software canvas may draw
 * and whose pixels the CPU may not read. It is recorded into a [Picture] and rendered by the GPU
 * into a hardware bitmap of the requested size (`Bitmap.createBitmap(Picture, …, HARDWARE)`), and
 * only that small bitmap is copied out: the screen never leaves the GPU. Both parts go into the one
 * render, because on the emulator the render and its read-back cost far more per call than per
 * pixel (2026-10-09: 31-84 ms for one 600×360 pass against 35-72 ms for a 320×200 one).
 *
 * The two small bitmaps are made per frame and recycled at once - at most 600×560, about 1.3 MB,
 * none of it on the Java heap. A renderer drawing into a kept `ImageReader` would have reused them,
 * but on the API 35 emulator it drew nothing a reader could see (2026-10-09), and this path is the
 * platform's own.
 *
 * The [Picture] is emptied right after the render: it holds the screen's buffer otherwise, until
 * the next frame records over it.
 */
internal class HudMapScaler : AutoCloseable {
    private val picture = Picture()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val from = Rect()
    private val to = Rect()

    /**
     * Draws [cut] of [image] at the top left at twice the map window's 300×180 ([HudMapPalette]) and,
     * if given, [whole] under it at the analysis width, into a [width]×[height] image read into
     * [out] row by row; false when the GPU gave no image.
     */
    fun draw(image: Bitmap, width: Int, height: Int, cut: PxRect, whole: PxRect?, out: IntArray): Boolean {
        val canvas = picture.beginRecording(width, height)
        from.set(cut.left, cut.top, cut.right, cut.bottom)
        to.set(0, 0, HudMapPalette.WIDTH, HudMapPalette.HEIGHT)
        canvas.drawBitmap(image, from, to, paint)
        if (whole != null) {
            from.set(whole.left, whole.top, whole.right, whole.bottom)
            to.set(
                0,
                HudMapPalette.HEIGHT,
                HudMapGeometry.analysisWidth(whole),
                HudMapPalette.HEIGHT + HudMapGeometry.analysisHeight(whole),
            )
            canvas.drawBitmap(image, from, to, paint)
        }
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

/**
 * Parts of the last frame a surface was given, read by PixelCopy - the cut at the palette's size
 * and, if asked, the whole window at the analysis width under it, laid out as [HudMapScaler] lays
 * them - for a display that draws into a surface of this app's, as the navigation display on the
 * instruments does. PixelCopy scales on the way, so only the small images reach the CPU.
 *
 * PixelCopy answers on a handler; its answers come on a thread of the reader's own, and the frame
 * thread waits for each, at most [TIMEOUT_MS]. A copy that fails throws with PixelCopy's reason:
 * the runner counts it and logs the first of a run, which is how a surface that cannot be read
 * shows itself. The two bitmaps are kept for the next frame. Owned by the frame thread; [close]
 * ends the answering thread.
 */
internal class HudMapSurfaceReader : AutoCloseable {
    private val answers = HandlerThread("denza-hud-map-copy").apply { start() }
    private val handler = Handler(answers.looper)
    private val from = Rect()
    private var cut: Bitmap? = null
    private var whole: Bitmap? = null

    /** Written on the answering thread before the latch opens, read after it. */
    @Volatile private var result = PixelCopy.ERROR_UNKNOWN

    /**
     * [cut] of [surface] at the top left of [out], [width] pixels a row, at the palette's size and,
     * if given, [window] under it at the analysis width.
     */
    fun draw(surface: Surface, width: Int, cut: PxRect, window: PxRect?, out: IntArray) {
        val cutWidth = HudMapPalette.WIDTH
        val cutHeight = HudMapPalette.HEIGHT
        val cutImage = sized(this.cut, cutWidth, cutHeight).also { this.cut = it }
        copy(surface, cut, cutImage)
        cutImage.getPixels(out, 0, width, 0, 0, cutWidth, cutHeight)
        if (window == null) return
        val analysisWidth = HudMapGeometry.analysisWidth(window)
        val analysisHeight = HudMapGeometry.analysisHeight(window)
        val wholeImage = sized(whole, analysisWidth, analysisHeight).also { whole = it }
        copy(surface, window, wholeImage)
        wholeImage.getPixels(out, cutHeight * width, width, 0, 0, analysisWidth, analysisHeight)
    }

    override fun close() {
        answers.quitSafely()
        cut?.recycle()
        whole?.recycle()
        cut = null
        whole = null
    }

    private fun copy(surface: Surface, part: PxRect, into: Bitmap) {
        check(surface.isValid) { "the surface is gone" }
        from.set(part.left, part.top, part.right, part.bottom)
        val done = CountDownLatch(1)
        result = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(surface, from, into, { code -> result = code; done.countDown() }, handler)
        check(done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "PixelCopy gave no answer in $TIMEOUT_MS ms" }
        val code = result
        check(code == PixelCopy.SUCCESS) { "PixelCopy ${describe(code)}" }
    }

    private fun sized(kept: Bitmap?, width: Int, height: Int): Bitmap {
        if (kept != null && kept.width == width && kept.height == height) return kept
        kept?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    companion object {
        /** Longer than any copy should take; a frame thread stuck here sends nothing. */
        const val TIMEOUT_MS = 250L

        fun describe(code: Int): String = when (code) {
            PixelCopy.SUCCESS -> "SUCCESS"
            PixelCopy.ERROR_UNKNOWN -> "ERROR_UNKNOWN"
            PixelCopy.ERROR_TIMEOUT -> "ERROR_TIMEOUT"
            PixelCopy.ERROR_SOURCE_NO_DATA -> "ERROR_SOURCE_NO_DATA"
            PixelCopy.ERROR_SOURCE_INVALID -> "ERROR_SOURCE_INVALID"
            PixelCopy.ERROR_DESTINATION_INVALID -> "ERROR_DESTINATION_INVALID"
            else -> "code $code"
        }
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
