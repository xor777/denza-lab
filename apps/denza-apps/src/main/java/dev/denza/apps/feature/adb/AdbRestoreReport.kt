package dev.denza.apps.feature.adb

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The service page and the support screenshot use the same rows and neutral wording. */
object AdbRestoreReport {
    const val TITLE = "Восстановление ADB"
    const val NOTE = "Возвращает доступ после перезагрузки через Wi-Fi. Если доступ уже есть, восстановление не требуется."
    fun rows(snapshot: AdbRestoreSnapshot): List<Pair<String, String>> = listOf(
        "Состояние" to if (snapshot.lastTrigger == null && snapshot.enabled) "ещё не проверено" else snapshot.state.label(),
        "Триггер" to (snapshot.lastTrigger ?: "—"),
        "Последний исход" to listOfNotNull(snapshot.lastOutcome,
            snapshot.lastOutcomeAtMs?.let { SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT).format(Date(it)) }).joinToString(" · ").ifEmpty { "—" },
        "TLS-порт" to (snapshot.tlsPort?.toString() ?: "не найден"),
        "Wi-Fi" to (snapshot.wifi?.label ?: "не подключён"),
        "WRITE_SECURE_SETTINGS" to if (snapshot.permissionHeld) "выдано" else "не выдано",
        "Сетевой диалог" to (snapshot.autoAllow ?: "ещё не было"),
        "Повторы" to if (snapshot.retryBudgetExhausted) "бюджет на этой сети исчерпан" else "в пределах бюджета",
    )
}
