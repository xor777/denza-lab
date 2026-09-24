package dev.denza.apps

import dev.denza.apps.feature.cluster.CameraRuntimePhase
import dev.denza.apps.feature.cluster.CameraRuntimeSnapshot
import dev.denza.apps.feature.mirrors.MirrorSide
import dev.denza.apps.feature.mirrors.SideCameraDetection
import dev.denza.apps.feature.split.SplitFirmwareReading
import dev.denza.apps.feature.split.SplitWorkEnd
import dev.denza.apps.feature.split.SplitWorkOperation
import dev.denza.apps.feature.split.SplitWorkState
import dev.denza.apps.feature.split.SplitWorkStep
import dev.denza.apps.feature.trip.SpectrumSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportDiagnosticsTest {
    @Test
    fun `the app section and the camera rows carry the injected values`() {
        val header = SupportDiagnosticsHeader(
            versionName = "9.8.7-test",
            versionCode = 54,
            androidRelease = "13",
            sdkLevel = 33,
            fingerprint = "denza/test/fingerprint",
            cameraRuntime = CameraRuntimeSnapshot(
                phase = CameraRuntimePhase.READY,
                side = MirrorSide.RIGHT,
                generation = 12,
                details = "avc ready",
            ),
            mirrorDetection = SideCameraDetection(
                recognizedSide = MirrorSide.RIGHT,
                avcCandidateBlocks = 4,
                unrecognizedCandidates = 2,
            ),
            simulcastRuntime = SimulcastRuntimeSnapshot(
                rootsFound = 10,
                rootsMissing = 3,
                geometryParseMisses = 2,
                unstableSamples = 7,
                appliedRelayouts = 5,
                semanticWindowRebuilds = 1,
            ),
        )

        val app = SupportDiagnostics.appSection(header)
        assertEquals("Приложение", app.title)
        assertEquals(
            listOf(
                TechnicalRow("Версия", "9.8.7-test · сборка 54"),
                TechnicalRow("Android", "13 · SDK 33"),
                TechnicalRow("Прошивка", "denza/test/fingerprint"),
            ),
            app.rows,
        )
        assertEquals(
            listOf(
                TechnicalRow("Камера AVC", "READY, сторона RIGHT, поколение 12"),
                TechnicalRow("Камера AVC, подробно", "avc ready"),
                TechnicalRow("Окна AVC", "сторона RIGHT, кандидатов 4, нераспознанных 2"),
            ),
            SupportDiagnostics.avcRows(header),
        )
        assertEquals(
            "найдено 10, потеряно 3, промахов геометрии 2, нестабильных 7, перекладок 5, пересборок 1",
            SupportDiagnostics.simulcastCounters(header.simulcastRuntime),
        )
    }

    @Test
    fun `the analyser's line is one reading a row, and a closed panel one row saying so`() {
        val rows = SupportDiagnostics.spectrumRows("разрешение=есть; захват=запрошен; кадр=40 мс назад")
        assertEquals(
            listOf(
                TechnicalRow("разрешение", "есть"),
                TechnicalRow("захват", "запрошен"),
                TechnicalRow("кадр", "40 мс назад"),
            ),
            rows,
        )
        assertEquals(
            listOf(TechnicalRow("Состояние", "панель не открывалась")),
            SupportDiagnostics.spectrumRows("панель не открывалась"),
        )
    }

    /**
     * Три состояния анализатора, которые снаружи выглядят одинаково - неподвижные столбики, - и
     * ради различения которых строка вообще существует.
     *
     * Живой случай 27.08.2026 - третий: захват запрошен, эффект создан, но включить его нам не дали,
     * потому что управлять общим эффектом сессии 0 может только тот, кто создал его первым. Отличить
     * это от тишины в машине было нечем, и диагноз пришлось ставить дампами `media.audio_flinger`.
     */
    @Test
    fun `the spectrum line tells the three silences apart`() {
        val quietCar = SupportDiagnostics.spectrumLabel(
            spectrum(effectEnabled = true, sinceLastFrameMs = 40L),
        )
        val takenEffect = SupportDiagnostics.spectrumLabel(
            spectrum(effectEnabled = false, lastFailure = "эффект сессии 0 занят другим владельцем"),
        )
        val neverDelivered = SupportDiagnostics.spectrumLabel(
            spectrum(effectEnabled = true, sinceLastFrameMs = null),
        )

        assertTrue(quietCar, quietCar.contains("эффект=включён") && quietCar.contains("40 мс назад"))
        assertTrue(takenEffect, takenEffect.contains("эффект=ВЫКЛЮЧЕН"))
        assertTrue(takenEffect, takenEffect.contains("занят другим владельцем"))
        assertTrue(neverDelivered, neverDelivered.contains("кадр=не приходил"))
        assertEquals(
            "три разные причины молчания - три разные строки",
            3,
            setOf(quietCar, takenEffect, neverDelivered).size,
        )
    }

    /** Отчёт спрашивает хаб, а не строит его: панель могли ни разу не открыть. */
    @Test
    fun `the spectrum line says so when the panel was never opened`() {
        assertEquals("панель не открывалась", SupportDiagnostics.spectrumLabel(null))
    }

    /**
     * Сплит перестал работать в 0.7.0-alpha на чужой прошивке, до которой нет ADB (2026-09-24):
     * раздел говорит, как прошло последнее открытие и что прошивка говорит и даёт услышать.
     */
    @Test
    fun `the split section says how the last open went and what the firmware lets us hear`() {
        val work = listOf(
            SplitWorkOperation(
                label = "open",
                startedAt = "20:34:02",
                steps = listOf(SplitWorkStep(0, "dequeued"), SplitWorkStep(588, "roots-started")),
                state = SplitWorkState.ENDED,
                end = SplitWorkEnd("rolled-back", "Прошивка не раскрыла native split", 3112),
            ),
        )
        val firmware = SplitFirmwareReading(
            mode = 100,
            area = 0,
            homeKeyHeard = true,
            areaHeard = true,
            callsOk = false,
        )

        assertEquals(
            listOf(
                TechnicalRow("Состояние", "active"),
                TechnicalRow("Последнее открытие", "20:34 · не вышло · 3,1 с"),
                TechnicalRow("Сплит прошивки", "две панели · область 0"),
                TechnicalRow("Сигналы прошивки", "Home да · область да · вызовы нет"),
            ),
            SupportDiagnostics.splitRows("active", work, firmware),
        )
        assertEquals(
            "страница журнала - тот же формат отчёта, и читается тем же разбором",
            listOf(
                TechnicalSection(
                    "Открытие 20:34:02 · не вышло · 3,1 с",
                    listOf(
                        TechnicalRow("+0 мс", "dequeued"),
                        TechnicalRow("+588 мс", "roots-started"),
                        TechnicalRow("итог", "outcome=rolled-back reason=Прошивка не раскрыла native split"),
                    ),
                ),
            ),
            TechnicalReadings.parse(SupportDiagnostics.splitJournal(work)),
        )
        assertEquals(
            listOf(TechnicalSection(null, listOf(TechnicalRow("Операции", "пока не было")))),
            TechnicalReadings.parse(SupportDiagnostics.splitJournal(emptyList())),
        )
    }

    private fun spectrum(
        granted: Boolean = true,
        running: Boolean = true,
        attached: Boolean = true,
        effectEnabled: Boolean?,
        sinceLastFrameMs: Long? = 40L,
        lastFailure: String? = null,
    ) = SpectrumSource.Diagnostics(
        granted = granted,
        running = running,
        attached = attached,
        effectEnabled = effectEnabled,
        sinceLastFrameMs = sinceLastFrameMs,
        lastFailure = lastFailure,
    )
}
