package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.DiagRequest
import dev.denza.apps.feature.diagnostics.bus.hex
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.protocol.hexBytes
import dev.denza.apps.feature.vehicle.JournalFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * A finished autoscan: when it ended and every module's replies, in catalog order, and the blocks that gave no
 * memory to mask `0D`, read with `09` from then on (architecture, 5.2).
 */
internal data class StoredScan(
    val finishedAtMillis: Long,
    val reads: List<ModuleRead>,
    val pendingMaskRefused: Set<Int> = emptySet(),
)

/** Where the last scan lives between openings (architecture, 6). */
internal interface ScanStore {
    fun load(): StoredScan?
    fun save(scan: StoredScan)
}

/**
 * `last-scan.json`: the time, each module's outcome as words for whoever opens the file, and the
 * raw bytes the outcome came from. Only the bytes are read back: the outcome is judged again by
 * [ModuleResult], so a file never says more than its replies do. A file that does not parse, or
 * holds a request the whitelist refuses, is no scan at all.
 */
internal object ScanJson {
    private const val FORMAT = 1

    fun encode(scan: StoredScan): String {
        val modules = JSONArray()
        scan.reads.forEach { read ->
            val ecu = EcuCatalog.byRequest(read.request)
            val result = ecu?.let { ModuleResult(it, read) }
            modules.put(JSONObject().apply {
                put("request", Figures.address(read.request))
                put("abbreviation", ecu?.abbreviation ?: "")
                put("status", result?.status?.name ?: "")
                result?.active?.let { put("active", it) }
                result?.history?.let { put("history", it) }
                result?.pending?.let { put("pending", it) }
                put("atMillis", read.atMillis)
                put("exchanges", JSONArray().apply { read.replies.forEach { put(reply(it)) } })
            })
        }
        return JSONObject().apply {
            put("format", FORMAT)
            put("finishedAtMillis", scan.finishedAtMillis)
            put("modules", modules)
            put("pendingMaskRefused", JSONArray().apply { scan.pendingMaskRefused.sorted().forEach { put(Figures.address(it)) } })
        }.toString(1)
    }

    fun decode(text: String): StoredScan? = runCatching {
        val root = JSONObject(text)
        if (root.getInt("format") != FORMAT) return null
        val modules = root.getJSONArray("modules")
        val reads = (0 until modules.length()).map { i ->
            val m = modules.getJSONObject(i)
            val address = m.getString("request").toInt(16)
            val exchanges = m.getJSONArray("exchanges")
            ModuleRead(address, m.getLong("atMillis"), (0 until exchanges.length()).map { reply(address, exchanges.getJSONObject(it)) })
        }
        val refused = root.optJSONArray("pendingMaskRefused")
        StoredScan(
            root.getLong("finishedAtMillis"),
            reads,
            (0 until (refused?.length() ?: 0)).map { refused!!.getString(it).toInt(16) }.toSet(),
        )
    }.getOrNull()

    private fun reply(r: DiagReply): JSONObject = JSONObject().apply {
        put("uds", hex(r.request.uds))
        put("timeoutMs", r.request.timeoutMs)
        when (r) {
            is DiagReply.Answer -> {
                put("answer", hex(r.uds))
                put("frames", r.frames)
            }
            is DiagReply.NoReply -> put("noReply", r.gatewayResult)
            is DiagReply.GatewayBusy -> put("busy", true)
        }
        put("atMillis", r.atMillis)
        put("latencyMs", r.latencyMs)
        put("receivedNanos", r.receivedNanos)
    }

    private fun reply(address: Int, o: JSONObject): DiagReply {
        val request = DiagRequest(address, hexBytes(o.getString("uds")), o.optInt("timeoutMs", DiagRequest.DEFAULT_TIMEOUT_MS))
        val at = o.getLong("atMillis")
        val latency = o.optInt("latencyMs")
        val received = o.optLong("receivedNanos")
        return when {
            o.has("answer") -> DiagReply.Answer(request, hexBytes(o.getString("answer")), o.optInt("frames", 1), at, latency, received)
            o.has("noReply") -> DiagReply.NoReply(request, o.getInt("noReply"), at, latency, received)
            o.optBoolean("busy") -> DiagReply.GatewayBusy(request, at, latency, received)
            else -> throw IllegalArgumentException("an exchange without an outcome")
        }
    }
}

/** [ScanStore] as `<dir>/last-scan.json`, replaced whole so a power cut leaves the previous scan. */
internal class FileScanStore(private val dir: File) : ScanStore {
    private val file get() = File(dir, FILE)

    override fun load(): StoredScan? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()?.let(ScanJson::decode)

    override fun save(scan: StoredScan) {
        dir.mkdirs()
        JournalFile.replace(file, ScanJson.encode(scan))
    }

    companion object {
        const val FILE = "last-scan.json"
    }
}
