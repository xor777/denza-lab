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
     * The whole of the edge rule, which is one line per asker and three different kinds of reason.
     *
     * The pair is a close and an open 350 ms apart, invisible on covers that are in and a twitch on
     * covers that are out, and nothing on this car can tell those apart. So who is asking decides,
     * and the three answers are not the same shape:
     *
     * - a **button** buys it wherever the property allows, because a driver looking at the covers
     *   and pressing anyway is the only thing that can say they are not where the app thinks - and
     *   «Поднять» silently doing nothing was the fault that made the rule take an asker at all;
     * - the **automation** buys it only on an unknown property, where the covers have just been
     *   retracted by the ignition so the close is invisible, and where its one promised opening
     *   needs a guaranteed edge;
     * - the **parting open** never buys it. Worth an open, not worth a twitch.
     *
     * Everything else is a single write, which differs from the last value and makes its own edge.
     */
    @Test
    fun eachAskerPaysForThePairOnItsOwnTerms() {
        val open = SpeakerCoverMotorProtocol.OPEN
        val close = SpeakerCoverMotorProtocol.CLOSE
        val manual = SpeakerCoverCommandSource.MANUAL
        val automatic = SpeakerCoverCommandSource.AUTOMATIC
        val parting = SpeakerCoverCommandSource.BEST_EFFORT

        // Nothing remembered: the firmware could be holding either value.
        assertTrue(SpeakerCoverMotorProtocol.needsEdgeBreak(null, open, automatic))
        assertTrue(SpeakerCoverMotorProtocol.needsEdgeBreak(null, close, automatic))
        assertTrue(SpeakerCoverMotorProtocol.needsEdgeBreak(null, open, manual))
        assertTrue(SpeakerCoverMotorProtocol.needsEdgeBreak(null, close, manual))

        // The button repeating the value already held, which is the dead-button case.
        assertTrue(SpeakerCoverMotorProtocol.needsEdgeBreak(open, open, manual))
        assertTrue(SpeakerCoverMotorProtocol.needsEdgeBreak(close, close, manual))

        // The automation repeating it does not: it cannot tell "already open" from "the amplifier
        // lowered them behind our back", so the twitch would be bought on a guess.
        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(open, open, automatic))
        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(close, close, automatic))

        // The parting open pays for nothing, in any of the three states of the property. The
        // unknown row is the regression seen from the seat on 2026-08-28: `rearm` had begun
        // clearing the remembered value, so switching the automation off found a `null` here and
        // twitched covers that were most likely already out.
        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(null, open, parting))
        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(null, close, parting))
        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(open, open, parting))
        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(close, close, parting))
        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(open, close, parting))
        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(close, open, parting))

        // A value that differs makes its own edge, whoever asked.
        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(open, close, automatic))
        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(close, open, automatic))
        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(open, close, manual))
        assertFalse(SpeakerCoverMotorProtocol.needsEdgeBreak(close, open, manual))
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
