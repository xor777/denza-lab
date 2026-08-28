package dev.denza.apps.feature.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `service call autoservice` actually prints back, and which of it means the covers moved.
 *
 * The first version of this required exactly two hex words and read the second - the shape a reply
 * takes when it carries an exception code and then a value. The car in the garage answers the
 * cover command with one word, so nothing matched, every successful command was read as a refusal,
 * and the dashboard tile turned a spinner while the covers did as they were told. Both shapes are
 * here now so that cannot happen again from either direction.
 */
class SpeakerCoverMotorProtocolTest {

    @Test
    fun aOneWordReplyIsTheOneThisCarSends() {
        assertTrue(SpeakerCoverMotorProtocol.accepted("Result: Parcel(00000001    '....')"))
        assertFalse(SpeakerCoverMotorProtocol.accepted("Result: Parcel(00000000    '....')"))
    }

    @Test
    fun aTwoWordReplyStillReadsItsValue() {
        assertTrue(
            SpeakerCoverMotorProtocol.accepted(
                "Result: Parcel(00000000 00000001   '........')",
            ),
        )
        assertFalse(
            SpeakerCoverMotorProtocol.accepted(
                "Result: Parcel(00000000 00000000   '........')",
            ),
        )
    }

    @Test
    fun anythingThatIsNotAParcelIsNotAnAcknowledgement() {
        assertFalse(SpeakerCoverMotorProtocol.accepted(""))
        assertFalse(SpeakerCoverMotorProtocol.accepted("Service autoservice does not exist"))
        assertFalse(SpeakerCoverMotorProtocol.accepted("Result: Parcel('....')"))
    }

    /**
     * The whole of the edge rule, which is three lines and two different kinds of reason.
     *
     * Nothing remembered is a fact about the firmware: before this app's first write of a boot the
     * property could be holding either value, so the pair has to be paid once whoever is asking.
     *
     * A button repeating the value already held is a choice. The pair is a close and an open 350 ms
     * apart, invisible on covers that are in and a twitch on covers that are out, and nothing on
     * this car can tell those apart. The automation is not allowed to spend that, because it would
     * be guessing; the driver is, because they can see the covers and pressed anyway - and «Поднять»
     * silently doing nothing was the fault that made this parameter exist.
     *
     * Everything else is a single write, which differs from the last value and makes its own edge.
     */
    @Test
    fun onlyAnUnknownPropertyOrARepeatedButtonPaysForAPair() {
        val open = SpeakerCoverMotorProtocol.OPEN
        val close = SpeakerCoverMotorProtocol.CLOSE

        assertTrue(SpeakerCoverMotorProtocol.needsEdgeBreak(null, open, manual = false))
        assertTrue(SpeakerCoverMotorProtocol.needsEdgeBreak(null, close, manual = false))
        assertTrue(SpeakerCoverMotorProtocol.needsEdgeBreak(null, open, manual = true))
        assertTrue(SpeakerCoverMotorProtocol.needsEdgeBreak(null, close, manual = true))

        assertTrue(SpeakerCoverMotorProtocol.needsEdgeBreak(open, open, manual = true))
        assertTrue(SpeakerCoverMotorProtocol.needsEdgeBreak(close, close, manual = true))

        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(open, open, manual = false))
        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(close, close, manual = false))

        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(open, close, manual = false))
        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(close, open, manual = false))
        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(open, close, manual = true))
        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(close, open, manual = true))
    }

    /**
     * The half of the pair that makes the edge, which only works if it is the other value.
     *
     * Everything above decides *whether* a break is owed; this is what a break is. Sending the
     * target twice writes the same value the property is already holding, which is precisely the
     * no-op the pair exists to avoid - a break that breaks nothing, and covers that never move.
     */
    @Test
    fun theBreakIsSentAsTheValueTheTargetIsNot() {
        assertEquals(
            SpeakerCoverMotorProtocol.CLOSE,
            SpeakerCoverMotorProtocol.opposite(SpeakerCoverMotorProtocol.OPEN),
        )
        assertEquals(
            SpeakerCoverMotorProtocol.OPEN,
            SpeakerCoverMotorProtocol.opposite(SpeakerCoverMotorProtocol.CLOSE),
        )
    }

    @Test
    fun theCommandIsTheDocumentedOne() {
        // AUDIO_RLSA_STATE_SET is 0x16300025 = 372244517, driven as an edge: 1 out, 2 in.
        assertTrue(
            SpeakerCoverMotorProtocol.command(SpeakerCoverMotorProtocol.OPEN)
                .contains("i32 372244517 i32 1"),
        )
        assertTrue(
            SpeakerCoverMotorProtocol.command(SpeakerCoverMotorProtocol.CLOSE)
                .contains("i32 372244517 i32 2"),
        )
    }
}
