package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.hex
import dev.denza.apps.feature.diagnostics.protocol.hexBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The board `scanner-sys-engine-graphs` against [GraphsBuilder] through [ModelBuilder]: ECM's stream with the
 * board's four PIDs as its focus, answered for 70 s at the rate that reads each of them 8,3 times a second;
 * the lanes' names, sources, units, decimals and norm bands, the line saying how they are read and the
 * footer's window are what the board shows. The values the board draws are illustrative, the engine's
 * fixtures here; their scales are the lanes' own rule (not narrower than the norm or 20 × the resolution).
 */
@Suppress("UNCHECKED_CAST")
class GraphsBoardContractTest {
    @Test
    fun fourPidsOnOneAxisAreWhatTheBoardShows() {
        val f = SpecJson.fixture("scanner-sys-engine-graphs")
        val signals = f["signals"] as List<Map<String, Any?>>
        val pids = signals.map { (it["pid"] as String).toInt(16) }
        val keys = pids.map { ParamKey.Pid(0x7E0, it) }
        val stream = LiveStream(0x7E0, pids + listOf(0x05, 0x0D, 0x0F), carRows = true)
        stream.setFocus(pids)
        val graphs = GraphTraces(GraphScope.Block(0x7E0), keys)
        // two focus requests and a table one a round: 8,3 rounds a second
        val step = (1e9 / (3 * 8.3)).toLong()
        var t = 1_000_000_000L
        repeat((70 * 3 * 8.3).toInt()) {
            val ask = stream.next()!!
            val asked = hex(ask.uds).drop(2).chunked(2)
            val answer = "41" + asked.joinToString("") { p -> p + Fixtures.ECM_DATA.getValue(p.toInt(16)) }
            stream.accept(DiagReply.Answer(ask, hexBytes(answer), 1, 0, 18, t))
            if (it % 30 == 0) graphs.take(stream.view(true, false, true).rows)
            t += step
        }
        graphs.take(stream.view(true, false, true).rows)
        val scan = BoardScan.results(BoardScan.records("scanner-summary"))
        val m = BoardScan.model(BoardScan.snapshot(scan, streams = mapOf(0x7E0 to stream), live = 0x7E0, graphs = graphs.snapshot()))
        val view = m.modules.getValue(0x7E0).obd!!.live.graphs!!

        assertEquals(((f["block"] as Map<String, Any?>)["line"] as String).removePrefix("7E0 → 7E8 · Mode 01 · "), view.line)
        val footer = f["footer"] as Map<String, Any?>
        val notes = footer["notes"] as List<Any?>
        assertEquals((notes.last() as List<Any?>)[0], view.footnote)
        assertEquals(GraphScope.Block(0x7E0), view.scope)
        assertEquals(signals.map { it["name"] }, view.lanes.map { it.name })
        signals.zip(view.lanes).forEach { (s, lane) ->
            val name = s["name"] as String
            assertEquals(name, "PID ${s["pid"]} · ${s["abbr"]}", lane.source)
            assertEquals(name, s["unit"], lane.unit)
            assertEquals(name, (s["decimals"] as Number).toInt(), lane.decimals)
            assertEquals(name, s["signed"] == true, lane.signed)
            val norm = (s["norm"] as List<Number>?)?.let { it[0].toFloat()..it[1].toFloat() }
            assertEquals(name, norm, lane.band)
            // five minutes kept, the scale not narrower than the norm or 20 × the resolution
            assertTrue(name, lane.samples.first().atNanos <= lane.samples.last().atNanos - 60_000_000_000L)
            assertTrue(name, lane.scale.endInclusive - lane.scale.start >= 20 * Math.pow(10.0, -lane.decimals.toDouble()) - 1e-6)
            norm?.let { assertTrue(name, lane.scale.start <= it.start && lane.scale.endInclusive >= it.endInclusive) }
        }
    }

    @Test
    fun aMarkIsOnTheCarsClockAndTheGraphsGoWithTheirTab() {
        val g = GraphTraces(GraphScope.System(CarSystem.PACK), listOf(ParamKey.Signal("CELL_MIN_MV")))
        g.mark(5_000_000_000L)
        assertEquals(listOf(5_000_000_000L), g.snapshot().marks)
        assertEquals(GraphScope.System(CarSystem.PACK), g.snapshot().scope)
    }
}
