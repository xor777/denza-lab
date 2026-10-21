package dev.denza.apps.feature.diagnostics.protocol

/**
 * A section of the public table of contents of the Z9GT PHEV 2024 diagnostic manual
 * (`research/vehicle-diagnostics/oem-dtc-public-index-20261020.json`), by its key in that file.
 * [side] is set for the two rear drives' sections.
 */
internal enum class OemSection(val key: String, val side: RearSide? = null) {
    ENGINE_ECM("engine_ecm"),
    BATTERY_MANAGEMENT("battery_management"),
    FRONT_DRIVE("front_drive"),
    GENERATOR("generator"),
    REAR_LEFT("rear_left", RearSide.LEFT),
    REAR_RIGHT("rear_right", RearSide.RIGHT);

    companion object {
        fun of(key: String): OemSection? = entries.firstOrNull { it.key == key }
    }
}

internal enum class RearSide(val short: String) { LEFT("Л"), RIGHT("П") }

/** One row of the index joined with its Russian title: a heading of the catalog, never a definition. */
internal data class OemHeading(
    val section: OemSection,
    /** `P222729`: the catalog's seven characters, no dash. */
    val displayCode: String,
    val outlineId: String,
    /** The public Chinese heading without its leading code. */
    val titleZh: String,
    /** The machine translation of [titleZh] (`oem-dtc-public-index-ru-20261020.json`). */
    val titleRu: String,
)

internal data class OemLocator(val section: OemSection, val outlineId: String)

/**
 * One distinct heading a code has in the sections its module is searched in. Equal headings are one
 * candidate with every place they stand: both rear sections, or the BMS duplicate that differs only by
 * a zero-width space.
 */
internal data class DtcCandidate(
    val displayCode: String,
    val titleZh: String,
    val titleRu: String,
    val locators: List<OemLocator>,
) {
    val section: OemSection get() = locators.first().section
    val outlineId: String get() = locators.first().outlineId
    val sections: List<OemSection> get() = locators.map { it.section }.distinct()

    /** The rear side this heading stands on alone; null when it is not a rear heading or stands on both. */
    val side: RearSide? get() = sections.mapNotNull { it.side }.distinct().singleOrNull()
        ?.takeIf { sections.all { s -> s.side != null } }
}

/** Why a code was not searched at all. */
internal enum class LookupSkip(val reason: String) {
    NO_SECTION("у блока нет раздела в оглавлении каталога"),
    FORMAT_01("сопоставление формата 01 с каталогом не установлено"),
    FORMAT_UNKNOWN("формат кода не известен: с каталогом не сопоставляется"),
}

/**
 * What the catalog has for one record. Empty [candidates] with no [skipped] means the code was
 * searched and the module's sections do not list it. [conflict]: one section gives the code two or
 * more different headings - all are kept, none is chosen.
 */
internal data class DtcLookup(
    val candidates: List<DtcCandidate>,
    val conflict: Boolean = false,
    val skipped: LookupSkip? = null,
)

/**
 * Candidate headings for a read DTC from the public index (docs/vehicle-diagnostics-architecture.md,
 * 4). A reference, not a decoding: the index is unqualified for the ECUs' software, and its own
 * headings conflict in places.
 *
 * Format 00 is matched by the record's J2012 code without the dash, `C1170-09` ↔ `C117009`; format 01
 * only from FMCU 7E3, laid out the same way ([catalogCode]). ISG's format 01 needs a mapping nobody has
 * established; any other format has no display.
 */
internal class OemDtcIndex(val headings: List<OemHeading>) {
    private val bySectionCode: Map<Pair<OemSection, String>, List<OemHeading>> =
        headings.groupBy { it.section to it.displayCode }

    fun lookup(request: Int, code: DtcCode): DtcLookup {
        val sections = sections(request)
        if (sections.isEmpty()) return DtcLookup(emptyList(), skipped = LookupSkip.NO_SECTION)
        val display = catalogCode(request, code) ?: return DtcLookup(
            emptyList(),
            skipped = if (code.format == DtcFormat.ISO_14229_1) LookupSkip.FORMAT_01 else LookupSkip.FORMAT_UNKNOWN,
        )
        val found = sections.flatMap { bySectionCode[it to display].orEmpty() }
        val candidates = found.groupBy { it.titleZh }.map { (zh, same) ->
            DtcCandidate(display, zh, same.first().titleRu, same.map { OemLocator(it.section, it.outlineId) })
        }
        val conflict = sections.any { s -> found.filter { it.section == s }.map { it.titleZh }.distinct().size > 1 }
        return DtcLookup(candidates, conflict)
    }

    companion object {
        val EMPTY = OemDtcIndex(emptyList())

        /**
         * The sections a module's codes are searched in, by request address. SMCU drives the left rear
         * motor and SMCU3 the right, yet each is searched in both rear sections: no code of theirs has been
         * read against a section, so the manual's side is not taken for the block's (architecture, 4).
         */
        fun sections(request: Int): List<OemSection> = when (request) {
            0x7E0 -> listOf(OemSection.ENGINE_ECM)
            0x7E7 -> listOf(OemSection.BATTERY_MANAGEMENT)
            0x7E3 -> listOf(OemSection.FRONT_DRIVE)
            0x7E6 -> listOf(OemSection.GENERATOR)
            0x744, 0x745 -> listOf(OemSection.REAR_LEFT, OemSection.REAR_RIGHT)
            else -> emptyList()
        }

        /**
         * The modules whose format-01 codes are looked up laid out as J2012: FMCU 7E3 alone. Its live
         * `19 0A` registry of 32 codes (2026-10-21, `research/vehicle-diagnostics/supported-dtc-20261021.json`)
         * read that way holds all 31 `front_drive` headings, `1BE201` alone has none. The match
         * establishes it, not a firmware rule, and only for FMCU: ISG 7E6 is not searched.
         */
        val FORMAT_01_AS_J2012: Set<Int> = setOf(0x7E3)

        /**
         * The catalog's seven characters for a [code] [request] reported; null where there is no mapping.
         * Format 00: its J2012 code without the dash, `C1170-09` → `C117009`. Format 01 of a module in
         * [FORMAT_01_AS_J2012]: the same layout over its three bytes - the letter from the first byte's top
         * two bits, the code's digits, the third byte - `1B0019` → `P1B0019`, `C1D487` → `U01D487`.
         */
        fun catalogCode(request: Int, code: DtcCode): String? = when {
            code.format == DtcFormat.SAE_J2012_00 -> J2012.compact(code.raw)
            code.format == DtcFormat.ISO_14229_1 && request in FORMAT_01_AS_J2012 -> J2012.compact(code.raw)
            else -> null
        }

        /**
         * The Chinese heading without its leading code and without the zero-width characters the
         * public page put in front of one BMS row.
         */
        fun bareTitle(displayCode: String, publicTitle: String): String {
            val t = publicTitle.trimStart { it.isWhitespace() || it in ZERO_WIDTH }
            val rest = if (t.startsWith(displayCode)) t.substring(displayCode.length) else t
            return rest.trim { it.isWhitespace() || it in ZERO_WIDTH }
        }

        /**
         * The index and its translation as rows of their JSON files, joined on subsystem, display code
         * and outline. Anything that does not join one to one - a row without a title, a title without a
         * row, an unknown section, a key twice - throws: the catalog is all of it or none.
         */
        fun join(index: List<List<String>>, ru: List<List<String>>): OemDtcIndex {
            val titles = HashMap<Triple<String, String, String>, String>()
            ru.forEach { r ->
                require(r.size == 4) { "translation row of ${r.size} columns" }
                require(titles.put(Triple(r[0], r[1], r[2]), r[3]) == null) { "translation of ${r.take(3)} twice" }
            }
            val seen = HashSet<Triple<String, String, String>>()
            val headings = index.map { r ->
                require(r.size == 4) { "index row of ${r.size} columns" }
                val key = Triple(r[0], r[1], r[2])
                require(seen.add(key)) { "index row ${r.take(3)} twice" }
                val section = requireNotNull(OemSection.of(r[0])) { "unknown section ${r[0]}" }
                val titleRu = requireNotNull(titles[key]) { "no translation of ${r.take(3)}" }
                require(titleRu.isNotBlank()) { "empty translation of ${r.take(3)}" }
                OemHeading(section, r[1], r[2], bareTitle(r[1], r[3]), titleRu)
            }
            require(seen == titles.keys) { "translations without an index row: ${titles.keys - seen}" }
            return OemDtcIndex(headings)
        }

        private const val ZERO_WIDTH = "​‌‍⁠﻿"
    }
}
