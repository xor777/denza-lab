package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.diagnostics.scan.Fixtures.carNow
import dev.denza.apps.feature.diagnostics.scan.Fixtures.session
import dev.denza.apps.feature.diagnostics.ui.moduleTabs
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The board `scanner-ecu-ident-updated` against the session: the BMS whose code the history knew with
 * its version, updated, read by the autoscan on the board's day and then opened - every row of its
 * «Идентификация», its tone, the cache's side, the head's line and the tabs are what the model says.
 * The debug fixture draws the board's words; this test is what makes them the engine's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SoftwareBoardContractTest {
    @Suppress("UNCHECKED_CAST")
    private fun rows(f: Map<String, Any?>, key: String): List<Field> = (f[key] as List<*>).map {
        val r = it as List<*>
        Field(r[0] as String, r[1] as String, muted = r[2] == "dim")
    }

    @Test
    fun theUpdatedBlocksBoardIsWhatTheSessionShows() = runTest {
        val board = SpecJson.fixture("scanner-ecu-ident-updated")
        val own = rows(board, "own")
        val code = own.single { it.key == "Номер ПО поставщика · 22 F194" }.value
        // The board shows F195 as the car reads it, «3.48.07 · 1A0B0C03»: back to the bytes the block sends.
        val (number, rest) = own.single { it.key == "Версия ПО · 22 F195" }.value.split(" · ")
        val version = "%04X".format(number.replace(".", "").toInt()) + rest
        // Before the update the history knew the snapshot's code with the version an opening read.
        val software = MemorySoftwareStore(
            SoftwareHistory().seen(SoftwareRead(SoftwareKey(0x7E7), "4A520011E00D00047B", "87F71A011402"), LocalDate.of(2026, 10, 20)),
        )
        val bus = carNow(epoch = BOARD_DAY) {
            repeat(4) { on(0x7E7, "22F194").answer("62F194$code") }
            on(0x7E7, "22F195").answer("62F195$version")
            on(0x7E7, "22F197").answer("7F2231")
        }
        val s = session(bus, software = software, epoch = BOARD_DAY)
        s.start()
        runCurrent()
        s.setModuleOpen(0x7E7, true)
        runCurrent()
        val module = s.model.value.modules.getValue(0x7E7)

        assertEquals(own, module.ident.own)
        assertEquals(rows(board, "cache"), module.ident.cache)
        assertEquals(emptyList<String>(), module.ident.pids)
        val block = board["block"] as Map<*, *>
        assertEquals(block["line"], module.line)
        assertEquals(block["title"], module.name)
        assertEquals((block["dtc"] as List<*>)[1], module.dtc.records.size.toString())
        assertEquals(ModuleStatus.OK, module.status)
        val tabs = board["tabs"] as Map<*, *>
        assertEquals(tabs["items"], moduleTabs(module))
        s.stop()
    }

    companion object {
        /** The board's day: 12.11.2026, its clock 10:14:05. */
        private val BOARD_DAY: Long = Instant.parse("2026-11-12T10:14:05Z").toEpochMilli()
    }
}
