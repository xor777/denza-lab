package dev.denza.apps.feature.hud

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executor

/**
 * The first source: the shared accessibility service's own screenshot of the main screen
 * (`AccessibilityService.takeScreenshot`, API 30), with Yandex's window read from the same
 * service's window list.
 *
 * What this car's IVI firmware does with it (`AbstractAccessibilityServiceConnection.takeScreenshot`):
 * without capability 128 - `android:canTakeScreenshot` in `simulcast_a11y_service.xml` - the call
 * throws `SecurityException`; a request within 333 ms of the previous one fails with error 3; the
 * frame is `DisplayManagerInternal.userScreenshot`, a hardware buffer of the whole display at its
 * own resolution. No consent, no token, nothing to renew after the process restarts. The price is
 * the pace: one frame per [frameIntervalMs], under 3 a second, which is why this source is the
 * first and not the last.
 *
 * Only what is visible is captured, so Yandex must be on the main screen; anywhere else is a gap.
 * A source without the capability says so once and stays quiet until the service connects again,
 * which builds a new source.
 */
internal class HudMapScreenshotSource(private val service: AccessibilityService) : HudMapFrameSource {
    override val name: String = "скриншот специальных возможностей"
    override val frameIntervalMs: Long = 350L

    private var handler: Handler? = null
    private var sink: HudMapFrameSink? = null
    private var generation = 0
    private var requestedAt = 0L
    private var noCapability = false
    private val tick = Runnable { capture() }
    private val bounds = Rect()

    override fun start(handler: Handler, sink: HudMapFrameSink) {
        if (this.sink != null) return
        this.handler = handler
        this.sink = sink
        generation++
        if (noCapability) {
            sink.onGap(HudMapState.NO_CAPABILITY)
            return
        }
        handler.post(tick)
    }

    override fun stop() {
        handler?.removeCallbacks(tick)
        sink = null
        generation++
    }

    private fun capture() {
        val sink = sink ?: return
        val handler = handler ?: return
        val placement = HudMapWindows.place(windows(), HudGuidanceSettings.NAVIGATOR_PACKAGE)
        if (placement !is YandexMapPlacement.MainScreen) {
            sink.onGap(HudMapState.NOT_ON_SCREEN)
            next(handler, SystemClock.uptimeMillis())
            return
        }
        val asked = generation
        val density = service.resources.displayMetrics.density
        requestedAt = SystemClock.uptimeMillis()
        val executor = Executor { runnable -> handler.post(runnable) }
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        try {
                            val current = this@HudMapScreenshotSource.sink
                            if (asked == generation && current != null) {
                                val image = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                if (image != null) {
                                    try {
                                        current.onFrame(HudMapFrame(image, placement.window, placement.above, density))
                                    } finally {
                                        image.recycle()
                                    }
                                }
                            }
                        } finally {
                            buffer.close()
                        }
                        if (asked == generation) next(handler, requestedAt)
                    }

                    override fun onFailure(errorCode: Int) {
                        HudMapRuntime.onCaptureFailure(errorCode)
                        if (asked == generation) next(handler, requestedAt)
                    }
                },
            )
            // An answer that never comes would end the loop; this is the next request if it does not.
            handler.postAtTime(tick, requestedAt + STALLED_MS)
        } catch (_: SecurityException) {
            noCapability = true
            sink.onGap(HudMapState.NO_CAPABILITY)
        }
    }

    /** The next request no sooner than [frameIntervalMs] after [from], the last request's time. */
    private fun next(handler: Handler, from: Long) {
        handler.removeCallbacks(tick)
        handler.postAtTime(tick, maxOf(from + frameIntervalMs, SystemClock.uptimeMillis()))
    }

    /**
     * The main screen's windows. The root - one Binder call into the window's process - is read
     * only for application windows, the one kind whose package matters here.
     */
    private fun windows(): List<HudMapWindow> {
        val windows = try {
            service.windows
        } catch (_: RuntimeException) {
            null
        } ?: return emptyList()
        return windows.mapNotNull { window ->
            window ?: return@mapNotNull null
            window.getBoundsInScreen(bounds)
            val type = window.type
            val packageName = if (type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                val root = window.root
                try {
                    root?.packageName?.toString()
                } finally {
                    root?.recycle()
                }
            } else {
                null
            }
            HudMapWindow(
                packageName = packageName,
                type = type,
                layer = window.layer,
                bounds = PxRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                title = window.title?.toString(),
            )
        }
    }

    private companion object {
        const val STALLED_MS = 2_000L
    }
}
