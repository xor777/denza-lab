package dev.denza.apps.feature.vehicle

import android.annotation.SuppressLint
import android.content.Context

/**
 * Which consumption window the driver last chose.
 *
 * It is a setting rather than the state of one screen because two screens obey
 * it and only one of them can be touched: the chart on the head unit is where
 * the window is picked, and the driver's cluster - which has no touchscreen -
 * follows whatever was picked there.
 */
internal object ConsumptionSettings {

    private const val PREFS = "denza_consumption"
    private const val WINDOW = "window"

    fun window(context: Context): ConsumptionWindow =
        ConsumptionWindow.byName(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(WINDOW, null),
        )

    // Keep the persistent write boundary explicit, as the other settings do.
    @SuppressLint("UseKtx")
    fun setWindow(context: Context, window: ConsumptionWindow) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(WINDOW, window.name)
            .apply()
    }
}
