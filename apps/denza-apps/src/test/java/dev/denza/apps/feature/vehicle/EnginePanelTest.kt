package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginePanelTest {

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
    fun everyLampIsBackedByAtLeastOneEngineOnlySignal() {
        EngineLamp.entries.forEach { lamp ->
            assertTrue(lamp.name, lamp.signals.isNotEmpty())
            assertTrue(lamp.name, lamp.signals.all { it.engineOnly })
            assertTrue(lamp.name, lamp.signals.all { it.kind == VehicleKind.FLAG })
        }
    }

    @Test
    fun theCombustionSetJoinsTheSweepOnlyForTheEnginePage() {
        val withoutEngine = VehicleSignal.sweep(VehiclePoll.COLD, engine = false)
        val withEngine = VehicleSignal.sweep(VehiclePoll.COLD, engine = true)
        assertTrue(withoutEngine.none { it.engineOnly })
        assertTrue(withEngine.containsAll(withoutEngine))
        assertTrue(withEngine.size > withoutEngine.size)
        assertFalse(VehicleSignal.sweep(VehiclePoll.HOT, engine = false).contains(VehicleSignal.ENGINE_RPM))
        assertTrue(VehicleSignal.sweep(VehiclePoll.HOT, engine = true).contains(VehicleSignal.ENGINE_RPM))
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
    fun theMeasuredStartStopCycleReadsTheWayThePageDrawsIt() {
        // 2026-08-23, parked: 0/0 stopped, then rpm 1619 with state 3 before
        // generation came online, a 1321 set-point at 8-10 kW, then spin-down.
        val stopped = telemetry(VehicleSignal.ENGINE_RUNNING to 0.0, VehicleSignal.ENGINE_RPM to 0.0)
        assertFalse(stopped.engineRunning!!)
        assertFalse(stopped.generating)

        val starting = telemetry(
            VehicleSignal.ENGINE_RUNNING to 3.0,
            VehicleSignal.ENGINE_RPM to 1619.0,
            VehicleSignal.GENERATION_KW to 0.0,
            VehicleSignal.GENERATION_STATE to 0.0,
        )
        assertTrue(starting.engineRunning!!)
        assertFalse(starting.generating)

        val generating = telemetry(
            VehicleSignal.ENGINE_RUNNING to 3.0,
            VehicleSignal.ENGINE_RPM to 1321.0,
            VehicleSignal.GENERATION_KW to 8.0,
            VehicleSignal.GENERATION_STATE to 1.0,
            VehicleSignal.POWER_KW to -8.0,
        )
        assertTrue(generating.generating)
        // Generation and pack power mirrored each other exactly, which is what
        // proves the generation figure is in kilowatts.
        assertEquals(-generating.loadKw!!, generating.generationKw!!, 1e-9)
    }

    @Test
    fun theCombustionReadsMeasuredOnTheCarDecodeAsThemselves() {
        // 2026-08-23, engine stopped: rpm and running both read 0, displacement
        // came back as a float and matched this car's 2.0 T.
        assertEquals(0.0, AutoserviceShell.decode(VehicleSignal.ENGINE_RPM, 0)!!, 1e-9)
        assertEquals(0.0, AutoserviceShell.decode(VehicleSignal.ENGINE_RUNNING, 0)!!, 1e-9)
        assertEquals(2.0, AutoserviceShell.decode(VehicleSignal.ENGINE_LITRES, 0x40000000)!!, 1e-6)
        // A lamp is a small enum, never a temperature-sized number.
        assertNull(AutoserviceShell.decode(VehicleSignal.OIL_LEVEL_LAMP, 65535))
    }
}
