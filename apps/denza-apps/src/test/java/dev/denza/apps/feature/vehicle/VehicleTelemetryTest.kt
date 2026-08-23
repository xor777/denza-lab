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
    fun theBmsStateOfChargeTracksTheDisplayedOne() {
        // Both live captures agree: 43.2 against 43 %, 61.6 against 62 %. The
        // pack size is not derivable from this pair — dividing a state of charge
        // by itself would always land near 100.
        val t = telemetry(
            VehicleSignal.BMS_SOC_PERCENT to 61.6,
            VehicleSignal.SOC_PERCENT to 62.0,
        )
        assertEquals(61.6, t[VehicleSignal.BMS_SOC_PERCENT]!!, 1e-9)
        assertEquals(0.4, t[VehicleSignal.SOC_PERCENT]!! - t[VehicleSignal.BMS_SOC_PERCENT]!!, 0.01)
    }

    @Test
    fun theCellWindowIsReportedInVolts() {
        val t = telemetry(
            VehicleSignal.CELL_MIN_MV to 3313.0,
            VehicleSignal.CELL_MAX_MV to 3317.0,
        )
        val window = t.cellWindowVolt!!
        assertEquals(3.313, window.first, 1e-9)
        assertEquals(3.317, window.second, 1e-9)
    }

    @Test
    fun cellSpreadIsComputedBecauseNoFeatureIdReportsIt() {
        val t = telemetry(
            VehicleSignal.CELL_MIN_MV to 3313.0,
            VehicleSignal.CELL_MAX_MV to 3317.0,
        )
        assertEquals(4.0, t.cellSpreadMv!!, 1e-9)
    }

    @Test
    fun cellAverageComesFromPackVoltageOverTheSeriesCount() {
        val t = telemetry(
            VehicleSignal.PACK_VOLT to 550.0,
            VehicleSignal.CELL_COUNT to 166.0,
        )
        assertEquals(3.313, t.cellAverageVolt!!, 1e-3)
    }

    @Test
    fun missingSignalsStayMissingInsteadOfBecomingZero() {
        val t = telemetry()
        assertNull(t[VehicleSignal.PACK_VOLT])
        assertNull(t.cellWindowVolt)
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
    fun loadFollowsTheDocumentedSignConvention() {
        val t = telemetry(VehicleSignal.POWER_KW to -2.0)
        val expected = if (VehicleConvention.POWER_POSITIVE_IS_DISCHARGE) -2.0 else 2.0
        assertEquals(expected, t.loadKw!!, 1e-9)
    }

    @Test
    fun insulationIsShownInMegaohms() {
        val t = telemetry(VehicleSignal.INSULATION_KOHM to 13051.0)
        assertEquals(13.051, t.insulationMohm!!, 1e-6)
    }
}
