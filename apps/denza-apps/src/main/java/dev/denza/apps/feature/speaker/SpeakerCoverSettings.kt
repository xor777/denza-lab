package dev.denza.apps.feature.speaker

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock

/** Persistent user ownership of the speaker-cover automation. */
object SpeakerCoverSettings {
    private const val PREFS = "speaker_covers"
    private const val ENABLED = "enabled"
    private const val LAST_COMMAND = "last_command_value"
    private const val LAST_COMMAND_BOOT = "last_command_boot"

    /**
     * Two clocks read together name the boot: wall time now, less how long the machine has been up.
     *
     * It drifts by a second or two as the wall clock is corrected, which is why the comparison
     * below has slack rather than being an equality.
     */
    private fun bootStamp(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private const val BOOT_SLACK_MS = 30_000L

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
     * The last value this app wrote to the amplifier's cover property **during this boot**.
     *
     * Not a claim about where the covers are - the amplifier lowers them on its own and says
     * nothing. It is only what the edge-triggered property last saw, which is knowable because
     * this app is its only writer, and which decides whether the next write can move anything.
     *
     * Scoped to the boot because the amplifier goes down with the car. A value remembered across
     * an ignition cycle would be a guess about firmware that has been power-cycled since, and the
     * guess fails silently: the app writes the value it thinks is already there, the property does
     * not change, no motor moves, and the feature looks dead with nothing to read. Forgetting costs
     * one close-then-open pair on the first command after a start - and the covers are retracted at
     * that point, so the close moves nothing and only the open is seen.
     */
    fun lastCommandValue(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = prefs.getInt(LAST_COMMAND, 0).takeIf { it != 0 } ?: return null
        val boot = prefs.getLong(LAST_COMMAND_BOOT, 0L)
        val sameBoot = boot != 0L && Math.abs(bootStamp() - boot) <= BOOT_SLACK_MS
        return value.takeIf { sameBoot }
    }

    @SuppressLint("UseKtx")
    fun rememberCommandValue(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(LAST_COMMAND, value)
            .putLong(LAST_COMMAND_BOOT, bootStamp())
            .apply()
    }
}
