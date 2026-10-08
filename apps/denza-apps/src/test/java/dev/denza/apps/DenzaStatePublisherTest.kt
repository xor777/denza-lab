package dev.denza.apps

import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DenzaStatePublisherTest {

    /** Runs nothing until the test says so, so every interleaving is spelled out. */
    private class ManualExecutor : Executor {
        val queued = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            queued.addLast(command)
        }

        fun runAll() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    /** What the car says about the mirrors right now, and what each read saw. */
    private var mirrorsOnCar: FeatureStatus = FeatureStatus.OFF
    private val reads = mutableListOf<Set<StateSlice>>()

    /** Runs inside a read, after the car was looked at and before the result is committed. */
    private var duringRead: (() -> Unit)? = null

    private val executor = ManualExecutor()
    private val store = DenzaUiStateStore()
    private val history = mutableListOf<FeatureStatus>()
    private val log = RecomputeLog()
    private val publisher = DenzaStatePublisher(
        store = store,
        executor = executor,
        read = { slices ->
            reads += slices.toSet()
            val seen = mirrors(mirrorsOnCar)
            duringRead?.let { it() }
            duringRead = null
            if (StateSlice.MIRRORS in slices) {
                { state -> state.copy(mirrors = seen).also { history += seen.status } }
            } else {
                { state -> state }
            }
        },
        log = log,
    )

    @Test
    fun `nothing is read on the caller's thread`() {
        publisher.invalidate(StateSlice.MIRRORS, "mirrors")

        assertTrue(reads.isEmpty())
        assertEquals(1, executor.queued.size)
    }

    @Test
    fun `marks that arrive before the read are folded into one read`() {
        publisher.invalidate(StateSlice.MIRRORS, "turn signal")
        publisher.invalidate(StateSlice.CLOUD_LINK, "cloud tick")
        publisher.invalidate(StateSlice.MIRRORS, "turn signal")
        assertEquals(1, executor.queued.size)

        executor.runAll()

        assertEquals(listOf(setOf(StateSlice.MIRRORS, StateSlice.CLOUD_LINK)), reads)
        assertEquals("turn signal, cloud tick", log.rows(0L)[2].key)
    }

    @Test
    fun `invalidating everything reads every slice once`() {
        publisher.invalidateAll("resume")
        publisher.invalidate(StateSlice.WEATHER, "weather")

        executor.runAll()

        assertEquals(listOf(StateSlice.entries.toSet()), reads)
    }

    @Test
    fun `a mark made during a read is read again after it, with what the car says then`() {
        mirrorsOnCar = FeatureStatus.READY
        publisher.invalidate(StateSlice.MIRRORS, "mirrors")
        // The slow read has looked at the car; the car moves on and says so before it commits.
        duringRead = {
            mirrorsOnCar = FeatureStatus.ACTIVE
            publisher.invalidate(StateSlice.MIRRORS, "mirrors")
        }

        executor.runAll()

        assertEquals(2, reads.size)
        assertEquals(listOf(FeatureStatus.READY, FeatureStatus.ACTIVE), history)
        assertEquals(FeatureStatus.ACTIVE, store.state.value.mirrors.status)
    }

    /**
     * The defect: two refreshes on two threads could finish in the wrong order, and the slower one
     * put an older reading over a newer one. Reads here never overlap, so a slow read always lands
     * before the fast one asked for after it.
     */
    @Test
    fun `a slow read never lands after a faster one asked for later`() {
        var inRead = 0
        var overlapped = false
        val publisher = DenzaStatePublisher(
            store = store,
            executor = executor,
            read = { _ ->
                inRead += 1
                if (inRead > 1) overlapped = true
                val seen = mirrors(mirrorsOnCar)
                duringRead?.let { it() }
                duringRead = null
                inRead -= 1
                val apply: (DenzaUiState) -> DenzaUiState = { state ->
                    state.copy(mirrors = seen).also { history += seen.status }
                }
                apply
            },
        )
        mirrorsOnCar = FeatureStatus.READY
        publisher.invalidate(StateSlice.MIRRORS, "slow")
        duringRead = {
            mirrorsOnCar = FeatureStatus.ACTIVE
            publisher.invalidate(StateSlice.MIRRORS, "fast")
        }

        executor.runAll()

        assertEquals(false, overlapped)
        assertEquals(listOf(FeatureStatus.READY, FeatureStatus.ACTIVE), history)
        assertEquals(FeatureStatus.ACTIVE, store.state.value.mirrors.status)
    }

    /**
     * The defect: a switch showed «starting», and a refresh that had read the car before the switch
     * was pressed committed after it and put «off» back. Here the older read lands first, the
     * switch's state after it, and the read the switch asked for last.
     */
    @Test
    fun `a transient state is not overwritten by an older read`() {
        mirrorsOnCar = FeatureStatus.OFF
        publisher.invalidate(StateSlice.MIRRORS, "cloud tick")
        // The switch is pressed while that read is looking at the car.
        duringRead = {
            mirrorsOnCar = FeatureStatus.READY
            publisher.publish("mirrors switch") { state ->
                state.copy(mirrors = FeatureReducer.starting(FeatureId.MIRRORS)).also {
                    history += FeatureStatus.STARTING
                }
            }
            publisher.invalidate(StateSlice.MIRRORS, "mirrors")
        }

        executor.runAll()

        assertEquals(listOf(FeatureStatus.OFF, FeatureStatus.STARTING, FeatureStatus.READY), history)
        assertEquals(FeatureStatus.READY, store.state.value.mirrors.status)
    }

    @Test
    fun `a mark made after a published state is not folded into a read before it`() {
        publisher.invalidate(StateSlice.MIRRORS, "before")
        publisher.publish("switch") { state ->
            state.copy(mirrors = FeatureReducer.starting(FeatureId.MIRRORS)).also {
                history += FeatureStatus.STARTING
            }
        }
        mirrorsOnCar = FeatureStatus.READY
        publisher.invalidate(StateSlice.MIRRORS, "after")

        executor.runAll()

        assertEquals(2, reads.size)
        assertEquals(listOf(FeatureStatus.READY, FeatureStatus.STARTING, FeatureStatus.READY), history)
        assertEquals(FeatureStatus.READY, store.state.value.mirrors.status)
    }

    @Test
    fun `a read that fails is reported and the queue goes on`() {
        val failures = mutableListOf<String>()
        var first = true
        val publisher = DenzaStatePublisher(
            store = store,
            executor = executor,
            read = { _ ->
                if (first) {
                    first = false
                    throw IllegalStateException("binder died")
                }
                val apply: (DenzaUiState) -> DenzaUiState = { state ->
                    state.copy(mirrors = mirrors(FeatureStatus.READY))
                }
                apply
            },
            onError = { cause, _ -> failures += cause },
        )
        publisher.invalidate(StateSlice.MIRRORS, "first")
        executor.runAll()
        publisher.invalidate(StateSlice.MIRRORS, "second")
        executor.runAll()

        assertEquals(listOf("first"), failures)
        assertEquals(FeatureStatus.READY, store.state.value.mirrors.status)
    }

    @Test
    fun `every read is recorded under what asked for it`() {
        publisher.invalidate(StateSlice.MIRRORS, "mirrors")
        executor.runAll()
        publisher.invalidateAll("resume")
        executor.runAll()

        val rows = log.rows(nowMs = 0L)
        assertEquals("2 · на главном потоке 0", rows[0].value)
        assertEquals(listOf("resume", "mirrors"), rows.drop(2).map(TechnicalRow::key))
    }

    private fun mirrors(status: FeatureStatus) = FeatureSnapshot(
        id = FeatureId.MIRRORS,
        desiredEnabled = status != FeatureStatus.OFF,
        status = status,
    )
}
