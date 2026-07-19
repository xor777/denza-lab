package dev.denza.apps.feature.split

internal class SplitShellRouter(
    private val shell: (String) -> String,
) {
    private var session: SplitSelectionSession? = null
    private var splitWasVisible = false

    fun tick(): Boolean {
        val snapshot = SplitTaskSnapshot.parse(shell("am stack list"))
        val pickerPane = snapshot.pane(PICKER_ANCHOR) ?: return leaveSplit()
        val freePane = snapshot.pane(FREE_PANE_ANCHOR) ?: return leaveSplit()
        val splitVisible = pickerPane.visible && freePane.visible

        if (splitVisible) {
            observeVisibleSplit(pickerPane, freePane)
            splitWasVisible = true
            return true
        }

        if (splitWasVisible && routeForegroundLaunch(snapshot, pickerPane, freePane)) {
            return true
        }
        return leaveSplit()
    }

    /**
     * Drops the short-lived stock-picker context without moving any tasks.
     * Explicit task moves owned by another feature must never be interpreted
     * as the next application selected inside the stock split shell.
     */
    fun cancelPendingSelection() {
        session = null
        splitWasVisible = false
    }

    private fun observeVisibleSplit(
        pickerPane: SplitRootTask,
        freePane: SplitRootTask,
    ) {
        val pickerVisible = pickerPane.topPackageName in PICKER_PACKAGES
        if (!pickerVisible || session != null) return

        val freePaneIsEmpty = freePane.topPackageName == FREE_PANE_ANCHOR
        session = SplitSelectionSession(
            nextPane = if (freePaneIsEmpty) SplitPane.FREE else SplitPane.PICKER,
        )
    }

    private fun routeForegroundLaunch(
        snapshot: SplitTaskSnapshot,
        pickerPane: SplitRootTask,
        freePane: SplitRootTask,
    ): Boolean {
        val activeSession = session ?: return false
        val nextPane = activeSession.nextPane ?: return false
        val candidate = snapshot.foregroundTaskOutside(
            paneIds = setOf(pickerPane.id, freePane.id),
            excludedPackages = SHELL_PACKAGES,
        ) ?: return false
        val destination = if (nextPane == SplitPane.FREE) freePane else pickerPane

        run("am stack move-task ${candidate.id} ${destination.id} true")
        resize(candidate.id, destination.bounds)
        activeSession.nextPane = when (nextPane) {
            SplitPane.FREE -> SplitPane.PICKER
            SplitPane.PICKER -> null
        }
        return true
    }

    fun disable() {
        val snapshot = SplitTaskSnapshot.parse(shell("am stack list"))
        val fullRoot = snapshot.roots.firstOrNull { root ->
            root.displayId == 0 && root.tasks.any { it.packageName == DENZA_APPS_PACKAGE }
        }
        val pickerPane = snapshot.pane(PICKER_ANCHOR)
        val freePane = snapshot.pane(FREE_PANE_ANCHOR)

        if (fullRoot != null) {
            listOfNotNull(pickerPane, freePane)
                .flatMap(SplitRootTask::tasks)
                .distinctBy(SplitTask::id)
                .filterNot { it.packageName in SHELL_PACKAGES }
                .forEach { task ->
                    run("am stack move-task ${task.id} ${fullRoot.id} false")
                    resize(task.id, fullRoot.bounds)
                }
        }

        pickerPane?.tasks
            ?.firstOrNull { it.packageName == PICKER_ANCHOR }
            ?.let { anchor ->
                run("am stack move-task ${anchor.id} ${pickerPane.id} true")
                resize(anchor.id, pickerPane.bounds)
            }
        freePane?.tasks
            ?.firstOrNull { it.packageName == FREE_PANE_ANCHOR }
            ?.let { anchor ->
                run("am stack move-task ${anchor.id} ${freePane.id} true")
                resize(anchor.id, freePane.bounds)
            }
        cancelPendingSelection()
    }

    private fun resize(taskId: Int, bounds: SplitBounds) {
        run("am task resize $taskId ${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}")
    }

    private fun run(command: String) {
        val output = shell(command)
        check(
            !output.contains("Error:", ignoreCase = true) &&
                !output.contains("Exception", ignoreCase = true),
        ) { output.trim().ifBlank { "task command failed" } }
    }

    private fun leaveSplit(): Boolean {
        session = null
        splitWasVisible = false
        return false
    }

    private data class SplitSelectionSession(
        var nextPane: SplitPane?,
    )

    private enum class SplitPane { FREE, PICKER }

    private companion object {
        const val DENZA_APPS_PACKAGE = "dev.denza.apps"
        const val PICKER_ANCHOR = "com.android.launcher3"
        const val PICKER_CONTENT = "com.byd.auto_photo"
        const val FREE_PANE_ANCHOR = "com.byd.launchermap"
        val PICKER_PACKAGES = setOf(PICKER_ANCHOR, PICKER_CONTENT)
        val SHELL_PACKAGES = PICKER_PACKAGES + FREE_PANE_ANCHOR + DENZA_APPS_PACKAGE
    }
}

internal data class SplitBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal data class SplitTask(
    val id: Int,
    val packageName: String,
    val bounds: SplitBounds,
    val visible: Boolean,
    val rootId: Int,
    val topPackageName: String?,
) {
    val isTop: Boolean get() = visible && packageName == topPackageName
}

internal data class SplitRootTask(
    val id: Int,
    val bounds: SplitBounds,
    val displayId: Int,
    val tasks: List<SplitTask>,
) {
    val visible: Boolean get() = tasks.any(SplitTask::visible)
    val topPackageName: String?
        get() = tasks.firstOrNull(SplitTask::isTop)?.packageName
            ?: tasks.firstNotNullOfOrNull(SplitTask::topPackageName)
}

internal data class SplitTaskSnapshot(val roots: List<SplitRootTask>) {
    fun pane(anchorPackage: String): SplitRootTask? = roots.firstOrNull { root ->
        root.displayId == 0 && root.tasks.any { it.packageName == anchorPackage }
    }

    fun foregroundTaskOutside(
        paneIds: Set<Int>,
        excludedPackages: Set<String>,
    ): SplitTask? = roots.asSequence()
        .filter { it.displayId == 0 && it.id !in paneIds }
        .flatMap { it.tasks.asSequence() }
        .firstOrNull { it.isTop && it.packageName !in excludedPackages }

    companion object {
        private val rootPattern = Regex(
            "^RootTask id=(\\d+) bounds=\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)] displayId=(\\d+)",
        )
        private val taskPattern = Regex(
            "^\\s+taskId=(\\d+):\\s+([^\\s/]+)(?:/[^\\s]+)?\\s+bounds=\\[(-?\\d+),(-?\\d+)]" +
                "\\[(-?\\d+),(-?\\d+)]\\s+userId=\\d+\\s+visible=(true|false)" +
                "(?:\\s+topActivity=ComponentInfo\\{([^/}\\s]+)/[^}]+\\})?",
        )

        fun parse(text: String): SplitTaskSnapshot {
            val roots = mutableListOf<SplitRootTask>()
            var rootId: Int? = null
            var rootBounds: SplitBounds? = null
            var displayId = -1
            var tasks = mutableListOf<SplitTask>()

            fun finishRoot() {
                val id = rootId ?: return
                val bounds = rootBounds ?: return
                roots += SplitRootTask(id, bounds, displayId, tasks.toList())
            }

            for (line in text.lineSequence()) {
                val rootMatch = rootPattern.find(line)
                if (rootMatch != null) {
                    finishRoot()
                    rootId = rootMatch.groupValues[1].toInt()
                    rootBounds = rootMatch.bounds(2)
                    displayId = rootMatch.groupValues[6].toInt()
                    tasks = mutableListOf()
                    continue
                }
                val id = rootId ?: continue
                val taskMatch = taskPattern.find(line) ?: continue
                tasks += SplitTask(
                    id = taskMatch.groupValues[1].toInt(),
                    packageName = taskMatch.groupValues[2],
                    bounds = taskMatch.bounds(3),
                    visible = taskMatch.groupValues[7].toBoolean(),
                    rootId = id,
                    topPackageName = taskMatch.groupValues[8].ifBlank { null },
                )
            }
            finishRoot()
            return SplitTaskSnapshot(roots)
        }

        private fun MatchResult.bounds(start: Int) = SplitBounds(
            left = groupValues[start].toInt(),
            top = groupValues[start + 1].toInt(),
            right = groupValues[start + 2].toInt(),
            bottom = groupValues[start + 3].toInt(),
        )
    }
}
