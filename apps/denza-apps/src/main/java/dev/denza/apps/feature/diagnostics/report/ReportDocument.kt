package dev.denza.apps.feature.diagnostics.report

import dev.denza.apps.feature.diagnostics.scan.Figures
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How a run of text is set on the sheet, in points on A4: the size, the face and the ink. The layout
 * measures with it and the painter draws with it, so the two never disagree.
 */
internal enum class ReportStyle(val size: Float, val mono: Boolean = false, val bold: Boolean = false, val grey: Boolean = false, val tracking: Float = 0f) {
    TITLE(14f, bold = true, tracking = 0.06f),
    BRAND(8.5f, grey = true),
    LEAD(11f),
    META(9.5f, grey = true),
    HEADING(8f, bold = true, grey = true, tracking = 0.08f),
    BODY(10f),
    CELL(8.5f),
    CELL_GREY(8.5f, grey = true),
    CELL_MONO(8.5f, mono = true),
    CELL_MONO_BOLD(8.5f, mono = true, bold = true),
    CELL_MONO_GREY(8.5f, mono = true, grey = true),
    COLUMN(7f, bold = true, grey = true, tracking = 0.06f),
    FOOT(7.5f, grey = true);

    /** The distance between baselines of two lines of this style. */
    val leading: Float get() = size * 1.4f
}

/** One column of a table: its width in points (0 takes what the fixed ones leave) and how its cells are set. */
internal data class ReportColumn(val title: String, val width: Float, val style: ReportStyle)

/** What the sheet is made of, top to bottom; [ReportLayout] puts it on pages. */
internal sealed interface ReportPiece {
    /** The sheet's head: the title with the brand at the right, then its lines. */
    data class Head(val title: String, val brand: String, val lines: List<Pair<String, ReportStyle>>) : ReportPiece

    /** A section's name over a rule; kept on a page with what follows it. */
    data class Heading(val text: String) : ReportPiece

    /** A paragraph, wrapped at the sheet's width. */
    data class Text(val text: String, val style: ReportStyle = ReportStyle.BODY) : ReportPiece

    /**
     * Rows of cells under column titles; a row is never split, a long cell wraps inside its column, and
     * the titles come again at the top of a page the table goes on to.
     */
    data class Table(val columns: List<ReportColumn>, val rows: List<List<String>>) : ReportPiece {
        init {
            require(rows.all { it.size == columns.size }) { "every row has a cell for each column" }
        }
    }
}

/** The sheet's words: [pieces] in order, and the footer every page carries before its number. */
internal data class ReportDocument(val pieces: List<ReportPiece>, val footer: String)

/**
 * The PDF's content out of a [ReportInput] (architecture, 7.4; concept v4, «Отчёт»): the head, the
 * summary, then the sections asked for in the concept's order - codes, the pack, the engine, the blocks
 * and their software - and the footer with the services asked, the exchanges and their time. Raw replies
 * and the recording go into files of their own, not onto the sheet.
 */
internal object ReportDocuments {
    const val TITLE = "ОТЧЁТ О ДИАГНОСТИКЕ"
    const val BRAND = "Denza Apps · сканер"

    fun of(input: ReportInput, sections: Set<ReportSection>): ReportDocument {
        val pieces = mutableListOf<ReportPiece>(head(input))
        pieces += ReportPiece.Heading("ИТОГ")
        pieces += ReportPiece.Text(summary(input))
        if (ReportSection.CODES in sections) {
            pieces += ReportPiece.Heading("КОДЫ")
            pieces += if (input.codes.isEmpty()) ReportPiece.Text(if (input.summary.memoriesRead > 0) "Записей нет" else "Память не читали")
                else codes(input)
        }
        if (ReportSection.PACK in sections) {
            pieces += ReportPiece.Heading("ТЯГОВАЯ БАТАРЕЯ")
            pieces += ReportPiece.Text(pack(input.pack))
        }
        if (ReportSection.ENGINE in sections) {
            pieces += ReportPiece.Heading("ДВИГАТЕЛЬ")
            val e = input.engine
            pieces += ReportPiece.Text(e?.let(::engine) ?: "Не читали")
            e?.freezeFrame?.let { frame ->
                pieces += ReportPiece.Heading("СТОП-КАДР · MODE 02")
                pieces += ReportPiece.Table(
                    listOf(ReportColumn("ПАРАМЕТР", 0f, ReportStyle.CELL), ReportColumn("ЗНАЧЕНИЕ", 160f, ReportStyle.CELL_MONO)),
                    frame.map { listOf(it.name, it.value) },
                )
            }
            e?.tests?.let { t ->
                pieces += ReportPiece.Heading(TESTS)
                val out = t.out
                if (out.isNotEmpty()) {
                    fun unit(test: ReportTestRow, v: String) = if (test.unit.isEmpty()) v else "$v ${test.unit}"
                    pieces += ReportPiece.Table(
                        listOf(
                            ReportColumn("МОНИТОР", 0f, ReportStyle.CELL),
                            ReportColumn("TID", 34f, ReportStyle.CELL_MONO_GREY),
                            ReportColumn("ЗНАЧЕНИЕ", 76f, ReportStyle.CELL_MONO_BOLD),
                            ReportColumn("МИН", 76f, ReportStyle.CELL_MONO),
                            ReportColumn("МАКС", 76f, ReportStyle.CELL_MONO),
                        ),
                        out.map { (m, test) -> listOf("${m.name} · MID ${m.mid}", test.tid, unit(test, test.value), unit(test, test.min), unit(test, test.max)) },
                    )
                }
                pieces += ReportPiece.Text(tests(t))
            }
        }
        if (ReportSection.BLOCKS in sections) {
            pieces += ReportPiece.Heading("БЛОКИ И НОМЕРА ПО · ${input.blocks.size}")
            pieces += blocks(input)
        }
        return ReportDocument(pieces, footer(input))
    }

    private fun head(input: ReportInput): ReportPiece.Head {
        val car = listOfNotNull(
            input.car,
            "VIN ${input.vin ?: "—"}",
            input.odometerKm?.let { "пробег ${Figures.number(it, 1)} км" },
        ).joinToString(" · ")
        val meta = listOfNotNull(
            input.scanAt?.let { "скан ${DATE_TIME.format(it)}" } ?: "скана нет",
            when (input.parked) { true -> "стоянка P"; false -> "не P"; null -> null },
            input.lowVoltage?.let { "12 В ${Figures.number(it, 1)} В" },
            "только чтение",
        ).joinToString(" · ")
        return ReportPiece.Head(TITLE, BRAND, listOf(car to ReportStyle.LEAD, meta to ReportStyle.META))
    }

    /** «68 из 68 блоков ответили · активных 0 · ожидающих 0 · в истории 18 записей · блоков с записями 6». */
    fun summary(input: ReportInput): String {
        val s = input.summary
        val blocks = if (s.catalogued % 10 == 1 && s.catalogued % 100 != 11) "блока" else "блоков"
        val parts = mutableListOf("${s.answered} из ${s.catalogued} $blocks ответили")
        if (s.memoriesRead == 0) {
            parts += "память не читали"
        } else {
            parts += "активных ${s.active}"
            parts += "ожидающих ${s.pending}"
            parts += "в истории ${ReportInputs.count(s.history, "запись", "записи", "записей")}"
            parts += "блоков с записями ${s.blocksWithRecords}"
            if (s.memoriesRead < s.answered) parts += "память прочитана у ${s.memoriesRead}"
        }
        return parts.joinToString(" · ")
    }

    private fun codes(input: ReportInput): ReportPiece.Table {
        val described = input.codes.any { it.description != null }
        val dated = input.codes.any { it.firstSeen != null }
        val columns = listOfNotNull(
            ReportColumn("БЛОК", 82f, ReportStyle.CELL_MONO_GREY),
            ReportColumn("КОД", 66f, ReportStyle.CELL_MONO_BOLD),
            ReportColumn("ТИП ОТКАЗА", if (described) 120f else 0f, ReportStyle.CELL),
            ReportColumn("ОПИСАНИЕ", 0f, ReportStyle.CELL).takeIf { described },
            ReportColumn("СТАТУС", 62f, ReportStyle.CELL),
            ReportColumn("ВПЕРВЫЕ", 50f, ReportStyle.CELL_MONO_GREY).takeIf { dated },
        )
        var last: Int? = null
        val rows = input.codes.map { c ->
            val block = if (c.request != last) "${c.block} ${c.address}" else ""
            last = c.request
            listOfNotNull(
                block,
                c.code,
                c.failureName.replaceFirstChar { it.lowercase() },
                (c.description ?: "").takeIf { described },
                c.kind.word,
                (c.firstSeen?.let(DAY::format) ?: "—").takeIf { dated },
            )
        }
        return ReportPiece.Table(columns, rows)
    }

    /** «SOH 99 % · разброс ячеек 4 мВ (SOC 74 %) · изоляция 14,4 / 19,7 МОм · датчики 13…18 °C». */
    fun pack(p: ReportPack): String {
        if (!p.any) return "Не читали"
        fun n(v: Double?, d: Int) = v?.let { Figures.number(it, d) } ?: "н/д"
        val parts = mutableListOf("SOH ${n(p.sohPercent, 0)} %")
        parts += "разброс ячеек ${n(p.cellSpreadMv, 0)} мВ" + (p.socPercent?.let { " (SOC ${Figures.number(it, 0)} %)" } ?: "")
        p.fullChargeSpreadMv?.let { parts += "при полном заряде ${Figures.number(it, 0)} мВ" }
        parts += "изоляция ${n(p.insulationBmcKohm?.div(1000.0), 1)} / ${n(p.insulationSecondKohm?.div(1000.0), 1)} МОм"
        parts += "датчики ${n(p.probeMinC, 0)}…${n(p.probeMaxC, 0)} °C"
        return parts.joinToString(" · ")
    }

    const val TESTS = "ТЕСТЫ БОРТОВОЙ ДИАГНОСТИКИ · MODE 06"

    /**
     * Under the tests out of their limits, or alone: «17 тестов в норме, 2 не выполнялись»; with tests out,
     * «Вне нормы 1 · в норме 16, 2 не выполнялись».
     */
    fun tests(t: ReportTests): String {
        val notRun = if (t.notRun > 0) ", ${ReportInputs.count(t.notRun, "не выполнялся", "не выполнялись", "не выполнялись")}" else ""
        val out = t.out.size
        return if (out == 0) "${ReportInputs.count(t.passed, "тест", "теста", "тестов")} в норме$notRun" else "Вне нормы $out · в норме ${t.passed}$notRun"
    }

    /** «MIL выкл · готовность 4 из 9 · ПО 4.00.10 · калибровка Z31L07AB01». */
    fun engine(e: ReportEngine): String = listOf(
        when (e.mil) { true -> "MIL горит"; false -> "MIL выкл"; null -> "MIL н/д" },
        if (e.ready != null && e.supported != null) "готовность ${e.ready} из ${e.supported}" else "готовность н/д",
        "ПО ${e.software?.let(::software) ?: "н/д"}",
        "калибровка ${e.calibration ?: "н/д"}",
    ).joinToString(" · ")

    private fun blocks(input: ReportInput): ReportPiece.Table {
        val versions = input.blocks.any { it.f195 != null }
        val columns = listOfNotNull(
            ReportColumn("БЛОК", 74f, ReportStyle.CELL_MONO_BOLD),
            ReportColumn("АДРЕС", 40f, ReportStyle.CELL_MONO_GREY),
            ReportColumn("F194", 150f, ReportStyle.CELL_MONO),
            ReportColumn("F195", 130f, ReportStyle.CELL_MONO).takeIf { versions },
            ReportColumn("СТАТУС", 0f, ReportStyle.CELL),
        )
        val rows = input.blocks.map { b ->
            listOfNotNull(b.abbreviation, b.address, b.f194?.let(::software) ?: "—", (b.f195 ?: "—").takeIf { versions }, b.status)
        }
        return ReportPiece.Table(columns, rows)
    }

    /** «Запросы: 22 F194 · 19 01 09 · 19 02 09 · OBD 01/03/07/09/0A — 148 обменов за 19,9 с». */
    fun footer(input: ReportInput): String {
        val x = input.exchanges
        if (x.count == 0) return "Запросов не было"
        val time = x.durationMs?.takeIf { it > 0 }?.let { if (it < 1000) " за $it мс" else " за ${Figures.number(it / 1000.0, 1)} с" } ?: ""
        return "Запросы: ${x.services.joinToString(" · ")} — ${ReportInputs.count(x.count, "обмен", "обмена", "обменов")}$time"
    }

    /** A nine-byte software code in fours, «3244 0011 E004 0000 00»; anything else as it is. */
    fun software(text: String): String =
        if (text.length == 18 && text.all { it in '0'..'9' || it in 'A'..'F' }) text.chunked(4).joinToString(" ") else text

    /** «Стр. 1 из 3». */
    fun page(number: Int, of: Int) = "Стр. $number из $of"

    private val DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("dd.MM.yy")
}

/** Small words of the report shared by its parts. */
internal object ReportText {
    /** «00:42», «1:02:05»; «—» when not known. */
    fun clock(ms: Long?): String {
        if (ms == null) return "—"
        val s = ms / 1000
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%02d:%02d", m, sec)
    }
}
