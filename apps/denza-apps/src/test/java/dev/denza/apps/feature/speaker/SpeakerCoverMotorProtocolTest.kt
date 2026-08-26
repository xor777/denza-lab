package dev.denza.apps.feature.speaker

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
     * The rule that stopped the covers twitching on every switch-on.
     *
     * The old code sent in-then-out whenever it did not know where the covers were, and the car
     * showed exactly that: two movements, 350 ms apart. A break is needed only when a plain write
     * would repeat the value the property already holds.
     */
    @Test
    fun aBreakIsNeededOnlyWhenThePropertyAlreadyHoldsTheValueWeWant() {
        val open = SpeakerCoverMotorProtocol.OPEN
        val close = SpeakerCoverMotorProtocol.CLOSE

        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(lastWritten = close, target = open))
        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(lastWritten = open, target = close))
        assertTrue(SpeakerCoverMotorProtocol.needsEdgeBreak(lastWritten = open, target = open))
        assertTrue(SpeakerCoverMotorProtocol.needsEdgeBreak(lastWritten = close, target = close))
    }

    /** Before the first command ever sent, the firmware's value could be either one. */
    @Test
    fun anUnwrittenPropertyIsPaidForOnce() {
        assertTrue(
            SpeakerCoverMotorProtocol.needsEdgeBreak(
                lastWritten = null,
                target = SpeakerCoverMotorProtocol.OPEN,
            ),
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
