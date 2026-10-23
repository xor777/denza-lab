package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.protocol.DtcFormat

/**
 * One record on its own screen (architecture, 7.3; boards `scanner-code`, `-code-plain`): what it means,
 * its status byte, how it was read, the block it lost, the same failure type elsewhere, its life in the
 * journal, and the reads opening it made - its snapshot and counters (`19 04`, `19 06`), or an OBD block's
 * freeze frame (Mode 02). Pure, out of [V4Inputs].
 */
internal object CodeBuilder {
    fun code(i: V4Inputs, request: Int, code: String): CodeView? {
        val ecu = EcuCatalog.byRequest(request) ?: return null
        val result = i.s.results[request] ?: return null
        val records = i.records[request].orEmpty()
        val record = records.firstOrNull { it.code == code || it.dtc?.rawHex.equals(code, ignoreCase = true) } ?: return null
        val meaning = i.meaning(record)
        val life = i.life(record)
        val addressee = if (record.code.startsWith("U")) Meanings.addressee(meaning) else null
        val status = record.status ?: if (record.mark == Mark.PENDING) 0x04 else 0x08
        val availability = record.availability ?: status
        val all = EcuCatalog.routed.flatMap { e -> i.records[e.request].orEmpty() }
        val same = record.failureType?.let { t -> all.filter { it.failureType == t && !(it.request == request && it.code == record.code) } }.orEmpty()
        return CodeView(
            request = request,
            code = record.code,
            aside = "· ${ecu.abbreviation} ${Figures.address(request)} · ${ModelBuilder.name(ecu)}",
            line = line(record, meaning),
            counters = listOfNotNull(
                HeadCounter("СТАТУС", record.dtc?.let { "0x%02X".format(status) } ?: record.source, on = record.mark == Mark.ACTIVE),
                life?.let { HeadCounter("ВПЕРВЫЕ", i.clock.date(it.firstAtMillis)) },
                life?.let { HeadCounter("В СКАНАХ", "${it.seenIn} из ${it.readIn}") },
            ),
            meaning = meaning ?: record.dtc?.let { ModelBuilder.failureName(it) } ?: "Код OBD · ${record.source}",
            meaningNote = when {
                addressee != null -> "${addressee.first} в этой машине — ${EcuCatalog.byRequest(addressee.second)!!.abbreviation} ${Figures.address(addressee.second)}"
                meaning == null -> record.dtc?.let { ModelBuilder.codeGroup(it) } ?: record.code.takeIf { it.length == 5 }?.let(ModelBuilder::codeGroup)
                else -> null
            },
            status = StatusByteView(
                head = "СТАТУС · 0x%02X = %s".format(status, binary(status)),
                byte = status,
                availability = availability,
                words = statusWords(status, availability),
                legend = record.availability?.let { a ->
                    (if (a and 0xFF != 0xFF) "«·» — блок этот бит не сообщает · " else "") + "доступность 0x%02X".format(a)
                },
            ),
            raw = raw(result, record),
            addressee = addressee?.let { (label, at) -> addresseeView(i, label, at) },
            sameTypeTitle = "ТОТ ЖЕ ТИП ОТКАЗА · " + (record.failureType?.let { "%02X".format(it) } ?: "—"),
            sameType = same.map { r ->
                val e = EcuCatalog.byRequest(r.request)!!
                CodeRef(r.request, r.code, "${e.abbreviation} ${Figures.address(r.request)}", i.sinceText(i.since(r)))
            },
            sameTypeNote = same.takeIf { it.isNotEmpty() }?.let { s ->
                val n = s.size + 1
                val blocks = (s.map { it.request } + request).distinct().size
                val what = if (record.network) "${Words.plural(n, "код", "кода", "кодов")} потери связи"
                    else "${Words.plural(n, "код", "кода", "кодов")} с типом %02X".format(record.failureType)
                "$n $what в $blocks ${if (blocks == 1) "блоке" else "блоках"}"
            },
            timeline = life?.let { timeline(i, it) },
            snapshot = snapshot(i, request, record),
            extended = extended(i, request, record),
            footnote = "скан ${i.clock.datedSeconds(result.read.atMillis)}",
        )
    }

    /** «SAE J2012 · U — сеть · 0121 — неисправность связи с ABS · 87 — нет сообщения». */
    private fun line(record: BlockRecord, meaning: String?): String {
        val dtc = record.dtc
        if (dtc != null && dtc.base == null) {
            return listOf(
                if (dtc.format == DtcFormat.ISO_14229_1) "ISO 14229-1" else "формат %02X".format(dtc.formatId and 0xFF),
                dtc.rawHex,
                ModelBuilder.failureName(dtc).replaceFirstChar { it.lowercase() },
            ).joinToString(" · ")
        }
        val base = dtc?.base ?: record.code.take(5)
        val digits = base.substring(1)
        val owner = when {
            meaning != null -> Meanings.lower(meaning)
            Meanings.generic(base) -> "общий код SAE"
            else -> "код производителя"
        }
        return listOfNotNull(
            "SAE J2012",
            "${base[0]} — ${LETTERS[base[0]] ?: "?"}",
            "$digits — $owner",
            dtc?.failureType?.let { t -> "%02X — %s".format(t, dtc.failureTypeName ?: "тип не назван в SAE J2012-DA") },
        ).joinToString(" · ")
    }

    private val LETTERS = mapOf('P' to "силовая установка", 'C' to "шасси", 'B' to "кузов", 'U' to "сеть")

    /** «0000 1000». */
    fun binary(v: Int): String = Integer.toBinaryString(v and 0xFF).padStart(8, '0').let { it.take(4) + " " + it.drop(4) }

    /** What is set of what the block reports, «сейчас (не) активен» last (the board's words). */
    fun statusWords(status: Int, availability: Int): String {
        fun has(bit: Int) = (availability shr bit) and 1 == 1
        fun on(bit: Int) = has(bit) && (status shr bit) and 1 == 1
        return listOfNotNull(
            "подтверждён".takeIf { on(3) },
            "ожидающий".takeIf { on(2) },
            "был отказ после сброса".takeIf { on(5) },
            "запрос лампы".takeIf { on(7) },
            if (has(0)) (if (on(0)) "сейчас активен" else "сейчас не активен") else null,
        ).joinToString(" · ")
    }

    /** The memory's exchanges, the record lit inside a long answer: «19 02 09 → 59 02 09 · … · C1 21 87 08 · …». */
    private fun raw(result: ModuleResult, record: BlockRecord): List<RawLine> {
        val asks = if (record.dtc == null) {
            listOfNotNull(ScanRequests.OBD_LISTS.firstOrNull { "Mode ${it.second}" == record.source }?.second)
        } else {
            listOf(ScanRequests.count(result.memoryMask), ScanRequests.list(result.memoryMask))
        }
        return asks.mapNotNull { uds -> result.read.reply(uds) }.map { rawLine(it, record.dtc?.rawHex) }
    }

    fun rawLine(reply: DiagReply, record: String?): RawLine {
        val e = Exchanges.of(reply)
        val lead = "${e.request} → "
        val answer = e.answer ?: return RawLine(lead + (e.noAnswer ?: "нет ответа"))
        val bytes = answer.split(" ")
        val rec = record?.chunked(2)
        var at = -1
        if (rec != null) {
            for (k in 2 until bytes.size - 3) if (bytes.subList(k, k + 3) == rec) { at = k; break }
        }
        if (at < 0) return RawLine(lead + answer)
        val before = bytes.take(minOf(3, at)).joinToString(" ") + if (at > 3) " · … · " else " "
        val lit = bytes.subList(at, at + 4).joinToString(" ")
        val after = if (at + 4 < bytes.size) " · …" else ""
        val start = lead.length + before.length
        return RawLine(lead + before + lit + after, start until start + lit.length)
    }

    private fun addresseeView(i: V4Inputs, label: String, request: Int): AddresseeView {
        val ecu = EcuCatalog.byRequest(request)!!
        val result = i.s.results[request]
        val records = i.records[request].orEmpty()
        val state = when {
            result == null -> "не опрашивался"
            !result.answered -> "сейчас не отвечает"
            else -> listOf(
                "сейчас отвечает",
                when {
                    !result.memoryRead -> "память не прочитана"
                    records.isEmpty() -> "записей нет"
                    else -> "записей ${records.size}"
                },
                BlockRecords.medianMs(result.read.replies)?.let { "$it мс" } ?: "—",
            ).joinToString(" · ")
        }
        return AddresseeView(label, request, ecu.abbreviation, Figures.address(request), ModelBuilder.name(ecu), state,
            BlockRecords.blockMark(result, records))
    }

    private fun timeline(i: V4Inputs, life: JournalCodeLife): CodeTimelineView {
        val axis = JournalAxis(i.s.journal, i.clock)
        val n = life.readIn
        val scans = Words.plural(n, "скана", "сканов", "сканов")
        return CodeTimelineView(
            days = axis.days,
            seen = life.seenAt.map(axis::at),
            line = "впервые ${i.clock.dated(life.firstAtMillis)} · " +
                if (life.seenIn == n) "в каждом из $n $scans" else "в ${life.seenIn} из $n $scans",
        )
    }

    // ------------------------------------------------------------------ the reads opening it made

    private fun snapshot(i: V4Inputs, request: Int, record: BlockRecord): ReadSection {
        if (record.dtc == null) return freezeFrame(i, request, record)
        val data = i.modules[request]?.codeReads?.get(record.code)?.snapshots ?: RecordReadData()
        val first = data.records.firstOrNull()
        return section(
            title = "СТОП-КАДР · 19 04" + (first?.let { " · ЗАПИСЬ ${it.number}" } ?: ""),
            data = data,
            rows = data.records.filter { it.did != null }.map { Field("DID ${it.did}", it.data.ifEmpty { "—" }) },
            raw = data.records.filter { it.did == null }.map { "запись ${it.number} · ${it.identifiers} DID · ${it.data}" },
            empty = "записи нет",
        )
    }

    private fun extended(i: V4Inputs, request: Int, record: BlockRecord): ReadSection {
        if (record.dtc == null) return ReadSection("СЧЁТЧИКИ", ReadState.NOT_READ, emptyList(), word = "у кода OBD их нет")
        val data = i.modules[request]?.codeReads?.get(record.code)?.extended ?: RecordReadData()
        return section(
            title = "СЧЁТЧИКИ · 19 06",
            data = data,
            rows = data.records.map { Field("запись ${it.number}", it.data.ifEmpty { "—" }) },
            raw = emptyList(),
            empty = "записей нет",
        )
    }

    /** Rows where the records decode, the rest raw; with nothing to show, the word and the exchange as it went. */
    private fun <T> section(title: String, data: RecordReadData<T>, rows: List<Field>, raw: List<String>, empty: String): ReadSection {
        val state = state(data.outcome)
        val rest = listOfNotNull(data.rest?.let { "остаток · $it" })
        val shown = rows.isNotEmpty() || raw.isNotEmpty()
        return ReadSection(
            title = title,
            state = state,
            rows = rows,
            raw = raw + rest + if (shown) emptyList() else data.exchanges.map(::exchangeLine),
            word = if (shown) null else word(state, data.why, empty),
        )
    }

    private fun freezeFrame(i: V4Inputs, request: Int, record: BlockRecord): ReadSection {
        val frame = i.modules[request]?.obd?.freezeFrame ?: FreezeFrameData()
        val state = state(frame.outcome)
        val other = frame.dtc?.takeIf { it != record.code }
        return ReadSection(
            title = "СТОП-КАДР · Mode 02" + (frame.dtc?.let { " · $it" } ?: ""),
            state = state,
            rows = if (other == null) frame.values.map { Field(it.name, it.value) } else emptyList(),
            raw = if (frame.values.isEmpty() || other != null) frame.exchanges.take(1).map(::exchangeLine)
                else listOfNotNull(frame.missing.takeIf { it.isNotEmpty() }?.let { "без значения · PID ${it.joinToString(" ")}" }),
            word = when {
                other != null -> "кадр сохранён кодом $other"
                frame.values.isEmpty() -> word(state, frame.why, empty = "кадра нет")
                else -> null
            },
        )
    }

    private fun state(o: ReadOutcome): ReadState = when (o) {
        ReadOutcome.NOT_READ -> ReadState.NOT_READ
        ReadOutcome.READING -> ReadState.READING
        ReadOutcome.EMPTY -> ReadState.EMPTY
        ReadOutcome.READ -> ReadState.READ
        ReadOutcome.REFUSED -> ReadState.REFUSED
        ReadOutcome.NO_REPLY -> ReadState.NO_REPLY
        ReadOutcome.INCONSISTENT -> ReadState.INCONSISTENT
    }

    private fun word(state: ReadState, why: String?, empty: String): String = when (state) {
        ReadState.NOT_READ -> "не читали"
        ReadState.READING -> "читаю…"
        ReadState.EMPTY, ReadState.READ -> empty
        ReadState.REFUSED -> listOfNotNull("блок не поддерживает", why).joinToString(" · ")
        ReadState.NO_REPLY -> "нет ответа"
        ReadState.INCONSISTENT -> listOfNotNull("ответ не сходится", why).joinToString(" · ")
    }

    /** «19 04 51 70 09 FF → 59 04 51 70 09 08». */
    fun exchangeLine(e: ExchangeData): String = "${e.request} → ${e.answer ?: e.noAnswer ?: "нет ответа"}"
}
