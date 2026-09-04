package dev.denza.apps.feature.vehicle.signal

import java.util.concurrent.Executor

/** A semantic value. Transport addresses such as FIDs never cross this boundary. */
internal sealed class VehicleSignalKey<T : Any>(val stableName: String)

internal object VehicleSignalKeys {
    data object TurnIndicatorMode : VehicleSignalKey<dev.denza.apps.feature.vehicle.signal.TurnIndicatorMode>(
        "turn-indicator-mode",
    )

    data object TurnSwitchPhase : VehicleSignalKey<dev.denza.apps.feature.vehicle.signal.TurnSwitchPhase>(
        "turn-switch-phase",
    )
}

internal data class VehicleSignalSourceId(val value: String)

internal data class VehicleSignalConsumerId(val value: String) {
    init {
        require(value.isNotBlank()) { "consumer id must not be blank" }
    }
}

internal data class VehicleSignalDemand<T : Any>(
    val key: VehicleSignalKey<T>,
    val maxVerificationAgeMs: Long,
) {
    init {
        require(maxVerificationAgeMs > 0L) { "freshness budget must be positive" }
    }
}

internal enum class VehicleSignalMissingReason {
    STARTING,
    SOURCE_DOWN,
    AUTH_REQUIRED,
    INVALID,
    AMBIGUOUS,
    UNSUPPORTED_PROFILE,
    STALE,
    STOPPED,
}

internal sealed interface VehicleSignalState<out T : Any> {
    data class Fresh<T : Any>(
        val value: T,
        /** When the value itself last changed or was read from the vehicle. */
        val observedAtElapsedMs: Long,
        /** When the live source last proved that this retained state is still valid. */
        val verifiedAtElapsedMs: Long,
    ) : VehicleSignalState<T>

    data class Missing(
        val reason: VehicleSignalMissingReason,
        val details: String = "",
    ) : VehicleSignalState<Nothing>
}

internal data class VehicleSignalSample<T : Any>(
    val key: VehicleSignalKey<T>,
    val state: VehicleSignalState<T>,
    val source: VehicleSignalSourceId,
    val sourceEpoch: Long,
    val sequence: Long,
    val publishedAtElapsedMs: Long,
)

/** A transient source event. Unlike a retained sample, this is never synthesized on connect. */
internal data class VehicleSignalEvent<T : Any>(
    val key: VehicleSignalKey<T>,
    val value: T,
    val source: VehicleSignalSourceId,
    val sourceEpoch: Long,
    val sequence: Long,
    val observedAtElapsedMs: Long,
    val publishedAtElapsedMs: Long,
)

internal sealed interface VehicleSignalEventNotice<out T : Any> {
    data class Event<T : Any>(val event: VehicleSignalEvent<T>) :
        VehicleSignalEventNotice<T>

    /** A subscriber must assume it may have missed a transient event after this notice. */
    data class Unavailable(
        val reason: VehicleSignalMissingReason,
        val details: String = "",
        val sourceEpoch: Long = 0L,
    ) : VehicleSignalEventNotice<Nothing>
}

internal interface VehicleSignalEventSubscription : AutoCloseable {
    override fun close()
}

internal class VehicleSignalSnapshot internal constructor(
    private val samples: Map<VehicleSignalKey<*>, VehicleSignalSample<*>> = emptyMap(),
) {
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> sample(key: VehicleSignalKey<T>): VehicleSignalSample<T>? =
        samples[key] as VehicleSignalSample<T>?

    internal fun replacing(sample: VehicleSignalSample<*>): VehicleSignalSnapshot =
        VehicleSignalSnapshot(samples + (sample.key to sample))
}

internal interface VehicleSignalLease : AutoCloseable {
    /** Applies this consumer's own freshness contract to the shared latest sample. */
    fun <T : Any> read(key: VehicleSignalKey<T>, nowElapsedMs: Long): VehicleSignalState<T>

    /**
     * Delivers only live transient events, never retained connection snapshots.
     *
     * Delivery is bounded and serialized per subscription, not across keys. Even an inline
     * [executor] runs off the source lane. Overflow or executor rejection retains an explicit
     * [VehicleSignalEventNotice.Unavailable]; rejection is retried only on new source traffic.
     * Closing suppresses queued callbacks, but cannot revoke a callback already executing.
     * The hub admits at most 16 delivery lanes, including closed lanes with stuck inline code.
     */
    fun <T : Any> subscribeEvents(
        key: VehicleSignalKey<T>,
        executor: Executor,
        listener: (VehicleSignalEventNotice<T>) -> Unit,
    ): VehicleSignalEventSubscription

    override fun close()
}

internal sealed interface VehicleSignalSourceUpdate {
    data class ConnectionStarting(val sourceEpoch: Long) : VehicleSignalSourceUpdate

    data class Sample<T : Any>(
        val key: VehicleSignalKey<T>,
        val value: T,
        val observedAtElapsedMs: Long,
        val verifiedAtElapsedMs: Long,
        val sourceEpoch: Long,
        val sequence: Long,
    ) : VehicleSignalSourceUpdate

    data class Event<T : Any>(
        val key: VehicleSignalKey<T>,
        val value: T,
        val observedAtElapsedMs: Long,
        val verifiedAtElapsedMs: Long,
        val sourceEpoch: Long,
        val sequence: Long,
    ) : VehicleSignalSourceUpdate

    data class Heartbeat(
        val sourceEpoch: Long,
        val verifiedAtElapsedMs: Long,
    ) : VehicleSignalSourceUpdate

    data class Missing(
        val sourceEpoch: Long,
        val reason: VehicleSignalMissingReason,
        val details: String = "",
        val key: VehicleSignalKey<*>? = null,
    ) : VehicleSignalSourceUpdate
}

/**
 * One bounded transport lane. Its implementation owns reconnect and backoff. Publish updates
 * serially in source order. Changing the requested-key union currently restarts the lane and
 * explicitly reports a discontinuity; no live reconfiguration capability is assumed.
 */
internal interface VehicleSignalSource {
    val id: VehicleSignalSourceId
    val keys: Set<VehicleSignalKey<*>>

    /** Must return quickly; only requested keys may be registered with the vehicle. */
    fun start(
        requestedKeys: Set<VehicleSignalKey<*>>,
        publish: (VehicleSignalSourceUpdate) -> Unit,
    )

    fun stop()
}
