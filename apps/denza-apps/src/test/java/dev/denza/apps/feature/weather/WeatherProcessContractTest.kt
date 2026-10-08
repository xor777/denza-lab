package dev.denza.apps.feature.weather

import dev.denza.apps.appManifest
import dev.denza.apps.between
import dev.denza.apps.component
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The weather service shares the dashboard's process, and the dashboard follows each run.
 *
 * Until 2026-10-06 the service ran in a `:weather` process and wrote its record into the same
 * SharedPreferences file the main process read and wrote too. Each process kept its own cached
 * copy and wrote it whole, so the main process put a stale temperature back on disk at every
 * alarm, the tile showed the temperature of the moment the runtime started, and a `:weather`
 * process that outlived the switch going off still read it as on. One process, one copy.
 */
class WeatherProcessContractTest {

    @Test
    fun theServiceRunsInTheAppsOwnProcess() {
        val service = appManifest().component("service", ".feature.weather.WeatherAdapterService")
        assertFalse(
            "WeatherAdapterService must not run in a process of its own: $service",
            "android:process" in service,
        )
    }

    @Test
    fun theDashboardReadsTheRecordOnEveryRefreshAndEveryRun() {
        val repository = File("src/main/java/dev/denza/apps/DenzaAppRepository.kt").readText()
        val refresh = repository.between("\n    private fun recompute() {", "\n    fun ")
        assertTrue(
            "DenzaAppRepository.refresh must read the weather record",
            "WeatherAdapterState.lastTemperature" in refresh &&
                "WeatherAdapterState.lastSuccessMillis" in refresh,
        )
        val runtime = repository.between("runtimeStep(\"weather initialize\")", "runtimeStep(")
        assertTrue(
            "the runtime must observe the weather record so the tile follows each run",
            "WeatherAdapterState.observe" in runtime,
        )
    }
}
