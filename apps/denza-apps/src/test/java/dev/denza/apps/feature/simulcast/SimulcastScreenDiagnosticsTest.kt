package dev.denza.apps.feature.simulcast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulcastScreenDiagnosticsTest {
    private fun lines() = SimulcastScreenDiagnostics.diagnosticLines(nowMs = System.currentTimeMillis())

    @Test
    fun anEmptyAnswerIsAnEmptyListAndAFailureIsNot() {
        SimulcastScreenDiagnostics.recordDiShareScreens(emptyList())
        assertTrue(lines().any { it.startsWith("DiShare getScreens=получено экранов: 0") })
        assertTrue(lines().contains("DiShare receivers=пустой список"))

        SimulcastScreenDiagnostics.recordDiShareFailure("отказ")
        assertTrue(lines().any { it.startsWith("DiShare getScreens=ошибка: отказ") })
        assertFalse(lines().contains("DiShare receivers=пустой список"))

        SimulcastScreenDiagnostics.recordDiShareFailure(" ")
        assertTrue(lines().any { it.startsWith("DiShare getScreens=ошибка: без описания") })
    }
}
