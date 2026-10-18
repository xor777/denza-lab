package dev.denza.apps.feature.diagnostics

/** A dated research index, not a discovered topology or an executable address list.
 * Source: research/vehicle-diagnostics/{ecu-inventory-cache,ota-ecu-prefix}-20261018.json.
 * Only DtcEcu owns qualified live-read targets; these display strings never create targets.
 */
internal object DiagnosticEvidence {
    val identity = DiagnosticNode("Не уточнён", "Имя блока 720 → 728", rows = listOf(
        "Запрос → ответ" to "720 → 728",
        "CDC · вариант 1" to "F195 / F194",
        "CDC · вариант 2" to "C295 / C294",
        "R_ZCU · вариант 2" to "C495 / C494",
        "Точное имя" to "Не доказано",
    ), notes = listOf("В карте один адрес у трёх записей", "Наборы идентификаторов различны", "По адресу нельзя выбрать имя ECU"))

    val inventory = DiagnosticNode("225 записей", "Кандидаты из кэша", rows = listOf(
        "Версионных записей" to "225",
        "Версия не получена" to "49",
        "Установка в авто" to "Не доказана",
        "Адреса в кэше" to "Отсутствуют",
        "Дата материалов" to "18.10.2026",
    ), notes = listOf("Кэш содержит варианты блоков", "Это не 225 опрошенных ECU", "Ошибки блоков здесь не читались"))

    val routes = DiagnosticNode("8 записей", "Частичная карта · кандидаты", rows = listOf(
        "Media · CAN" to "741 → 749",
        "ASSY · CAN" to "5E7 → 5EF",
        "F_ZCU · CAN" to "722 → 72A",
        "CDC_SOC · Ethernet" to "0010 / 0001",
        "CDC · F195/F194" to "720 → 728",
        "CDC · C295/C294" to "720 → 728",
        "R_ZCU · F195/F194" to "747 → 74F",
        "R_ZCU · C495/C494" to "720 → 728",
    ), notes = listOf("Ответ сервера · 18.10.2026", "Установка и DTC не подтверждены", "ДВС / HV / ABS: адресов здесь нет"))
}
