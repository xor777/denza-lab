package dev.denza.apps.feature.hud

import dev.denza.apps.feature.vehicle.ConsumptionLog
import dev.denza.apps.feature.vehicle.EngineCharge
import dev.denza.apps.feature.vehicle.TripEnergyLedger
import dev.denza.apps.feature.vehicle.VehicleAnsweredSweep
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The wheels' speed for HUD Speed's red: only as fresh as the start of the sweep that read it. */
class HudWheelSpeedTest {
    private val horizon = VehicleTelemetry.HOT_HORIZON_MS

    private fun read(startedAtMs: Long, endedAtMs: Long = startedAtMs, kmh: Double = 80.0) =
        VehicleAnsweredSweep.snapshot(
            mapOf(VehicleSignal.VEHICLE_SPEED to kmh), ConsumptionLog(), TripEnergyLedger(), EngineCharge(),
            readAtMillis = endedAtMs, readStartedAtMillis = startedAtMs,
        )

    @Test
    fun aRetainedSnapshotSeenForTheFirstTimeIsAsOldAsItsSweep() {
        // The hub last answered 80 km/h at 10 s; the first camera asks at 13 s.
        assertNull(HudWheelSpeed.kmh(read(10_000), 13_000))
    }

    /**
     * Review round 2, finding 7: the wheels answered 80 km/h at the start of a batch whose cold
     * ids took 2.5 s more; the driver braked meanwhile. Stamped at the batch's end the 80 looked
     * fresh to the next camera.
     */
    @Test
    fun aSlowSweepIsAsOldAsItsStart() {
        val slow = read(startedAtMs = 10_000, endedAtMs = 12_500)
        assertNull(HudWheelSpeed.kmh(slow, 12_600))
        assertEquals(80.0, HudWheelSpeed.kmh(slow, 10_000 + horizon - 1)!!, 0.0)
    }

    @Test
    fun aSweepWithinTheHorizonIsTheSpeed() {
        assertEquals(80.0, HudWheelSpeed.kmh(read(10_000), 11_500)!!, 0.0)
        assertEquals(80.0, HudWheelSpeed.kmh(read(10_000), 10_000 + horizon - 1)!!, 0.0)
        assertNull(HudWheelSpeed.kmh(read(10_000), 10_000 + horizon))
    }

    @Test
    fun aSnapshotWithoutASweepHasNoSpeed() {
        assertNull(HudWheelSpeed.kmh(VehicleTelemetry(values = mapOf(VehicleSignal.VEHICLE_SPEED to 80.0)), 0))
    }
}
