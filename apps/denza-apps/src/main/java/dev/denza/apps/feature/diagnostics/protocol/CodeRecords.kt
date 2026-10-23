package dev.denza.apps.feature.diagnostics.protocol

/**
 * One snapshot record of `59 04` (ISO 14229-1, reportDTCSnapshotRecordByDTCNumber): its number, how many
 * identifiers it holds and their bytes. The lengths of a DID's data are the module's own and unknown here, so
 * only a record with one identifier is split into [did] and its data; a record with several keeps every byte
 * after its count in [data], as it came (architecture, 4).
 */
internal data class SnapshotRecord(
    val number: Int,
    val identifiers: Int,
    /** The one DID of a record that holds one; null when it holds none or several. */
    val did: Int?,
    /** Upper-case hex: the DID's data for one identifier, every identifier's bytes for several. */
    val data: String,
)

/** One extended data record of `59 06`: its number and its bytes, upper-case hex. Their meaning is the module's. */
internal data class ExtendedRecord(val number: Int, val data: String)

/**
 * A code's records as one `59 04` or `59 06` answer gave them. [records] are the ones whose bounds the answer
 * itself gives; where it does not - an answer to record `FF` (all of them) whose first record carries data of
 * unknown length - the bytes from that record on stay whole in [unsplit] and [unsplitFrom] is its number, so
 * the engine can ask that record alone. An answer of the code and its status only is complete and empty: the
 * module returned no record for this request (BMS `59 04 1A 3A 22 00`, 2026-10-21), which is not proof that it
 * never stored one.
 */
internal data class CodeRecords<T>(
    val dtc: Int,
    val status: DtcStatus,
    val records: List<T>,
    val unsplitFrom: Int? = null,
    val unsplit: String = "",
) : MaybeEmpty {
    override val isEmpty: Boolean get() = records.isEmpty() && unsplitFrom == null
}

/** Bytes of `59 04` and `59 06`, positive SID included, against the code and the record number asked. */
internal object CodeRecordCodec {
    /** ISO 14229-1's record number that asks for every record of the code. */
    const val ALL = 0xFF

    /** `59 04 <DTC> <status> [<record> <count> <DID> <data>…]…`. */
    fun snapshots(uds: ByteArray, dtc: Int, record: Int): Decoded<CodeRecords<SnapshotRecord>> {
        val b = uds.unsigned()
        head(b, 0x04, dtc)?.let { return Decoded.Malformed(it) }
        val status = DtcStatus(b[5])
        if (b.size == 6) return Decoded.Ok(CodeRecords(dtc, status, emptyList()))
        if (b.size == 7) return Decoded.Malformed("59 04 record %02X without its identifier count".format(b[6]))
        if (record != ALL) {
            if (b[6] != record) return Decoded.Malformed("asked record %02X, got %02X".format(record, b[6]))
            return one(b, 6)?.let { Decoded.Ok(CodeRecords(dtc, status, listOf(it))) }
                ?: Decoded.Malformed("59 04 record %02X counts one identifier and has no DID".format(b[6]))
        }
        // Every record: a record without identifiers ends at its count, so the next header follows; the first
        // with identifiers has data of unknown length, and from there the answer stays whole.
        val records = ArrayList<SnapshotRecord>()
        var i = 6
        while (i < b.size) {
            if (i + 1 >= b.size) return Decoded.Malformed("59 04 record %02X without its identifier count".format(b[i]))
            if (b[i + 1] != 0) return Decoded.Ok(CodeRecords(dtc, status, records, b[i], uds.copyOfRange(i, b.size).hexString()))
            records += SnapshotRecord(b[i], 0, null, "")
            i += 2
        }
        return Decoded.Ok(CodeRecords(dtc, status, records))
    }

    /** `59 06 <DTC> <status> [<record> <data>…]…`. */
    fun extended(uds: ByteArray, dtc: Int, record: Int): Decoded<CodeRecords<ExtendedRecord>> {
        val b = uds.unsigned()
        head(b, 0x06, dtc)?.let { return Decoded.Malformed(it) }
        val status = DtcStatus(b[5])
        if (b.size == 6) return Decoded.Ok(CodeRecords(dtc, status, emptyList()))
        if (record != ALL) {
            if (b[6] != record) return Decoded.Malformed("asked record %02X, got %02X".format(record, b[6]))
            return Decoded.Ok(CodeRecords(dtc, status, listOf(ExtendedRecord(b[6], uds.copyOfRange(7, b.size).hexString()))))
        }
        // A record's data has no length on the wire: from the first record on the answer stays whole.
        return Decoded.Ok(CodeRecords(dtc, status, emptyList(), b[6], uds.copyOfRange(6, b.size).hexString()))
    }

    /** One record from [at]: its number, count and what follows; null when it counts identifiers it does not carry. */
    private fun one(b: IntArray, at: Int): SnapshotRecord? {
        val number = b[at]
        val count = b[at + 1]
        val data = b.copyOfRange(at + 2, b.size)
        return when {
            count == 0 -> SnapshotRecord(number, 0, null, hex(data))
            count == 1 && data.size >= 2 -> SnapshotRecord(number, 1, data[0] shl 8 or data[1], hex(data.copyOfRange(2, data.size)))
            count == 1 -> null
            else -> SnapshotRecord(number, count, null, hex(data))
        }
    }

    /** Why [b] is not the head of `59 <sub> <dtc> <status>`, or null. */
    private fun head(b: IntArray, sub: Int, dtc: Int): String? = when {
        b.size < 2 || b[0] != 0x59 || b[1] != sub -> "not 59 %02X".format(sub)
        b.size < 6 -> "59 %02X is ${b.size} bytes, not code and status".format(sub)
        (b[2] shl 16 or (b[3] shl 8) or b[4]) != dtc -> "asked %06X, got %02X%02X%02X".format(dtc, b[2], b[3], b[4])
        else -> null
    }

    private fun hex(b: IntArray): String = b.joinToString("") { "%02X".format(it) }
}
