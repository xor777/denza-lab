package dev.denza.apps.feature.speaker

import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Off, waiting for access, or on - and nothing about what the service is doing right now.
 *
 * STARTING is deliberately absent from every row: the tiles draw it as a spinner, and a spinner on
 * this tile used to mean only that the service had considered speaking and decided not to.
 */
class SpeakerCoverStatusTest {

    @Test
    fun switchedOffIsOffWhateverTheAccess() {
        for (access in listOf(true, false)) {
            val snapshot = SpeakerCoverStatus.snapshot(enabled = false, sessionsObservable = access)
            assertEquals(FeatureId.SPEAKER_COVERS, snapshot.id)
            assertEquals(FeatureStatus.OFF, snapshot.status)
            assertFalse(snapshot.desiredEnabled)
        }
    }

    @Test
    fun switchedOnWithoutSessionAccessAsksForTheAccessAgain() {
        val snapshot = SpeakerCoverStatus.snapshot(enabled = true, sessionsObservable = false)
        assertEquals(FeatureId.SPEAKER_COVERS, snapshot.id)
        assertEquals(FeatureStatus.NEEDS_ACTION, snapshot.status)
        assertTrue(snapshot.desiredEnabled)
        assertEquals("Повторите настройку доступа", snapshot.message)
        assertEquals(FeatureResolution.RETRY, snapshot.resolution)
    }

    @Test
    fun switchedOnWithSessionAccessIsSimplyOn() {
        val snapshot = SpeakerCoverStatus.snapshot(enabled = true, sessionsObservable = true)
        assertEquals(FeatureId.SPEAKER_COVERS, snapshot.id)
        assertEquals(FeatureStatus.READY, snapshot.status)
        assertTrue(snapshot.desiredEnabled)
        assertEquals("", snapshot.message)
        assertNull(snapshot.resolution)
    }

    @Test
    fun noRowEverSpins() {
        for (enabled in listOf(true, false)) for (access in listOf(true, false)) {
            val status = SpeakerCoverStatus.snapshot(enabled, access).status
            assertTrue("$enabled/$access -> $status", status != FeatureStatus.STARTING && status != FeatureStatus.RECOVERING)
        }
    }
}
