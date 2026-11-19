package dev.denza.apps.feature.hud

import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The wheels' speed for HUD Speed's red: only as fresh as the sweep that read it. */
class HudWheelSpeedTest {
    private fun at(sampledAtMs: Long, kmh: Double = 80.0) =
        VehicleTelemetry(values = mapOf(VehicleSignal.VEHICLE_SPEED to kmh), hotReadAtMillis = sampledAtMs)

    @Test
    fun aRetainedSnapshotSeenForTheFirstTimeIsAsOldAsItsSweep() {
        // The hub last answered 80 km/h at 10 s; the first camera asks at 13 s.
        assertNull(HudWheelSpeed.kmh(at(10_000), 13_000))
    }

    @Test
    fun aSweepWithinTwoSecondsIsTheSpeed() {
        assertEquals(80.0, HudWheelSpeed.kmh(at(10_000), 11_500)!!, 0.0)
        assertEquals(80.0, HudWheelSpeed.kmh(at(10_000), 10_000 + HudWheelSpeed.STALE_MS)!!, 0.0)
        assertNull(HudWheelSpeed.kmh(at(10_000), 10_001 + HudWheelSpeed.STALE_MS))
    }

    @Test
    fun aSnapshotWithoutASweepHasNoSpeed() {
        assertNull(HudWheelSpeed.kmh(VehicleTelemetry(values = mapOf(VehicleSignal.VEHICLE_SPEED to 80.0)), 0))
    }
}
