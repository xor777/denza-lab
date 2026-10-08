package dev.denza.apps

import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.feature.adb.AdbRescuePhase
import dev.denza.apps.feature.adb.AdbRescueSnapshot
import dev.denza.apps.feature.adb.AdbSystemSwitch
import org.junit.Assert.assertEquals
import org.junit.Test

class BehindAdbGateTest {
    private val report = TechnicalReadings.render(
        listOf(
            TechnicalSection(
                "Приложение",
                listOf(TechnicalRow("Версия", "0.7.0-alpha · сборка 54")),
            ),
            TechnicalSection(
                "Доступ к машине",
                listOf(
                    TechnicalRow("Состояние", "awaiting-confirmation"),
                    TechnicalRow("Отладка ADB в машине", "включено"),
                ),
            ),
        ),
    )
    private val journal = TechnicalReadings.render(
        listOf(TechnicalSection("Открытие 20:34:02 · вышло · 1,2 с", listOf(TechnicalRow("+0 мс", "dequeued")))),
    )

    /**
     * The defect: a fresh process behind the gate published the gate's phase and nothing else, so
     * the technical page opened through the explainer's seven taps parsed an empty string.
     */
    @Test
    fun `behind the gate the service page has its report and its journal`() {
        val gated = DenzaUiState().behindAdbGate(
            AdbRescueSnapshot(
                phase = AdbRescuePhase.AWAITING_CONFIRMATION,
                requestPending = true,
                systemSwitch = AdbSystemSwitch.ENABLED,
            ),
            report,
            journal,
        )

        assertEquals(
            listOf("Приложение", "Доступ к машине"),
            TechnicalReadings.parse(gated.technicalDetails).map { it.title },
        )
        assertEquals(1, TechnicalReadings.parse(gated.splitJournal).size)
        assertEquals(AdbRescuePhase.AWAITING_CONFIRMATION, gated.adbRescue.phase)
    }

    @Test
    fun `the tiles keep their last healthy state behind the gate`() {
        val mirrorsRunning = FeatureSnapshot(
            id = FeatureId.MIRRORS,
            desiredEnabled = true,
            status = FeatureStatus.ACTIVE,
        )
        val healthy = DenzaUiState(mirrors = mirrorsRunning, selectedAppCount = 3)

        val gated = healthy.behindAdbGate(AdbRescueSnapshot(phase = AdbRescuePhase.ERROR), report, journal)

        assertEquals(healthy.copy(adbRescue = gated.adbRescue, technicalDetails = report, splitJournal = journal), gated)
        assertEquals(mirrorsRunning, gated.mirrors)
        assertEquals(3, gated.selectedAppCount)
    }
}
