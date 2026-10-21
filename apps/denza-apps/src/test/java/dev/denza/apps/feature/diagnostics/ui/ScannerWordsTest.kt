package dev.denza.apps.feature.diagnostics.ui

import dev.denza.apps.feature.diagnostics.scan.CompareColumn
import dev.denza.apps.feature.diagnostics.protocol.DataQuality
import dev.denza.apps.feature.diagnostics.scan.DtcRow
import dev.denza.apps.feature.diagnostics.scan.DtcView
import dev.denza.apps.feature.diagnostics.scan.Field
import dev.denza.apps.feature.diagnostics.scan.IdentView
import dev.denza.apps.feature.diagnostics.scan.ModuleGroupView
import dev.denza.apps.feature.diagnostics.scan.ModuleRowView
import dev.denza.apps.feature.diagnostics.scan.ModuleStatus
import dev.denza.apps.feature.diagnostics.scan.MilState
import dev.denza.apps.feature.diagnostics.scan.RailTone
import dev.denza.apps.feature.diagnostics.scan.ScanPhase
import dev.denza.apps.feature.diagnostics.scan.ScanSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the scanner's screens derive from the model themselves. */
class ScannerWordsTest {

    private fun row(status: ModuleStatus, active: String = "0", history: String = "0", request: Int? = 0x720) = ModuleRowView(
        request = request, abbreviation = "LA_BCM", unit = "LA_BCM", name = "Кузовной контроллер левый", short = "Кузов слева",
        inferred = false, route = ScannerWords.route(request), protocol = "UDS", active = active, history = history,
        status = status, target = null,
    )

    @Test
    fun aHistoricalStatusIsReadOutBitByBit() {
        // the R_ZCU's two records, 19 02 FF from 747 on 2026-10-20
        val e = ScannerWords.explain(0x28)
        assertEquals("СТАТУС 0x28 = 0010 1000", e.head)
        assertEquals("Подтверждённый, неактивный (исторический).", e.lead)
        assertEquals(
            "CDTC = 1 и TFSLC = 1: отказ был зафиксирован после последнего сброса. " +
                "TF = 0, TFTOC = 0 и TNCTOC = 0: в текущем цикле тест выполнен, отказа нет. WIR = 0: лампу не запрашивает.",
            e.body,
        )
    }

    @Test
    fun anActiveConfirmedStatusAsksForTheLamp() {
        val e = ScannerWords.explain(0xAF)
        assertEquals("Активный, подтверждённый.", e.lead)
        assertTrue(e.body.endsWith("WIR = 1: блок запрашивает лампу."))
        assertTrue(ScannerWords.asksLamp(0xAF))
        assertFalse(ScannerWords.asksLamp(0x28))
    }

    @Test
    fun theReplyAddressIsTheRequestPlusEight() {
        assertEquals("7E0 → 7E8", ScannerWords.route(0x7E0))
        assertEquals("747 → 74F", ScannerWords.route(0x747))
        assertEquals("—", ScannerWords.route(null))
    }

    @Test
    fun onlyWithDtcKeepsTheRowsWhoseMemoryHoldsARecord() {
        val g = ModuleGroupView(
            "body", "КУЗОВ", "Кузов", "",
            listOf(row(ModuleStatus.HISTORY, history = "8"), row(ModuleStatus.OK), row(ModuleStatus.UNREAD, "—", "—")),
        )
        val empty = g.copy(id = "lv", rows = listOf(row(ModuleStatus.OK)))
        assertEquals(listOf(g, empty), ScannerWords.filter(listOf(g, empty), onlyDtc = false))
        val kept = ScannerWords.filter(listOf(g, empty), onlyDtc = true)
        assertEquals(listOf("body"), kept.map { it.id })
        assertEquals(listOf(ModuleStatus.HISTORY), kept.single().rows.map { it.status })
    }

    @Test
    fun everyStatusHasAChipAndOnlyReadOnesCount() {
        ModuleStatus.entries.forEach { assertTrue(it.name, ScannerWords.chip(it).text.isNotEmpty()) }
        assertEquals("OK", ScannerWords.chip(ModuleStatus.OK).text)
        assertEquals("НЕТ АДРЕСА", ScannerWords.chip(ModuleStatus.NO_ROUTE).text)
        assertEquals(ScannerWords.Look.UNREAD, ScannerWords.chip(ModuleStatus.UNREAD).look)
        assertTrue(ScannerWords.read(ModuleStatus.HISTORY))
        assertFalse(ScannerWords.read(ModuleStatus.UNREAD))
        assertEquals("ИСТ 8", ScannerWords.tileState(row(ModuleStatus.HISTORY, history = "8")))
        // the look alone says OK and not read: the tile keeps its address
        assertEquals("", ScannerWords.tileState(row(ModuleStatus.OK)))
        assertEquals("", ScannerWords.tileState(row(ModuleStatus.UNREAD)))
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
    fun theMatchIsCountedInTheBytesOfF194() {
        val ident = IdentView(listOf(Field("Номер ПО поставщика · F194", "32440011E004000000")), emptyList(), true, emptyList())
        assertEquals("побайтно, 9 байт", ScannerWords.matchedBytes(ident))
        assertEquals(null, ScannerWords.matchedBytes(ident.copy(own = emptyList())))
    }

    @Test
    fun aComparisonColumnIsHeadedByItsBlock() {
        assertEquals("FMCU · 7E3", ScannerWords.columnTag(CompareColumn("FMCU", "Передний", 0x7E3)))
        assertEquals("SMCU", ScannerWords.columnTag(CompareColumn("SMCU", "Задний", null)))
    }

    @Test
    fun theAutoscansHeadSaysWhenItRanAndTheSystemsTheirSnapshot() {
        fun scan(phase: ScanPhase, at: String?) = ScanSummary(phase, 12, 68, at, "68/68", 0, 10, "19/68", MilState.OFF, null)
        assertEquals("опрос 16:31 · маска DTC 09", ScannerWords.scanLine(scan(ScanPhase.DONE, "16:31")))
        assertEquals("опрос 12/68 · маска DTC 09", ScannerWords.scanLine(scan(ScanPhase.RUNNING, "16:31")))
        assertEquals("опроса не было · маска DTC 09", ScannerWords.scanLine(scan(ScanPhase.NEVER, null)))
        assertEquals("снимок 19:31:29", ScannerWords.snapshot("19 01 09 · 19 02 09 · снимок 19:31:29"))
        assertEquals(null, ScannerWords.snapshot("не читали"))
        assertEquals("· 68 блоков", ScannerWords.blocks(68))
        assertEquals("· 3 блока", ScannerWords.blocks(3))
        assertEquals("· 1 блок", ScannerWords.blocks(1))
        assertEquals("· 11 блоков", ScannerWords.blocks(11))
    }

    @Test
    fun aRailMarkIsQuietUnlessCodesAreActive() {
        assertEquals(ScannerSpec.Ink.FAINT, ScannerWords.railInk(RailTone.COUNT))
        assertEquals(ScannerSpec.Ink.INK4, ScannerWords.railInk(RailTone.HISTORY))
        assertEquals(ScannerSpec.Ink.ORANGE, ScannerWords.railInk(RailTone.ACTIVE))
    }

    @Test
    fun aLegendExplainsOnlyTheKindsOnScreen() {
        val legend = listOf(Swatch.OK to "OK", Swatch.NONE to "адрес не найден", Swatch.MECH to "механическая связь")
        assertEquals(listOf(Swatch.OK, Swatch.MECH), ScannerWords.present(legend, listOf(ModuleStatus.OK)).map { it.first })
        assertEquals(legend, ScannerWords.present(legend, listOf(ModuleStatus.OK, ModuleStatus.NO_ROUTE)))
    }

    @Test
    fun aFastCarSignalHasNoPid() {
        assertEquals("0C", ScannerWords.pid(0x0C))
        assertEquals("—", ScannerWords.pid(-1))
    }

    @Test
    fun aGroupSpanningTwoColumnsKeepsItsWholeTitle() {
        val g = ModuleGroupView("misc", "НАЗНАЧЕНИЕ НЕ УСТАНОВЛЕНО", "Прочие", "", List(12) { row(ModuleStatus.UNREAD) })
        assertEquals("НАЗНАЧЕНИЕ НЕ УСТАНОВЛЕНО · 12", ScannerWords.topologyLabel(g, 2))
        assertEquals("ПРОЧИЕ · 12", ScannerWords.topologyLabel(g, 1))
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
    fun theContactorSaysItsStateOnlyWhenKnown() {
        assertEquals("замкнут", ScannerWords.contactor(true))
        assertEquals("разомкнут", ScannerWords.contactor(false))
        assertEquals(null, ScannerWords.contactor(null))
    }
}
