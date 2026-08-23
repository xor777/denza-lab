package dev.denza.apps.feature.split

internal data class SplitBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal fun SplitBounds.hasArea(): Boolean = right > left && bottom > top

internal fun SplitBounds.width(): Int = right - left

internal data class SplitTask(
    val id: Int,
    val packageName: String,
    val activityName: String?,
    val bounds: SplitBounds,
    val visible: Boolean,
    val rootId: Int,
    val topPackageName: String?,
    val topActivityName: String?,
) {
    val isTop: Boolean
        get() = visible &&
            packageName == topPackageName &&
            (topActivityName == null || activityName == topActivityName)
}

internal data class SplitRootTask(
    val id: Int,
    val bounds: SplitBounds,
    val displayId: Int,
    val activityType: String?,
    val tasks: List<SplitTask>,
)

internal data class SplitTaskSnapshot(val roots: List<SplitRootTask>) {
    fun root(rootId: Int): SplitRootTask? =
        roots.firstOrNull { it.id == rootId && it.displayId == 0 }

    companion object {
        private val rootPattern = Regex(
            "^RootTask id=(\\d+) bounds=\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)] displayId=(\\d+)",
        )
        private val taskPattern = Regex(
            "^\\s+taskId=(\\d+):\\s+([^\\s/]+)(?:/([^\\s]+))?\\s+bounds=\\[(-?\\d+),(-?\\d+)]" +
                "\\[(-?\\d+),(-?\\d+)]\\s+userId=\\d+\\s+visible=(true|false)" +
                "(?:\\s+topActivity=ComponentInfo\\{([^/}\\s]+)/([^}\\s]+)\\})?",
        )
        private val activityTypePattern = Regex("mActivityType=([^\\s}]+)")

        fun parse(text: String): SplitTaskSnapshot {
            val roots = mutableListOf<SplitRootTask>()
            var rootId: Int? = null
            var rootBounds: SplitBounds? = null
            var displayId = -1
            var activityType: String? = null
            var tasks = mutableListOf<SplitTask>()

            fun finishRoot() {
                val id = rootId ?: return
                val bounds = rootBounds ?: return
                roots += SplitRootTask(id, bounds, displayId, activityType, tasks.toList())
            }

            for (line in text.lineSequence()) {
                val rootMatch = rootPattern.find(line)
                if (rootMatch != null) {
                    finishRoot()
                    rootId = rootMatch.groupValues[1].toInt()
                    rootBounds = rootMatch.bounds(2)
                    displayId = rootMatch.groupValues[6].toInt()
                    activityType = null
                    tasks = mutableListOf()
                    continue
                }
                val id = rootId ?: continue
                activityTypePattern.find(line)?.let { match ->
                    activityType = match.groupValues[1]
                }
                val taskMatch = taskPattern.find(line) ?: continue
                val packageName = taskMatch.groupValues[2]
                tasks += SplitTask(
                    id = taskMatch.groupValues[1].toInt(),
                    packageName = packageName,
                    activityName = taskMatch.groupValues[3]
                        .ifBlank { null }
                        ?.let { canonicalActivityName(packageName, it) },
                    bounds = taskMatch.bounds(4),
                    visible = taskMatch.groupValues[8].toBoolean(),
                    rootId = id,
                    topPackageName = taskMatch.groupValues[9].ifBlank { null },
                    topActivityName = taskMatch.groupValues[10]
                        .ifBlank { null }
                        ?.let { canonicalActivityName(taskMatch.groupValues[9], it) },
                )
            }
            finishRoot()
            return SplitTaskSnapshot(roots)
        }

        private fun canonicalActivityName(packageName: String, activityName: String): String =
            if (activityName.startsWith(".")) packageName + activityName else activityName

        private fun MatchResult.bounds(start: Int) = SplitBounds(
            left = groupValues[start].toInt(),
            top = groupValues[start + 1].toInt(),
            right = groupValues[start + 2].toInt(),
            bottom = groupValues[start + 3].toInt(),
        )
    }
}

/**
 * `am stack list` repeats the root's top component on every task line. Package
 * equality alone is ambiguous when Denza Apps and its placeholder share a
 * root, so prefer the exact component. Package-only fallback is allowed only
 * when exactly one visible task can own that top package (normal internal
 * Activity transitions within one task).
 */
internal fun SplitRootTask.resolvedTopTask(): SplitTask? {
    val soleVisible = tasks.singleOrNull { task -> task.visible }
    if (soleVisible != null) return soleVisible
    val packageCandidates = tasks.filter { task ->
        task.visible && task.packageName == task.topPackageName
    }
    val exactCandidates = packageCandidates.filter { task ->
        task.topActivityName != null && task.activityName == task.topActivityName
    }
    exactCandidates.firstOrNull()?.let { return it }
    packageCandidates.singleOrNull()?.let { return it }
    return tasks.singleOrNull { task ->
        task.visible &&
            task.packageName == SPLIT_HOST_PACKAGE &&
            task.activityName == SPLIT_APP_HOST_ACTIVITY &&
            task.topPackageName != null &&
            task.topPackageName != task.packageName
    }
}
