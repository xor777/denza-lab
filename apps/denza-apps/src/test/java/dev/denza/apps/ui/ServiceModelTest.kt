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
                    "Повторите настройку доступа",
                    resolution = FeatureResolution.RETRY,
                ),
                cloudLink = FeatureSnapshot(FeatureId.CLOUD_LINK, true, FeatureStatus.ERROR, message = "Не включилось"),
            ),
        )
        assertEquals("2 функции ждут", model.status)
        assertEquals(DenzaTileTone.ATTENTION, model.statusTone)
        assertEquals(listOf(TileId.HUD, TileId.CLOUD), model.trouble.map { it.id })
        assertEquals(listOf("Повторите настройку доступа", "Не включилось"), model.trouble.map { it.state })
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
}
