package dev.denza.apps.feature.trip

import dev.denza.apps.feature.vehicle.EngineTrace
import dev.denza.apps.feature.vehicle.EngineTraceSnapshot
import dev.denza.apps.feature.vehicle.TripEnergy
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the car's page says that only the car's page says.
 *
 * The direction of the pack's flow, the consumption and its window left this class with the energy
 * display contract: they are the same quantities the cluster prints, and `EnergyReadoutsTest` is
 * what holds the two screens to one answer about them. What is left here is the engine's own cell
 * and the pack's voltage.
 */
class VehiclePageWordsTest {

    @Test
    fun theEngineCellIsRevolutionsWhileItTurns() {
        val cell = VehiclePageWords.engineCell(
            telemetry(rpm = 1321.0, running = 1.0),
        )
        assertEquals(VehiclePageWords.TITLE_RPM to "1321", cell)
    }

    @Test
    fun andHowLongItRanOnceItStops() {
        val cell = VehiclePageWords.engineCell(
            telemetry(running = 0.0, trip = TripEnergy(engineSeconds = 14 * 60.0), warm = true),
        )
        assertEquals(VehiclePageWords.TITLE_ENGINE_MINUTES to "14", cell)
    }

    /**
     * And it goes when the engine has been cold for two minutes, whatever the trip remembers.
     *
     * The owner got into the car, had not started the engine, and the cell said «3 мин за
     * поездку» - true by the ledger, because a trip runs from the first movement after P and
     * yesterday's drive was still the trip, and wrong to anybody reading it. The cell lives as
     * long as the cluster's engine box does now: while the trace still holds a slot the engine was
     * alive in.
     */
    @Test
    fun andGoesOnceTheEngineHasBeenColdForTwoMinutes() {
        assertNull(
            VehiclePageWords.engineCell(
                telemetry(running = 0.0, trip = TripEnergy(engineSeconds = 3 * 60.0)),
            ),
        )
    }

    /**
     * And nothing at all when it has not run: a zero is never drawn.
     *
     * Including the half-minute case, which would print «0 мин за поездку» - a zero with a unit on
     * it is still a zero, and this is the exact shape of the sentence the Contour's sixth pass was
     * called for: *«что означает 0,0 от ДВС, когда ДВС заглушен?»*
     */
    @Test
    fun andNothingAtAllWhenItHasNotRun() {
        assertNull("never started", VehiclePageWords.engineCell(telemetry(running = 0.0)))
        assertNull(
            "ran for forty seconds",
            VehiclePageWords.engineCell(
                telemetry(running = 0.0, trip = TripEnergy(engineSeconds = 40.0), warm = true),
            ),
        )
        assertNull(
            "running, but the revolutions did not answer",
            VehiclePageWords.engineCell(telemetry(running = 1.0)),
        )
    }

    private fun telemetry(
        rpm: Double? = null,
        running: Double? = null,
        trip: TripEnergy = TripEnergy(),
        warm: Boolean = false,
    ): VehicleTelemetry {
        val values = LinkedHashMap<VehicleSignal, Double>()
        rpm?.let { values[VehicleSignal.ENGINE_RPM] = it }
        running?.let { values[VehicleSignal.ENGINE_RUNNING] = it }
        // A trace with one live slot in it: the engine ran inside the last two minutes.
        val trace = if (!warm) EngineTraceSnapshot.EMPTY else EngineTrace().apply {
            sample(atMillis = 0L, engineRunning = true, generationKw = 8.0)
            sample(atMillis = 1_000L, engineRunning = true, generationKw = 8.0)
        }.snapshot()
        return VehicleTelemetry(values = values, engineTrace = trace, trip = trip)
    }
}
