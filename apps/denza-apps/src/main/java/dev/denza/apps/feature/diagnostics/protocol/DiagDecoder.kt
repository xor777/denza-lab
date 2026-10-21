package dev.denza.apps.feature.diagnostics.protocol

import dev.denza.apps.feature.diagnostics.bus.DiagReply

/**
 * Decoders the scan engine calls with a complete [DiagReply.Answer]: each first holds the answer
 * to its own request - the positive service is the asked one + 0x40, the sub-function, DID, PID or
 * info type is the asked one, and a `7F` names the asked service - then reads the bytes. Wrap
 * with [Polled.of] to get data quality as well.
 */
internal object DiagDecoder {
    /** `19 01 <mask>` → `59 01`. */
    fun dtcCount(answer: DiagReply.Answer): Decoded<DtcCount> =
        checked(answer, 0x19) { req, uds ->
            if (req.size != 3 || req[1] != 0x01) return@checked Decoded.Malformed("request is not 19 01")
            DtcCodec.count(uds)
        }

    /** `19 02 <mask>` → `59 02`. Join with the count by [DtcCodec.memory]. */
    fun dtcList(answer: DiagReply.Answer): Decoded<DtcList> =
        checked(answer, 0x19) { req, uds ->
            if (req.size != 3 || req[1] != 0x02) return@checked Decoded.Malformed("request is not 19 02")
            DtcCodec.list(uds)
        }

    /** A `19 01` and `19 02` pair of one mask as one memory; a zero count needs no list. */
    fun dtcMemory(count: DiagReply.Answer, list: DiagReply.Answer?): Decoded<DtcMemory> {
        val mask = count.request.uds.let { if (it.size == 3) it[2].toInt() and 0xFF else -1 }
        val c = when (val d = dtcCount(count)) {
            is Decoded.Ok -> d.value
            is Decoded.Negative -> return d
            is Decoded.Malformed -> return d
        }
        if (list == null) return DtcCodec.emptyMemory(mask, c)
        if (!list.request.uds.contentEquals(byteArrayOf(0x19, 0x02, mask.toByte())) || list.request.address != count.request.address) {
            return Decoded.Malformed("count and list are not one ECU and one mask")
        }
        return when (val l = dtcList(list)) {
            is Decoded.Ok -> DtcCodec.memory(mask, c, l.value).laidOut(count.request.address)
            is Decoded.Negative -> l
            is Decoded.Malformed -> l
        }
    }

    /** A `19 02` alone, for an ECU whose `19 01` does not answer: see [DtcCodec.listOnlyMemory]. */
    fun dtcMemoryFromList(list: DiagReply.Answer, format: DtcFormat?): Decoded<DtcMemory> {
        val mask = list.request.uds.let { if (it.size == 3) it[2].toInt() and 0xFF else -1 }
        return when (val l = dtcList(list)) {
            is Decoded.Ok -> DtcCodec.listOnlyMemory(mask, l.value, format).laidOut(list.request.address)
            is Decoded.Negative -> l
            is Decoded.Malformed -> l
        }
    }

    /** `22 <did>` → `62 <did> <data>`. */
    fun identification(answer: DiagReply.Answer): Decoded<Identification> =
        checked(answer, 0x22) { req, uds ->
            if (req.size != 3) return@checked Decoded.Malformed("request is not 22 <did>")
            val did = req[1] shl 8 or req[2]
            when (val d = IdentCodec.decode(uds)) {
                is Decoded.Ok -> if (d.value.did.did != did) Decoded.Malformed("asked %04X, got %s".format(did, d.value.did.hex)) else d
                else -> d
            }
        }

    /**
     * `22 F194`, or `22 <did>` of a child at its parent → `62 <did>` and a nine-byte software code that
     * is not all zero ([IdentCodec.softwareCode]).
     */
    fun softwareCode(answer: DiagReply.Answer): Decoded<String> =
        checked(answer, 0x22) { req, uds ->
            if (req.size != 3) return@checked Decoded.Malformed("request is not 22 <did>")
            IdentCodec.softwareCode(uds, req[1] shl 8 or req[2])
        }

    /** `22 <did>` of a block's ASCII build version → `62 <did>` and digits and dots ([IdentCodec.buildVersion]). */
    fun buildVersion(answer: DiagReply.Answer): Decoded<String> =
        checked(answer, 0x22) { req, uds ->
            if (req.size != 3) return@checked Decoded.Malformed("request is not 22 <did>")
            IdentCodec.buildVersion(uds, req[1] shl 8 or req[2])
        }

    /** `01 <pid>…` → `41 <pid> <data>…`; PIDs the ECU leaves out are [PidAnswer.unanswered]. */
    fun obdPids(answer: DiagReply.Answer): Decoded<PidAnswer> =
        checked(answer, 0x01) { req, uds ->
            val asked = req.drop(1)
            if (asked.isEmpty()) return@checked Decoded.Malformed("request has no PID")
            when (val d = ObdCodec.pids(uds)) {
                is Decoded.Ok -> {
                    val foreign = d.value.firstOrNull { it.pid !in asked }
                    if (foreign != null) Decoded.Malformed("PID %02X not asked".format(foreign.pid))
                    else Decoded.Ok(PidAnswer(d.value, asked.filter { p -> d.value.none { it.pid == p } }))
                }
                is Decoded.Negative -> d
                is Decoded.Malformed -> d
            }
        }

    /** `03`, `07`, `0A` → `43`, `47`, `4A` with a count and two-byte codes. */
    fun obdDtcs(answer: DiagReply.Answer): Decoded<ObdDtcs> {
        val sid = answer.request.uds.firstOrNull()?.toInt()?.and(0xFF)
        if (sid == null || ObdDtcMode.ofService(sid) == null) return Decoded.Malformed("request is not 03, 07 or 0A")
        return checked(answer, sid) { _, uds -> ObdCodec.dtcs(uds) }
    }

    /** `09 <info type>` → `49 <info type> …`. */
    fun vehicleInfo(answer: DiagReply.Answer): Decoded<VehicleInfo> =
        checked(answer, 0x09) { req, uds ->
            if (req.size != 2) return@checked Decoded.Malformed("request is not 09 <info type>")
            val u = uds.unsigned()
            if (u.size >= 2 && u[1] != req[1]) return@checked Decoded.Malformed("asked 09 %02X, got 49 %02X".format(req[1], u[1]))
            ObdCodec.vehicleInfo(uds)
        }

    /** The service check every decoder shares; [body] sees the request and the answer bytes. */
    private inline fun <T> checked(answer: DiagReply.Answer, service: Int, body: (IntArray, ByteArray) -> Decoded<T>): Decoded<T> {
        val req = answer.request.uds.unsigned()
        if (req.isEmpty() || req[0] != service) return Decoded.Malformed("request is not service %02X".format(service))
        val uds = answer.uds
        val u = uds.unsigned()
        if (u.isEmpty()) return Decoded.Malformed("empty answer")
        if (u[0] == 0x7F) {
            if (u.size != 3) return Decoded.Malformed("7F is ${u.size} bytes, not 3")
            if (u[1] != service) return Decoded.Malformed("7F for service %02X, asked %02X".format(u[1], service))
            return Decoded.Negative(Nrc.of(u[2]))
        }
        if (u[0] != service + 0x40) return Decoded.Malformed("answer %02X to service %02X".format(u[0], service))
        return body(req, uds)
    }

    private fun Decoded<DtcMemory>.laidOut(address: Int): Decoded<DtcMemory> =
        if (this is Decoded.Ok) Decoded.Ok(J2012.laidOut(address, value)) else this
}
