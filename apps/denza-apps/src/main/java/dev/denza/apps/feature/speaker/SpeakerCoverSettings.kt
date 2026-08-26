package dev.denza.apps.feature.speaker

import android.annotation.SuppressLint
import android.content.Context

/** Persistent user ownership of the speaker-cover automation. */
object SpeakerCoverSettings {
    private const val PREFS = "speaker_covers"
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
