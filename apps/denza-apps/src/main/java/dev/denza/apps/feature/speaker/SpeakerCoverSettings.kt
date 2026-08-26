package dev.denza.apps.feature.speaker

import android.annotation.SuppressLint
import android.content.Context

/** Persistent user ownership of the speaker-cover automation. */
object SpeakerCoverSettings {
    private const val PREFS = "speaker_covers"
    private const val ENABLED = "enabled"
    private const val LAST_COMMAND = "last_command_value"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    @SuppressLint("UseKtx")
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()
    }

    /**
     * The last value this app wrote to the amplifier's cover property, or null before the first.
     *
     * Not a claim about where the covers are - the amplifier lowers them on its own and says
     * nothing. It is only what the edge-triggered property last saw, which is knowable because
     * this app is its only writer, and which decides whether the next write moves anything.
     */
    fun lastCommandValue(context: Context): Int? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(LAST_COMMAND, 0)
            .takeIf { it != 0 }

    @SuppressLint("UseKtx")
    fun rememberCommandValue(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(LAST_COMMAND, value)
            .apply()
    }
}
