package dev.denza.apps.feature.speaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as "this trip" on a head unit that suspends instead of rebooting.
 *
 * The three facts the feature persists - the value last written to the cover property, the driver's
 * takeover, the spent one-shot - were scoped to the kernel boot, which on a phone is the same thing
 * as a trip and on this car is not. Measured on 2026-08-28: 31.4 hours of boot against about 7
 * hours awake, and stamps written the previous day matching the current boot stamp to within 13 ms.
 * The amplifier had been power-cycled twice in between. The covers stayed shut through a whole trip
 * with music playing, and nothing in the app could tell why.
 *
 * So a fact is live only while the machine has stayed awake since it was written. Everything below
 * is that sentence from a different side.
 */
class SpeakerCoverFactScopeTest {

    /** An ordinary trip: the same boot, and the machine has not been off since. */
    @Test
    fun aFactWrittenThisTripIsStillTheCarInFrontOfYou() {
        assertTrue(
            SpeakerCoverFactScope.isLive(
                stampBoot = 1_700_000_000_000L,
                stampAsleep = 900_000L,
                nowBootStamp = 1_700_000_000_000L,
                nowAsleep = 900_000L,
            ),
        )
        // A minute of the display dozing, an hour into the trip: nothing an ignition did.
        assertTrue(
            SpeakerCoverFactScope.isLive(
                stampBoot = 1_700_000_000_000L,
                stampAsleep = 900_000L,
                nowBootStamp = 1_700_000_000_000L,
                nowAsleep = 900_000L + 42_000L,
            ),
        )
    }

    /**
     * The reported bug, which is the entire reason this object exists.
     *
     * Yesterday evening's press, read this morning. The boot stamp agrees to the millisecond -
     * Android never went down - and under the old rule that alone made the fact live: the automation
     * stood down for a trip whose covers it had never opened.
     */
    @Test
    fun aFactThatSleptThroughTheNightIsNotAboutThisTrip() {
        val boot = 1_700_000_000_000L
        assertFalse(
            SpeakerCoverFactScope.isLive(
                stampBoot = boot,
                stampAsleep = 7 * 3_600_000L,
                nowBootStamp = boot + 13L, // the 13 ms of drift actually measured on the car
                nowAsleep = 7 * 3_600_000L + 17 * 3_600_000L,
            ),
        )
    }

    /** A real restart still ends everything, whatever the sleep counters say about it. */
    @Test
    fun aDifferentBootIsExpiredNoMatterHowAwakeTheMachineHasBeen() {
        assertFalse(
            SpeakerCoverFactScope.isLive(
                stampBoot = 1_700_000_000_000L,
                stampAsleep = 0L,
                nowBootStamp = 1_700_000_000_000L + 3_600_000L,
                nowAsleep = 0L,
            ),
        )
        // Including the case where the sleep counter has been reset below the stored one, which is
        // what a fresh boot looks like from here.
        assertFalse(
            SpeakerCoverFactScope.isLive(
                stampBoot = 1_700_000_000_000L,
                stampAsleep = 5_000_000L,
                nowBootStamp = 1_700_000_000_000L - 86_400_000L,
                nowAsleep = 0L,
            ),
        )
    }

    /**
     * Both edges, pinned, because "about a minute" is not a rule anyone can implement twice.
     *
     * Boot slack is inclusive: exactly 30 s of drift is still the same boot, one millisecond more
     * is not. Sleep is exclusive: exactly 60 s asleep is already a different trip. The asymmetry is
     * deliberate - slack is measurement error and is forgiven, sleep is the fact itself and the safe
     * direction is to expire early.
     */
    @Test
    fun theTwoThresholdsSitWhereTheyAreWritten() {
        val boot = 1_700_000_000_000L
        fun live(bootNow: Long, asleepNow: Long) = SpeakerCoverFactScope.isLive(
            stampBoot = boot,
            stampAsleep = 0L,
            nowBootStamp = bootNow,
            nowAsleep = asleepNow,
        )

        assertTrue(live(boot + SpeakerCoverFactScope.BOOT_SLACK_MS, 0L))
        assertTrue(live(boot - SpeakerCoverFactScope.BOOT_SLACK_MS, 0L))
        assertFalse(live(boot + SpeakerCoverFactScope.BOOT_SLACK_MS + 1L, 0L))
        assertFalse(live(boot - SpeakerCoverFactScope.BOOT_SLACK_MS - 1L, 0L))

        assertTrue(live(boot, SpeakerCoverFactScope.MAX_SLEEP_MS - 1L))
        assertFalse(live(boot, SpeakerCoverFactScope.MAX_SLEEP_MS))
        assertFalse(live(boot, SpeakerCoverFactScope.MAX_SLEEP_MS + 1L))
    }

    /**
     * The same two edges said in milliseconds, because the widths are the decision.
     *
     * The test above states them in terms of the constants, so it holds however wide the constants
     * get - a sleep window of ten minutes would pass it unchanged, and ten minutes is a coffee stop
     * with the amplifier down and the covers back in. That is the night of 2026-08-28 in miniature,
     * so the numbers themselves are the behaviour: a minute of sleep ends the trip, thirty seconds of
     * wall-clock drift does not end the boot.
     */
    @Test
    fun theRuleIsAMinuteOfSleepAndThirtySecondsOfDrift() {
        val boot = 1_700_000_000_000L
        fun live(bootNow: Long, asleepNow: Long) = SpeakerCoverFactScope.isLive(
            stampBoot = boot,
            stampAsleep = 0L,
            nowBootStamp = bootNow,
            nowAsleep = asleepNow,
        )

        assertTrue(live(boot, 59_999L))
        assertFalse(live(boot, 60_000L))

        assertTrue(live(boot + 30_000L, 0L))
        assertFalse(live(boot + 30_001L, 0L))
    }

    /**
     * Preferences written by the version that did not know about sleep.
     *
     * They carry a boot stamp and nothing else, and they are precisely the day-old facts this rule
     * was written to throw away - so absence has to read as expired rather than as "no sleep". The
     * first install after the fix therefore starts every trip owing the automation its opening,
     * which is the state a fresh trip is in anyway.
     */
    @Test
    fun aStampWithNoSleepRecordIsExpired() {
        val boot = 1_700_000_000_000L
        assertFalse(
            SpeakerCoverFactScope.isLive(
                stampBoot = boot,
                stampAsleep = null,
                nowBootStamp = boot,
                nowAsleep = 0L,
            ),
        )
        // -1 is how the preferences say "absent" for a number that is never negative.
        assertFalse(
            SpeakerCoverFactScope.isLive(
                stampBoot = boot,
                stampAsleep = -1L,
                nowBootStamp = boot,
                nowAsleep = 0L,
            ),
        )
        // And nothing written at all is nothing to honour.
        assertFalse(
            SpeakerCoverFactScope.isLive(
                stampBoot = null,
                stampAsleep = 0L,
                nowBootStamp = boot,
                nowAsleep = 0L,
            ),
        )
        assertFalse(
            SpeakerCoverFactScope.isLive(
                stampBoot = 0L,
                stampAsleep = 0L,
                nowBootStamp = boot,
                nowAsleep = 0L,
            ),
        )
    }

    /**
     * The unwritten stamp, read on a car that has not been told the time yet.
     *
     * Nothing above pins this: the assertions on a stored 0 all pass because 0 is a year and a half
     * of drift away from a plausible wall clock, not because anything rejected it. But the stamp is
     * `currentTimeMillis` less `elapsedRealtime`, and a head unit that wakes before the network or
     * GNSS corrects it holds a clock near the epoch - so the current stamp is itself a few seconds
     * from zero, and there the two are indistinguishable by arithmetic. A fact nobody ever stored
     * would then read as this trip's, which is the same failure as the day-old one and quieter.
     */
    @Test
    fun anUnwrittenStampIsExpiredEvenWhenTheClockAgreesWithIt() {
        assertFalse(
            SpeakerCoverFactScope.isLive(
                stampBoot = 0L,
                stampAsleep = 0L,
                nowBootStamp = 4_000L,
                nowAsleep = 0L,
            ),
        )
    }

    /**
     * Why the boot comparison has slack at all: the wall clock is corrected while the car sits.
     *
     * GNSS or the network moves the clock a few seconds, the stamp moves with it, and nothing has
     * restarted. The sleep record is the second opinion that keeps this honest - it barely moved,
     * so the trip is the same one, and a fact that would have survived the old rule survives this
     * one too.
     */
    @Test
    fun aCorrectedWallClockIsNotARestart() {
        val boot = 1_700_000_000_000L
        assertTrue(
            SpeakerCoverFactScope.isLive(
                stampBoot = boot,
                stampAsleep = 120_000L,
                nowBootStamp = boot + 4_000L,
                nowAsleep = 120_000L + 250L,
            ),
        )
    }

    /**
     * The millisecond of skew between two clock reads, which is not a trip going backwards.
     *
     * `elapsedRealtime` and `uptimeMillis` are read one after the other rather than atomically, so
     * a tick landing between them can make the stored total a hair larger than the current one.
     * Within a boot the real total only grows; a negative delta is arithmetic, and it means nothing
     * has slept.
     */
    @Test
    fun aMillisecondOfClockSkewStillReadsAsAwake() {
        val boot = 1_700_000_000_000L
        assertTrue(
            SpeakerCoverFactScope.isLive(
                stampBoot = boot,
                stampAsleep = 5_000L,
                nowBootStamp = boot,
                nowAsleep = 4_999L,
            ),
        )
    }
}
