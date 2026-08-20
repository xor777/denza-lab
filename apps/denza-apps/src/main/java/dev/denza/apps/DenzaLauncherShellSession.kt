package dev.denza.apps

import dev.denza.apps.feature.split.SplitTaskSnapshot

/** Exact, shell-backed promotion of the user-started control task to the full IVI root. */
internal class DenzaLauncherShellSession(
    private val shell: (String) -> String,
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    fun moveToFullIvi(taskId: Int) {
        check(taskId > 0) { "Launcher task id is unavailable" }
        var state = awaitTask(taskId)
        val fullRoot = state.root(FULL_IVI_ROOT_ID)
            ?: error("Полноэкранный IVI-контейнер недоступен")
        check(
            fullRoot.displayId == MAIN_DISPLAY_ID &&
                fullRoot.bounds.right > fullRoot.bounds.left &&
                fullRoot.bounds.bottom > fullRoot.bounds.top,
        ) {
            "Некорректный полноэкранный IVI-контейнер"
        }

        var task = state.roots.asSequence()
            .flatMap { it.tasks.asSequence() }
            .first { it.id == taskId }
        if (task.rootId != FULL_IVI_ROOT_ID) {
            // Do not call activity_task transaction 118 here. While native split is active that
            // transaction changes SmartMulti mode before it returns. Root 4 is the stable full
            // IVI organizer on this supported firmware and moving only our task preserves both
            // picker/app stacks underneath it.
            run("am stack move-task $taskId $FULL_IVI_ROOT_ID true")
            pause(SETTLE_MS)
        }

        state = awaitTaskInRoot(taskId, FULL_IVI_ROOT_ID)
        val currentFullRoot = state.root(FULL_IVI_ROOT_ID)
            ?: error("Полноэкранный IVI-контейнер исчез")
        task = currentFullRoot.tasks.firstOrNull { it.id == taskId }
            ?: error("Control-задача не вошла в полноэкранный IVI-контейнер")
        if (task.bounds != currentFullRoot.bounds) {
            val bounds = currentFullRoot.bounds
            run(
                "am task resize $taskId ${bounds.left} ${bounds.top} " +
                    "${bounds.right} ${bounds.bottom}",
            )
            pause(SETTLE_MS)
        }

        val verifiedRoot = awaitTaskInRoot(taskId, FULL_IVI_ROOT_ID).root(FULL_IVI_ROOT_ID)
            ?: error("Полноэкранный IVI-контейнер исчез после переноса")
        val verified = verifiedRoot.tasks.firstOrNull { it.id == taskId }
            ?: error("Control-задача исчезла после переноса")
        check(verified.bounds == verifiedRoot.bounds) {
            "Control-задача не приняла полноэкранный размер"
        }
    }

    private fun awaitTask(taskId: Int): SplitTaskSnapshot {
        repeat(DISCOVERY_ATTEMPTS) { attempt ->
            val state = snapshot()
            if (state.roots.any { root -> root.tasks.any { it.id == taskId } }) return state
            if (attempt + 1 < DISCOVERY_ATTEMPTS) pause(DISCOVERY_INTERVAL_MS)
        }
        error("Launcher task $taskId не появился в ActivityTaskManager")
    }

    private fun awaitTaskInRoot(taskId: Int, rootId: Int): SplitTaskSnapshot {
        repeat(DISCOVERY_ATTEMPTS) { attempt ->
            val state = snapshot()
            if (state.root(rootId)?.tasks?.any { it.id == taskId } == true) return state
            if (attempt + 1 < DISCOVERY_ATTEMPTS) pause(DISCOVERY_INTERVAL_MS)
        }
        error("Control-задача не вошла в полноэкранный IVI-контейнер")
    }

    private fun snapshot(): SplitTaskSnapshot =
        SplitTaskSnapshot.parse(shell("am stack list").also(::validateOutput))

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
        const val FULL_IVI_ROOT_ID = 4
        const val DISCOVERY_ATTEMPTS = 8
        const val DISCOVERY_INTERVAL_MS = 100L
        const val SETTLE_MS = 120L
    }
}
