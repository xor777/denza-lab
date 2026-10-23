package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.protocol.DtcCode
import dev.denza.apps.feature.diagnostics.protocol.DtcStatus
import dev.denza.apps.feature.diagnostics.protocol.ObdDtcMode
import dev.denza.apps.feature.diagnostics.protocol.OemDtcIndex
import dev.denza.apps.feature.diagnostics.protocol.Polled
import dev.denza.apps.feature.diagnostics.protocol.StatusBit

/**
 * One record of a block as the v4 screens count it (architecture, 7.3): its code as [DtcRow.code] prints it,
 * its mark, and where it came from. «Сводка», «Блоки», the rail, the code's screen and «Журнал» all count
 * these, so a figure on one is the figure on the others.
 */
internal data class BlockRecord(
    val request: Int,
    /** «U0121-87»; an OBD list's «P0420». */
    val code: String,
    val mark: Mark,
    /** The UDS record's code; null for an OBD list's. */
    val dtc: DtcCode?,
    /** The ISO 14229-1 status byte and the block's availability mask; null for an OBD list's code. */
    val status: Int?,
    val availability: Int?,
    /** «19 02 0D», «Mode 07». */
    val source: String,
) {
    /** The failure type byte of a J2012 code; null for a format-01 code or an OBD list's. */
    val failureType: Int? get() = dtc?.failureType

    /** A message lost between blocks: «СЕТЬ», U…-87. */
    val network: Boolean get() = code.startsWith("U") && failureType == LOST_MESSAGE

    companion object {
        const val LOST_MESSAGE = 0x87
    }
}

internal object BlockRecords {
    /**
     * [result]'s records, in the memory's order: the UDS memory where it was read whole; at an OBD-only block
     * its Mode 03 and 0A codes, active as its row counts them, and Mode 07's, pending; at a UDS block that also
     * speaks OBD, Mode 07's codes its memory does not hold, pending (architecture, 5.2), as
     * [ModuleResult.pending] counts them. Nothing for a memory not read.
     */
    fun of(result: ModuleResult): List<BlockRecord> {
        val request = result.read.request
        val obdOnly = !result.ecu.uds && result.ecu.obd
        val out = ArrayList<BlockRecord>()
        val memory = (result.memory as? Polled.Got)?.value
        if (!obdOnly && memory != null) {
            val source = "19 02 %02X".format(memory.requestMask.byte)
            memory.records.forEach { r ->
                out += BlockRecord(request, r.code.display, mark(r.status), r.code, r.status.byte, memory.availability.byte, source)
            }
        }
        if (!result.memoryRead) return out
        val lists = ScanRequests.OBD_LISTS.mapNotNull { (mode, uds) ->
            (result.obdLists[mode] as? Polled.Got)?.value?.codes?.let { Triple(mode, uds, it) }
        }
        if (obdOnly) {
            val seen = LinkedHashMap<String, BlockRecord>()
            lists.forEach { (mode, uds, codes) ->
                val mark = if (mode == ObdDtcMode.PENDING) Mark.PENDING else Mark.ACTIVE
                codes.forEach { code ->
                    val before = seen[code]
                    if (before == null || mark.ordinal < before.mark.ordinal) {
                        seen[code] = BlockRecord(request, code, mark, null, null, null, "Mode $uds")
                    }
                }
            }
            out += seen.values
        } else if (result.ecu.obd) {
            val bases = memory?.records.orEmpty().mapNotNull { it.code.base }.toSet()
            lists.filter { it.first == ObdDtcMode.PENDING }.forEach { (_, uds, codes) ->
                codes.distinct().filter { it !in bases }.forEach { out += BlockRecord(request, it, Mark.PENDING, null, null, null, "Mode $uds") }
            }
        }
        return out
    }

    /** A record's mark by its status byte (architecture, 5.2): testFailed, then pending, then everything else in history. */
    fun mark(status: DtcStatus): Mark = when {
        StatusBit.TF in status -> Mark.ACTIVE
        StatusBit.PDTC in status -> Mark.PENDING
        else -> Mark.HISTORY
    }

    /** The worst of [marks], or null for none. */
    fun worst(marks: Iterable<Mark>): Mark? = marks.minByOrNull { it.ordinal }

    /** A block's mark: its worst record, active where its MIL is on; null for a block with nothing to say. */
    fun blockMark(result: ModuleResult?, records: List<BlockRecord>): Mark? =
        if (result?.mil == true) Mark.ACTIVE else worst(records.map { it.mark })

    /** The median answered exchange of [replies], ms; null with none. */
    fun medianMs(replies: List<DiagReply>): Int? = JournalEntries.medianMs(replies)
}

/**
 * What a record means, as the screens say it (architecture, 7.3): the heading the manual's catalog gives the
 * code in its own block's section ([DtcRow.description]); for a generic SAE code the heading of the same base
 * code in any section of the catalog (LA_BCM's `U0121-87` is the BMS section's «Неисправность связи с ABS»);
 * else nothing, and the screens say the failure type.
 */
internal object Meanings {
    fun of(catalog: OemDtcIndex, record: BlockRecord, description: String?): String? {
        if (description != null) return description
        val base = record.dtc?.base ?: record.code.takeIf { it.length == 5 && it[0] in "PCBU" } ?: return null
        if (!generic(base)) return null
        return catalog.anySection(base).takeIf { it.isNotEmpty() }?.joinToString(" / ")
    }

    /** A J2012 code SAE defines for every maker: the second character 0, and P2. */
    fun generic(base: String): Boolean = base.getOrNull(1) == '0' || (base[0] == 'P' && base.getOrNull(1) == '2')

    /** «неисправность связи с ABS»: a heading inside a sentence; an abbreviation at its start keeps its case. */
    fun lower(text: String): String =
        if (text.length > 1 && text[0].isUpperCase() && text[1].isLowerCase()) text[0].lowercaseChar() + text.substring(1) else text

    /**
     * The block a lost-communication meaning names, «связи с ABS» → ESP/IPB 782: a catalogued block's own
     * abbreviation, or the brake controller by the functions SAE names it after (ABS, ESP, ESC). Null where the
     * meaning names none.
     */
    fun addressee(meaning: String?): Pair<String, Int>? {
        val name = meaning?.let { LINK.find(it) }?.groupValues?.get(1) ?: return null
        val request = ALIASES[name] ?: EcuCatalog.all.firstOrNull { it.abbreviation == name }?.request ?: return null
        return name to request
    }

    private val LINK = Regex("""связ\p{L}* с (?:блоком )?([A-Z][A-Z0-9_/-]+)""")
    private val ALIASES = mapOf("ABS" to 0x782, "ESP" to 0x782, "ESC" to 0x782, "IPB" to 0x782)
}
