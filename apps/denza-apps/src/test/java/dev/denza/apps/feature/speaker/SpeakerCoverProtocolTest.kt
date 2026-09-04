package dev.denza.apps.feature.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exact bytes on the wire, because every one of them was learned from a live car.
 *
 * The device and feature numbers are not adjustable: `1007` is the instrument device and
 * `1138753546` is `0x43E0000A`, the write that raised the covers on the Z9GT on 2026-09-03 and on
 * the N9 on 2026-09-04 with nothing playing. Changing either silently turns the feature into a
 * write to some other subsystem. There is no second command: the stock auto-lift setting on the
 * audio device is the car's, and this feature does not read or write it.
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
    fun aWriteIsAcceptedOnItsSingleStatusWord() {
        assertTrue(SpeakerCoverProtocol.accepted("Result: Parcel(00000001    '....')"))
    }

    @Test
    fun aWriteIsRefusedOnAnythingElse() {
        assertFalse(SpeakerCoverProtocol.accepted("Result: Parcel(00000000    '....')"))
        assertFalse(SpeakerCoverProtocol.accepted("Result: Parcel(ffffd8e5    '....')"))
        // A two-word reply is a read, not a write acknowledgement - including one whose exception
        // code happens to be 1, which a check on the first word alone would wave through.
        assertFalse(SpeakerCoverProtocol.accepted("Result: Parcel(00000000 00000001   '........')"))
        assertFalse(SpeakerCoverProtocol.accepted("Result: Parcel(00000001 00000002   '........')"))
        assertFalse(SpeakerCoverProtocol.accepted(""))
        assertFalse(SpeakerCoverProtocol.accepted("Permission not granted"))
    }
}
