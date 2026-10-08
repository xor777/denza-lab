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
     * the technical page opened through the explainer's seven taps parsed an empty string. The
     * report is now built because the panel is open, and the gate has no say in that.
     */
    @Test
    fun `behind the gate the service page has its report and its journal`() {
        val store = DenzaUiStateStore()
        store.update {
            it.behindAdbGate(
                AdbRescueSnapshot(
                    phase = AdbRescuePhase.AWAITING_CONFIRMATION,
                    requestPending = true,
                    systemSwitch = AdbSystemSwitch.ENABLED,
                ),
            )
        }
        val clock = ManualReportClock()
        val serviceReport = ServiceReport(
            clock = clock,
            periodMs = 1_000L,
            build = { ServiceReport.Pages(report, journal) },
            publish = { pages ->
                store.update {
                    it.copy(technicalDetails = pages.technicalDetails, splitJournal = pages.splitJournal)
                }
            },
        )

        serviceReport.setOpen(true)
        clock.runPending()

        val gated = store.state.value
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
        val healthy = DenzaUiState(
            mirrors = mirrorsRunning,
            selectedAppCount = 3,
            technicalDetails = report,
            splitJournal = journal,
        )

        val gated = healthy.behindAdbGate(AdbRescueSnapshot(phase = AdbRescuePhase.ERROR))

        assertEquals(healthy.copy(adbRescue = gated.adbRescue), gated)
        assertEquals(mirrorsRunning, gated.mirrors)
        assertEquals(3, gated.selectedAppCount)
    }
}
