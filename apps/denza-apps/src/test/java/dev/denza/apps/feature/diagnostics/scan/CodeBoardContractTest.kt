package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.DiagRequest
import dev.denza.apps.feature.diagnostics.protocol.hexBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The boards `scanner-code` and `-code-plain` against [CodeBuilder] through [ModelBuilder]: the boards' scan,
 * their journal ([BoardJournal]), the block read again by hand at the board's time, and - for the plain code -
 * its `19 04` / `19 06` answered with the code and no record, as the BMS answered `19 04` on 2026-10-21. The
 * head, the meaning and the block it names, the status byte, how it was read with the record lit, the same
 * failure type elsewhere, the life on the journal's axis and the reads' sections are what the boards derive
 * from the same data (scanner.js `codeScreen`). The decoded snapshot of `scanner-code` is illustrative - no
 * DID of LA_BCM is decoded - and stays out of it.
 */
@Suppress("UNCHECKED_CAST")
class CodeBoardContractTest {
    private val records = BoardScan.records("scanner-summary")

    /** The board's reads as `readLine` draws them: a long answer cut to its head and the record, which is lit. */
    private fun line(ask: String, answer: String, code: String): Pair<String, String?> {
        val bytes = answer.split(" ")
        val rec = BoardScan.dtcHex(code).chunked(2)
        var at = -1
        for (i in 2 until bytes.size - 3) if (at < 0 && bytes.subList(i, i + 3) == rec) at = i
        if (at < 0) return "$ask → $answer" to null
        val before = bytes.take(minOf(3, at)).joinToString(" ") + if (at > 3) " · … · " else " "
        val lit = bytes.subList(at, at + 4).joinToString(" ")
        return "$ask → $before$lit${if (at + 4 < bytes.size) " · …" else ""}" to lit
    }

    private fun check(id: String, request: Int, readAt: Long, codeReads: Map<Int, Map<String, CodeReadsData>> = emptyMap(), extra: List<JournalScan> = emptyList()) {
        val f = SpecJson.fixture(id)
        val code = f["code"] as String
        val scan = BoardScan.results(records)
        val results = scan + (request to BoardScan.again(scan.getValue(request), readAt))
        val journal = BoardJournal.data(scan, extra)
        val m = BoardScan.model(BoardScan.snapshot(results, journal = journal, now = readAt, openCode = request to code, codeReads = codeReads))
        val v = m.code!!
        val block = f["block"] as Map<String, Any?>
        val sae = f["sae"] as Map<String, Any?>
        val meaning = f["meaning"] as Map<String, Any?>
        val status = (f["status"] as Number).toInt()
        val availability = (f["availability"] as Number).toInt()
        val tl = f["timeline"] as Map<String, Any?>
        val seen = tl["seen"] as List<String>
        val since = (tl["scans"] as List<String>).filter { BoardScan.at(it) >= BoardScan.at(seen[0]) }

        assertEquals(code, v.code)
        assertEquals("· ${block["abbr"]} ${block["addr"]} · ${block["name"]}", v.aside)
        assertEquals((listOf(sae["standard"]) + (sae["parts"] as List<String>)).joinToString(" · "), v.line)
        assertEquals(listOf(HeadCounter("СТАТУС", "0x%02X".format(status)), HeadCounter("ВПЕРВЫЕ", seen[0].take(5)),
            HeadCounter("В СКАНАХ", "${seen.size} из ${since.size}")), v.counters)
        assertEquals(meaning["title"], v.meaning)
        assertEquals(meaning["note"], v.meaningNote)

        // the status byte from bit 7 to 0, in words, with what the block does not report
        val bin = Integer.toBinaryString(status).padStart(8, '0')
        assertEquals("СТАТУС · 0x%02X = %s %s".format(status, bin.take(4), bin.drop(4)), v.status.head)
        assertEquals(status, v.status.byte)
        assertEquals(availability, v.status.availability)
        assertEquals("подтверждён · сейчас не активен", v.status.words)
        assertEquals("«·» — блок этот бит не сообщает · доступность 0x%02X".format(availability), v.status.legend)

        // how it was read: the board's requests and answers, the record lit
        val reads = (f["reads"] as List<Map<String, Any?>>).map { line(it["ask"] as String, it["answer"] as String, code) }
        assertEquals(reads.map { it.first }, v.raw.map { it.text })
        assertEquals(reads.map { it.second }, v.raw.map { r -> r.lit?.let { r.text.substring(it) } })

        // the block a lost message names, and how it stands now
        val addressee = f["addressee"] as Map<String, Any?>?
        if (addressee == null) {
            assertNull(v.addressee)
        } else {
            val a = v.addressee!!
            assertEquals(addressee["label"], a.label)
            assertEquals(addressee["abbr"], a.abbreviation)
            assertEquals(addressee["addr"], a.address)
            assertEquals(addressee["name"], a.name)
            val n = (addressee["records"] as Number).toInt()
            assertEquals(listOf(if (addressee["answers"] == true) "сейчас отвечает" else "сейчас не отвечает",
                if (n > 0) "записей $n" else "записей нет", "${(addressee["ms"] as Number).toInt()} мс").joinToString(" · "), a.state)
        }

        // the same failure type elsewhere
        val ftb = code.substringAfter('-')
        assertEquals("ТОТ ЖЕ ТИП ОТКАЗА · $ftb", v.sameTypeTitle)
        val same = f["same"] as List<Map<String, Any?>>
        assertEquals(same.map { listOf(it["code"], "${it["block"]} ${it["addr"]}", "с ${it["since"]}") },
            v.sameType.map { listOf(it.code, it.block, it.since) })
        if (same.isEmpty()) {
            assertNull(v.sameTypeNote)
        } else {
            val n = same.size + 1
            val blocks = (same.map { it["block"] } + block["abbr"]).distinct().size
            assertEquals("$n ${Words.plural(n, "код", "кода", "кодов")} потери связи в $blocks ${if (blocks == 1) "блоке" else "блоках"}", v.sameTypeNote)
        }

        // its life on the journal's days
        val t = v.timeline!!
        assertEquals(tl["days"], t.days)
        assertEquals(seen.size, t.seen.size)
        val scans = Words.plural(since.size, "скана", "сканов", "сканов")
        assertEquals("впервые ${seen[0]} · " + if (seen.size == since.size) "в каждом из ${since.size} $scans" else "в ${seen.size} из ${since.size} $scans", t.line)

        val footer = f["footer"] as Map<String, Any?>
        assertEquals((footer["notes"] as List<List<Any?>>).single()[0], v.footnote)
        val snapshot = f["snapshot"] as Map<String, Any?>
        if (snapshot["ask"] != null) {
            assertEquals("СТОП-КАДР · 19 04", v.snapshot.title)
            assertEquals(ReadState.EMPTY, v.snapshot.state)
            assertEquals("записи нет", v.snapshot.word)
            assertEquals(listOf("${snapshot["ask"]} → ${snapshot["answer"]}"), v.snapshot.raw)
            val counts = f["counts"] as Map<String, Any?>
            assertEquals("СЧЁТЧИКИ · 19 06", v.extended.title)
            assertEquals("записей нет", v.extended.word)
            assertEquals(listOf("${counts["ask"]} → ${counts["answer"]}"), v.extended.raw)
        }
    }

    @Test
    fun aLostMessageReadAgainIsWhatItsBoardMakesOfIt() {
        val again = BoardScan.at("23.10 09:12:40")
        // the reads of LA_BCM: alone on 18.10, the seven scans, and alone again at 09:12
        val read = JournalEntries.scan(JournalScanKind.MODULE, listOf(BoardScan.again(BoardScan.results(records).getValue(0x720), again)),
            emptyMap(), CarReadings(), BoardScan.at("23.10 09:12"))
        check("scanner-code", 0x720, again, extra = listOf(read))
    }

    @Test
    fun aMakersCodeWithoutAMeaningIsWhatItsBoardMakesOfIt() {
        val at = BoardScan.at("23.10 08:50:31")
        fun answer(uds: String, back: String) = DiagReply.Answer(DiagRequest(0x747, hexBytes(uds)), hexBytes(back), 1, at, 20, 0)
        // 19 04 and 19 06 answered with the code and its status and no record (the BMS's 19 04 of 2026-10-21)
        val reads = CodeReadResult("C1170-09", 0x517009, ModuleRead(0x747, at, listOf(
            answer("1904517009FF", "5904517009" + "08"), answer("1906517009FF", "5906517009" + "08"),
        ))).data
        check("scanner-code-plain", 0x747, at, codeReads = mapOf(0x747 to mapOf("C1170-09" to reads)))
    }
}
