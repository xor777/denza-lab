package dev.denza.apps.feature.diagnostics

/** Only addresses qualified by bounded live reads on profile 170. */
internal enum class DtcEcu(val argument: String, val request: Int, val reply: Int) {
    BODY("body", 0x720, 0x728), LBMS("lbms", 0x762, 0x76A);
    val address get() = "0x%03X".format(reply)
    companion object {
        fun forNode(node: String): DtcEcu? = when (node) {
            "body" -> BODY
            "lbms", "lowv" -> LBMS
            else -> null
        }
    }
}

/** Raw OEM code stays authoritative; status bits are independent, never an ordinal severity. */
internal data class DtcRecord(val code: String, val status: Int) {
    val active get() = status and 1 != 0
    val pending get() = status and 4 != 0
    val confirmed get() = status and 8 != 0
    val warning get() = status and 128 != 0
    val label get() = when {
        active -> "Текущий отказ"
        pending -> "Ожидает подтверждения"
        confirmed -> "Сохранённый"
        else -> "Статус проверки"
    }
}

internal data class DtcSnapshot(
    val records: List<DtcRecord>,
    val availability: Int,
    val format: Int,
    val atMillis: Long,
)

internal class DiagnosticFailure(message: String) : Exception(message)

/** Only the single-frame envelope proved on this car. Truncation/timeout never produce zero. */
internal object DtcProtocol {
    fun payload(output: String, sub: Int, ecu: DtcEcu = DtcEcu.BODY): ByteArray {
        if ("TRANSPORT_RESULT 0" !in output || "END WINDOW_ENDED" !in output || "UNREGISTERED" !in output) {
            val why = when {
                "MANAGER_STATE" in output || "BUSY" in output || "PREEXISTING" in output -> "Диагностический канал занят"
                "SecurityException" in output -> "Нет разрешения на диагностический канал"
                else -> "Чтение DTC не завершено"
            }
            throw DiagnosticFailure(why)
        }
        val frames = Regex("(?m)^FRAME phase=response raw=([0-9A-Fa-f]+)\\s*$")
            .findAll(output).map { it.groupValues[1].uppercase() }.distinct().toList()
        if (frames.isEmpty()) throw DiagnosticFailure("ECU не ответил")
        if (frames.size != 1) throw DiagnosticFailure("Многочастный ответ пока не поддерживается")
        val raw = frames.single()
        if (raw.length % 2 != 0 || raw.length > 2048) throw DiagnosticFailure("Повреждённый ответ ECU")
        val b = raw.chunked(2).map { it.toInt(16) }
        if (b.size < 7 || b[0] != 10 || b[1] != (ecu.reply shr 8) || b[2] != (ecu.reply and 255)) throw DiagnosticFailure("Ответ другого ECU")
        if (b[4] != 1 || b[5] != 1) throw DiagnosticFailure("Неполный многочастный ответ")
        if (b[6] != 1) throw DiagnosticFailure(when (b[6]) {
            2 -> "ECU отклонил запрос" + (if (b.size >= 10 && b[3] >= 3) " · NRC %02X".format(b[9]) else "")
            3, 4 -> "ECU не ответил вовремя"
            5 -> "ECU занят"
            else -> "Ошибка диагностического канала"
        })
        val end = 7 + b[3]
        if (end > b.size || b[3] < 3 || b[7] != 0x59 || b[8] != sub) throw DiagnosticFailure("Повреждённый ответ UDS")
        return b.subList(7, end).map(Int::toByte).toByteArray()
    }

    fun decode(countOutput: String, listOutput: String, atMillis: Long, ecu: DtcEcu = DtcEcu.BODY): DtcSnapshot {
        val count = payload(countOutput, 1, ecu).map { it.toInt() and 255 }
        val list = payload(listOutput, 2, ecu).map { it.toInt() and 255 }
        if (count.size != 6 || (list.size - 3) % 4 != 0 || count[2] != list[2]) throw DiagnosticFailure("Несогласованный ответ DTC")
        val records = list.drop(3).chunked(4).map { r ->
            if (r[3] and list[2].inv() != 0) throw DiagnosticFailure("Неподдерживаемые биты статуса")
            DtcRecord("%02X%02X%02X".format(r[0], r[1], r[2]), r[3])
        }
        if (records.size != count[4] * 256 + count[5] || records.map { it.code }.distinct().size != records.size) {
            throw DiagnosticFailure("Количество и список DTC различаются; повторите чтение")
        }
        return DtcSnapshot(records, count[2], count[3], atMillis)
    }
}
