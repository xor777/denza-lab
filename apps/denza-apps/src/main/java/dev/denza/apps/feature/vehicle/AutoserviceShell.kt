package dev.denza.apps.feature.vehicle

/**
 * Turns the vehicle allowlist into one shell command and its output back into
 * numbers. Pure Kotlin with no Android imports, so the whole protocol is unit
 * tested on the JVM.
 *
 * One `service call` per value would mean one ADB round trip per value. The
 * reader instead chains the whole set into a single command and tags each call
 * with an echoed index, so a call that prints nothing (an unsupported feature id
 * on this generation) cannot shift the remaining answers onto the wrong signals.
 *
 * Expected per-call output:
 *
 * ```text
 * @@3
 * Result: Parcel(00000000 0000002b   '........')
 * ```
 */
internal object AutoserviceShell {

    const val SERVICE = "autoservice"

    private const val MARKER = "@@"

    private val PARCEL = Regex(
        """Parcel\(([0-9a-fA-F]{8})\s+([0-9a-fA-F]{8})""",
    )

    /** Sentinels the Binder returns instead of failing; never a reading. */
    private val SENTINEL_WORDS = intArrayOf(
        0xFFFFD8E3.toInt(), // -10013: wrong transact or direction
        0xFFFFD8E5.toInt(), // -10011: no data / not on this generation
        0xBF800000.toInt(), // -1.0f: invalid float
    )

    fun command(signals: List<VehicleSignal>): String =
        signals.mapIndexed { index, signal ->
            "echo $MARKER$index; service call $SERVICE ${signal.transact.code} " +
                "i32 ${signal.device} i32 ${signal.fid}"
        }.joinToString("; ")

    /**
     * Reads the payload word of every answered call and decodes it.
     *
     * A signal is absent from the result when its call printed nothing, when the
     * word is a sentinel, or when the decoded value cannot be true for its unit.
     * Absent means the dashboard draws a dash; it never means zero.
     */
    fun parse(output: String, signals: List<VehicleSignal>): Map<VehicleSignal, Double> {
        val values = LinkedHashMap<VehicleSignal, Double>()
        var index = -1
        var answered = false
        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith(MARKER) -> {
                    index = line.removePrefix(MARKER).toIntOrNull() ?: -1
                    answered = false
                }

                !answered && index in signals.indices -> {
                    val match = PARCEL.find(line)
                    if (match != null) {
                        answered = true
                        val word = match.groupValues[2].toLong(16).toInt()
                        val signal = signals[index]
                        decode(signal, word)?.let { values[signal] = it }
                    }
                }
            }
        }
        return values
    }

    /** Parcel word -> value in the signal's unit, or null when it cannot be one. */
    fun decode(signal: VehicleSignal, word: Int): Double? {
        if (SENTINEL_WORDS.any { it == word }) return null
        if (signal.invalid == word) return null
        val raw = when (signal.transact) {
            VehicleTransact.INT -> word.toDouble()
            VehicleTransact.FLOAT -> Float.fromBits(word).toDouble()
        }
        if (raw.isNaN() || raw.isInfinite()) return null
        val value = raw * signal.scale + signal.offset
        return value.takeIf { signal.kind.accepts(it) }
    }
}
