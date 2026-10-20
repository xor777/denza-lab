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
        val rows = JSONObject(File(root,"research/vehicle-diagnostics/readings-20261018.json").readText()).getJSONArray("rows")
        val pairs = (0 until rows.length()).map { rows.getJSONObject(it).let { r -> r.getString("key") to r.getLong("raw").toInt() } }
        val decoded = DiagnosticExtraReader.decode(batch(*pairs.toTypedArray()), 123, 10)
        assertEquals(43,decoded.values.size)
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i); val signal = DiagnosticExtra.entries.first { it.key == r.getString("key") }
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
