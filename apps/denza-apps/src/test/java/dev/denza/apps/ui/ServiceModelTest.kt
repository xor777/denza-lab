package dev.denza.apps.ui

import dev.denza.apps.DenzaUiState
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureResolution
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.feature.adb.AdbRescuePhase
import dev.denza.apps.feature.adb.AdbRescueSnapshot
import dev.denza.apps.ui.components.DenzaTileTone
import dev.denza.apps.ui.dashboard.TileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceModelTest {

    private val trusted = DenzaUiState(adbRescue = AdbRescueSnapshot(phase = AdbRescuePhase.TRUSTED))

    @Test
    fun `a healthy car is a panel with nothing to say and no buttons`() {
        val model = ServiceModel.of(trusted)
        assertNull(model.status)
        assertTrue(model.trouble.isEmpty())
        assertTrue(model.allWorking)
        assertNull(model.accessTone)
        assertFalse(model.accessActions)
    }

    @Test
    fun `features that need somebody are counted in the tile's words and listed in its colours`() {
        val model = ServiceModel.of(
            trusted.copy(
                hudGuidance = FeatureReducer.needsAction(
                    FeatureReducer.starting(FeatureId.HUD_GUIDANCE),
                    "Нет доступа",
                    resolution = FeatureResolution.RETRY,
                ),
                cloudLink = FeatureSnapshot(FeatureId.CLOUD_LINK, true, FeatureStatus.ERROR, message = "Не включилось"),
            ),
        )
        // The summary takes its worst row's words and colour: one of the two is broken.
        assertEquals("2 функции не работают", model.status)
        assertEquals(DenzaTileTone.BROKEN, model.statusTone)
        assertEquals(listOf(TileId.HUD, TileId.CLOUD), model.trouble.map { it.id })
        assertEquals(listOf("Нет доступа", "Не включилось"), model.trouble.map { it.state })
        assertEquals(listOf(DenzaTileTone.ATTENTION, DenzaTileTone.BROKEN), model.trouble.map { it.tone })
        assertFalse(model.accessActions)
    }

    @Test
    fun `no access outranks everything, in red, with the access buttons`() {
        val model = ServiceModel.of(
            DenzaUiState(adbRescue = AdbRescueSnapshot(phase = AdbRescuePhase.AUTHORIZATION_REQUIRED)),
        )
        assertEquals(ServiceModel.NO_ACCESS, model.status)
        assertFalse("nothing works without access, so the panel does not say it does", model.allWorking)
        assertEquals(DenzaTileTone.BROKEN, model.statusTone)
        assertEquals(DenzaTileTone.BROKEN, model.accessTone)
        assertTrue(model.accessActions)
        assertFalse(model.accessBusy)
    }

    @Test
    fun `a check under way is not a missing access, and its buttons wait`() {
        val model = ServiceModel.of(DenzaUiState(adbRescue = AdbRescueSnapshot(phase = AdbRescuePhase.CHECKING)))
        assertNull(model.status)
        assertNull(model.accessTone)
        assertTrue(model.accessActions)
        assertTrue(model.accessBusy)
    }

    /** A request on the car's screen ends by itself; nothing on the panel may send another. */
    @Test
    fun `a request on the car's screen keeps the access buttons waiting`() {
        val model = ServiceModel.of(
            DenzaUiState(adbRescue = AdbRescueSnapshot(phase = AdbRescuePhase.AWAITING_CONFIRMATION)),
        )
        assertNull(model.status)
        assertTrue(model.accessActions)
        assertTrue(model.accessBusy)
        assertFalse(AdbRescueSnapshot(phase = AdbRescuePhase.AWAITING_CONFIRMATION).canAsk)
    }

    /** The split's journal is opened from the technical page, and its way back goes there. */
    @Test
    fun `the journal goes back to the technical page and every other page to the answer`() {
        assertEquals(ServicePage.TECHNICAL, ServicePage.JOURNAL.parent)
        assertEquals(ServicePage.MAIN, ServicePage.TECHNICAL.parent)
        assertEquals(ServicePage.MAIN, ServicePage.SCREEN.parent)
    }

    /** Review finding 15: «Все функции работают» on a car with a prompt on its screen. Only a trusted key works. */
    @Test
    fun `everything works only on a trusted key`() {
        AdbRescuePhase.entries.forEach { phase ->
            val model = ServiceModel.of(DenzaUiState(adbRescue = AdbRescueSnapshot(phase = phase)))
            assertEquals(phase.name, phase == AdbRescuePhase.TRUSTED, model.allWorking)
        }
    }

    /** A check of a gate that is up keeps the row as the gate keeps its card: no access, buttons waiting. */
    @Test
    fun `a re-check of missing access keeps the row red and its buttons waiting`() {
        val model = ServiceModel.of(
            DenzaUiState(
                adbRescue = dev.denza.apps.feature.adb.AdbRescuePolicy.checking(
                    AdbRescueSnapshot(phase = AdbRescuePhase.AUTHORIZATION_REQUIRED),
                ),
            ),
        )
        assertEquals(ServiceModel.NO_ACCESS, model.status)
        assertEquals(DenzaTileTone.BROKEN, model.accessTone)
        assertTrue(model.accessBusy)
        assertFalse(model.allWorking)
    }
}
