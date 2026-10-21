package dev.denza.apps.feature.diagnostics.protocol

/** The identification DIDs the whitelist allows, and how each reads. */
internal enum class IdentDid(val did: Int, val ascii: Boolean, val label: String) {
    F194(0xF194, false, "номер ПО поставщика"),
    F195(0xF195, false, "версия ПО поставщика"),
    F18A(0xF18A, true, "поставщик"),
    F197(0xF197, true, "имя системы");

    val hex: String get() = "%04X".format(did)

    companion object {
        fun of(did: Int): IdentDid? = entries.firstOrNull { it.did == did }
    }
}

/**
 * One `62 <did> <data>` answer. [text] is upper-case hex for F194/F195 and trimmed ASCII for
 * F18A/F197; ASCII that does not print falls back to hex with [ascii] false.
 */
internal data class Identification(val did: IdentDid, val text: String, val ascii: Boolean, val raw: String) {
    /** What a screen shows: F195 as the version the car uses ([IdentCodec.softwareVersion]), the rest as [text]. */
    val shown: String
        get() = when (did) {
            IdentDid.F195 -> IdentCodec.softwareVersion(raw)
            else -> text
        }
}

internal object IdentCodec {
    fun decode(uds: ByteArray): Decoded<Identification> {
        val b = uds.unsigned()
        if (b.isEmpty() || b[0] != 0x62) return Decoded.Malformed("not 62")
        if (b.size < 4) return Decoded.Malformed("62 without data")
        val did = IdentDid.of(b[1] shl 8 or b[2]) ?: return Decoded.Malformed("DID %02X%02X not asked".format(b[1], b[2]))
        val data = uds.copyOfRange(3, uds.size)
        val hex = data.hexString()
        if (!did.ascii) return Decoded.Ok(Identification(did, hex, false, hex))
        val text = Ascii.trimmed(data)
        return Decoded.Ok(if (text != null) Identification(did, text, true, hex) else Identification(did, hex, false, hex))
    }

    /**
     * A BYD software code (architecture, 5.1, 5.4): `62 <did>` and nine bytes that are not all zero, as
     * upper-case hex - a block's own `F194`, or a child's code behind its parent. Another DID, another
     * length or a zero placeholder is not that form: Malformed. The F194 gate passes on any such code,
     * and a child's row shows the answer's bytes rather than a code when it is not one.
     */
    fun softwareCode(uds: ByteArray, did: Int): Decoded<String> {
        val b = uds.unsigned()
        if (b.isEmpty() || b[0] != 0x62) return Decoded.Malformed("not 62")
        if (b.size < 3 || (b[1] shl 8 or b[2]) != did) return Decoded.Malformed("not DID %04X".format(did))
        if (b.size != 3 + SOFTWARE_CODE_BYTES) return Decoded.Malformed("code is ${b.size - 3} bytes, not $SOFTWARE_CODE_BYTES")
        if (b.drop(3).all { it == 0 }) return Decoded.Malformed("zero placeholder")
        return Decoded.Ok(uds.copyOfRange(3, uds.size).hexString())
    }

    /** Every F194 this car returned, and every child code, is nine bytes (research/vehicle-diagnostics/). */
    const val SOFTWARE_CODE_BYTES = 9

    /**
     * `F195` as the car's own updater reads it (stock GCY `CanDiagnostic.parseEcuVersionInfo` and
     * `VehicleOTAUtil.getCurrentEcuSoftVersion`): data bytes 0-1 big-endian, padded to five decimal digits,
     * `D.DD.DD` - BMS `87F7…` is 3.48.07, ECM `9C4A…` 4.00.10 - then the other bytes as they came, their
     * meaning not established and never read as a date: «3.48.07 · 1A011402». [hex] is the data, upper-case
     * hex. Fresh F195 of 64 addresses read so matched the updater's `ECUVersion.json` on 2026-10-21, every
     * one six bytes. Another length, or `FFFF` in bytes 0-1 - the stock parser's «6.55.35», an unwritten
     * field - is [hex] as it came.
     */
    fun softwareVersion(hex: String): String {
        val b = runCatching { hexBytes(hex) }.getOrNull()?.unsigned() ?: return hex
        if (b.size != SOFTWARE_VERSION_BYTES) return hex
        val version = b[0] shl 8 or b[1]
        if (version == 0xFFFF) return hex
        val d = version.toString().padStart(5, '0')
        return "${d[0]}.${d.substring(1, 3)}.${d.substring(3)} · ${hex.substring(4).uppercase()}"
    }

    /** Every F195 this car returned on 2026-10-21 is six bytes: the version, then four of unknown meaning. */
    const val SOFTWARE_VERSION_BYTES = 6
}

internal object Ascii {
    /** Printable ASCII with trailing NUL, 0xFF and spaces cut; null when anything else is inside. */
    fun trimmed(data: ByteArray): String? {
        var end = data.size
        while (end > 0 && (data[end - 1].toInt() and 0xFF).let { it == 0x00 || it == 0xFF || it == 0x20 }) end--
        if (end == 0) return null
        val body = data.copyOf(end)
        if (body.any { (it.toInt() and 0xFF) !in 0x20..0x7E }) return null
        return String(body, Charsets.US_ASCII)
    }
}

internal fun ByteArray.hexString(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

internal fun hexBytes(hex: String): ByteArray {
    val clean = hex.filterNot { it.isWhitespace() }
    require(clean.length % 2 == 0) { "odd hex" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
