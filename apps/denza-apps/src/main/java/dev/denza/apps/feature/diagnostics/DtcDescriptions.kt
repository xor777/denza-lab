package dev.denza.apps.feature.diagnostics

internal data class DtcDescription(
    val display: String, val meaning: List<String>, val subtype: String,
    val source: List<String>, val summary: String,
    val base: String? = null, val failureType: String? = null,
)

/** Format 00 only. General references are not a BYD ECU/software-specific dictionary.
 * Sources and their applicability are retained in research/vehicle-diagnostics/dtc-reference-20261018.json.
 */
internal object DtcDescriptions {
    fun describe(record: DtcRecord, format: Int): DtcDescription {
        val raw = record.code.uppercase()
        if (format != 0 || !Regex("[0-9A-F]{6}").matches(raw)) return DtcDescription(
            raw, listOf("Расшифровка этого формата", "пока не поддерживается"), "Подтип не интерпретируется",
            listOf("Сохранён исходный код ECU"), "Значение пока неизвестно")
        val first = raw.take(2).toInt(16)
        val base = "${"PCBU"[first shr 6]}${(first shr 4) and 3}${raw.substring(1, 4)}"
        val suffix = raw.takeLast(2)
        val meaning = when (base) {
            "U0121" -> listOf("Потеря связи с блоком ABS")
            "U029D" -> listOf("Потеря связи с датчиком", "NOx «A»")
            "U0229" -> listOf("Потеря связи с блоком", "обогрева руля")
            else -> listOf("Назначение кода BYD", "пока не установлено")
        }
        val subtype = when (suffix) {
            "00" -> "Подтип не указан"
            "02" -> "Общий сбой сигнала"
            "06" -> "Сбой алгоритма"
            "87" -> "Нет сообщения"
            else -> "Подтип не расшифрован"
        }
        val summary = when (base) {
            "U0121" -> "Связь с ABS · справочно"
            "U029D" -> "Связь с NOx A · справочно"
            "U0229" -> "Обогрев руля · связь · справочно"
            else -> "Назначение BYD неизвестно"
        }
        return DtcDescription("$base-$suffix", meaning, subtype,
            if (base == "U0229") listOf("Общий справочник Autel", "Применимость к BYD не проверена")
            else if (base == "U0121" || base == "U029D") listOf("Общее значение SAE", "Применимость к BYD не проверена")
            else listOf("Словарь этого ECU не получен", "Тип отказа: общее значение SAE"),
            summary, base, suffix)
    }
}

/** Independent UDS flags, including the supported-but-zero and unsupported cases. */
internal enum class DtcStatusLabel(val mask: Int, val title: String) {
    FAILED(0x01, "Отказ сейчас"),
    CYCLE_FAILED(0x02, "Отказ в этом цикле"),
    PENDING(0x04, "Ожидает подтверждения"),
    CONFIRMED(0x08, "Подтверждённая запись"),
    NOT_TESTED_SINCE_CLEAR(0x10, "Не проверен после очистки"),
    FAILED_SINCE_CLEAR(0x20, "Отказ после очистки"),
    NOT_TESTED_THIS_CYCLE(0x40, "Не проверен в этом цикле"),
    WARNING(0x80, "Запрошена лампа");

    fun value(record: DtcRecord, availability: Int): String = when {
        availability and mask == 0 -> "Не поддержан"
        record.status and mask != 0 -> "Да"
        else -> "Нет"
    }
}
