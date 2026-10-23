package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.DiagRequest
import dev.denza.apps.feature.diagnostics.protocol.hexBytes
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TestsBuilder] at the edges the boards do not show: a test that has not run, a value past either limit and
 * with limits equal, a scaling ID outside J1979's table, signed words, a monitor or the whole read refused or
 * unanswered, a map that lists nothing, an answer of three tests, a single monitor.
 */
class TestsBuilderTest {
    private val clock = ScanClock(ZoneOffset.UTC)
    private val at = 12 * 3_600_000L + 34 * 60_000L + 56_000L

    private fun answer(ask: String, back: String) = DiagReply.Answer(DiagRequest(0x7E0, hexBytes(ask)), hexBytes(back), 1, at)

    private fun view(vararg pairs: Pair<String, String>, noReply: List<String> = emptyList()): TestsView {
        val replies = pairs.map { (ask, back) -> answer(ask, back) as DiagReply } +
            noReply.map { DiagReply.NoReply(DiagRequest(0x7E0, hexBytes(it)), 3, at) }
        return TestsBuilder.view("ECM", ObdReadProcedure.tests(ModuleRead(0x7E0, at, replies)), clock)
    }

    private fun row(raw: String): TestRow = TestsBuilder.row(ObdTestData("", "", "", "", "", null, false, raw))!!

    @Test
    fun aTestThatHasNotRunHasNoWordsNoScaleAndNoJudgement() {
        val r = row("21 80 03 00 00 00 00 00 00")
        assertEquals(listOf("—", "—", "—", ""), listOf(r.value, r.min, r.max, r.unit))
        assertNull(r.pass)
        assertNull(r.position)
        assertEquals(0, r.beyond)
    }

    @Test
    fun aValuePastALimitStandsAtTheEndItLeftBy() {
        val high = row("A4 0C 24 00 34 00 00 00 28")
        assertEquals(false, high.pass)
        assertEquals(1f, high.position!!, 0f)
        assertEquals(1, high.beyond)
        assertEquals("52", high.value)
        val low = row("21 80 03 00 15 00 1E 00 64")
        assertEquals(false, low.pass)
        assertEquals(0f, low.position!!, 0f)
        assertEquals(-1, low.beyond)
        assertEquals(listOf("0,21", "0,30", "1,00", ""), listOf(low.value, low.min, low.max, low.unit))
        // limits equal: the middle when the value is them, an end when it is not
        assertEquals(0.5f, row("21 80 10 00 05 00 05 00 05").position!!, 0f)
        assertEquals(1, row("21 80 10 00 06 00 05 00 05").beyond)
        // limits the wrong way round: no scale, not in them
        val wrong = row("21 80 10 00 05 00 09 00 01")
        assertNull(wrong.position)
        assertEquals(false, wrong.pass)
    }

    @Test
    fun aScalingOutsideTheTableKeepsTheBlocksOwnNumbersAndItsScale() {
        // UASID 3A is not in J1979's table: raw numbers, «ед. блока», the scale on the same numbers
        val r = row("35 80 3A 01 F4 00 64 07 D0")
        assertEquals(listOf("500", "100", "2 000", TestsBuilder.RAW_UNIT), listOf(r.value, r.min, r.max, r.unit))
        assertEquals(true, r.pass)
        assertEquals(400f / 1900f, r.position!!, 1e-6f)
        // signed above 80 even unknown: FF9C is −100
        val s = row("35 80 BA FF 9C FF 38 00 C8")
        assertEquals(listOf("−100", "−200", "+200"), listOf(s.value, s.min, s.max))
        assertEquals(0.25f, s.position!!, 1e-6f)
    }

    @Test
    fun signedWordsAreSignedAndScaled() {
        val r = row("81 80 AF FF 16 F6 3C 09 C4")
        assertEquals(listOf("−2,34", "−25,00", "+25,00", "%"), listOf(r.value, r.min, r.max, r.unit))
        assertEquals(true, r.pass)
        assertEquals(2266f / 5000f, r.position!!, 1e-6f)
        assertEquals("81 80 AF FF 16 F6 3C 09 C4", r.raw)
    }

    @Test
    fun theWholeReadRefusedUnansweredOrEmptyIsAQuietLine() {
        val refused = view("06 00" to "7F 06 12")
        assertEquals(ReadState.REFUSED, refused.state)
        assertEquals("ECM не отдаёт тесты · 7F 06 12", refused.word)
        assertNull(refused.line)
        assertTrue(refused.columns.isEmpty())
        assertEquals(0, refused.outOfNorm)

        val silent = view(noReply = listOf("06 00"))
        assertEquals(ReadState.NO_REPLY, silent.state)
        assertEquals("нет ответа · шлюз 3", silent.word)

        val empty = view("06 00" to "46 00 00 00 00 00")
        assertEquals(ReadState.EMPTY, empty.state)
        assertEquals("блок не перечисляет тестов", empty.word)
        assertEquals(listOf("06 00 → 46 00 00 00 00 00"), empty.raw)

        assertEquals("не читали", TestsBuilder.view("ECM", ObdTestsData(), clock).word)
        assertEquals(ReadState.READING, TestsBuilder.view("ECM", ObdTestsData(ReadOutcome.READING), clock).state)
    }

    @Test
    fun aMonitorThatGaveNothingSaysWhyUnderItsTitle() {
        val v = view("06 00" to "46 00 C0 00 00 00", "06 01" to "46 01 05 10 00 3A 00 00 00 78", "06 02" to "7F 06 31")
        assertEquals(ReadState.READ, v.state)
        assertEquals("06 00 → 2 монитора · 1 тест · 12:34:56", v.line)
        val refused = v.groups.single { it.mid == "02" }
        assertTrue(refused.rows.isEmpty())
        assertEquals("не отдаёт", refused.word)
        assertEquals(listOf("06 02 → 7F 06 31"), refused.raw)
        assertEquals(3, refused.lines)
        // one monitor alone stands in one column
        val one = view("06 00" to "46 00 80 00 00 00", "06 01" to "46 01 05 10 00 3A 00 00 00 78")
        assertEquals(1, one.columns.size)
        assertEquals("06 00 → 1 монитор · 1 тест · 12:34:56", one.line)
    }

    @Test
    fun anAnswerOfThreeTestsBreaksAfterTheSecond() {
        val v = view(
            "06 00" to "46 00 80 00 00 00",
            "06 01" to "46 01 05 10 00 3A 00 00 00 78 01 06 10 00 40 00 00 00 78 01 07 0B 00 3E 00 00 01 90",
        )
        val g = v.groups.single()
        assertEquals(
            listOf(
                "06 01 → 46 01 05 10 00 3A 00 00 00 78 01 06 10 00 40 00 00 00 78",
                "           01 07 0B 00 3E 00 00 01 90",
            ),
            g.raw,
        )
        assertEquals(listOf("05", "06", "07"), g.rows.map { it.tid })
        assertEquals(6, g.lines)
    }

    @Test
    fun theOutMonitorGoesFirstAndTheColumnsBalance() {
        val v = view(
            "06 00" to "46 00 E0 00 00 00",
            "06 01" to "46 01 05 10 00 3A 00 00 00 78",
            "06 02" to "46 02 05 10 00 3A 00 00 00 78",
            "06 03" to "46 03 05 10 00 FF 00 00 00 78",
        )
        assertEquals(listOf("03", "01", "02"), v.groups.map { it.mid })
        assertEquals(listOf(listOf("03", "01"), listOf("02")), v.columns.map { c -> c.map { it.mid } })
        assertEquals(1, v.outOfNorm)
        assertEquals(2, v.passed)
        assertEquals(0, v.notRun)
    }
}
