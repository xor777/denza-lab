package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.DiagRequest
import dev.denza.apps.feature.diagnostics.bus.hex
import dev.denza.apps.feature.diagnostics.ecu.Ecu
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.protocol.CodeRecordCodec
import dev.denza.apps.feature.diagnostics.protocol.CodeRecords
import dev.denza.apps.feature.diagnostics.protocol.Decoded
import dev.denza.apps.feature.diagnostics.protocol.DiagDecoder
import dev.denza.apps.feature.diagnostics.protocol.ExtendedRecord
import dev.denza.apps.feature.diagnostics.protocol.FramePid
import dev.denza.apps.feature.diagnostics.protocol.ObdCodec
import dev.denza.apps.feature.diagnostics.protocol.ObdPids
import dev.denza.apps.feature.diagnostics.protocol.ObdTestAnswer
import dev.denza.apps.feature.diagnostics.protocol.ObdTests
import dev.denza.apps.feature.diagnostics.protocol.ObdValue
import dev.denza.apps.feature.diagnostics.protocol.Polled
import dev.denza.apps.feature.diagnostics.protocol.SnapshotRecord
import dev.denza.apps.feature.diagnostics.protocol.StatusBit
import dev.denza.apps.feature.diagnostics.protocol.hexBytes

/*
 * The reads of the owner's decision of 2026-10-23 (architecture, 5.2, 6, 7.3) beside the autoscan: the memory
 * mask each block is asked with, a code's snapshot and extended data when its screen opens, an OBD block's
 * freeze frame and test results when its screen opens. Nothing here runs on its own: the session queues each
 * read on an action of the owner's and runs it as one unit of work.
 */

/**
 * What the session remembers of each block's memory mask (architecture, 5.2): the availability its memory
 * answers last reported, and the blocks that once gave no memory to `0D`, which are read with `09` from then on
 * - kept in `last-scan.json` ([StoredScan.pendingMaskRefused]), so the next scan does not ask `0D` again.
 */
internal class PendingMasks {
    private val availability = HashMap<Int, Int>()
    private val refusedBlocks = LinkedHashSet<Int>()

    val refused: Set<Int> get() = refusedBlocks.toSet()

    /** How [ecu]'s memory is asked next: `09` where `0D` is not for it, `0D` where its availability says so, a probe where nothing is known. */
    fun plan(ecu: Ecu): MaskPlan {
        val request = ecu.request ?: return MaskPlan.STANDARD
        if (!ScanRequests.pendingMaskEligible(ecu) || request in refusedBlocks) return MaskPlan.STANDARD
        val known = availability[request] ?: return MaskPlan.PROBE
        return if (known and StatusBit.PDTC.mask != 0) MaskPlan.PENDING else MaskPlan.STANDARD
    }

    fun learn(result: ModuleResult) {
        val request = result.ecu.request ?: return
        result.availability?.let { availability[request] = it.byte }
        if (result.pendingMaskRefused) refusedBlocks += request
    }

    fun restore(refused: Set<Int>) {
        refusedBlocks += refused
    }
}

/** One exchange for the screens ([ExchangeData]). */
internal object Exchanges {
    fun of(reply: DiagReply): ExchangeData = ExchangeData(
        route = Figures.route(reply.request.address),
        request = Figures.spaced(hex(reply.request.uds)),
        answer = (reply as? DiagReply.Answer)?.let { Figures.spaced(hex(it.uds)) },
        noAnswer = when (reply) {
            is DiagReply.Answer -> null
            is DiagReply.NoReply -> if (reply.gatewayResult == 0) "нет ответа · время помощника" else "нет ответа · шлюз ${reply.gatewayResult}"
            is DiagReply.GatewayBusy -> "шлюз занят"
        },
        latencyMs = reply.latencyMs,
        atMillis = reply.atMillis,
    )

    /** What a reading that is no value says about itself: its outcome, and why where there is a why. */
    fun outcome(p: Polled<*>, empty: Boolean = false): Pair<ReadOutcome, String?> = when (p) {
        Polled.NotPolled -> ReadOutcome.NOT_READ to null
        is Polled.NoReply, is Polled.Busy -> ReadOutcome.NO_REPLY to null
        is Polled.Denied -> ReadOutcome.REFUSED to "NRC ${p.nrc.hex} · ${p.nrc.label}"
        is Polled.Unsupported -> ReadOutcome.REFUSED to (p.nrc?.let { "NRC ${it.hex} · ${it.label}" } ?: "не поддерживается")
        is Polled.Inconsistent -> ReadOutcome.INCONSISTENT to p.why
        is Polled.Got -> (if (empty) ReadOutcome.EMPTY else ReadOutcome.READ) to null
    }
}

// ---------------------------------------------------------------------- one code: 19 04, 19 06

/**
 * An opened code's reads (architecture, 6): `19 04 <DTC> FF` and `19 06 <DTC> FF`, all of the code's
 * snapshots and extended data. A record's data has no length on the wire, so where the answer to `FF` starts a
 * record with data, that record is asked alone (`19 04 <DTC> <n>`, `19 06 <DTC> <n>`): its bounds are then the
 * answer's, and what `FF` held beyond it stays raw. Two exchanges where the code has no records, four at most.
 */
internal object CodeReadProcedure {
    fun snapshots(raw: Int, record: Int) = "1904%06X%02X".format(raw, record)
    fun extended(raw: Int, record: Int) = "1906%06X%02X".format(raw, record)

    suspend fun read(ecu: Ecu, raw: Int, nowMillis: () -> Long, ask: suspend (DiagRequest) -> DiagReply): ModuleRead {
        val address = requireNotNull(ecu.request) { "${ecu.abbreviation} has no route" }
        val replies = ArrayList<DiagReply>()
        suspend fun send(uds: String): DiagReply = ask(DiagRequest(address, hexBytes(uds))).also { replies += it }

        val all = send(snapshots(raw, CodeRecordCodec.ALL)) as? DiagReply.Answer
        (all?.let { DiagDecoder.snapshots(it) } as? Decoded.Ok)?.value?.unsplitFrom?.takeIf { it != CodeRecordCodec.ALL }?.let { send(snapshots(raw, it)) }
        val extended = send(extended(raw, CodeRecordCodec.ALL)) as? DiagReply.Answer
        (extended?.let { DiagDecoder.extendedData(it) } as? Decoded.Ok)?.value?.unsplitFrom?.takeIf { it != CodeRecordCodec.ALL }?.let { send(extended(raw, it)) }
        return ModuleRead(address, nowMillis(), replies)
    }
}

/** An opened code: which one, and its reads once they came ([read] null while they are on their way). */
internal class CodeReadResult(val code: String, val raw: Int, val read: ModuleRead?) {
    val data: CodeReadsData by lazy {
        CodeReadsData(
            code = code,
            raw = "%06X".format(raw),
            snapshots = read?.let { records(it, CodeReadProcedure::snapshots, DiagDecoder::snapshots, ::snapshot) }
                ?: RecordReadData(ReadOutcome.READING),
            extended = read?.let { records(it, CodeReadProcedure::extended, DiagDecoder::extendedData, ::extended) }
                ?: RecordReadData(ReadOutcome.READING),
        )
    }

    private fun <R, T> records(
        read: ModuleRead,
        request: (Int, Int) -> String,
        decode: (DiagReply.Answer) -> Decoded<CodeRecords<R>>,
        data: (R) -> T,
    ): RecordReadData<T> {
        val allReply = read.reply(request(raw, CodeRecordCodec.ALL)) ?: return RecordReadData()
        val all = Polled.of(allReply, decode)
        val (outcome, why) = Exchanges.outcome(all, (all as? Polled.Got)?.value?.isEmpty == true)
        val got = (all as? Polled.Got)?.value ?: return RecordReadData(outcome, why = why, exchanges = listOf(Exchanges.of(allReply)))
        val oneReply = got.unsplitFrom?.takeIf { it != CodeRecordCodec.ALL }?.let { read.reply(request(raw, it)) }
        val one = oneReply?.let { Polled.of(it, decode) as? Polled.Got }?.value
        // The record asked alone is the first of what `FF` could not split: what follows it there is raw.
        val oneHex = (oneReply as? DiagReply.Answer)?.takeIf { one != null }?.let { hex(it.uds).drop(12) }
        val rest = when {
            got.unsplit.isEmpty() -> ""
            oneHex != null && got.unsplit.startsWith(oneHex) -> got.unsplit.drop(oneHex.length)
            else -> got.unsplit
        }
        return RecordReadData(
            outcome = outcome,
            status = got.status.byte,
            records = (got.records + one?.records.orEmpty()).map(data),
            rest = rest.takeIf { it.isNotEmpty() }?.let(Figures::spaced),
            exchanges = listOfNotNull(allReply, oneReply).map(Exchanges::of),
        )
    }

    private fun snapshot(r: SnapshotRecord) =
        SnapshotRecordData("%02X".format(r.number), r.identifiers, r.did?.let { "%04X".format(it) }, Figures.spaced(r.data))

    private fun extended(r: ExtendedRecord) = ExtendedRecordData("%02X".format(r.number), Figures.spaced(r.data))
}

// ---------------------------------------------------------------------- an OBD block: Mode 02 and Mode 06

/** The two reads an opened OBD block's screen makes, each its own unit of work. */
internal enum class ObdRead { FREEZE_FRAME, TESTS }

/**
 * Mode 02 and Mode 06 of an opened OBD block (architecture, 6), once per opening, at the blocks of [AT].
 *
 * The freeze frame: `02 02 00` first - the code that stored frame 0, `0000` for none, and then nothing more is
 * asked - then the frame's maps `02 00 00`, `02 20 00`, … as far as each lists the next, then its values in
 * requests of up to three PID and frame pairs, decoded as Mode 01 decodes them; a PID of unknown length, and PID
 * 56, alone. The tests: the MID maps `06 00`, `06 20`, … as far as each lists the next, then `06 <MID>` for every
 * monitor listed.
 */
internal object ObdReadProcedure {
    /** ECM 7E0: the OBD block whose screen asks for its freeze frame and tests. */
    val AT: Set<Int> = setOf(0x7E0)

    const val FRAME_DTC_REQUEST = "020200"
    const val NO_FRAME = "0000"
    private const val PAIRS = 3
    private val MAPS = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0)

    /** PID 56 has two lengths, as in the stream: asked alone. */
    private val ALONE = setOf(0x56)

    fun frameMap(base: Int) = "02%02X00".format(base)
    fun test(mid: Int) = "06%02X".format(mid)

    suspend fun read(ecu: Ecu, what: ObdRead, nowMillis: () -> Long, ask: suspend (DiagRequest) -> DiagReply): ModuleRead {
        val address = requireNotNull(ecu.request) { "${ecu.abbreviation} has no route" }
        val replies = ArrayList<DiagReply>()
        suspend fun send(uds: String): DiagReply = ask(DiagRequest(address, hexBytes(uds))).also { replies += it }
        when (what) {
            ObdRead.FREEZE_FRAME -> {
                val dtc = (send(FRAME_DTC_REQUEST) as? DiagReply.Answer)?.let(::frameDtc)
                if (dtc != null && dtc != NO_FRAME) {
                    val values = maps({ send(frameMap(it)) }, ::frameMapPids).filter { !ObdPids.isBitmap(it) && it != ObdCodec.FRAME_DTC }
                    val (packed, alone) = values.partition { ObdPids.of(it) != null && it !in ALONE }
                    packed.chunked(PAIRS).forEach { pids -> send("02" + pids.joinToString("") { "%02X00".format(it) }) }
                    alone.forEach { send("02%02X00".format(it)) }
                }
            }
            ObdRead.TESTS -> maps({ send(test(it)) }, ::testMapMids).filterNot(ObdTests::isBitmap).forEach { send(test(it)) }
        }
        return ModuleRead(address, nowMillis(), replies)
    }

    /** The maps from 00 on, as far as each lists the next; what they list together. */
    private suspend fun maps(ask: suspend (Int) -> DiagReply, listed: (Int, DiagReply.Answer) -> List<Int>?): List<Int> {
        val offered = ArrayList<Int>()
        for (base in MAPS) {
            if (base != 0 && base !in offered) break
            val answer = ask(base) as? DiagReply.Answer ?: break
            offered += listed(base, answer) ?: break
        }
        return offered
    }

    /** [pick] out of frame 0 of a `42` answer, a refusal passed on as it came; Malformed where [pick] finds nothing. */
    private fun <T> framed(answer: DiagReply.Answer, pick: (List<FramePid>) -> T?): Decoded<T> = when (val d = DiagDecoder.freezeFrame(answer)) {
        is Decoded.Ok -> pick(d.value.filter { it.frame == 0 })?.let { Decoded.Ok(it) } ?: Decoded.Malformed("not in frame 0")
        is Decoded.Negative -> d
        is Decoded.Malformed -> d
    }

    /** Frame 0's readings in a `42` answer. */
    fun frame0(answer: DiagReply.Answer): List<FramePid>? = (DiagDecoder.freezeFrame(answer) as? Decoded.Ok)?.value?.filter { it.frame == 0 }

    /** Frame 0's PID 02 in [answer]: four hex digits, [NO_FRAME] for no stored frame; null when it did not come. */
    fun frameDtc(answer: DiagReply.Answer): String? = frame0(answer)?.firstOrNull { it.reading.pid == ObdCodec.FRAME_DTC }?.reading?.rawHex

    fun frameMapPids(base: Int, answer: DiagReply.Answer): List<Int>? =
        (frame0(answer)?.firstOrNull { it.reading.pid == base }?.reading?.value as? ObdValue.Supported)?.pids

    fun testMapMids(base: Int, answer: DiagReply.Answer): List<Int>? =
        ((DiagDecoder.obdTests(answer) as? Decoded.Ok)?.value as? ObdTestAnswer.Supported)?.takeIf { it.base == base }?.mids

    /** What [read] - a [ObdRead.FREEZE_FRAME] read - says, for the screens. */
    fun freezeFrame(read: ModuleRead): FreezeFrameData {
        val dtcReply = read.reply(FRAME_DTC_REQUEST) ?: return FreezeFrameData()
        val exchanges = read.replies.map(Exchanges::of)
        val dtc = Polled.of(dtcReply) { a ->
            framed(a) { frame -> frame.firstOrNull { it.reading.pid == ObdCodec.FRAME_DTC }?.reading?.rawHex }
        }
        if (dtc !is Polled.Got) {
            val (outcome, why) = Exchanges.outcome(dtc)
            return FreezeFrameData(outcome, why = why, exchanges = exchanges)
        }
        if (dtc.value == NO_FRAME) return FreezeFrameData(ReadOutcome.EMPTY, exchanges = exchanges)
        val code = (frame0(dtcReply as DiagReply.Answer)?.first { it.reading.pid == ObdCodec.FRAME_DTC }?.reading?.value as? ObdValue.Text)?.text
        val maps = MAPS.mapNotNull { base -> read.reply(frameMap(base))?.let { base to it } }
        val offered = maps.flatMap { (base, r) -> (r as? DiagReply.Answer)?.let { frameMapPids(base, it) }.orEmpty() }
            .filter { !ObdPids.isBitmap(it) && it != ObdCodec.FRAME_DTC }
        val mapRequests = maps.map { (_, r) -> hex(r.request.uds) }.toSet() + FRAME_DTC_REQUEST
        val readings = read.replies.filter { hex(it.request.uds) !in mapRequests }
            .flatMap { (it as? DiagReply.Answer)?.let(::frame0).orEmpty() }
            .map { it.reading }
            .filter { !ObdPids.isBitmap(it.pid) && it.pid != ObdCodec.FRAME_DTC }
            .distinctBy { it.pid }
            .sortedBy { it.pid }
        val why = maps.firstOrNull()?.second?.let { first ->
            Polled.of(first) { a -> framed(a) { frame -> (frame.firstOrNull { it.reading.pid == 0 }?.reading?.value as? ObdValue.Supported)?.pids } }
        }?.let { Exchanges.outcome(it).second } ?: if (maps.isEmpty()) "карта PID кадра не прочитана" else null
        return FreezeFrameData(
            outcome = ReadOutcome.READ,
            dtc = code,
            values = readings.map { FrameValueData(it.pid, it.name, valueText(it.value, it.rawHex), Figures.spaced(it.rawHex)) },
            missing = offered.filter { pid -> readings.none { it.pid == pid } }.map { "%02X".format(it) },
            why = why,
            exchanges = exchanges,
        )
    }

    /** What [read] - a [ObdRead.TESTS] read - says, for the screens. */
    fun tests(read: ModuleRead): ObdTestsData {
        val first = read.reply(test(0)) ?: return ObdTestsData()
        val maps = MAPS.mapNotNull { base -> read.reply(test(base))?.let { base to it } }
        val exchanges = maps.map { Exchanges.of(it.second) }
        val head = Polled.of(first) { a ->
            when (val d = DiagDecoder.obdTests(a)) {
                is Decoded.Ok -> (d.value as? ObdTestAnswer.Supported)?.let { Decoded.Ok(it.mids) } ?: Decoded.Malformed("not the map of MIDs 01-20")
                is Decoded.Negative -> d
                is Decoded.Malformed -> d
            }
        }
        if (head !is Polled.Got) {
            val (outcome, why) = Exchanges.outcome(head)
            return ObdTestsData(outcome, why = why, exchanges = exchanges)
        }
        val supported = maps.flatMap { (base, r) -> (r as? DiagReply.Answer)?.let { testMapMids(base, it) }.orEmpty() }.filterNot(ObdTests::isBitmap)
        val monitors = supported.map { mid ->
            val reply = read.reply(test(mid))
            val p = reply?.let { r ->
                Polled.of(r) { a ->
                    when (val d = DiagDecoder.obdTests(a)) {
                        is Decoded.Ok -> (d.value as? ObdTestAnswer.Results)?.let { Decoded.Ok(it) } ?: Decoded.Malformed("not the tests of MID %02X".format(mid))
                        is Decoded.Negative -> d
                        is Decoded.Malformed -> d
                    }
                }
            } ?: Polled.NotPolled
            val (outcome, why) = Exchanges.outcome(p, (p as? Polled.Got)?.value?.isEmpty == true)
            ObdMidData(
                mid = "%02X".format(mid),
                name = ObdTests.name(mid),
                outcome = outcome,
                tests = (p as? Polled.Got)?.value?.tests.orEmpty().map { t ->
                    ObdTestData(
                        tid = "%02X".format(t.tid),
                        uasid = "%02X".format(t.uasid),
                        value = t.text(t.value),
                        min = t.text(t.min),
                        max = t.text(t.max),
                        pass = t.pass,
                        scaled = t.scale != null,
                        raw = Figures.spaced(t.hex),
                    )
                },
                why = why,
                exchange = reply?.let(Exchanges::of),
            )
        }
        return ObdTestsData(
            outcome = if (supported.isEmpty()) ReadOutcome.EMPTY else ReadOutcome.READ,
            supported = supported.map { "%02X".format(it) },
            monitors = monitors,
            exchanges = exchanges,
        )
    }

    /** One PID's value as a line: «1 250 об/мин», «B1 −1,6 % · B3 0,0 %», «CL · замкн. петля». */
    fun valueText(value: ObdValue, rawHex: String): String = when (value) {
        is ObdValue.Numbers -> (value.quantities.joinToString(" · ") { q -> listOfNotNull(q.label, q.text).joinToString(" ") } +
            (value.note?.let { " · $it" } ?: "")).ifEmpty { "—" }
        is ObdValue.Text -> if (value.code != null && value.note != null) "${value.code} · ${value.note}" else value.text
        is ObdValue.Supported -> value.pids.joinToString(" ") { "%02X".format(it) }
        is ObdValue.Monitors -> listOfNotNull(value.status.mil?.let { if (it) "MIL горит" else "MIL выключена" },
            value.status.dtcCount?.let { "DTC $it" }).joinToString(" · ").ifEmpty { "мониторы" }
        ObdValue.Raw -> Figures.spaced(rawHex)
        ObdValue.NoValue -> ObdValue.NoValue.TEXT
    }
}

// ---------------------------------------------------------------------- the VIN

/** The VIN of a scan's results ([ScanRequests.vin]): each source's answer, the VIN they agree on, a mismatch. */
internal object VinReads {
    fun data(results: Map<Int, ModuleResult>): VinData? {
        val sources = SOURCES.mapNotNull { request ->
            val r = results[request] ?: return@mapNotNull null
            val uds = ScanRequests.vin(r.ecu) ?: return@mapNotNull null
            val reply = r.read.reply(uds) ?: return@mapNotNull null
            val service = if (uds == ScanRequests.VIN_UDS) "22 F190" else "09 02"
            val (outcome, why) = Exchanges.outcome(r.vin, (r.vin as? Polled.Got)?.value?.isEmpty == true)
            VinSourceData(
                module = "${r.ecu.abbreviation} ${Figures.address(request)}",
                service = service,
                outcome = outcome,
                vin = (r.vin as? Polled.Got)?.value?.text?.takeIf { it.isNotEmpty() },
                why = why,
                exchange = Exchanges.of(reply),
            )
        }
        if (sources.isEmpty()) return null
        val vins = sources.mapNotNull { it.vin }
        return VinData(vin = vins.firstOrNull(), mismatch = vins.distinct().size > 1, sources = sources)
    }

    /** VCU first: its `22 F190` is the block's own record of the car; ECM's `09 02` is the legislated one. */
    private val SOURCES = listOf(0x7E2, 0x7E0)
}

// ---------------------------------------------------------------------- the session's share

/**
 * What the session keeps of these reads between exchanges, under its lock (architecture, 6): the masks, the
 * code whose screen is open and each code read so far, the OBD reads of an opened block. Each queue holds only
 * what an action of the owner's asked for; closing a screen drops what it asked and has not started.
 */
internal class SessionReads {
    val masks = PendingMasks()

    /** One opened code to read: where, as the screen names it, and its three bytes. */
    data class CodeWork(val request: Int, val code: String, val raw: Int)

    private val codes = HashMap<Int, LinkedHashMap<String, CodeReadResult>>()
    private var codeQueue: CodeWork? = null
    private val obdQueue = LinkedHashSet<Pair<Int, ObdRead>>()
    /** Per block and read: its data, null while it is asked and not answered. */
    private val obd = HashMap<Int, HashMap<ObdRead, ModuleRead?>>()
    private val obdData = HashMap<Int, Pair<FreezeFrameData, ObdTestsData>>()

    /**
     * A code's screen opened: the record of [result]'s memory it names - by [DtcRow.code] or the six raw digits -
     * is queued; false when there is no such record at a UDS block. [code] null closes it.
     */
    fun openCode(request: Int, code: String?, result: ModuleResult?): Boolean {
        if (code == null) {
            val dropped = codeQueue?.takeIf { it.request == request } ?: return false
            codeQueue = null
            if (codes[request]?.get(dropped.code)?.read == null) codes[request]?.remove(dropped.code)
            return false
        }
        val ecu = EcuCatalog.byRequest(request)?.takeIf { it.uds } ?: return false
        val record = (result?.memory as? Polled.Got)?.value?.records
            ?.firstOrNull { it.code.display == code || it.code.rawHex.equals(code, ignoreCase = true) } ?: return false
        val key = record.code.display
        codes.getOrPut(ecu.request!!) { LinkedHashMap() }[key] = CodeReadResult(key, record.code.raw, null)
        codeQueue = CodeWork(request, key, record.code.raw)
        return true
    }

    fun nextCode(): CodeWork? = codeQueue

    fun codeRead(work: CodeWork, read: ModuleRead) {
        codes.getOrPut(work.request) { LinkedHashMap() }[work.code] = CodeReadResult(work.code, work.raw, read)
        if (codeQueue == work) codeQueue = null
    }

    /** An OBD block's screen opened or closed: on opening its freeze frame and tests are queued, once. */
    fun openModule(request: Int, open: Boolean) {
        if (request !in ObdReadProcedure.AT) return
        if (open) {
            obd[request] = HashMap(ObdRead.entries.associateWith { null })
            ObdRead.entries.forEach { obdQueue += request to it }
        } else {
            obdQueue.filter { it.first == request }.forEach { (r, what) ->
                obdQueue.remove(r to what)
                obd[r]?.remove(what)
            }
        }
        refreshObd(request)
    }

    /**
     * «Прочитать снова» on «Тесты»: the tests of an opened block queued again, alone; false - and nothing - where
     * the block's screen is not open or its tests are queued or being read already.
     */
    fun rereadTests(request: Int): Boolean {
        val m = obd[request] ?: return false
        if (ObdRead.TESTS in m && m[ObdRead.TESTS] == null) return false
        m[ObdRead.TESTS] = null
        obdQueue += request to ObdRead.TESTS
        refreshObd(request)
        return true
    }

    fun nextObd(): Pair<Int, ObdRead>? = obdQueue.firstOrNull()

    fun obdRead(request: Int, what: ObdRead, read: ModuleRead) {
        obdQueue.remove(request to what)
        obd.getOrPut(request) { HashMap() }[what] = read
        refreshObd(request)
    }

    private fun refreshObd(request: Int) {
        val m = obd[request] ?: return
        fun outcome(what: ObdRead) = if (what in m) ReadOutcome.READING else ReadOutcome.NOT_READ
        obdData[request] = Pair(
            m[ObdRead.FREEZE_FRAME]?.let(ObdReadProcedure::freezeFrame) ?: FreezeFrameData(outcome(ObdRead.FREEZE_FRAME)),
            m[ObdRead.TESTS]?.let(ObdReadProcedure::tests) ?: ObdTestsData(outcome(ObdRead.TESTS)),
        )
    }

    /** Each block's code reads, by code, for the model. */
    fun codeData(): Map<Int, Map<String, CodeReadsData>> = codes.mapValues { (_, m) -> m.mapValues { it.value.data } }

    /** Each opened OBD block's freeze frame and tests, for the model. */
    fun obdData(): Map<Int, Pair<FreezeFrameData, ObdTestsData>> = HashMap(obdData)

    /** The section closed: queued reads are dropped; what came stays for the next opening only through the masks. */
    fun reset() {
        codes.clear()
        codeQueue = null
        obdQueue.clear()
        obd.clear()
        obdData.clear()
    }
}
