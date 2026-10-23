package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.protocol.ObdTest
import dev.denza.apps.feature.diagnostics.protocol.hexBytes

/**
 * «Тесты» of an OBD block (architecture, 7.3; boards `scanner-ecu-tests`, `-ecu-tests-out`) out of what its
 * Mode 06 read gave ([ObdTestsData]). Every number comes again from the test's nine bytes - the words signed
 * where the scaling ID is 0x80 or above, scaled by J1979's table ([ObdTest.scale]) - never from the read's
 * words; a scaling ID outside the table leaves the block's own numbers, which are in one unit with each other,
 * so the scale still stands. A monitor with a test outside its limits goes first, the rest by MID; the
 * columns are balanced by lines, as the board balances them.
 */
internal object TestsBuilder {
    /** What a test outside J1979's scaling table is counted in. */
    const val RAW_UNIT = "ед. блока"
    private const val NONE = "—"
    /** Tests of one answer on a line of its exchange. */
    private const val PER_LINE = 2

    fun view(abbreviation: String, data: ObdTestsData, clock: ScanClock): TestsView {
        val maps = data.exchanges.mapNotNull(::line)
        fun quiet(state: ReadState, word: String, raw: List<String> = emptyList()) =
            TestsView(state, null, word, raw, emptyList(), 0, 0, 0)
        return when (data.outcome) {
            ReadOutcome.NOT_READ -> quiet(ReadState.NOT_READ, "не читали")
            ReadOutcome.READING -> quiet(ReadState.READING, "читаю…")
            ReadOutcome.REFUSED -> quiet(ReadState.REFUSED, listOfNotNull("$abbreviation не отдаёт тесты", data.exchanges.firstOrNull()?.answer).joinToString(" · "))
            ReadOutcome.NO_REPLY -> quiet(ReadState.NO_REPLY, data.exchanges.firstOrNull()?.noAnswer ?: "нет ответа")
            ReadOutcome.INCONSISTENT -> quiet(ReadState.INCONSISTENT, "ответ не сходится", maps)
            ReadOutcome.EMPTY -> quiet(ReadState.EMPTY, "блок не перечисляет тестов", maps)
            ReadOutcome.READ -> read(data, clock)
        }
    }

    private fun read(data: ObdTestsData, clock: ScanClock): TestsView {
        val groups = data.monitors.map(::group)
        val ordered = groups.filter { it.out } + groups.filterNot { it.out }
        val rows = groups.flatMap { it.rows }
        val at = (data.exchanges + data.monitors.mapNotNull { it.exchange }).maxOfOrNull { it.atMillis }
        val line = data.exchanges.joinToString(" · ") { it.request } + " → " +
            listOfNotNull(
                "${groups.size} ${Words.plural(groups.size, "монитор", "монитора", "мониторов")}",
                "${rows.size} ${Words.plural(rows.size, "тест", "теста", "тестов")}",
                at?.let(clock::seconds),
            ).joinToString(" · ")
        return TestsView(
            state = ReadState.READ,
            line = line,
            word = null,
            raw = emptyList(),
            columns = columns(ordered),
            outOfNorm = rows.count { it.pass == false },
            passed = rows.count { it.pass == true },
            notRun = rows.count { it.pass == null },
        )
    }

    private fun group(m: ObdMidData): TestsGroup {
        val rows = m.tests.mapNotNull(::row).sortedBy { it.tid }
        val word = when (m.outcome) {
            ReadOutcome.READ -> null
            ReadOutcome.EMPTY -> "тестов нет"
            ReadOutcome.REFUSED -> "не отдаёт"
            ReadOutcome.NO_REPLY -> m.exchange?.noAnswer ?: "нет ответа"
            ReadOutcome.INCONSISTENT -> "ответ не сходится"
            ReadOutcome.NOT_READ -> "не читали"
            ReadOutcome.READING -> "читаю…"
        }
        return TestsGroup(
            mid = m.mid,
            name = m.name,
            title = "${m.name.uppercase()} · MID ${m.mid}",
            rows = rows,
            word = word,
            raw = m.exchange?.let(::lines).orEmpty(),
        )
    }

    /** One test out of its nine bytes; null where they are not nine. */
    fun row(t: ObdTestData): TestRow? {
        val b = runCatching { hexBytes(t.raw.replace(" ", "")) }.getOrNull()?.map { it.toInt() and 0xFF } ?: return null
        if (b.size != 9) return null
        val test = ObdTest(b[0], b[1], b[2], b[3] shl 8 or b[4], b[5] shl 8 or b[6], b[7] shl 8 or b[8])
        val s = test.scale
        fun text(raw: Int): String = if (!test.ran) NONE else s?.let { Figures.number(test.word(raw) * it.scale + it.offset, it.decimals, signed = it.signed) }
            ?: Figures.number(test.word(raw).toDouble(), 0, signed = test.uasid >= 0x80)
        val v = test.word(test.value)
        val lo = test.word(test.min)
        val hi = test.word(test.max)
        val beyond = if (!test.ran || hi < lo) 0 else if (v < lo) -1 else if (v > hi) 1 else 0
        val position = when {
            !test.ran || hi < lo -> null
            hi == lo -> when (beyond) { -1 -> 0f; 1 -> 1f; else -> 0.5f }
            else -> ((v - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
        }
        return TestRow(
            tid = "%02X".format(test.tid),
            uasid = "%02X".format(test.uasid),
            value = text(test.value),
            unit = s?.unit ?: RAW_UNIT,
            min = text(test.min),
            max = text(test.max),
            pass = test.pass,
            position = position,
            beyond = beyond,
            raw = Figures.spaced(test.hex),
        )
    }

    /** «06 00 → 46 00 C0 00 00 01»; null for an exchange that had no answer. */
    private fun line(e: ExchangeData): String? = e.answer?.let { "${e.request} → $it" }

    /**
     * A monitor's exchange, [PER_LINE] tests a line: «06 A2 → 46 A2 0B 24 …», a further line indented under the
     * answer's first test. An answer that is not `46` and whole tests - a refusal - is one line.
     */
    private fun lines(e: ExchangeData): List<String> {
        val answer = e.answer ?: return emptyList()
        val bytes = answer.split(" ")
        if (bytes.size < 10 || (bytes.size - 1) % 9 != 0 || bytes[0] != "46") return listOf("${e.request} → $answer")
        val head = "${e.request} → ${bytes[0]} "
        return bytes.drop(1).chunked(9 * PER_LINE).mapIndexed { i, chunk ->
            (if (i == 0) head else " ".repeat(head.length)) + chunk.joinToString(" ")
        }
    }

    /**
     * Two columns: the split that leaves the taller of them the shortest, the first the taller where two splits
     * tie (`scanner.js` `ecuTests`). One monitor stands alone.
     */
    fun columns(groups: List<TestsGroup>): List<List<TestsGroup>> {
        val total = groups.sumOf { it.lines }
        var best = Int.MAX_VALUE
        var split = 0
        var left = 0
        for (k in 0..groups.size) {
            if (k > 0) left += groups[k - 1].lines
            val m = maxOf(left, total - left)
            if (m <= best) { best = m; split = k }
        }
        return listOf(groups.take(split), groups.drop(split)).filter { it.isNotEmpty() }
    }
}
