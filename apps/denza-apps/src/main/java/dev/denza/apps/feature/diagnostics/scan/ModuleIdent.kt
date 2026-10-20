package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.DiagRequest
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
 * where it speaks UDS; for an OBD responder the PID maps `01 00/20/40/60` as far as each map points
 * to the next, then `01 1C`, `01 01` and `01 41` where offered; and for the OBD-only brake interface,
 * which has no UDS identity, its name and calibration (`09 0A`, `09 04`).
 */
internal object IdentProcedure {
    private val MAPS = listOf(0x00, 0x20, 0x40, 0x60)

    suspend fun read(ecu: Ecu, nowMillis: () -> Long, ask: suspend (DiagRequest) -> DiagReply): ModuleRead {
        val address = requireNotNull(ecu.request) { "${ecu.abbreviation} has no route" }
        val replies = ArrayList<DiagReply>()
        suspend fun send(uds: String): DiagReply = ask(DiagRequest(address, hexBytes(uds))).also { replies += it }

        if (ecu.uds) IdentDid.entries.forEach { send("22" + it.hex) }
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
            if (!ecu.uds) {
                send(ScanRequests.ECU_NAME)
                send("0904")
            }
        }
        return ModuleRead(address, nowMillis(), replies)
    }

    fun pidReading(answer: DiagReply.Answer, pid: Int): PidReading? =
        (DiagDecoder.obdPids(answer) as? Decoded.Ok)?.value?.get(pid)
}

/** What an opened module's own read says. */
internal class IdentResult(val ecu: Ecu, val read: ModuleRead) {
    private fun <T> polled(uds: String, decode: (DiagReply.Answer) -> Decoded<T>): Polled<T> =
        read.reply(uds)?.let { Polled.of(it, decode) } ?: Polled.NotPolled

    val dids: Map<IdentDid, Polled<Identification>> =
        IdentDid.entries.associateWith { did -> polled("22" + did.hex) { DiagDecoder.identification(it) } }

    /** Mode 01 PIDs the module offers, by its own maps, bitmap PIDs included. */
    val offered: List<Int> = listOf(0x00, 0x20, 0x40, 0x60).flatMap { base ->
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
    val ecuName: Polled<String> = polled(ScanRequests.ECU_NAME) { ModuleResult.ecuName(it) }
    val calibration: Polled<String> = polled("0904") { a ->
        DiagDecoder.vehicleInfo(a).map { (it as? VehicleInfo.CalibrationIds)?.ids?.joinToString(", ") ?: "" }
    }

    companion object {
        /** Whether [pid] is a value the stream can ask for: a known PID, not a map or a block read once, one frame. */
        fun streamable(pid: Int): Boolean {
            val def = ObdPids.of(pid) ?: return false
            return !ObdPids.isBitmap(pid) && pid !in STATIC && 1 + def.length <= LiveStream.FRAME_DATA
        }

        /** Read once with the identification, not streamed: monitors, the standard, which O2 sensors exist. */
        private val STATIC = setOf(0x01, 0x41, 0x1C, 0x13)
    }
}
