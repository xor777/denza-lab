package dev.denza.apps.feature.split

internal class SplitShellRouter(
    private val shell: (String) -> String,
) {
    private val knownTaskIds = mutableSetOf<Int>()
    private var splitWasVisible = false

    fun tick(): Boolean {
        val snapshot = SplitTaskSnapshot.parse(shell("am stack list"))
        val left = snapshot.pane(LEFT_ANCHOR) ?: return leaveSplit()
        val right = snapshot.pane(RIGHT_ANCHOR) ?: return leaveSplit()
        val splitVisible = left.visible && right.visible
        if (!splitVisible) {
            if (splitWasVisible && routeForegroundLaunch(snapshot, left, right)) return true
            return leaveSplit()
        }

        val initialSnapshot = !splitWasVisible
        if (initialSnapshot) knownTaskIds.clear()
        route(snapshot, left, NAVIGATOR_PACKAGE, initialSnapshot)
        route(snapshot, right, YANDEX_MUSIC_PACKAGE, initialSnapshot)
        route(snapshot, right, APPLE_MUSIC_PACKAGE, initialSnapshot)
        splitWasVisible = true
        return true
    }

    private fun routeForegroundLaunch(
        snapshot: SplitTaskSnapshot,
        left: SplitRootTask,
        right: SplitRootTask,
    ): Boolean {
        val targets = listOf(
            NAVIGATOR_PACKAGE to left,
            YANDEX_MUSIC_PACKAGE to right,
            APPLE_MUSIC_PACKAGE to right,
        )
        for ((packageName, pane) in targets) {
            val task = snapshot.task(packageName) ?: continue
            if (!task.isTop || task.rootId == pane.id) continue
            run("am stack move-task ${task.id} ${pane.id} true")
            resize(task.id, pane.bounds)
            return true
        }
        return false
    }

    fun disable() {
        val snapshot = SplitTaskSnapshot.parse(shell("am stack list"))
        val fullRoot = snapshot.roots.firstOrNull { root ->
            root.displayId == 0 && root.tasks.any { it.packageName == DENZA_APPS_PACKAGE }
        }
        if (fullRoot != null) {
            for (packageName in TARGET_PACKAGES) {
                val task = snapshot.task(packageName) ?: continue
                if (task.rootId == fullRoot.id) continue
                run("am stack move-task ${task.id} ${fullRoot.id} false")
                resize(task.id, fullRoot.bounds)
            }
        }

        snapshot.pane(LEFT_ANCHOR)?.let { pane ->
            pane.tasks.firstOrNull { it.packageName == LEFT_ANCHOR }?.let { anchor ->
                run("am stack move-task ${anchor.id} ${pane.id} true")
                resize(anchor.id, pane.bounds)
            }
        }
        snapshot.pane(RIGHT_ANCHOR)?.let { pane ->
            pane.tasks.firstOrNull { it.packageName == RIGHT_ANCHOR }?.let { anchor ->
                run("am stack move-task ${anchor.id} ${pane.id} true")
                resize(anchor.id, pane.bounds)
            }
        }
        knownTaskIds.clear()
        splitWasVisible = false
    }

    private fun route(
        snapshot: SplitTaskSnapshot,
        pane: SplitRootTask,
        packageName: String,
        initialSnapshot: Boolean,
    ) {
        val task = snapshot.task(packageName) ?: return
        val firstSeen = knownTaskIds.add(task.id)
        if (task.rootId != pane.id) {
            val toTop = task.isTop || (!initialSnapshot && firstSeen)
            run("am stack move-task ${task.id} ${pane.id} $toTop")
        }
        if (task.rootId != pane.id || task.bounds != pane.bounds) {
            resize(task.id, pane.bounds)
        }
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
        if (splitWasVisible) knownTaskIds.clear()
        splitWasVisible = false
        return false
    }

    private companion object {
        const val DENZA_APPS_PACKAGE = "dev.denza.apps"
        const val LEFT_ANCHOR = "com.android.launcher3"
        const val RIGHT_ANCHOR = "com.byd.launchermap"
        const val NAVIGATOR_PACKAGE = "ru.yandex.yandexnavi"
        const val YANDEX_MUSIC_PACKAGE = "ru.yandex.music"
        const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
        val TARGET_PACKAGES = listOf(NAVIGATOR_PACKAGE, YANDEX_MUSIC_PACKAGE, APPLE_MUSIC_PACKAGE)
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
}

internal data class SplitTaskSnapshot(val roots: List<SplitRootTask>) {
    fun pane(anchorPackage: String): SplitRootTask? = roots.firstOrNull { root ->
        root.displayId == 0 && root.tasks.any { it.packageName == anchorPackage }
    }

    fun task(packageName: String): SplitTask? = roots.asSequence()
        .filter { it.displayId == 0 }
        .flatMap { it.tasks.asSequence() }
        .firstOrNull { it.packageName == packageName }

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
