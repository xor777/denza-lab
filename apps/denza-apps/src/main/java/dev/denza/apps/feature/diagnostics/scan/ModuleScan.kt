package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.DiagRequest
import dev.denza.apps.feature.diagnostics.bus.hex
import dev.denza.apps.feature.diagnostics.ecu.Ecu
import dev.denza.apps.feature.diagnostics.ecu.IdentityBasis
import dev.denza.apps.feature.diagnostics.protocol.Decoded
import dev.denza.apps.feature.diagnostics.protocol.DiagDecoder
import dev.denza.apps.feature.diagnostics.protocol.DtcFormat
import dev.denza.apps.feature.diagnostics.protocol.DtcMemory
import dev.denza.apps.feature.diagnostics.protocol.MonitorStatus
import dev.denza.apps.feature.diagnostics.protocol.ObdDtcMode
import dev.denza.apps.feature.diagnostics.protocol.ObdDtcs
import dev.denza.apps.feature.diagnostics.protocol.ObdValue
import dev.denza.apps.feature.diagnostics.protocol.Polled
import dev.denza.apps.feature.diagnostics.protocol.StatusBit
import dev.denza.apps.feature.diagnostics.protocol.VehicleInfo
import dev.denza.apps.feature.diagnostics.protocol.hexBytes
import dev.denza.apps.feature.diagnostics.protocol.map

/**
 * What one module was asked in one read and what it answered, in order. Everything the screens
 * say about the module is derived from these replies ([ModuleResult]), and the stored scan keeps
 * exactly them, so a scan read back from disk is judged by the same code as a live one.
 */
internal data class ModuleRead(val request: Int, val atMillis: Long, val replies: List<DiagReply>) {
    /** The last reply to `uds` (upper-case hex), or null when it was not asked. */
    fun reply(uds: String): DiagReply? = replies.lastOrNull { hex(it.request.uds) == uds }
}

/** One request kind of the autoscan: what the line on the screen says it asked. */
internal object ScanRequests {
    const val F194 = "22F194"
    const val ECU_NAME = "090A"
    const val PID_01 = "0101"
    fun count(mask: Int) = "1901%02X".format(mask)
    fun list(mask: Int) = "1902%02X".format(mask)
    val OBD_LISTS = listOf(ObdDtcMode.STORED to "03", ObdDtcMode.PENDING to "07", ObdDtcMode.PERMANENT to "0A")

    /**
     * The identity gate of [ecu] (architecture, 5.1), or null where the name rests on a stock route:
     * `22 F194` where the role was proved by the software code, `09 0A` where by the OBD name.
     */
    fun gate(ecu: Ecu): String? = when (ecu.identity) {
        IdentityBasis.F194, IdentityBasis.MODEL_CATALOG -> F194.takeIf { ecu.uds }
        IdentityBasis.OBD_ECU_NAME -> ECU_NAME.takeIf { ecu.expectedObdName != null && ecu.obd }
        IdentityBasis.STOCK_ROUTE, IdentityBasis.NONE -> null
    }

    /**
     * `22 F194` asked before the memory where it is no gate (architecture, 5.1, 6; owner's decision
     * 2026-10-21): a block named by a stock route whose code the update cache keeps - 720, 722, 726, 747.
     * Whatever it answers, the memory read follows; a software code feeds the code history and the
     * screens as a gate's does.
     */
    fun ungatedF194(ecu: Ecu): Boolean =
        ecu.identity == IdentityBasis.STOCK_ROUTE && ecu.uds && ecu.snapshotF194 != null
}

/**
 * The autoscan's read of one module (architecture, 6): the gate - or, at a stock-route block the cache
 * keeps a code for, `22 F194` that gates nothing ([ScanRequests.ungatedF194]) - then `19 01`, `19 02`
 * only when the count is above zero (or `19 02` alone where the catalog says `19 01` never answers),
 * then for an OBD responder `01 01`, `03`, `07`, `0A`. A gate that does not pass ends the read: on a
 * block that does not say who it is - no answer, a refusal, no software code, another OBD name -
 * nothing else is asked.
 */
internal object ModuleProcedure {
    suspend fun read(ecu: Ecu, nowMillis: () -> Long, ask: suspend (DiagRequest) -> DiagReply): ModuleRead {
        val address = requireNotNull(ecu.request) { "${ecu.abbreviation} has no route" }
        val replies = ArrayList<DiagReply>()
        suspend fun send(uds: String): DiagReply = ask(DiagRequest(address, hexBytes(uds))).also { replies += it }

        val gate = ScanRequests.gate(ecu)?.let { send(it) }
        if (gate == null && ScanRequests.ungatedF194(ecu)) send(ScanRequests.F194)
        if (gate == null || ModuleResult.gatePassed(ecu, gate)) {
            if (ecu.uds && !ecu.dtcCountAnswers) {
                // DCOBC: `19 01` is never answered, the list alone is the memory.
                send(ScanRequests.list(ecu.dtcMask))
            } else if (ecu.uds) {
                val count = send(ScanRequests.count(ecu.dtcMask))
                val n = (count as? DiagReply.Answer)?.let { (DiagDecoder.dtcCount(it) as? Decoded.Ok)?.value?.count } ?: 0
                if (n > 0) send(ScanRequests.list(ecu.dtcMask))
            }
            if (ecu.obd) {
                send(ScanRequests.PID_01)
                ScanRequests.OBD_LISTS.forEach { (_, uds) -> send(uds) }
            }
        }
        return ModuleRead(address, nowMillis(), replies)
    }
}

/** What one module's read says, decided once: identity, memory, OBD state, and the row's status. */
internal class ModuleResult(val ecu: Ecu, val read: ModuleRead) {
    private fun <T> polled(uds: String, decode: (DiagReply.Answer) -> Decoded<T>): Polled<T> =
        read.reply(uds)?.let { Polled.of(it, decode) } ?: Polled.NotPolled

    /**
     * `22 F194` as it came wherever the autoscan asked it - a gate, or the ungated read of a stock-route
     * block (5.1): the head's «ПО (F194)», the identification's row until the block's own read, the code
     * history. NotPolled where it was not asked.
     */
    val f194: Polled<String> = polled(ScanRequests.F194) { a -> DiagDecoder.identification(a).map { it.text } }

    /** The gate's reading: the F194 data as it came or the OBD name, NotPolled where there is no gate. */
    val identity: Polled<String> = when (ScanRequests.gate(ecu)) {
        ScanRequests.F194 -> f194
        ScanRequests.ECU_NAME -> polled(ScanRequests.ECU_NAME) { a -> ecuName(a) }
        else -> Polled.NotPolled
    }

    /**
     * The gate's answer names the catalog's block (5.1): a software code - any - for F194, the
     * catalog's own name for `09 0A`; false for a positive answer that is neither, null where the gate
     * gave no positive answer or there is none.
     */
    val identified: Boolean? = (identity as? Polled.Got)?.let { ScanRequests.gate(ecu)?.let(read::reply)?.let { gatePassed(ecu, it) } }

    private val gated: Boolean = ScanRequests.gate(ecu) != null

    /** The memory of the catalog's mask: count and list joined, never «0 codes» from a part. */
    val memory: Polled<DtcMemory> = run {
        val list = read.reply(ScanRequests.list(ecu.dtcMask))
        if (!ecu.dtcCountAnswers) {
            return@run list?.let { reply ->
                Polled.of(reply) { DiagDecoder.dtcMemoryFromList(it, ecu.dtcFormat?.let(DtcFormat::of)) }
            } ?: Polled.NotPolled
        }
        val count = read.reply(ScanRequests.count(ecu.dtcMask)) ?: return@run Polled.NotPolled
        if (list != null && list !is DiagReply.Answer) Polled.of(list) { Decoded.Malformed("no list") }
        else Polled.of(count) { DiagDecoder.dtcMemory(it, list as DiagReply.Answer?) }
    }

    /** PID 01: MIL, its code count and readiness since the codes were cleared. */
    val monitors: Polled<MonitorStatus> = polled(ScanRequests.PID_01) { a -> monitorStatus(a, 0x01) }

    val obdLists: Map<ObdDtcMode, Polled<ObdDtcs>> =
        ScanRequests.OBD_LISTS.associate { (mode, uds) -> mode to polled(uds) { DiagDecoder.obdDtcs(it) } }

    /** Anything came back from the module, positive or negative. */
    val answered: Boolean = read.replies.any { it is DiagReply.Answer }

    /** For a module without UDS its memory is OBD Mode 03. */
    private val obdOnly: Boolean = !ecu.uds && ecu.obd

    /** The memory is complete for what was asked. */
    val memoryRead: Boolean = if (obdOnly) obdLists[ObdDtcMode.STORED] is Polled.Got else memory is Polled.Got

    /** Records with testFailed; for an OBD-only module the stored codes. Null where nothing was read. */
    val active: Int? = when {
        obdOnly -> (obdLists[ObdDtcMode.STORED] as? Polled.Got)?.value?.codes?.size
        else -> (memory as? Polled.Got)?.value?.records?.count { StatusBit.TF in it.status }
    }

    /** Confirmed records without testFailed; none for an OBD-only module. */
    val history: Int? = if (obdOnly) null else (memory as? Polled.Got)?.value?.records
        ?.count { StatusBit.CDTC in it.status && StatusBit.TF !in it.status }

    /** The module returned records its request mask leaves out (architecture, 4): shown and flagged. */
    val outsideMask: Boolean = (memory as? Polled.Got)?.value?.let { m ->
        m.records.any { it.status.byte and m.requestMask.byte == 0 }
    } ?: false

    val mil: Boolean? = (monitors as? Polled.Got)?.value?.mil

    val status: ModuleStatus = run {
        if (gated) when (identity) {
            Polled.NotPolled, is Polled.NoReply, is Polled.Busy -> return@run ModuleStatus.NO_REPLY
            is Polled.Inconsistent -> return@run ModuleStatus.INCONSISTENT
            // A module that will not say who it is keeps its memory unread, as another module would.
            is Polled.Denied, is Polled.Unsupported -> return@run ModuleStatus.UNREAD
            is Polled.Got -> if (identified != true) return@run ModuleStatus.UNIDENTIFIED
        }
        val lit = mil == true
        val memoryState: Polled<*> = if (obdOnly) obdLists.getValue(ObdDtcMode.STORED) else memory
        when (memoryState) {
            is Polled.Got -> when {
                lit || (active ?: 0) > 0 -> ModuleStatus.ACTIVE
                (history ?: 0) > 0 -> ModuleStatus.HISTORY
                else -> ModuleStatus.OK
            }
            is Polled.Denied, is Polled.Unsupported -> ModuleStatus.REFUSED
            is Polled.Inconsistent -> ModuleStatus.INCONSISTENT
            Polled.NotPolled, is Polled.NoReply, is Polled.Busy -> when {
                lit -> ModuleStatus.ACTIVE
                answered -> ModuleStatus.UNREAD
                else -> ModuleStatus.NO_REPLY
            }
        }
    }

    companion object {
        /**
         * The gate's reply lets the block's memory be read (architecture, 5.1). F194: a positive `62 F194`
         * with a nine-byte software code that is not all zero - any code, since an update changes it while
         * the address and the role, which the factory map and the model's catalog give, stay. The OBD
         * name: exactly the catalog's, since a name does not change with software. No answer, a refusal,
         * a malformed answer or a zero placeholder does not pass.
         */
        fun gatePassed(ecu: Ecu, reply: DiagReply): Boolean {
            val answer = reply as? DiagReply.Answer ?: return false
            return when (ScanRequests.gate(ecu)) {
                ScanRequests.F194 -> DiagDecoder.softwareCode(answer) is Decoded.Ok
                ScanRequests.ECU_NAME -> (ecuName(answer) as? Decoded.Ok)?.value.let { it != null && it == ecu.expectedObdName }
                else -> false
            }
        }

        fun ecuName(answer: DiagReply.Answer): Decoded<String> = when (val d = DiagDecoder.vehicleInfo(answer)) {
            is Decoded.Ok -> (d.value as? VehicleInfo.EcuName)?.let { Decoded.Ok(it.name) } ?: Decoded.Malformed("not 49 0A")
            is Decoded.Negative -> d
            is Decoded.Malformed -> d
        }

        /** PID 01 or 41 out of a `41` answer; a PID the module left out is not offered. */
        fun monitorStatus(answer: DiagReply.Answer, pid: Int): Decoded<MonitorStatus> = when (val d = DiagDecoder.obdPids(answer)) {
            is Decoded.Ok -> (d.value[pid]?.value as? ObdValue.Monitors)?.let { Decoded.Ok(it.status) }
                ?: Decoded.Malformed("PID %02X not in the answer".format(pid))
            is Decoded.Negative -> d
            is Decoded.Malformed -> d
        }
    }
}
