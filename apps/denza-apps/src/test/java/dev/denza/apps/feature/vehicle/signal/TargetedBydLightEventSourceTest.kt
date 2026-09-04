package dev.denza.apps.feature.vehicle.signal

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import dev.denza.disharebridge.LocalAdbClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetedBydLightEventSourceTest {
    @Test
    fun stopClosesAChannelWhoseLongPollIsBlocked() {
        val channel = BlockingChannel()
        val source = TargetedBydLightEventSource(
            channels = TurnSignalEventChannelFactory { _, _ -> channel },
            clock = { 1L },
            log = {},
        )

        source.start(setOf(VehicleSignalKeys.TurnIndicatorMode)) {}
        assertTrue("source did not enter long-poll", channel.inNext.await(1, TimeUnit.SECONDS))

        source.stop()

        assertTrue("source did not close its owned channel", channel.closed.await(1, TimeUnit.SECONDS))
        assertTrue(channel.closeCalls.get() >= 1)
    }

    @Test
    fun stopClosesAChannelWhoseStartupIsBlocked() {
        val channel = BlockingStartChannel()
        val source = TargetedBydLightEventSource(
            channels = TurnSignalEventChannelFactory { _, _ -> channel },
            clock = { 1L },
            log = {},
        )

        source.start(setOf(VehicleSignalKeys.TurnIndicatorMode)) {}
        assertTrue("source did not enter channel startup", channel.inStart.await(1, TimeUnit.SECONDS))

        source.stop()

        assertTrue("startup channel was not closed", channel.closed.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun passiveAuthorizationFailureIsTerminalForTheActivation() {
        val opens = AtomicInteger()
        val missing = CountDownLatch(1)
        val updates = Collections.synchronizedList(mutableListOf<VehicleSignalSourceUpdate>())
        val source = TargetedBydLightEventSource(
            channels = TurnSignalEventChannelFactory { _, _ ->
                opens.incrementAndGet()
                throw authorizationRequired()
            },
            clock = { 1L },
            log = {},
        )

        source.start(setOf(VehicleSignalKeys.TurnIndicatorMode)) { update ->
            updates += update
            if (
                update is VehicleSignalSourceUpdate.Missing &&
                update.reason == VehicleSignalMissingReason.AUTH_REQUIRED
            ) {
                missing.countDown()
            }
        }

        assertTrue("authorization failure was not published", missing.await(1, TimeUnit.SECONDS))
        Thread.sleep(650L)
        assertEquals(1, opens.get())
        assertTrue(
            updates.any {
                it is VehicleSignalSourceUpdate.Missing &&
                    it.reason == VehicleSignalMissingReason.AUTH_REQUIRED
            },
        )
        source.stop()
    }

    @Test
    fun overflowFailsClosedAndReconnectsWithANewEpoch() {
        val opens = AtomicInteger()
        val reconnected = CountDownLatch(1)
        val second = BlockingChannel()
        val source = TargetedBydLightEventSource(
            channels = TurnSignalEventChannelFactory { _, _ ->
                if (opens.incrementAndGet() == 1) OverflowChannel() else second
            },
            clock = { 1L },
            sleep = {},
            log = {},
        )

        source.start(setOf(VehicleSignalKeys.TurnIndicatorMode)) { update ->
            if (update is VehicleSignalSourceUpdate.ConnectionStarting && update.sourceEpoch >= 2L) {
                reconnected.countDown()
            }
        }

        assertTrue("source did not reconnect after overflow", reconnected.await(2, TimeUnit.SECONDS))
        assertTrue("second channel did not start", second.inNext.await(1, TimeUnit.SECONDS))
        assertEquals(2, opens.get())
        source.stop()
    }

    @Test
    fun repeatedReadyThenFailureUsesExponentialBackoff() {
        val opens = AtomicInteger()
        val delays = Collections.synchronizedList(mutableListOf<Long>())
        val healthy = BlockingChannel()
        val source = TargetedBydLightEventSource(
            channels = TurnSignalEventChannelFactory { _, _ ->
                if (opens.incrementAndGet() <= 3) VendorFailureChannel() else healthy
            },
            clock = { 1L },
            sleep = { delays += it },
            log = {},
        )

        source.start(setOf(VehicleSignalKeys.TurnIndicatorMode)) {}

        assertTrue("fourth channel was not reached", healthy.inNext.await(1, TimeUnit.SECONDS))
        assertEquals(listOf(500L, 1_000L, 2_000L), delays.toList())
        source.stop()
    }

    @Test
    fun aQuickInitialSnapshotDoesNotResetFailureBackoff() {
        val opens = AtomicInteger()
        val delays = Collections.synchronizedList(mutableListOf<Long>())
        val healthy = BlockingChannel()
        val source = TargetedBydLightEventSource(
            channels = TurnSignalEventChannelFactory { _, _ ->
                when (opens.incrementAndGet()) {
                    1 -> VendorFailureChannel()
                    2 -> SampleThenFailureChannel()
                    else -> healthy
                }
            },
            clock = { 1L },
            sleep = { delays += it },
            log = {},
        )

        source.start(setOf(VehicleSignalKeys.TurnIndicatorMode)) {}

        assertTrue("third channel was not reached", healthy.inNext.await(1, TimeUnit.SECONDS))
        assertEquals(listOf(500L, 1_000L), delays.toList())
        source.stop()
    }

    private fun authorizationRequired(): LocalAdbClient.AuthorizationRequiredException {
        val constructor = LocalAdbClient.AuthorizationRequiredException::class.java
            .getDeclaredConstructor()
        constructor.isAccessible = true
        return constructor.newInstance()
    }

    private class BlockingChannel : TurnSignalEventChannel {
        val inNext = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closeCalls = AtomicInteger()

        override fun start() = Unit

        override fun next(waitMs: Int): String {
            inNext.countDown()
            closed.await()
            throw IOException("closed")
        }

        override fun close() {
            closeCalls.incrementAndGet()
            closed.countDown()
        }
    }

    private class OverflowChannel : TurnSignalEventChannel {
        override fun start() = Unit

        override fun next(waitMs: Int): String = "O 1 1000000"

        override fun close() = Unit
    }

    private class VendorFailureChannel : TurnSignalEventChannel {
        override fun start() = Unit

        override fun next(waitMs: Int): String = "X 1000000"

        override fun close() = Unit
    }

    private class SampleThenFailureChannel : TurnSignalEventChannel {
        private var requests = 0

        override fun start() = Unit

        override fun next(waitMs: Int): String = if (++requests == 1) {
            "S 1 1000000 950009900 1"
        } else {
            "X 2000000"
        }

        override fun close() = Unit
    }

    private class BlockingStartChannel : TurnSignalEventChannel {
        val inStart = CountDownLatch(1)
        val closed = CountDownLatch(1)

        override fun start() {
            inStart.countDown()
            closed.await()
            throw IOException("closed")
        }

        override fun next(waitMs: Int): String = error("startup never completed")

        override fun close() {
            closed.countDown()
        }
    }
}
