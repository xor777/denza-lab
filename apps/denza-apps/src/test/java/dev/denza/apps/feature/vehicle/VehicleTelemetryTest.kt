package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleTelemetryTest {

    private fun telemetry(vararg values: Pair<VehicleSignal, Double>) = VehicleTelemetry(
        access = VehicleAccess.READY,
        values = values.toMap(),
    )

    @Test
    fun cellSpreadIsComputedBecauseNoFeatureIdReportsIt() {
        val t = telemetry(
            VehicleSignal.CELL_MIN_MV to 3313.0,
            VehicleSignal.CELL_MAX_MV to 3317.0,
        )
        assertEquals(4.0, t.cellSpreadMv!!, 1e-9)
    }

    @Test
    fun missingSignalsStayMissingInsteadOfBecomingZero() {
        val t = telemetry()
        assertNull(t[VehicleSignal.PACK_VOLT])
        assertNull(t.cellSpreadMv)
        assertNull(t.loadKw)
        assertNull(t.hottestMotorC)
        assertTrue(t.motorTemps.all { it == null })
        assertFalse(t.charging)
    }

    @Test
    fun allThreeMotorsAreReportedAndTheHottestLeadsTheRow() {
        val t = telemetry(
            VehicleSignal.MOTOR_FRONT_C to 34.0,
            VehicleSignal.MOTOR_REAR_LEFT_C to 29.0,
            VehicleSignal.MOTOR_REAR_RIGHT_C to 31.0,
        )
        assertEquals(listOf(34.0, 29.0, 31.0), t.motorTemps)
        assertEquals(34.0, t.hottestMotorC!!, 1e-9)
    }

    @Test
    fun aConnectedGunMeansCharging() {
        assertTrue(telemetry(VehicleSignal.CHARGE_GUN to 2.0).charging)
        assertFalse(telemetry(VehicleSignal.CHARGE_GUN to 0.0).charging)
    }

    @Test
    fun parkIsReadFromTheSwitchAndNotFromItsAbsence() {
        // Three answers, not two. Null is not "moving": a trip is bounded by a switch we can read,
        // and a switch that did not answer bounds nothing - which is what [TripEnergyLedger] and
        // the Contour's PARKED scene both hang off.
        assertNull(VehicleTelemetry().parked)
        assertFalse(telemetry(VehicleSignal.GEARBOX_PARK to 0.0).parked!!)
        assertTrue(telemetry(VehicleSignal.GEARBOX_PARK to 1.0).parked!!)
    }

    @Test
    fun loadFollowsTheDocumentedSignConvention() {
        val t = telemetry(VehicleSignal.POWER_KW to -2.0)
        assertEquals(-2.0, t.loadKw!!, 1e-9)
    }

    @Test
    fun insulationIsShownInMegaohms() {
        val t = telemetry(VehicleSignal.INSULATION_KOHM to 13051.0)
        assertEquals(13.051, t.insulationMohm!!, 1e-6)
    }
}
