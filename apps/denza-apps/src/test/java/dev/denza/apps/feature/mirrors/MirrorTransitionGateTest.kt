package dev.denza.apps.feature.mirrors

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorTransitionGateTest {
    @Test
    fun stopWaitsForInFlightShowAndMakesHideTheFinalCommand() {
        val gate = MirrorTransitionGate()
        val showEntered = CountDownLatch(1)
        val allowShow = CountDownLatch(1)
        val commands = Collections.synchronizedList(mutableListOf<String>())
        assertTrue(gate.start())

        val poll = thread {
            gate.runIfRunning {
                showEntered.countDown()
                allowShow.await(1, TimeUnit.SECONDS)
                commands += "show"
            }
        }
        assertTrue(showEntered.await(1, TimeUnit.SECONDS))
        val stop = thread { gate.stop { commands += "hide" } }

        allowShow.countDown()
        poll.join(1_000L)
        stop.join(1_000L)

        assertEquals(listOf("show", "hide"), commands.toList())
        assertFalse(gate.isRunning)
        assertFalse(gate.runIfRunning { commands += "late-show" })
        assertEquals(listOf("show", "hide"), commands.toList())
    }

    @Test
    fun duplicateStartDoesNotCreateASecondLifecycle() {
        val gate = MirrorTransitionGate()

        assertTrue(gate.start())
        assertFalse(gate.start())
    }
}
