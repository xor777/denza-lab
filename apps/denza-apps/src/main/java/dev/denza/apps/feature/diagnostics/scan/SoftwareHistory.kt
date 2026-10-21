package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.protocol.Decoded
import dev.denza.apps.feature.diagnostics.protocol.DiagDecoder
import dev.denza.apps.feature.diagnostics.protocol.IdentDid
import dev.denza.apps.feature.diagnostics.protocol.Polled
import dev.denza.apps.feature.vehicle.JournalFile
import java.io.File
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/** Where a software code is reported (architecture, 5.5): a block's own F194, or a module's code behind it by its DID. */
internal data class SoftwareKey(val request: Int, val did: Int? = null) {
    /** «7E7», «750/F8C4»: the key in the file. */
    val id: String get() = did?.let { "%03X/%04X".format(request, it) } ?: "%03X".format(request)

    companion object {
        fun parse(id: String): SoftwareKey? {
            val parts = id.split('/')
            if (parts.size !in 1..2 || parts[0].length != 3 || (parts.size == 2 && parts[1].length != 4)) return null
            val request = parts[0].toIntOrNull(16) ?: return null
            if (parts.size == 1) return SoftwareKey(request)
            return parts[1].toIntOrNull(16)?.let { SoftwareKey(request, it) }
        }
    }
}

/** A code as the scanner remembers it: the code, its `F195` version where the block gave one, the day it was first seen. */
internal data class SoftwareSeen(val code: String, val version: String?, val since: LocalDate)

/** One update the scanner saw: what was remembered, what was read, and the day the read was made. */
internal data class SoftwareChange(
    val oldCode: String,
    val oldVersion: String?,
    val newCode: String,
    val newVersion: String?,
    val detected: LocalDate,
)

/** What the scanner remembers of one key: the last code it read, and every change it saw, oldest first. */
internal data class SoftwareRecord(val seen: SoftwareSeen, val changes: List<SoftwareChange> = emptyList()) {
    val lastChange: SoftwareChange? get() = changes.lastOrNull()
}

/** One good reading of a software code: a positive, well-formed, non-zero code, its version when one came. */
internal data class SoftwareRead(val key: SoftwareKey, val code: String, val version: String?)

/**
 * The scanner's memory of software codes on the head unit (architecture, 5.5): per block address the
 * last F194 and the F195 version where the block gives one, per module behind a parent (address and
 * DID) the last code, each with the day it was first seen, and the changes between them.
 *
 * A key the scanner never read starts from the catalog's snapshot - the code the updater's
 * `ECUVersion.json` kept on [EcuCatalog.SNAPSHOT_DATE] - where the catalog has one; that baseline
 * is not written down until something is learned against it. A read code that differs from the
 * remembered one is a change {old, new, the day it was read}, and the new code is remembered from
 * then on. A read that only adds the version to a remembered code is no change. Only good reads come
 * here ([SoftwareReads]): a failed, negative, malformed or all-zero answer is never a change.
 *
 * Immutable: [seen] returns this same instance when it learned nothing, so the caller knows when to
 * write it down.
 */
internal class SoftwareHistory(val records: Map<SoftwareKey, SoftwareRecord> = emptyMap()) {

    /** What is remembered of [key]: its record, or the catalog's snapshot. */
    fun record(key: SoftwareKey): SoftwareRecord? = records[key] ?: baseline(key)

    fun lastChange(key: SoftwareKey): SoftwareChange? = records[key]?.lastChange

    /** The history after [read] was made on [day]. */
    fun seen(read: SoftwareRead, day: LocalDate): SoftwareHistory {
        val now = record(read.key)
        val next = when {
            now == null -> SoftwareRecord(SoftwareSeen(read.code, read.version, day))
            now.seen.code == read.code && (read.version == null || read.version == now.seen.version) -> return this
            now.seen.code == read.code && now.seen.version == null -> now.copy(seen = now.seen.copy(version = read.version))
            else -> SoftwareRecord(
                SoftwareSeen(read.code, read.version, day),
                (now.changes + SoftwareChange(now.seen.code, now.seen.version, read.code, read.version, day)).takeLast(MAX_CHANGES),
            )
        }
        return SoftwareHistory(records + (read.key to next))
    }

    fun seen(reads: List<SoftwareRead>, day: LocalDate): SoftwareHistory = reads.fold(this) { h, r -> h.seen(r, day) }

    override fun equals(other: Any?): Boolean = other is SoftwareHistory && other.records == records
    override fun hashCode(): Int = records.hashCode()
    override fun toString(): String = "SoftwareHistory($records)"

    companion object {
        /** Changes kept per key; older ones go first. */
        const val MAX_CHANGES = 32

        /** The catalog's snapshot of [key], seen on [EcuCatalog.SNAPSHOT_DATE] with no version. */
        fun baseline(key: SoftwareKey): SoftwareRecord? {
            val ecu = EcuCatalog.byRequest(key.request) ?: return null
            val code = if (key.did == null) ecu.snapshotF194 else ecu.children.firstOrNull { it.did == key.did }?.snapshotCode
            return code?.let { SoftwareRecord(SoftwareSeen(it, null, EcuCatalog.SNAPSHOT_DATE)) }
        }
    }
}

/** The good software readings of one read: only a positive, well-formed, non-zero code is one. */
internal object SoftwareReads {
    /** The autoscan's: its `22 F194`, the gate or the ungated read of a stock-route block (5.1), where it brought a code. */
    fun of(result: ModuleResult): List<SoftwareRead> {
        val code = code(result.read.reply(ScanRequests.F194)) ?: return emptyList()
        return listOf(SoftwareRead(SoftwareKey(result.read.request), code, null))
    }

    /** An opened block's: its F194 with its F195 version, and every code of a module behind it. */
    fun of(ident: IdentResult): List<SoftwareRead> {
        val request = ident.read.request
        val own = code(ident.read.reply("22" + IdentDid.F194.hex))?.let { SoftwareRead(SoftwareKey(request), it, version(ident)) }
        val children = ident.children.mapNotNull { (child, p) ->
            (p as? Polled.Got)?.let { SoftwareRead(SoftwareKey(request, child.did), it.value, null) }
        }
        return listOfNotNull(own) + children
    }

    private fun code(reply: DiagReply?): String? =
        ((reply as? DiagReply.Answer)?.let(DiagDecoder::softwareCode) as? Decoded.Ok)?.value

    /** `F195` as hex where it came positive and is not empty or all zero. */
    private fun version(ident: IdentResult): String? =
        (ident.dids[IdentDid.F195] as? Polled.Got)?.value?.text?.takeIf { v -> v.isNotEmpty() && v.any { it != '0' } }
}

/** Where the code history lives between openings (architecture, 5.5). */
internal interface SoftwareStore {
    fun load(): SoftwareHistory
    fun save(history: SoftwareHistory)
}

/** A [SoftwareStore] in memory, for a session without files and for the tests; [saves] counts the writes. */
internal class MemorySoftwareStore(var history: SoftwareHistory = SoftwareHistory()) : SoftwareStore {
    var saves = 0
        private set
    override fun load(): SoftwareHistory = history
    override fun save(history: SoftwareHistory) {
        saves++
        this.history = history
    }
}

/**
 * `software-codes.json`: one entry per key, its remembered code and every change, days as ISO dates.
 * An entry that does not parse - a key, a code that is not nine bytes of hex, a day - is left out,
 * and a file that does not parse at all is an empty history: the catalog's snapshot again.
 */
internal object SoftwareJson {
    private const val FORMAT = 1
    private val CODE = Regex("[0-9A-F]{18}")
    private val VERSION = Regex("[0-9A-F]+")

    fun encode(history: SoftwareHistory): String {
        val codes = JSONArray()
        history.records.entries.sortedWith(compareBy({ it.key.request }, { it.key.did ?: -1 })).forEach { (key, r) ->
            codes.put(JSONObject().apply {
                put("key", key.id)
                put("code", r.seen.code)
                r.seen.version?.let { put("version", it) }
                put("since", r.seen.since.toString())
                put("changes", JSONArray().apply {
                    r.changes.forEach { c ->
                        put(JSONObject().apply {
                            put("detected", c.detected.toString())
                            put("oldCode", c.oldCode)
                            c.oldVersion?.let { put("oldVersion", it) }
                            put("newCode", c.newCode)
                            c.newVersion?.let { put("newVersion", it) }
                        })
                    }
                })
            })
        }
        return JSONObject().apply {
            put("format", FORMAT)
            put("codes", codes)
        }.toString(1)
    }

    fun decode(text: String): SoftwareHistory? = runCatching {
        val root = JSONObject(text)
        if (root.getInt("format") != FORMAT) return null
        val codes = root.getJSONArray("codes")
        SoftwareHistory((0 until codes.length()).mapNotNull { i -> entry(codes.getJSONObject(i)) }.toMap())
    }.getOrNull()

    private fun entry(o: JSONObject): Pair<SoftwareKey, SoftwareRecord>? = runCatching {
        val key = requireNotNull(SoftwareKey.parse(o.getString("key")))
        val changes = o.optJSONArray("changes") ?: JSONArray()
        key to SoftwareRecord(
            SoftwareSeen(code(o.getString("code")), version(o.optString("version")), LocalDate.parse(o.getString("since"))),
            (0 until changes.length()).map { changes.getJSONObject(it) }.map { c ->
                SoftwareChange(
                    oldCode = code(c.getString("oldCode")), oldVersion = version(c.optString("oldVersion")),
                    newCode = code(c.getString("newCode")), newVersion = version(c.optString("newVersion")),
                    detected = LocalDate.parse(c.getString("detected")),
                )
            },
        )
    }.getOrNull()

    private fun code(s: String): String = s.also { require(CODE.matches(it)) { "not a software code: $it" } }
    private fun version(s: String): String? = s.ifEmpty { null }?.also { require(VERSION.matches(it)) { "not a version: $it" } }
}

/** [SoftwareStore] as `<dir>/software-codes.json`, replaced whole so a power cut leaves the previous one. */
internal class FileSoftwareStore(private val dir: File) : SoftwareStore {
    private val file get() = File(dir, FILE)

    override fun load(): SoftwareHistory =
        runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()?.let(SoftwareJson::decode) ?: SoftwareHistory()

    override fun save(history: SoftwareHistory) {
        dir.mkdirs()
        check(JournalFile.replace(file, SoftwareJson.encode(history))) { "$FILE not written" }
    }

    companion object {
        const val FILE = "software-codes.json"
    }
}
