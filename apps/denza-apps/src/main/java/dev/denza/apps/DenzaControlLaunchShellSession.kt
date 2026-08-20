package dev.denza.apps

import dev.denza.apps.feature.split.SplitTaskSnapshot

/** Waits for the user-started control task, then moves only that task to full IVI. */
internal class DenzaControlLaunchShellSession(
    private val shell: (String) -> String,
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    fun moveExistingControlFullscreen() {
        val taskId = awaitControlTaskId()
        DenzaLauncherShellSession(shell, pause).moveToFullIvi(taskId)
        // Finishing the windowless launcher can briefly return focus to SmartMulti's survivor
        // after the move. Refocus only after that transition settles.
        pause(FINAL_FOCUS_DELAY_MS)
        run("am task focus $taskId")
    }

    private fun awaitControlTaskId(): Int {
        repeat(DISCOVERY_ATTEMPTS) { attempt ->
            val state = SplitTaskSnapshot.parse(
                shell("am stack list").also(::validateOutput),
            )
            val control = state.roots.asSequence()
                .filter { it.displayId == MAIN_DISPLAY_ID }
                .flatMap { it.tasks.asSequence() }
                .firstOrNull { task ->
                    task.packageName == CONTROL_PACKAGE &&
                        task.activityName == CONTROL_ACTIVITY
                }
            if (control != null) return control.id
            if (attempt + 1 < DISCOVERY_ATTEMPTS) pause(DISCOVERY_INTERVAL_MS)
        }
        error("Control-задача не появилась после запуска")
    }

    private fun run(command: String) {
        validateOutput(shell(command))
    }

    private fun validateOutput(output: String) {
        check(
            !output.contains("Error:", ignoreCase = true) &&
                !output.contains("Exception", ignoreCase = true) &&
                !output.contains("UNKNOWN_TRANSACTION", ignoreCase = true),
        ) { output.trim().ifBlank { "shell command failed" } }
    }

    private companion object {
        const val MAIN_DISPLAY_ID = 0
        const val CONTROL_PACKAGE = "dev.denza.apps"
        const val CONTROL_ACTIVITY = "dev.denza.apps.MainActivity"
        const val DISCOVERY_ATTEMPTS = 16
        const val DISCOVERY_INTERVAL_MS = 120L
        const val FINAL_FOCUS_DELAY_MS = 600L
    }
}
