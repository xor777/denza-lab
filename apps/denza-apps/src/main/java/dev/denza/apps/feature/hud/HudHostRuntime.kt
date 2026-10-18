package dev.denza.apps.feature.hud

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
}
