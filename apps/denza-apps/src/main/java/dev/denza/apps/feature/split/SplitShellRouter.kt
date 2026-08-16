package dev.denza.apps.feature.split

internal class SplitShellRouter(
    private val shell: (String) -> String,
    private val apkPath: String,
    private val eligibleApps: () -> Map<String, String>,
    private val pause: (Long) -> Unit = Thread::sleep,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onEvent: (String) -> Unit = {},
    private val stateStore: SplitRoutingStateStore? = null,
) {
    private var memory = stateStore?.load() ?: SplitRoutingMemory()
    private var firstObservation = true
    private var firstNativeRootId: Int? = null
    private var secondNativeRootId: Int? = null
    private var gateOwnedByUs = false
    private var mutationTargetKey: String? = null
    private var mutationSceneKey: String? = null
    private var mutationAttempts = 0
    private var lastMutationAtMs = Long.MIN_VALUE
    private var abandonedSceneKey: String? = null
    private var lastNonTargetMutationKey: String? = null
    private val supportedPackages = mutableSetOf<String>()

    fun tick(): Boolean {
        val apps = eligibleApps()
        if (apps.isEmpty()) return false

        val firstRoot = firstNativeRootId ?: nativeRootId(1).also {
            firstNativeRootId = it
        }
        val secondRoot = secondNativeRootId ?: nativeRootId(2).also {
            secondNativeRootId = it
        }
        check(firstRoot != secondRoot) { "Прошивка вернула один split-контейнер дважды" }

        val area = callInt("service call activity_task 30")
        val stackText = shell("am stack list").also(::validateOutput)
        val observation = SplitRoutingObservation(
            area = area,
            firstNativeRootId = firstRoot,
            secondNativeRootId = secondRoot,
            snapshot = SplitTaskSnapshot.parse(stackText),
            eligiblePackages = apps.keys,
            recovering = firstObservation,
        )
        firstObservation = false
        val sceneKey = semanticSceneKey(observation)

        SplitRoutingReducer.recoverOrphanPlaceholder(memory, observation)?.let { recovery ->
            updateMemory(recovery.memory)
            recovery.event?.let(onEvent)
            execute(recovery.actions)
            abandonedSceneKey = null
            lastNonTargetMutationKey = null
            resetMutationAttempts()
            return false
        }

        if (abandonedSceneKey == sceneKey) {
            return area == AREA_BALANCED_SPLIT
        }
        abandonedSceneKey = null

        val previousMemory = memory
        val decision = SplitRoutingReducer.reduce(previousMemory, observation)
        val memoryChanged = decision.memory != previousMemory
        updateMemory(decision.memory)

        if (decision.actions.isEmpty()) {
            if (memoryChanged) decision.event?.let(onEvent)
            if (decision.memory.target == null) resetMutationAttempts()
            return decision.splitVisible
        }

        val target = decision.memory.target
        if (target != null) {
            lastNonTargetMutationKey = null
            val targetKey = semanticTargetKey(target)
            if (targetKey != mutationTargetKey || sceneKey != mutationSceneKey) {
                mutationTargetKey = targetKey
                mutationSceneKey = sceneKey
                mutationAttempts = 0
                lastMutationAtMs = Long.MIN_VALUE
            }
            if (mutationAttempts >= MAX_ATTEMPTS_WITHOUT_PROGRESS) {
                val fallbackMemory = memory.stablePair
                    ?.let { SplitRoutingMemory(stablePair = it) }
                    ?: SplitRoutingReducer.baseline(observation)
                updateMemory(fallbackMemory)
                abandonedSceneKey = sceneKey
                resetMutationAttempts()
                if (area != AREA_BALANCED_SPLIT) closeOwnedGate()
                onEvent("routing target abandoned after bounded retries")
                return false
            }
            val now = nowMs()
            if (
                mutationAttempts > 0 &&
                now - lastMutationAtMs < MUTATION_RETRY_BACKOFF_MS
            ) {
                return decision.splitVisible
            }
            mutationAttempts += 1
            lastMutationAtMs = now
        } else {
            resetMutationAttempts()
            val mutationKey = "$sceneKey|${decision.actions}"
            if (mutationKey == lastNonTargetMutationKey) return decision.splitVisible
            lastNonTargetMutationKey = mutationKey
        }

        decision.event?.let(onEvent)
        target?.let(::prepareTarget)
        execute(decision.actions)
        return decision.splitVisible
    }

    /**
     * Navigation and Simulcast own their explicit task moves. Pause mutation
     * attempts without discarding the semantic target: after the quiet period
     * the reducer reconciles that target against the actual new scene. Fresh
     * foreground, Home, and missing members then cancel or retarget it using
     * the same fail-closed rules as an ordinary observation.
     */
    fun pauseForExternalTaskMoves() {
        abandonedSceneKey = null
        lastNonTargetMutationKey = null
        resetMutationAttempts()
    }

    /** Stops automation without changing the user's current native layout. */
    fun disable() {
        memory = SplitRoutingMemory()
        stateStore?.clear()
        abandonedSceneKey = null
        lastNonTargetMutationKey = null
        resetMutationAttempts()
        closeOwnedGate()
    }

    /** Releases the process-owned gate while retaining an unfinished target for recovery. */
    fun closeForRestart() {
        resetMutationAttempts()
        closeOwnedGate()
    }

    private fun resetMutationAttempts() {
        mutationTargetKey = null
        mutationSceneKey = null
        mutationAttempts = 0
        lastMutationAtMs = Long.MIN_VALUE
    }

    private fun semanticTargetKey(target: SplitPairTarget): String = target.panes()
        .joinToString("|") { pane ->
            "${pane.id}:${pane.packageName}:${pane.activityName}:${pane.preferredRootId}"
        }

    private fun semanticSceneKey(observation: SplitRoutingObservation): String = buildString {
        append(observation.area)
        observation.snapshot.roots
            .filter { it.displayId == 0 }
            .sortedBy(SplitRootTask::id)
            .forEach { root ->
                append("|r:")
                append(root.id)
                append(':')
                append(root.bounds)
                append(':')
                append(root.activityType)
                root.tasks.sortedBy(SplitTask::id).forEach { task ->
                    append("|t:")
                    append(task.id)
                    append(':')
                    append(task.packageName)
                    append(':')
                    append(task.activityName)
                    append(':')
                    append(task.rootId)
                    append(':')
                    append(task.bounds)
                    append(':')
                    append(task.topPackageName)
                    append(':')
                    append(task.topActivityName)
                }
            }
    }

    private fun updateMemory(next: SplitRoutingMemory) {
        if (next == memory) return
        memory = next
        stateStore?.save(next)
    }

    private fun prepareTarget(target: SplitPairTarget) {
        ensureGateOpen()
        target.panes().forEach { ensureSupported(it.packageName) }
    }

    private fun execute(actions: List<SplitRoutingAction>) {
        var moved = false
        for (action in actions) {
            when (action) {
                SplitRoutingAction.LaunchPlaceholder -> {
                    run("am start -n $PLACEHOLDER_COMPONENT -f $PLACEHOLDER_LAUNCH_FLAGS")
                    pause(PLACEHOLDER_LAUNCH_MS)
                }

                is SplitRoutingAction.RemoveTask -> removeTask(action)

                is SplitRoutingAction.PlaceTask -> {
                    if (action.promoteInPlace) {
                        focusTask(action.taskId)
                    } else {
                        run("am stack move-task ${action.taskId} ${action.rootId} true")
                        moved = true
                    }
                }

                SplitRoutingAction.BalanceDivider -> {
                    dragDividerToBalanced()
                    pause(LAYOUT_SETTLE_MS)
                }

                is SplitRoutingAction.ResizeTask -> resizeTask(action.taskId, action.bounds)
                SplitRoutingAction.CloseOwnedGate -> closeOwnedGate()
            }
        }
        if (moved) pause(ROOT_SETTLE_MS)
    }

    private fun nativeRootId(areaId: Int): Int {
        val rootId = callInt("service call activity_task 118 i32 $areaId")
        check(rootId > 0) { "Прошивка не вернула split-контейнер $areaId" }
        return rootId
    }

    private fun ensureGateOpen() {
        if (callBoolean("service call activity_task 123")) return
        callVoid("service call activity_task 126 i32 1")
        check(callBoolean("service call activity_task 123")) {
            "Прошивка не разрешила открыть split"
        }
        gateOwnedByUs = true
    }

    private fun ensureSupported(packageName: String) {
        if (packageName in supportedPackages) return
        val quoted = shellQuote(packageName)
        if (!callBoolean("service call activity_task 112 s16 $quoted")) {
            callVoid("service call activity_task 125 s16 $quoted")
            check(callBoolean("service call activity_task 112 s16 $quoted")) {
                "Прошивка не добавила $packageName в split"
            }
        }
        supportedPackages += packageName
    }

    private fun closeOwnedGate() {
        if (!gateOwnedByUs) return
        callVoid("service call activity_task 126 i32 0")
        gateOwnedByUs = false
    }

    private fun resizeTask(taskId: Int, bounds: SplitBounds) {
        check(taskId > 0 && bounds.hasArea())
        run(
            "am task resize $taskId ${bounds.left} ${bounds.top} " +
                "${bounds.right} ${bounds.bottom}",
        )
    }

    private fun removeTask(action: SplitRoutingAction.RemoveTask) {
        check(action.taskId > 0)
        val output = shell(
            "CLASSPATH=${shellQuote(apkPath)} app_process /system/bin " +
                "--nice-name=denza_split_cmd $SPLIT_PROXY_CLASS remove-task ${action.taskId} " +
                "${shellQuote(action.packageName)} ${shellQuote(action.activityName)} " +
                "${shellQuote(action.topPackageName ?: "-")} " +
                "${shellQuote(action.topActivityName ?: "-")}",
        ).also(::validateOutput)
        val result = output.lineSequence()
            .map(String::trim)
            .lastOrNull { it.startsWith(SPLIT_PROXY_RESULT_PREFIX) }
            ?.removePrefix(SPLIT_PROXY_RESULT_PREFIX)
        check(result == "true") { "Не удалось безопасно удалить задачу ${action.taskId}" }
        pause(ROOT_SETTLE_MS)
    }

    private fun focusTask(taskId: Int) {
        check(taskId > 0)
        val output = shell(
            "CLASSPATH=${shellQuote(apkPath)} app_process /system/bin " +
                "--nice-name=denza_split_cmd $SPLIT_PROXY_CLASS focus-task $taskId",
        ).also(::validateOutput)
        val result = output.lineSequence()
            .map(String::trim)
            .lastOrNull { it.startsWith(SPLIT_PROXY_RESULT_PREFIX) }
            ?.removePrefix(SPLIT_PROXY_RESULT_PREFIX)
        check(result == "true") { "Прошивка не подняла задачу $taskId внутри split" }
    }

    private fun dragDividerToBalanced() {
        val inputState = shell("dumpsys input").also(::validateOutput)
        val dividerLine = inputState.lineSequence()
            .firstOrNull { line ->
                line.contains("multi-divider-shadow") && line.contains("frame=[")
            }
            ?: error("Нативный drag control не появился")
        val divider = DIVIDER_FRAME_PATTERN.find(dividerLine)
            ?: error("Нативный drag control не появился")
        val left = divider.groupValues[1].toInt()
        val right = divider.groupValues[2].toInt()
        val startX = ((left + right) / 2).coerceIn(EDGE_INSET, DISPLAY_WIDTH - EDGE_INSET)
        val endX = if (startX < DISPLAY_WIDTH / 2) LEFT_DIVIDER_X else RIGHT_DIVIDER_X
        run("input swipe $startX $DIVIDER_Y $endX $DIVIDER_Y $DIVIDER_DRAG_MS")
    }

    private fun callBoolean(command: String): Boolean = callInt(command) != 0

    private fun callInt(command: String): Int {
        val output = shell(command).also(::validateOutput)
        val parcel = PARCEL_PATTERN.find(output)?.groupValues?.get(1)
            ?: error("Некорректный ответ activity_task")
        val words = WORD_PATTERN.findAll(parcel).map { it.value }.toList()
        check(words.size >= 2 && words[0].toLong(16) == 0L) {
            "Ошибка activity_task: ${output.trim()}"
        }
        return words[1].toLong(16).toInt()
    }

    private fun callVoid(command: String) {
        val output = shell(command).also(::validateOutput)
        val parcel = PARCEL_PATTERN.find(output)?.groupValues?.get(1)
            ?: error("Некорректный ответ activity_task")
        val words = WORD_PATTERN.findAll(parcel).map { it.value }.toList()
        check(words.isNotEmpty() && words[0].toLong(16) == 0L) {
            "Ошибка activity_task: ${output.trim()}"
        }
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

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private companion object {
        const val AREA_BALANCED_SPLIT = 3
        const val MAX_ATTEMPTS_WITHOUT_PROGRESS = 3
        const val MUTATION_RETRY_BACKOFF_MS = 1_000L
        const val ROOT_SETTLE_MS = 100L
        const val LAYOUT_SETTLE_MS = 100L
        const val PLACEHOLDER_LAUNCH_MS = 100L
        const val DISPLAY_WIDTH = 2_560
        const val EDGE_INSET = 50
        const val LEFT_DIVIDER_X = 856
        const val RIGHT_DIVIDER_X = 1_704
        const val DIVIDER_Y = 800
        const val DIVIDER_DRAG_MS = 400
        const val PLACEHOLDER_COMPONENT =
            "dev.denza.apps/dev.denza.apps.feature.split.SplitPlaceholderActivity"
        const val PLACEHOLDER_LAUNCH_FLAGS = "0x18010000"
        const val SPLIT_PROXY_CLASS =
            "dev.denza.apps.feature.split.SplitTaskProxyMain"
        const val SPLIT_PROXY_RESULT_PREFIX = "DENZA_SPLIT_RESULT:"
        val DIVIDER_FRAME_PATTERN = Regex(
            "frame=\\[(-?[0-9]+),-?[0-9]+]\\[(-?[0-9]+),-?[0-9]+]",
        )
        val PARCEL_PATTERN = Regex("Parcel\\(([^']+)")
        val WORD_PATTERN = Regex("[0-9a-fA-F]{8}")
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
    fun foregroundTask(): SplitTask? = roots.asSequence()
        .filter { it.displayId == 0 && it.activityType != HOME_ACTIVITY_TYPE }
        .mapNotNull(SplitRootTask::resolvedTopTask)
        .firstOrNull()

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

        private const val HOME_ACTIVITY_TYPE = "home"
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
    val packageCandidates = tasks.filter { task ->
        task.visible && task.packageName == task.topPackageName
    }
    val exactCandidates = packageCandidates.filter { task ->
        task.topActivityName != null && task.activityName == task.topActivityName
    }
    return exactCandidates.firstOrNull() ?: packageCandidates.singleOrNull()
}
