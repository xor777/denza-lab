package dev.denza.apps.feature.diagnostics.report

import dev.denza.apps.feature.diagnostics.scan.ScanJson
import dev.denza.apps.feature.diagnostics.scan.StoredScan
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** The files' names in `Download/Denza/` (architecture, 7.4). */
internal object ReportNames {
    /** The folder under the shared storage's root, as MediaStore's RELATIVE_PATH has it (with the trailing slash added there). */
    const val FOLDER = "Download/Denza"
    const val PDF = ".pdf"
    const val JSON = ".json"
    const val CSV = ".csv"

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    /** «scan-20261020-1931»: the scan the report is of, or the moment it is made when there is no scan. */
    fun base(at: ZonedDateTime): String = "scan-${STAMP.format(at)}"

    /**
     * [base], or «base-2», «base-3»…: the first whose files with every one of [extensions] are free in
     * [taken], so a second report of the same scan sits beside the first and never over it.
     */
    fun free(base: String, extensions: List<String>, taken: Set<String>): String {
        val lower = taken.map { it.lowercase() }.toSet()
        return generateSequence(1) { it + 1 }
            .map { if (it == 1) base else "$base-$it" }
            .first { b -> extensions.none { (b + it).lowercase() in lower } }
    }
}

/** Everything one «Сохранить» writes, decided before anything is written. */
internal data class ReportBundle(
    /** «scan-20261020-1931»: the writer may take the next free one. */
    val base: String,
    val document: ReportDocument,
    /** The JSON's text, when [ReportSection.RAW] was asked. */
    val json: String?,
    /** The recording to copy beside them, when [ReportSection.STREAM] was asked and there is one. */
    val stream: String?,
) {
    /** The extensions this bundle writes, PDF first. */
    val extensions: List<String>
        get() = listOfNotNull(ReportNames.PDF, ReportNames.JSON.takeIf { json != null }, ReportNames.CSV.takeIf { stream != null })
}

internal object ReportBundles {
    fun of(input: ReportInput, sections: Set<ReportSection>, scan: StoredScan?): ReportBundle = ReportBundle(
        base = ReportNames.base(input.scanAt ?: input.madeAt),
        document = ReportDocuments.of(input, sections),
        json = if (ReportSection.RAW in sections) ReportJson.encode(input, sections, scan) else null,
        stream = input.recording?.path?.takeIf { ReportSection.STREAM in sections },
    )
}

/**
 * The report's JSON (architecture, 7.4): what the PDF says as fields - the head, the summary and every
 * section asked for - then the scan itself in `last-scan.json`'s own format ([ScanJson]: each module's
 * replies as the bytes came, with their times), then the journal's excerpt where the journal gave one.
 * A program reads it; nothing in it is worded for people beyond the code words of the screens.
 */
internal object ReportJson {
    const val FORMAT = 1
    const val KIND = "denza-diagnostics-report"

    fun encode(input: ReportInput, sections: Set<ReportSection>, scan: StoredScan?): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("kind", KIND)
        root.put("car", input.car)
        root.put("vin", input.vin ?: JSONObject.NULL)
        root.put("odometerKm", input.odometerKm ?: JSONObject.NULL)
        root.put("scanAt", input.scanAt?.toOffsetDateTime()?.toString() ?: JSONObject.NULL)
        root.put("madeAt", input.madeAt.toOffsetDateTime().toString())
        root.put("parked", input.parked ?: JSONObject.NULL)
        root.put("lowVoltage", input.lowVoltage ?: JSONObject.NULL)
        root.put("sections", JSONArray(ReportSection.entries.filter { it in sections }.map { it.name }))
        root.put("summary", JSONObject().apply {
            val s = input.summary
            put("answered", s.answered)
            put("catalogued", s.catalogued)
            put("memoriesRead", s.memoriesRead)
            put("active", s.active)
            put("pending", s.pending)
            put("history", s.history)
            put("blocksWithRecords", s.blocksWithRecords)
        })
        if (ReportSection.CODES in sections) root.put("codes", JSONArray().apply {
            input.codes.forEach { c ->
                put(JSONObject().apply {
                    put("request", c.address)
                    put("block", c.block)
                    put("code", c.code)
                    put("failureType", c.failureType)
                    put("failureName", c.failureName)
                    put("description", c.description ?: JSONObject.NULL)
                    put("status", "%02X".format(c.status))
                    put("kind", c.kind.name)
                    put("firstSeen", c.firstSeen?.toString() ?: JSONObject.NULL)
                })
            }
        })
        if (ReportSection.PACK in sections) root.put("pack", JSONObject().apply {
            val p = input.pack
            put("sohPercent", p.sohPercent ?: JSONObject.NULL)
            put("socPercent", p.socPercent ?: JSONObject.NULL)
            put("cellMinMv", p.cellMinMv ?: JSONObject.NULL)
            put("cellMaxMv", p.cellMaxMv ?: JSONObject.NULL)
            put("cellSpreadMv", p.cellSpreadMv ?: JSONObject.NULL)
            put("insulationBmcKohm", p.insulationBmcKohm ?: JSONObject.NULL)
            put("insulationSecondKohm", p.insulationSecondKohm ?: JSONObject.NULL)
            put("probeMinC", p.probeMinC ?: JSONObject.NULL)
            put("probeMaxC", p.probeMaxC ?: JSONObject.NULL)
            put("fullChargeSpreadMv", p.fullChargeSpreadMv ?: JSONObject.NULL)
        })
        if (ReportSection.ENGINE in sections) root.put("engine", input.engine?.let { e ->
            JSONObject().apply {
                put("mil", e.mil ?: JSONObject.NULL)
                put("ready", e.ready ?: JSONObject.NULL)
                put("supported", e.supported ?: JSONObject.NULL)
                put("software", e.software ?: JSONObject.NULL)
                put("calibration", e.calibration ?: JSONObject.NULL)
                put("freezeFrame", e.freezeFrame?.let { frame ->
                    JSONArray().apply { frame.forEach { f -> put(JSONObject().put("name", f.name).put("value", f.value)) } }
                } ?: JSONObject.NULL)
                // Mode 06: every monitor with its exchange, every test with its nine bytes
                put("tests", e.tests?.let { t ->
                    JSONObject().apply {
                        put("passed", t.passed)
                        put("notRun", t.notRun)
                        put("out", t.out.size)
                        put("monitors", JSONArray().apply {
                            t.monitors.forEach { m ->
                                put(JSONObject().apply {
                                    put("mid", m.mid)
                                    put("name", m.name)
                                    put("word", m.word ?: JSONObject.NULL)
                                    put("exchange", JSONArray(m.exchange))
                                    put("tests", JSONArray().apply {
                                        m.tests.forEach { x ->
                                            put(JSONObject().apply {
                                                put("tid", x.tid)
                                                put("uasid", x.uasid)
                                                put("value", x.value)
                                                put("min", x.min)
                                                put("max", x.max)
                                                put("unit", x.unit)
                                                put("pass", x.pass ?: JSONObject.NULL)
                                                put("raw", x.raw)
                                            })
                                        }
                                    })
                                })
                            }
                        })
                    }
                } ?: JSONObject.NULL)
            }
        } ?: JSONObject.NULL)
        if (ReportSection.BLOCKS in sections) root.put("blocks", JSONArray().apply {
            input.blocks.forEach { b ->
                put(JSONObject().apply {
                    put("request", b.address)
                    put("abbreviation", b.abbreviation)
                    put("f194", b.f194 ?: JSONObject.NULL)
                    put("f195", b.f195 ?: JSONObject.NULL)
                    put("status", b.statusCode)
                })
            }
        })
        root.put("exchanges", JSONObject().apply {
            put("count", input.exchanges.count)
            put("durationMs", input.exchanges.durationMs ?: JSONObject.NULL)
            put("services", JSONArray(input.exchanges.services))
        })
        root.put("scan", scan?.let { JSONObject(ScanJson.encode(it)) } ?: JSONObject.NULL)
        input.journal?.excerptJson?.let { text ->
            runCatching { JSONTokener(text).nextValue() }.getOrNull()?.takeIf { it is JSONObject || it is JSONArray }?.let { root.put("journal", it) }
        }
        return root.toString(1)
    }
}

/**
 * A stream recording's length and its signals, out of its lines (architecture, 6.1: `received_ns,
 * address,pid,raw,value`): from the first line's time to the last one's, and how many distinct address
 * and PID pairs it holds. Lines that do not parse are passed over.
 */
internal object StreamCsv {
    data class Summary(val durationMs: Long?, val signals: Int)

    fun summary(lines: Sequence<String>): Summary {
        var first: Long? = null
        var last: Long? = null
        val signals = HashSet<String>()
        lines.forEach { line ->
            val parts = line.split(',')
            if (parts.size < 3) return@forEach
            val ns = parts[0].trim().toLongOrNull() ?: return@forEach
            if (first == null || ns < first!!) first = ns
            if (last == null || ns > last!!) last = ns
            signals += parts[1].trim() + "/" + parts[2].trim()
        }
        val f = first
        val l = last
        return Summary(if (f != null && l != null) (l - f) / 1_000_000L else null, signals.size)
    }
}
