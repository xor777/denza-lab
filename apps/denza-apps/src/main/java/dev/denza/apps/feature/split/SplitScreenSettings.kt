package dev.denza.apps.feature.split

import android.annotation.SuppressLint
import android.content.Context

object SplitScreenSettings {
    private const val PREFS = "denza_split_screen"
    private const val ENABLED = "enabled"

    /**
     * The single chokepoint for the feature being off.
     *
     * Reporting `false` here rather than editing each consumer keeps the
     * coordinator, the navigation router and the simulcast overlay untouched:
     * they all already handle the feature being disabled, and the stored
     * preference survives for whenever [SplitScreenFlag] goes back on.
     */
    fun isEnabled(context: Context): Boolean =
        SplitScreenFlag.ENABLED &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    // Keep the persistent write boundary explicit for split-screen recovery.
    @SuppressLint("UseKtx")
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()
    }
}
