package dev.denza.apps.feature.hud

import android.graphics.Bitmap
import android.os.Handler

/**
 * Where the map window's pictures come from: a picture of the screen Yandex is drawn on, and where
 * Yandex is in it.
 *
 * The pipeline behind it - the cursor, the cut, the key, the PNG and the send ([HudMapPipeline],
 * [HudMapRunner]) - does not know how the picture was taken, and a source does not know what is
 * done with it. The first source is the accessibility service's screenshot of the main screen
 * ([HudMapScreenshotSource]), which the firmware holds under 3 frames a second; the map window
 * wants 10 or more, so a faster source takes its place without the pipeline changing. The pace is
 * the source's alone: it delivers when it has a frame, and nothing downstream assumes an interval.
 *
 * Every call to a source, and every call it makes back, is on the one thread whose [Handler] it is
 * started with.
 */
interface HudMapFrameSource {
    /** What «Сервис» calls this source. */
    val name: String

    /** The shortest pause this source keeps between two frames; its own limit, for the report. */
    val frameIntervalMs: Long

    /** Starts delivering to [sink] on [handler]'s thread. Starting a started source does nothing. */
    fun start(handler: Handler, sink: HudMapFrameSink)

    /** Stops; nothing reaches the sink after this returns. Safe to repeat. */
    fun stop()
}

/**
 * One picture of the screen Yandex is on. [image] - a hardware bitmap as a rule - is the source's
 * and is valid only until [HudMapFrameSink.onFrame] returns; [window] and [above] are in its pixels;
 * [density] is that screen's pixels per dp.
 */
class HudMapFrame(
    val image: Bitmap,
    val window: PxRect,
    val above: List<HudMapWindow>,
    val density: Float,
)

/** What a source tells the pipeline, on the source's thread. */
interface HudMapFrameSink {
    fun onFrame(frame: HudMapFrame)

    /**
     * No frame, and why: [HudMapState.NOT_ON_SCREEN] while Yandex is not where the source looks,
     * [HudMapState.NO_CAPABILITY] when the source cannot capture at all. A frame the source asked
     * for and did not get once is not a gap: it counts in [HudMapRuntime.onCaptureFailure], and the
     * map window keeps its last frame.
     */
    fun onGap(state: HudMapState)
}
