package dev.denza.apps.feature.vehicle.signal

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleSignalHubTest {
    private val source = FakeSource()
    private val hub = VehicleSignalHub(listOf(source))

    @Test
    fun oneSourceIsSharedUntilTheLastConsumerReleasesIt() {
        val first = hub.acquire(
            consumer = VehicleSignalConsumerId("mirrors"),
            demands = setOf(VehicleSignalDemand(VehicleSignalKeys.TurnIndicatorMode, 2_000L)),
        )
        val second = hub.acquire(
            consumer = VehicleSignalConsumerId("diagnostics"),
            demands = setOf(VehicleSignalDemand(VehicleSignalKeys.TurnIndicatorMode, 2_000L)),
        )

        assertEquals(1, source.starts)
        first.close()
        assertEquals(0, source.stops)
        second.close()
        assertEquals(1, source.stops)
    }

    @Test
    fun sourceReceivesOnlyTheCurrentUnionOfDemandedKeys() {
        val modeLease = hub.acquire(
            VehicleSignalConsumerId("mirrors"),
            setOf(VehicleSignalDemand(VehicleSignalKeys.TurnIndicatorMode, 2_000L)),
        )
        assertEquals(setOf(VehicleSignalKeys.TurnIndicatorMode), source.requestedKeys.last())

        val switchLease = hub.acquire(
            VehicleSignalConsumerId("diagnostics"),
            setOf(VehicleSignalDemand(VehicleSignalKeys.TurnSwitchPhase, 2_000L)),
        )
        assertEquals(
            setOf(VehicleSignalKeys.TurnIndicatorMode, VehicleSignalKeys.TurnSwitchPhase),
            source.requestedKeys.last(),
        )

        modeLease.close()
        assertEquals(setOf(VehicleSignalKeys.TurnSwitchPhase), source.requestedKeys.last())
        switchLease.close()
        assertEquals(3, source.starts)
        assertEquals(3, source.stops)
    }

    @Test
    fun staleReadFailsClosedForTheConsumersOwnFreshnessBudget() {
        val lease = hub.acquire(
            VehicleSignalConsumerId("mirrors"),
            setOf(VehicleSignalDemand(VehicleSignalKeys.TurnIndicatorMode, 100L)),
        )
        source.connection(7L)
        source.sample(
            key = VehicleSignalKeys.TurnIndicatorMode,
            value = TurnIndicatorMode.LEFT,
            observedAt = 10L,
            verifiedAt = 20L,
            sourceEpoch = 7L,
            sequence = 1L,
        )

        assertEquals(
            TurnIndicatorMode.LEFT,
            (lease.read(VehicleSignalKeys.TurnIndicatorMode, 120L) as
                VehicleSignalState.Fresh).value,
        )
        assertEquals(
            VehicleSignalMissingReason.STALE,
            (lease.read(VehicleSignalKeys.TurnIndicatorMode, 121L) as
                VehicleSignalState.Missing).reason,
        )
    }

    @Test
    fun channelHeartbeatDoesNotPretendToVerifyTheRetainedValue() {
        val lease = hub.acquire(
            VehicleSignalConsumerId("mirrors"),
            setOf(VehicleSignalDemand(VehicleSignalKeys.TurnIndicatorMode, 100L)),
        )
        source.connection(4L)
        source.sample(
            VehicleSignalKeys.TurnIndicatorMode,
            TurnIndicatorMode.RIGHT,
            observedAt = 10L,
            verifiedAt = 20L,
            sourceEpoch = 4L,
            sequence = 8L,
        )
        source.heartbeat(sourceEpoch = 4L, verifiedAt = 110L)

        val state = lease.read(VehicleSignalKeys.TurnIndicatorMode, 121L)
            as VehicleSignalState.Missing
        assertEquals(VehicleSignalMissingReason.STALE, state.reason)
    }

    @Test
    fun retainedSnapshotIsNotDeliveredAsATransientEvent() {
        val lease = hub.acquire(
            VehicleSignalConsumerId("mirrors"),
            setOf(VehicleSignalDemand(VehicleSignalKeys.TurnSwitchPhase, 1_000L)),
        )
        val notices = mutableListOf<VehicleSignalEventNotice<TurnSwitchPhase>>()
        val subscription = lease.subscribeEvents(
            VehicleSignalKeys.TurnSwitchPhase,
            Executor(Runnable::run),
            notices::add,
        )
        source.connection(3L)
        notices.clear()

        source.sample(
            VehicleSignalKeys.TurnSwitchPhase,
            TurnSwitchPhase(2),
            observedAt = 10L,
            verifiedAt = 11L,
            sourceEpoch = 3L,
            sequence = 1L,
        )

        assertTrue(notices.isEmpty())
        subscription.close()
        lease.close()
    }

    @Test
    fun liveEventIsDeliveredAndAlsoUpdatesRetainedState() {
        val lease = hub.acquire(
            VehicleSignalConsumerId("mirrors"),
            setOf(VehicleSignalDemand(VehicleSignalKeys.TurnSwitchPhase, 1_000L)),
        )
        val notices = mutableListOf<VehicleSignalEventNotice<TurnSwitchPhase>>()
        lease.subscribeEvents(
            VehicleSignalKeys.TurnSwitchPhase,
            Executor(Runnable::run),
            notices::add,
        )
        source.connection(8L)
        notices.clear()

        source.event(
            VehicleSignalKeys.TurnSwitchPhase,
            TurnSwitchPhase(4),
            observedAt = 20L,
            verifiedAt = 21L,
            sourceEpoch = 8L,
            sequence = 2L,
        )

        val delivered = (notices.single() as VehicleSignalEventNotice.Event).event
        assertEquals(TurnSwitchPhase(4), delivered.value)
        assertEquals(2L, delivered.sequence)
        assertEquals(
            TurnSwitchPhase(4),
            (lease.read(VehicleSignalKeys.TurnSwitchPhase, 21L) as
                VehicleSignalState.Fresh).value,
        )
        lease.close()
    }

    @Test
    fun sourceLossIsAnExplicitEventDiscontinuity() {
        val lease = hub.acquire(
            VehicleSignalConsumerId("mirrors"),
            setOf(VehicleSignalDemand(VehicleSignalKeys.TurnSwitchPhase, 1_000L)),
        )
        val notices = mutableListOf<VehicleSignalEventNotice<TurnSwitchPhase>>()
        lease.subscribeEvents(
            VehicleSignalKeys.TurnSwitchPhase,
            Executor(Runnable::run),
            notices::add,
        )
        source.connection(6L)
        notices.clear()

        source.missing(6L, VehicleSignalMissingReason.SOURCE_DOWN)

        val unavailable = notices.single() as VehicleSignalEventNotice.Unavailable
        assertEquals(VehicleSignalMissingReason.SOURCE_DOWN, unavailable.reason)
        lease.close()
    }

    @Test
    fun slowConsumerGetsAnExplicitOverflowInsteadOfAnUnboundedQueue() {
        val lease = hub.acquire(
            VehicleSignalConsumerId("slow"),
            setOf(VehicleSignalDemand(VehicleSignalKeys.TurnSwitchPhase, 1_000L)),
        )
        val executor = QueuedExecutor()
        val notices = mutableListOf<VehicleSignalEventNotice<TurnSwitchPhase>>()
        lease.subscribeEvents(VehicleSignalKeys.TurnSwitchPhase, executor, notices::add)
        source.connection(5L)
        executor.runAll()
        notices.clear()

        repeat(17) { index ->
            source.event(
                VehicleSignalKeys.TurnSwitchPhase,
                TurnSwitchPhase(2),
                observedAt = index.toLong(),
                verifiedAt = index.toLong(),
                sourceEpoch = 5L,
                sequence = index + 1L,
            )
        }
        executor.runAll()

        val unavailable = notices.single() as VehicleSignalEventNotice.Unavailable
        assertEquals(VehicleSignalMissingReason.AMBIGUOUS, unavailable.reason)
        lease.close()
    }

    @Test
    fun closeSuppressesAnAlreadyQueuedCallback() {
        val lease = hub.acquire(
            VehicleSignalConsumerId("closing"),
            setOf(VehicleSignalDemand(VehicleSignalKeys.TurnSwitchPhase, 1_000L)),
        )
        val executor = QueuedExecutor()
        val notices = mutableListOf<VehicleSignalEventNotice<TurnSwitchPhase>>()
        val subscription = lease.subscribeEvents(
            VehicleSignalKeys.TurnSwitchPhase,
            executor,
            notices::add,
        )
        source.connection(9L)
        executor.runAll()
        notices.clear()
        source.event(
            VehicleSignalKeys.TurnSwitchPhase,
            TurnSwitchPhase(4),
            observedAt = 1L,
            verifiedAt = 1L,
            sourceEpoch = 9L,
            sequence = 1L,
        )

        subscription.close()
        executor.runAll()

        assertTrue(notices.isEmpty())
        lease.close()
    }

    @Test
    fun latePublicationFromAReleasedActivationCannotEnterANewLease() {
        val oldLease = hub.acquire(
            VehicleSignalConsumerId("old"),
            setOf(VehicleSignalDemand(VehicleSignalKeys.TurnIndicatorMode, 1_000L)),
        )
        val oldPublisher = source.publishers.single()
        oldLease.close()

        val newLease = hub.acquire(
            VehicleSignalConsumerId("new"),
            setOf(VehicleSignalDemand(VehicleSignalKeys.TurnIndicatorMode, 1_000L)),
        )
        source.connection(2L)
        oldPublisher(
            VehicleSignalSourceUpdate.Sample(
                key = VehicleSignalKeys.TurnIndicatorMode,
                value = TurnIndicatorMode.LEFT,
                observedAtElapsedMs = 10L,
                verifiedAtElapsedMs = 10L,
                sourceEpoch = 1L,
                sequence = 1L,
            ),
        )

        assertTrue(
            newLease.read(VehicleSignalKeys.TurnIndicatorMode, 10L) is
                VehicleSignalState.Missing,
        )
    }

    @Test
    fun lateEventFromAReleasedActivationCannotReachANewSubscriber() {
        val oldLease = hub.acquire(
            VehicleSignalConsumerId("old"),
            setOf(VehicleSignalDemand(VehicleSignalKeys.TurnSwitchPhase, 1_000L)),
        )
        val oldPublisher = source.publishers.single()
        oldLease.close()

        val newLease = hub.acquire(
            VehicleSignalConsumerId("new"),
            setOf(VehicleSignalDemand(VehicleSignalKeys.TurnSwitchPhase, 1_000L)),
        )
        val notices = mutableListOf<VehicleSignalEventNotice<TurnSwitchPhase>>()
        newLease.subscribeEvents(
            VehicleSignalKeys.TurnSwitchPhase,
            Executor(Runnable::run),
            notices::add,
        )
        source.connection(2L)
        notices.clear()

        oldPublisher(
            VehicleSignalSourceUpdate.Event(
                VehicleSignalKeys.TurnSwitchPhase,
                TurnSwitchPhase(4),
                observedAtElapsedMs = 10L,
                verifiedAtElapsedMs = 10L,
                sourceEpoch = 1L,
                sequence = 2L,
            ),
        )

        assertTrue(notices.isEmpty())
        newLease.close()
    }

    @Test
    fun aNewActivationCannotStartUntilThePreviousStopFinishes() {
        val serialSource = BlockingStopSource()
        val serialHub = VehicleSignalHub(listOf(serialSource))
        val first = serialHub.acquire(
            VehicleSignalConsumerId("first"),
            setOf(VehicleSignalDemand(VehicleSignalKeys.TurnIndicatorMode, 1_000L)),
        )

        val releaseThread = thread(name = "release-old-signal-lease") { first.close() }
        assertTrue("old source did not enter stop", serialSource.stopEntered.await(1, TimeUnit.SECONDS))

        var second: VehicleSignalLease? = null
        val acquireThread = thread(name = "acquire-new-signal-lease") {
            second = serialHub.acquire(
                VehicleSignalConsumerId("second"),
                setOf(VehicleSignalDemand(VehicleSignalKeys.TurnIndicatorMode, 1_000L)),
            )
        }
        assertEquals(listOf("start-1", "stop-enter"), serialSource.events.toList())

        serialSource.allowStop.countDown()
        releaseThread.join(1_000L)
        acquireThread.join(1_000L)

        assertTrue(!releaseThread.isAlive)
        assertTrue(!acquireThread.isAlive)
        assertEquals(
            listOf("start-1", "stop-enter", "stop-exit", "start-2"),
            serialSource.events.toList(),
        )
        second?.close()
    }

    private class FakeSource : VehicleSignalSource {
        override val id = VehicleSignalSourceId("fake-turn")
        override val keys: Set<VehicleSignalKey<*>> = setOf(
            VehicleSignalKeys.TurnIndicatorMode,
            VehicleSignalKeys.TurnSwitchPhase,
        )
        val publishers = mutableListOf<(VehicleSignalSourceUpdate) -> Unit>()
        val requestedKeys = mutableListOf<Set<VehicleSignalKey<*>>>()
        var starts = 0
        var stops = 0

        override fun start(
            requestedKeys: Set<VehicleSignalKey<*>>,
            publish: (VehicleSignalSourceUpdate) -> Unit,
        ) {
            starts += 1
            this.requestedKeys += requestedKeys
            publishers += publish
        }

        override fun stop() {
            stops += 1
        }

        fun connection(sourceEpoch: Long) {
            publishers.last()(VehicleSignalSourceUpdate.ConnectionStarting(sourceEpoch))
        }

        fun <T : Any> sample(
            key: VehicleSignalKey<T>,
            value: T,
            observedAt: Long,
            verifiedAt: Long,
            sourceEpoch: Long,
            sequence: Long,
        ) {
            publishers.last()(
                VehicleSignalSourceUpdate.Sample(
                    key,
                    value,
                    observedAt,
                    verifiedAt,
                    sourceEpoch,
                    sequence,
                ),
            )
        }

        fun heartbeat(sourceEpoch: Long, verifiedAt: Long) {
            publishers.last()(
                VehicleSignalSourceUpdate.Heartbeat(sourceEpoch, verifiedAt),
            )
        }

        fun <T : Any> event(
            key: VehicleSignalKey<T>,
            value: T,
            observedAt: Long,
            verifiedAt: Long,
            sourceEpoch: Long,
            sequence: Long,
        ) {
            publishers.last()(
                VehicleSignalSourceUpdate.Event(
                    key,
                    value,
                    observedAt,
                    verifiedAt,
                    sourceEpoch,
                    sequence,
                ),
            )
        }

        fun missing(sourceEpoch: Long, reason: VehicleSignalMissingReason) {
            publishers.last()(VehicleSignalSourceUpdate.Missing(sourceEpoch, reason))
        }
    }

    private class BlockingStopSource : VehicleSignalSource {
        override val id = VehicleSignalSourceId("blocking-stop")
        override val keys: Set<VehicleSignalKey<*>> = setOf(VehicleSignalKeys.TurnIndicatorMode)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val stopEntered = CountDownLatch(1)
        val allowStop = CountDownLatch(1)
        private var starts = 0

        override fun start(
            requestedKeys: Set<VehicleSignalKey<*>>,
            publish: (VehicleSignalSourceUpdate) -> Unit,
        ) {
            starts += 1
            events += "start-$starts"
        }

        override fun stop() {
            events += "stop-enter"
            stopEntered.countDown()
            allowStop.await(1, TimeUnit.SECONDS)
            events += "stop-exit"
        }
    }

    private class QueuedExecutor : Executor {
        private val tasks = mutableListOf<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeAt(0).run()
        }
    }
}
