package dev.denza.apps.feature.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerCoverMotorProtocolTest {

    @Test
    fun buildsTheLiveProvenAutoserviceCommands() {
        assertEquals(
            "service call autoservice 6 i32 1002 i32 372244517 i32 1 null",
            SpeakerCoverMotorProtocol.command(SpeakerCoverMotorProtocol.OPEN),
        )
        assertEquals(
            "service call autoservice 6 i32 1002 i32 372244517 i32 2 null",
            SpeakerCoverMotorProtocol.command(SpeakerCoverMotorProtocol.CLOSE),
        )
    }

    @Test
    fun acceptsOnlyTheObservedSuccessWord() {
        assertTrue(SpeakerCoverMotorProtocol.accepted("Result: Parcel(00000000 00000001   '........')"))
        assertFalse(SpeakerCoverMotorProtocol.accepted("Result: Parcel(00000000 00000000   '........')"))
        assertFalse(SpeakerCoverMotorProtocol.accepted("service unavailable"))
    }
}
