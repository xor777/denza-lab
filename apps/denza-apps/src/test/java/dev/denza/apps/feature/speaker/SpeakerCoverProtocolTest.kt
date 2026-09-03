package dev.denza.apps.feature.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exact bytes on the wire, because every one of them was learned from a live car.
 *
 * The device and feature numbers are not adjustable: `1007` is the instrument device and
 * `1138753546` is `0x43E0000A`, the write that raised the covers on 2026-09-03 with nothing
 * playing. Changing either silently turns the feature into a write to some other subsystem.
 */
class SpeakerCoverProtocolTest {

    @Test
    fun theReportGoesToTheInstrumentDeviceAsPlaying() {
        assertEquals(
            "service call autoservice 6 i32 1007 i32 1138753546 i32 1 null",
            SpeakerCoverProtocol.reportPlayingCommand(),
        )
    }

    @Test
    fun theAutoLiftWritesGoToTheAudioDevice() {
        assertEquals(
            "service call autoservice 6 i32 1002 i32 372244517 i32 1 null",
            SpeakerCoverProtocol.autoLiftCommand(SpeakerCoverProtocol.AUTO_LIFT_ON),
        )
        assertEquals(
            "service call autoservice 6 i32 1002 i32 372244517 i32 2 null",
            SpeakerCoverProtocol.autoLiftCommand(SpeakerCoverProtocol.AUTO_LIFT_OFF),
        )
    }

    @Test
    fun theSettingIsReadFromTheAudioDevice() {
        assertEquals(
            "service call autoservice 5 i32 1002 i32 899678426",
            SpeakerCoverProtocol.readAutoLiftCommand(),
        )
    }

    @Test
    fun eachStepHasItsOwnCommand() {
        assertEquals(
            SpeakerCoverProtocol.autoLiftCommand(SpeakerCoverProtocol.AUTO_LIFT_ON),
            SpeakerCoverProtocol.command(SpeakerCoverStep.ENABLE_AUTO_LIFT),
        )
        assertEquals(
            SpeakerCoverProtocol.reportPlayingCommand(),
            SpeakerCoverProtocol.command(SpeakerCoverStep.REPORT_PLAYING),
        )
        assertEquals(
            SpeakerCoverProtocol.autoLiftCommand(SpeakerCoverProtocol.AUTO_LIFT_OFF),
            SpeakerCoverProtocol.command(SpeakerCoverStep.HIDE),
        )
    }

    @Test
    fun aWriteIsAcceptedOnItsSingleStatusWord() {
        assertTrue(SpeakerCoverProtocol.accepted("Result: Parcel(00000001    '....')"))
    }

    @Test
    fun aWriteIsRefusedOnAnythingElse() {
        assertFalse(SpeakerCoverProtocol.accepted("Result: Parcel(00000000    '....')"))
        assertFalse(SpeakerCoverProtocol.accepted("Result: Parcel(ffffd8e5    '....')"))
        // A two-word reply is a read, not a write acknowledgement.
        assertFalse(SpeakerCoverProtocol.accepted("Result: Parcel(00000000 00000001   '........')"))
        assertFalse(SpeakerCoverProtocol.accepted(""))
        assertFalse(SpeakerCoverProtocol.accepted("Permission not granted"))
    }

    @Test
    fun aReadYieldsTheSecondWordWhenTheFirstIsClean() {
        assertEquals(1, SpeakerCoverProtocol.readValue("Result: Parcel(00000000 00000001   '........')"))
        assertEquals(2, SpeakerCoverProtocol.readValue("Result: Parcel(00000000 00000002   '........')"))
    }

    @Test
    fun aReadWithAnExceptionCodeYieldsNothing() {
        assertNull(SpeakerCoverProtocol.readValue("Result: Parcel(00000001 00000002   '........')"))
        assertNull(SpeakerCoverProtocol.readValue("Result: Parcel(00000001    '....')"))
        assertNull(SpeakerCoverProtocol.readValue(""))
    }

    @Test
    fun theSettingReadsAsOnOffOrUnknown() {
        assertEquals(
            SpeakerCoverAutoLift.ENABLED,
            SpeakerCoverProtocol.autoLift("Result: Parcel(00000000 00000001   '........')"),
        )
        assertEquals(
            SpeakerCoverAutoLift.DISABLED,
            SpeakerCoverProtocol.autoLift("Result: Parcel(00000000 00000002   '........')"),
        )
        // -10011 is the car saying the property is not supported, which is not a reading.
        assertEquals(
            SpeakerCoverAutoLift.UNKNOWN,
            SpeakerCoverProtocol.autoLift("Result: Parcel(00000000 ffffd8e5   '........')"),
        )
        assertEquals(SpeakerCoverAutoLift.UNKNOWN, SpeakerCoverProtocol.autoLift("no such service"))
    }
}
