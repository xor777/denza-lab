package dev.denza.apps.feature.diagnostics.bus

import kotlinx.coroutines.flow.Flow

/**
 * One physical request to one ECU on the car's diagnostic channel. [address] is the request id;
 * the ECU answers on [reply]. Only what [DiagnosticWhitelist] allows can be made at all.
 */
internal class DiagRequest(val address: Int, uds: ByteArray, val timeoutMs: Int = DEFAULT_TIMEOUT_MS) {
    private val bytes = uds.copyOf()
    val uds: ByteArray get() = bytes.copyOf()
    val reply: Int get() = address + 8

    init {
        DiagnosticWhitelist.refusal(address, bytes)?.let { throw IllegalArgumentException(it) }
        require(timeoutMs in 1..MAX_TIMEOUT_MS) { "timeout outside 1..$MAX_TIMEOUT_MS ms" }
    }

    /** The helper's wire form: `<address> <uds> <timeout>`, upper-case hex. */
    val line: String get() = "%03X %s %d".format(address, hex(bytes), timeoutMs)

    override fun equals(other: Any?) = other is DiagRequest && other.address == address &&
        other.timeoutMs == timeoutMs && other.bytes.contentEquals(bytes)
    override fun hashCode() = (address * 31 + bytes.contentHashCode()) * 31 + timeoutMs
    override fun toString() = "%03X %s".format(address, hex(bytes))

    companion object {
        const val DEFAULT_TIMEOUT_MS = 4_000
        const val MAX_TIMEOUT_MS = 6_000
    }
}

/**
 * What one ECU said, or that it said nothing. A fault of the channel itself is a [DiagFailure].
 *
 * Every reply carries its timing as the car measured it, because the stream's chart and the
 * acceptance's step are measured, not assumed: [latencyMs] runs from the helper's `set()` to the
 * last frame of the answer, or to the decision that there is none; [receivedNanos] is that moment
 * on the car's `SystemClock.elapsedRealtimeNanos`, so the gaps between two replies are the car's
 * gaps and not the ADB stream's.
 */
internal sealed interface DiagReply {
    val request: DiagRequest
    /** Wall clock when the exchange ended. */
    val atMillis: Long
    val latencyMs: Int
    val receivedNanos: Long

    /** A complete answer: positive (service + 0x40) or negative (`7F <service> <nrc>`). */
    class Answer(
        override val request: DiagRequest,
        uds: ByteArray,
        val frames: Int,
        override val atMillis: Long,
        override val latencyMs: Int = 0,
        override val receivedNanos: Long = 0L,
    ) : DiagReply {
        private val bytes = uds.copyOf()
        val uds: ByteArray get() = bytes.copyOf()
        /** The negative response code, or null for a positive answer. */
        val nrc: Int? get() = if (bytes.size >= 3 && bytes[0] == 0x7F.toByte()) bytes[2].toInt() and 0xFF else null
        override fun toString() = "$request → ${hex(bytes)}"
    }

    /** The gateway said 3 (no response) or 4 (diagnostic timeout); 0 when the helper's own wait ran out. */
    class NoReply(
        override val request: DiagRequest,
        val gatewayResult: Int,
        override val atMillis: Long,
        override val latencyMs: Int = 0,
        override val receivedNanos: Long = 0L,
    ) : DiagReply

    /** The gateway said 5: busy. */
    class GatewayBusy(
        override val request: DiagRequest,
        override val atMillis: Long,
        override val latencyMs: Int = 0,
        override val receivedNanos: Long = 0L,
    ) : DiagReply
}

/** The channel, not an ECU, failed. Nothing here is retried in a loop (architecture, 3.5). */
internal sealed class DiagFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No trusted local ADB, so no shell. */
    class NoAccess(cause: Throwable? = null) : DiagFailure("no trusted ADB access", cause)
    /** The car is not profile 170, not in P or not at 0 km/h; the request was not sent. */
    class NotParked(val why: String) : DiagFailure(why)
    /** Another reader holds the lock, the stock diagnostic manager is busy, or foreign traffic was seen. */
    class ChannelBusy(val why: String) : DiagFailure(why)
    /** The helper or its ADB stream went away. */
    class ChannelLost(cause: Throwable? = null) : DiagFailure("diagnostic channel lost", cause)
    /** The helper refused the line: a program error, never a car state. */
    class Refused(val why: String) : DiagFailure(why)
}

/**
 * One record of the BMS's own pages on the GB stream (device 1039, event `9900001F`; architecture
 * 6.3): CAN id `43A` (the counts), `43C` (six probe codes) or `43D` (three cell words), its payload
 * as it came, and when the helper's listener got it on the car's `elapsedRealtimeNanos`.
 */
internal class BmsPage(val canId: Int, payload: ByteArray, val receivedNanos: Long) {
    private val bytes = payload.copyOf()
    val payload: ByteArray get() = bytes.copyOf()
    val size: Int get() = bytes.size
    /** The payload's byte at [index], 0..255. */
    operator fun get(index: Int): Int = bytes[index].toInt() and 0xFF
    override fun toString() = "%03X %s @%d".format(canId, hex(bytes), receivedNanos)

    companion object {
        const val COUNTS = 0x43A
        const val PROBES = 0x43C
        const val CELLS = 0x43D
    }
}

/** The car's diagnostic channel: one exchange at a time, read-only by [DiagnosticWhitelist]. */
internal interface DiagnosticBus : AutoCloseable {
    /** Returns what the ECU said; throws [DiagFailure] when the channel itself failed. */
    suspend fun exchange(request: DiagRequest): DiagReply

    /**
     * The BMS's pages while collected (architecture 6.3, helper lines `s gb` / `u gb`): collecting
     * subscribes, cancelling leaves. Listening only - nothing goes to the car - and exchanges keep
     * their turn while it runs. It ends with a [DiagFailure] when the helper that serves it is lost
     * or its session ends; asking again is the caller's decision, never this flow's.
     */
    fun batteryPages(): Flow<BmsPage>

    /**
     * The helper's gap between two exchanges, 0..500 ms, from the next exchange on (architecture,
     * 3.3, `g <ms>`). A helper opened later gets it too; the gap a helper starts with stays the
     * launch parameter.
     */
    fun setGap(ms: Int)
}

internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
