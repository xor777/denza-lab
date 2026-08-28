package dev.denza.apps.feature.speaker

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

/** Persistent user ownership of the speaker-cover automation. */
object SpeakerCoverSettings {
    private const val PREFS = "speaker_covers"
    private const val ENABLED = "enabled"
    private const val LAST_COMMAND = "last_command_value"
    private const val LAST_COMMAND_BOOT = "last_command_boot"
    private const val LAST_COMMAND_ASLEEP = "last_command_asleep"
    private const val DRIVER_TOOK_OVER_BOOT = "driver_took_over_boot"
    private const val DRIVER_TOOK_OVER_ASLEEP = "driver_took_over_asleep"
    private const val AUTO_OPENED_BOOT = "auto_opened_boot"
    private const val AUTO_OPENED_ASLEEP = "auto_opened_asleep"

    /**
     * Two clocks read together name the boot: wall time now, less how long the machine has been up.
     *
     * It drifts by a second or two as the wall clock is corrected, which is why the comparison in
     * [SpeakerCoverFactScope] has slack rather than being an equality.
     */
    private fun bootStamp(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /**
     * How long this boot has spent in deep sleep, which on this car is how a trip ends.
     *
     * The head unit suspends at ignition off instead of rebooting, so the boot stamp above goes on
     * naming the same boot for days while the car is started and stopped underneath it. This is the
     * number that notices: `elapsedRealtime` keeps counting through the suspend, `uptimeMillis` does
     * not, and the gap between them only grows. Stored beside each stamp, it turns "written in this
     * boot" into "written in this waking" - see [SpeakerCoverFactScope] for what that cost us.
     */
    private fun asleepTotal(): Long = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()

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
     * The last value this app wrote to the amplifier's cover property **during this trip**.
     *
     * Not a claim about where the covers are - the amplifier lowers them on its own and says
     * nothing. It is only what the edge-triggered property last saw, which is knowable because
     * this app is its only writer, and which decides whether the next write can move anything.
     *
     * Scoped to the trip because the amplifier goes down with the car. A value remembered across an
     * ignition cycle would be a guess about firmware that has been power-cycled since, and the guess
     * fails silently: the app writes the value it thinks is already there, the property does not
     * change, no motor moves, and the feature looks dead with nothing to read. That is the fault
     * that was seen on 2026-08-28, when the trip was still being measured in kernel boots on a head
     * unit that suspends rather than restarting. Forgetting costs one close-then-open pair on the
     * first command after a start - and the covers are retracted at that point, so the close moves
     * nothing and only the open is seen.
     */
    fun lastCommandValue(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = prefs.getInt(LAST_COMMAND, 0).takeIf { it != 0 } ?: return null
        return value.takeIf { live(prefs, LAST_COMMAND_BOOT, LAST_COMMAND_ASLEEP) }
    }

    @SuppressLint("UseKtx")
    fun rememberCommandValue(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(LAST_COMMAND, value)
            .stamp(LAST_COMMAND_BOOT, LAST_COMMAND_ASLEEP)
            .apply()
    }

    /**
     * Whether the driver has pressed one of the panel's two buttons **during this trip**.
     *
     * From that press until the car is switched off the covers are the driver's, and the automation
     * offers nothing. It has to survive the process because the service is restartable and the trip
     * is not: a service killed and revived mid-trip would otherwise wake up believing nobody had
     * touched anything and re-open covers that were deliberately shut a minute earlier.
     *
     * Trip-scoped for the same reason as [lastCommandValue] - the amplifier retracts the covers at
     * power-off, so each start is a clean slate and the automation is owed its one opening again.
     */
    fun driverTookOver(context: Context): Boolean =
        thisTrip(context, DRIVER_TOOK_OVER_BOOT, DRIVER_TOOK_OVER_ASLEEP)

    fun rememberDriverTookOver(context: Context) =
        stampTrip(context, DRIVER_TOOK_OVER_BOOT, DRIVER_TOOK_OVER_ASLEEP)

    /**
     * Whether the automation has already spent its one opening **during this trip**.
     *
     * Written only after the command is acknowledged, so a start that failed to reach the motor is
     * still owed its try. Without this, restarting the process would hand the automation a second
     * opening it never had - and the driver would see the covers come back out after closing them
     * and killing the app, which reads as the feature ignoring both.
     */
    fun autoOpened(context: Context): Boolean =
        thisTrip(context, AUTO_OPENED_BOOT, AUTO_OPENED_ASLEEP)

    fun rememberAutoOpened(context: Context) = stampTrip(context, AUTO_OPENED_BOOT, AUTO_OPENED_ASLEEP)

    /**
     * Switching the automation on, which is the driver handing the wheel back.
     *
     * Both flags are one-way inside a trip on purpose - nothing an automation notices may cancel a
     * press. But turning the feature on is not something it noticed: it is a deliberate act, newer
     * than any button pressed before it, and it can only mean "you do this now". So it clears both,
     * and the automation is owed its one opening again for the rest of this trip. Without this a
     * driver who pressed «Опустить» in the morning would switch the feature on in the afternoon and
     * get a panel that says «Управление у водителя» and covers that never move, with nothing short
     * of an ignition cycle to argue with.
     *
     * Both halves of each stamp go, boot and sleep together. A cleared boot stamp already reads as
     * expired, but leaving the sleep record behind would be leaving half a fact in the file for a
     * later version to trip over.
     *
     * **And the property memory goes with them.** Clearing only the two flags left the third fact
     * standing, and it is the one that can silently swallow the opening the other two just bought:
     * the automation opens (a `1` is remembered), the amplifier retracts the covers by itself, the
     * driver toggles the feature off and on, music plays, the re-armed one-shot asks for OPEN - and
     * [SpeakerCoverMotorProtocol.needsEdgeBreak] sees an [SpeakerCoverCommandSource.AUTOMATIC] `1`
     * against a remembered `1`, writes it once, and moves nothing. The HAL acknowledges,
     * `auto_opened` is written, and the tile says «Автоматика отработала» over closed covers. That
     * is the same silent no-op
     * [SpeakerCoverFactScope] was built against, arriving from the property side instead of the
     * clock side, and a deliberate toggle is precisely the moment to stop trusting a remembered
     * value.
     *
     * The price is the once-per-trip forced pair on the next command from the automation or a
     * button, and its visibility is asymmetric in our favour: in the failing case the covers are
     * already down, so the pair's close moves nothing and only the open is seen. On covers that are
     * up it costs one dip-and-rise immediately after a switch the driver just flipped, where
     * movement reads as the feature answering. A deterministic opening beats a correctly-predicted
     * no-op.
     *
     * The parting open is *not* in that price, and the first version of this change let it be. It
     * reaches the motor with nothing remembered too - a quick off-on-off, or an on-off with no
     * playback in between, never writes anything - and back when the edge rule was asked only
     * "manual or not", it took the automation's branch and bought the pair. The covers went out and
     * in as the toggle went off, which is the one thing switching a feature off must not look like.
     * [SpeakerCoverCommandSource.BEST_EFFORT] now buys no pair at all, so wiping the value here
     * costs that path a possible no-op and never a twitch.
     */
    @SuppressLint("UseKtx")
    fun rearm(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(DRIVER_TOOK_OVER_BOOT)
            .remove(DRIVER_TOOK_OVER_ASLEEP)
            .remove(AUTO_OPENED_BOOT)
            .remove(AUTO_OPENED_ASLEEP)
            .remove(LAST_COMMAND)
            .remove(LAST_COMMAND_BOOT)
            .remove(LAST_COMMAND_ASLEEP)
            .apply()
    }

    private fun thisTrip(context: Context, bootKey: String, asleepKey: String): Boolean =
        live(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), bootKey, asleepKey)

    /**
     * The two stored numbers and the two current ones, handed to the rule that decides.
     *
     * `-1` for the sleep record means "this file was written before the app knew to record it",
     * which [SpeakerCoverFactScope] reads as expired - a real total is never negative, and zero is a
     * machine that has simply never slept.
     */
    private fun live(prefs: SharedPreferences, bootKey: String, asleepKey: String): Boolean =
        SpeakerCoverFactScope.isLive(
            stampBoot = prefs.getLong(bootKey, 0L),
            stampAsleep = prefs.getLong(asleepKey, -1L),
            nowBootStamp = bootStamp(),
            nowAsleep = asleepTotal(),
        )

    @SuppressLint("UseKtx")
    private fun stampTrip(context: Context, bootKey: String, asleepKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .stamp(bootKey, asleepKey)
            .apply()
    }

    private fun SharedPreferences.Editor.stamp(
        bootKey: String,
        asleepKey: String,
    ): SharedPreferences.Editor = putLong(bootKey, bootStamp()).putLong(asleepKey, asleepTotal())
}
