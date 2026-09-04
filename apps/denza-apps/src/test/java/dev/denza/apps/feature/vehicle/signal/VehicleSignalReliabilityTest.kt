package dev.denza.apps.feature.vehicle.signal

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/** Host-only failure schedules: no assumed firmware gestures and no sleeps to order threads. */
class VehicleSignalReliabilityTest {
    @Test
    fun delayedSnapshotDoesNotAcquireFreshnessInTransport() {
        Fixture(now = 10_000L).use { f ->
            val batch = TurnSignalBatchDecoder().decode(
                "S 1 1000000000 ${TurnSignalDecoder.TURN_MODE_FID} 2", 1L, f.now,
            ) as TurnSignalBatchResult.Updates
            batch.values.forEach(f.source.publish)
            assertEquals(VehicleSignalMissingReason.STALE, f.stateReason())
        }
    }

    @Test
    fun delayedEventDoesNotAcquireFreshnessInTransport() {
        Fixture(now = 10_000L).use { f ->
            val batch = TurnSignalBatchDecoder().decode(
                "E 1 1000000000 ${TurnSignalDecoder.TURN_MODE_FID} 2", 1L, f.now,
            ) as TurnSignalBatchResult.Updates
            batch.values.forEach(f.source.publish)
            assertEquals(VehicleSignalMissingReason.STALE, f.stateReason())
        }
    }

    @Test
    fun invalidClockValuesCannotBeFresh() {
        Fixture().use { f ->
            f.source.publish(VehicleSignalSourceUpdate.Sample(
                VehicleSignalKeys.TurnIndicatorMode, TurnIndicatorMode.LEFT,
                101L, 101L, 1L, 1L,
            ))
            assertEquals(VehicleSignalMissingReason.INVALID, f.stateReason())
        }
    }

    @Test
    fun protocolRejectsNegativeAndFutureObservationTimes() {
        for (nanos in listOf(-1L, 101_000_000L)) {
            assertTrue("timestamp $nanos", TurnSignalBatchDecoder().decode(
                "E 1 $nanos ${TurnSignalDecoder.TURN_MODE_FID} 2", 1L, 100L,
            ) is TurnSignalBatchResult.Reconnect)
        }
    }

    @Test
    fun inlineExecutorCannotHoldTheSourceOrAnotherSubscriber() {
        Fixture().use { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val published = CountDownLatch(1)
            val healthy = CountDownLatch(1)
            f.lease.subscribeEvents(VehicleSignalKeys.TurnIndicatorMode, Executor(Runnable::run)) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            f.lease.subscribeEvents(VehicleSignalKeys.TurnIndicatorMode, Executor(Runnable::run)) {
                healthy.countDown()
            }
            val publisher = thread(isDaemon = true) { f.source.event(1L); published.countDown() }
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                assertTrue("consumer held the source", published.await(1, TimeUnit.SECONDS))
                assertTrue("consumer held a peer", healthy.await(1, TimeUnit.SECONDS))
            } finally {
                release.countDown()
                publisher.join(1_000L)
            }
        }
    }

    @Test
    fun executorRejectionMustBeReportedBeforeTheNextLiveEvent() {
        Fixture().use { f ->
            val rejecting = CountDownLatch(1)
            val allowRejection = CountDownLatch(1)
            val notices = LinkedBlockingQueue<VehicleSignalEventNotice<TurnIndicatorMode>>()
            var reject = true
            f.lease.subscribeEvents(VehicleSignalKeys.TurnIndicatorMode, Executor {
                if (reject) {
                    reject = false
                    rejecting.countDown()
                    allowRejection.await(1, TimeUnit.SECONDS)
                    throw RejectedExecutionException("test refusal")
                }
                it.run()
            }, notices::add)
            f.source.event(1L)
            try {
                assertTrue(rejecting.await(1, TimeUnit.SECONDS))
                // Traffic arriving during rejection must not strand the gap. Just two events:
                // an unrelated mailbox overflow cannot accidentally satisfy this assertion.
                f.source.event(2L)
            } finally {
                allowRejection.countDown()
            }
            val first = notices.poll(1, TimeUnit.SECONDS)
            assertTrue("lost event was not reported: $first", first is VehicleSignalEventNotice.Unavailable)
            assertEquals(VehicleSignalMissingReason.AMBIGUOUS,
                (first as VehicleSignalEventNotice.Unavailable).reason)
            f.source.event(3L)
            val next = notices.poll(1, TimeUnit.SECONDS) as VehicleSignalEventNotice.Event
            assertEquals(3L, next.event.sequence)
        }
    }

    @Test
    fun deliveryAdmissionIsBoundedAndUnusedSlotsAreReleasedOnClose() {
        Fixture().use { f ->
            val subscriptions = MutableList(16) {
                f.lease.subscribeEvents(VehicleSignalKeys.TurnIndicatorMode, Executor(Runnable::run)) {}
            }
            try {
                f.lease.subscribeEvents(VehicleSignalKeys.TurnIndicatorMode, Executor(Runnable::run)) {}
                fail("unbounded delivery lanes")
            } catch (_: IllegalStateException) {
                // Explicit admission failure, not silently dropped subscriptions.
            }
            subscriptions.removeAt(0).close()
            subscriptions += f.lease.subscribeEvents(
                VehicleSignalKeys.TurnIndicatorMode, Executor(Runnable::run),
            ) {}
            subscriptions.forEach { it.close() }
        }
    }

    @Test
    fun checkedListenerExceptionDoesNotStrandLaterEvents() {
        Fixture().use { f ->
            val first = CountDownLatch(1)
            val second = CountDownLatch(1)
            f.lease.subscribeEvents(VehicleSignalKeys.TurnIndicatorMode, Executor(Runnable::run)) {
                if (it is VehicleSignalEventNotice.Event && it.event.sequence == 1L) {
                    first.countDown()
                    throw IOException("consumer failure")
                }
                second.countDown()
            }
            f.source.event(1L)
            assertTrue(first.await(1, TimeUnit.SECONDS))
            f.source.event(2L)
            assertTrue("listener exception stranded the mailbox", second.await(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun rejectedExecutorDoesNotCreateAnIdleRetryLoop() {
        Fixture().use { f ->
            val attempts = LinkedBlockingQueue<Boolean>()
            f.lease.subscribeEvents(VehicleSignalKeys.TurnIndicatorMode, Executor {
                attempts.add(true)
                throw RejectedExecutionException("permanently unavailable")
            }) {}
            f.source.event(1L)
            assertNotNull(attempts.poll(1, TimeUnit.SECONDS))
            assertNull("retry without source traffic", attempts.poll(150, TimeUnit.MILLISECONDS))
            f.source.event(2L)
            assertNotNull("new traffic did not retry the pending gap", attempts.poll(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun demandRestartIsAnnouncedEvenIfTheNewSourceHasNotConnected() {
        Fixture().use { f ->
            val notices = LinkedBlockingQueue<VehicleSignalEventNotice<TurnIndicatorMode>>()
            f.lease.subscribeEvents(VehicleSignalKeys.TurnIndicatorMode, Executor(Runnable::run), notices::add)
            val previousPublisher = f.source.publish
            f.hub.acquire(VehicleSignalConsumerId("new-key"), setOf(
                VehicleSignalDemand(VehicleSignalKeys.TurnSwitchPhase, 100L),
            )).use {
                assertEquals(2, f.source.starts)
                assertEquals(1, f.source.stops)
                val gap = notices.poll(1, TimeUnit.SECONDS)
                assertTrue("reconfiguration was silent", gap is VehicleSignalEventNotice.Unavailable)
                assertEquals(VehicleSignalMissingReason.STARTING,
                    (gap as VehicleSignalEventNotice.Unavailable).reason)
                previousPublisher(VehicleSignalSourceUpdate.Event(
                    VehicleSignalKeys.TurnIndicatorMode, TurnIndicatorMode.RIGHT,
                    100L, 100L, 1L, 2L,
                ))
                assertEquals(VehicleSignalMissingReason.STARTING, f.stateReason())
            }
        }
    }

    @Test
    fun sharingTheSameKeyDoesNotRestartTheSource() {
        Fixture().use { f ->
            f.source.event(1L)
            f.hub.acquire(VehicleSignalConsumerId("peer"), setOf(
                VehicleSignalDemand(VehicleSignalKeys.TurnIndicatorMode, 50L),
            )).use { peer ->
                assertTrue(peer.read(VehicleSignalKeys.TurnIndicatorMode, f.now) is VehicleSignalState.Fresh)
            }
            assertEquals(1, f.source.starts)
            assertEquals(0, f.source.stops)
            assertTrue(f.lease.read(VehicleSignalKeys.TurnIndicatorMode, f.now) is VehicleSignalState.Fresh)
        }
    }

    @Test
    fun activationFenceSurvivesAnEpochNumberReusedByAnotherActivation() {
        Fixture().use { f ->
            val oldPublisher = f.source.publish
            f.lease.close()
            f.hub.acquire(VehicleSignalConsumerId("replacement"), setOf(
                VehicleSignalDemand(VehicleSignalKeys.TurnIndicatorMode, 100L),
            )).use { replacement ->
                f.source.publish(VehicleSignalSourceUpdate.ConnectionStarting(1L))
                oldPublisher(VehicleSignalSourceUpdate.Sample(
                    VehicleSignalKeys.TurnIndicatorMode, TurnIndicatorMode.RIGHT,
                    100L, 100L, 1L, 1L,
                ))
                assertEquals(VehicleSignalMissingReason.STARTING,
                    (replacement.read(VehicleSignalKeys.TurnIndicatorMode, f.now) as
                        VehicleSignalState.Missing).reason)
                f.source.event(2L)
                assertEquals(TurnIndicatorMode.LEFT,
                    (replacement.read(VehicleSignalKeys.TurnIndicatorMode, f.now) as
                        VehicleSignalState.Fresh).value)
            }
        }
    }

    @Test
    fun oneSourceCannotReportAnotherSourcesKeyAsMissing() {
        val mode = Source(setOf(VehicleSignalKeys.TurnIndicatorMode), "mode")
        val phase = Source(setOf(VehicleSignalKeys.TurnSwitchPhase), "phase")
        val hub = VehicleSignalHub(listOf(mode, phase), clock = { 100L })
        hub.acquire(VehicleSignalConsumerId("both"), setOf(
            VehicleSignalDemand(VehicleSignalKeys.TurnIndicatorMode, 100L),
            VehicleSignalDemand(VehicleSignalKeys.TurnSwitchPhase, 100L),
        )).use { lease ->
            mode.publish(VehicleSignalSourceUpdate.ConnectionStarting(1L))
            phase.publish(VehicleSignalSourceUpdate.ConnectionStarting(1L))
            val notices = LinkedBlockingQueue<VehicleSignalEventNotice<TurnSwitchPhase>>()
            lease.subscribeEvents(VehicleSignalKeys.TurnSwitchPhase, Executor(Runnable::run), notices::add)
            mode.publish(VehicleSignalSourceUpdate.Missing(
                1L, VehicleSignalMissingReason.SOURCE_DOWN, key = VehicleSignalKeys.TurnSwitchPhase,
            ))
            // A real event behind the foreign notice makes this a positive delivery barrier.
            phase.publish(VehicleSignalSourceUpdate.Event(
                VehicleSignalKeys.TurnSwitchPhase, TurnSwitchPhase(2), 100L, 100L, 1L, 1L,
            ))
            val first = notices.poll(1, TimeUnit.SECONDS)
            assertTrue("foreign source invalidated this subscription: $first",
                first is VehicleSignalEventNotice.Event)
        }
    }

    private class Fixture(var now: Long = 100L) : AutoCloseable {
        val source = Source()
        val hub = VehicleSignalHub(listOf(source), clock = { now })
        val lease = hub.acquire(VehicleSignalConsumerId("reliability"), setOf(
            VehicleSignalDemand(VehicleSignalKeys.TurnIndicatorMode, 100L),
        ))
        init { source.publish(VehicleSignalSourceUpdate.ConnectionStarting(1L)) }
        fun stateReason(): VehicleSignalMissingReason? =
            (lease.read(VehicleSignalKeys.TurnIndicatorMode, now) as? VehicleSignalState.Missing)?.reason
        override fun close() = lease.close()
    }

    private class Source(
        override val keys: Set<VehicleSignalKey<*>> =
            setOf(VehicleSignalKeys.TurnIndicatorMode, VehicleSignalKeys.TurnSwitchPhase),
        name: String = "reliability-source",
    ) : VehicleSignalSource {
        override val id = VehicleSignalSourceId(name)
        lateinit var publish: (VehicleSignalSourceUpdate) -> Unit
        var starts = 0
        var stops = 0
        override fun start(requestedKeys: Set<VehicleSignalKey<*>>, publish: (VehicleSignalSourceUpdate) -> Unit) {
            starts++
            this.publish = publish
        }
        override fun stop() { stops++ }
        fun event(sequence: Long) = publish(VehicleSignalSourceUpdate.Event(
            VehicleSignalKeys.TurnIndicatorMode, TurnIndicatorMode.LEFT, 100L, 100L, 1L, sequence,
        ))
    }
}
