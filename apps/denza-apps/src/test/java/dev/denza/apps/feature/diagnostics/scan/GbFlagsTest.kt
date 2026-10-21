package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.DiagnosticExtraReader
import dev.denza.apps.feature.diagnostics.ExtraPage
import dev.denza.apps.feature.diagnostics.scan.Fixtures.carNow
import dev.denza.apps.feature.diagnostics.scan.Fixtures.session
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * «Тяговая батарея · Флаги GB/T 32960» (architecture 6, 7.2) against
 * research/vehicle-diagnostics/gb-alarm-dtc-boundary-20261021.json: every flag one of the stock
 * reporter's 14 inputs with its bit, device and FID; each code that raises a flag the reporter's own
 * with the simulation off, every other code read «норма», a failed read «н/д»; the count; the GETs only
 * in the flags' batch; the boards `scanner-sys-pack-flags` and `-flags-alarm` what the builder makes
 * of their codes; and the parked sweep of 2026-10-20, where the machine has it, the parked board.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GbFlagsTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "research/vehicle-diagnostics").exists() }

    private val inputs: List<JSONObject> by lazy {
        val a = JSONObject(File(root, "research/vehicle-diagnostics/gb-alarm-dtc-boundary-20261021.json").readText()).getJSONArray("alarmInputs")
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun ints(o: JSONObject, key: String): Set<Int> = o.getJSONArray(key).let { a -> (0 until a.length()).map(a::getInt).toSet() }

    /** The batch's answer as the shell prints it: one word a GET, through the same decoding as the car's. */
    private fun read(words: Map<DiagnosticExtra, Int>, fresh: Boolean = true): CarReadings {
        val output = words.entries.joinToString("\n") { (e, w) -> "@@extra:${e.key}\nResult: Parcel(00000000 %08x   '........')".format(w) }
        return CarReadings(extras = DiagnosticExtraReader.decode(output, 1, 1).values, extrasFresh = fresh)
    }

    private fun flag(view: GbFlagsView, bit: Int): GbFlagView = view.groups.flatMap { it.flags }.single { it.bit == bit }

    // ------------------------------------------------------------------ the reporter's inputs

    @Test
    fun everyFlagIsOneOfTheReportersInputsWithItsBitDeviceAndFid() {
        assertEquals(14, inputs.size)
        assertEquals(inputs.map { it.getInt("bit") }.toSet(), GbFlags.ALL.map { it.bit }.toSet())
        assertEquals("one flag a bit", 14, GbFlags.ALL.size)
        GbFlags.ALL.forEach { f ->
            val r = inputs.single { it.getInt("bit") == f.bit }
            assertEquals("bit ${f.bit}", r.getString("fid").toLong(16).toInt(), f.extra.fid)
            assertEquals("bit ${f.bit}", r.getInt("device"), f.extra.device)
            assertEquals("bit ${f.bit}", 1039, f.extra.device)
            assertEquals("bit ${f.bit}: an integer GET", 5, f.extra.transact)
            assertEquals("bit ${f.bit}", setOf(ExtraPage.GB_FLAGS), f.extra.pages)
            assertEquals("bit ${f.bit}: the codes the reporter raises it for", ints(r, "triggerRawValuesSimulationOff"), f.raising)
        }
        // the page's GETs are the flags', one each
        assertEquals(DiagnosticExtra.entries.filter { ExtraPage.GB_FLAGS in it.pages }, GbFlags.ALL.map { it.extra }.sortedBy { it.ordinal })
        // the standard's bit order, within each group and down the GETs
        assertEquals(GbFlags.PACK.map { it.bit }.sorted(), GbFlags.PACK.map { it.bit })
        assertEquals(GbFlags.DRIVE.map { it.bit }.sorted(), GbFlags.DRIVE.map { it.bit })
        assertEquals(GbFlags.ALL.sortedBy { it.bit }.map { it.extra }, DiagnosticExtra.entries.filter { ExtraPage.GB_FLAGS in it.pages })
    }

    // ------------------------------------------------------------------ the rule

    @Test
    fun eachCodeThatRaisesAFlagRaisesItAndEveryOtherCodeReadIsNorm() {
        GbFlags.ALL.forEach { f ->
            (0..254).forEach { code ->
                val v = flag(GbFlags.view(read(mapOf(f.extra to code))), f.bit)
                val raised = code in f.raising
                assertEquals("bit ${f.bit} code $code", if (raised) GbFlagState.ALARM else GbFlagState.OK, v.state)
                assertEquals("bit ${f.bit} code $code", if (raised) "тревога" else "норма", v.word)
                assertEquals("bit ${f.bit} code $code", code, v.raw)
            }
        }
        // what raises which, spelled out: the pack's high temperature takes 1 and 2, the DC-DC's
        // temperature and the interlock 1, the rest 2
        assertEquals(setOf(1, 2), GbFlags.ALL.single { it.bit == 1 }.raising)
        assertEquals(listOf(12, 16), GbFlags.ALL.filter { it.raising == setOf(1) }.map { it.bit })
        assertEquals(11, GbFlags.ALL.count { it.raising == setOf(2) })
    }

    @Test
    fun aFailedReadIsNoData() {
        GbFlags.ALL.forEach { f ->
            // the no-data words, 65535, a byte's no-data code and anything else outside 0..254
            listOf(-10011, -10013, 65535, 255, 256, -1).forEach { word ->
                assertNull("bit ${f.bit} word $word", f.extra.decode(word))
                val v = flag(GbFlags.view(read(mapOf(f.extra to word))), f.bit)
                assertEquals("bit ${f.bit} word $word", GbFlagView(f.bit, "%08X".format(f.extra.fid), f.name, null, GbFlagState.NONE, "н/д"), v)
            }
            // nothing in the batch, and a batch past its horizon
            assertEquals(GbFlagState.NONE, flag(GbFlags.view(CarReadings()), f.bit).state)
            assertEquals(GbFlagState.NONE, flag(GbFlags.view(read(mapOf(f.extra to f.raising.first()), fresh = false)), f.bit).state)
        }
    }

    @Test
    fun theCountIsTheFlagsRaisedLitWhenAnyIs() {
        val all = GbFlags.ALL
        val calm = GbFlags.view(read(all.associate { it.extra to 0 }))
        assertEquals(listOf(HeadCounter("ТРЕВОГ", "0", on = false)), calm.counters)
        val two = GbFlags.view(read(all.associate { it.extra to 0 } + (DiagnosticExtra.GB_HVIL to 1) + (DiagnosticExtra.GB_PACK_HOT to 2)))
        assertEquals(listOf(HeadCounter("ТРЕВОГ", "2", on = true)), two.counters)
        // a flag not read is not counted, raised or not; nothing read at all is «н/д», not zero
        val one = GbFlags.view(read(mapOf(DiagnosticExtra.GB_HVIL to 1, DiagnosticExtra.GB_TEMP_DIFF to 65535)))
        assertEquals(listOf(HeadCounter("ТРЕВОГ", "1", on = true)), one.counters)
        assertEquals(listOf(HeadCounter("ТРЕВОГ", "н/д", on = false)), GbFlags.view(CarReadings()).counters)
        assertEquals("ОБЩИЕ ТРЕВОГИ GB/T 32960.3 · 14", calm.head)
    }

    // ------------------------------------------------------------------ the batch

    @Test
    fun onlyTheFlagsBatchCarriesTheFlagsGets() {
        val shared = DiagnosticExtraReader.command
        val flags = DiagnosticExtraReader.command(setOf(ExtraPage.GB_FLAGS))
        val cabin = DiagnosticExtraReader.command(setOf(ExtraPage.CABIN))
        GbFlags.ALL.forEach { f ->
            val get = "echo @@extra:${f.extra.key}; service call autoservice 5 i32 1039 i32 ${f.extra.fid}"
            assertFalse(f.extra.key, shared.contains("@@extra:${f.extra.key};"))
            assertFalse(f.extra.key, cabin.contains("@@extra:${f.extra.key};"))
            assertTrue(f.extra.key, flags.contains(get))
        }
        // the shared batch is what it was: every GET without a page, and nothing else
        assertEquals(DiagnosticExtraReader.command(emptySet()), shared)
        assertEquals(DiagnosticExtra.entries.filter { it.pages.isEmpty() }.size, shared.split("; echo ").size)
        assertTrue(flags.startsWith(shared))
        assertEquals(DiagnosticExtra.entries.count { it.pages.isEmpty() } + 14, flags.split("; echo ").size)
        assertTrue(flags.contains("service call autoservice 5 i32 1039 i32 608174142"))
    }

    @Test
    fun theTabAsksForTheFlagsGetsThroughTheCarFeed() = runTest {
        val car = FakeCar()
        val s = session(carNow(), car = car)
        s.start()
        runCurrent()
        s.setExtrasVisible(ExtraPage.GB_FLAGS, true)
        s.setExtrasVisible(ExtraPage.GB_FLAGS, false)
        assertEquals(listOf(ExtraPage.GB_FLAGS to true, ExtraPage.GB_FLAGS to false), car.pageLog)
        s.stop()
    }

    @Test
    fun theSessionsModelCarriesTheFlagsOfItsReadings() = runTest {
        val car = FakeCar(FakeCar.parked().copy(extras = mapOf(DiagnosticExtra.GB_HVIL to 1.0, DiagnosticExtra.GB_DCDC_STATE to 1.0), extrasFresh = true))
        val s = session(carNow(), car = car)
        s.start()
        runCurrent()
        val v = s.model.value.systems.gbFlags
        assertEquals(GbFlagState.ALARM, flag(v, 16).state)
        assertEquals(GbFlagState.OK, flag(v, 14).state)
        assertEquals(GbFlagState.NONE, flag(v, 0).state)
        assertEquals(HeadCounter("ТРЕВОГ", "1", on = true), v.counters.single())
        s.stop()
    }

    // ------------------------------------------------------------------ the boards

    /** The codes a board's tiles show; a tile without one was not read. */
    @Suppress("UNCHECKED_CAST")
    private fun boardCodes(id: String): Map<DiagnosticExtra, Int> =
        (SpecJson.fixture(id)["groups"] as List<Map<String, Any?>>).flatMap { it["flags"] as List<Map<String, Any?>> }
            .mapNotNull { f -> (f["raw"] as Number?)?.let { GbFlags.ALL.single { g -> g.bit == (f["bit"] as Number).toInt() }.extra to it.toInt() } }
            .toMap()

    @Suppress("UNCHECKED_CAST")
    private fun check(id: String, view: GbFlagsView) {
        val f = SpecJson.fixture(id)
        assertEquals("$id: the head", f["head"], view.head)
        val counters = (f["counters"] as List<List<Any?>>).map { HeadCounter(it[0] as String, it[1] as String, on = it.getOrNull(2) == true) }
        assertEquals("$id: the counters", counters, view.counters)
        val groups = (f["groups"] as List<Map<String, Any?>>).map { g ->
            GbFlagGroup(
                g["title"] as String,
                (g["flags"] as List<Map<String, Any?>>).map { t ->
                    GbFlagView(
                        (t["bit"] as Number).toInt(), t["fid"] as String, t["name"] as String, (t["raw"] as Number?)?.toInt(),
                        GbFlagState.valueOf((t["state"] as String).uppercase()), t["word"] as String,
                    )
                },
            )
        }
        assertEquals("$id: the groups", groups, view.groups)
    }

    @Test
    fun theParkedBoardIsWhatTheBuilderMakesOfItsCodes() {
        val codes = boardCodes("scanner-sys-pack-flags")
        assertEquals("every flag read", 14, codes.size)
        check("scanner-sys-pack-flags", GbFlags.view(read(codes)))
    }

    @Test
    fun theAlarmBoardIsWhatTheBuilderMakesOfItsCodes() {
        val codes = boardCodes("scanner-sys-pack-flags-alarm")
        // the parked sweep with the interlock open
        assertEquals(boardCodes("scanner-sys-pack-flags") + (DiagnosticExtra.GB_HVIL to 1), codes)
        check("scanner-sys-pack-flags-alarm", GbFlags.view(read(codes)))
    }

    /**
     * The catalog sweep of 2026-10-20 (`captures/diagnostics-sweep-20261020/fids.tsv`, git-ignored: the
     * checkout's own, or that of the checkout a worktree stands in): both passes read every flag as the
     * parked board shows it. Skipped where the machine has no sweep.
     */
    @Test
    fun theSweepOf20261020IsTheParkedBoard() {
        val tsv = generateSequence(root) { it.parentFile }.map { File(it, "captures/diagnostics-sweep-20261020/fids.tsv") }.firstOrNull { it.isFile }
        assumeTrue("no sweep of 2026-10-20 in captures/", tsv != null)
        // FID <pass> <ns> <device> <fid> <int word> <float>
        val rows = tsv!!.readLines().map { it.split('\t') }.filter { it[0] == "FID" && it[3] == "1039" }
        val board = boardCodes("scanner-sys-pack-flags")
        listOf("1", "2").forEach { pass ->
            val words = GbFlags.ALL.associate { f ->
                val row = rows.single { it[1] == pass && it[4].removePrefix("0x").toLong(16).toInt() == f.extra.fid }
                f.extra to row[5].removePrefix("0x").toLong(16).toInt()
            }
            assertEquals("pass $pass", board, words)
            check("scanner-sys-pack-flags", GbFlags.view(read(words)))
        }
    }
}
