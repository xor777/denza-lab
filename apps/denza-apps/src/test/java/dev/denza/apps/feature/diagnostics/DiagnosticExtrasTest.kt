package dev.denza.apps.feature.diagnostics

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The qualified scalar GETs the scanner's car feed reads: decoding, sentinels and freshness. */
class DiagnosticExtrasTest {
    private fun parcel(word: Int) = "Result: Parcel(00000000 %08x   '........')".format(word)
    private fun batch(vararg pairs: Pair<String, Int>) = pairs.joinToString("\n") { "@@extra:${it.first}\n${parcel(it.second)}" }
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }.first { File(it,"research/vehicle-diagnostics").exists() }
    @Test fun replayAllFortyThreeQualifiedGetsWithoutChangingUnitsOrWheelOrder() {
        // The pack's current moved to its raw channel (C1, research stream-selection-20261021.json): its
        // 2026-10-18 reading was the converted channel's, which the app no longer reads.
        val rows = JSONObject(File(root,"research/vehicle-diagnostics/readings-20261018.json").readText()).getJSONArray("rows")
            .let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.filter { it.getString("key") != "charge_current" }
        val pairs = rows.map { r -> r.getString("key") to r.getLong("raw").toInt() }
        val decoded = DiagnosticExtraReader.decode(batch(*pairs.toTypedArray()), 123, 10)
        assertEquals(42,decoded.values.size)
        for (r in rows) {
            val signal = DiagnosticExtra.entries.first { it.key == r.getString("key") }
            assertEquals(r.getInt("dev"), signal.device); assertEquals(r.getInt("fid"), signal.fid); assertEquals(r.getInt("tx"), signal.transact)
            assertEquals(r.getDouble("value"), decoded.values.getValue(signal), 0.00001)
        }
        assertEquals(61.0, decoded.values.getValue(DiagnosticExtra.SOC), 0.0)
        assertEquals(2.77, decoded.values.getValue(DiagnosticExtra.FL_PRESSURE), 0.00001)
        assertEquals(13.0, decoded.values.getValue(DiagnosticExtra.FR_TYRE_TEMP), 0.0)
    }
    @Test fun zeroHasSignalSpecificMeaning() {
        val d = DiagnosticExtraReader.decode(batch("fl_window" to 0,"fl_thermal" to 0,"fl_position" to 0,"fl_lock" to 0,"fl_init" to 0),1,1)
        assertEquals(setOf(DiagnosticExtra.FL_WINDOW,DiagnosticExtra.FL_THERMAL),d.values.keys)
    }
    @Test fun invalidFloatsSentinelsAndMissingValuesAreNotZero() {
        val d = DiagnosticExtraReader.decode(batch("soc" to Float.NaN.toBits(),"lv" to Float.POSITIVE_INFINITY.toBits(),"temp_min" to 0,"fr_pressure" to 4095,"rr_lock" to 9),1,1)
        assertTrue(d.values.isEmpty())
        assertNull(DiagnosticExtra.CHARGE_CURRENT.decode((-1f).toBits()))
        assertNull(DiagnosticExtra.CHARGE_CURRENT.decode(-10011))
        assertNull(DiagnosticExtra.CHARGE_CURRENT.decode(-10013))
        assertTrue(DiagnosticExtraReader.decode("@@extra:soh\nResult: Parcel(ffffffff 00000063)",1,1).values.isEmpty())
    }
    @Test fun thePacksCurrentIsItsRawChannelWithTheStockArithmetic() {
        // C1: 1009·C4400018 is float32(wire) of CAN 0x444; the app applies wire × 0.1 − 500.
        val e = DiagnosticExtra.CHARGE_CURRENT
        assertEquals(1009, e.device); assertEquals(0xC4400018.toInt(), e.fid); assertEquals(7, e.transact)
        assertEquals(-4.5, e.decode(4955f.toBits())!!, 1e-9)   // the sweep of 2026-10-20: raw 4955.0 beside converted −4.5
        assertEquals(0.0, e.decode(5000f.toBits())!!, 1e-9)    // CAN444 2026-10-21: wire 5000
        assertEquals(-1.0, e.decode(4990f.toBits())!!, 1e-9)   // a real −1 A, which the converted channel lost
        assertEquals(-500.0, e.decode(0f.toBits())!!, 1e-9)
        assertEquals(1500.0, e.decode(20000f.toBits())!!, 1e-9)
        // −1.0 on the raw channel is only the no-data word: the wire is unsigned
        assertNull(e.decode((-1f).toBits()))
        // FFFF, whose meaning is unproven, and anything above 1500 A
        assertNull(e.decode(65535f.toBits())); assertNull(e.decode(20001f.toBits()))
    }
    @Test fun aRealMinusOneIsAValueWhereTheConvertersRangeHoldsIt() {
        // C1: the front torque (wire × 0.1 − 500) and DC current (wire × 0.1 − 1000) give −1.0 at wire 4990 and 9990.
        for (e in listOf(DiagnosticExtra.TORQUE_FRONT, DiagnosticExtra.CURRENT_FRONT)) {
            assertEquals(e.name, -1.0, e.decode((-1f).toBits())!!, 0.0)
            assertNull(e.decode(-10011)); assertNull(e.decode(-10013))
        }
        // their ranges are the converters' whole range
        assertEquals(-500.0, DiagnosticExtra.TORQUE_FRONT.decode((-500f).toBits())!!, 0.0)
        assertEquals(500.0, DiagnosticExtra.TORQUE_FRONT.decode(500f.toBits())!!, 0.0)
        assertNull(DiagnosticExtra.TORQUE_FRONT.decode(500.1f.toBits())); assertNull(DiagnosticExtra.TORQUE_FRONT.decode((-500.1f).toBits()))
        assertEquals(-1000.0, DiagnosticExtra.CURRENT_FRONT.decode((-1000f).toBits())!!, 0.0)
        assertNull(DiagnosticExtra.CURRENT_FRONT.decode(1000.1f.toBits())); assertNull(DiagnosticExtra.CURRENT_FRONT.decode((-1000.1f).toBits()))
        // elsewhere a float's −1.0 stays the no-data word
        for (e in DiagnosticExtra.entries.filter { it.transact == 7 } - DiagnosticExtra.TORQUE_FRONT - DiagnosticExtra.CURRENT_FRONT) {
            assertNull(e.name, e.decode((-1f).toBits()))
        }
    }
    @Test fun theRearDrivesUnsignedFieldsRefuseAWrapAsNoValue() {
        // C4: torque and current are unsigned16 × 0.1, speed unsigned16; a wrapped negative is no value.
        for (e in listOf(DiagnosticExtra.TORQUE_REAR_LEFT, DiagnosticExtra.TORQUE_REAR_RIGHT, DiagnosticExtra.CURRENT_REAR_LEFT, DiagnosticExtra.CURRENT_REAR_RIGHT)) {
            assertEquals(e.name, 0.0, e.decode(0f.toBits())!!, 0.0)
            assertEquals(e.name, 3000.0, e.decode(3000f.toBits())!!, 0.0)
            listOf(3276.8f, 4000f, 6543.6f, 6553.5f, 3000.1f, -0.1f, -1f, -5f).forEach { assertNull("${e.name} $it", e.decode(it.toBits())) }
        }
        for (e in listOf(DiagnosticExtra.RPM_REAR_LEFT, DiagnosticExtra.RPM_REAR_RIGHT)) {
            assertEquals(e.name, 20000.0, e.decode(20000)!!, 0.0)
            listOf(20001, 32768, 65436, 0xFFFF, -1).forEach { assertNull("${e.name} $it", e.decode(it)) }
        }
    }
    @Test fun insulationAboveTheStockCapIsNoValueNotSixtyThousand() {
        // The stock collector caps the field at 60000; FFFE and FFFF are the firmware's invalid codes.
        for (e in listOf(DiagnosticExtra.INSULATION, DiagnosticExtra.INSULATION_2)) {
            assertEquals(0.0, e.decode(0)!!, 0.0)
            assertEquals(14429.0, e.decode(14429)!!, 0.0)
            assertEquals(60000.0, e.decode(60000)!!, 0.0)
            listOf(60001, 0xFFFE, 0xFFFF, 0x10000, -1).forEach { assertNull("%X".format(it), e.decode(it)) }
        }
        val d = DiagnosticExtraReader.decode(batch("insulation" to 0xFFFE, "insulation_2" to 0xFFFF), 1, 1)
        assertTrue(d.values.isEmpty())
    }
    @Test fun countsOf254And255AreTheFirmwaresInvalidNotCounts() {
        for (e in listOf(DiagnosticExtra.CELL_COUNT, DiagnosticExtra.PROBE_COUNT)) {
            assertEquals(1.0, e.decode(1)!!, 0.0)
            assertEquals(166.0, e.decode(166)!!, 0.0)
            assertEquals(253.0, e.decode(253)!!, 0.0)
            listOf(0, 254, 255, 256, 1023).forEach { assertNull("$it", e.decode(it)) }
        }
    }
    @Test fun partialRefreshDropsOldValuesAndExpiryUsesMonotonicTime() {
        val d = DiagnosticExtraReader.decode(batch("soh" to 99),100000,10)
        assertEquals(1,d.fresh(15009).size); assertTrue(d.fresh(15010).isEmpty()); assertTrue(d.fresh(9).isEmpty())
        assertTrue(DiagnosticExtraReader.decode("",100001,20).values.isEmpty())
    }
    @Test fun eachDoorHasIndependentPositionLockWindowInitializationAndProtection() {
        val d=DiagnosticExtraReader.decode(batch("fl_position" to 3,"fl_lock" to 2,"fl_window" to 40,"fl_init" to 2,"fl_thermal" to 1,
            "fr_position" to 2,"fr_lock" to 1,"fr_window" to 0,"fr_init" to 1,"fr_thermal" to 0),10,10)
        assertEquals(listOf(3.0,2.0,40.0,2.0,1.0),listOf(DiagnosticExtra.FL_POSITION,DiagnosticExtra.FL_LOCK,
            DiagnosticExtra.FL_WINDOW,DiagnosticExtra.FL_INIT,DiagnosticExtra.FL_THERMAL).map { d.values.getValue(it) })
        assertEquals(listOf(2.0,1.0,0.0,1.0,0.0),listOf(DiagnosticExtra.FR_POSITION,DiagnosticExtra.FR_LOCK,
            DiagnosticExtra.FR_WINDOW,DiagnosticExtra.FR_INIT,DiagnosticExtra.FR_THERMAL).map { d.values.getValue(it) })
        assertTrue(DiagnosticExtra.entries.filter { it.key.startsWith("rl_") || it.key.startsWith("rr_") }.none { it in d.values })
    }
}
