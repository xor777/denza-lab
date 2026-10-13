package dev.denza.apps.feature.hud

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import dev.denza.apps.feature.navigation.NavigationCoordinator
import dev.denza.apps.feature.navigation.NavigationDisplaySurface
import dev.denza.apps.feature.navigation.NavigationPhase
import dev.denza.apps.feature.navigation.NavigationProxyClient
import dev.denza.apps.feature.navigation.NavigationSession

/**
 * The instruments' source: Yandex on the navigation display this app makes for the cluster
 * (`Denza Navigation`, [NavigationProxyClient.createVirtualDisplay]), read from the surface that
 * display draws into ([HudMapImage.Drawn], [HudMapSurfaceReader]).
 *
 * Yandex is alone on that display and fills it, so its window is the whole display and nothing lies
 * over it. The scene's shades over the map are views of their own above the surface and are not in
 * what is read. The density is the display's own - 85 % of the instruments' 320 dpi, Center 100 % -
 * so the cut keeps its size in dp ([HudMapGeometry.crop]) and the map on the glass keeps one scale
 * on the main screen and in every placement (docs/hud-projection-findings.md, «Приборка: оценка»).
 *
 * Nothing says when the display has drawn a new frame, so the source reads every [frameIntervalMs]
 * (12 a second) while Yandex is there, and answers [HudMapState.NOT_ON_SCREEN] otherwise, then
 * looks again after [ABSENT_MS]. No token is needed: the display and the surface are this app's.
 *
 * Every call is on the map's thread, the [Handler] it is started with.
 */
internal class HudMapClusterSource @JvmOverloads constructor(
    private val drawnSurface: () -> NavigationDisplaySurface? = { yandexOnInstruments() },
) : HudMapFrameSource {
    override val name: String = "приборка, PixelCopy"
    override val frameIntervalMs: Long = 83L

    private val tick = Runnable { read() }
    private var handler: Handler? = null
    private var sink: HudMapFrameSink? = null

    override fun start(handler: Handler, sink: HudMapFrameSink) {
        if (this.sink != null) return
        this.handler = handler
        this.sink = sink
        handler.post(tick)
    }

    override fun stop() {
        handler?.removeCallbacks(tick)
        sink = null
    }

    private fun read() {
        val sink = sink ?: return
        val handler = handler ?: return
        val started = SystemClock.uptimeMillis()
        val drawn = drawnSurface()
        if (drawn == null) {
            sink.onGap(HudMapState.NOT_ON_SCREEN)
        } else {
            // The frame is made inside onFrame; the next read keeps the pace from this one's start.
            sink.onFrame(
                HudMapFrame(
                    HudMapImage.Drawn(drawn.surface, drawn.width, drawn.height),
                    window(drawn.width, drawn.height),
                    emptyList(),
                    density(drawn.densityDpi),
                ),
            )
        }
        if (this.sink == null) return
        val pause = if (drawn == null) ABSENT_MS else frameIntervalMs - (SystemClock.uptimeMillis() - started)
        handler.postDelayed(tick, pause.coerceAtLeast(0L))
    }

    companion object {
        /** How long the source waits to look again while Yandex is not on the instruments. */
        const val ABSENT_MS = 500L

        /** Yandex's window on its display: all of it. */
        fun window(width: Int, height: Int): PxRect = PxRect(0, 0, width, height)

        /** A display's pixels per dp. */
        fun density(densityDpi: Int): Float = densityDpi / 160f

        /**
         * The navigation display's id while [session] has [navigator] projected on the cluster,
         * else null: another application there, a projection on its way or back, or none.
         */
        fun projectedDisplay(session: NavigationSession, navigator: String): Int? =
            session.virtualDisplayId?.takeIf {
                session.phase == NavigationPhase.PROJECTED && session.projectedPackage == navigator
            }

        /** The surface Yandex is drawn into on the instruments, or null when it is not there. */
        fun yandexOnInstruments(): NavigationDisplaySurface? {
            val displayId = projectedDisplay(NavigationCoordinator.snapshot(), HudGuidanceSettings.NAVIGATOR_PACKAGE)
                ?: return null
            return NavigationProxyClient.drawnSurface(displayId)
        }
    }
}

/**
 * The map window's source wherever Yandex is: [instruments] while Yandex is projected on the
 * cluster, [screen] otherwise. One runs at a time, the choice looked at every [CHOOSE_MS]; a switch
 * stops the one before it starts the other, so the sink never hears both, and «Сервис» is told
 * which one runs. The main screen's mirror goes while Yandex is on the instruments; its token
 * stays ([HudMapGrant]).
 *
 * Every call is on the map's thread, the [Handler] it is started with.
 */
internal class HudMapAutoSource @JvmOverloads constructor(
    private val screen: HudMapFrameSource,
    private val instruments: HudMapFrameSource,
    private val onInstruments: () -> Boolean = { HudMapClusterSource.yandexOnInstruments() != null },
) : HudMapFrameSource {
    override val name: String
        get() = (running ?: screen).name

    override val frameIntervalMs: Long = minOf(screen.frameIntervalMs, instruments.frameIntervalMs)

    private val choose = Runnable { choose() }
    private var handler: Handler? = null
    private var sink: HudMapFrameSink? = null
    private var running: HudMapFrameSource? = null

    override fun start(handler: Handler, sink: HudMapFrameSink) {
        if (this.sink != null) return
        this.handler = handler
        this.sink = sink
        choose()
    }

    override fun stop() {
        handler?.removeCallbacks(choose)
        running?.stop()
        running = null
        sink = null
    }

    private fun choose() {
        val sink = sink ?: return
        val handler = handler ?: return
        val wanted = pick(onInstruments(), screen, instruments)
        if (wanted !== running) {
            running?.stop()
            running = wanted
            HudMapRuntime.onSource("${wanted.name}, ${wanted.frameIntervalMs} мс")
            // One line per switch, never per frame: on the car it says which source a run used.
            Log.i(TAG, "source ${wanted.name}")
            wanted.start(handler, sink)
        }
        handler.postDelayed(choose, CHOOSE_MS)
    }

    companion object {
        /** How often the source looks where Yandex is. */
        const val CHOOSE_MS = 500L

        private const val TAG = "DenzaHudMap"

        fun <T> pick(onInstruments: Boolean, screen: T, instruments: T): T = if (onInstruments) instruments else screen
    }
}
