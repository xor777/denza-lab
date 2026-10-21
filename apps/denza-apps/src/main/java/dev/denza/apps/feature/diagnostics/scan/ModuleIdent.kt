package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.DiagRequest
import dev.denza.apps.feature.diagnostics.ecu.ChildIdentity
import dev.denza.apps.feature.diagnostics.ecu.Ecu
import dev.denza.apps.feature.diagnostics.protocol.Decoded
import dev.denza.apps.feature.diagnostics.protocol.DiagDecoder
import dev.denza.apps.feature.diagnostics.protocol.IdentDid
import dev.denza.apps.feature.diagnostics.protocol.Identification
import dev.denza.apps.feature.diagnostics.protocol.MonitorStatus
import dev.denza.apps.feature.diagnostics.protocol.ObdPids
import dev.denza.apps.feature.diagnostics.protocol.ObdValue
import dev.denza.apps.feature.diagnostics.protocol.PidReading
import dev.denza.apps.feature.diagnostics.protocol.Polled
import dev.denza.apps.feature.diagnostics.protocol.VehicleInfo
import dev.denza.apps.feature.diagnostics.protocol.hexBytes
import dev.denza.apps.feature.diagnostics.protocol.map

/**
 * An opened module's own read, once per opening (architecture, 6, task «блок»): `22 F194/F195/F18A/F197`
 * where it speaks UDS, then the software codes of the modules behind it once its gate holds
 * (architecture, 5.4); for an OBD responder the PID maps `01 00/20/40/60/80/A0` as far as each map
 * points to the next, then `01 1C`, `01 01`, `01 41` and the odometer `01 A6` where offered, and
 * Mode 09: `09 00` first, then the calibration `09 04` and the name `09 0A` where `09 00` lists them.
 */
internal object IdentProcedure {
    /** The Mode 01 maps the identification reads; a later one a map points to is listed, not read. */
    val MAPS = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0)

    suspend fun read(ecu: Ecu, nowMillis: () -> Long, ask: suspend (DiagRequest) -> DiagReply): ModuleRead {
        val address = requireNotNull(ecu.request) { "${ecu.abbreviation} has no route" }
        val replies = ArrayList<DiagReply>()
        suspend fun send(uds: String): DiagReply = ask(DiagRequest(address, hexBytes(uds))).also { replies += it }

        if (ecu.uds) {
            val dids = IdentDid.entries.associateWith { send("22" + it.hex) }
            if (ecu.children.isNotEmpty() && childrenGate(ecu, dids.getValue(IdentDid.F194))) {
                ecu.children.forEach { send(childRequest(it)) }
            }
        }
        if (ecu.obd) {
            val offered = ArrayList<Int>()
            for (base in MAPS) {
                if (base != 0 && base !in offered) break
                val reply = send("01%02X".format(base)) as? DiagReply.Answer ?: break
                val pids = (pidReading(reply, base)?.value as? ObdValue.Supported)?.pids ?: break
                offered += pids
            }
            if (0x1C in offered) send("011C")
            send(ScanRequests.PID_01)
            if (0x41 in offered) send("0141")
            if (ODOMETER in offered) send(ODOMETER_REQUEST)
            val info = (send(INFO_TYPES) as? DiagReply.Answer)?.let { infoTypes(it) as? Decoded.Ok }?.value.orEmpty()
            if (0x04 in info) send(CALIBRATION)
            if (0x0A in info) send(ScanRequests.ECU_NAME)
        }
        return ModuleRead(address, nowMillis(), replies)
    }

    /**
     * The children are read behind a parent whose gate holds, as its memory would be (5.1): any
     * software code in its F194 where the gate is F194 - an update of the parent changes its code, not
     * the modules behind it; a parent named by a stock route has no gate, and is read as the autoscan
     * reads its memory.
     */
    private fun childrenGate(ecu: Ecu, f194: DiagReply): Boolean = when (ScanRequests.gate(ecu)) {
        null -> true
        ScanRequests.F194 -> ModuleResult.gatePassed(ecu, f194)
        else -> false
    }

    fun childRequest(child: ChildIdentity) = "22" + child.hex

    fun pidReading(answer: DiagReply.Answer, pid: Int): PidReading? =
        (DiagDecoder.obdPids(answer) as? Decoded.Ok)?.value?.get(pid)

    /** `09 00`: the info types the module lists. */
    fun infoTypes(answer: DiagReply.Answer): Decoded<List<Int>> = when (val d = DiagDecoder.vehicleInfo(answer)) {
        is Decoded.Ok -> (d.value as? VehicleInfo.Supported)?.let { Decoded.Ok(it.infoTypes) } ?: Decoded.Malformed("not 49 00")
        is Decoded.Negative -> d
        is Decoded.Malformed -> d
    }

    const val ODOMETER = 0xA6
    const val ODOMETER_REQUEST = "01A6"
    const val INFO_TYPES = "0900"
    const val CALIBRATION = "0904"
}

/** What an opened module's own read says. */
internal class IdentResult(val ecu: Ecu, val read: ModuleRead) {
    private fun <T> polled(uds: String, decode: (DiagReply.Answer) -> Decoded<T>): Polled<T> =
        read.reply(uds)?.let { Polled.of(it, decode) } ?: Polled.NotPolled

    val dids: Map<IdentDid, Polled<Identification>> =
        IdentDid.entries.associateWith { did -> polled("22" + did.hex) { DiagDecoder.identification(it) } }

    /**
     * Each child's code by its own answer; NotPolled where it was not asked (the parent's gate did not
     * hold). A reply that is not the expected form is [Polled.Inconsistent], and its bytes are in [read].
     */
    val children: List<Pair<ChildIdentity, Polled<String>>> =
        ecu.children.map { c -> c to polled(IdentProcedure.childRequest(c)) { DiagDecoder.softwareCode(it) } }

    /** Mode 01 PIDs the module offers, by its own maps, bitmap PIDs included. */
    val offered: List<Int> = IdentProcedure.MAPS.flatMap { base ->
        val answer = read.reply("01%02X".format(base)) as? DiagReply.Answer ?: return@flatMap emptyList()
        (IdentProcedure.pidReading(answer, base)?.value as? ObdValue.Supported)?.pids ?: emptyList()
    }.distinct().sorted()

    /** PID 1C as the module sent it, «0x29», or null. */
    val obdStandard: Polled<String> = polled("011C") { a ->
        when (val d = DiagDecoder.obdPids(a)) {
            is Decoded.Ok -> d.value[0x1C]?.let { Decoded.Ok("0x" + it.rawHex) } ?: Decoded.Malformed("no PID 1C")
            is Decoded.Negative -> d
            is Decoded.Malformed -> d
        }
    }

    val monitors: Polled<MonitorStatus> = polled(ScanRequests.PID_01) { ModuleResult.monitorStatus(it, 0x01) }
    val thisCycle: Polled<MonitorStatus> = polled("0141") { ModuleResult.monitorStatus(it, 0x41) }

    /** PID A6 in kilometres with one decimal, «13 199,7 км»; NotPolled where the maps do not offer it. */
    val odometer: Polled<String> = polled(IdentProcedure.ODOMETER_REQUEST) { a ->
        when (val d = DiagDecoder.obdPids(a)) {
            is Decoded.Ok -> ((d.value[IdentProcedure.ODOMETER]?.value as? ObdValue.Numbers)?.quantities?.singleOrNull())
                ?.let { Decoded.Ok("${Figures.number(it.value, it.decimals)} ${it.unit}") } ?: Decoded.Malformed("no PID A6")
            is Decoded.Negative -> d
            is Decoded.Malformed -> d
        }
    }

    /** `09 00`: the info types the module lists. */
    val infoTypes: Polled<List<Int>> = polled(IdentProcedure.INFO_TYPES) { IdentProcedure.infoTypes(it) }

    val ecuName: Polled<String> = info(0x0A, ScanRequests.ECU_NAME) { ModuleResult.ecuName(it) }
    val calibration: Polled<String> = info(0x04, IdentProcedure.CALIBRATION) { a ->
        DiagDecoder.vehicleInfo(a).map { (it as? VehicleInfo.CalibrationIds)?.ids?.joinToString(", ") ?: "" }
    }

    /**
     * One Mode 09 type: its own answer when it was asked; otherwise what `09 00` said - not listed is
     * Unsupported («не отдаёт»), no answer to `09 00` is its no answer, and nothing asked is NotPolled.
     */
    private fun info(type: Int, uds: String, decode: (DiagReply.Answer) -> Decoded<String>): Polled<String> {
        read.reply(uds)?.let { return Polled.of(it, decode) }
        return when (val types = infoTypes) {
            is Polled.Got -> if (type in types.value) Polled.NotPolled else Polled.Unsupported(null, types.atMillis)
            Polled.NotPolled -> Polled.NotPolled
            is Polled.NoReply -> types
            is Polled.Busy -> types
            is Polled.Denied -> types
            is Polled.Unsupported -> types
            is Polled.Inconsistent -> types
        }
    }

    companion object {
        /** Whether [pid] is a value the stream can ask for: a known PID, not a map or a block read once, one frame. */
        fun streamable(pid: Int): Boolean {
            val def = ObdPids.of(pid) ?: return false
            return !ObdPids.isBitmap(pid) && pid !in STATIC && 1 + def.length <= LiveStream.FRAME_DATA
        }

        /** Read once with the identification, not streamed: monitors, the standard, which O2 sensors exist, the odometer. */
        private val STATIC = setOf(0x01, 0x41, 0x1C, 0x13, IdentProcedure.ODOMETER)
    }
}
