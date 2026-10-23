package dev.denza.apps.feature.diagnostics.report

/** How wide a run of text is in a style, in points: the painter's own measure on the device, a fixed one in tests. */
internal fun interface TextMeasure {
    fun width(text: String, style: ReportStyle): Float
}

/** What a page holds, in points from its top left corner. */
internal sealed interface ReportInk {
    /** A run of text on its baseline. */
    data class Text(val x: Float, val baseline: Float, val text: String, val style: ReportStyle) : ReportInk

    /** A hairline across. */
    data class Rule(val x0: Float, val x1: Float, val y: Float) : ReportInk
}

internal data class ReportPage(val number: Int, val inks: List<ReportInk>) {
    /** Every run of text on the page, top to bottom, for whoever reads it back. */
    val texts: List<String> get() = inks.filterIsInstance<ReportInk.Text>().map { it.text }
}

/** The sheet: A4 in points and its margins. */
internal object ReportSheet {
    const val WIDTH = 595f
    const val HEIGHT = 842f
    const val MARGIN_X = 40f
    const val MARGIN_TOP = 40f
    const val MARGIN_BOTTOM = 32f
    const val CONTENT = WIDTH - 2 * MARGIN_X
    /** Between two columns of a table. */
    const val GAP = 8f
    /** Above a heading, and between its rule and what follows. */
    const val HEADING_ABOVE = 14f
    const val HEADING_BELOW = 5f
    /** Between the footer's rule and its text, and between the page's last line and the rule. */
    const val FOOTER_PAD = 6f
}

/**
 * A [ReportDocument] on A4 pages (architecture, 7.4): pure arithmetic over a [TextMeasure], so what it
 * lays out is checked in a JVM test and the painter only draws the [ReportInk]s. Text wraps at spaces,
 * and a word wider than its room breaks where it must; a heading stays with what follows it; a table
 * row is never split, and a table that goes on to the next page repeats its column titles there. Every
 * page ends with the footer and «Стр. N из M».
 */
internal class ReportLayout(private val measure: TextMeasure) {

    fun lay(document: ReportDocument): List<ReportPage> {
        val footerLines = wrap(document.footer, ReportStyle.FOOT, ReportSheet.CONTENT - measure.width(ReportDocuments.page(999, 999), ReportStyle.FOOT) - ReportSheet.GAP * 2)
        val footerHeight = ReportSheet.FOOTER_PAD * 2 + footerLines.size * ReportStyle.FOOT.leading
        val bottom = ReportSheet.HEIGHT - ReportSheet.MARGIN_BOTTOM - footerHeight
        val pages = mutableListOf<MutableList<ReportInk>>(mutableListOf())
        var y = ReportSheet.MARGIN_TOP

        fun newPage() {
            pages += mutableListOf<ReportInk>()
            y = ReportSheet.MARGIN_TOP
        }
        fun top() = y <= ReportSheet.MARGIN_TOP
        fun ink(i: ReportInk) {
            pages.last() += i
        }
        /** Room for [h] more points, or a new page unless this one is still empty. */
        fun room(h: Float) {
            if (y + h > bottom && !top()) newPage()
        }
        fun line(text: String, style: ReportStyle, x: Float = ReportSheet.MARGIN_X) {
            room(style.leading)
            ink(ReportInk.Text(x, y + style.size, text, style))
            y += style.leading
        }

        document.pieces.forEachIndexed { index, piece ->
            when (piece) {
                is ReportPiece.Head -> {
                    val brandWidth = measure.width(piece.brand, ReportStyle.BRAND)
                    val title = ReportStyle.TITLE
                    ink(ReportInk.Text(ReportSheet.MARGIN_X, y + title.size, piece.title, title))
                    ink(ReportInk.Text(ReportSheet.MARGIN_X + ReportSheet.CONTENT - brandWidth, y + title.size, piece.brand, ReportStyle.BRAND))
                    y += title.leading + 4f
                    piece.lines.forEach { (text, style) -> wrap(text, style, ReportSheet.CONTENT).forEach { line(it, style) } }
                }
                is ReportPiece.Heading -> {
                    val style = ReportStyle.HEADING
                    val own = ReportSheet.HEADING_ABOVE + style.leading + ReportSheet.HEADING_BELOW
                    // The heading keeps company: at least the first line or row of what follows it.
                    room(own + firstHeight(document.pieces.getOrNull(index + 1)))
                    if (!top()) y += ReportSheet.HEADING_ABOVE
                    ink(ReportInk.Text(ReportSheet.MARGIN_X, y + style.size, piece.text, style))
                    y += style.leading
                    ink(ReportInk.Rule(ReportSheet.MARGIN_X, ReportSheet.MARGIN_X + ReportSheet.CONTENT, y))
                    y += ReportSheet.HEADING_BELOW
                }
                is ReportPiece.Text -> wrap(piece.text, piece.style, ReportSheet.CONTENT).forEach { line(it, piece.style) }
                is ReportPiece.Table -> {
                    val widths = widths(piece.columns)
                    val xs = widths.runningFold(ReportSheet.MARGIN_X) { x, w -> x + w + ReportSheet.GAP }
                    fun titles() {
                        val style = ReportStyle.COLUMN
                        piece.columns.forEachIndexed { c, column -> ink(ReportInk.Text(xs[c], y + style.size, column.title, style)) }
                        y += style.leading + 1f
                    }
                    titles()
                    piece.rows.forEach { row ->
                        val cells = row.mapIndexed { c, text -> wrap(text, piece.columns[c].style, widths[c]) }
                        val leading = piece.columns.maxOf { it.style.leading }
                        val h = cells.maxOf { it.size.coerceAtLeast(1) } * leading
                        if (y + h > bottom && !top()) {
                            newPage()
                            titles()
                        }
                        cells.forEachIndexed { c, lines ->
                            val style = piece.columns[c].style
                            lines.forEachIndexed { i, text -> ink(ReportInk.Text(xs[c], y + i * leading + style.size, text, style)) }
                        }
                        y += h
                    }
                }
            }
        }

        val total = pages.size
        return pages.mapIndexed { i, inks ->
            val foot = ReportStyle.FOOT
            val ruleY = ReportSheet.HEIGHT - ReportSheet.MARGIN_BOTTOM - footerHeight + ReportSheet.FOOTER_PAD
            inks += ReportInk.Rule(ReportSheet.MARGIN_X, ReportSheet.MARGIN_X + ReportSheet.CONTENT, ruleY)
            val first = ruleY + ReportSheet.FOOTER_PAD + foot.size
            footerLines.forEachIndexed { n, text -> inks += ReportInk.Text(ReportSheet.MARGIN_X, first + n * foot.leading, text, foot) }
            val label = ReportDocuments.page(i + 1, total)
            inks += ReportInk.Text(ReportSheet.MARGIN_X + ReportSheet.CONTENT - measure.width(label, foot), first, label, foot)
            ReportPage(i + 1, inks.toList())
        }
    }

    /** The fixed columns as asked, the flexible ones sharing what is left, never narrower than [MIN_COLUMN]. */
    fun widths(columns: List<ReportColumn>): List<Float> {
        val fixed = columns.filter { it.width > 0f }.sumOf { it.width.toDouble() }.toFloat()
        val flexible = columns.count { it.width <= 0f }
        val left = ReportSheet.CONTENT - fixed - ReportSheet.GAP * (columns.size - 1)
        val share = if (flexible == 0) 0f else (left / flexible).coerceAtLeast(MIN_COLUMN)
        return columns.map { if (it.width > 0f) it.width else share }
    }

    private fun firstHeight(piece: ReportPiece?): Float = when (piece) {
        null, is ReportPiece.Heading -> 0f
        is ReportPiece.Head -> 0f
        is ReportPiece.Text -> piece.style.leading
        is ReportPiece.Table -> ReportStyle.COLUMN.leading + 1f + piece.columns.maxOf { it.style.leading }
    }

    /** [text] in lines no wider than [width]: at spaces, and inside a word only when the word alone is wider. */
    fun wrap(text: String, style: ReportStyle, width: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        text.split('\n').forEach { paragraph ->
            var line = ""
            paragraph.split(' ').filter { it.isNotEmpty() }.forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                when {
                    measure.width(candidate, style) <= width -> line = candidate
                    else -> {
                        if (line.isNotEmpty()) out += line
                        line = ""
                        var rest = word
                        while (measure.width(rest, style) > width && rest.length > 1) {
                            var n = rest.length - 1
                            while (n > 1 && measure.width(rest.take(n), style) > width) n--
                            out += rest.take(n)
                            rest = rest.drop(n)
                        }
                        line = rest
                    }
                }
            }
            out += line
        }
        return out
    }

    companion object {
        const val MIN_COLUMN = 60f
    }
}
