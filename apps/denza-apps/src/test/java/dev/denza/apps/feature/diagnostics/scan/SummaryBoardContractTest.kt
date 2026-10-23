package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boards `scanner-summary`, `-summary-active` and `-blocks` against [ModelBuilder]: the scan the boards
 * draw ([BoardScan]) and the journal that read LA_BCM on 18.10 and R_ZCU at 15:42 before it go in; the rail,
 * the head, the verdict, the scan's line, the four values, the band, the columns of records with their words
 * and dates, the blocks without records, the table of blocks with its groups and filters come out as the
 * boards derive them from the same data (scanner.js). The board's median exchange, 18 ms, is the real scan's,
 * whose exchanges this one gives each block's median; the median is checked as this scan's own.
 */
@Suppress("UNCHECKED_CAST")
class SummaryBoardContractTest {
    private fun board(id: String) = SpecJson.fixture(id)

    /** LA_BCM alone on 18.10 15:42, R_ZCU alone at 15:42, then the scan of 19:31 itself. */
    private fun journal(results: Map<Int, ModuleResult>): JournalData {
        fun alone(at: String, request: Int) = JournalEntries.scan(JournalScanKind.MODULE, listOf(results.getValue(request)), emptyMap(), CarReadings(), BoardScan.at(at))
        val full = JournalEntries.scan(JournalScanKind.FULL, EcuCatalog.routed.map { results.getValue(it.request!!) }, emptyMap(), BoardScan.car(), BoardScan.FINISHED)
        return JournalData().with(alone("18.10 15:42", 0x720)).with(alone("20.10 15:42", 0x747)).with(full)
    }

    private fun model(id: String, lv: Double = 13.7): Pair<DiagnosticsModel, Map<Int, ModuleResult>> {
        val results = BoardScan.results(BoardScan.records(id), availability = mapOf(0x597 to 0x29, 0x747 to 0x0D))
        return BoardScan.model(BoardScan.snapshot(results, car = BoardScan.car(lv = lv), journal = journal(results))) to results
    }

    private val rank = mapOf("active" to 0, "pending" to 1, "hist" to 2)
    private fun kind(status: Int) = if (status and 1 != 0) "active" else if (status and 4 != 0) "pending" else "hist"
    private fun mark(kind: String) = when (kind) { "active" -> Mark.ACTIVE; "pending" -> Mark.PENDING; else -> Mark.HISTORY }
    private fun plural(n: Int, one: String, few: String, many: String) = Words.plural(n, one, few, many)

    @Test
    fun theSummaryIsWhatTheBoardMakesOfItsScan() = summary("scanner-summary", 13.7)

    @Test
    fun theSummaryWithARecordNowAndOnePendingIsWhatItsBoardMakesOfIt() = summary("scanner-summary-active", 11.9)

    private fun summary(id: String, lv: Double) {
        val (m, results) = model(id, lv)
        val f = board(id)
        val sc = f["scan"] as Map<String, Any?>
        val systems = f["systems"] as List<Map<String, Any?>>
        val view = m.summary!!
        val codes = systems.flatMap { s -> (s["blocks"] as List<Map<String, Any?>>).flatMap { b -> b["codes"] as List<Map<String, Any?>> } }
        val kinds = codes.map { kind((it["status"] as Number).toInt()) }
        val act = kinds.count { it == "active" }

        // the verdict and the scan's line
        assertEquals(if (act > 0) "$act " + plural(act, "активная неисправность", "активные неисправности", "активных неисправностей")
            else "Активных неисправностей нет", view.verdict)
        assertEquals(if (act > 0) Mark.ACTIVE else null, view.verdictMark)
        assertEquals("скан ${sc["at"]} · ${(sc["answered"] as Number).toInt()} из ${(sc["blocks"] as Number).toInt()} блоков · маска ${sc["mask"]} · ${sc["seconds"]} с", view.line)

        // the four values: words, units, the orange of a value outside its norm
        val vitals = (f["vitals"] as List<Map<String, Any?>>).map { Vital(it["key"] as String, it["value"] as String, it["unit"] as String, it["out"] == true) }
        assertEquals(vitals, view.vitals)

        // the band: a part a kind present, the last with the blocks; the lost messages beside it
        val nBlocks = systems.sumOf { (it["blocks"] as List<*>).size }
        val parts = listOf("active" to "АКТИВНЫЕ", "pending" to "ОЖИДАЮЩИЕ", "hist" to "В ИСТОРИИ").filter { (k, _) -> kinds.count { it == k } > 0 }
        val texts = parts.mapIndexed { i, (k, word) ->
            val n = kinds.count { it == k }
            "$word · $n ${plural(n, "ЗАПИСЬ", "ЗАПИСИ", "ЗАПИСЕЙ")}" + if (i == parts.lastIndex) " · $nBlocks ${plural(nBlocks, "БЛОК", "БЛОКА", "БЛОКОВ")}" else ""
        }
        assertEquals(texts, view.band!!.parts.map { it.text })
        assertEquals(parts.map { mark(it.first) }, view.band!!.parts.map { it.mark })
        val net = codes.count { (it["code"] as String).let { c -> c.startsWith("U") && c.endsWith("-87") } }
        assertEquals("в т. ч. $net — потеря связи между блоками, U…-87", view.band!!.note)

        // the columns: most severe first, then the most records, then the catalog; each into the shorter
        fun bKinds(b: Map<String, Any?>) = (b["codes"] as List<Map<String, Any?>>).map { kind((it["status"] as Number).toInt()) }
        fun worst(k: List<String>) = k.minOf { rank.getValue(it) }
        fun <T> order(list: List<T>, kindsOf: (T) -> List<String>, n: (T) -> Int) =
            list.withIndex().sortedWith(compareBy({ worst(kindsOf(it.value)) }, { -n(it.value) }, { it.index })).map { it.value }
        val sorted = order(systems, { s -> (s["blocks"] as List<Map<String, Any?>>).flatMap(::bKinds) },
            { s -> (s["blocks"] as List<Map<String, Any?>>).sumOf { (it["codes"] as List<*>).size } })
        val cols = listOf(ArrayList<Map<String, Any?>>(), ArrayList())
        val rows = intArrayOf(0, 0)
        sorted.forEach { s ->
            val k = if (rows[1] < rows[0]) 1 else 0
            cols[k] += s
            rows[k] += 1 + (s["blocks"] as List<Map<String, Any?>>).sumOf { 1 + (it["codes"] as List<*>).size }
        }
        assertEquals(cols.map { c -> c.map { s -> "${(s["name"] as String).uppercase()} · ${(s["blocks"] as List<Map<String, Any?>>).sumOf { (it["codes"] as List<*>).size }}" } },
            view.columns.map { c -> c.map { it.title } })
        cols.flatten().zip(view.columns.flatten()).forEach { (s, shown) ->
            val blocks = order(s["blocks"] as List<Map<String, Any?>>, ::bKinds) { (it["codes"] as List<*>).size }
            assertEquals(blocks.map { it["abbr"] }, shown.blocks.map { it.abbreviation })
            blocks.zip(shown.blocks).forEach { (b, block) ->
                val id = b["abbr"] as String
                assertEquals(id, b["addr"], block.address)
                assertEquals(id, b["name"], block.name)
                assertEquals(id, "с ${b["since"]}", block.since)
                assertEquals(id, mark(worst(bKinds(b)).let { r -> rank.entries.first { it.value == r }.key }), block.mark)
                val rec = b["codes"] as List<Map<String, Any?>>
                assertEquals(id, rec.map { it["code"] }, block.records.map { it.code })
                assertEquals(id, rec.map { (it["meaning"] ?: it["type"]) as String }, block.records.map { it.words })
                assertEquals(id, rec.map { it["meaning"] != null }, block.records.map { it.meaning })
                assertEquals(id, rec.map { (it["code"] as String).let { c -> c.startsWith("U") && c.endsWith("-87") } }, block.records.map { it.network })
                assertEquals(id, rec.map { kind((it["status"] as Number).toInt()).takeIf { k -> k != "hist" }?.let(::mark) }, block.records.map { it.mark })
                assertTrue(block.records.none { it.fresh })
            }
        }

        // the blocks with nothing to say, and the footer
        val quiet = (sc["blocks"] as Number).toInt() - nBlocks
        assertEquals("$quiet ${plural(quiet, "блок", "блока", "блоков")} без записей · все ответили", view.rest)
        val median = m.header.latency!!.removeSuffix(" мс")
        val note = ((f["footer"] as Map<String, Any?>)["notes"] as List<List<Any?>>).single()[0] as String
        assertEquals(note.replace(Regex("медиана \\d+ мс"), "медиана $median мс"), view.footnote)
        assertEquals(JournalEntries.medianMs(results.values.flatMap { it.read.replies }).toString(), median)

        // the head and the rail
        val top = f["top"] as Map<String, Any?>
        assertEquals(top["car"], m.header.car)
        assertEquals(top["odo"], m.header.odometer)
        assertEquals(top["mode"], m.header.mode)
        assertEquals(top["volts"], m.header.volts)
        assertEquals(top["voltsOut"] == true, m.header.voltsOut)
        rail(f["rail"] as Map<String, Any?>, m.railLines)
    }

    /** The rail of [rail] - names, the worst kind and the sum of records, «Блоки»' count - as the model's lines. */
    private fun rail(rail: Map<String, Any?>, lines: List<RailLine>) {
        val items = (rail["items"] as List<Any?>).filterIsInstance<Map<String, Any?>>()
        assertEquals(items.map { it["name"] }, lines.map { it.name })
        items.zip(lines).forEach { (it, line) ->
            val name = it["name"] as String
            val counts = listOf("active", "pending", "history").associateWith { k -> (it[k] as? Number)?.toInt() ?: 0 }
            val worst = listOf("active" to Mark.ACTIVE, "pending" to Mark.PENDING, "history" to Mark.HISTORY).firstOrNull { (k, _) -> counts.getValue(k) > 0 }?.second
            assertEquals(name, worst?.let { m -> MarkCount(m, counts.values.sum()) }, line.mark)
            assertEquals(name, (it["count"] as? Number)?.toInt()?.toString(), line.plain)
            assertTrue(name, !line.plainLit)
        }
    }

    @Test
    fun theTableOfBlocksIsWhatTheBoardMakesOfItsScan() {
        val (m, _) = model("scanner-summary")
        val f = board("scanner-blocks")
        val sc = f["scan"] as Map<String, Any?>
        val view = m.blocks!!
        val records = BoardScan.records("scanner-summary")
        val blocks = (f["groups"] as List<Map<String, Any?>>).flatMap { it["ecus"] as List<Map<String, Any?>> }
        fun n(b: Map<String, Any?>) = records[(b["req"] as String).toInt(16)].orEmpty().size

        assertEquals("· ${blocks.size} · все ответили", view.aside)
        assertEquals("скан ${sc["at"]} · ${(sc["exchanges"] as Number).toInt()} обменов · ${sc["seconds"]} с · маска ${sc["mask"]}", view.line)
        val withRec = blocks.count { n(it) > 0 }
        assertEquals(listOf("Все · ${blocks.size}", "С записями · $withRec", "Без ответа · 0", "ПО изменено · 0"), view.filters.map { it.second })
        assertEquals(listOf("С ЗАПИСЯМИ · $withRec", "БЕЗ ЗАПИСЕЙ · ${blocks.size - withRec}"), view.groups.map { it.title })

        // those with records most severe and most first, then the rest in the catalog's order
        val first = blocks.withIndex().filter { n(it.value) > 0 }.sortedWith(compareBy({ -n(it.value) }, { it.index })).map { it.value }
        val rest = blocks.filter { n(it) == 0 }
        val rows = view.groups.flatMap { it.rows }
        (first + rest).zip(rows).forEach { (b, row) ->
            val id = b["abbr"] as String
            assertEquals(id, row.abbreviation)
            assertEquals(id, b["name"], row.name)
            val req = b["req"] as String
            assertEquals(id, "$req → %03X".format(req.toInt(16) + 8), row.route)
            assertEquals(id, b["proto"], row.protocol)
            assertEquals(id, n(b).takeIf { it > 0 }?.toString() ?: "—", row.records)
            assertEquals(id, (b["f194"] as String?)?.chunked(4)?.joinToString(" ") ?: "—", row.software)
            assertEquals(id, "${(b["ms"] as Number).toInt()} мс", row.latency)
            assertEquals(id, if (n(b) > 0) Mark.HISTORY else null, row.mark)
        }
        assertEquals(blocks.size, rows.size)
        assertEquals(((f["footer"] as Map<String, Any?>)["notes"] as List<List<Any?>>).single()[0], view.footnote)
        rail(f["rail"] as Map<String, Any?>, m.railLines)
    }

    @Test
    fun aBlockThatDidNotAnswerLightsTheCountAndHasItsOwnGroup() {
        val records = BoardScan.records("scanner-summary")
        val results = BoardScan.results(records).toMutableMap()
        val silent = results.getValue(0x7B3)
        results[0x7B3] = ModuleResult(silent.ecu, ModuleRead(0x7B3, silent.read.atMillis, listOf(
            dev.denza.apps.feature.diagnostics.bus.DiagReply.NoReply(silent.read.replies.first().request, 3, silent.read.atMillis, 4000),
        )))
        val m = BoardScan.model(BoardScan.snapshot(results))
        val blocks = m.railLines.single { it.place == RailPlace.Blocks }
        assertEquals("67/68", blocks.plain)
        assertTrue(blocks.plainLit)
        assertEquals("НЕТ ОТВЕТА · 1", m.blocks!!.groups.last().title)
        assertEquals(Mark.NO_REPLY, m.blocks!!.groups.last().rows.single().mark)
        assertEquals("62 блока без записей · не ответили 1", m.summary!!.rest)
        assertTrue(m.summary!!.restAttention)
        // no journal: no dates
        assertNull(m.summary!!.columns.flatten().first().blocks.first().since)
    }
}
