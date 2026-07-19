package dev.denza.disharebridge

import dev.denza.apps.SimulcastScreenDiagnostics
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulcastScreenDiagnosticsTest {
    @Test
    fun rawReceiverDiagnosticsPreserveRearScreenContract() {
        SimulcastScreenDiagnostics.recordDiShareScreens(
            listOf(
                DiShareScreens.Screen("tv", "screen_tv", true),
                DiShareScreens.Screen("rse", "screen_rse_l", false),
            ),
        )

        val lines = SimulcastScreenDiagnostics.diagnosticLines()

        assertTrue(lines.contains("DiShare screen_tv=device=tv; available=да"))
        assertTrue(lines.contains("DiShare screen_rse_l=device=rse; available=нет"))
    }
}
