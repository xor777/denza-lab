package dev.denza.apps.feature.diagnostics.protocol

/**
 * A negative response code, ISO 14229-1 Annex A.1. [name] is the standard's identifier, [label]
 * a short Russian caption for the screen. An unknown code keeps its number and nothing else.
 */
internal class Nrc private constructor(val code: Int, val name: String, val label: String) {
    /** The ECU does not offer the service, sub-function or identifier: a fact, not a refusal. */
    val unsupported: Boolean get() = code in UNSUPPORTED

    /** `78`: the ECU asked to wait. As the last word of an exchange it means no answer came. */
    val pending: Boolean get() = code == RESPONSE_PENDING

    val hex: String get() = "%02X".format(code)

    override fun equals(other: Any?) = other is Nrc && other.code == code
    override fun hashCode() = code
    override fun toString() = "NRC $hex $name"

    companion object {
        const val RESPONSE_PENDING = 0x78
        private val UNSUPPORTED = setOf(0x11, 0x12, 0x31, 0x7E, 0x7F)

        private val KNOWN = listOf(
            Nrc(0x10, "generalReject", "общий отказ"),
            Nrc(0x11, "serviceNotSupported", "служба не поддерживается"),
            Nrc(0x12, "subFunctionNotSupported", "подфункция не поддерживается"),
            Nrc(0x13, "incorrectMessageLengthOrInvalidFormat", "неверная длина или формат"),
            Nrc(0x14, "responseTooLong", "ответ слишком длинный"),
            Nrc(0x21, "busyRepeatRequest", "занят, повторить"),
            Nrc(0x22, "conditionsNotCorrect", "условия не выполнены"),
            Nrc(0x31, "requestOutOfRange", "вне диапазона"),
            Nrc(0x33, "securityAccessDenied", "доступ закрыт"),
            Nrc(0x35, "invalidKey", "неверный ключ"),
            Nrc(0x36, "exceedNumberOfAttempts", "превышено число попыток"),
            Nrc(0x37, "requiredTimeDelayNotExpired", "задержка не истекла"),
            Nrc(0x78, "requestCorrectlyReceivedResponsePending", "ответ готовится"),
            Nrc(0x7E, "subFunctionNotSupportedInActiveSession", "подфункция не в этой сессии"),
            Nrc(0x7F, "serviceNotSupportedInActiveSession", "служба не в этой сессии"),
        ).associateBy { it.code }

        fun of(code: Int): Nrc {
            val c = code and 0xFF
            return KNOWN[c] ?: Nrc(c, "NRC_%02X".format(c), "NRC %02X".format(c))
        }
    }
}
