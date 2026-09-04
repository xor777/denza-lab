package dev.denza.apps.feature.vehicle.signal

internal sealed interface TurnIndicatorMode {
    val diagnosticName: String

    data object OFF : TurnIndicatorMode {
        override val diagnosticName = "off"
    }

    data object LEFT : TurnIndicatorMode {
        override val diagnosticName = "left"
    }

    data object RIGHT : TurnIndicatorMode {
        override val diagnosticName = "right"
    }

    data object HAZARD : TurnIndicatorMode {
        override val diagnosticName = "hazard"
    }

    /** A vendor-delivered value whose vehicle behaviour has not been observed and named yet. */
    data class VendorDefined(val rawValue: Int) : TurnIndicatorMode {
        override val diagnosticName = "vendor-$rawValue"
    }
}

/** Raw typed lever phase. Live cancellation produced opposite-side blips, so it is not intent. */
@JvmInline
internal value class TurnSwitchPhase(val rawValue: Int)

internal object TurnSignalDecoder {
    const val TURN_SWITCH_FID = 0x1330002C
    const val TURN_MODE_FID = 0x38A0002C

    fun indicatorMode(value: Int): TurnIndicatorMode = when (value) {
        1 -> TurnIndicatorMode.OFF
        2 -> TurnIndicatorMode.LEFT
        4 -> TurnIndicatorMode.RIGHT
        6 -> TurnIndicatorMode.HAZARD
        else -> TurnIndicatorMode.VendorDefined(value)
    }

    fun switchPhase(value: Int) = TurnSwitchPhase(value)
}

internal sealed interface TurnSignalBatchResult {
    data class Updates(val values: List<VehicleSignalSourceUpdate>) : TurnSignalBatchResult
    data class Reconnect(val reason: String) : TurnSignalBatchResult
}

/** Strict decoder for the tiny line protocol emitted by the shell-UID listener. */
internal class TurnSignalBatchDecoder {
    private var lastSequence = 0L

    fun decode(
        response: String,
        sourceEpoch: Long,
        publishedAtElapsedMs: Long,
    ): TurnSignalBatchResult {
        val lines = response.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.isEmpty()) return TurnSignalBatchResult.Reconnect("empty event batch")
        val updates = mutableListOf<VehicleSignalSourceUpdate>()
        for (line in lines) {
            val words = line.split(Regex("\\s+"))
            when (words.firstOrNull()) {
                "S", "E" -> {
                    if (words.size != 5) {
                        return TurnSignalBatchResult.Reconnect("malformed event record")
                    }
                    val sequence = words[1].toLongOrNull()
                        ?: return TurnSignalBatchResult.Reconnect("invalid event sequence")
                    if (sequence != lastSequence + 1L) {
                        return TurnSignalBatchResult.Reconnect(
                            "event sequence gap ${lastSequence}->$sequence",
                        )
                    }
                    val observedAtNanos = words[2].toLongOrNull()
                        ?: return TurnSignalBatchResult.Reconnect("invalid event timestamp")
                    val observedAtElapsedMs = observedAtNanos / 1_000_000L
                    // Both processes use Android's elapsed-realtime clock (including suspend).
                    // Arrival cannot re-verify a value that waited in a helper/transport queue.
                    if (observedAtNanos < 0L || observedAtElapsedMs > publishedAtElapsedMs) {
                        return TurnSignalBatchResult.Reconnect("event timestamp outside elapsed clock")
                    }
                    val fid = words[3].toIntOrNull()
                        ?: return TurnSignalBatchResult.Reconnect("invalid event fid")
                    val value = words[4].toIntOrNull()
                        ?: return TurnSignalBatchResult.Reconnect("invalid event value")
                    lastSequence = sequence
                    updates += decodeObservation(
                        words[0],
                        fid,
                        value,
                        observedAtElapsedMs,
                        observedAtElapsedMs,
                        sourceEpoch,
                        sequence,
                    ) ?: return TurnSignalBatchResult.Reconnect("unexpected event fid $fid")
                }
                "H" -> {
                    if (words.size != 2) {
                        return TurnSignalBatchResult.Reconnect("malformed heartbeat")
                    }
                    val atNanos = words[1].toLongOrNull()
                        ?: return TurnSignalBatchResult.Reconnect("invalid heartbeat timestamp")
                    updates += VehicleSignalSourceUpdate.Heartbeat(sourceEpoch, atNanos / 1_000_000L)
                }
                "O" -> return TurnSignalBatchResult.Reconnect("event queue overflow")
                "X" -> return TurnSignalBatchResult.Reconnect("vendor listener failure")
                else -> return TurnSignalBatchResult.Reconnect("unknown event record")
            }
        }
        return TurnSignalBatchResult.Updates(updates)
    }

    private fun decodeObservation(
        recordType: String,
        fid: Int,
        value: Int,
        observedAtElapsedMs: Long,
        verifiedAtElapsedMs: Long,
        sourceEpoch: Long,
        sequence: Long,
    ): VehicleSignalSourceUpdate? = when (fid) {
        TurnSignalDecoder.TURN_SWITCH_FID -> observation(
            recordType,
            VehicleSignalKeys.TurnSwitchPhase,
            TurnSignalDecoder.switchPhase(value),
            observedAtElapsedMs,
            verifiedAtElapsedMs,
            sourceEpoch,
            sequence,
        )
        TurnSignalDecoder.TURN_MODE_FID -> observation(
            recordType,
            VehicleSignalKeys.TurnIndicatorMode,
            TurnSignalDecoder.indicatorMode(value),
            observedAtElapsedMs,
            verifiedAtElapsedMs,
            sourceEpoch,
            sequence,
        )
        else -> null
    }

    private fun <T : Any> observation(
        recordType: String,
        key: VehicleSignalKey<T>,
        value: T,
        observedAtElapsedMs: Long,
        verifiedAtElapsedMs: Long,
        sourceEpoch: Long,
        sequence: Long,
    ): VehicleSignalSourceUpdate = if (recordType == "E") {
        VehicleSignalSourceUpdate.Event(
            key,
            value,
            observedAtElapsedMs,
            verifiedAtElapsedMs,
            sourceEpoch,
            sequence,
        )
    } else {
        VehicleSignalSourceUpdate.Sample(
            key,
            value,
            observedAtElapsedMs,
            verifiedAtElapsedMs,
            sourceEpoch,
            sequence,
        )
    }
}
