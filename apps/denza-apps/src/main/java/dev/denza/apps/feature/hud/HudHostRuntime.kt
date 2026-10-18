package dev.denza.apps.feature.hud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.denza.hudlink.HudSource

/**
 * Which sources in other apps this app hosts on the HUD now, for «Сервис» and the log. Main thread.
 */
object HudHostRuntime {
    private const val TAG = "DenzaHudHost"
    private val attached = mutableSetOf<HudSource>()

    fun onAttached(source: HudSource) {
        attached += source
        Log.i(TAG, "hosting $source")
    }

    fun onDetached(source: HudSource) {
        attached -= source
        Log.i(TAG, "no longer hosting $source")
    }

    /** Whether the YandexHUD module writes to the HUD through this app now. */
    fun hostsModule(): Boolean = HudSource.MODULE in attached

    /** A switch of the HUD panel moved; the host applies it on the main thread. */
    fun settingsChanged(context: Context) {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post { HudHostOutput.get(app).onSettingsChanged() }
    }

    /**
     * The HUD's map window as a picture, for a screen beside the HUD: [listener] is told every
     * new PNG, null when the window shows nothing, on the main thread; null stops it. One
     * listener, the instruments' right card (`ClusterMapCardRuntime`). Main thread.
     */
    fun mapFrames(context: Context, listener: ((ByteArray?) -> Unit)?) {
        HudHostOutput.get(context.applicationContext).setMapFrameListener(listener)
    }
}
