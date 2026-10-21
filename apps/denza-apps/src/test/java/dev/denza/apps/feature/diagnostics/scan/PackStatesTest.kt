package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.DiagnosticExtraReader
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four states the firmware names (architecture 6.3): the main contactor, the pack's charge
 * state, the charger's state and fault - GETs of the batch, words on the pack's «Потоковые данные»,
 * and the contactor on the high-voltage scheme.
 */
class PackStatesTest {
    private fun model(extras: Map<DiagnosticExtra, Double>, fresh: Boolean = true) = ModelBuilder(ScanClock(ZoneOffset.UTC)).build(SessionSnapshot(
        link = LinkState.IDLE, phase = ScanPhase.NEVER, done = 0, finishedAtMillis = null, notice = null, polling = null,
        results = emptyMap(), idents = emptyMap(), open = emptySet(), streams = emptyMap(), live = null, streaming = false,
        recording = null, car = CarReadings(extras = extras, extrasFresh = fresh), nowMillis = Fixtures.EPOCH,
    ))

    private fun DiagnosticsModel.row(name: String) = systems.pack.rows.first { it.name == name }

    @Test
    fun theFourGetsAreInTheBatchAsTheResearchTypedThem() {
        listOf(
            DiagnosticExtra.CONTACTOR to (1032 to 0x34400010),
            DiagnosticExtra.PACK_CHARGE_STATE to (1009 to 0x34400018),
            DiagnosticExtra.CHARGER_STATE to (1009 to 0x27C0000A),
            DiagnosticExtra.CHARGER_FAULT to (1009 to 0x27C00008),
        ).forEach { (e, at) ->
            assertEquals(at, e.device to e.fid)
            assertEquals("an integer GET", 5, e.transact)
            assertTrue(DiagnosticExtraReader.command.contains("echo @@extra:${e.key}; service call autoservice 5 i32 ${at.first} i32 ${at.second}"))
        }
        val read = DiagnosticExtraReader.decode(
            listOf("contactor" to 1, "pack_charge_state" to 13, "charger_state" to 4, "charger_fault" to 3).joinToString("\n") { (k, w) ->
                "@@extra:$k\nResult: Parcel(00000000 %08x   '........')".format(w)
            },
            1, 1,
        )
        assertEquals(mapOf(DiagnosticExtra.CONTACTOR to 1.0, DiagnosticExtra.PACK_CHARGE_STATE to 13.0,
            DiagnosticExtra.CHARGER_STATE to 4.0, DiagnosticExtra.CHARGER_FAULT to 3.0), read.values)
    }

    @Test
    fun theRowsSayTheFirmwaresWords() {
        val m = model(mapOf(DiagnosticExtra.CONTACTOR to 1.0, DiagnosticExtra.PACK_CHARGE_STATE to 0.0,
            DiagnosticExtra.CHARGER_STATE to 1.0, DiagnosticExtra.CHARGER_FAULT to 1.0))
        val names = listOf("Главный контактор, разряд", "Состояние заряда батареи", "Зарядное устройство, состояние", "Зарядное устройство, отказ")
        assertEquals(names, m.systems.pack.rows.takeLast(4).map { it.name })
        assertEquals(listOf("замкнут", "готов", "готово", "норма"), names.map { m.row(it).value })
        names.forEach {
            val r = m.row(it)
            assertEquals("", r.unit)
            assertEquals("—", r.norm)
            assertFalse(r.outOfNorm)
            // a state keeps no trace and no extremes
            assertEquals("" to "", r.min to r.max)
            assertTrue(r.samples.isEmpty())
        }
        assertEquals(listOf("1032·34400010", "1009·34400018", "1009·27C0000A", "1009·27C00008"), names.map { m.row(it).source })
        assertEquals(listOf("contactor", "pack_charge_state", "charger_state", "charger_fault"),
            names.map { (m.row(it).key as ParamKey.Signal).key })
        // Every word the firmware has.
        val charge = (0..13).map { SystemsBuilder.word(SystemsBuilder.PACK_CHARGE_WORDS, it.toDouble()) }
        assertEquals(listOf("готов", "заряд", "заряд завершён", "разряд", "заряд прерван", "отказ C10", "отказ пистолета",
            "отказ зарядного", "отказ сети AC", "по расписанию", "разряд CBU", "тайм-аут", "разряд завершён", "заряд на паузе"), charge)
        assertEquals(listOf("готово", "старт", "завершено", "прервано"), (1..4).map { SystemsBuilder.word(SystemsBuilder.CHARGER_STATE_WORDS, it.toDouble()) })
        assertEquals(listOf("норма", "лёгкий отказ", "тяжёлый отказ"), (1..3).map { SystemsBuilder.word(SystemsBuilder.CHARGER_FAULT_WORDS, it.toDouble()) })
        assertEquals("разомкнут", SystemsBuilder.word(SystemsBuilder.CONTACTOR_WORDS, 0.0))
    }

    @Test
    fun anUnknownCodeIsItsNumberAndNothingIsNa() {
        val m = model(mapOf(DiagnosticExtra.CONTACTOR to 7.0, DiagnosticExtra.CHARGER_STATE to 0.0))
        assertEquals("код 7", m.row("Главный контактор, разряд").value)
        assertEquals("код 0", m.row("Зарядное устройство, состояние").value)
        assertEquals("н/д", m.row("Состояние заряда батареи").value)
        assertEquals("н/д", m.row("Зарядное устройство, отказ").value)
        assertEquals("a batch past its horizon is no reading", "н/д",
            model(mapOf(DiagnosticExtra.CONTACTOR to 1.0), fresh = false).row("Главный контактор, разряд").value)
    }

    @Test
    fun theSchemesContactor() {
        assertEquals(true, model(mapOf(DiagnosticExtra.CONTACTOR to 1.0)).systems.hv.contactorClosed)
        assertEquals(false, model(mapOf(DiagnosticExtra.CONTACTOR to 0.0)).systems.hv.contactorClosed)
        assertNull(model(mapOf(DiagnosticExtra.CONTACTOR to 2.0)).systems.hv.contactorClosed)
        assertNull(model(emptyMap()).systems.hv.contactorClosed)
        assertNull("an old reading is not drawn", model(mapOf(DiagnosticExtra.CONTACTOR to 1.0), fresh = false).systems.hv.contactorClosed)
    }

    @Test
    fun aStateOpensItsParameterPageByItsRowsName() {
        val spec = ParamSpec.of(ParamKey.Signal("charger_fault"))!!
        assertEquals("Зарядное устройство, отказ", spec.name)
    }
}
