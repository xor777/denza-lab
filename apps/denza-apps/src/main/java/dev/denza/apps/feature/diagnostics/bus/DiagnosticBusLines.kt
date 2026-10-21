package dev.denza.apps.feature.diagnostics.bus

import java.io.IOException

/**
 * The helper said `END err <code> <why>`: the session, not an ECU, has an answer.
 *
 * An [IOException] because the resident stream reports it as one - and closes itself, so after any
 * refusal the next exchange starts a new helper (docs/vehicle-diagnostics-architecture.md, 3.3).
 */
internal class HelperRefusal(val code: String, val why: String) : IOException("$code $why".trim())

/**
 * The app's half of the helper's line protocol ([DiagnosticBusMain], architecture 3.3): what goes
 * down the stream and what an answer means. Pure, so every shape of an answer is a JVM test.
 */
internal object DiagnosticBusLines {
    /** `x <seq> <address> <uds> <timeout-ms>`. */
    fun request(seq: Int, request: DiagRequest): String {
        require(seq >= 0) { "negative seq" }
        return "x $seq ${request.line}"
    }

    /** `g <ms>`: the helper's gap from its next exchange on. */
    fun gap(ms: Int): String {
        require(ms in 0..ResidentDiagnosticBus.MAX_GAP_MS) { "gap outside 0..${ResidentDiagnosticBus.MAX_GAP_MS} ms" }
        return "g $ms"
    }

    /**
     * The answer to [gap] for [ms]: the one line `g <ms>`, the gap now in force. Anything else is
     * a helper that does not speak this protocol, a program error.
     */
    fun gapTook(ms: Int, payload: String) {
        val lines = payload.split('\n').map(String::trim).filter(String::isNotEmpty)
        val line = lines.singleOrNull() ?: throw malformed(payload)
        if (line != "g $ms") throw malformed(line)
    }

    /**
     * The one line between `BEGIN` and `END ok`, as a reply to [request] sent as [seq]. Anything
     * else - another seq, a second line, a word out of place - is a helper that does not speak
     * this protocol, which is a program error and never a car state.
     */
    fun reply(request: DiagRequest, seq: Int, payload: String, atMillis: Long): DiagReply {
        val lines = payload.split('\n').map(String::trim).filter(String::isNotEmpty)
        val line = lines.singleOrNull() ?: throw malformed(payload)
        val words = line.split(' ')
        if (words.size < 2 || words[1] != seq.toString()) throw malformed(line)
        return when (words[0]) {
            "a" -> {
                if (words.size != 6) throw malformed(line)
                val frames = words[2].toIntOrNull()?.takeIf { it in 1..DiagnosticFrameAssembler.MAX_FRAMES }
                    ?: throw malformed(line)
                val uds = words[5].takeIf { it.length in 2..DiagnosticFrameAssembler.MAX_BYTES * 2 && HEX.matches(it) }
                    ?: throw malformed(line)
                DiagReply.Answer(request, bytes(uds), frames, atMillis, latency(words[3], line), nanos(words[4], line))
            }
            "n" -> {
                if (words.size != 5) throw malformed(line)
                val latencyMs = latency(words[3], line)
                val receivedNanos = nanos(words[4], line)
                when (words[2]) {
                    "3", "4" -> DiagReply.NoReply(request, words[2].toInt(), atMillis, latencyMs, receivedNanos)
                    "T" -> DiagReply.NoReply(request, 0, atMillis, latencyMs, receivedNanos)
                    "5" -> DiagReply.GatewayBusy(request, atMillis, latencyMs, receivedNanos)
                    else -> throw malformed(line)
                }
            }
            else -> throw malformed(line)
        }
    }

    /** What the app makes of an `END err` code; the table in architecture 3.3. */
    fun failure(refusal: HelperRefusal): DiagFailure = when (refusal.code) {
        "GUARD_PROFILE", "GUARD_PARK", "GUARD_SPEED" -> DiagFailure.NotParked(refusal.message.orEmpty())
        "LOCK_BUSY", "MANAGER_BUSY", "FOREIGN_TRAFFIC" -> DiagFailure.ChannelBusy(refusal.message.orEmpty())
        "FRAME_SHAPE", "TRANSPORT_REJECTED", "HELPER_FAILED" -> DiagFailure.ChannelLost(refusal)
        "REFUSED", "WRONG_UID" -> DiagFailure.Refused(refusal.message.orEmpty())
        else -> DiagFailure.Refused("unknown helper code: ${refusal.message}")
    }

    /**
     * The refusal inside the resident stream's error, which words it `the resident helper refused:
     * <status>` with the status after `END` (`err <code> <why>`); null for any other error.
     */
    fun refusal(error: IOException): HelperRefusal? {
        val message = error.message ?: return null
        if (!message.startsWith(REFUSED_PREFIX)) return null
        val words = message.removePrefix(REFUSED_PREFIX).trim().split(' ', limit = 3)
        if (words.size < 2 || words[0] != "err" || !CODE.matches(words[1])) return null
        return HelperRefusal(words[1], words.getOrElse(2) { "" }.trim())
    }

    private fun latency(word: String, line: String): Int =
        word.toIntOrNull()?.takeIf { it >= 0 } ?: throw malformed(line)

    private fun nanos(word: String, line: String): Long =
        word.toLongOrNull()?.takeIf { it >= 0 } ?: throw malformed(line)

    private fun malformed(what: String) =
        DiagFailure.Refused("malformed helper answer: ${what.trim().take(120)}")

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private const val REFUSED_PREFIX = "the resident helper refused: "
    private val HEX = Regex("(?:[0-9A-F]{2})+")
    private val CODE = Regex("[A-Z_]+")
}
