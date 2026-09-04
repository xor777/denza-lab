package dev.denza.apps.feature.vehicle.signal

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local, demand-driven distribution of typed read-only vehicle observations.
 *
 * The hub knows no Binder address, FID, shell command, camera command, or UI model. A source starts
 * when its first lease appears, is shared by every consumer of its keys, and stops with its final
 * lease. Every activation has a generation fence so a late callback from a stopped source cannot
 * enter a later activation.
 */
internal class VehicleSignalHub(
    sources: List<VehicleSignalSource>,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val lock = Any()
    private val sourceById = sources.associateBy(VehicleSignalSource::id)
    private val sourceSlots = sourceById.mapValues { SourceSlot() }
    private val sourceByKey: Map<VehicleSignalKey<*>, VehicleSignalSource> = buildMap {
        sources.forEach { source ->
            source.keys.forEach { key ->
                check(put(key, source) == null) {
                    "more than one source owns ${key.stableName}"
                }
            }
        }
    }
    @Volatile private var snapshot = VehicleSignalSnapshot()
    private val leases = mutableMapOf<Long, LeaseRecord>()
    private val keyLeaseCounts = mutableMapOf<VehicleSignalKey<*>, Int>()
    private val sourceActivations = mutableMapOf<VehicleSignalSourceId, SourceActivation>()
    private val sourceEpochs = mutableMapOf<VehicleSignalSourceId, Long>()
    private var nextLeaseId = 0L
    private var nextActivation = 0L

    init {
        require(sourceById.size == sources.size) { "vehicle signal source ids must be unique" }
    }

    fun acquire(
        consumer: VehicleSignalConsumerId,
        demands: Set<VehicleSignalDemand<*>>,
    ): VehicleSignalLease {
        require(demands.isNotEmpty()) { "a vehicle signal lease needs at least one demand" }
        val demandByKey = demands.associateBy(VehicleSignalDemand<*>::key)
        require(demandByKey.size == demands.size) { "a signal may be demanded only once per lease" }
        val requestedSources = demandByKey.keys.map { key ->
            requireNotNull(sourceByKey[key]) { "no source registered for ${key.stableName}" }
        }.toSet()

        val reconcile = mutableSetOf<VehicleSignalSource>()
        val leaseId: Long
        synchronized(lock) {
            leaseId = ++nextLeaseId
            leases[leaseId] = LeaseRecord(consumer, demandByKey, requestedSources.map { it.id }.toSet())
            demandByKey.keys.forEach { key ->
                keyLeaseCounts[key] = (keyLeaseCounts[key] ?: 0) + 1
            }
            requestedSources.forEach { source ->
                if (refreshSourceDemandLocked(source)) reconcile += source
            }
        }
        reconcile.forEach(::reconcile)
        return Lease(leaseId, demandByKey)
    }

    private fun accept(
        source: VehicleSignalSource,
        activationId: Long,
        update: VehicleSignalSourceUpdate,
    ) {
        val deliveries: List<Pair<EventSubscription<*>, VehicleSignalEventNotice<*>>> =
            synchronized(lock) {
            val activation = sourceActivations[source.id]
            if (activation?.id != activationId) return@synchronized emptyList()
            when (update) {
                is VehicleSignalSourceUpdate.ConnectionStarting -> {
                    sourceEpochs[source.id] = update.sourceEpoch
                    markKeysMissingLocked(
                        source,
                        activation.keys,
                        update.sourceEpoch,
                        VehicleSignalMissingReason.STARTING,
                        "connection starting",
                    )
                    unavailableDeliveriesLocked(
                        activation.keys,
                        VehicleSignalMissingReason.STARTING,
                        "connection starting",
                        update.sourceEpoch,
                    )
                }
                is VehicleSignalSourceUpdate.Sample<*> -> {
                    if (update.key !in activation.keys) return@synchronized emptyList()
                    if (sourceEpochs[source.id] != update.sourceEpoch) {
                        return@synchronized emptyList()
                    }
                    replaceSampleLocked(source, update)
                    emptyList()
                }
                is VehicleSignalSourceUpdate.Event<*> -> {
                    if (update.key !in activation.keys) return@synchronized emptyList()
                    if (sourceEpochs[source.id] != update.sourceEpoch) {
                        return@synchronized emptyList()
                    }
                    replaceEventLocked(source, update)
                    eventDeliveriesLocked(source, update)
                }
                is VehicleSignalSourceUpdate.Heartbeat -> {
                    if (sourceEpochs[source.id] != update.sourceEpoch) {
                        return@synchronized emptyList()
                    }
                    // This proves only that the helper channel answered. It does not read the value
                    // again and therefore must not renew any signal's freshness timestamp.
                    emptyList()
                }
                is VehicleSignalSourceUpdate.Missing -> {
                    if (sourceEpochs[source.id] != update.sourceEpoch && update.sourceEpoch != 0L) {
                        return@synchronized emptyList()
                    }
                    if (update.key == null) {
                        markKeysMissingLocked(
                            source,
                            activation.keys,
                            update.sourceEpoch,
                            update.reason,
                            update.details,
                        )
                    } else if (update.key in activation.keys) {
                        replaceMissingLocked(source, update.key, update)
                    }
                    unavailableDeliveriesLocked(
                        update.key?.let(::setOf) ?: activation.keys,
                        update.reason,
                        update.details,
                        update.sourceEpoch,
                    )
                }
            }
        }
        deliveries.forEach { (subscription, notice) -> subscription.offerUntyped(notice) }
    }

    private fun replaceSampleLocked(
        source: VehicleSignalSource,
        update: VehicleSignalSourceUpdate.Sample<*>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val typed = update as VehicleSignalSourceUpdate.Sample<Any>
        val sample = VehicleSignalSample(
            key = typed.key,
            state = VehicleSignalState.Fresh(
                typed.value,
                typed.observedAtElapsedMs,
                typed.verifiedAtElapsedMs,
            ),
            source = source.id,
            sourceEpoch = typed.sourceEpoch,
            sequence = typed.sequence,
            publishedAtElapsedMs = clock(),
        )
        snapshot = snapshot.replacing(sample)
    }

    private fun replaceEventLocked(
        source: VehicleSignalSource,
        update: VehicleSignalSourceUpdate.Event<*>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val typed = update as VehicleSignalSourceUpdate.Event<Any>
        snapshot = snapshot.replacing(
            VehicleSignalSample(
                key = typed.key,
                state = VehicleSignalState.Fresh(
                    typed.value,
                    typed.observedAtElapsedMs,
                    typed.verifiedAtElapsedMs,
                ),
                source = source.id,
                sourceEpoch = typed.sourceEpoch,
                sequence = typed.sequence,
                publishedAtElapsedMs = clock(),
            ),
        )
    }

    private fun eventDeliveriesLocked(
        source: VehicleSignalSource,
        update: VehicleSignalSourceUpdate.Event<*>,
    ): List<Pair<EventSubscription<*>, VehicleSignalEventNotice<*>>> {
        val publishedAt = clock()
        @Suppress("UNCHECKED_CAST")
        val typed = update as VehicleSignalSourceUpdate.Event<Any>
        val notice = VehicleSignalEventNotice.Event(
            VehicleSignalEvent(
                key = typed.key,
                value = typed.value,
                source = source.id,
                sourceEpoch = typed.sourceEpoch,
                sequence = typed.sequence,
                observedAtElapsedMs = typed.observedAtElapsedMs,
                publishedAtElapsedMs = publishedAt,
            ),
        )
        return subscriptionsForKeyLocked(update.key).map { it to notice }
    }

    private fun unavailableDeliveriesLocked(
        keys: Set<VehicleSignalKey<*>>,
        reason: VehicleSignalMissingReason,
        details: String,
        sourceEpoch: Long,
    ): List<Pair<EventSubscription<*>, VehicleSignalEventNotice<*>>> {
        val notice = VehicleSignalEventNotice.Unavailable(reason, details, sourceEpoch)
        return keys.flatMap { key -> subscriptionsForKeyLocked(key).map { it to notice } }
    }

    private fun subscriptionsForKeyLocked(key: VehicleSignalKey<*>): List<EventSubscription<*>> =
        leases.values.flatMap { record -> record.subscriptions.filter { it.key == key } }

    private fun markKeysMissingLocked(
        source: VehicleSignalSource,
        keys: Set<VehicleSignalKey<*>>,
        sourceEpoch: Long,
        reason: VehicleSignalMissingReason,
        details: String,
    ) {
        keys.forEach { key ->
            replaceMissingLocked(
                source,
                key,
                VehicleSignalSourceUpdate.Missing(sourceEpoch, reason, details, key),
            )
        }
    }

    private fun replaceMissingLocked(
        source: VehicleSignalSource,
        key: VehicleSignalKey<*>,
        update: VehicleSignalSourceUpdate.Missing,
    ) {
        @Suppress("UNCHECKED_CAST")
        val typedKey = key as VehicleSignalKey<Any>
        snapshot = snapshot.replacing(
            VehicleSignalSample(
                key = typedKey,
                state = VehicleSignalState.Missing(update.reason, update.details),
                source = source.id,
                sourceEpoch = update.sourceEpoch,
                sequence = 0L,
                publishedAtElapsedMs = clock(),
            ),
        )
    }

    private fun release(leaseId: Long) {
        val reconcile = mutableSetOf<VehicleSignalSource>()
        val subscriptions: List<EventSubscription<*>>
        synchronized(lock) {
            val lease = leases.remove(leaseId) ?: return
            subscriptions = lease.subscriptions.toList()
            lease.subscriptions.clear()
            lease.demands.keys.forEach { key ->
                val remaining = (keyLeaseCounts[key] ?: 1) - 1
                if (remaining > 0) {
                    keyLeaseCounts[key] = remaining
                } else {
                    keyLeaseCounts.remove(key)
                }
            }
            lease.sources.forEach { sourceId ->
                sourceById[sourceId]?.let { source ->
                    if (refreshSourceDemandLocked(source)) reconcile += source
                }
            }
        }
        subscriptions.forEach(EventSubscription<*>::deactivate)
        reconcile.forEach(::reconcile)
    }

    private fun <T : Any> subscribe(
        leaseId: Long,
        key: VehicleSignalKey<T>,
        executor: Executor,
        listener: (VehicleSignalEventNotice<T>) -> Unit,
    ): VehicleSignalEventSubscription {
        val subscription = EventSubscription(key, executor, listener) {
            unsubscribe(leaseId, it)
        }
        synchronized(lock) {
            val lease = requireNotNull(leases[leaseId]) { "vehicle signal lease is closed" }
            require(key in lease.demands) { "${key.stableName} was not demanded" }
            lease.subscriptions += subscription
        }
        return subscription
    }

    private fun unsubscribe(leaseId: Long, subscription: EventSubscription<*>) {
        synchronized(lock) { leases[leaseId]?.subscriptions?.remove(subscription) }
        subscription.deactivate()
    }

    /** Replaces one source activation exactly when its union of demanded keys changes. */
    private fun refreshSourceDemandLocked(source: VehicleSignalSource): Boolean {
        val previous = sourceActivations[source.id]
        val requested = source.keys.filterTo(linkedSetOf()) { (keyLeaseCounts[it] ?: 0) > 0 }
        if (previous?.keys == requested) return false

        sourceEpochs.remove(source.id)
        val removed = previous?.keys.orEmpty() - requested
        if (removed.isNotEmpty()) {
            markKeysMissingLocked(
                source,
                removed,
                0L,
                VehicleSignalMissingReason.STOPPED,
                "no active consumers",
            )
        }
        if (requested.isEmpty()) {
            sourceActivations.remove(source.id)
        } else {
            sourceActivations[source.id] = SourceActivation(++nextActivation, requested)
            markKeysMissingLocked(
                source,
                requested,
                0L,
                VehicleSignalMissingReason.STARTING,
                "source starting",
            )
        }
        return true
    }

    /**
     * Serialises one source without calling external code under the Hub's global state lock.
     *
     * The loop is intentional: demand may change while start/stop is in flight. The next pass
     * reconciles the physical source with the newest desired activation, so an old stop can never
     * tear down a newer lease and a new start can never race an old worker that still exists.
     */
    private fun reconcile(source: VehicleSignalSource) {
        val slot = requireNotNull(sourceSlots[source.id])
        synchronized(slot.lifecycleLock) {
            while (true) {
                val desired = synchronized(lock) { sourceActivations[source.id] }
                if (slot.runningActivation == desired) return
                if (slot.runningActivation != null) {
                    try {
                        source.stop()
                    } catch (_: Exception) {
                        // The desired state is still reconciled below. A source must fence its
                        // own old worker; the Hub also fences every late publication by activation.
                    }
                    slot.runningActivation = null
                    continue
                }
                if (desired == null) return
                try {
                    source.start(desired.keys) { update -> accept(source, desired.id, update) }
                    slot.runningActivation = desired
                } catch (error: Exception) {
                    try {
                        source.stop()
                    } catch (_: Exception) {
                        // Preserve the original start failure in the signal state.
                    }
                    accept(
                        source,
                        desired.id,
                        VehicleSignalSourceUpdate.Missing(
                            sourceEpoch = 0L,
                            reason = VehicleSignalMissingReason.SOURCE_DOWN,
                            details = error.message ?: error::class.java.simpleName,
                        ),
                    )
                    return
                }
            }
        }
    }

    private inner class Lease(
        private val id: Long,
        private val demands: Map<VehicleSignalKey<*>, VehicleSignalDemand<*>>,
    ) : VehicleSignalLease {
        private val closed = AtomicBoolean()
        override fun <T : Any> read(
            key: VehicleSignalKey<T>,
            nowElapsedMs: Long,
        ): VehicleSignalState<T> {
            check(!closed.get()) { "vehicle signal lease is closed" }
            val demand = requireNotNull(demands[key]) { "${key.stableName} was not demanded" }
            val state = snapshot.sample(key)?.state
                ?: VehicleSignalState.Missing(VehicleSignalMissingReason.STARTING)
            if (
                state is VehicleSignalState.Fresh &&
                nowElapsedMs - state.verifiedAtElapsedMs > demand.maxVerificationAgeMs
            ) {
                return VehicleSignalState.Missing(
                    VehicleSignalMissingReason.STALE,
                    "last verified ${nowElapsedMs - state.verifiedAtElapsedMs} ms ago",
                )
            }
            return state
        }

        override fun <T : Any> subscribeEvents(
            key: VehicleSignalKey<T>,
            executor: Executor,
            listener: (VehicleSignalEventNotice<T>) -> Unit,
        ): VehicleSignalEventSubscription {
            check(!closed.get()) { "vehicle signal lease is closed" }
            return subscribe(id, key, executor, listener)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) release(id)
        }
    }

    private data class LeaseRecord(
        val consumer: VehicleSignalConsumerId,
        val demands: Map<VehicleSignalKey<*>, VehicleSignalDemand<*>>,
        val sources: Set<VehicleSignalSourceId>,
        val subscriptions: MutableSet<EventSubscription<*>> = linkedSetOf(),
    )

    private data class SourceActivation(
        val id: Long,
        val keys: Set<VehicleSignalKey<*>>,
    )

    private class SourceSlot {
        val lifecycleLock = Any()
        var runningActivation: SourceActivation? = null
    }

    /** A small per-consumer mailbox: FIFO while healthy, explicit gap instead of an unbounded queue. */
    private class EventSubscription<T : Any>(
        val key: VehicleSignalKey<T>,
        private val executor: Executor,
        private val listener: (VehicleSignalEventNotice<T>) -> Unit,
        private val onClose: (EventSubscription<T>) -> Unit,
    ) : VehicleSignalEventSubscription {
        private val lock = Any()
        private val queue = ArrayDeque<VehicleSignalEventNotice<T>>()
        private var drainScheduled = false
        private var closed = false

        @Suppress("UNCHECKED_CAST")
        fun offerUntyped(notice: VehicleSignalEventNotice<*>) {
            offer(notice as VehicleSignalEventNotice<T>)
        }

        private fun offer(notice: VehicleSignalEventNotice<T>) {
            val shouldSchedule = synchronized(lock) {
                if (closed) return
                if (queue.size >= EVENT_MAILBOX_CAPACITY) {
                    queue.clear()
                    queue.addLast(
                        VehicleSignalEventNotice.Unavailable(
                            VehicleSignalMissingReason.AMBIGUOUS,
                            "consumer event mailbox overflow",
                        ),
                    )
                } else {
                    queue.addLast(notice)
                }
                if (drainScheduled) {
                    false
                } else {
                    drainScheduled = true
                    true
                }
            }
            if (!shouldSchedule) return
            try {
                executor.execute(::drain)
            } catch (_: RuntimeException) {
                synchronized(lock) {
                    drainScheduled = false
                    queue.clear()
                }
            }
        }

        private fun drain() {
            while (true) {
                val notice = synchronized(lock) {
                    if (closed) {
                        queue.clear()
                        drainScheduled = false
                        return
                    }
                    queue.pollFirst() ?: run {
                        drainScheduled = false
                        return
                    }
                }
                try {
                    listener(notice)
                } catch (_: RuntimeException) {
                    // One consumer callback cannot kill its serialized delivery lane.
                }
            }
        }

        override fun close() = onClose(this)

        fun deactivate() = synchronized(lock) {
            closed = true
            queue.clear()
        }

        private companion object {
            const val EVENT_MAILBOX_CAPACITY = 16
        }
    }
}
