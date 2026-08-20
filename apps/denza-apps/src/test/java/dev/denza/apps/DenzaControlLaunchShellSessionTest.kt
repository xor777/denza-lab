package dev.denza.apps

import org.junit.Assert.assertTrue
import org.junit.Test

class DenzaControlLaunchShellSessionTest {
    @Test
    fun existingControlTaskIsMovedWithoutRelaunchOrGlobalSettings() {
        val fake = FakeShell()

        DenzaControlLaunchShellSession(fake::shell, pause = {})
            .moveExistingControlFullscreen()

        val move = fake.commands.indexOf("am stack move-task $TASK_ID $FULL_ROOT true")
        val resize = fake.commands.indexOf("am task resize $TASK_ID 0 0 2560 1600")
        val focus = fake.commands.indexOf("am task focus $TASK_ID")
        assertTrue(move >= 0)
        assertTrue(resize > move)
        assertTrue(focus > resize)
        assertTrue(fake.commands.none { it.startsWith("am start ") })
        assertTrue(fake.commands.none { it.startsWith("settings ") })
    }

    private class FakeShell {
        val commands = mutableListOf<String>()
        var rootId = 3
        var fullBounds = false

        fun shell(command: String): String {
            commands += command
            return when (command) {
                "am stack list" -> stack()
                "am stack move-task $TASK_ID $FULL_ROOT true" -> {
                    rootId = FULL_ROOT
                    ""
                }
                "am task resize $TASK_ID 0 0 2560 1600" -> {
                    fullBounds = true
                    ""
                }
                "am task focus $TASK_ID" -> ""
                else -> error("Unexpected command: $command")
            }
        }

        private fun stack(): String = buildString {
            appendLine("RootTask id=$FULL_ROOT bounds=[0,0][2560,1600] displayId=0 userId=0")
            if (rootId == FULL_ROOT) appendTask()
            appendLine(" configuration={ winConfig={ mActivityType=standard }}")
            if (rootId != FULL_ROOT) {
                appendLine("RootTask id=$rootId bounds=[24,112][1680,1472] displayId=0 userId=0")
                appendTask()
                appendLine(" configuration={ winConfig={ mActivityType=standard }}")
            }
        }

        private fun StringBuilder.appendTask() {
            val bounds = if (fullBounds) "[0,0][2560,1600]" else "[24,112][1680,1472]"
            appendLine(
                "  taskId=$TASK_ID: dev.denza.apps/dev.denza.apps.MainActivity " +
                    "bounds=$bounds userId=0 visible=true " +
                    "topActivity=ComponentInfo{dev.denza.apps/dev.denza.apps.MainActivity}",
            )
        }
    }

    private companion object {
        const val TASK_ID = 51
        const val FULL_ROOT = 4
    }
}
