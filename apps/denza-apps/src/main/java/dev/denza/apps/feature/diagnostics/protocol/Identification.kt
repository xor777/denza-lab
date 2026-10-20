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
internal data class Identification(val did: IdentDid, val text: String, val ascii: Boolean, val raw: String)

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
