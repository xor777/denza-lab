package dev.denza.apps.feature.cloud

/** Only fixed protocol codes reach UI. Neither native text nor identifiers are displayed. */
internal object CloudCustomMessages {
    fun error(code: String?): String = when (code) {
        "unsupported_firmware" -> "Эта прошивка пока не поддерживается"
        "unsupported_identity" -> "Не удалось проверить сертификат машины"
        "native_unavailable" -> "Не удалось запустить адаптер облака"
        "registration_rejected" -> "Облако отклонило регистрацию"
        "login_rejected" -> "Облако отклонило вход"
        "power_lost" -> "Машина выключена"
        "power_unavailable" -> "Не удалось прочитать питание машины"
        "lease_expired" -> "Служба связи остановилась"
        "session_failed", "worker_stalled" -> "Служба связи остановилась"
        "owner_changed", "owner_present" -> "Предыдущая сессия не закрыта"
        "config_changed" -> "Настройки изменились. Выключите и включите связь"
        "cleanup_uncertain" -> "Выключение не завершено"
        "stock_owner_competed" -> "Штатный сервис возобновил подключение"
        "network_retry" -> "Ожидаем соединения"
        "none", "stopped", "owner_stopped" -> "Нет связи с облаком"
        else -> "Не удалось выполнить запрос к адаптеру"
    }
}
