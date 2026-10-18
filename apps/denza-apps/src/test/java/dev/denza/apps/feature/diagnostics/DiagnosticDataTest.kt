package dev.denza.apps.feature.diagnostics

import dev.denza.apps.feature.vehicle.VehicleTelemetry
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiagnosticDataTest {
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
    @Test fun staleExtraReadingsAndLampsDisappearButDtcKeepsItsOriginalTime() {
        val dtc = DtcSnapshot(emptyList(),9,0,123)
        val status = DiagnosticStatus(extras=DiagnosticExtraReader.decode(batch("lv" to 13.8f.toBits()),1000,10),
            indicators=DiagnosticIndicators(mapOf(DiagnosticLamp.ABS to 2),1000),lbms=dtc)
        val f=DiagnosticFrameBuilder().build(VehicleTelemetry(),status,15010)
        assertEquals("—",f.nodes.getValue("lowv").value)
        assertEquals("— / —",f.nodes.getValue("abs").value)
        assertTrue(f.forEcu(DtcEcu.LBMS).dtcRead); assertFalse(f.dtcRead)
        assertEquals(9,f.forEcu(DtcEcu.LBMS).availability)
    }
    @Test fun eachDoorHasIndependentPositionLockWindowInitializationAndProtection() {
        val d=DiagnosticExtraReader.decode(batch("fl_position" to 3,"fl_lock" to 2,"fl_window" to 40,"fl_init" to 2,"fl_thermal" to 1,
            "fr_position" to 2,"fr_lock" to 1,"fr_window" to 0,"fr_init" to 1,"fr_thermal" to 0),10,10)
        val f=DiagnosticFrameBuilder().build(VehicleTelemetry(),DiagnosticStatus(extras=d),11)
        assertEquals(listOf("Приоткрыта","Заперт","40 %","Требуется","Активна"),f.nodes.getValue("door_fl").rows.map { it.second })
        assertEquals(listOf("Закрыта","Открыт","0 %","Готово","Не активна"),f.nodes.getValue("door_fr").rows.map { it.second })
        assertEquals("amber",f.nodes.getValue("door_fl").tone); assertEquals("—",f.nodes.getValue("door_rr").value)
    }
    @Test fun refreshCannotReplaceOrDuplicateManualDtc() = runTest {
        val q=DiagnosticReadQueue()
        assertTrue(q.request(DtcEcu.LBMS)); repeat(10) { q.refresh() }
        assertFalse(q.request(DtcEcu.BODY)); assertEquals(DtcEcu.LBMS,q.next())
        assertFalse(q.request(DtcEcu.BODY)); q.finished()
        assertTrue(q.request(DtcEcu.BODY)); assertEquals(DtcEcu.BODY,q.next())
    }
    @Test fun idleQueuePollsAtFiveSecondsAndWaitingCanBeCancelled() = runTest {
        val q=DiagnosticReadQueue(); val a=async { q.next() }
        runCurrent(); advanceTimeBy(4999); assertFalse(a.isCompleted)
        advanceTimeBy(1); runCurrent(); assertNull(a.await())
        val b=async { q.next() }; runCurrent(); b.cancel(); runCurrent()
        assertTrue(b.isCancelled); assertTrue(q.request(DtcEcu.LBMS)); assertEquals(DtcEcu.LBMS,q.next())
    }
}
