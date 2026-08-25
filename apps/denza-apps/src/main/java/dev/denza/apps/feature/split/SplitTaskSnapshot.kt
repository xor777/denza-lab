package dev.denza.apps.feature.split

/**
 * The two topology reads of one operation, shared for as long as nothing can have changed them.
 *
 * `am stack list` and the two `activity_task 118` calls that name the panel roots are by far the
 * chattiest part of every recipe, and one operation used to repeat them up to five times before its
 * first mutation - three whole `am stack list` for a single open. They describe the same instant, so
 * they are read once and handed to every reader until something happens that could move a task.
 *
 * What may invalidate is deliberately a *deny*-by-default rule, held in one place
 * ([isTopologyRead]): only the handful of commands that provably cannot move a task keep the shared
 * read alive, every other command drops it, and so does every settle pause - a polling recipe like
 * `awaitTaskMatching` must see the car again on each attempt, not its own first sample forever.
 *
 * This is not an "atomic snapshot": consistency still comes from the token check around every
 * mutation and from each recipe's own postcondition on a fresh read (contract section 7).
 */
internal class SplitTopologyCache {
    private val lock = Any()
    private var roots: Map<SplitPane, Int>? = null
    private var state: SplitTaskSnapshot? = null

    fun invalidate() {
        synchronized(lock) {
            roots = null
            state = null
        }
    }

    fun roots(read: () -> Map<SplitPane, Int>): Map<SplitPane, Int> {
        synchronized(lock) { roots }?.let { return it }
        val fresh = read()
        synchronized(lock) { roots = fresh }
        return fresh
    }

    fun state(read: () -> SplitTaskSnapshot): SplitTaskSnapshot {
        synchronized(lock) { state }?.let { return it }
        val fresh = read()
        synchronized(lock) { state = fresh }
        return fresh
    }

    companion object {
        /** Commands that cannot move, create or destroy a task. Everything else invalidates. */
        fun isTopologyRead(command: String): Boolean =
            command == "am stack list" ||
                READS.any { read -> command.startsWith(read) }

        /**
         * `activity_task` 30 is the current area, 112 is-split-capable, 118 the root of an area;
         * `settings get` and `dumpsys` are how the two global leases and the pointer are read.
         */
        private val READS = listOf(
            "service call activity_task 30",
            "service call activity_task 112 ",
            "service call activity_task 118 ",
            "settings get ",
            "dumpsys ",
        )
    }
}

internal data class SplitBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal fun SplitBounds.hasArea(): Boolean = right > left && bottom > top

internal fun SplitBounds.width(): Int = right - left

/**
 * Whether this rectangle holds the other one and is strictly larger than it.
 *
 * Two panel containers side by side never satisfy it in either direction; one stretched over the
 * whole display satisfies it exactly once. That asymmetry is what tells a collapsed split from a
 * balanced one long after the screen stopped showing either (волна 12).
 */
internal fun SplitBounds.strictlyContains(other: SplitBounds): Boolean =
    this != other &&
        left <= other.left &&
        top <= other.top &&
        right >= other.right &&
        bottom >= other.bottom

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
