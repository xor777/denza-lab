package dev.denza.apps.feature.speaker

import android.annotation.SuppressLint
import android.content.Context

/**
 * Persistent user ownership of the speaker-cover feature, and nothing else.
 *
 * There used to be five more facts here, all scoped to a trip: the value last written to the
 * amplifier's cover property, whether the driver had taken over, whether the one automatic opening
 * had been spent, and two clock stamps deciding what a trip was. Every one of them existed to keep
 * an edge-triggered motor command honest across a process death.
 *
 * The app no longer sends motor commands. It reports playback, the amplifier decides, and a report
 * that arrives twice costs nothing - so there is nothing left to remember between runs but the
 * switch the driver set.
 */
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
