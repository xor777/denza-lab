package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.DiagRequest
import dev.denza.apps.feature.diagnostics.protocol.hexBytes
import dev.denza.apps.feature.diagnostics.ui.moduleTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The boards `scanner-ecu-tests` and `-ecu-tests-out` against [TestsBuilder] through [ModelBuilder]: the boards'
 * maps and each monitor's answer go to the session's own [ObdReadProcedure.tests] as the ECM's replies, and what
 * the model makes of them is held, line by line, to what the board draws (scanner.js `ecuTests`): the order of
 * the monitors and of their tests, the two columns, every value, limit and unit - the fixture's words against
 * the model's scaling of the bytes - which test is out and the side it left by, where its mark stands, the
 * exchanges, the line over them, «ВНЕ НОРМЫ», and the tabs. The values are illustrative; the bytes are whole.
 */
@Suppress("UNCHECKED_CAST")
class TestsBoardContractTest {
    private val ecm = 0x7E0

    /** One test as the board reads it from its nine bytes (`monitorTests`). */
    private data class Drawn(val tid: String, val value: String, val min: String, val max: String, val unit: String, val pass: Boolean?, val pos: Float?, val beyond: Int)

    private fun drawn(m: Map<String, Any?>): List<Drawn> {
        val b = (m["answer"] as String).split(" ").map { it.toInt(16) }
        val words = m["tests"] as List<List<String>>
        return words.indices.map { i ->
            val o = 1 + 9 * i
            val ua = b[o + 2]
            fun w(k: Int): Int = ((b[o + 3 + 2 * k] shl 8) or b[o + 4 + 2 * k]).let { if (ua >= 0x80 && it >= 0x8000) it - 0x10000 else it }
            val (v, lo, hi) = Triple(w(0), w(1), w(2))
            val ran = v != 0 || lo != 0 || hi != 0
            val beyond = if (!ran || hi < lo) 0 else if (v < lo) -1 else if (v > hi) 1 else 0
            val pos = when {
                !ran || hi < lo -> null
                hi == lo -> if (beyond < 0) 0f else if (beyond > 0) 1f else 0.5f
                else -> ((v - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
            }
            val (value, min, max, unit) = words[i]
            Drawn("%02X".format(b[o + 1]), value, min, max, unit, if (ran) hi >= lo && beyond == 0 else null, pos, beyond)
        }.sortedBy { it.tid }
    }

    /** A monitor's exchange as `testLines` breaks it. */
    private fun lines(ask: String, answer: String): List<String> {
        val bytes = answer.split(" ")
        val head = "$ask → ${bytes[0]} "
        return bytes.drop(1).chunked(18).mapIndexed { i, c -> (if (i == 0) head else " ".repeat(head.length)) + c.joinToString(" ") }
    }

    private fun check(id: String) {
        val f = SpecJson.fixture(id)
        val t = f["tests"] as Map<String, Any?>
        val maps = t["maps"] as List<List<String>>
        val monitors = t["monitors"] as List<Map<String, Any?>>
        val at = BoardScan.at("20.10 " + t["at"])
        fun answer(ask: String, back: String) = DiagReply.Answer(DiagRequest(ecm, hexBytes(ask)), hexBytes(back), 1, at)
        val replies = maps.map { answer(it[0], it[1]) } + monitors.map { answer("06 " + it["mid"], it["answer"] as String) }
        val read = ObdReadProcedure.tests(ModuleRead(ecm, at, replies))
        // every exchange the board shows is one the procedure would make, in its order
        assertEquals(maps.map { it[0] }, read.exchanges.map { it.request })
        val scan = BoardScan.results(BoardScan.records("scanner-summary"))
        val m = BoardScan.model(BoardScan.snapshot(scan, now = at, obdReads = mapOf(ecm to (FreezeFrameData() to read))))
        val module = m.modules.getValue(ecm)
        val v = module.tests!!

        // the board's monitors: one with a test out first, the rest by MID
        val groups = monitors.map { mo ->
            val tests = drawn(mo)
            Triple(mo, tests, tests.any { it.pass == false })
        }.let { g -> g.filter { it.third } + g.filterNot { it.third } }
        assertEquals(groups.map { it.first["mid"] }, v.groups.map { it.mid })

        // two columns: the split that leaves the taller the shortest, the first the taller on a tie
        val weights = groups.map { 1 + it.second.size + lines("06 " + it.first["mid"], it.first["answer"] as String).size }
        var best = Int.MAX_VALUE
        var split = 0
        var left = 0
        for (k in 0..weights.size) {
            if (k > 0) left += weights[k - 1]
            val worst = maxOf(left, weights.sum() - left)
            if (worst <= best) { best = worst; split = k }
        }
        assertEquals(listOf(groups.take(split), groups.drop(split)).filter { it.isNotEmpty() }.map { c -> c.map { it.first["mid"] } },
            v.columns.map { c -> c.map { it.mid } })

        groups.zip(v.groups).forEach { (g, view) ->
            val (mo, tests) = g
            assertEquals(mo["name"], view.name)
            assertEquals("${mo["name"]} · MID ${mo["mid"]}".uppercase(), view.title)
            assertNull(view.word)
            assertEquals(lines("06 " + mo["mid"], mo["answer"] as String), view.raw)
            assertEquals(tests.map { it.tid }, view.rows.map { it.tid })
            tests.zip(view.rows).forEach { (d, r) ->
                val where = "MID ${mo["mid"]} TID ${d.tid}"
                assertEquals(where, listOf(d.value, d.min, d.max, d.unit), listOf(r.value, r.min, r.max, r.unit))
                assertEquals(where, d.pass, r.pass)
                assertEquals(where, d.beyond, r.beyond)
                if (d.pos == null) assertNull(where, r.position) else assertEquals(where, d.pos, r.position!!, 1e-6f)
            }
        }

        // the line over the table: what was asked, then the monitors, the tests and the read's time
        val all = groups.flatMap { it.second }
        assertEquals(maps.joinToString(" · ") { it[0] } + " → ${groups.size} мониторов · ${all.size} " +
            Words.plural(all.size, "тест", "теста", "тестов") + " · " + t["at"], v.line)
        val out = all.count { it.pass == false }
        assertEquals(out, v.outOfNorm)
        assertEquals(all.count { it.pass == true }, v.passed)
        assertEquals(all.count { it.pass == null }, v.notRun)
        assertEquals(ReadState.READ, v.state)

        // the tabs: «Тесты» after «DTC и готовность», the board's active one
        val tabs = f["tabs"] as Map<String, Any?>
        assertEquals(tabs["items"], moduleTabs(module))
        assertEquals("Тесты", (tabs["items"] as List<String>)[(tabs["active"] as Number).toInt()])
    }

    @Test
    fun theUsualPictureIsWhatItsBoardMakesOfIt() = check("scanner-ecu-tests")

    @Test
    fun aCatalystBelowItsMinimumIsWhatItsBoardMakesOfIt() = check("scanner-ecu-tests-out")

    /** The other ECM boards carry the same four tabs; a block without Mode 06 keeps its three. */
    @Test
    fun theEcmsOtherBoardsCarryTheSameTabs() {
        listOf("scanner-ecu-dtc", "scanner-ecu-fault", "scanner-ecu-live", "scanner-ecu-ident").forEach { id ->
            assertEquals(id, listOf("DTC и готовность", "Тесты", "Потоковые данные", "Идентификация"),
                (SpecJson.fixture(id)["tabs"] as Map<String, Any?>)["items"])
        }
        val scan = BoardScan.results(BoardScan.records("scanner-summary"))
        val m = BoardScan.model(BoardScan.snapshot(scan))
        assertEquals(listOf("DTC и готовность", "Тесты", "Потоковые данные", "Идентификация"), moduleTabs(m.modules.getValue(ecm)))
        assertEquals(ReadState.NOT_READ, m.modules.getValue(ecm).tests!!.state)
        assertEquals(listOf("DTC и готовность", "Потоковые данные", "Идентификация"), moduleTabs(m.modules.getValue(0x7E5)))
        assertNull(m.modules.getValue(0x7E5).tests)
    }
}
