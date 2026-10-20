package dev.denza.apps.feature.diagnostics.protocol

/**
 * The eight DTC status bits of ISO 14229-1 (D.2). Each is independent: «подтверждённый» is not
 * «активный», and a bit outside the ECU's availability mask is unsupported, not zero.
 */
internal enum class StatusBit(val mask: Int, val short: String, val label: String) {
    TF(0x01, "TF", "отказ сейчас"),
    TFTOC(0x02, "TFTOC", "отказ в текущем цикле"),
    PDTC(0x04, "PDTC", "ожидающий"),
    CDTC(0x08, "CDTC", "подтверждённый"),
    TNCSLC(0x10, "TNCSLC", "тест не выполнен после сброса"),
    TFSLC(0x20, "TFSLC", "отказ после сброса"),
    TNCTOC(0x40, "TNCTOC", "тест не выполнен в текущем цикле"),
    WIR(0x80, "WIR", "запрос лампы"),
}

/** One status bit of one record as the matrix shows it. */
internal enum class BitState(val label: String) { SET("да"), CLEAR("нет"), UNSUPPORTED("не поддержан") }

/** A status byte, or an availability mask: the bits it holds. */
@JvmInline
internal value class DtcStatus(val byte: Int) {
    operator fun contains(bit: StatusBit) = byte and bit.mask != 0
    val bits: List<StatusBit> get() = StatusBit.entries.filter { it in this }
    /** This record's [bit] against the ECU's [availability] mask. */
    fun state(bit: StatusBit, availability: DtcStatus): BitState = when {
        bit !in availability -> BitState.UNSUPPORTED
        bit in this -> BitState.SET
        else -> BitState.CLEAR
    }
    val hex: String get() = "%02X".format(byte)
    override fun toString() = hex
}

/** DTCFormatIdentifier of `59 01`, ISO 14229-1 (D.6). */
internal enum class DtcFormat(val id: Int, val title: String) {
    SAE_J2012_00(0x00, "SAE J2012-DA, формат 00"),
    ISO_14229_1(0x01, "ISO 14229-1"),
    SAE_J1939_73(0x02, "SAE J1939-73"),
    ISO_11992_4(0x03, "ISO 11992-4"),
    SAE_J2012_04(0x04, "SAE J2012-DA, формат 04"),
    OTHER(-1, "неизвестный формат");

    companion object {
        fun of(id: Int): DtcFormat = entries.firstOrNull { it.id == id } ?: OTHER
    }
}

/**
 * A three-byte DTC as its format reads it. Format 00 is SAE J2012: letter, four characters and
 * the failure type (`C1170-09`). Format 01 has no letter: six hex digits (`517009`), never
 * P/C/B/U. Any other format: the raw bytes.
 */
internal data class DtcCode(val raw: Int, val format: DtcFormat, val formatId: Int = format.id) {
    val rawHex: String get() = "%06X".format(raw and 0xFFFFFF)

    /** `C1170` for format 00, null otherwise. */
    val base: String? get() = if (format == DtcFormat.SAE_J2012_00) J2012.base(raw shr 8) else null

    /** The failure type byte of format 00, null otherwise. */
    val failureType: Int? get() = if (format == DtcFormat.SAE_J2012_00) raw and 0xFF else null

    /** SAE J2012-DA general meaning of [failureType], null when unnamed. Not a BYD definition. */
    val failureTypeName: String? get() = failureType?.let(FailureTypes::name)

    val display: String get() = base?.let { "%s-%02X".format(it, raw and 0xFF) } ?: rawHex

    override fun toString() = display
}

/** SAE J2012 two-byte base code: P/C/B/U, a digit 0-3 and three hex digits. */
internal object J2012 {
    fun base(twoBytes: Int): String {
        val v = twoBytes and 0xFFFF
        return "%c%d%03X".format("PCBU"[v shr 14], (v shr 12) and 3, v and 0xFFF)
    }
}

/**
 * Failure type byte names, SAE J2012-DA: general categories, the same for every maker. They say
 * what kind of failure, not which part; the base code's meaning is the maker's and stays unknown.
 */
internal object FailureTypes {
    const val SOURCE = "SAE J2012-DA, общее значение"

    fun name(ftb: Int): String? = NAMES[ftb and 0xFF]

    private val NAMES = mapOf(
        0x00 to "подтип не указан",
        0x01 to "общий электрический отказ",
        0x02 to "общий отказ сигнала",
        0x03 to "отказ ЧМ/ШИМ",
        0x04 to "внутренний отказ системы",
        0x05 to "отказ программирования",
        0x06 to "отказ по алгоритму",
        0x07 to "механический отказ",
        0x08 to "отказ сигнала или сообщения шины",
        0x09 to "отказ компонента",
        0x11 to "замыкание на массу",
        0x12 to "замыкание на питание",
        0x13 to "обрыв цепи",
        0x14 to "замыкание на массу или обрыв",
        0x15 to "замыкание на питание или обрыв",
        0x16 to "напряжение цепи ниже порога",
        0x17 to "напряжение цепи выше порога",
        0x18 to "ток цепи ниже порога",
        0x19 to "ток цепи выше порога",
        0x1A to "сопротивление ниже порога",
        0x1B to "сопротивление выше порога",
        0x1C to "напряжение вне диапазона",
        0x1D to "ток вне диапазона",
        0x1E to "сопротивление вне диапазона",
        0x1F to "цепь с перебоями",
        0x21 to "амплитуда сигнала ниже минимума",
        0x22 to "амплитуда сигнала выше максимума",
        0x23 to "сигнал застрял внизу",
        0x24 to "сигнал застрял вверху",
        0x25 to "неверная форма сигнала",
        0x26 to "скорость изменения сигнала ниже порога",
        0x27 to "скорость изменения сигнала выше порога",
        0x28 to "смещение сигнала вне диапазона",
        0x29 to "недостоверный сигнал",
        0x2A to "сигнал застрял в диапазоне",
        0x2F to "сигнал нестабилен",
        0x31 to "нет сигнала",
        0x36 to "частота сигнала слишком низкая",
        0x37 to "частота сигнала слишком высокая",
        0x38 to "неверная частота сигнала",
        0x41 to "ошибка контрольной суммы",
        0x42 to "общий отказ памяти",
        0x44 to "отказ памяти данных",
        0x45 to "отказ памяти программ",
        0x46 to "отказ памяти калибровок",
        0x47 to "отказ сторожевого таймера",
        0x48 to "отказ контролирующего ПО",
        0x49 to "внутренний электронный отказ",
        0x4A to "установлен не тот компонент",
        0x4B to "перегрев",
        0x51 to "не запрограммирован",
        0x52 to "не активирован",
        0x53 to "деактивирован",
        0x54 to "нет калибровки",
        0x55 to "не сконфигурирован",
        0x61 to "ошибка расчёта сигнала",
        0x62 to "ошибка сравнения сигналов",
        0x63 to "истекло время защиты цепи",
        0x64 to "неправдоподобный сигнал",
        0x67 to "неверный сигнал после события",
        0x71 to "исполнительный механизм заклинил",
        0x72 to "заклинил открытым",
        0x73 to "заклинил закрытым",
        0x77 to "заданное положение не достигнуто",
        0x7A to "утечка жидкости или отказ уплотнения",
        0x7B to "низкий уровень жидкости",
        0x81 to "получены неверные данные",
        0x82 to "неверный счётчик сообщений",
        0x83 to "неверная защита сигнала (контрольная сумма)",
        0x84 to "сигнал ниже допустимого",
        0x85 to "сигнал выше допустимого",
        0x86 to "недостоверный сигнал шины",
        0x87 to "нет сообщения",
        0x88 to "шина отключена (bus off)",
        0x8F to "сообщения нестабильны",
        0x92 to "отказ работы или характеристик",
        0x93 to "нет работы",
        0x94 to "неожиданная работа",
        0x96 to "внутренний отказ компонента",
        0x98 to "перегрев компонента или системы",
    )
}

/** `59 01`: the availability mask, the format and the count of records matching the request mask. */
internal data class DtcCount(val availability: DtcStatus, val format: DtcFormat, val formatId: Int, val count: Int)

/** One `59 02` record before its format is known. */
internal data class DtcEntry(val raw: Int, val status: DtcStatus)

/** `59 02`: the availability mask and the records. */
internal data class DtcList(val availability: DtcStatus, val entries: List<DtcEntry>)

/** One read record: its code as the format reads it and its own status byte. */
internal data class DtcRecord(val code: DtcCode, val status: DtcStatus)

/**
 * One ECU's DTC memory for one request mask: count and list agreed. Empty means «no record
 * matched [requestMask]» - not that every monitor ran.
 */
internal data class DtcMemory(
    val requestMask: DtcStatus,
    val availability: DtcStatus,
    val format: DtcFormat,
    val records: List<DtcRecord>,
) : MaybeEmpty {
    override val isEmpty: Boolean get() = records.isEmpty()
}

/** Bytes of `59 01` / `59 02` (positive SID included) and the agreement between them (architecture, 4). */
internal object DtcCodec {
    fun count(uds: ByteArray): Decoded<DtcCount> {
        val b = uds.unsigned()
        if (b.size < 2 || b[0] != 0x59 || b[1] != 0x01) return Decoded.Malformed("not 59 01")
        if (b.size != 6) return Decoded.Malformed("59 01 is ${b.size} bytes, not 6")
        return Decoded.Ok(DtcCount(DtcStatus(b[2]), DtcFormat.of(b[3]), b[3], b[4] shl 8 or b[5]))
    }

    fun list(uds: ByteArray): Decoded<DtcList> {
        val b = uds.unsigned()
        if (b.size < 2 || b[0] != 0x59 || b[1] != 0x02) return Decoded.Malformed("not 59 02")
        if (b.size < 3 || (b.size - 3) % 4 != 0) return Decoded.Malformed("59 02 is ${b.size} bytes, not 3 + 4n")
        val availability = DtcStatus(b[2])
        val entries = (3 until b.size step 4).map { i ->
            DtcEntry(b[i] shl 16 or (b[i + 1] shl 8) or b[i + 2], DtcStatus(b[i + 3]))
        }
        return Decoded.Ok(DtcList(availability, entries))
    }

    /**
     * Count and list of one request mask as one memory, or why they disagree: the count is not
     * the list's length, the two masks differ, a record holds a bit outside the availability mask,
     * or a code repeats.
     */
    fun memory(requestMask: Int, count: DtcCount, list: DtcList): Decoded<DtcMemory> {
        if (count.availability != list.availability) {
            return Decoded.Malformed("availability %s in 59 01, %s in 59 02".format(count.availability, list.availability))
        }
        if (count.count != list.entries.size) {
            return Decoded.Malformed("59 01 counts ${count.count}, 59 02 lists ${list.entries.size}")
        }
        list.entries.firstOrNull { it.status.byte and count.availability.byte.inv() != 0 }?.let {
            return Decoded.Malformed("%06X status %s outside availability %s".format(it.raw, it.status, count.availability))
        }
        if (list.entries.map { it.raw }.toSet().size != list.entries.size) return Decoded.Malformed("a code repeats")
        val records = list.entries.map { DtcRecord(DtcCode(it.raw, count.format, count.formatId), it.status) }
        return Decoded.Ok(DtcMemory(DtcStatus(requestMask and 0xFF), count.availability, count.format, records))
    }

    /**
     * A zero count needs no list (architecture, 6): the memory is complete and empty for this mask.
     * A non-zero count alone is not a memory.
     */
    fun emptyMemory(requestMask: Int, count: DtcCount): Decoded<DtcMemory> =
        if (count.count != 0) Decoded.Malformed("59 01 counts ${count.count}; the list is needed")
        else Decoded.Ok(DtcMemory(DtcStatus(requestMask and 0xFF), count.availability, count.format, emptyList()))
}

internal fun ByteArray.unsigned(): IntArray = IntArray(size) { this[it].toInt() and 0xFF }
