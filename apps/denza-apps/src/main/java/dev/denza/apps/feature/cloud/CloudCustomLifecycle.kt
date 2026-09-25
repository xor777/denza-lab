package dev.denza.apps.feature.cloud

/**
 * Serialized custom-owner lifecycle. Ports implement the documented supervisor contract;
 * this class does not infer native absence from connectivity, IPC closure or a missing object.
 */
internal class CloudCustomLifecycle(
    private val store: Store,
    private val openBackend: () -> Backend,
    private val clock: () -> Long,
    private val serviceInstance: () -> String? = { null },
) {
    interface Store {
        fun ownerNonce(): String?
        /** Return only after durable commit; failure must throw before START is attempted. */
        fun claim(nonce: String)
        /** Atomically reconcile a pending START or old bridge journal with the actual owner. */
        fun adopt(expected: String?, owner: String)
        /** Compare and clear exactly this owner, durably. */
        fun clear(nonce: String)
        /** Terminal START refusal for the current configuration generation, persisted across app death. */
        fun terminalCode(): String?
        fun saveTerminal(code: String)
        fun clearTerminal()
    }

    interface Backend : AutoCloseable {
        val nonce: String
        fun isRunning(): Boolean
        /** Snapshot only. The guardian must arbitrate START atomically. */
        fun probe(): CloudCustomStatus
        /** Guardian authenticates installation and configuration, independently of bridge nonce. */
        fun attach(ownerId: String): CloudCustomStatus
        fun start(identity: CloudIdentity): CloudCustomStatus
        fun renew(ownerId: String, renewSeq: Long): CloudCustomStatus
        fun status(): CloudCustomStatus
        fun stop(ownerId: String): CloudCustomStatus
        override fun close()
    }

    enum class Operation {
        OWNER, OPEN, RUNNING, PROBE, ATTACH, CLAIM, ADOPT, START, STATUS, STOP, CLEAR, CLOSE,
        WAIT_RETRY, WAIT_NETWORK, WAIT_SERVICE, IDENTITY, READ_TERMINAL, SAVE_TERMINAL, CLEAR_TERMINAL,
    }

    enum class Reason { IO, OWNER_PRESENT, OWNER_CHANGED, STOP_UNCONFIRMED, IDENTITY_REQUIRED, FUTURE_STATUS, REJECTED }
    data class Event(val operation: Operation, val processNonce: String? = null)
    data class Failure(val operation: Operation, val reason: Reason, val errorType: String?,
                       val code: String? = null, val retryable: Boolean = true)
    data class Result(
        val completed: Boolean,
        val stopConfirmed: Boolean,
        val status: CloudCustomStatus?,
        val processNonce: String?,
        val retryAtMs: Long,
        val events: List<Event>,
        val failure: Failure?,
    )

    private var backend: Backend? = null
    private var retryAtMs = 0L
    private var permanentFailure: Failure? = null

    /** Pending teardown always runs first, including a rapid off/on and network loss. */
    fun reconcile(
        enabled: Boolean,
        pendingDisable: Boolean,
        networkUsable: Boolean,
        identity: CloudIdentity?,
        force: Boolean = false,
    ): Result = runCycle { cycle ->
        if (pendingDisable || !enabled) {
            if (!resolve(cycle, force)) return@runCycle false
            cycle.stopConfirmed = true
            // The caller must durably clear pendingDisable before a separate cycle may START.
            // Otherwise process death can make the old OFF obligation target the new session.
            return@runCycle true
        }
        if (serviceInstance() == null) {
            cycle.events += Event(Operation.WAIT_SERVICE)
            return@runCycle false
        }
        if (permanentFailure == null) cycle.call(Operation.READ_TERMINAL, action = store::terminalCode)?.let {
            permanentFailure = Failure(Operation.START, Reason.REJECTED, null, it, false)
        }
        permanentFailure?.let { throw OperationFailure(it.operation, CloudCustomRejected(it.code ?: "operation_rejected", false)) }
        if (!force && clock() < retryAtMs) {
            cycle.events += Event(Operation.WAIT_RETRY)
            return@runCycle false
        }
        try {
            discardExited(cycle)
            val current = backend
            if (current == null) {
                connect(cycle, identity, networkUsable)
            } else {
                val status = cycle.call(Operation.STATUS, current.nonce, current::status)
                if (owner(cycle) != status.ownerId || status.ownerId.isEmpty()) reject(Operation.OWNER, Reason.OWNER_CHANGED)
                cycle.observe(current, Operation.STATUS, status)
            }
            retryAtMs = 0L
            true
        } catch (error: OperationFailure) {
            // START/STATUS may have taken effect. Closing IPC cannot discharge the journal.
            closeCurrentQuietly(cycle)
            throw error
        }
    }

    /**
     * Every OFF and factory activation probes even after app data was erased or the APK downgraded.
     * Completion proves runtime absence only, never restoration of BYD server registration.
     */
    fun resolveOwnership(force: Boolean = false): Result =
        runCycle { cycle ->
            resolve(cycle, force).also { cycle.stopConfirmed = it }
        }

    /** Service destruction detaches only. A later instance ATTACHes without restarting the session. */
    fun serviceClosed(): Result = runCycle { cycle ->
        closeCurrent(cycle)
        true
    }

    private fun connect(cycle: Cycle, identity: CloudIdentity?, networkUsable: Boolean) {
        val fresh = cycle.call(Operation.OPEN, action = openBackend)
        try {
            val probe = probe(cycle, fresh)
            if (probe == null) {
                // Kernel owners are gone but a durable cleanup debt remains. Complete only
                // local cleanup now; a later cycle must prove absence before any new START.
                val stopped = cycle.call(Operation.STOP, fresh.nonce) { fresh.stop("") }
                cycle.observe(fresh, Operation.STOP, stopped)
                if (!CloudCustomOwnershipPolicy.stopped(stopped)) reject(Operation.STOP, Reason.STOP_UNCONFIRMED)
                owner(cycle)?.let { cycle.call(Operation.CLEAR, it) { store.clear(it) } }
                cycle.call(Operation.CLOSE, fresh.nonce, fresh::close)
                return
            }
            cycle.observe(fresh, Operation.PROBE, probe)
            if (probe.protocol == 2) {
                val stopped = cycle.call(Operation.STOP, fresh.nonce) { fresh.stop(probe.ownerId) }
                cycle.observe(fresh, Operation.STOP, stopped)
                if (!CloudCustomOwnershipPolicy.stopped(stopped)) reject(Operation.STOP, Reason.STOP_UNCONFIRMED)
                if (stopped.ownerId.isNotEmpty() && stopped.ownerId != probe.ownerId)
                    reject(Operation.STOP, Reason.OWNER_CHANGED)
                owner(cycle)?.let { saved -> cycle.call(Operation.CLEAR, saved) { store.clear(saved) } }
                cycle.call(Operation.CLOSE, fresh.nonce, fresh::close)
                return
            }
            val saved = owner(cycle)
            val response: CloudCustomStatus
            val operation: Operation
            if (probe.code == "owner_present" && probe.ownerId.isNotEmpty()) {
                // An ambiguous START, bridge death or app restart must never re-register.
                operation = Operation.ATTACH
                response = cycle.call(operation, fresh.nonce) { fresh.attach(probe.ownerId) }
            } else {
                if (!CloudCustomOwnershipPolicy.absent(probe)) reject(Operation.PROBE, Reason.OWNER_PRESENT)
                saved?.let { previous -> cycle.call(Operation.CLEAR, previous) { store.clear(previous) } }
                if (!networkUsable) {
                    cycle.events += Event(Operation.WAIT_NETWORK)
                    cycle.call(Operation.CLOSE, fresh.nonce, fresh::close)
                    return
                }
                if (identity?.valid() != true) reject(Operation.IDENTITY, Reason.IDENTITY_REQUIRED)
                cycle.call(Operation.CLAIM, fresh.nonce) { store.claim(fresh.nonce) }
                operation = Operation.START
                response = cycle.call(operation, fresh.nonce) { fresh.start(checkNotNull(identity)) }
            }
            if (response.ownerId.isEmpty()) reject(operation, Reason.OWNER_CHANGED)
            cycle.observe(fresh, operation, response)
            val previous = owner(cycle)
            if (previous != response.ownerId) cycle.call(Operation.ADOPT, response.ownerId) {
                store.adopt(previous, response.ownerId)
            }
            backend = fresh
        } catch (error: OperationFailure) {
            if (backend === fresh) backend = null
            cycle.closeQuietly(fresh)
            throw error
        }
    }

    private fun resolve(cycle: Cycle, force: Boolean): Boolean {
        val savedOwner = owner(cycle)
        // Permanent START refusal must never prevent local OFF or owner cleanup.
        if (permanentFailure != null) retryAtMs = 0L
        if (!force && clock() < retryAtMs) {
            cycle.events += Event(Operation.WAIT_RETRY)
            return false
        }
        discardExited(cycle)
        val current = backend
        if (current != null) {
            val probe = probe(cycle, current)
            if (probe != null) cycle.observe(current, Operation.PROBE, probe)
            val stopped = cycle.call(Operation.STOP, current.nonce) { current.stop(probe?.ownerId.orEmpty()) }
            cycle.observe(current, Operation.STOP, stopped)
            if (!CloudCustomOwnershipPolicy.stopped(stopped)) reject(Operation.STOP, Reason.STOP_UNCONFIRMED)
            if (stopped.ownerId.isNotEmpty() && stopped.ownerId != savedOwner) reject(Operation.STOP, Reason.OWNER_CHANGED)
            savedOwner?.let { cycle.call(Operation.CLEAR, it) { store.clear(it) } }
            closeCurrent(cycle)
        } else {
            val probeBackend = cycle.call(Operation.OPEN, action = openBackend)
            try {
                val probe = probe(cycle, probeBackend)
                if (probe != null) cycle.observe(probeBackend, Operation.PROBE, probe)
                if (!CloudCustomOwnershipPolicy.absent(probe)) {
                    if (probe != null && (probe.code != "owner_present" || probe.ownerId.isEmpty())) reject(Operation.PROBE, Reason.OWNER_PRESENT)
                    // STOP validates the installation but permits an OFF generation advance.
                    // ATTACH requires the active generation and therefore cannot precede OFF.
                    val stopped = cycle.call(Operation.STOP, probeBackend.nonce) { probeBackend.stop(probe?.ownerId.orEmpty()) }
                    cycle.observe(probeBackend, Operation.STOP, stopped)
                    if (!CloudCustomOwnershipPolicy.stopped(stopped)) reject(Operation.STOP, Reason.STOP_UNCONFIRMED)
                    if (probe != null && stopped.ownerId.isNotEmpty() && stopped.ownerId != probe.ownerId) reject(Operation.STOP, Reason.OWNER_CHANGED)
                }
                savedOwner?.let { cycle.call(Operation.CLEAR, it) { store.clear(it) } }
            } catch (error: OperationFailure) {
                cycle.closeQuietly(probeBackend)
                throw error
            }
            cycle.call(Operation.CLOSE, probeBackend.nonce, probeBackend::close)
        }
        retryAtMs = 0L
        cycle.call(Operation.CLEAR_TERMINAL, action = store::clearTerminal)
        permanentFailure = null
        return true
    }

    private fun owner(cycle: Cycle): String? = cycle.call(Operation.OWNER, action = store::ownerNonce)

    /** A typed debt-only refusal is not owner absence and can authorize only cleanup, not START. */
    private fun probe(cycle: Cycle, source: Backend): CloudCustomStatus? = try {
        cycle.call(Operation.PROBE, source.nonce, source::probe)
    } catch (error: OperationFailure) {
        if ((error.original as? CloudCustomRejected)?.code == "cleanup_uncertain") null else throw error
    }

    private fun discardExited(cycle: Cycle) {
        val current = backend ?: return
        if (!cycle.call(Operation.RUNNING, current.nonce, current::isRunning)) closeCurrent(cycle)
    }

    private fun closeCurrent(cycle: Cycle) {
        val current = backend ?: return
        backend = null
        cycle.call(Operation.CLOSE, current.nonce, current::close)
    }

    private fun closeCurrentQuietly(cycle: Cycle) {
        val current = backend ?: return
        backend = null
        cycle.closeQuietly(current)
    }

    private fun runCycle(action: (Cycle) -> Boolean): Result {
        val cycle = Cycle()
        return try {
            cycle.result(action(cycle), null)
        } catch (error: OperationFailure) {
            // A pre-START PROBE (or the previous owner's STOP) cannot describe a
            // new session after an ambiguous operation has potentially taken effect.
            cycle.discardObservation()
            val rejected = error.original as? CloudCustomRejected
            val reason = (error.original as? Rejected)?.reason ?: if (rejected != null) Reason.REJECTED else Reason.IO
            val type = if (reason == Reason.IO) error.original.javaClass.simpleName
                .takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,79}")) } ?: "Exception" else null
            val failure = Failure(error.operation, reason, type, rejected?.code, rejected?.retryable ?: true)
            if (!failure.retryable && error.operation !in setOf(Operation.STOP, Operation.CLEAR, Operation.CLOSE)) {
                permanentFailure = failure
                try {
                    cycle.call(Operation.SAVE_TERMINAL) { store.saveTerminal(checkNotNull(failure.code)) }
                } catch (persistence: OperationFailure) {
                    // Stay latched in memory and retain the owner journal if storage failed.
                    return cycle.result(false, Failure(persistence.operation, Reason.IO,
                        persistence.original.javaClass.simpleName.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,79}")) } ?: "Exception"))
                }
            }
            retryAtMs = if (failure.retryable) clock() + RETRY_MS else 0L
            cycle.result(false, failure)
        }
    }

    private inner class Cycle {
        val events = mutableListOf<Event>()
        var stopConfirmed = false
        private var status: CloudCustomStatus? = null
        private var processNonce: String? = null

        fun <T> call(operation: Operation, nonce: String? = null, action: () -> T): T {
            events += Event(operation, nonce)
            return try { action() } catch (error: Exception) { throw OperationFailure(operation, error) }
        }

        fun observe(source: Backend, operation: Operation, value: CloudCustomStatus) {
            if (value.updatedElapsedMs > clock() + 5_000L) reject(operation, Reason.FUTURE_STATUS)
            status = value
            processNonce = value.ownerId.ifEmpty { source.nonce }
            // The guardian owns retryability. An unfamiliar, validated failure code
            // must not cause a new START after the guardian has already stopped.
            if (operation != Operation.PROBE && value.stage == "failed" && !value.retryable) {
                permanentFailure = Failure(operation, Reason.REJECTED, null, value.code, false)
                call(Operation.SAVE_TERMINAL) { store.saveTerminal(value.code) }
            }
        }

        fun closeQuietly(source: Backend) {
            try { call(Operation.CLOSE, source.nonce, source::close) } catch (_: OperationFailure) { }
        }

        fun discardObservation() {
            status = null
            processNonce = null
        }

        fun result(completed: Boolean, failure: Failure?) = Result(
            completed, stopConfirmed, status, processNonce, retryAtMs, events.toList(), failure,
        )
    }

    private class Rejected(val reason: Reason) : Exception()
    private class OperationFailure(val operation: Operation, val original: Exception) : Exception()
    private fun reject(operation: Operation, reason: Reason): Nothing = throw OperationFailure(operation, Rejected(reason))

    companion object {
        const val RETRY_MS = 30_000L
    }
}
