package dev.denza.apps.feature.diagnostics.report

import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.scan.CarReadings
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsSession
import dev.denza.apps.feature.diagnostics.scan.FakeCar
import dev.denza.apps.feature.diagnostics.scan.Fixtures
import dev.denza.apps.feature.diagnostics.scan.Figures
import dev.denza.apps.feature.diagnostics.scan.Fixtures.carNow
import dev.denza.apps.feature.diagnostics.scan.MemoryStore
import dev.denza.apps.feature.diagnostics.scan.ModuleResult
import dev.denza.apps.feature.diagnostics.scan.ScanJson
import dev.denza.apps.feature.diagnostics.scan.SoftwareHistory
import dev.denza.apps.feature.diagnostics.scan.StoredScan
import dev.denza.apps.feature.diagnostics.scan.StreamSinks
import dev.denza.apps.feature.vehicle.VehicleSignal
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Отчёт» (architecture, 7.4) on «машина сейчас»: what it says, how it falls on pages, the JSON, the
 * names, and the save as the session runs it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportTest {
    /** Every character the same width, a mono one a little wider: enough to make the layout's arithmetic visible. */
    private val measure = TextMeasure { text, style -> text.length * style.size * if (style.mono) 0.6f else 0.55f }

    private val car = CarReadings(
        hub = mapOf(
            VehicleSignal.GEARBOX_PARK to 1.0, VehicleSignal.VEHICLE_SPEED to 0.0, VehicleSignal.ODOMETER_KM to 13199.7,
            VehicleSignal.CELL_MIN_MV to 3333.0, VehicleSignal.CELL_MAX_MV to 3337.0,
        ),
        sweep = 1,
        extras = mapOf(
            DiagnosticExtra.SOH to 99.0, DiagnosticExtra.SOC to 74.0, DiagnosticExtra.LV to 13.7,
            DiagnosticExtra.INSULATION to 14400.0, DiagnosticExtra.INSULATION_2 to 19700.0,
            DiagnosticExtra.TEMP_MIN to 13.0, DiagnosticExtra.TEMP_MAX to 18.0,
        ),
        extrasFresh = true,
    )

    private class Scanned(val model: DiagnosticsModel, val scan: StoredScan)

    /** The autoscan of «машина сейчас», finished and saved, with the model it left. */
    private fun scanned(writer: ReportWriter = ReportWriter.NONE): Pair<Scanned, DiagnosticsSession> = TestScope().run {
        val store = MemoryStore()
        val s = DiagnosticsSession(
            buses = { carNow() },
            store = store,
            car = FakeCar(car),
            sinks = StreamSinks { null },
            wallClock = { Fixtures.EPOCH + testScheduler.currentTime },
            zone = ZoneOffset.UTC,
            context = backgroundScope.coroutineContext,
            reports = writer,
        )
        s.start()
        testScheduler.runCurrent()
        val result = Scanned(s.model.value, store.saved.single())
        s.stop()
        result to s
    }

    private fun input(journal: ReportJournal? = null, reads: ReportReads? = null, recording: ReportRecording? = null): Pair<ReportInput, Scanned> {
        val (scanned, _) = scanned()
        val results = scanned.scan.reads.associate { it.request to ModuleResult(EcuCatalog.byRequest(it.request)!!, it) }
        val input = ReportInputs.of(
            scanned.model, results, scanned.scan.finishedAtMillis, car, SoftwareHistory(),
            Fixtures.EPOCH + 60_000, ZoneOffset.UTC, recording, journal, reads,
        )
        return input to scanned
    }

    // ------------------------------------------------------------------ content

    @Test
    fun theInputSaysWhatTheScanAndTheCarSay() {
        val (input, scanned) = input()
        val results = scanned.scan.reads.associate { it.request to ModuleResult(EcuCatalog.byRequest(it.request)!!, it) }
        val s = input.summary
        assertEquals(results.values.count { it.answered }, s.answered)
        assertEquals(EcuCatalog.all.size, s.catalogued)
        assertEquals(scanned.model.scan.active, s.active)
        assertEquals(scanned.model.scan.history, s.history)
        // Every record of the scan is a row, in the autoscan's order, its block named.
        val records = scanned.model.modules.values.sumOf { it.dtc.records.size }
        assertEquals(records, input.codes.size)
        assertTrue(records > 0)
        assertEquals(input.codes.map { it.request }.distinct().size, s.blocksWithRecords)
        val lbcm = input.codes.filter { it.address == "720" }
        assertEquals(8, lbcm.size)
        assertTrue(lbcm.all { it.block == "LA_BCM" && it.kind == ReportCodeKind.HISTORY })
        // Every catalogued block, with the scan's software code where it gave one.
        assertEquals(EcuCatalog.all.size, input.blocks.size)
        assertEquals("4B310011E0019A0002", input.blocks.first { it.address == "720" }.f194)
        assertEquals("история 8", input.blocks.first { it.address == "720" }.status)
        // The car: no VIN read, the odometer, P, 12 V, the pack.
        assertNull(input.vin)
        assertEquals(13199.7, input.odometerKm!!, 0.0)
        assertEquals(true, input.parked)
        assertEquals(4.0, input.pack.cellSpreadMv!!, 0.0)
        // The exchanges are the scan's, and the services in the order first asked.
        assertEquals(scanned.scan.reads.sumOf { it.replies.size }, input.exchanges.count)
        assertEquals(listOf("22 F194", "22 F190"), input.exchanges.services.take(2))
        assertEquals("OBD 01/03/07/09/0A", input.exchanges.services.last())
    }

    @Test
    fun theScansTimeRunsFromTheFirstAskToTheLastAnswer() {
        val (scanned, _) = scanned()
        var t = Fixtures.EPOCH
        val timed = scanned.scan.reads.associate { read ->
            read.request to ModuleResult(EcuCatalog.byRequest(read.request)!!, read.copy(replies = read.replies.map { r ->
                t += 100
                when (r) {
                    is DiagReply.Answer -> DiagReply.Answer(r.request, r.uds, r.frames, t, 20)
                    is DiagReply.NoReply -> DiagReply.NoReply(r.request, r.gatewayResult, t, 20)
                    is DiagReply.GatewayBusy -> DiagReply.GatewayBusy(r.request, t, 20)
                }
            }))
        }
        val input = ReportInputs.of(scanned.model, timed, scanned.scan.finishedAtMillis, car, SoftwareHistory(), t, ZoneOffset.UTC)
        val n = input.exchanges.count
        assertEquals((n - 1) * 100L + 20, input.exchanges.durationMs)
        assertTrue(ReportDocuments.footer(input), ReportDocuments.footer(input).endsWith("за ${Figures.number(((n - 1) * 100 + 20) / 1000.0, 1)} с"))
    }

    @Test
    fun theModelCarriesTheReportOnceThereIsAScan() {
        val (scanned, _) = scanned()
        val report = scanned.model.report
        val input = report.input!!
        assertEquals(ReportOutcome.Idle, report.outcome)
        assertEquals(ReportSection.entries, report.options.map { it.section })
        val codes = report.options.first { it.section == ReportSection.CODES }
        assertTrue(codes.available && codes.byDefault)
        assertEquals("${ReportInputs.count(input.codes.size, "запись", "записи", "записей")} · " +
            ReportInputs.count(input.summary.blocksWithRecords, "блок", "блока", "блоков"), codes.detail)
        assertEquals(EcuCatalog.all.size.toString(), report.options.first { it.section == ReportSection.BLOCKS }.detail)
        // Nothing recorded: the recording is there to see, not to tick.
        val stream = report.options.first { it.section == ReportSection.STREAM }
        assertFalse(stream.available || stream.byDefault)
    }

    @Test
    fun optionsNameWhatEachSectionHolds() {
        val (input, _) = input(recording = ReportRecording("/x/stream-20261020-193512.csv", 42_000, 4))
        val o = ReportInputs.options(input).associateBy { it.section }
        assertEquals("SOH · ячейки · изоляция · датчики", o.getValue(ReportSection.PACK).detail)
        assertEquals("00:42 · 4 сигнала", o.getValue(ReportSection.STREAM).detail)
        assertTrue(o.getValue(ReportSection.STREAM).available)
        assertFalse(o.getValue(ReportSection.STREAM).byDefault)
        assertTrue(o.getValue(ReportSection.RAW).detail.endsWith("обменов") || o.getValue(ReportSection.RAW).detail.endsWith("обмена"))
    }

    @Test
    fun theSheetFollowsTheConceptsOrderAndLeavesOutWhatIsNotAsked() {
        val (input, _) = input()
        fun headings(sections: Set<ReportSection>) =
            ReportDocuments.of(input, sections).pieces.filterIsInstance<ReportPiece.Heading>().map { it.text.substringBefore(" ·") }
        assertEquals(listOf("ИТОГ", "КОДЫ", "ТЯГОВАЯ БАТАРЕЯ", "ДВИГАТЕЛЬ", "БЛОКИ И НОМЕРА ПО"), headings(ReportSection.entries.toSet()))
        assertEquals(listOf("ИТОГ", "ДВИГАТЕЛЬ"), headings(setOf(ReportSection.ENGINE, ReportSection.RAW)))

        val doc = ReportDocuments.of(input, ReportSection.entries.toSet())
        val head = doc.pieces.first() as ReportPiece.Head
        assertEquals("ОТЧЁТ О ДИАГНОСТИКЕ", head.title)
        assertEquals("Denza Z9 GT DM · VIN — · пробег 13 199,7 км", head.lines[0].first)
        assertTrue(head.lines[1].first, head.lines[1].first.matches(Regex("скан \\d\\d\\.\\d\\d\\.2026 \\d\\d:\\d\\d · стоянка P · 12 В 13,7 В · только чтение")))
        assertEquals("SOH 99 % · разброс ячеек 4 мВ (SOC 74 %) · изоляция 14,4 / 19,7 МОм · датчики 13…18 °C", ReportDocuments.pack(input.pack))
        val s = input.summary
        assertEquals(
            "${s.answered} из 68 блоков ответили · активных ${s.active} · ожидающих ${s.pending} · " +
                "в истории ${ReportInputs.count(s.history, "запись", "записи", "записей")} · блоков с записями ${s.blocksWithRecords}" +
                (if (s.memoriesRead < s.answered) " · память прочитана у ${s.memoriesRead}" else ""),
            ReportDocuments.summary(input),
        )
        assertTrue(doc.footer, doc.footer.matches(Regex("Запросы: 22 F194 · 22 F190 · 19 01 09 · .* · OBD 01/03/07/09/0A — \\d+ обмен(а|ов)?( за \\d+ мс| за \\d+,\\d с)?")))
        // The codes' table: the block on its first row only, no «впервые» without a journal.
        val codes = doc.pieces.filterIsInstance<ReportPiece.Table>().first()
        assertTrue(codes.columns.none { it.title == "ВПЕРВЫЕ" })
        val lbcm = codes.rows.dropWhile { it[0] != "LA_BCM 720" }.take(8)
        assertEquals(listOf("LA_BCM 720", "", "", "", "", "", "", ""), lbcm.map { it[0] })
        // Software codes in fours.
        val blocks = doc.pieces.filterIsInstance<ReportPiece.Table>().last()
        assertEquals("4B31 0011 E001 9A00 02", blocks.rows.first { it[0] == "LA_BCM" }[2])
    }

    @Test
    fun theJournalAndTheReadsAddWhatTheyKnow() {
        val (bare, _) = input()
        val first = bare.codes.first()
        val (input, _) = input(
            journal = ReportJournal(
                firstSeen = mapOf(ReportCodeKey(first.request, first.code) to LocalDate.of(2026, 10, 18)),
                fullChargeSpreadMv = 6.0,
                excerptJson = """{"scans": 3}""",
            ),
            reads = ReportReads(vin = "LGXCE4CB0R0000000", engineFreezeFrame = listOf(ReportField("Обороты", "1 250 об/мин"))),
        )
        assertEquals(LocalDate.of(2026, 10, 18), input.codes.first().firstSeen)
        val doc = ReportDocuments.of(input, ReportSection.entries.toSet())
        assertTrue((doc.pieces.first() as ReportPiece.Head).lines[0].first.contains("VIN LGXCE4CB0R0000000"))
        val codes = doc.pieces.filterIsInstance<ReportPiece.Table>().first()
        assertEquals("ВПЕРВЫЕ", codes.columns.last().title)
        assertEquals("18.10.26", codes.rows.first().last())
        assertEquals("—", codes.rows.last().last())
        assertTrue(ReportDocuments.pack(input.pack).contains("при полном заряде 6 мВ"))
        assertTrue(doc.pieces.any { it is ReportPiece.Heading && it.text == "СТОП-КАДР · MODE 02" })
        val json = JSONObject(ReportJson.encode(input, setOf(ReportSection.RAW), null))
        assertEquals(3, json.getJSONObject("journal").getInt("scans"))
        assertEquals("LGXCE4CB0R0000000", json.getString("vin"))
    }

    // ------------------------------------------------------------------ pages

    @Test
    fun pagesAreNumberedAndNothingRunsIntoTheFooter() {
        val (input, _) = input()
        val pages = ReportLayout(measure).lay(ReportDocuments.of(input, ReportSection.entries.toSet()))
        assertTrue("68 blocks and the codes take more than a page", pages.size > 1)
        pages.forEachIndexed { i, page ->
            assertEquals(i + 1, page.number)
            assertTrue(page.texts.contains("Стр. ${i + 1} из ${pages.size}"))
            val texts = page.inks.filterIsInstance<ReportInk.Text>()
            val footerRule = page.inks.filterIsInstance<ReportInk.Rule>().last().y
            texts.filter { it.style != ReportStyle.FOOT }.forEach { t ->
                assertTrue("${t.text} at ${t.baseline} under the footer's rule $footerRule", t.baseline < footerRule)
                assertTrue("${t.text} runs past the margin", t.x + measure.width(t.text, t.style) <= ReportSheet.MARGIN_X + ReportSheet.CONTENT + 0.01f)
            }
            assertTrue(texts.all { it.baseline <= ReportSheet.HEIGHT - ReportSheet.MARGIN_BOTTOM + 0.01f })
        }
        // A table that goes on repeats its titles; a heading is never the last line of a page.
        pages.drop(1).forEach { page ->
            val first = page.inks.filterIsInstance<ReportInk.Text>().first()
            assertTrue(first.text, first.style == ReportStyle.COLUMN || first.style == ReportStyle.HEADING)
        }
        pages.forEach { page ->
            val body = page.inks.filterIsInstance<ReportInk.Text>().filter { it.style != ReportStyle.FOOT }
            assertTrue(body.last().style != ReportStyle.HEADING)
        }
        // Only the codes and the blocks: fewer pages, the same footer.
        val shorter = ReportLayout(measure).lay(ReportDocuments.of(input, setOf(ReportSection.PACK)))
        assertEquals(1, shorter.size)
        assertTrue(shorter.single().texts.contains("Стр. 1 из 1"))
    }

    @Test
    fun aLongWordBreaksAndAShortLineStaysWhole() {
        val layout = ReportLayout(measure)
        val style = ReportStyle.BODY
        val width = 100f
        val lines = layout.wrap("короткая строка и " + "Ж".repeat(40), style, width)
        assertTrue(lines.all { measure.width(it, style) <= width })
        assertEquals("Ж".repeat(40), lines.drop(1).joinToString("").replace(" ", "").takeLast(40))
        assertEquals(listOf("SOH 99 %"), layout.wrap("SOH 99 %", style, width))
        assertEquals(listOf(""), layout.wrap("", style, width))
    }

    @Test
    fun aRowNeverSplitsAndWideColumnsShareWhatIsLeft() {
        val layout = ReportLayout(measure)
        val columns = listOf(ReportColumn("A", 80f, ReportStyle.CELL), ReportColumn("B", 0f, ReportStyle.CELL), ReportColumn("C", 60f, ReportStyle.CELL))
        val widths = layout.widths(columns)
        assertEquals(ReportSheet.CONTENT, widths.sum() + 2 * ReportSheet.GAP, 0.01f)
        val long = "слово ".repeat(60).trim()
        val rows = List(80) { listOf("$it", long, "x") }
        val pages = layout.lay(ReportDocument(listOf(ReportPiece.Table(columns, rows)), "подвал"))
        // A row's lines stay together: its number and the last line of its words are on one page.
        rows.indices.forEach { n ->
            val page = pages.first { p -> p.inks.any { it is ReportInk.Text && it.text == "$n" && it.x == ReportSheet.MARGIN_X } }
            val numberAt = page.inks.filterIsInstance<ReportInk.Text>().first { it.text == "$n" && it.x == ReportSheet.MARGIN_X }.baseline
            val next = page.inks.filterIsInstance<ReportInk.Text>().firstOrNull { it.text == "${n + 1}" && it.x == ReportSheet.MARGIN_X }?.baseline
            val words = page.inks.filterIsInstance<ReportInk.Text>().filter { it.x > ReportSheet.MARGIN_X + 80f && it.x < ReportSheet.MARGIN_X + 80f + widths[1] && it.baseline >= numberAt && (next == null || it.baseline < next) }
            assertEquals(layout.wrap(long, ReportStyle.CELL, widths[1]).size, words.size)
        }
    }

    // ------------------------------------------------------------------ files

    @Test
    fun namesAreTheScansMinuteAndNeverAnotherFilesName() {
        val at = ZonedDateTime.of(2026, 10, 20, 19, 31, 29, 0, ZoneOffset.UTC)
        assertEquals("scan-20261020-1931", ReportNames.base(at))
        val all = listOf(".pdf", ".json", ".csv")
        assertEquals("scan-20261020-1931", ReportNames.free("scan-20261020-1931", all, setOf("scan-20261020-1930.pdf")))
        assertEquals("scan-20261020-1931-2", ReportNames.free("scan-20261020-1931", all, setOf("scan-20261020-1931.json")))
        assertEquals("scan-20261020-1931-3", ReportNames.free("scan-20261020-1931", listOf(".pdf"), setOf("SCAN-20261020-1931.PDF", "scan-20261020-1931-2.pdf")))
    }

    @Test
    fun theJsonCarriesTheScanAsItCameAndTheSectionsAsked() {
        val (input, scanned) = input()
        val all = ReportSection.entries.toSet()
        val bundle = ReportBundles.of(input, all, scanned.scan)
        assertEquals(listOf(".pdf", ".json"), bundle.extensions)
        assertEquals(ReportNames.base(input.scanAt!!), bundle.base)
        val json = JSONObject(bundle.json!!)
        assertEquals(ReportJson.KIND, json.getString("kind"))
        assertEquals(JSONObject.NULL, json.get("vin"))
        assertEquals(input.codes.size, json.getJSONArray("codes").length())
        assertEquals(68, json.getJSONArray("blocks").length())
        assertEquals(input.exchanges.count, json.getJSONObject("exchanges").getInt("count"))
        // The scan in last-scan.json's own format: it reads back to the same replies.
        val back = ScanJson.decode(json.getJSONObject("scan").toString())!!
        assertEquals(scanned.scan.reads.sumOf { it.replies.size }, back.reads.sumOf { it.replies.size })
        assertFalse(json.has("journal"))
        // Without the codes and without «Сырые ответы»: no codes there, and no JSON at all.
        assertFalse(JSONObject(ReportJson.encode(input, setOf(ReportSection.RAW), scanned.scan)).has("codes"))
        assertNull(ReportBundles.of(input, setOf(ReportSection.CODES), scanned.scan).json)
        // The recording rides along only when asked and there.
        val recorded = input.copy(recording = ReportRecording("/r/stream-1.csv", 1000, 1))
        assertEquals("/r/stream-1.csv", ReportBundles.of(recorded, setOf(ReportSection.STREAM), null).stream)
        assertNull(ReportBundles.of(recorded, setOf(ReportSection.CODES), null).stream)
    }

    @Test
    fun aRecordingIsMeasuredFromItsLines() {
        val lines = sequenceOf(
            "1000000000,7E0,0C,0000,0",
            "1500000000,7E0,0D,00,0",
            "garbage",
            "43000000000,autoservice,SOC,,",
            "2000000000,7E0,0C,0FA0,1000",
        )
        assertEquals(StreamCsv.Summary(42_000, 3), StreamCsv.summary(lines))
        assertEquals(StreamCsv.Summary(null, 0), StreamCsv.summary(emptySequence()))
        assertEquals("00:42", ReportText.clock(42_000))
        assertEquals("1:02:05", ReportText.clock(3_725_000))
    }

    // ------------------------------------------------------------------ the save

    private class FakeWriter(var fail: Boolean = false) : ReportWriter {
        val bundles = mutableListOf<ReportBundle>()
        override fun lastRecording(): ReportRecording? = ReportRecording("/r/stream-20261020-193512.csv", 42_000, 4)
        override fun write(bundle: ReportBundle): ReportOutcome.Saved {
            bundles += bundle
            check(!fail) { "disk full" }
            return ReportOutcome.Saved(ReportNames.FOLDER, bundle.extensions.map { bundle.base + it }, 3)
        }
    }

    @Test
    fun theDeskSavesOnceAtATimeAndAFailureIsNoMessage() {
        val writer = FakeWriter()
        val desk = ReportDesk(writer)
        assertTrue(desk.claim())
        assertFalse(desk.claim())
        writer.fail = true
        desk.save { ReportBundle("scan-1", ReportDocument(emptyList(), ""), null, null) }
        assertEquals(ReportOutcome.Failed, desk.outcome)
        // The button is there again.
        assertTrue(desk.claim())
        writer.fail = false
        desk.save { ReportBundle("scan-1", ReportDocument(emptyList(), ""), "{}", null) }
        assertEquals(ReportOutcome.Saved("Download/Denza", listOf("scan-1.pdf", "scan-1.json"), 3), desk.outcome)
        desk.settle()
        assertTrue(desk.outcome is ReportOutcome.Saved)
        desk.open()
        assertEquals(ReportOutcome.Idle, desk.outcome)
        desk.look()
        assertEquals(4, desk.recording!!.signals)
    }

    @Test
    fun theSessionSavesWhatTheModelOffersAndSaysHowItWent() = runTest {
        val writer = FakeWriter()
        val s = DiagnosticsSession(
            buses = { carNow() },
            store = MemoryStore(),
            car = FakeCar(car),
            sinks = StreamSinks { null },
            wallClock = { Fixtures.EPOCH + testScheduler.currentTime },
            zone = ZoneOffset.UTC,
            context = backgroundScope.coroutineContext,
            reports = writer,
        )
        s.start()
        testScheduler.runCurrent()
        // The newest recording was looked for on opening and is offered.
        val stream = s.model.value.report.options.first { it.section == ReportSection.STREAM }
        assertTrue(stream.available)
        assertEquals("00:42 · 4 сигнала", stream.detail)

        s.saveReport(setOf(ReportSection.CODES, ReportSection.RAW, ReportSection.STREAM))
        assertEquals(ReportOutcome.Saving, s.model.value.report.outcome)
        s.saveReport(setOf(ReportSection.CODES))
        testScheduler.runCurrent()
        assertEquals(1, writer.bundles.size)
        val saved = s.model.value.report.outcome as ReportOutcome.Saved
        assertEquals(listOf(".pdf", ".json", ".csv").map { writer.bundles.single().base + it }, saved.names)
        assertTrue(writer.bundles.single().base.startsWith("scan-20261020-"))
        assertTrue(writer.bundles.single().document.pieces.any { it is ReportPiece.Heading && it.text == "КОДЫ" })

        writer.fail = true
        s.saveReport(setOf(ReportSection.CODES))
        testScheduler.runCurrent()
        assertEquals(ReportOutcome.Failed, s.model.value.report.outcome)
        s.stop()
    }
}
