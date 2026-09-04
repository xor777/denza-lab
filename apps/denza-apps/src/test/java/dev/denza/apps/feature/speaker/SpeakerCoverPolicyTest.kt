package dev.denza.apps.feature.speaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole feature, as a table of when the car is told that music is playing.
 *
 * The covers are not driven here and cannot be: the amplifier owns the motor, raises while it
 * believes music is playing, and lowers on its own. The stock auto-lift setting is not touched
 * here either - it is the car's switch. All this policy decides is whether now is a moment to tell
 * the car the truth about a player it does not recognise.
 */
class SpeakerCoverPolicyTest {

    private fun reports(trigger: SpeakerCoverTrigger, featureEnabled: Boolean = true) =
        SpeakerCoverPolicy.reports(trigger, featureEnabled)

    @Test
    fun playbackFromAnUnknownPlayerIsReported() {
        assertTrue(reports(SpeakerCoverTrigger.Playback("ru.yandex.music")))
        assertTrue(reports(SpeakerCoverTrigger.Playback("com.some.podcast.app")))
    }

    @Test
    fun playbackFromAPlayerTheCarReportsForIsLeftAlone() {
        assertFalse(reports(SpeakerCoverTrigger.Playback("com.byd.mediacenter")))
        assertFalse(reports(SpeakerCoverTrigger.Playback("com.tencent.qqmusiccar")))
        assertFalse(reports(SpeakerCoverTrigger.Playback("com.android.bluetooth")))
    }

    @Test
    fun playbackIsIgnoredWhileTheFeatureIsOff() {
        assertFalse(reports(SpeakerCoverTrigger.Playback("ru.yandex.music"), featureEnabled = false))
    }

    @Test
    fun openingAKnownPlayerReportsBeforeItMakesASound() {
        assertTrue(reports(SpeakerCoverTrigger.PlayerOpened("ru.yandex.music")))
        assertTrue(reports(SpeakerCoverTrigger.PlayerOpened("com.google.android.youtube")))
    }

    @Test
    fun openingSomethingThatIsNotAPlayerDoesNothing() {
        assertFalse(reports(SpeakerCoverTrigger.PlayerOpened("com.byd.carsettings")))
    }

    @Test
    fun openingAPlayerTheCarReportsForIsLeftAlone() {
        assertFalse(reports(SpeakerCoverTrigger.PlayerOpened("com.byd.mediacenter")))
    }

    @Test
    fun openingAKnownPlayerIsIgnoredWhileTheFeatureIsOff() {
        assertFalse(reports(SpeakerCoverTrigger.PlayerOpened("ru.yandex.music"), featureEnabled = false))
    }

    @Test
    fun theRaiseButtonAnswersWhetherOrNotTheFeatureIsOn() {
        assertTrue(reports(SpeakerCoverTrigger.RaisePressed, featureEnabled = true))
        assertTrue(reports(SpeakerCoverTrigger.RaisePressed, featureEnabled = false))
    }

    @Test
    fun aNullPackageIsNeverReportedFor() {
        assertFalse(reports(SpeakerCoverTrigger.Playback(null)))
        assertFalse(reports(SpeakerCoverTrigger.PlayerOpened(null)))
    }
}
