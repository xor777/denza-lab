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
    private const val DRIVER_TOOK_OVER_BOOT = "driver_took_over_boot"
    private const val AUTO_OPENED_BOOT = "auto_opened_boot"

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

    /**
     * Whether the driver has pressed one of the panel's two buttons **during this boot**.
     *
     * From that press until the next ignition cycle the covers are the driver's, and the automation
     * offers nothing. It has to survive the process because the service is restartable and the car
     * is not: a service killed and revived mid-boot would otherwise wake up believing nobody had
     * touched anything and re-open covers that were deliberately shut a minute earlier.
     *
     * Boot-scoped for the same reason as [lastCommandValue] - the amplifier retracts the covers at
     * power-off, so each start is a clean slate and the automation is owed its one opening again.
     */
    fun driverTookOver(context: Context): Boolean = thisBoot(context, DRIVER_TOOK_OVER_BOOT)

    fun rememberDriverTookOver(context: Context) = stampBoot(context, DRIVER_TOOK_OVER_BOOT)

    /**
     * Whether the automation has already spent its one opening **during this boot**.
     *
     * Written only after the command is acknowledged, so a start that failed to reach the motor is
     * still owed its try. Without this, restarting the process would hand the automation a second
     * opening it never had - and the driver would see the covers come back out after closing them
     * and killing the app, which reads as the feature ignoring both.
     */
    fun autoOpened(context: Context): Boolean = thisBoot(context, AUTO_OPENED_BOOT)

    fun rememberAutoOpened(context: Context) = stampBoot(context, AUTO_OPENED_BOOT)

    /**
     * Switching the automation on, which is the driver handing the wheel back.
     *
     * Both flags are one-way inside a boot on purpose - nothing an automation notices may cancel a
     * press. But turning the feature on is not something it noticed: it is a deliberate act, newer
     * than any button pressed before it, and it can only mean "you do this now". So it clears both,
     * and the automation is owed its one opening again for the rest of this boot. Without this a
     * driver who pressed «Опустить» in the morning would switch the feature on in the afternoon and
     * get a panel that says «Управление у водителя» and covers that never move, with nothing short
     * of an ignition cycle to argue with.
     */
    @SuppressLint("UseKtx")
    fun rearm(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(DRIVER_TOOK_OVER_BOOT)
            .remove(AUTO_OPENED_BOOT)
            .apply()
    }

    private fun thisBoot(context: Context, key: String): Boolean {
        val stamp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key, 0L)
        return stamp != 0L && Math.abs(bootStamp() - stamp) <= BOOT_SLACK_MS
    }

    @SuppressLint("UseKtx")
    private fun stampBoot(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(key, bootStamp())
            .apply()
    }
}
