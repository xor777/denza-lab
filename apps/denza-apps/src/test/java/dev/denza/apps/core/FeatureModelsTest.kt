package dev.denza.apps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureModelsTest {
    @Test
    fun recoverableFailureKeepsDesiredStateEnabled() {
        val ready = FeatureReducer.ready(FeatureId.SIMULCAST)

        val recovering = FeatureReducer.recovering(ready, "Восстанавливаю", "ADB timeout")

        assertTrue(recovering.desiredEnabled)
        assertEquals(FeatureStatus.RECOVERING, recovering.status)
        assertEquals("ADB timeout", recovering.details)
    }

    @Test
    fun disabledFeatureCannotEnterRecovery() {
        val disabled = FeatureReducer.disabled(FeatureId.MIRRORS)

        val result = FeatureReducer.recovering(disabled, "ignored")

        assertFalse(result.desiredEnabled)
        assertEquals(FeatureStatus.OFF, result.status)
    }

    @Test
    fun userActionDoesNotResetDesiredState() {
        val starting = FeatureReducer.starting(FeatureId.SIMULCAST)

        val result = FeatureReducer.needsAction(
            starting,
            "Не выбрано",
            resolution = FeatureResolution.SELECT_APPS,
        )

        assertTrue(result.desiredEnabled)
        assertEquals(FeatureStatus.NEEDS_ACTION, result.status)
        assertEquals(FeatureResolution.SELECT_APPS, result.resolution)
    }

    @Test
    fun recoveringClearsAStaleUserResolution() {
        val needsAction = FeatureReducer.needsAction(
            FeatureReducer.starting(FeatureId.SIMULCAST),
            "Подтвердите запрос",
            resolution = FeatureResolution.CHECK_ACCESS,
        )

        val result = FeatureReducer.recovering(needsAction, "Восстанавливаю")

        assertEquals(FeatureStatus.RECOVERING, result.status)
        assertNull(result.resolution)
    }
}
