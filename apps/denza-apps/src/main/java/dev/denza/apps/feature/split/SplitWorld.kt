package dev.denza.apps.feature.split

import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_PRIMARY_FULL
import dev.denza.apps.feature.split.SplitWorld.Companion.AREA_SECONDARY_FULL
import dev.denza.apps.platform.shell.ServiceCallParcel

/**
 * The car as one split session sees it: the funnel every command and every settle pause of every
 * recipe goes through, the reads of the task tree and of the area, and the waits on them.
 *
 * Nothing here decides anything about a scene. The recipes - [SplitGate], the scene reads, the
 * build, the selection, the collapse, the navigation return, the edge and the teardown - ask, and
 * this is the one place that turns a question into a command and an answer into the model
 * ([SplitTaskSnapshot], [SplitTopologyCache]). The identity predicates below the class are the
 * other half of reading the world: which task is ours, which is the firmware's, and which is the
 * user's.
 */
internal class SplitWorld(
    shell: (String) -> String,
    private val settle: (Long) -> Unit,
    /**
     * The topology reads of the operation this session belongs to. The default of the session is
     * a private one, which makes a stand-alone session share reads only within itself.
     */
    private val topology: SplitTopologyCache,
    /**
     * Milliseconds spent turning an answer into the model, reported to the operation's budget.
     *
     * Every parse of this session goes through it, so the ring can say whether a slow operation
     * was slow because of the car or because of 8 KB of text and a regular expression per line.
     */
    private val parsed: (Long) -> Unit,
) {
    private val send = shell

    /**
     * Every command of every recipe, in the order the recipe sends it - unchanged, and the one
     * place that decides whether the shared topology read may outlive it (deny by default).
     */
    fun shell(command: String): String {
        if (!SplitTopologyCache.isTopologyRead(command)) topology.invalidate()
        return send(command)
    }

    /**
     * Every settle pause of every recipe. It drops the shared topology read first: a recipe that
     * waits is a recipe that expects the car to have changed underneath it.
     */
    fun pause(millis: Long) {
        topology.invalidate()
        settle(millis)
    }

    fun snapshot(): SplitTaskSnapshot = topology.state {
        val answer = shell("am stack list").also(::validateOutput)
        measured { SplitTaskSnapshot.parse(answer) }
    }

    /** One pair of marks around the work that is neither the car's nor the transport's. */
    private inline fun <T> measured(read: () -> T): T {
        val startedAt = System.nanoTime()
        try {
            return read()
        } finally {
            parsed((System.nanoTime() - startedAt) / 1_000_000L)
        }
    }

    fun callBoolean(command: String): Boolean = callInt(command) != 0

    fun callInt(command: String): Int {
        val output = shell(command).also(::validateOutput)
        return measured {
            val words = ServiceCallParcel.words(output)
                ?: error("Некорректный ответ activity_task")
            check(words.size >= 2 && words[0] == 0) {
                "Ошибка activity_task: ${output.trim()}"
            }
            words[1]
        }
    }

    fun callVoid(command: String) {
        val output = shell(command).also(::validateOutput)
        val words = ServiceCallParcel.words(output)
            ?: error("Некорректный ответ activity_task")
        check(words.isNotEmpty() && words[0] == 0) {
            "Ошибка activity_task: ${output.trim()}"
        }
    }

    fun run(command: String) {
        validateOutput(shell(command))
    }

    fun validateOutput(output: String) {
        check(
            !output.contains("Error:", ignoreCase = true) &&
                !output.contains("Exception", ignoreCase = true) &&
                !output.contains("UNKNOWN_TRANSACTION", ignoreCase = true),
        ) { output.trim().ifBlank { "shell command failed" } }
    }

    fun nativeRootIds(): Map<SplitPane, Int> = topology.roots {
        SplitPane.entries.associateWith { pane ->
            callInt("service call activity_task 118 i32 ${pane.areaId}").also { rootId ->
                check(rootId > 0) { "Прошивка не вернула split-контейнер ${pane.areaId}" }
            }
        }
    }

    fun fullIviRootTaskId(): Int =
        callInt("service call activity_task 118 i32 $AREA_FULL_IVI").also { rootId ->
            check(rootId > 0) { "Прошивка не вернула полноэкранный IVI-контейнер" }
        }

    fun mainDisplayTasks(): List<SplitTask> = snapshot().roots.asSequence()
        .filter { it.displayId == MAIN_DISPLAY_ID }
        .flatMap { it.tasks.asSequence() }
        .toList()

    /**
     * Every task the car holds right now, on every display.
     *
     * A mutating operation reads it before its first command, because a launch without
     * `MULTIPLE_TASK` hands back the task the package already had - wherever that was, the
     * instrument display included. Journalling one of those as "started" would let an unwind treat
     * an application the user was already running as this operation's own (invariant 3, U2).
     */
    fun livingTaskIds(): Set<Int> = snapshot().roots.asSequence()
        .flatMap { root -> root.tasks.asSequence() }
        .mapTo(mutableSetOf(), SplitTask::id)

    /**
     * Whether the firmware currently covers the scene: Home (area 0) and a foreign fullscreen
     * window (area 4) hide it without ending it (инвариант 5, 1.9.1, 1.11.5). Read-only.
     */
    fun sceneCovered(): Boolean =
        callInt("service call activity_task 30").let { it == AREA_HOME || it == AREA_FULL_IVI }

    /**
     * Waits for the firmware's own split area to reach a state, and not one slice longer.
     *
     * It looks first and sleeps only between looks, in [AREA_POLL_INTERVAL_MS] slices up to
     * [budgetMs]: a transition the firmware has already finished costs one read, not a settle the
     * user waits out for nothing (1.13).
     */
    fun awaitArea(budgetMs: Long, matches: (Int) -> Boolean): Boolean {
        var waited = 0L
        while (true) {
            if (matches(callInt("service call activity_task 30"))) return true
            if (waited >= budgetMs) return false
            val slice = minOf(AREA_POLL_INTERVAL_MS, budgetMs - waited)
            pause(slice)
            waited += slice
        }
    }

    fun awaitTaskMatching(predicate: (SplitTask) -> Boolean): SplitTask {
        repeat(TASK_DISCOVERY_ATTEMPTS) { attempt ->
            snapshot().roots.asSequence()
                .filter { it.displayId == MAIN_DISPLAY_ID }
                .flatMap { it.tasks.asSequence() }
                .filter(predicate)
                .maxByOrNull(SplitTask::id)
                ?.let { return it }
            if (attempt + 1 < TASK_DISCOVERY_ATTEMPTS) pause(TASK_DISCOVERY_INTERVAL_MS)
        }
        error("Запущенная задача не появилась в ActivityTaskManager")
    }

    /**
     * Polls the whole topology until [matches] agrees, within the discovery budget.
     *
     * A mutation is waited out by condition, not by a blind settle: the first read usually already
     * agrees - `am stack move-task` reparents synchronously on this firmware - and the read then
     * doubles, through the shared topology cache, as the next phase's snapshot. A timeout is not
     * an error here: the recipes that use it end in their own postcondition, which is the honest
     * judge of whether the car really settled.
     */
    fun awaitSnapshotMatching(
        attempts: Int = TASK_DISCOVERY_ATTEMPTS,
        matches: (SplitTaskSnapshot) -> Boolean,
    ): Boolean {
        repeat(attempts) { attempt ->
            if (matches(snapshot())) return true
            if (attempt + 1 < attempts) pause(TASK_DISCOVERY_INTERVAL_MS)
        }
        return false
    }

    /**
     * [awaitSnapshotMatching] over the short budget, and then - while [longer] still says the
     * wait is for a cold application - more looks, one per [COLD_POLL_INTERVAL_MS], for as long as
     * [remainingMs] leaves of the operation (1.3.11, «К 1.14»).
     *
     * The wait is by condition, never a blind sleep: the first agreeing read ends it. It is bounded
     * twice - by the time [remainingMs] reports as each look begins, and by the sum of the pauses
     * against what it reported at the start - so a clock that does not move (a test's) still ends
     * the wait. Like the short form, a timeout is not an error: the recipe's own postcondition
     * judges what the car settled.
     */
    fun awaitSnapshotMatching(
        attempts: Int,
        remainingMs: () -> Long,
        longer: () -> Boolean,
        matches: (SplitTaskSnapshot) -> Boolean,
    ): Boolean {
        if (awaitSnapshotMatching(attempts, matches)) return true
        val ceilingMs = remainingMs()
        var waitedMs = 0L
        while (longer() && waitedMs < ceilingMs && remainingMs() > 0L) {
            pause(COLD_POLL_INTERVAL_MS)
            waitedMs += COLD_POLL_INTERVAL_MS
            if (matches(snapshot())) return true
        }
        return false
    }

    companion object {
        const val MAIN_DISPLAY_ID = 0
        const val AREA_HOME = 0
        const val AREA_PRIMARY_FULL = 1
        const val AREA_SECONDARY_FULL = 2
        const val AREA_BALANCED_SPLIT = 3
        const val AREA_FULL_IVI = 4
        const val MAX_TASKS_PER_PANE = 2
        const val TASK_DISCOVERY_ATTEMPTS = 12
        const val TASK_DISCOVERY_INTERVAL_MS = 100L

        /**
         * One look at a cold application per this many milliseconds. An `am stack list` costs
         * 46-50 ms over the product's shell (findings, "What a command costs"), so a look every
         * quarter second keeps the link at a fifth of its load while an application that takes
         * seconds to start is waited for.
         */
        const val COLD_POLL_INTERVAL_MS = 250L

        const val APP_PLACEMENT_CONFIRM_ATTEMPTS = 20
        const val APP_PLACEMENT_CONFIRM_INTERVAL_MS = 100L

        const val ROOT_SETTLE_MS = 120L
        const val EXIT_SETTLE_MS = 650L
        const val AREA_POLL_INTERVAL_MS = 100L
    }
}

internal fun SplitTask.matchesComponent(flattenedComponent: String): Boolean {
    val separator = flattenedComponent.indexOf('/')
    if (separator <= 0 || separator == flattenedComponent.lastIndex) return false
    val expectedPackage = flattenedComponent.substring(0, separator)
    val rawClass = flattenedComponent.substring(separator + 1)
    val expectedClass = if (rawClass.startsWith('.')) expectedPackage + rawClass else rawClass
    val actualClass = activityName?.let { name ->
        if (name.startsWith('.')) packageName + name else name
    }
    return packageName == expectedPackage && actualClass == expectedClass
}

internal fun SplitTask.matchesTopComponent(flattenedComponent: String): Boolean {
    val separator = flattenedComponent.indexOf('/')
    if (separator <= 0 || separator == flattenedComponent.lastIndex) return false
    val expectedPackage = flattenedComponent.substring(0, separator)
    val rawClass = flattenedComponent.substring(separator + 1)
    val expectedClass = if (rawClass.startsWith('.')) expectedPackage + rawClass else rawClass
    return topPackageName == expectedPackage && topActivityName == expectedClass
}

internal fun SplitTask.matchesAnyComponent(components: Set<String>): Boolean =
    components.any { component -> matchesComponent(component) }

internal fun SplitTask.matchesAnyTopComponent(components: Set<String>): Boolean =
    components.any { component -> matchesTopComponent(component) }

internal fun SplitTask.isNativeSplitBootstrap(): Boolean =
    isStockSplitPicker() || isStockSplitBootstrap()

internal fun SplitTask.isStockSplitPicker(): Boolean =
    packageName == STOCK_PICKER_PACKAGE && activityName == STOCK_PICKER_ACTIVITY

internal fun SplitTask.isStockSplitBootstrap(): Boolean =
    packageName == STOCK_BOOTSTRAP_PACKAGE && activityName == STOCK_BOOTSTRAP_ACTIVITY

internal fun SplitTask.matchesOwnTopComponent(): Boolean =
    topPackageName == packageName &&
        topActivityName == activityName

/** Resolves a native root hidden by area=4, where `am stack list` marks every child hidden. */
internal fun SplitRootTask.resolvedCoveredTopTask(): SplitTask? {
    val exact = tasks.filter { task -> task.matchesOwnTopComponent() }
    if (exact.isNotEmpty()) return exact.first()
    return tasks.filter { task -> task.packageName == task.topPackageName }.singleOrNull()
}

/**
 * `am stack list` hides every child when a fullscreen root covers split and repeats only the
 * old root-top component. Exact persisted task id plus package identity is the narrow proof
 * that lets us reveal that owned scene without guessing which hidden child was top.
 */
internal fun SplitRootTask.resolveExpectedCoveredApp(
    expected: SplitPickerExpectedApp?,
): SplitTask? {
    expected ?: return null
    val task = tasks.singleOrNull { candidate -> candidate.id == expected.taskId }
        ?: return null
    return task.takeIf { it.packageName == expected.packageName && it.bounds == bounds }
}

/**
 * Invariant 3: a package alone proves no identity. The product's own components - the permanent
 * pickers and the stock bootstrap - are never "the application found", even when the package being
 * launched is the product's own (U3): matched by package alone, a freshly created picker would be
 * taken for the hub's existing task.
 */
internal fun SplitTask.isOwnSplitComponent(): Boolean =
    isDenzaPickerBase() || isNativeSplitBootstrap()

internal fun SplitTask.isDenzaPickerBase(): Boolean =
    packageName == SPLIT_HOST_PACKAGE && activityName == SPLIT_PICKER_ACTIVITY

internal fun SplitTask.isEmptyRootMarker(): Boolean =
    id == rootId && packageName == "unknown" && activityName == null

internal val SplitPane.fullArea: Int
    get() = when (this) {
        SplitPane.PRIMARY -> AREA_PRIMARY_FULL
        SplitPane.SECONDARY -> AREA_SECONDARY_FULL
    }

private const val STOCK_PICKER_PACKAGE = "com.android.launcher3"
private const val STOCK_PICKER_ACTIVITY = "com.android.launcher3.SplitScreenListActivity"
private const val STOCK_BOOTSTRAP_PACKAGE = "com.byd.sr"
private const val STOCK_BOOTSTRAP_ACTIVITY = "com.byd.sr.MainActivity"
