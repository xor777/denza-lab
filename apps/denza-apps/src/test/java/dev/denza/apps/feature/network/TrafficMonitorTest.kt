package dev.denza.apps.feature.network

import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class TrafficMonitorTest {
    private val enabled = output(true, true, false)
    private val disabled = output(true, false, true)
    private val absent = output(false, false, false)
    private val completed = "Package ${TrafficMonitorProtocol.PACKAGE} new state: disabled-user\n@@traffic:write:exit=0\n"

    @Test fun `effective state requires complete successful and consistent reads`() {
        assertEquals(TrafficMonitorState.ENABLED, TrafficMonitorProtocol.parse(enabled))
        assertEquals(TrafficMonitorState.DISABLED, TrafficMonitorProtocol.parse(disabled))
        assertEquals(TrafficMonitorState.ABSENT, TrafficMonitorProtocol.parse(absent))
        listOf("", "SecurityException", enabled.substringBeforeLast("@@traffic:"),
            enabled.replace("enabled:exit=0", "enabled:exit=1"),
            enabled + "unexpected", output(true, false, false), output(true, true, true),
            output(false, true, false), enabled.replace("@@traffic:all", "@@traffic:wrong"),
            enabled.replace("package:${TrafficMonitorProtocol.PACKAGE}\n", "package:${TrafficMonitorProtocol.PACKAGE}\npackage:${TrafficMonitorProtocol.PACKAGE}\n"),
        ).forEach { assertEquals(it, TrafficMonitorState.UNKNOWN, TrafficMonitorProtocol.parse(it)) }
    }

    @Test fun `substring matches do not impersonate the stock package`() {
        assertEquals(TrafficMonitorState.ABSENT,
            TrafficMonitorProtocol.parse(enabled.replace(TrafficMonitorProtocol.PACKAGE, TrafficMonitorProtocol.PACKAGE + ".other")))
    }

    @Test fun `writes affect only the package and user zero with a completed exit marker`() {
        assertEquals("pm disable-user --user 0 com.byd.trafficmonitor; echo \"@@traffic:write:exit=${'$'}?\"", TrafficMonitorProtocol.disableCommand)
        assertTrue(TrafficMonitorProtocol.writeCompleted(completed))
        assertFalse(TrafficMonitorProtocol.writeCompleted(completed.substringBefore("@@traffic:")))
        assertFalse(TrafficMonitorProtocol.writeCompleted(completed.replace("exit=0", "exit=1")))
    }

    @Test fun `construction and refresh never change the car`() {
        val h = Harness(enabled)
        assertTrue(h.commands.isEmpty())
        h.controller.disable()
        assertTrue(h.executor.tasks.isEmpty())
        h.refresh()
        assertEquals(listOf(TrafficMonitorProtocol.readCommand), h.commands)
        assertTrue(h.snapshot.canDisable)
    }

    @Test fun `button uses a fresh preflight and readback rather than the shell success text`() {
        val h = Harness(enabled, enabled, completed, disabled)
        h.refresh()
        h.controller.disable()
        assertTrue(h.snapshot.busy)
        assertEquals(TrafficMonitorState.ENABLED, h.snapshot.state)
        assertFalse(h.snapshot.canDisable)
        h.executor.finish()
        assertEquals(TrafficMonitorState.DISABLED, h.snapshot.state)
        assertNull(h.snapshot.error)
        assertEquals("Отключение подтверждено", h.snapshot.result)
        assertEquals(listOf(TrafficMonitorProtocol.readCommand, TrafficMonitorProtocol.readCommand,
            TrafficMonitorProtocol.disableCommand, TrafficMonitorProtocol.readCommand), h.commands)
    }

    @Test fun `an already disabled monitor cannot be changed`() {
        val h = Harness(disabled)
        h.refresh(); h.controller.disable()
        assertEquals(TrafficMonitorState.DISABLED, h.snapshot.state)
        assertFalse(h.snapshot.canDisable)
        assertTrue(h.executor.tasks.isEmpty())
        assertEquals(listOf(TrafficMonitorProtocol.readCommand), h.commands)
    }

    @Test fun `reopening or double tapping while switching never queues another write`() {
        val h = Harness(enabled, enabled, completed, disabled)
        h.refresh(); h.controller.disable()
        h.controller.disable(); h.controller.disable(); h.controller.refresh()
        assertEquals(1, h.executor.tasks.size)
        h.executor.finish()
        assertEquals(1, h.commands.count { it == TrafficMonitorProtocol.disableCommand })
        assertEquals(TrafficMonitorState.DISABLED, h.snapshot.state)
    }

    @Test fun `failed or missing fresh package state prevents writes`() {
        listOf(absent, "read timed out").forEach { before ->
            val h = Harness(enabled, before)
            h.refresh(); h.controller.disable(); h.executor.finish()
            assertEquals(2, h.commands.size)
            assertFalse(h.snapshot.canDisable)
            assertTrue(h.snapshot.error!!.contains("Команда не выполнена"))
            assertNull(h.snapshot.result)
        }
    }

    @Test fun `an external change to the desired state makes the press read only`() {
        val h = Harness(enabled, disabled)
        h.refresh(); h.controller.disable(); h.executor.finish()
        assertEquals(2, h.commands.size)
        assertEquals(TrafficMonitorState.DISABLED, h.snapshot.state)
        assertEquals("Монитор уже отключён", h.snapshot.result)
    }

    @Test fun `refused command and unchanged readback report failure with real state`() {
        val h = Harness(enabled, enabled, "SecurityException\n@@traffic:write:exit=1", enabled)
        h.refresh(); h.controller.disable(); h.executor.finish()
        assertEquals(TrafficMonitorState.ENABLED, h.snapshot.state)
        assertEquals("Не удалось отключить монитор", h.snapshot.error)
        assertNull(h.snapshot.result)
        assertFalse(h.snapshot.busy)
    }

    @Test fun `successful command alone cannot confirm switching`() {
        val h = Harness(enabled, enabled, completed, enabled)
        h.refresh(); h.controller.disable(); h.executor.finish()
        assertEquals(TrafficMonitorState.ENABLED, h.snapshot.state)
        assertNotNull(h.snapshot.error)
        assertNull(h.snapshot.result)
    }

    @Test fun `timeout after write reads back once without retrying the mutation`() {
        val h = Harness(enabled, enabled, IllegalStateException("timeout after OPEN"), disabled)
        h.refresh(); h.controller.disable(); h.executor.finish()
        assertEquals(TrafficMonitorState.DISABLED, h.snapshot.state)
        assertEquals("Состояние изменено, но ответ команды не подтверждён", h.snapshot.error)
        assertNull(h.snapshot.result)
        assertEquals(4, h.commands.size)
    }

    @Test fun `failed post-write read clears old state instead of showing success`() {
        val h = Harness(enabled, enabled, completed, IllegalStateException("unreachable"))
        h.refresh(); h.controller.disable(); h.executor.finish()
        assertEquals(TrafficMonitorState.UNKNOWN, h.snapshot.state)
        assertFalse(h.snapshot.canDisable)
        assertEquals("Не удалось подтвердить состояние монитора", h.snapshot.error)
    }

    @Test fun `refresh discards stale state and absent cannot be changed`() {
        val h = Harness(enabled, absent)
        h.refresh(); h.controller.refresh()
        assertEquals(TrafficMonitorState.UNKNOWN, h.snapshot.state)
        assertTrue(h.snapshot.busy)
        h.executor.finish()
        assertEquals(TrafficMonitorState.ABSENT, h.snapshot.state)
        assertFalse(h.snapshot.known)
        assertEquals("Недоступен на этой прошивке", h.snapshot.summary)
    }

    @Test fun `later refresh clears old result and never enforces the previous action`() {
        val h = Harness(enabled, enabled, completed, disabled, enabled)
        h.refresh(); h.controller.disable(); h.executor.finish()
        assertEquals("Отключение подтверждено", h.snapshot.result)
        h.refresh()
        assertEquals(TrafficMonitorState.ENABLED, h.snapshot.state)
        assertNull(h.snapshot.result)
        assertEquals(1, h.commands.count { it == TrafficMonitorProtocol.disableCommand })
    }

    private fun output(all: Boolean, on: Boolean, off: Boolean): String =
        listOf("all" to all, "enabled" to on, "disabled" to off).joinToString("\n") { (field, present) ->
            "@@traffic:$field\n" + (if (present) "package:${TrafficMonitorProtocol.PACKAGE}\n" else "") + "@@traffic:$field:exit=0"
        }

    private class QueueExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun finish() { tasks.removeFirst().run() }
    }

    private class Harness(vararg replies: Any) {
        val executor = QueueExecutor()
        val commands = mutableListOf<String>()
        private val replies = ArrayDeque(replies.toList())
        val controller = TrafficMonitorController(executor) { command ->
            commands += command
            when (val next = this.replies.removeFirst()) {
                is Exception -> throw next
                else -> next as String
            }
        }
        val snapshot get() = controller.state.value
        fun refresh() { controller.refresh(); executor.finish() }
    }
}
