package dev.denza.apps.feature.hud

import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFlightReadRunnerTest {
    @Test
    fun `read and result delivery use their configured executors`() {
        val worker = QueueExecutor()
        val callbacks = QueueExecutor()
        var readCalled = false
        var delivered: String? = null
        val runner = SingleFlightReadRunner(
            worker,
            callbacks,
            {
                readCalled = true
                "guidance"
            },
        ) { value, error ->
            assertNull(error)
            delivered = value
        }

        runner.activate()
        runner.request()

        assertFalse(readCalled)
        assertNull(delivered)
        assertEquals(1, worker.size)

        worker.runNext()

        assertTrue(readCalled)
        assertNull(delivered)
        assertEquals(1, callbacks.size)

        callbacks.runNext()

        assertEquals("guidance", delivered)
    }

    @Test
    fun `requests during a read coalesce into one trailing read`() {
        val worker = QueueExecutor()
        val callbacks = QueueExecutor()
        var reads = 0
        val delivered = mutableListOf<Int>()
        val runner = SingleFlightReadRunner(
            worker,
            callbacks,
            { ++reads },
        ) { value, error ->
            assertNull(error)
            delivered += value
        }

        runner.activate()
        runner.request()
        runner.request()
        runner.request()
        runner.request()

        assertEquals(1, worker.size)
        worker.runNext()
        callbacks.runNext()

        assertEquals(listOf(1), delivered)
        assertEquals(1, worker.size)

        worker.runNext()
        callbacks.runNext()

        assertEquals(2, reads)
        assertEquals(listOf(1, 2), delivered)
        assertEquals(0, worker.size)
    }

    @Test
    fun `late result from an inactive generation is ignored`() {
        val worker = QueueExecutor()
        val callbacks = QueueExecutor()
        val delivered = mutableListOf<String>()
        val runner = SingleFlightReadRunner(
            worker,
            callbacks,
            { "stale" },
        ) { value, error ->
            assertNull(error)
            delivered += value
        }

        runner.activate()
        runner.request()
        worker.runNext()
        runner.deactivate()
        callbacks.runNext()

        assertTrue(delivered.isEmpty())
        assertFalse(runner.request())
    }

    @Test
    fun `read failure is delivered and a later request can recover`() {
        val worker = QueueExecutor()
        val callbacks = QueueExecutor()
        var fail = true
        val values = mutableListOf<String?>()
        val errors = mutableListOf<Throwable?>()
        val runner = SingleFlightReadRunner(
            worker,
            callbacks,
            {
                if (fail) error("binder timeout")
                "recovered"
            },
        ) { value, error ->
            values += value
            errors += error
        }

        runner.activate()
        runner.request()
        worker.runNext()
        callbacks.runNext()

        assertNull(values.single())
        assertNotNull(errors.single())

        fail = false
        runner.request()
        worker.runNext()
        callbacks.runNext()

        assertEquals(listOf(null, "recovered"), values)
        assertNull(errors.last())
    }

    private class QueueExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        val size: Int
            get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }
}
