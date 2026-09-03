package dev.denza.apps.feature.speaker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The whole feature, as a table of what the car is told and when.
 *
 * The covers are not driven here and cannot be: the amplifier owns the motor, raises while it
 * believes music is playing, and lowers on its own at power off. All this policy decides is when to
 * tell it the truth about a player the car does not recognise.
 */
class SpeakerCoverPolicyTest {

    private fun steps(
        trigger: SpeakerCoverTrigger,
        featureEnabled: Boolean = true,
        autoLift: SpeakerCoverAutoLift = SpeakerCoverAutoLift.ENABLED,
    ) = SpeakerCoverPolicy.steps(trigger, featureEnabled, autoLift)

    @Test
    fun playbackFromAnUnknownPlayerIsReported() {
        assertEquals(
            listOf(SpeakerCoverStep.REPORT_PLAYING),
            steps(SpeakerCoverTrigger.Playback("ru.yandex.music")),
        )
    }

    @Test
    fun playbackFromAPlayerTheCarReportsForIsLeftAlone() {
        assertEquals(emptyList<SpeakerCoverStep>(), steps(SpeakerCoverTrigger.Playback("com.byd.mediacenter")))
        assertEquals(emptyList<SpeakerCoverStep>(), steps(SpeakerCoverTrigger.Playback("com.tencent.qqmusiccar")))
        assertEquals(emptyList<SpeakerCoverStep>(), steps(SpeakerCoverTrigger.Playback("com.android.bluetooth")))
    }

    @Test
    fun playbackIsIgnoredWhileTheFeatureIsOff() {
        assertEquals(
            emptyList<SpeakerCoverStep>(),
            steps(SpeakerCoverTrigger.Playback("ru.yandex.music"), featureEnabled = false),
        )
    }

    @Test
    fun disabledAutoLiftIsEnabledBeforeTheReport() {
        assertEquals(
            listOf(SpeakerCoverStep.ENABLE_AUTO_LIFT, SpeakerCoverStep.REPORT_PLAYING),
            steps(
                SpeakerCoverTrigger.Playback("ru.yandex.music"),
                autoLift = SpeakerCoverAutoLift.DISABLED,
            ),
        )
    }

    @Test
    fun unknownAutoLiftIsEnabledBeforeTheReport() {
        assertEquals(
            listOf(SpeakerCoverStep.ENABLE_AUTO_LIFT, SpeakerCoverStep.REPORT_PLAYING),
            steps(
                SpeakerCoverTrigger.Playback("ru.yandex.music"),
                autoLift = SpeakerCoverAutoLift.UNKNOWN,
            ),
        )
    }

    @Test
    fun openingAKnownPlayerReportsBeforeItMakesASound() {
        assertEquals(
            listOf(SpeakerCoverStep.REPORT_PLAYING),
            steps(SpeakerCoverTrigger.PlayerOpened("ru.yandex.music")),
        )
    }

    @Test
    fun openingSomethingThatIsNotAPlayerDoesNothing() {
        assertEquals(
            emptyList<SpeakerCoverStep>(),
            steps(SpeakerCoverTrigger.PlayerOpened("com.byd.carsettings")),
        )
    }

    @Test
    fun openingAPlayerTheCarReportsForIsLeftAlone() {
        assertEquals(
            emptyList<SpeakerCoverStep>(),
            steps(SpeakerCoverTrigger.PlayerOpened("com.byd.mediacenter")),
        )
    }

    @Test
    fun theRaiseButtonWorksWhileTheFeatureIsOff() {
        assertEquals(
            listOf(SpeakerCoverStep.REPORT_PLAYING),
            steps(SpeakerCoverTrigger.RaisePressed, featureEnabled = false),
        )
    }

    @Test
    fun theRaiseButtonEnablesAutoLiftFirstWhenItIsOff() {
        assertEquals(
            listOf(SpeakerCoverStep.ENABLE_AUTO_LIFT, SpeakerCoverStep.REPORT_PLAYING),
            steps(
                SpeakerCoverTrigger.RaisePressed,
                featureEnabled = false,
                autoLift = SpeakerCoverAutoLift.DISABLED,
            ),
        )
    }

    @Test
    fun switchingTheFeatureOnRaisesTheCoversOnBothCars() {
        assertEquals(
            listOf(SpeakerCoverStep.ENABLE_AUTO_LIFT, SpeakerCoverStep.REPORT_PLAYING),
            steps(SpeakerCoverTrigger.FeatureEnabled, autoLift = SpeakerCoverAutoLift.DISABLED),
        )
        assertEquals(
            listOf(SpeakerCoverStep.REPORT_PLAYING),
            steps(SpeakerCoverTrigger.FeatureEnabled, autoLift = SpeakerCoverAutoLift.ENABLED),
        )
    }

    @Test
    fun switchingTheFeatureOffHidesTheCoversAndNothingElse() {
        assertEquals(
            listOf(SpeakerCoverStep.HIDE),
            steps(SpeakerCoverTrigger.FeatureDisabled, featureEnabled = false),
        )
    }

    @Test
    fun nothingEverAsksForTheCoversToBeHiddenExceptSwitchingOff() {
        val everythingElse = listOf(
            SpeakerCoverTrigger.Playback("ru.yandex.music"),
            SpeakerCoverTrigger.PlayerOpened("ru.yandex.music"),
            SpeakerCoverTrigger.RaisePressed,
            SpeakerCoverTrigger.FeatureEnabled,
        )
        val hides = everythingElse.flatMap { trigger ->
            SpeakerCoverAutoLift.entries.flatMap { lift ->
                listOf(true, false).flatMap { enabled -> steps(trigger, enabled, lift) }
            }
        }.filter { it == SpeakerCoverStep.HIDE }
        assertEquals(emptyList<SpeakerCoverStep>(), hides)
    }

    @Test
    fun aNullPackageIsNeverReportedFor() {
        assertEquals(emptyList<SpeakerCoverStep>(), steps(SpeakerCoverTrigger.Playback(null)))
        assertEquals(emptyList<SpeakerCoverStep>(), steps(SpeakerCoverTrigger.PlayerOpened(null)))
    }
}
