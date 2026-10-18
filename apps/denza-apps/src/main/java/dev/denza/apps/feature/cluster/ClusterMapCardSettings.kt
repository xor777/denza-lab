package dev.denza.apps.feature.cluster

import android.annotation.SuppressLint
import android.content.Context

/** «Карта в правом окне» of the driver's screen panel ([ClusterMapCard]); off until switched on. */
object ClusterMapCardSettings {
    private const val PREFS = "denza_cluster_map_card"
    private const val ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    @SuppressLint("UseKtx")
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()
    }
}
