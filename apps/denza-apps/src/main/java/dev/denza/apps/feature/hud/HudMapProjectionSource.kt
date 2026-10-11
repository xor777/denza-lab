package dev.denza.apps.feature.hud

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display

/**
 * The main screen's source: a MediaProjection mirror of display 0 at half its size, with Yandex's
 * window from the shared accessibility service's window list.
 *
 * The mirror is a virtual display of [DISPLAY_NAME] with `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` into an
 * [ImageReader] whose buffers the GPU samples: a frame is a hardware buffer the pipeline draws from
 * without the CPU ever holding the screen. It exists only while the source runs - a route held, the
 * map on - and goes on [stop]; the token ([HudMapGrant]) outlives it.
 *
 * Frames are taken at most every [frameIntervalMs] (12 a second): every image that arrives is
 * acquired as the newest, made into a frame if one is due ([HudMapPacer]) and closed either way, so
 * the reader's queue never fills and a slow frame is followed by the newest picture. The window
 * list is read at most every 250 ms ([HudMapWindowReader]) and scaled into the mirror's pixels.
 *
 * With no token the source reports [HudMapState.NO_GRANT] and looks again every [GRANT_CHECK_MS],
 * so a token taken mid-route starts the frames at once. A projection stopped by the system - another
 * app's capture, a live-video route - ends the mirror ([VirtualDisplay.Callback.onStopped]) and the
 * source goes back to looking.
 *
 * Every call is on the map's thread, the [Handler] it is started with.
 */
internal class HudMapProjectionSource(private val service: AccessibilityService) : HudMapFrameSource {
    override val name: String = "MediaProjection"
    override val frameIntervalMs: Long = 83L

    private val windows = HudMapWindowReader(service)
    private val pacer = HudMapPacer(frameIntervalMs)
    private val check = Runnable { look() }

    private var handler: Handler? = null
    private var sink: HudMapFrameSink? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var factor = 0.5f
    private var density = 1f

    private val displayCallback = object : VirtualDisplay.Callback() {
        override fun onStopped() {
            close()
            handler?.let { it.removeCallbacks(check); it.post(check) }
        }
    }

    override fun start(handler: Handler, sink: HudMapFrameSink) {
        if (this.sink != null) return
        this.handler = handler
        this.sink = sink
        pacer.reset()
        windows.forget()
        handler.post(check)
    }

    override fun stop() {
        handler?.removeCallbacks(check)
        sink = null
        close()
    }

    /** Is there a token, has it changed, is Yandex there - also while no frame arrives. */
    private fun look() {
        val sink = sink ?: return
        val handler = handler ?: return
        val granted = HudMapGrant.projection
        if (granted !== projection) close()
        if (granted == null) {
            sink.onGap(HudMapState.NO_GRANT)
            // The map would show now: ask without waiting for the driver to open Denza Apps.
            if (windows.placement(SystemClock.uptimeMillis()) is YandexMapPlacement.MainScreen) {
                HudMapGrant.requestInBackground(service)
            }
        } else {
            if (display == null) open(granted, handler)
            if (display == null) {
                sink.onGap(HudMapState.NO_GRANT)
            } else if (windows.placement(SystemClock.uptimeMillis()) !is YandexMapPlacement.MainScreen) {
                // A screen that does not change sends no frames, so the gap is found here too.
                sink.onGap(HudMapState.NOT_ON_SCREEN)
            }
        }
        handler.postDelayed(check, GRANT_CHECK_MS)
    }

    private fun open(granted: MediaProjection, handler: Handler) {
        val metrics = DisplayMetrics()
        val main = service.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        if (main == null) {
            HudMapRuntime.onCaptureFailure("display")
            return
        }
        @Suppress("DEPRECATION")
        main.getRealMetrics(metrics)
        val width = HudMapMirror.width(metrics.widthPixels)
        val height = HudMapMirror.height(metrics.heightPixels)
        factor = width.toFloat() / metrics.widthPixels
        density = metrics.density * factor
        val images = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        images.setOnImageAvailableListener({ onImage(it) }, handler)
        val mirror = try {
            granted.createVirtualDisplay(
                DISPLAY_NAME,
                width,
                height,
                (metrics.densityDpi * factor).toInt().coerceAtLeast(1),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                images.surface,
                displayCallback,
                handler,
            )
        } catch (error: RuntimeException) {
            null
        }
        if (mirror == null) {
            HudMapRuntime.onCaptureFailure("display")
            images.close()
            return
        }
        projection = granted
        display = mirror
        reader = images
        pacer.reset()
        windows.forget()
    }

    private fun onImage(images: ImageReader) {
        val image = try {
            images.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return
        try {
            val sink = sink ?: return
            val now = SystemClock.uptimeMillis()
            if (!pacer.due(now)) return
            val placement = windows.placement(now)
            if (placement !is YandexMapPlacement.MainScreen) {
                sink.onGap(HudMapState.NOT_ON_SCREEN)
                return
            }
            pacer.started(now)
            val buffer = image.hardwareBuffer
            if (buffer == null) {
                HudMapRuntime.onCaptureFailure("buffer")
                return
            }
            try {
                val picture = Bitmap.wrapHardwareBuffer(buffer, null)
                if (picture == null) {
                    HudMapRuntime.onCaptureFailure("bitmap")
                    return
                }
                try {
                    val mirrored = HudMapMirror.scale(placement, factor)
                    sink.onFrame(HudMapFrame(picture, mirrored.window, mirrored.above, density))
                } finally {
                    picture.recycle()
                }
            } finally {
                buffer.close()
            }
        } finally {
            image.close()
        }
    }

    private fun close() {
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        reader?.close()
        display = null
        reader = null
        projection = null
    }

    companion object {
        /**
         * The mirror's name. `ClusterDisplayResolver` knows it as one of this app's own displays, so
         * it is never offered as the instruments' screen while it exists.
         */
        const val DISPLAY_NAME = "Denza HUD Map"

        /** How often the source looks for a token, a changed one, and Yandex without frames. */
        const val GRANT_CHECK_MS = 500L

        /** One frame in the pipeline, the newest waiting, and one the mirror is drawing. */
        private const val MAX_IMAGES = 3
    }
}
