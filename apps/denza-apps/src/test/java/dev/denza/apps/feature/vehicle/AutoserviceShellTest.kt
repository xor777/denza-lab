package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoserviceShellTest {

    @Test
    fun commandTagsEveryCallAndPassesFeatureIdsAsSignedDecimals() {
        val command = AutoserviceShell.command(
            listOf(VehicleSignal.POWER_KW, VehicleSignal.PACK_VOLT),
        )
        assertEquals(
            "echo @@0; service call autoservice 5 i32 1012 i32 339738656; " +
                "echo @@1; service call autoservice 5 i32 1009 i32 1145045000",
            command,
        )
    }

    @Test
    fun everyAllowlistFeatureIdSurvivesTheTripToTheShell() {
        val command = AutoserviceShell.command(VehicleSignal.entries)
        VehicleSignal.entries.forEach { signal ->
            assertTrue(signal.name, command.contains("i32 ${signal.device} i32 ${signal.fid}"))
        }
        // `service call` reads i32, so a feature id past 0x7fffffff has to
        // arrive as a negative decimal. Nothing may reach the shell as hex.
        assertFalse(command.contains("0x"))
    }

    @Test
    fun floatSignalsUseTransactSeven() {
        val command = AutoserviceShell.command(listOf(VehicleSignal.CHARGE_KW))
        assertTrue(command.contains("service call autoservice 7 i32 1009"))
    }

    @Test
    fun parseKeepsAnswersOnTheirOwnSignals() {
        val batch = listOf(
            VehicleSignal.POWER_KW,
            VehicleSignal.CHARGE_KW,
            VehicleSignal.PACK_VOLT,
        )
        val output = """
            @@0
            Result: Parcel(00000000 0000002b   '........')
            @@1
            Result: Parcel(00000000 422c0000   '..,.....')
            @@2
            Result: Parcel(00000000 00000226   '....&...')
        """.trimIndent()

        val values = AutoserviceShell.parse(output, batch)

        assertEquals(43.0, values.getValue(VehicleSignal.POWER_KW), 1e-9)
        assertEquals(43.0, values.getValue(VehicleSignal.CHARGE_KW), 1e-6)
        assertEquals(550.0, values.getValue(VehicleSignal.PACK_VOLT), 1e-9)
    }

    @Test
    fun aSilentCallDoesNotShiftTheAnswersThatFollow() {
        // The middle feature id is not supported on this generation and prints
        // nothing at all; the third answer must stay the third signal's.
        val batch = listOf(
            VehicleSignal.POWER_KW,
            VehicleSignal.MOTOR_REAR_LEFT_C,
            VehicleSignal.PACK_VOLT,
        )
        val output = """
            @@0
            Result: Parcel(00000000 0000002b   '........')
            @@1
            @@2
            Result: Parcel(00000000 00000226   '....&...')
        """.trimIndent()

        val values = AutoserviceShell.parse(output, batch)

        assertEquals(43.0, values.getValue(VehicleSignal.POWER_KW), 1e-9)
        assertFalse(values.containsKey(VehicleSignal.MOTOR_REAR_LEFT_C))
        assertEquals(550.0, values.getValue(VehicleSignal.PACK_VOLT), 1e-9)
    }

    @Test
    fun sentinelsAreNotReadings() {
        // -10013 (wrong transact), -10011 (no data on this generation), -1.0f.
        assertNull(AutoserviceShell.decode(VehicleSignal.PACK_VOLT, 0xFFFFD8E3.toInt()))
        assertNull(AutoserviceShell.decode(VehicleSignal.PACK_VOLT, 0xFFFFD8E5.toInt()))
        assertNull(AutoserviceShell.decode(VehicleSignal.CHARGE_KW, 0xBF800000.toInt()))
    }

    @Test
    fun aSignalsOwnUnavailableWordIsNotAReadingEither() {
        // Live on 2026-08-25, engine off and the ECU asleep: rpm answered 0x1FFF - thirteen bits of
        // ones - and the cluster printed 8191 об/мин. No range gate can catch that; 8191 is a
        // perfectly ordinary rpm. Only the bit pattern says "not available".
        assertNull(AutoserviceShell.decode(VehicleSignal.ENGINE_RPM, 0x1FFF))
        // The word means nothing outside the signal that declared it.
        assertEquals(8191.0, AutoserviceShell.decode(VehicleSignal.INSULATION_KOHM, 0x1FFF)!!, 1e-9)
        // And a real reading either side of it still reads.
        assertEquals(0.0, AutoserviceShell.decode(VehicleSignal.ENGINE_RPM, 0)!!, 1e-9)
        assertEquals(8190.0, AutoserviceShell.decode(VehicleSignal.ENGINE_RPM, 0x1FFE)!!, 1e-9)
    }

    @Test
    fun provenScalesFromTheSessionCapture() {
        // Pack temperature: raw 68 read 28 C against a third-party dashboard.
        assertEquals(28.0, AutoserviceShell.decode(VehicleSignal.PACK_TEMP_AVG, 68)!!, 1e-9)
        // Cell voltage stays in millivolts; 3313 mV.
        assertEquals(3313.0, AutoserviceShell.decode(VehicleSignal.CELL_MIN_MV, 3313)!!, 1e-9)
        // Battery health is reported as a whole percent.
        assertEquals(96.0, AutoserviceShell.decode(VehicleSignal.SOH_PERCENT, 96)!!, 1e-9)
        // Odometer is tenths of a kilometre: 118927 -> 11892.7.
        assertEquals(11892.7, AutoserviceShell.decode(VehicleSignal.ODOMETER_KM, 118927)!!, 1e-6)
        // Charge power, float, 2.4 kW on the household socket.
        assertEquals(2.4, AutoserviceShell.decode(VehicleSignal.CHARGE_KW, 0x4019999A)!!, 1e-4)
    }

    @Test
    fun valuesThatCannotBeTrueForTheirUnitAreDropped() {
        // 255 is a common max-range placeholder: as a pack temperature it would
        // decode to 215 C.
        assertNull(AutoserviceShell.decode(VehicleSignal.PACK_TEMP_AVG, 255))
        // -40 raw is the vendor's "no sensor" marker on the as-is scales.
        assertNull(AutoserviceShell.decode(VehicleSignal.MOTOR_FRONT_C, -40))
        // Battery health cannot exceed 100 %.
        assertNull(AutoserviceShell.decode(VehicleSignal.SOH_PERCENT, 125))
        // The traction pack is not a 12 V battery.
        assertNull(AutoserviceShell.decode(VehicleSignal.PACK_VOLT, 12))
        // No charger delivers 300 kW to this car; the panel showed one once.
        assertNull(AutoserviceShell.decode(VehicleSignal.CHARGE_KW, 0x43960000))
        // Pack power is a different gate: this car really can pull 300 kW.
        assertEquals(300.0, AutoserviceShell.decode(VehicleSignal.POWER_KW, 300)!!, 1e-9)
    }

    @Test
    fun hotAndColdSetsCoverTheAllowlistWithoutOverlap() {
        val hot = VehicleSignal.HOT.toSet()
        val cold = VehicleSignal.COLD.toSet()
        assertTrue(hot.intersect(cold).isEmpty())
        assertEquals(VehicleSignal.entries.size, hot.size + cold.size)
        assertTrue(VehicleSignal.POWER_KW in hot)
        assertTrue(VehicleSignal.ODOMETER_KM in hot)
        assertTrue(VehicleSignal.MOTOR_REAR_RIGHT_C in cold)
    }

    @Test
    fun onlyReadTransactsAreEverIssued() {
        // Transact 6 is setInt. It must not exist anywhere in this feature.
        assertTrue(VehicleSignal.entries.all { it.transact.code == 5 || it.transact.code == 7 })
        val command = AutoserviceShell.command(VehicleSignal.entries.toList())
        assertFalse(command.contains("autoservice 6 "))
    }
}
