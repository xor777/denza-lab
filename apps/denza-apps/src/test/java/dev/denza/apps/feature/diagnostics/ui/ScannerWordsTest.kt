package dev.denza.apps.feature.diagnostics.ui

import dev.denza.apps.feature.diagnostics.scan.BlockRow
import dev.denza.apps.feature.diagnostics.scan.BlocksFilter
import dev.denza.apps.feature.diagnostics.scan.CarSystem
import dev.denza.apps.feature.diagnostics.scan.CompareColumn
import dev.denza.apps.feature.diagnostics.protocol.DataQuality
import dev.denza.apps.feature.diagnostics.scan.DtcRow
import dev.denza.apps.feature.diagnostics.scan.DtcView
import dev.denza.apps.feature.diagnostics.scan.Mark
import dev.denza.apps.feature.diagnostics.scan.MarkCount
import dev.denza.apps.feature.diagnostics.scan.ModuleRowView
import dev.denza.apps.feature.diagnostics.scan.ModuleStatus
import dev.denza.apps.feature.diagnostics.scan.MilState
import dev.denza.apps.feature.diagnostics.scan.RawLine
import dev.denza.apps.feature.diagnostics.scan.ScanPhase
import dev.denza.apps.feature.diagnostics.scan.ScanSummary
import dev.denza.apps.feature.diagnostics.scan.SystemTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the scanner's screens derive from the model themselves. */
class ScannerWordsTest {

    private fun row(status: ModuleStatus, active: String = "0", history: String = "0", request: Int? = 0x720, pending: String = "0") = ModuleRowView(
        request = request, abbreviation = "LA_BCM", unit = "LA_BCM", name = "Кузовной контроллер левый", short = "Кузов слева",
        inferred = false, route = ScannerWords.route(request), protocol = "UDS", active = active, history = history,
        status = status, target = null, pending = pending,
    )

    @Test
    fun aStatusByteIsReadInWordsOfWhatTheBlockReports() {
        // the R_ZCU's two records, 19 02 FF from 747 on 2026-10-20 (board `scanner-body-dtc`)
        assertEquals("подтверждён · был отказ после сброса · сейчас не активен", ScannerWords.statusWords(0x28, 0xFF))
        assertEquals("доступность 0xFF", ScannerWords.availabilityNote(0xFF))
        // LA_BCM's U0121-87, 08 under an availability of 09: bit 5 is not reported (board `scanner-code`)
        assertEquals("подтверждён · сейчас не активен", ScannerWords.statusWords(0x08, 0x09))
        assertEquals("«·» — блок этот бит не сообщает · доступность 0x09", ScannerWords.availabilityNote(0x09))
        assertEquals("подтверждён · запрос лампы · сейчас активен", ScannerWords.statusWords(0x89, 0xFF))
        assertEquals("0x28", ScannerWords.hex2(0x28))
        assertEquals("0010 1000", ScannerWords.bin8(0x28))
        assertEquals(listOf("WIR", "TNCTOC", "TFSLC", "TNCSLC", "CDTC", "PDTC", "TFTOC", "TF"), ScannerWords.STRIP)
    }

    @Test
    fun aRecordsKindIsNowElsePendingElseHistory() {
        assertEquals(Mark.ACTIVE, ScannerWords.markOf(0x09))
        assertEquals(Mark.PENDING, ScannerWords.markOf(0x04))
        assertEquals(Mark.HISTORY, ScannerWords.markOf(0x08))
        assertTrue(ScannerWords.asksLamp(0xAF))
        assertFalse(ScannerWords.asksLamp(0x28))
        assertEquals(Mark.PENDING, ScannerWords.mark(ModuleStatus.PENDING))
        assertEquals(Mark.NO_REPLY, ScannerWords.mark(ModuleStatus.NO_REPLY))
        assertEquals(null, ScannerWords.mark(ModuleStatus.OK))
    }

    @Test
    fun theReplyAddressIsTheRequestPlusEight() {
        assertEquals("7E0 → 7E8", ScannerWords.route(0x7E0))
        assertEquals("747 → 74F", ScannerWords.route(0x747))
        assertEquals("—", ScannerWords.route(null))
    }

    @Test
    fun aCountersUnitIsTheWordAfterItsLastSpace() {
        // the board's splitUnit(): «553 В» is 553 and «В», «1 250» stays whole
        assertEquals("553" to "В", ScannerWords.splitUnit("553 В"))
        assertEquals("1 250" to "", ScannerWords.splitUnit("1 250"))
        assertEquals("2,60–2,72" to "бар", ScannerWords.splitUnit("2,60–2,72 бар"))
        assertEquals("ВЫКЛ" to "", ScannerWords.splitUnit("ВЫКЛ"))
        assertEquals("9 из 9" to "", ScannerWords.splitUnit("9 из 9"))
    }

    @Test
    fun aTileSaysItsRecordsOrWhatItsLookCannot() {
        assertEquals(ScannerWords.TileRight(MarkCount(Mark.HISTORY, 8), ""), ScannerWords.tileRight(row(ModuleStatus.HISTORY, history = "8")))
        assertEquals(ScannerWords.TileRight(MarkCount(Mark.PENDING, 3), ""), ScannerWords.tileRight(row(ModuleStatus.PENDING, history = "2", pending = "1")))
        // the look alone says OK and not read: the tile keeps its address
        assertEquals("720", ScannerWords.tileRight(row(ModuleStatus.OK)).word)
        assertEquals("720", ScannerWords.tileRight(row(ModuleStatus.UNREAD, "—", "—", pending = "—")).word)
        // a gate answer with no software code in it: not «другой блок» - newer software passes the gate (5.1)
        assertEquals("не опознан", ScannerWords.tileRight(row(ModuleStatus.UNIDENTIFIED)).word)
        assertEquals("нет адреса", ScannerWords.tileRight(row(ModuleStatus.NO_ROUTE, request = null)).word)
        assertTrue(ScannerWords.read(ModuleStatus.HISTORY))
        assertFalse(ScannerWords.read(ModuleStatus.UNREAD))
    }

    @Test
    fun bitmapPidsAreTheServiceOnesAndTheAskedListIsEveryMapTheIdentificationReads() {
        val pids = "01 03 1F 20 21 40 41 60 67 80".split(' ')
        assertEquals(listOf("20", "40", "60", "80"), pids.filter(ScannerWords::bitmapPid))
        assertEquals("01 00, 01 20, 01 40, 01 60, 01 80", ScannerWords.pidsAsked(pids))
        // ECM 2026-10-21: 80 lists A0, A0 lists A6 alone.
        assertEquals("01 00, 01 20, 01 40, 01 60, 01 80, 01 A0", ScannerWords.pidsAsked(pids + listOf("A0", "A6")))
        // A map beyond the last one read is listed, not asked.
        assertEquals("01 00, 01 20, 01 40, 01 60, 01 80, 01 A0", ScannerWords.pidsAsked(pids + listOf("A0", "A6", "C0")))
    }

    @Test
    fun aComparisonColumnIsHeadedByItsBlock() {
        assertEquals("FMCU · 7E3", ScannerWords.columnTag(CompareColumn("FMCU", "Передний", 0x7E3)))
        assertEquals("SMCU", ScannerWords.columnTag(CompareColumn("SMCU", "Задний", null)))
    }

    @Test
    fun theScanButtonRestartsTheReadAndSaysWhatTheRunningOneDoes() {
        fun scan(phase: ScanPhase) = ScanSummary(phase, 12, 68, "16:31", "68/68", 0, 10, "19/68", MilState.OFF, null)
        assertEquals("Опросить снова", ScannerWords.scanButton(scan(ScanPhase.DONE)))
        assertEquals("Опрос… 12/68", ScannerWords.scanButton(scan(ScanPhase.RUNNING)))
        assertEquals("Скан идёт" to "опрос 12 из 68 блоков", ScannerWords.noSummary(scan(ScanPhase.RUNNING)))
        assertEquals("снимок 19:31:29", ScannerWords.snapshot("19 01 09 · 19 02 09 · снимок 19:31:29"))
        assertEquals(null, ScannerWords.snapshot("не читали"))
        assertEquals(listOf("Таблица", "Графики · 4"), ScannerWords.views(4))
        assertEquals("запись 0:42", ScannerWords.recording(42_400))
        assertEquals("запись 1:24 · войдёт в отчёт", ScannerWords.recorded(84_000))
        assertEquals("запись 12:05", ScannerWords.recording(725_000))
    }

    @Test
    fun theRailBeforeTheSessionHasOneIsEveryPlaceUnmarked() {
        val model = dev.denza.apps.feature.diagnostics.scan.DiagnosticsModel(
            dev.denza.apps.feature.diagnostics.scan.Header(dev.denza.apps.feature.diagnostics.scan.LinkState.IDLE, "Z9 GT DM", "—"),
            ScanSummary(ScanPhase.NEVER, 0, 68, null, "", null, null, "", MilState.UNKNOWN, null),
            emptyList(), emptyMap(),
            dev.denza.apps.feature.diagnostics.scan.SystemsView(
                dev.denza.apps.feature.diagnostics.scan.HvView(emptyList(), ModuleStatus.UNREAD, emptyList(), emptyList(), emptyList()),
                emptyMap(), dev.denza.apps.feature.diagnostics.scan.CompareView(emptyList(), emptyList(), emptyList()),
                dev.denza.apps.feature.diagnostics.scan.CompareView(emptyList(), emptyList(), emptyList()), emptyList(),
            ),
        )
        val lines = ScannerWords.railLines(model)
        assertEquals(listOf("Сводка", "Блоки"), lines.take(2).map { it.name })
        assertEquals("Журнал", lines.last().name)
        assertTrue(lines.all { it.mark == null })
        assertEquals("Здоровье", ScannerWords.tabLabel(CarSystem.PACK, SystemTab.STATE))
        assertEquals("Состояние", ScannerWords.tabLabel(CarSystem.HV, SystemTab.STATE))
    }

    private fun block(abbr: String, mark: Mark?, records: String) = BlockRow(
        0x700, abbr, abbr, "700 → 708", "UDS", mark, records, "—", false, "—",
        setOfNotNull(BlocksFilter.ALL, BlocksFilter.WITH_RECORDS.takeIf { records != "—" }), null,
    )

    @Test
    fun blocksWithRecordsComeFirstTheWorstAndTheMostFirst() {
        val rows = listOf(block("A", null, "—"), block("B", Mark.HISTORY, "8"), block("C", Mark.HISTORY, "2"), block("D", Mark.PENDING, "1"))
        val groups = ScannerWords.groups(rows)
        assertEquals(listOf("С ЗАПИСЯМИ · 3", "БЕЗ ЗАПИСЕЙ · 1"), groups.map { it.title })
        assertEquals(listOf("D", "B", "C"), groups[0].rows.map { it.abbreviation })
        // a filter keeps what passes it and counts what it shows
        assertEquals(listOf("С ЗАПИСЯМИ · 3"), ScannerWords.filtered(groups, BlocksFilter.WITH_RECORDS).map { it.title })
        assertEquals(groups, ScannerWords.filtered(groups, BlocksFilter.ALL))
    }

    @Test
    fun aRawReadIsCutAtItsRecord() {
        // «19 02 09 → 59 02 09 · … · C1 21 87 08 · …» with U0121-87 picked out (board `scanner-code`)
        val text = "19 02 09 → 59 02 09 · … · C1 21 87 08 · …"
        val at = text.indexOf("C1")
        assertEquals(Triple("19 02 09 → 59 02 09 · … · ", "C1 21 87 08", " · …"), ScannerWords.pieces(RawLine(text, at until at + 11)))
        assertEquals(Triple(text, "", ""), ScannerWords.pieces(RawLine(text)))
    }

    @Test
    fun theGraphsReadNumbersAsTheBoardDoes() {
        assertEquals("1 882", ScannerWords.number(1882.4f, 0))
        assertEquals("+3,1", ScannerWords.number(3.1f, 1, signed = true))
        assertEquals("−4,7", ScannerWords.number(-4.7f, 1, signed = true))
        assertEquals("0,0", ScannerWords.number(-0.01f, 1))
        assertEquals("−37,7 с", ScannerWords.secondsBack(37.7f))
    }

    @Test
    fun theReportSaysItsRequestsByTheirServices() {
        assertEquals("22 F194 · 19 01/02 · OBD 01/03/07/09/0A", ScannerWords.reportServices(listOf("22 F194", "19 01 09", "19 02 09", "OBD 01/03/07/09/0A")))
        assertEquals("3 страницы", "3 " + ScannerWords.plural(3, "страница", "страницы", "страниц"))
        assertEquals("68 блоков", "68 " + ScannerWords.plural(68, "блок", "блока", "блоков"))
    }

    @Test
    fun aFastCarSignalHasNoPid() {
        assertEquals("0C", ScannerWords.pid(0x0C))
        assertEquals("—", ScannerWords.pid(-1))
    }

    @Test
    fun aDescriptionTakesTwoLinesAtMostAndTheSecondCarriesTheRest() {
        // one unit a character: the board's wrap() and the screen's break at the same spaces
        val w = { t: String -> t.length.toFloat() }
        assertEquals(listOf("Датчик атмосферного", "давления"), ScannerWords.wrap("Датчик атмосферного давления", 20f, w))
        assertEquals(listOf("Контроллер левого", "заднего электродвигателя: перенапряжение"),
            ScannerWords.twoLines("Контроллер левого заднего электродвигателя: перенапряжение", 20f, w))
        assertEquals(listOf("BIC1"), ScannerWords.twoLines("BIC1", 20f, w))
    }

    @Test
    fun aFaultRowLeadsWithTheDescriptionAndDrawsNoEmptyLine() {
        val read = DtcRow("P2227-29", "29", "Неверный сигнал", "Силовая · общий код SAE", "", 0x09, 0xFF,
            description = "Датчик атмосферного давления: неисправность сигнала")
        assertEquals("Датчик атмосферного давления: неисправность сигнала" to "Неверный сигнал", ScannerWords.faultLines(read))
        assertEquals("Неверный сигнал" to "", ScannerWords.faultLines(read.copy(description = null)))
    }

    @Test
    fun theDescriptionsSourceIsSaidOnceInTheFooter() {
        val dtc = DtcView(emptyList(), emptyList(), DataQuality.COMPLETE, outsideMask = false, footnote = "19 01 09 · 19 02 09",
            source = "Описания: каталог мануала Z9GT PHEV, перевод")
        assertEquals(
            listOf(FooterNote.Words("Описания: каталог мануала Z9GT PHEV, перевод"), FooterNote.Divider, FooterNote.Words("19 01 09 · 19 02 09", mono = true)),
            dtcNotes(dtc),
        )
        assertEquals(listOf(FooterNote.Words("19 01 09 · 19 02 09", mono = true)), dtcNotes(dtc.copy(source = null)))
    }

    @Test
    fun theCellsAreNumberedAsTheBmsNumbersThem() {
        // the board's cellTicks for 166 cells; the extremes by the BMS's 1-based numbers
        assertEquals(listOf(1, 20, 40, 60, 80, 100, 120, 140, 160), ScannerWords.cellTicks(166))
        assertEquals(emptyList<Int>(), ScannerWords.cellTicks(0))
        assertEquals("мин №9", ScannerWords.cellExtreme(max = false, index = 8))
        assertEquals("макс №82", ScannerWords.cellExtreme(max = true, index = 81))
        assertEquals("Д14", ScannerWords.probe(13))
    }

    @Test
    fun aGbFlagsTileSaysItsBitAndItsCode() {
        // the board's «бит 16» and «код 1»; the footer's source
        assertEquals("бит 16", ScannerWords.gbBit(16))
        assertEquals("код 1", ScannerWords.gbCode(1))
        assertEquals("1039 · gbacqservice", ScannerWords.GB_FLAGS_SOURCE)
    }

    @Test
    fun theContactorSaysItsStateOnlyWhenKnown() {
        assertEquals("замкнут", ScannerWords.contactor(true))
        assertEquals("разомкнут", ScannerWords.contactor(false))
        assertEquals(null, ScannerWords.contactor(null))
    }
}
