package dev.denza.apps.feature.speaker

import kotlin.math.abs

/**
 * Whether something this app remembered still describes the car that is reading it back.
 *
 * The feature keeps three facts across a process death - the value last written to the amplifier's
 * cover property, that the driver has taken the covers over, that the one automatic opening has
 * been spent - and every one of them is true only for as long as the trip it was written in. The
 * question this object answers is what a trip is, and the first answer was wrong.
 *
 * It was "a kernel boot", on the assumption that turning the ignition off restarts Android. This
 * head unit does not restart: **it suspends**. Measured on 2026-08-28, the kernel had been booted
 * 31.4 hours while the machine had only been awake for about 7 of them, and stamps written the
 * previous day matched the current boot stamp to within 13 ms. The amplifier, meanwhile, does go
 * down with the ignition - it takes its cover state with it and physically retracts them. So the
 * three facts stayed "live" across a night the car had spent switched off, and the result from the
 * seat was music playing on a fresh trip with the covers never coming out: the automation believed
 * the driver had taken over (yesterday), that its one shot was spent (yesterday), and the value it
 * remembered writing (yesterday) made an automatic open a no-op against firmware power-cycled since.
 *
 * So sleep is how this car says the trip ended. Android has two monotonic clocks and their
 * difference is the whole answer: `uptimeMillis` stops in deep sleep, `elapsedRealtime` does not,
 * and `elapsedRealtime - uptimeMillis` is how long this boot has spent asleep - a number that only
 * ever grows. Recording it beside each fact turns "same boot" into "same waking", and a fact that
 * has slept through an ignition-off no longer speaks for the car it wakes up in.
 *
 * Expiring early is the safe direction, which is why the threshold is a minute rather than an hour.
 * The worst case is a stop short enough that the amplifier stayed up: the covers and the property
 * are then where the app left them, the facts expire anyway, and the once-per-trip forced edge pair
 * costs one visible twitch - the price this design already documents for a property it cannot read.
 * The other direction has no such floor, as the night of 2026-08-28 showed.
 */
internal object SpeakerCoverFactScope {

    /**
     * How far the boot stamp may drift and still name the same boot.
     *
     * The stamp is wall time less time since boot, so correcting the wall clock moves it by a
     * second or two without anything having restarted. Hence slack rather than equality.
     */
    const val BOOT_SLACK_MS = 30_000L

    /** How much sleep since the write is still the same trip. Sixty seconds is not a stop. */
    const val MAX_SLEEP_MS = 60_000L

    /**
     * Live iff written in this boot **and** the machine has barely slept since.
     *
     * Both stamps are needed and a missing one is fatal, which is not a technicality: preferences
     * written by an earlier version of this app carry a boot stamp and no sleep record at all, and
     * those are exactly the day-old facts this rule exists to throw away. Absent reads as expired.
     *
     * The sleep comparison is strict: exactly [maxSleepMs] of sleep since the write is already too
     * much. The delta can come out a millisecond below zero - the two clocks are read one after the
     * other, not atomically, so a tick can land between them - and that is treated as no sleep,
     * because within one boot the asleep total cannot genuinely go backwards.
     */
    fun isLive(
        stampBoot: Long?,
        stampAsleep: Long?,
        nowBootStamp: Long,
        nowAsleep: Long,
        bootSlackMs: Long = BOOT_SLACK_MS,
        maxSleepMs: Long = MAX_SLEEP_MS,
    ): Boolean {
        // 0 is how an unwritten `getLong` answers, and a negative sleep total cannot be a reading.
        val boot = stampBoot?.takeIf { it != 0L } ?: return false
        val asleep = stampAsleep?.takeIf { it >= 0L } ?: return false
        if (abs(nowBootStamp - boot) > bootSlackMs) return false
        return nowAsleep - asleep < maxSleepMs
    }
}
