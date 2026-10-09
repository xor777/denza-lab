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

    /**
     * A start DiShare refused is written here, with its reply, instead of a toast over the stock
     * dialog in the projection's internal name.
     */
    @Test
    fun theLastStartOrExitIsOnThePageWithDiSharesReply() {
        SimulcastScreenDiagnostics.recordCastOutcome("запуск com.vk.vkvideo: не запустилось (start returned {screen_hud=605})")

        val line = SimulcastScreenDiagnostics.diagnosticLines().last()

        assertTrue(line, line.startsWith("Последний исход=запуск com.vk.vkvideo: не запустилось (start returned {screen_hud=605})"))
    }
}
