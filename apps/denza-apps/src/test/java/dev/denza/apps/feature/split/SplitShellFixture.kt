package dev.denza.apps.feature.split

/**
 * The shared firmware fixture: one fake `am`/`service call` surface, one fake gate lease and the
 * task topology every split test speaks in.
 *
 * It lives here rather than inside one test class because the shell recipes and the coordinator
 * that drives them have to be tested against the *same* firmware, not against two hand-made
 * approximations of it (contract 10.2.4).
 */

internal const val ACTIVE_DIVIDER_TOUCH = """
    Input Dispatcher State:
      TouchStatesByDisplay:
        0: down=true, split=true, deviceId=-1, source=0x00001002
          Windows:
            0: name='Embedded{multi-divider-shadow}'
"""
internal const val NO_ACTIVE_TOUCH = """
    Input Dispatcher State:
      TouchStates: <no displays touched>
"""
internal const val NAVIGATOR = "ru.yandex.yandexnavi"
internal const val MUSIC = "ru.yandex.music"
internal const val WAZE = "com.waze"
internal const val PRIMARY_ROOT = 2
internal const val SECONDARY_ROOT = 3
internal const val FULL_ROOT = 4
internal const val EXTERNAL_ROOT = 9
internal const val STOCK_PICKER_PACKAGE = "com.android.launcher3"
internal const val STOCK_PICKER_ACTIVITY = "com.android.launcher3.SplitScreenListActivity"
internal const val LAUNCHER_PACKAGE = "com.android.launcher3"
internal const val LAUNCHER_ACTIVITY = "com.android.launcher3.Launcher"
internal const val STOCK_BOOTSTRAP_PACKAGE = "com.byd.sr"
internal const val PRIMARY_PICKER_ACTIVITY = SPLIT_PICKER_ACTIVITY
internal const val SECONDARY_PICKER_ACTIVITY = PRIMARY_PICKER_ACTIVITY
internal const val PRIMARY_PICKER = "$SPLIT_HOST_PACKAGE/$PRIMARY_PICKER_ACTIVITY"
internal const val SECONDARY_PICKER = "$SPLIT_HOST_PACKAGE/$SECONDARY_PICKER_ACTIVITY"
internal val PICKERS = mapOf(
    SplitPane.PRIMARY to PRIMARY_PICKER,
    SplitPane.SECONDARY to SECONDARY_PICKER,
)
internal val PICKER_COMPONENTS = PICKERS.values.toSet()
internal val FULL = SplitBounds(0, 0, 2560, 1600)
internal val PRIMARY_BOUNDS = SplitBounds(24, 112, 856, 1472)
internal val SECONDARY_BOUNDS = SplitBounds(880, 112, 2536, 1472)

internal class FakeGateLease(
    private var owned: Boolean = false,
) : SplitGateLeaseStore {
    override fun isOwned(): Boolean = owned

    override fun setOwned(owned: Boolean): Boolean {
        this.owned = owned
        return true
    }
}

internal class FakeShell(
    initialGate: Boolean = false,
    private val capabilityAlwaysTrue: Boolean = false,
    private val replaceFullForegroundDuringPickerCleanup: Boolean = false,
    private val hostingSucceeds: Boolean = true,
    private val directTargetLaunchSucceeds: Boolean = true,
    private val redirectOnStartPackage: String? = null,
    private val secondaryBootstrapPackage: String? = null,
    private val tx115RequiresHome: Boolean = false,
    private val nativeBootstrapStartsFullscreen: Boolean = false,
    private val renderEmptyNativeRootMarker: Boolean = false,
    private val transientAreaReadsAfterDirectLaunch: Int = 0,
    private val replaceStaleFullscreenTargetOnLaunch: Boolean = false,
) {
    data class Task(
        val id: Int,
        var packageName: String,
        var activityName: String,
        var rootId: Int,
        var bounds: SplitBounds,
    )

    val commands = mutableListOf<String>()
    var area = 4
    var preserveBoundsOnShellMove = false
    private var gate = initialGate
    private var homeVisible = false
    private var nextTaskId = 100
    private var fullForegroundReplaced = false
    private var disappearOnNextRemove = false
    private var destabilizeAreaOnNextShellMove = false
    private var transientAreaReadsRemaining = 0
    private val supported = mutableSetOf<String>()
    private val tasks = mutableListOf<Task>()

    fun addTask(rootId: Int, id: Int, packageName: String, activityName: String) {
        tasks += Task(id, packageName, activityName, rootId, bounds(rootId))
    }

    fun hasPackage(rootId: Int, packageName: String): Boolean =
        tasks.any { it.rootId == rootId && it.packageName == packageName }

    fun hasTask(taskId: Int): Boolean = tasks.any { it.id == taskId }

    fun hasActivity(rootId: Int, activityName: String): Boolean =
        tasks.any { it.rootId == rootId && it.activityName == activityName }

    fun taskCount(rootId: Int): Int = tasks.count { it.rootId == rootId }

    fun taskBounds(taskId: Int): SplitBounds = tasks.first { it.id == taskId }.bounds

    fun taskRoot(taskId: Int): Int = tasks.first { it.id == taskId }.rootId

    fun moveTask(taskId: Int, rootId: Int) {
        val task = tasks.first { it.id == taskId }
        tasks.remove(task)
        task.rootId = rootId
        task.bounds = bounds(rootId)
        tasks += task
    }

    fun taskBaseActivity(taskId: Int): String = tasks.first { it.id == taskId }.activityName

    fun topActivity(rootId: Int): String? =
        tasks.lastOrNull { it.rootId == rootId }?.activityName

    fun topTaskId(rootId: Int): Int? = tasks.lastOrNull { it.rootId == rootId }?.id

    fun isGateOpen(): Boolean = gate

    fun promoteActivity(rootId: Int, activityName: String) {
        val task = tasks.first {
            it.rootId == rootId && it.activityName == activityName
        }
        tasks.remove(task)
        tasks += task
    }

    fun removeActivity(rootId: Int, activityName: String) {
        tasks.removeAll { it.rootId == rootId && it.activityName == activityName }
    }

    fun dismissPane(rootId: Int) {
        tasks.removeAll { it.rootId == rootId }
        val remainingRoot = when (rootId) {
            PRIMARY_ROOT -> SECONDARY_ROOT
            SECONDARY_ROOT -> PRIMARY_ROOT
            else -> error("Not a split pane: $rootId")
        }
        area = if (remainingRoot == PRIMARY_ROOT) 1 else 2
        tasks.filter { it.rootId == remainingRoot }.forEach { it.bounds = FULL }
    }

    fun disappearOnNextRemove() {
        disappearOnNextRemove = true
    }

    fun destabilizeAreaOnNextShellMove() {
        destabilizeAreaOnNextShellMove = true
    }

    fun shell(command: String): String {
        commands += command
        return when {
            command == "service call activity_task 123" ->
                intParcel(if (capabilityAlwaysTrue || gate) 1 else 0)
            command == "service call activity_task 126 i32 1" -> {
                gate = true
                voidParcel()
            }
            command == "service call activity_task 126 i32 0" -> {
                gate = false
                voidParcel()
            }
            command.startsWith("service call activity_task 114 i32 ") -> {
                area = if (command.endsWith("101")) 1 else 2
                voidParcel()
            }
            command == "service call activity_task 115" -> {
                if (tx115RequiresHome && !homeVisible) {
                    return voidParcel()
                }
                homeVisible = false
                area = 3
                tasks.removeAll {
                    it.packageName == STOCK_PICKER_PACKAGE &&
                        it.activityName == STOCK_PICKER_ACTIVITY
                }
                tasks += Task(
                    id = nextTaskId++,
                    packageName = STOCK_PICKER_PACKAGE,
                    activityName = STOCK_PICKER_ACTIVITY,
                    rootId = PRIMARY_ROOT,
                    bounds = if (nativeBootstrapStartsFullscreen) FULL else bounds(PRIMARY_ROOT),
                )
                tasks += Task(
                    id = nextTaskId++,
                    packageName = secondaryBootstrapPackage ?: STOCK_PICKER_PACKAGE,
                    activityName = secondaryBootstrapPackage?.let { "$it.MainActivity" }
                        ?: STOCK_PICKER_ACTIVITY,
                    rootId = SECONDARY_ROOT,
                    bounds = if (nativeBootstrapStartsFullscreen) FULL else bounds(SECONDARY_ROOT),
                )
                voidParcel()
            }
            command == "service call activity_task 118 i32 1" -> intParcel(PRIMARY_ROOT)
            command == "service call activity_task 118 i32 2" -> intParcel(SECONDARY_ROOT)
            command == "service call activity_task 118 i32 4" -> intParcel(FULL_ROOT)
            command == "service call activity_task 30" -> {
                if (transientAreaReadsRemaining > 0) {
                    transientAreaReadsRemaining -= 1
                    intParcel(2)
                } else {
                    intParcel(area)
                }
            }
            command.startsWith("service call activity_task 112 s16 ") ->
                intParcel(if (quotedArgument(command) in supported) 1 else 0)
            command.startsWith("service call activity_task 125 s16 ") -> {
                supported += quotedArgument(command)
                voidParcel()
            }
            command.startsWith("am start ") -> {
                val component = command.substringAfter("-n '").substringBefore("'")
                val packageName = component.substringBefore('/')
                val rawActivity = component.substringAfter('/')
                val activityName = if (rawActivity.startsWith('.')) {
                    packageName + rawActivity
                } else {
                    rawActivity
                }
                if (!hostingSucceeds && component in PICKERS.values) {
                    return "Error: picker launch rejected"
                }
                if (
                    !directTargetLaunchSucceeds &&
                    component !in PICKERS.values
                ) {
                    return "Error: direct target launch rejected"
                }
                val pickerRoot = when {
                    command.contains("byd.intent.category.START_IVI_PRIMARY") -> PRIMARY_ROOT
                    command.contains("byd.intent.category.START_IVI_SECOND") -> SECONDARY_ROOT
                    else -> null
                }
                if (pickerRoot != null) {
                    if (area != fullArea(pickerRoot)) area = 3
                    tasks.removeAll {
                        it.rootId == pickerRoot &&
                            (it.packageName == STOCK_PICKER_PACKAGE ||
                                it.packageName == STOCK_BOOTSTRAP_PACKAGE)
                    }
                } else {
                    tasks.removeAll { it.activityName == activityName }
                }
                if (
                    replaceStaleFullscreenTargetOnLaunch &&
                    component !in PICKERS.values
                ) {
                    tasks.removeAll { task ->
                        task.rootId == FULL_ROOT &&
                            task.packageName == packageName
                    }
                }
                val launchedTask = Task(
                    id = nextTaskId++,
                    packageName = packageName,
                    activityName = activityName,
                    rootId = pickerRoot ?: FULL_ROOT,
                    bounds = pickerRoot?.let(::bounds) ?: FULL,
                )
                tasks += launchedTask
                if (component !in PICKERS.values) {
                    transientAreaReadsRemaining = transientAreaReadsAfterDirectLaunch
                }
                if (packageName == redirectOnStartPackage) {
                    tasks.remove(launchedTask)
                    tasks += Task(
                        id = nextTaskId++,
                        packageName = packageName,
                        activityName = "$packageName.MainActivity",
                        rootId = launchedTask.rootId,
                        bounds = launchedTask.bounds,
                    )
                }
                "Starting: Intent"
            }
            command.startsWith("am stack move-task ") -> {
                val parts = command.split(' ')
                val taskId = parts[3].toInt()
                val rootId = parts[4].toInt()
                val toTop = parts[5].toBoolean()
                val task = tasks.first { it.id == taskId }
                val changedRoot = task.rootId != rootId
                if (changedRoot) {
                    tasks.remove(task)
                    task.rootId = rootId
                    if (!preserveBoundsOnShellMove) task.bounds = bounds(rootId)
                    if (toTop) {
                        tasks += task
                    } else {
                        val firstInRoot = tasks.indexOfFirst { it.rootId == rootId }
                        if (firstInRoot >= 0) tasks.add(firstInRoot, task) else tasks += task
                    }
                }
                if (destabilizeAreaOnNextShellMove) {
                    destabilizeAreaOnNextShellMove = false
                    area = 3
                }
                if (rootId == FULL_ROOT) area = 4
                ""
            }
            command.startsWith("am task focus ") -> {
                val taskId = command.substringAfter("am task focus ").toInt()
                val task = tasks.first { it.id == taskId }
                tasks.remove(task)
                tasks += task
                if (task.rootId == PRIMARY_ROOT || task.rootId == SECONDARY_ROOT) {
                    if (area != fullArea(task.rootId)) area = 3
                }
                ""
            }
            command.contains("SplitTaskProxyMain remove-task ") -> {
                val taskId = command.substringAfter("remove-task ").substringBefore(' ').toInt()
                if (disappearOnNextRemove) {
                    disappearOnNextRemove = false
                    tasks.removeAll { it.id == taskId }
                    return "DENZA_SPLIT_RESULT:false"
                }
                val removedTask = tasks.firstOrNull { it.id == taskId }
                val removed = tasks.removeAll { it.id == taskId }
                if (
                    replaceFullForegroundDuringPickerCleanup &&
                    !fullForegroundReplaced &&
                    removedTask?.packageName == STOCK_PICKER_PACKAGE
                ) {
                    tasks.removeAll { it.rootId == FULL_ROOT }
                    tasks += Task(
                        id = nextTaskId++,
                        packageName = LAUNCHER_PACKAGE,
                        activityName = LAUNCHER_ACTIVITY,
                        rootId = FULL_ROOT,
                        bounds = FULL,
                    )
                    area = 4
                    fullForegroundReplaced = true
                }
                "DENZA_SPLIT_RESULT:$removed"
            }
            command.startsWith("am task resize ") -> {
                val parts = command.split(' ')
                tasks.first { it.id == parts[3].toInt() }.bounds = SplitBounds(
                    parts[4].toInt(),
                    parts[5].toInt(),
                    parts[6].toInt(),
                    parts[7].toInt(),
                )
                ""
            }
            command == "am stack list" -> renderStack()
            command == "dumpsys input" ->
                "name='Embedded{multi-divider-shadow}', frame=[-67,0][108,1600]"
            command.startsWith("input swipe ") -> {
                area = 3
                ""
            }
            command == "input keyevent KEYCODE_HOME" -> {
                area = 0
                homeVisible = true
                ""
            }
            else -> error("Unexpected command: $command")
        }
    }

    private fun renderStack(): String = buildString {
        listOf(FULL_ROOT, PRIMARY_ROOT, SECONDARY_ROOT, EXTERNAL_ROOT).forEach { rootId ->
            val rootTasks = tasks.filter { it.rootId == rootId }
            val rootBounds = bounds(rootId)
            appendLine(
                "RootTask id=$rootId bounds=[${rootBounds.left},${rootBounds.top}]" +
                    "[${rootBounds.right},${rootBounds.bottom}] " +
                    "displayId=${if (rootId == EXTERNAL_ROOT) 2 else 0} userId=0",
            )
            if (
                renderEmptyNativeRootMarker &&
                rootTasks.isEmpty() &&
                rootId in setOf(PRIMARY_ROOT, SECONDARY_ROOT)
            ) {
                appendLine(
                    "  taskId=$rootId: unknown bounds=[${rootBounds.left},${rootBounds.top}]" +
                        "[${rootBounds.right},${rootBounds.bottom}] userId=0 visible=false",
                )
            }
            val top = rootTasks.lastOrNull()
            val topPackage = top?.packageName
            val topActivity = top?.activityName
            rootTasks.forEach { task ->
                append("  taskId=${task.id}: ${task.packageName}/${task.activityName} ")
                append(
                    "bounds=[${task.bounds.left},${task.bounds.top}]" +
                        "[${task.bounds.right},${task.bounds.bottom}] userId=0 ",
                )
                append("visible=${task.id == top?.id}")
                if (topPackage != null && topActivity != null) {
                    append(
                        " topActivity=ComponentInfo{$topPackage/$topActivity}",
                    )
                }
                appendLine()
            }
        }
    }

    private fun bounds(rootId: Int): SplitBounds = when (rootId) {
        PRIMARY_ROOT -> if (area == 1) FULL else PRIMARY_BOUNDS
        SECONDARY_ROOT -> if (area == 2) FULL else SECONDARY_BOUNDS
        else -> FULL
    }

    private fun fullArea(rootId: Int): Int = when (rootId) {
        PRIMARY_ROOT -> 1
        SECONDARY_ROOT -> 2
        else -> -1
    }

    private fun quotedArgument(command: String): String =
        command.substringAfter("s16 '").substringBeforeLast("'")

    private fun intParcel(value: Int): String =
        "Result: Parcel(00000000 ${"%08x".format(value)} '........')"

    private fun voidParcel(): String = "Result: Parcel(00000000 '....')"
}
