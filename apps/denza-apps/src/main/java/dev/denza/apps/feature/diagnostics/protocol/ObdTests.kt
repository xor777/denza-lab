package dev.denza.apps.feature.diagnostics.protocol

/**
 * How one Unit and Scaling ID of SAE J1979 Mode 06 turns a test's 16-bit word into a quantity: the word,
 * signed where the ID is 0x80 or above, times [scale] plus [offset], in [unit] with [decimals] shown.
 */
internal data class TestScale(val scale: Double, val offset: Double, val unit: String, val decimals: Int, val signed: Boolean)

/**
 * The Unit and Scaling IDs of SAE J1979 (Mode 06, CAN format), written from the standard's table. An ID
 * not here is read as its raw word ([of] null): a screen shows the number and the ID, never a guessed unit.
 */
internal object TestScales {
    private fun u(scale: Double, unit: String, decimals: Int, offset: Double = 0.0) = TestScale(scale, offset, unit, decimals, false)
    private fun s(scale: Double, unit: String, decimals: Int) = TestScale(scale, 0.0, unit, decimals, true)

    private val TABLE: Map<Int, TestScale> = mapOf(
        0x01 to u(1.0, "", 0),
        0x02 to u(0.1, "", 1),
        0x03 to u(0.01, "", 2),
        0x04 to u(0.001, "", 3),
        0x05 to u(1.0 / 32768, "", 5),
        0x06 to u(0.000305, "", 4),
        0x07 to u(0.25, "об/мин", 2),
        0x08 to u(0.01, "км/ч", 2),
        0x09 to u(1.0, "км/ч", 0),
        0x0A to u(1.0 / 8192, "В", 4),
        0x0B to u(0.001, "В", 3),
        0x0C to u(0.01, "В", 2),
        0x0D to u(1.0 / 256, "мА", 3),
        0x0E to u(0.001, "А", 3),
        0x0F to u(0.01, "А", 2),
        0x10 to u(1.0, "мс", 0),
        0x11 to u(100.0, "мс", 0),
        0x12 to u(1.0, "с", 0),
        0x13 to u(1.0, "мОм", 0),
        0x14 to u(1.0, "Ом", 0),
        0x15 to u(1.0, "кОм", 0),
        0x16 to u(0.1, "°C", 1, offset = -40.0),
        0x17 to u(0.01, "кПа", 2),
        0x18 to u(0.0117, "кПа", 2),
        0x19 to u(0.079, "кПа", 2),
        0x1A to u(1.0, "кПа", 0),
        0x1B to u(10.0, "кПа", 0),
        0x1C to u(0.01, "°", 2),
        0x1D to u(0.5, "°", 1),
        0x1E to u(1.0 / 32768, "", 4),
        0x1F to u(0.05, "", 2),
        0x20 to u(1.0 / 256, "", 3),
        0x21 to u(1.0, "мГц", 0),
        0x22 to u(1.0, "Гц", 0),
        0x23 to u(1.0, "кГц", 0),
        0x24 to u(1.0, "", 0),
        0x25 to u(1.0, "км", 0),
        0x27 to u(0.01, "г/с", 2),
        0x28 to u(1.0, "г/с", 0),
        0x2F to u(0.01, "%", 2),
        0x81 to s(1.0, "", 0),
        0x82 to s(0.1, "", 1),
        0x83 to s(0.01, "", 2),
        0x84 to s(0.001, "", 3),
        0x85 to s(1.0 / 32768, "", 5),
        0x86 to s(0.000305, "", 4),
        0x8A to s(1.0 / 8192, "В", 4),
        0x8B to s(0.001, "В", 3),
        0x8C to s(0.01, "В", 2),
        0x8D to s(1.0 / 256, "мА", 3),
        0x8E to s(0.001, "А", 3),
        0x90 to s(1.0, "мс", 0),
        0x96 to s(0.1, "°C", 1),
        0x9C to s(0.01, "°", 2),
        0x9D to s(0.5, "°", 1),
        0xAF to s(0.01, "%", 2),
    )

    fun of(uasid: Int): TestScale? = TABLE[uasid and 0xFF]
}

/**
 * One test of a Mode 06 answer: monitor [mid], test [tid], scaling [uasid] and the three 16-bit words as they
 * came. [pass] is the module's own limits applied to its own value; a test the module has not run since the
 * codes were cleared reports all three as zero (SAE J1979) and is not judged.
 */
internal data class ObdTest(val mid: Int, val tid: Int, val uasid: Int, val value: Int, val min: Int, val max: Int) {
    val scale: TestScale? get() = TestScales.of(uasid)

    private fun word(raw: Int): Int = if (uasid >= 0x80 && raw >= 0x8000) raw - 0x10000 else raw

    /** Whether the test ran: not every word zero. */
    val ran: Boolean get() = value != 0 || min != 0 || max != 0

    /** Within the module's limits; null for a test that did not run. */
    val pass: Boolean? get() = if (!ran) null else word(value) in word(min)..word(max)

    /** One of the words in its unit, «0,742 В»; the raw number where the scaling is not known. */
    fun text(raw: Int): String = scale?.let { s ->
        ObdQuantity(null, word(raw) * s.scale + s.offset, s.unit, s.decimals).text
    } ?: word(raw).toString()

    val hex: String get() = "%02X%02X%02X%04X%04X%04X".format(mid, tid, uasid, value, min, max)
}

/** What one `46` answer holds. */
internal sealed interface ObdTestAnswer {
    /** `46 00/20/40/…`: the MIDs this module offers in the next block of 32. */
    data class Supported(val base: Int, val mids: List<Int>) : ObdTestAnswer

    /** `46 <MID> …`: every test of one monitor. */
    data class Results(val mid: Int, val tests: List<ObdTest>) : ObdTestAnswer, MaybeEmpty {
        override val isEmpty: Boolean get() = tests.isEmpty()
    }
}

/** SAE J1979 Mode 06 on CAN: the answer, the monitor names, which MIDs are maps. */
internal object ObdTests {
    /** Whether [mid] is one of the maps 00, 20, 40, … E0. */
    fun isBitmap(mid: Int) = mid and 0x1F == 0

    /** `46 <map> <4 bytes>` or `46 [<MID> <TID> <UASID> <value> <min> <max>]…`, each test nine bytes. */
    fun decode(uds: ByteArray, mid: Int): Decoded<ObdTestAnswer> {
        val b = uds.unsigned()
        if (b.isEmpty() || b[0] != 0x46) return Decoded.Malformed("not 46")
        if (isBitmap(mid)) {
            if (b.size != 6 || b[1] != mid) return Decoded.Malformed("46 %02X is not one map".format(mid))
            val bits = b[2].toLong() shl 24 or (b[3].toLong() shl 16) or (b[4].toLong() shl 8) or b[5].toLong()
            return Decoded.Ok(ObdTestAnswer.Supported(mid, (0 until 32).filter { bits and (1L shl (31 - it)) != 0L }.map { mid + it + 1 }))
        }
        if (b.size < 2 || (b.size - 1) % 9 != 0) return Decoded.Malformed("46 is ${b.size} bytes, not 1 + 9n")
        val tests = (1 until b.size step 9).map { i ->
            if (b[i] != mid) return Decoded.Malformed("asked MID %02X, got %02X".format(mid, b[i]))
            ObdTest(b[i], b[i + 1], b[i + 2], b[i + 3] shl 8 or b[i + 4], b[i + 5] shl 8 or b[i + 6], b[i + 7] shl 8 or b[i + 8])
        }
        return Decoded.Ok(ObdTestAnswer.Results(mid, tests))
    }

    /** The monitor's name by SAE J1979's OBD Monitor IDs; a manufacturer's or unlisted MID by its number. */
    fun name(mid: Int): String = NAMES[mid] ?: if (mid >= 0xE1) "MID %02X · производителя".format(mid) else "MID %02X".format(mid)

    private fun sensor(i: Int) = "B%dS%d".format(i / 4 + 1, i % 4 + 1)

    private val NAMES: Map<Int, String> = buildMap {
        (0 until 8).forEach { put(it * 0x20, "Поддерживаемые MID %02X–%02X".format(it * 0x20 + 1, it * 0x20 + 0x20)) }
        (0 until 16).forEach { put(0x01 + it, "Датчик кислорода ${sensor(it)}") }
        (0 until 4).forEach { put(0x21 + it, "Катализатор, ряд ${it + 1}") }
        (0 until 4).forEach { put(0x31 + it, "EGR, ряд ${it + 1}") }
        (0 until 4).forEach { put(0x35 + it, "VVT, ряд ${it + 1}") }
        put(0x39, "EVAP: крышка или утечка 0,150″")
        put(0x3A, "EVAP: утечка 0,090″")
        put(0x3B, "EVAP: утечка 0,040″")
        put(0x3C, "EVAP: утечка 0,020″")
        put(0x3D, "Продувка адсорбера")
        (0 until 16).forEach { put(0x41 + it, "Подогрев датчика кислорода ${sensor(it)}") }
        (0 until 4).forEach { put(0x61 + it, "Подогрев катализатора, ряд ${it + 1}") }
        (0 until 4).forEach { put(0x71 + it, "Вторичный воздух ${it + 1}") }
        (0 until 4).forEach { put(0x81 + it, "Топливная система, ряд ${it + 1}") }
        (0 until 2).forEach { put(0x85 + it, "Давление наддува, ряд ${it + 1}") }
        (0 until 2).forEach { put(0x90 + it, "Накопитель NOx, ряд ${it + 1}") }
        (0 until 2).forEach { put(0x98 + it, "Катализатор NOx/SCR, ряд ${it + 1}") }
        put(0xA1, "Пропуски воспламенения, общие")
        (0 until 12).forEach { put(0xA2 + it, "Пропуски воспламенения, цилиндр ${it + 1}") }
        (0 until 2).forEach { put(0xB0 + it, "Фильтр частиц, ряд ${it + 1}") }
    }
}
