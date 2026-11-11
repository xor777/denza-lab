package dev.denza.apps

/** One reading of the service report: a name and what it read. */
data class TechnicalRow(val key: String, val value: String)

/**
 * Readings that belong together - one feature's - under the name of what they belong to.
 *
 * [title] is what the page prints and nothing else. A section the app itself has to find again says
 * so with an [id], which no language changes.
 */
data class TechnicalSection(val title: String?, val rows: List<TechnicalRow>, val id: String? = null)

/**
 * The service report as text, and back.
 *
 * The report is plain text so it can be carried anywhere a string goes - the UI state, a log, a
 * test - and read back into the sections the technical page draws: a line `[Название]` opens a
 * section, or `[id|Название]` one with an id, and every other line is `key=value` in the section
 * above it, split on the first `=`. A line with no `=` is a key with nothing read (`—`), and a line
 * before any section goes to an untitled one. The Luminofor board's `techBlocks()` in `fixtures.js`
 * is this same rule, with its sections told apart by their titles.
 *
 * It used to be forty lines in one list, keyed half in English and half in Russian, with a
 * feature's state packed into one value as `phase=…; start=…; fire=…`. A screenshot of it answered
 * the question only for whoever wrote the line.
 */
object TechnicalReadings {

    fun render(sections: List<TechnicalSection>): String = buildString {
        sections.filter { it.rows.isNotEmpty() }.forEach { section ->
            section.title?.let { appendLine(header(it, section.id)) }
            section.rows.forEach { row -> appendLine("${row.key}=${row.value}") }
        }
    }.trimEnd()

    /** The line that opens a section. */
    fun header(title: String, id: String? = null): String =
        if (id == null) "[$title]" else "[$id$ID_MARK$title]"

    fun parse(text: String): List<TechnicalSection> {
        val sections = mutableListOf<TechnicalSection>()
        var title: String? = null
        var id: String? = null
        var rows = mutableListOf<TechnicalRow>()
        fun close() {
            if (rows.isNotEmpty()) sections += TechnicalSection(title, rows, id)
            rows = mutableListOf()
        }
        text.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            if (line.length > 2 && line.startsWith('[') && line.endsWith(']')) {
                close()
                val header = line.substring(1, line.length - 1)
                // An id is lower-case Latin, so a title that happens to hold the mark stays a title.
                id = header.substringBefore(ID_MARK, "").takeIf { candidate ->
                    candidate.isNotEmpty() && candidate.all { it in 'a'..'z' }
                }
                title = if (id == null) header else header.substringAfter(ID_MARK)
            } else {
                rows += row(line)
            }
        }
        close()
        return sections
    }

    /** `key=value`, split on the first `=`; a missing or empty value reads `—`. */
    fun row(line: String): TechnicalRow {
        val at = line.indexOf('=')
        if (at < 0) return TechnicalRow(line, NOTHING)
        return TechnicalRow(line.substring(0, at), line.substring(at + 1).ifBlank { NOTHING })
    }

    private const val NOTHING = "—"
    private const val ID_MARK = '|'
}
