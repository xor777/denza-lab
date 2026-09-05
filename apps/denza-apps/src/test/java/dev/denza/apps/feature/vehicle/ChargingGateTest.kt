package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When this car may be called "charging", after it was called that on a motorway.
 *
 * The gate read `gun >= 1` on the strength of one parked session where the gun answered `2` with
 * 2.4 kW flowing. On 2026-09-06 the head unit's second page printed «В БАТАРЕЮ ОТ ЗАРЯДКИ» over a
 * pack that was plainly discharging on the road, which falsifies the reading of that session: this
 * id sits at or above 1 with no charger anywhere.
 *
 * Both screens read this - the cluster's countdown hangs off it too - so the tests are here rather
 * than beside either of them.
 */
class ChargingGateTest {

    @Test
    fun aParkedCarOnAChargerIsCharging() {
        assertTrue(
            telemetry(
                VehicleSignal.CHARGE_GUN to 2.0,
                VehicleSignal.CHARGE_KW to 2.4,
                VehicleSignal.POWER_KW to -2.0,
            ).charging,
        )
    }

    @Test
    fun andSoIsOneWhoseChargerOnlyOffersAnEstimate() {
        assertTrue(
            telemetry(
                VehicleSignal.CHARGE_GUN to 2.0,
                VehicleSignal.CHARGE_HOURS to 2.0,
                VehicleSignal.CHARGE_MINUTES to 15.0,
                VehicleSignal.POWER_KW to -7.0,
            ).charging,
        )
    }

    /** The failure that started this: a gun value on the road, and a pack emptying under it. */
    @Test
    fun aCarOnTheRoadIsNotChargingWhateverTheGunSays() {
        assertFalse(
            "the gun alone",
            telemetry(VehicleSignal.CHARGE_GUN to 1.0, VehicleSignal.POWER_KW to 34.0).charging,
        )
        assertFalse(
            "even at the value a charger uses",
            telemetry(VehicleSignal.CHARGE_GUN to 2.0, VehicleSignal.POWER_KW to 34.0).charging,
        )
        assertFalse(
            "and even with a stale estimate behind it",
            telemetry(
                VehicleSignal.CHARGE_GUN to 2.0,
                VehicleSignal.CHARGE_MINUTES to 40.0,
                VehicleSignal.POWER_KW to 34.0,
            ).charging,
        )
    }

    @Test
    fun aGunWithNothingBehindItIsNotACharger() {
        assertFalse(telemetry(VehicleSignal.CHARGE_GUN to 2.0).charging)
    }

    @Test
    fun andNothingAnsweringIsNotACharger() {
        assertFalse(telemetry().charging)
    }

    private fun telemetry(vararg values: Pair<VehicleSignal, Double>) = VehicleTelemetry(
        access = VehicleAccess.READY,
        values = values.toMap(),
    )
}
