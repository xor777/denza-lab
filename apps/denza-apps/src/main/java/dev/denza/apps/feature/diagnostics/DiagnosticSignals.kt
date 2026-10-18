package dev.denza.apps.feature.diagnostics

import dev.denza.apps.platform.shell.ServiceCallParcel

internal enum class DiagnosticLamp(val title: String, val fid: Int) {
    ENGINE("Двигатель", 0x28700026), ABS("ABS", 0x27500014), ESP("ESP", 0x2750002C),
    BRAKE("Тормоза", 0x27500020), SRS("Подушки / ремни SRS", 0x27500008), STEERING("Рулевое управление", 0x2870000E),
    TPMS("Давление шин TPMS", 0x28700014), LOW_FUEL("Мало топлива", 0x28700032), LOW_PACK("Низкий заряд", 0x2A600026),
    COOLANT_HIGH("Перегрев ОЖ", 0x2870002C), OIL_PRESSURE("Давление масла", 0x29600008), GPF("Фильтр частиц GPF", 0x2870001A),
    PACK_HEAT("Перегрев HV", 0x2A600032), PACK_FAULT("Неисправность HV", 0x2B000008), POWER_FAULT("Силовая система", 0x2A60001A);
}

internal data class DiagnosticIndicators(val values: Map<DiagnosticLamp, Int> = emptyMap(), val atMillis: Long = 0,
    val rawValues: Map<DiagnosticLamp, Int> = emptyMap(),
) {
    fun label(lamp: DiagnosticLamp): String = when (values[lamp]) {
        1 -> "ВКЛ"
        2 -> "ВЫКЛ"
        3 -> "Мигает 0,25 Гц"
        4 -> "Мигает 0,5 Гц"
        5 -> "Мигает 1 Гц"
        6 -> "Мигает 2 Гц"
        7 -> "Мигает 4 Гц"
        else -> "Нет данных"
    }
    fun raw(lamp: DiagnosticLamp): String = (rawValues[lamp] ?: values[lamp])?.toString() ?: "Нет ответа"
    fun detailLabel(lamp: DiagnosticLamp): String = when {
        values[lamp] == 1 -> "Включён"
        values[lamp] == 2 -> "Выключен"
        lamp in values -> label(lamp)
        rawValues[lamp] == 0 -> "Недоступно · 0"
        lamp in rawValues -> "Неизвестно · ${rawValues[lamp]}"
        else -> "Нет ответа"
    }
    fun quality(lamp: DiagnosticLamp): String = when {
        lamp in values -> "Показание получено"
        rawValues[lamp] == 0 -> "0: показание недоступно"
        lamp in rawValues -> "Ответ не распознан"
        else -> "Ответ не получен"
    }
    fun lit(lamp: DiagnosticLamp) = values[lamp]?.let { it == 1 || it in 3..7 } == true
}

internal object DiagnosticSignals {
    val command = DiagnosticLamp.entries.mapIndexed { i, lamp ->
        "echo @@$i; service call autoservice 5 i32 1007 i32 ${lamp.fid}"
    }.joinToString("; ")

    fun indicators(output: String, atMillis: Long): DiagnosticIndicators {
        val sections = output.split(Regex("(?m)^@@"))
        val values = mutableMapOf<DiagnosticLamp, Int>()
        val raw = mutableMapOf<DiagnosticLamp, Int>()
        sections.forEach { section ->
            val index = section.lineSequence().firstOrNull()?.trim()?.toIntOrNull()
            if (index != null && index in DiagnosticLamp.entries.indices) {
                val word = value(section)
                if (word != null) {
                    raw[DiagnosticLamp.entries[index]] = word
                    if (word in 1..7) values[DiagnosticLamp.entries[index]] = word
                }
            }
        }
        return DiagnosticIndicators(values, atMillis, raw)
    }

    fun value(output: String): Int? = output.lineSequence().mapNotNull { ServiceCallParcel.oneLineWords(it) }
        .firstOrNull { it.size == 2 && it[0] == 0 }?.get(1)

    fun requireParked(profile: String, park: String, speed: String) {
        if (profile.trim() != "170") throw DiagnosticFailure("Чтение DTC проверено только для Z9GT · 170")
        val speedValue = value(speed)?.let(Float::fromBits)
        if (value(park) != 1 || speedValue == null || !speedValue.isFinite() || speedValue != 0f) {
            throw DiagnosticFailure("Для чтения DTC нужна подтверждённая парковка P и скорость 0")
        }
    }
}
