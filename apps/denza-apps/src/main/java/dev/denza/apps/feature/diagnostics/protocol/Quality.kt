package dev.denza.apps.feature.diagnostics.protocol

import dev.denza.apps.feature.diagnostics.bus.DiagReply

/** What decoding one complete answer gave. */
internal sealed interface Decoded<out T> {
    /** A positive answer to this request, consistent with itself. */
    data class Ok<out T>(val value: T) : Decoded<T>

    /** `7F <service> <nrc>` for the service that was asked. */
    data class Negative(val nrc: Nrc) : Decoded<Nothing>

    /** Not an answer to this request, cut short, or at odds with itself: never «0 codes». */
    data class Malformed(val why: String) : Decoded<Nothing>
}

internal inline fun <T, R> Decoded<T>.map(f: (T) -> R): Decoded<R> = when (this) {
    is Decoded.Ok -> Decoded.Ok(f(value))
    is Decoded.Negative -> this
    is Decoded.Malformed -> this
}

/** A result that can be complete and still hold nothing: a DTC memory, an OBD code list. */
internal interface MaybeEmpty {
    val isEmpty: Boolean
}

/**
 * One reading of one thing from one ECU, with how good the data is kept apart from what the
 * car says (architecture, 4). Built from a [DiagReply] by [Polled.of].
 */
internal sealed interface Polled<out T> {
    /** Never asked. */
    data object NotPolled : Polled<Nothing>

    /** The gateway said no response (3) or diagnostic timeout (4), the helper's wait ran out (0),
     * or the ECU's last word was `78` (still preparing). */
    data class NoReply(val gatewayResult: Int, val atMillis: Long) : Polled<Nothing>

    /** The gateway said 5: busy. */
    data class Busy(val atMillis: Long) : Polled<Nothing>

    /** A negative answer that is a refusal: conditions, security, a general reject. */
    data class Denied(val nrc: Nrc, val atMillis: Long) : Polled<Nothing>

    /** The ECU does not offer it: NRC 11/12/31/7E/7F, or its own bitmap leaves it out ([nrc] null). */
    data class Unsupported(val nrc: Nrc?, val atMillis: Long) : Polled<Nothing>

    /** An answer came and does not hold together. */
    data class Inconsistent(val why: String, val atMillis: Long) : Polled<Nothing>

    /** A complete, consistent answer. */
    data class Got<out T>(val value: T, val atMillis: Long) : Polled<T>

    companion object {
        /** The engine's one entry point: an exchange's reply through one of [DiagDecoder]'s functions. */
        fun <T> of(reply: DiagReply, decode: (DiagReply.Answer) -> Decoded<T>): Polled<T> = when (reply) {
            is DiagReply.NoReply -> NoReply(reply.gatewayResult, reply.atMillis)
            is DiagReply.GatewayBusy -> Busy(reply.atMillis)
            is DiagReply.Answer -> when (val d = decode(reply)) {
                is Decoded.Ok -> Got(d.value, reply.atMillis)
                is Decoded.Negative -> when {
                    d.nrc.pending -> NoReply(0, reply.atMillis)
                    d.nrc.unsupported -> Unsupported(d.nrc, reply.atMillis)
                    else -> Denied(d.nrc, reply.atMillis)
                }
                is Decoded.Malformed -> Inconsistent(d.why, reply.atMillis)
            }
        }
    }
}

/** When the reading was taken; null for [Polled.NotPolled]. */
internal val Polled<*>.atMillis: Long?
    get() = when (this) {
        Polled.NotPolled -> null
        is Polled.NoReply -> atMillis
        is Polled.Busy -> atMillis
        is Polled.Denied -> atMillis
        is Polled.Unsupported -> atMillis
        is Polled.Inconsistent -> atMillis
        is Polled.Got -> atMillis
    }

/**
 * Data quality as a screen names it. Only [COMPLETE_EMPTY] lets a screen say «ошибок нет», and
 * only for what was asked (the mask, the mode).
 */
internal enum class DataQuality(val label: String) {
    NOT_POLLED("не читали"),
    NO_REPLY("нет ответа"),
    BUSY("шлюз занят"),
    DENIED("отказано"),
    UNSUPPORTED("не поддерживается"),
    INCONSISTENT("несогласованный ответ"),
    STALE("устарело"),
    COMPLETE("прочитано"),
    COMPLETE_EMPTY("полный ответ, кодов нет");

    val provesNone: Boolean get() = this == COMPLETE_EMPTY
}

/** The quality at [nowMillis]: a [Polled.Got] older than [maxAgeMs] is [DataQuality.STALE]. */
internal fun Polled<*>.quality(nowMillis: Long, maxAgeMs: Long): DataQuality = when (this) {
    Polled.NotPolled -> DataQuality.NOT_POLLED
    is Polled.NoReply -> DataQuality.NO_REPLY
    is Polled.Busy -> DataQuality.BUSY
    is Polled.Denied -> DataQuality.DENIED
    is Polled.Unsupported -> DataQuality.UNSUPPORTED
    is Polled.Inconsistent -> DataQuality.INCONSISTENT
    is Polled.Got -> when {
        nowMillis - atMillis > maxAgeMs || nowMillis < atMillis -> DataQuality.STALE
        (value as? MaybeEmpty)?.isEmpty == true -> DataQuality.COMPLETE_EMPTY
        else -> DataQuality.COMPLETE
    }
}
