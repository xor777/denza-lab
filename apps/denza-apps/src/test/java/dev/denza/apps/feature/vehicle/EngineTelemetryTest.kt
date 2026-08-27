package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTelemetryTest {

    private fun telemetry(vararg values: Pair<VehicleSignal, Double>) = VehicleTelemetry(
        access = VehicleAccess.READY,
        values = values.toMap(),
    )

    @Test
    fun aLampNothingAnsweredForIsUnknownRatherThanHealthy() {
        assertEquals(LampState.UNKNOWN, telemetry().lamp(EngineLamp.COOLANT_LEVEL))
    }

    @Test
    fun oneAnsweringIdIsEnoughToCallALampHealthy() {
        val t = telemetry(VehicleSignal.COOLANT_LEVEL_LOW_B to 0.0)
        assertEquals(LampState.OK, t.lamp(EngineLamp.COOLANT_LEVEL))
    }

    @Test
    fun anyOfTheGenerationVariantsCanRaiseTheLamp() {
        // Four feature ids carry low coolant level on this firmware; the one
        // this generation actually uses is not known, so the worst answer wins.
        val t = telemetry(
            VehicleSignal.COOLANT_LEVEL_LOW_A to 0.0,
            VehicleSignal.COOLANT_LEVEL_LOW_B to 0.0,
            VehicleSignal.COOLANT_LEVEL_LOW_C to 0.0,
            VehicleSignal.COOLANT_LEVEL_LOW_D to 1.0,
        )
        assertEquals(LampState.ALERT, t.lamp(EngineLamp.COOLANT_LEVEL))
        assertEquals(listOf(EngineLamp.COOLANT_LEVEL), t.lampAlerts)
    }

    @Test
    fun everyLampIsBackedByAtLeastOneFlagSignal() {
        EngineLamp.entries.forEach { lamp ->
            assertTrue(lamp.name, lamp.signals.isNotEmpty())
            assertTrue(lamp.name, lamp.signals.all { it.kind == VehicleKind.FLAG })
        }
    }

    @Test
    fun engineStateIsUnknownUntilItAnswers() {
        assertNull(telemetry().engineRunning)
        assertFalse(telemetry(VehicleSignal.ENGINE_RUNNING to 0.0).engineRunning!!)
        assertTrue(telemetry(VehicleSignal.ENGINE_RUNNING to 1.0).engineRunning!!)
    }

    @Test
    fun generationCountsWhenEitherTheStateOrThePowerSaysSo() {
        assertFalse(telemetry(VehicleSignal.GENERATION_KW to 0.0).generating)
        assertTrue(telemetry(VehicleSignal.GENERATION_KW to 14.0).generating)
        assertTrue(telemetry(VehicleSignal.GENERATION_STATE to 1.0).generating)
    }

    @Test
    fun theShutdownStateIsNotGeneration() {
        // Captured on the car: the state goes 1 -> 2 a second and a half before
        // the engine stops, with the kilowatt figure already back at zero. A
        // `>= 1` test would have kept claiming generation through the spin-down.
        val t = telemetry(
            VehicleSignal.GENERATION_STATE to 2.0,
            VehicleSignal.GENERATION_KW to 0.0,
        )
        assertFalse(t.generating)
    }

    @Test
    fun theMeasuredStateThreeMeansRunningBeforeGenerationStarts() {
        // 2026-08-23, parked: state 3 and 1619 rpm appeared before generation
        // came online. Treating only state 1 as running would miss this phase.
        val starting = telemetry(
            VehicleSignal.ENGINE_RUNNING to 3.0,
            VehicleSignal.ENGINE_RPM to 1619.0,
            VehicleSignal.GENERATION_KW to 0.0,
            VehicleSignal.GENERATION_STATE to 0.0,
        )
        assertTrue(starting.engineRunning!!)
        assertFalse(starting.generating)
    }

    @Test
    fun theCombustionReadsMeasuredOnTheCarDecodeAsThemselves() {
        // 2026-08-23, engine stopped: rpm and running both read 0.
        assertEquals(0.0, AutoserviceShell.decode(VehicleSignal.ENGINE_RPM, 0)!!, 1e-9)
        assertEquals(0.0, AutoserviceShell.decode(VehicleSignal.ENGINE_RUNNING, 0)!!, 1e-9)
        // A lamp is a small enum, never a temperature-sized number.
        assertNull(AutoserviceShell.decode(VehicleSignal.OIL_LEVEL_LAMP, 65535))
    }
}
