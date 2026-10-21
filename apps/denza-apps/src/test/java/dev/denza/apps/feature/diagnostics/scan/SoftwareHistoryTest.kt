package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.FakeDiagnosticBus
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.scan.Fixtures.carNow
import dev.denza.apps.feature.diagnostics.scan.Fixtures.session
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The code history of architecture 5.5: what the scanner remembers of each block's F194 and F195 and of
 * each module behind a parent, starting from the catalog's snapshot of 2026-10-18, and the row
 * «ПО обновлено» an update leaves in «Идентификация».
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SoftwareHistoryTest {
    private val bms = SoftwareKey(0x7E7)
    private val massage = SoftwareKey(0x750, 0xF8C4)
    private val snapshot = "4A520011E00D00047B"
    private val newer = "4A520011E00D00048C"
    private val day = LocalDate.of(2026, 11, 12)

    @Test
    fun aKeyNeverReadIsTheCatalogsSnapshot() {
        val h = SoftwareHistory()
        assertEquals(SoftwareSeen(snapshot, null, LocalDate.of(2026, 10, 18)), h.record(bms)!!.seen)
        assertEquals(EcuCatalog.SNAPSHOT_DATE, h.record(bms)!!.seen.since)
        assertEquals("47380011DFE3000102", h.record(massage)!!.seen.code)
        // A block named by a stock route has no code in the catalog: nothing until it is read.
        assertNull(h.record(SoftwareKey(0x726)))
        assertNull(h.lastChange(bms))
        // The snapshot read again teaches nothing, so nothing is written down.
        assertSame(h, h.seen(SoftwareRead(bms, snapshot, null), day))
    }

    @Test
    fun aChangeIsRecordedOnceWithTheDayItWasRead() {
        val h = SoftwareHistory().seen(SoftwareRead(bms, newer, null), day)
        assertEquals(SoftwareChange(snapshot, null, newer, null, day), h.lastChange(bms))
        assertEquals(SoftwareSeen(newer, null, day), h.record(bms)!!.seen)
        // Read again the next days: no second change, the first keeps its day.
        val later = h.seen(SoftwareRead(bms, newer, null), day.plusDays(1)).seen(SoftwareRead(bms, newer, null), day.plusDays(9))
        assertSame(h, later)
        assertEquals(1, later.records.getValue(bms).changes.size)
        assertEquals(day, later.lastChange(bms)!!.detected)
    }

    @Test
    fun aVersionFillsInAndAChangedVersionIsAChange() {
        // The autoscan reads F194 only; the opened block adds F195 to the same code: no change.
        val h = SoftwareHistory().seen(SoftwareRead(bms, snapshot, "87F71A011402"), day)
        assertNull(h.lastChange(bms))
        assertEquals(SoftwareSeen(snapshot, "87F71A011402", EcuCatalog.SNAPSHOT_DATE), h.record(bms)!!.seen)
        // A read without the version says nothing about it.
        assertSame(h, h.seen(SoftwareRead(bms, snapshot, null), day.plusDays(1)))
        // An update: the old version is the remembered one.
        val updated = h.seen(SoftwareRead(bms, newer, null), day.plusDays(2))
        assertEquals(SoftwareChange(snapshot, "87F71A011402", newer, null, day.plusDays(2)), updated.lastChange(bms))
        // Its version comes with the next opening, without another change.
        val filled = updated.seen(SoftwareRead(bms, newer, "87F71A0B0C03"), day.plusDays(3))
        assertEquals(1, filled.records.getValue(bms).changes.size)
        assertEquals("87F71A0B0C03", filled.record(bms)!!.seen.version)
        // The same code with another version is a change of its own.
        val reflashed = filled.seen(SoftwareRead(bms, newer, "87F71A0B0C04"), day.plusDays(4))
        assertEquals(SoftwareChange(newer, "87F71A0B0C03", newer, "87F71A0B0C04", day.plusDays(4)), reflashed.lastChange(bms))
        assertEquals(2, reflashed.records.getValue(bms).changes.size)
    }

    @Test
    fun aKeyWithoutASnapshotStartsWhereItIsFirstRead() {
        val zcu = SoftwareKey(0x726)
        val h = SoftwareHistory().seen(SoftwareRead(zcu, "4B310011E001540100", null), day)
        assertEquals(SoftwareSeen("4B310011E001540100", null, day), h.record(zcu)!!.seen)
        assertNull(h.lastChange(zcu))
    }

    @Test
    fun onlyAGoodReadIsASoftwareRead() {
        val bmsEcu = EcuCatalog.byRequest(0x7E7)!!
        fun scan(answer: String?): ModuleResult = runBlocking {
            val bus = FakeDiagnosticBus()
            answer?.let { bus.on(0x7E7, "22F194").answer(it) }
            ModuleResult(bmsEcu, ModuleProcedure.read(bmsEcu, { Fixtures.EPOCH }) { bus.exchange(it) })
        }
        assertEquals(listOf(SoftwareRead(bms, newer, null)), SoftwareReads.of(scan("62F194$newer")))
        // A zero placeholder, a short code, a refusal, another DID, nothing at all: no reading.
        listOf("62F194000000000000000000", "62F1944A520011E00D", "7F2231", "62F195$newer", "62F1", null).forEach {
            assertEquals("$it", emptyList<SoftwareRead>(), SoftwareReads.of(scan(it)))
        }
        // A stock route's autoscan has no F194 to learn from.
        val zcu = EcuCatalog.byRequest(0x726)!!
        val bus = FakeDiagnosticBus()
        val r = runBlocking { ModuleResult(zcu, ModuleProcedure.read(zcu, { Fixtures.EPOCH }) { bus.exchange(it) }) }
        assertEquals(emptyList<SoftwareRead>(), SoftwareReads.of(r))
    }

    @Test
    fun anOpenedBlockGivesItsCodeVersionAndTheModulesBehindIt() {
        val ducu = EcuCatalog.byRequest(0x750)!!
        val bus = FakeDiagnosticBus()
        bus.on(0x750, "22F194").answer("62F1944B310011DF016A0002")
        bus.on(0x750, "22F195").answer("62F195000000000000")
        bus.on(0x750, "22F8C4").answer("62F8C447380011DFE3000199")
        val ident = runBlocking { IdentResult(ducu, IdentProcedure.read(ducu, { Fixtures.EPOCH }) { bus.exchange(it) }) }
        // An all-zero F195 is no version.
        assertEquals(listOf(SoftwareRead(SoftwareKey(0x750), "4B310011DF016A0002", null), SoftwareRead(massage, "47380011DFE3000199", null)),
            SoftwareReads.of(ident))

        // A child that is not a code teaches nothing.
        val zero = FakeDiagnosticBus()
        zero.on(0x750, "22F194").answer("62F1944B310011DF016A0001")
        zero.on(0x750, "22F195").answer("62F195AB12")
        zero.on(0x750, "22F8C4").answer("62F8C4000000000000000000")
        val z = runBlocking { IdentResult(ducu, IdentProcedure.read(ducu, { Fixtures.EPOCH }) { zero.exchange(it) }) }
        assertEquals(listOf(SoftwareRead(SoftwareKey(0x750), "4B310011DF016A0001", "AB12")), SoftwareReads.of(z))
    }

    @Test
    fun theRowSaysTheDayAndWhatWasThereVersionFirst() {
        // The version is kept as the bytes came and shown as the car's updater reads it.
        val withVersion = ModelBuilder.updateField(SoftwareChange(snapshot, "87F71A011402", newer, null, day))
        assertEquals("ПО обновлено · 12.11.2026", withVersion.key)
        assertEquals("был 3.48.07 · 1A011402 · 4A520011E00D00047B", withVersion.value)
        // A version not of that shape stays as it came.
        assertEquals("был AB12 · 4A520011E00D00047B", ModelBuilder.updateField(SoftwareChange(snapshot, "AB12", newer, null, day)).value)
        assertEquals(false, withVersion.muted)
        assertEquals("был 4A520011E00D00047B", ModelBuilder.updateField(SoftwareChange(snapshot, null, newer, null, day)).value)
    }

    // ------------------------------------------------------------------ the session

    private fun ModuleView.rows() = ident.own.map { it.key to it.value }

    /**
     * [answer] to every `22 F194` of the BMS in a session: «машина сейчас» answers the recorded code
     * after a test's own steps, and a session asks the autoscan's gate, the opening's and a rescan's.
     */
    private fun FakeDiagnosticBus.bms(answer: String) = repeat(8) { on(0x7E7, "22F194").answer(answer) }

    @Test
    fun theCarAsItIsLearnsNothingAndWritesNothing() = runTest {
        val software = MemorySoftwareStore()
        val s = session(carNow(), software = software)
        s.start()
        runCurrent()
        assertEquals(ScanPhase.DONE, s.model.value.scan.phase)
        // Every code the car answered on 2026-10-20 is the catalog's: no change, no file.
        assertEquals(0, software.saves)
        assertTrue(s.model.value.modules.values.none { m -> m.ident.own.any { it.key.startsWith("ПО обновлено") } })
        s.stop()
    }

    @Test
    fun anUpdatedBlockShowsItsRowUntilTheNextChange() = runTest {
        val software = MemorySoftwareStore()
        val bus = carNow {
            bms("62F194$newer")
            on(0x7E7, "22F195").answer("62F19587F71A0B0C03")
        }
        val s = session(bus, software = software)
        s.start()
        runCurrent()
        // The autoscan read the new code: the block's memory was read, and the change is written down once.
        assertEquals(ModuleStatus.OK, s.model.value.groups.flatMap { it.rows }.first { it.request == 0x7E7 }.status)
        assertEquals(1, software.saves)
        assertEquals(SoftwareChange(snapshot, null, newer, null, LocalDate.of(2026, 10, 20)), software.history.lastChange(bms))

        // Opened: F195 fills the version in (one more write, no second change), the row stands under it.
        s.setModuleOpen(0x7E7, true)
        runCurrent()
        assertEquals(2, software.saves)
        assertEquals(1, software.history.records.getValue(bms).changes.size)
        val rows = s.model.value.modules.getValue(0x7E7).rows()
        val f195 = rows.indexOf("Версия ПО · 22 F195" to "3.48.07 · 1A0B0C03")
        assertTrue(rows.toString(), f195 > 0)
        assertEquals("ПО обновлено · 20.10.2026" to "был 4A520011E00D00047B", rows[f195 + 1])
        assertEquals("Номер ПО поставщика · 22 F194" to newer, rows[f195 - 1])
        s.stop()

        // Opened again a day later on the same software: the row is still there, nothing is written.
        val next = session(carNow {
            bms("62F194$newer")
            on(0x7E7, "22F195").answer("62F19587F71A0B0C03")
        }, software = software, epoch = Fixtures.EPOCH + 86_400_000L)
        next.start()
        runCurrent()
        assertEquals(2, software.saves)
        assertTrue(next.model.value.modules.getValue(0x7E7).rows().contains("ПО обновлено · 20.10.2026" to "был 4A520011E00D00047B"))

        // The next update replaces the row: the old version is the one the opening learned.
        next.rescan()
        runCurrent()
        assertEquals(2, software.saves)
        next.stop()
        val third = session(carNow { bms("62F1944A520011E00D00049D") }, software = software,
            epoch = Fixtures.EPOCH + 3 * 86_400_000L)
        third.start()
        runCurrent()
        assertEquals(3, software.saves)
        assertEquals(2, software.history.records.getValue(bms).changes.size)
        val row = third.model.value.modules.getValue(0x7E7).rows().single { it.first.startsWith("ПО обновлено") }
        assertEquals("ПО обновлено · 23.10.2026" to "был 3.48.07 · 1A0B0C03 · $newer", row)
        third.stop()
    }

    @Test
    fun aFailedReadRecordsNothing() = runTest {
        // The BMS learned an update earlier; now it answers with nothing that is a code.
        val earlier = SoftwareHistory().seen(SoftwareRead(bms, newer, "87F71A0B0C03"), day)
        listOf<FakeDiagnosticBus.() -> Unit>(
            { bms("62F194000000000000000000") },
            { bms("7F2222") },
            { bms("62F1944A52") },
            { repeat(8) { on(0x7E7, "22F194").noReply(3) } },
        ).forEach { script ->
            val software = MemorySoftwareStore(earlier)
            val s = session(carNow(before = script), software = software)
            s.start()
            runCurrent()
            s.setModuleOpen(0x7E7, true)
            runCurrent()
            assertEquals(0, software.saves)
            assertSame(earlier, software.history)
            s.stop()
        }
    }

    @Test
    fun aStoredScanTeachesNothing() = runTest {
        // A scan read back from disk is older than the history: its codes could only undo a later change.
        val first = MemoryStore()
        val a = session(carNow { bms("62F194$newer") }, store = first)
        a.start()
        runCurrent()
        a.stop()
        val software = MemorySoftwareStore()
        val b = session(carNow(), store = MemoryStore(first.saved.single()), software = software,
            epoch = first.saved.single().finishedAtMillis + 60_000L)
        b.start()
        runCurrent()
        assertEquals(0, software.saves)
        b.stop()
    }

    @Test
    fun aModuleBehindItsParentHasItsRowUnderItsOwn() = runTest {
        val software = MemorySoftwareStore()
        val bus = carNow { on(0x750, "22F8C4").answer("62F8C447380011DFE3000199") }
        val s = session(bus, software = software)
        s.start()
        runCurrent()
        s.setModuleOpen(0x750, true)
        runCurrent()
        assertEquals(SoftwareChange("47380011DFE3000102", null, "47380011DFE3000199", null, LocalDate.of(2026, 10, 20)),
            software.history.lastChange(massage))
        val rows = s.model.value.modules.getValue(0x750).rows()
        val child = rows.indexOf("Массаж перед. левого · 22 F8C4" to "47380011DFE3000199")
        assertTrue(rows.toString(), child > 0)
        assertEquals("ПО обновлено · 20.10.2026" to "был 47380011DFE3000102", rows[child + 1])
        // The parent's own code did not change: no row of its own.
        assertEquals(1, rows.count { it.first.startsWith("ПО обновлено") })
        s.stop()
    }

    // ------------------------------------------------------------------ the file

    @Test
    fun theHistoryComesBackFromItsFile() {
        val h = SoftwareHistory()
            .seen(SoftwareRead(bms, snapshot, "87F71A011402"), day)
            .seen(SoftwareRead(bms, newer, null), day.plusDays(1))
            .seen(SoftwareRead(massage, "47380011DFE3000199", null), day.plusDays(1))
            .seen(SoftwareRead(SoftwareKey(0x726), "4B310011E001540100", null), day)
        assertEquals(h, SoftwareJson.decode(SoftwareJson.encode(h)))

        val dir = Files.createTempDirectory("diag-software").toFile()
        val store = FileSoftwareStore(File(dir, "diagnostics"))
        assertEquals(SoftwareHistory(), store.load())
        store.save(h)
        assertEquals(h, store.load())
        val file = File(dir, "diagnostics/${FileSoftwareStore.FILE}")
        assertTrue(file.readText().contains("\"key\": \"750/F8C4\""))

        // An entry that is not a code is left out; the rest stands.
        file.writeText(file.readText().replace("\"code\": \"4B310011E001540100\"", "\"code\": \"4B31\""))
        assertEquals(h.records - SoftwareKey(0x726), store.load().records)
        // A file cut short is no history: the catalog's snapshot again.
        file.writeText(file.readText().take(120))
        assertEquals(SoftwareHistory(), store.load())
        dir.deleteRecursively()
    }

    @Test
    fun aKeyIsItsAddressAndItsDid() {
        assertEquals("7E7", bms.id)
        assertEquals("750/F8C4", massage.id)
        assertEquals(bms, SoftwareKey.parse("7E7"))
        assertEquals(massage, SoftwareKey.parse("750/F8C4"))
        listOf("", "7E", "7E7/", "7E7/F8C", "XYZ", "7E7/F8C4/1").forEach { assertNull(it, SoftwareKey.parse(it)) }
    }

}
