package dev.denza.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DenzaLauncherShellSessionTest {
    @Test
    fun splitControlTaskIsMovedDirectlyWithoutChangingSmartMultiMode() {
        val fake = FakeShell(rootId = 3, bounds = NARROW)

        DenzaLauncherShellSession(fake::shell, pause = {}).moveToFullIvi(TASK_ID)

        assertEquals(FULL_ROOT, fake.rootId)
        assertEquals(FULL, fake.bounds)
        val move = fake.commands.indexOf("am stack move-task $TASK_ID $FULL_ROOT true")
        val resize = fake.commands.indexOf("am task resize $TASK_ID 0 0 2560 1600")
        assertTrue(move >= 0)
        assertTrue(resize > move)
        assertFalse(fake.commands.any { it.startsWith("service call activity_task ") })
    }

    @Test
    fun alreadyFullControlTaskNeedsNoMutation() {
        val fake = FakeShell(rootId = FULL_ROOT, bounds = FULL)

        DenzaLauncherShellSession(fake::shell, pause = {}).moveToFullIvi(TASK_ID)

        assertFalse(fake.commands.any { it.startsWith("am stack move-task ") })
        assertFalse(fake.commands.any { it.startsWith("am task resize ") })
    }

    private class FakeShell(
        var rootId: Int,
        var bounds: Bounds,
    ) {
        val commands = mutableListOf<String>()

        fun shell(command: String): String {
            commands += command
            return when {
                command == "am stack list" -> stack()
                command == "am stack move-task $TASK_ID $FULL_ROOT true" -> {
                    rootId = FULL_ROOT
                    ""
                }
                command == "am task resize $TASK_ID 0 0 2560 1600" -> {
                    bounds = FULL
                    ""
                }
                else -> error("Unexpected command: $command")
            }
        }

        private fun stack(): String = buildString {
            appendLine("RootTask id=$FULL_ROOT bounds=[0,0][2560,1600] displayId=0 userId=0")
            if (rootId == FULL_ROOT) appendTask()
            appendLine(" configuration={ winConfig={ mActivityType=standard }}")
            appendLine()
            if (rootId != FULL_ROOT) {
                appendLine("RootTask id=$rootId bounds=[24,112][1680,1472] displayId=0 userId=0")
                appendTask()
                appendLine(" configuration={ winConfig={ mActivityType=standard }}")
            }
        }

        private fun StringBuilder.appendTask() {
            appendLine(
                "  taskId=$TASK_ID: dev.denza.apps/dev.denza.apps.DenzaLauncherActivity " +
                    "bounds=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}] " +
                    "userId=0 visible=true " +
                    "topActivity=ComponentInfo{dev.denza.apps/dev.denza.apps.DenzaLauncherActivity}",
            )
        }
    }

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private companion object {
        const val TASK_ID = 51
        const val FULL_ROOT = 4
        val FULL = Bounds(0, 0, 2560, 1600)
        val NARROW = Bounds(24, 112, 1680, 1472)
    }
}
