package dev.denza.apps.feature.split

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The shared firmware fixture: one fake `am`/`service call` surface, one fake gate lease and the
 * task topology every split test speaks in - plus the fake car the coordinator scenarios drive.
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

/**
 * Where `removeIviStack` strands a task it threw out of the panel roots: still on the main
 * display, outside every root the product owns, with its panel bounds preserved (ground-v18).
 */
internal const val DETACHED_ROOT = 7
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
/** `Intent.FLAG_ACTIVITY_MULTIPLE_TASK`: the one bit that decides new task or existing one. */
internal const val FLAG_ACTIVITY_MULTIPLE_TASK = 0x08000000L

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
    firstTaskId: Int = 100,
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

    /**
     * What the firmware did without a command from us.
     *
     * A shared topology read ([SplitTopologyCache]) may not survive one: out of band is exactly the
     * case a session cannot deduce from its own traffic, and in the car it is only ever seen by the
     * next read after a command or a settle pause. Registering here is how a test says "and now the
     * car changed underneath you".
     */
    val carChanged = mutableListOf<() -> Unit>()

    var area = 4
    var preserveBoundsOnShellMove = false
    private var gate = initialGate
    private var homeVisible = false
    private var nextTaskId = firstTaskId
    private var fullForegroundReplaced = false
    private var disappearOnNextRemove = false
    private var destabilizeAreaOnNextShellMove = false
    private var transientAreaReadsRemaining = 0
    private val supported = mutableSetOf<String>()
    private val tasks = mutableListOf<Task>()
    private val globals = mutableMapOf<String, String>()
    private val secure = mutableMapOf<String, String>()
    private val system = mutableMapOf<String, String>()

    fun addTask(rootId: Int, id: Int, packageName: String, activityName: String) {
        tasks += Task(id, packageName, activityName, rootId, bounds(rootId))
        changed()
    }

    /** Seeds a firmware-global setting a lease will displace, without recording a command. */
    fun setGlobal(key: String, value: String) {
        globals[key] = value
    }

    /** Seeds or reads what SmartMulti remembers; the firmware is its only writer in the car. */
    fun setSystem(key: String, value: String) {
        system[key] = value
    }

    fun system(key: String): String? = system[key]

    /** What the firmware holds for a global setting a lease borrowed (contract, to 1.12). */
    fun globalValue(key: String): String? = globals[key]

    fun hasPackage(rootId: Int, packageName: String): Boolean =
        tasks.any { it.rootId == rootId && it.packageName == packageName }

    fun hasTask(taskId: Int): Boolean = tasks.any { it.id == taskId }

    fun hasActivity(rootId: Int, activityName: String): Boolean =
        tasks.any { it.rootId == rootId && it.activityName == activityName }

    fun taskCount(rootId: Int): Int = tasks.count { it.rootId == rootId }

    /** Every task of a root, oldest first - the order the firmware itself reports them in. */
    fun taskIds(rootId: Int): List<Int> = tasks.filter { it.rootId == rootId }.map(Task::id)

    fun taskBounds(taskId: Int): SplitBounds = tasks.first { it.id == taskId }.bounds

    fun taskRoot(taskId: Int): Int = tasks.first { it.id == taskId }.rootId

    fun moveTask(taskId: Int, rootId: Int) {
        val task = tasks.first { it.id == taskId }
        tasks.remove(task)
        task.rootId = rootId
        task.bounds = bounds(rootId)
        tasks += task
        changed()
    }

    /**
     * The firmware threw this task out of the panel roots (`removeIviStack`, ground-v18): it
     * stays alive on the main display, invisible, with its panel bounds preserved.
     */
    fun detachTask(taskId: Int) {
        val task = tasks.first { it.id == taskId }
        tasks.remove(task)
        task.rootId = DETACHED_ROOT
        tasks += task
        changed()
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
        changed()
    }

    fun removeActivity(rootId: Int, activityName: String) {
        tasks.removeAll { it.rootId == rootId && it.activityName == activityName }
        changed()
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
        changed()
    }

    fun disappearOnNextRemove() {
        disappearOnNextRemove = true
    }

    fun destabilizeAreaOnNextShellMove() {
        destabilizeAreaOnNextShellMove = true
    }

    private fun changed() = carChanged.forEach { listener -> listener() }

    private fun removeExactTask(taskId: Int): Boolean {
        if (disappearOnNextRemove) {
            disappearOnNextRemove = false
            tasks.removeAll { it.id == taskId }
            return false
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
        return removed
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
                // The pane categories route the product's own picker and nothing else: this
                // firmware ignores them for third-party components, which is exactly why every
                // recipe follows a launch with `promoteTask` (split-screen-findings, live).
                val pickerRoot = when {
                    component !in PICKERS.values -> null
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
                val destination = pickerRoot ?: FULL_ROOT
                // What the launch flags mean to the firmware, and the whole of the difference:
                // without `FLAG_ACTIVITY_MULTIPLE_TASK` a package that already has a task keeps it
                // - it is brought to the destination, splash and all - and with the flag a second,
                // independent task is created next to it (1.5.2).
                val flags = command.substringAfter("-f 0x", "")
                    .trim()
                    .toLongOrNull(radix = 16)
                    ?: 0L
                val reused = if (flags and FLAG_ACTIVITY_MULTIPLE_TASK != 0L) {
                    null
                } else {
                    tasks.lastOrNull { task ->
                        task.packageName == packageName && task.rootId != EXTERNAL_ROOT
                    }
                }
                val launchedTask = if (reused != null) {
                    tasks.remove(reused)
                    reused.activityName = activityName
                    reused.rootId = destination
                    reused.bounds = bounds(destination)
                    reused
                } else {
                    Task(
                        id = nextTaskId++,
                        packageName = packageName,
                        activityName = activityName,
                        rootId = destination,
                        bounds = bounds(destination),
                    )
                }
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
                // One invocation, any number of tasks, one answer line each - the proxy's own
                // contract, so a batched recipe learns exactly which removals happened.
                command.substringAfter("remove-task ")
                    .split(' ')
                    .chunked(5)
                    .mapNotNull { group -> group.firstOrNull()?.toIntOrNull() }
                    .joinToString("\n") { taskId ->
                        "DENZA_SPLIT_RESULT:$taskId=${removeExactTask(taskId)}"
                    }
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
            // The firmware-global settings a lease borrows and gives back (contract, to 1.12).
            // The resizeability lease reads, writes and verifies in one compound statement, so the
            // fake answers a chain the same way the shell does: one line per `get`, nothing else.
            command.startsWith("settings get global ") ||
                command.startsWith("settings put global ") ||
                command.startsWith("settings delete global ") ->
                command.split(';').mapNotNull { raw ->
                    val statement = raw.trim()
                    when {
                        statement.startsWith("settings get global ") ->
                            globals[statement.removePrefix("settings get global ")] ?: "null"
                        statement.startsWith("settings put global ") -> {
                            val parts = statement.removePrefix("settings put global ")
                                .split(' ', limit = 2)
                            globals[parts[0]] = parts.getOrElse(1) { "" }
                            null
                        }
                        statement.startsWith("settings delete global ") -> {
                            globals.remove(statement.removePrefix("settings delete global "))
                            null
                        }
                        else -> error("Unexpected statement: $statement")
                    }
                }.joinToString("\n")
            // Secure settings: where the accessibility observer of the picker-access lease lives.
            // The real controller writes both of its keys as one compound shell statement.
            command.startsWith("settings get secure ") ->
                secure[command.removePrefix("settings get secure ")] ?: "null"
            command.startsWith("settings put secure ") -> {
                command.split(';').forEach { statement ->
                    val parts = statement.trim()
                        .removePrefix("settings put secure ")
                        .split(' ', limit = 2)
                    secure[parts[0]] = parts.getOrElse(1) { "" }.trim().trim('\'')
                }
                ""
            }
            // System settings: the pair SmartMulti remembers (contract 1.12). The firmware writes
            // it itself, so the fake does too - a scene of ours records the packages of its panes.
            // The lease reads all four keys with one compound statement.
            command.startsWith("settings get system ") ->
                command.split(';').joinToString("\n") { statement ->
                    system[statement.trim().removePrefix("settings get system ")] ?: "null"
                }
            command.startsWith("settings put system ") -> {
                val parts = command.removePrefix("settings put system ").split(' ', limit = 2)
                system[parts[0]] = parts.getOrElse(1) { "" }.trim().trim('\'')
                ""
            }
            // The one channel this firmware is proven to accept for our tag (U5).
            command.startsWith("log -t ") -> ""
            command.startsWith("settings delete system ") -> {
                system.remove(command.removePrefix("settings delete system "))
                ""
            }
            else -> error("Unexpected command: $command")
        }
    }

    private fun renderStack(): String = buildString {
        listOf(FULL_ROOT, PRIMARY_ROOT, SECONDARY_ROOT, DETACHED_ROOT, EXTERNAL_ROOT).forEach { rootId ->
            val rootTasks = tasks.filter { it.rootId == rootId }
            if (rootId == DETACHED_ROOT && rootTasks.isEmpty()) return@forEach
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
            // A task the firmware detached is alive but never visible (ground-v18: the stranded
            // narrow picker reports `visible=false` with its panel bounds kept).
            val top = rootTasks.lastOrNull().takeUnless { rootId == DETACHED_ROOT }
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

// region the fake car: the coordinator harness of appendix B.3

internal const val SPLIT_AWAIT_MS = 5_000L
internal const val SPLIT_APK_PATH = "/data/app/dev.denza.apps/base.apk"
internal const val SPLIT_AREA_QUERY = "service call activity_task 30"
internal const val SPLIT_ADB_DROPPED = "adb link dropped"

internal const val PRIMARY_PICKER_TASK = 60
internal const val SECONDARY_PICKER_TASK = 61
internal const val PRIMARY_APP_TASK = 70
internal const val SECONDARY_APP_TASK = 71

/** The navigator recreates its own task on the way back (live run 2026-08-19). */
internal const val RETURNED_NAV_TASK = 90
internal const val RADIO = "ru.radio.player"

internal val PICKER_PAIR = mapOf(
    SplitPane.PRIMARY to SplitSlot.Picker,
    SplitPane.SECONDARY to SplitSlot.Picker,
)
internal val APP_PAIR = mapOf(
    SplitPane.PRIMARY to SplitSlot.App(NAVIGATOR),
    SplitPane.SECONDARY to SplitSlot.App(MUSIC),
)

/** The two permanent picker bases a build settled, by pane. */
internal fun SplitSceneBuild.hostIds(): Map<SplitPane, Int> =
    panes.mapValues { (_, observed) -> observed.hostTaskId }

/** The whole scene with nothing above the pickers - what an open with no saved pair asks for. */
internal fun SplitPickerShellSession.buildPickers(): Map<SplitPane, Int> =
    buildScene(PICKERS, emptyMap()).hostIds()

/** A restore of one remembered package into one pane, the way an open asks for it. */
internal fun launchTargetOf(packageName: String, launchMode: Int = 0): SplitLaunchTarget =
    SplitLaunchTarget(packageName, "$packageName/$packageName.MainActivity", launchMode)

/** A stock split the user built themselves: nothing here belongs to the product. */
internal fun FakeShell.stockSplitOfSomeoneElse() {
    area = 3
    addTask(PRIMARY_ROOT, 40, STOCK_PICKER_PACKAGE, STOCK_PICKER_ACTIVITY)
    addTask(SECONDARY_ROOT, 41, MUSIC, "$MUSIC.MainActivity")
}

/** A live product scene: one permanent picker base per pane, optionally with its app. */
internal fun FakeShell.liveProductScene(withApps: Boolean = false) {
    area = 3
    addTask(PRIMARY_ROOT, PRIMARY_PICKER_TASK, SPLIT_HOST_PACKAGE, PRIMARY_PICKER_ACTIVITY)
    if (withApps) addTask(PRIMARY_ROOT, PRIMARY_APP_TASK, NAVIGATOR, "$NAVIGATOR.MainActivity")
    addTask(SECONDARY_ROOT, SECONDARY_PICKER_TASK, SPLIT_HOST_PACKAGE, SECONDARY_PICKER_ACTIVITY)
    if (withApps) addTask(SECONDARY_ROOT, SECONDARY_APP_TASK, MUSIC, "$MUSIC.MainActivity")
}

/**
 * What the car still shows after Denza Apps was killed: our two permanent picker bases, one of
 * them bare and reporting itself, the other still carrying its app.
 */
internal fun FakeShell.sceneLeftByADeadProcess() {
    area = 3
    addTask(PRIMARY_ROOT, PRIMARY_PICKER_TASK, SPLIT_HOST_PACKAGE, PRIMARY_PICKER_ACTIVITY)
    addTask(SECONDARY_ROOT, SECONDARY_PICKER_TASK, SPLIT_HOST_PACKAGE, SECONDARY_PICKER_ACTIVITY)
    addTask(SECONDARY_ROOT, SECONDARY_APP_TASK, MUSIC, "$MUSIC.MainActivity")
}

/**
 * One fake car: the firmware fixture above plus every seam [SplitCoordinatorCore] is built from.
 *
 * The oracle every scenario reads is what actually reached this car - the ordered command journal,
 * the per-operation shell sessions, the atomic store, the overlay lease counter - never a
 * restatement of the code under test (contract 10.3.2).
 */
internal class SplitCarFixture(
    val fake: FakeShell,
    val clock: FakeSplitClock = FakeSplitClock(),
    val store: CountingStore = CountingStore(),
) {
    val actor = SplitActor(clock)
    val shells = RecordingShellFactory(fake)
    val overlay = CountingOverlay()
    val gateLease = FakeGateLease()
    val notices: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val diagnostics: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Lines no proven channel has carried yet - the production sink's second half (U5). */
    private val unmirrored: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private var built: SplitCoordinatorCore? = null

    fun core(
        initial: SplitDurable,
        leases: List<SplitLeaseController> = emptyList(),
        appLabel: (String) -> String = { it },
        /**
         * Runs on the worker as each diagnostic line is recorded.
         *
         * It is the seam for "and right here the car changed underneath you": the step marks of an
         * operation are the only points inside one that a scenario can name from outside it.
         */
        onDiagnostic: (String) -> Unit = {},
    ): SplitCoordinatorCore {
        store.seed(initial)
        return SplitCoordinatorCore(
            shellFactory = shells,
            clock = clock,
            store = store,
            actor = actor,
            overlayOwner = overlay,
            notices = notices::add,
            catalog = FakeCatalog,
            gateLeaseStore = gateLease,
            leases = leases,
            apkPath = SPLIT_APK_PATH,
            appLabel = appLabel,
            sleeper = {},
            log = { line ->
                diagnostics += line
                unmirrored += line
                onDiagnostic(line)
            },
            logMirror = {
                synchronized(unmirrored) { unmirrored.toList().also { unmirrored.clear() } }
            },
        ).also { core -> built = core }
    }

    fun commands(): List<String> = synchronized(fake) { fake.commands.toList() }

    /** What each operation sent through its own shell session, in the order the sessions opened. */
    fun sessions(): List<List<String>> = shells.sessions()

    fun clearCommands() {
        synchronized(fake) { fake.commands.clear() }
        shells.clearSessions()
    }

    /** Everything that could have changed the screen; reads are deliberately not listed. */
    fun mutations(): List<String> = commands().filter(::isMutation)

    /** The read-only prologue of an operation: everything it asked before it changed anything. */
    fun readsBeforeFirstMutation(): List<String> = commands().takeWhile { !isMutation(it) }

    /** Occupies the single worker so a test can fill the queue and watch the order it is served. */
    fun hold(until: () -> Boolean = { false }): SplitWorkerHold {
        val held = SplitWorkerHold(until)
        actor.submit(held)
        check(held.entered.await(SPLIT_AWAIT_MS, TimeUnit.MILLISECONDS)) {
            "the worker never picked the hold up"
        }
        return held
    }

    /** The single worker is the barrier: what it finishes last, it finished after everything. */
    fun barrier() {
        checkNotNull(actor.submit(BarrierSpec).await(SPLIT_AWAIT_MS)) {
            "the actor did not drain in time"
        }
    }

    fun close() {
        built?.shutdown()
        actor.shutdown()
    }

    private companion object {
        fun isMutation(command: String): Boolean =
            command.startsWith("am start ") ||
                command.startsWith("am stack move-task ") ||
                command.startsWith("am task focus ") ||
                command.startsWith("am task resize ") ||
                command.startsWith("input ") ||
                command.contains("settings put global ") ||
                command.contains("settings delete global ") ||
                command.contains(" remove-task ") ||
                command.startsWith("service call activity_task 114 ") ||
                command.startsWith("service call activity_task 115") ||
                command.startsWith("service call activity_task 125 ") ||
                command.startsWith("service call activity_task 126 ")
    }
}

/**
 * The shell seam, with the two injections a scenario needs: a command the operation is parked on,
 * and a command the ADB link drops on.
 *
 * A dropped command is recorded in [sessions] as attempted but never reaches [FakeShell], which is
 * exactly what a link failure means: the car did not see it.
 */
internal class RecordingShellFactory(private val fake: FakeShell) : SplitShellFactory {
    val opened = AtomicInteger()

    private val recorded = mutableListOf<MutableList<String>>()

    @Volatile
    private var blockAt: String? = null

    @Volatile
    private var failAt: String? = null

    @Volatile
    private var reached = CountDownLatch(0)

    @Volatile
    private var gate = CountDownLatch(0)

    fun sessions(): List<List<String>> =
        synchronized(recorded) { recorded.map { session -> session.toList() } }

    fun clearSessions() = synchronized(recorded) { recorded.clear() }

    /** Parks the next occurrence of [command] until [release]; one arming, one park. */
    fun blockAt(command: String) {
        reached = CountDownLatch(1)
        gate = CountDownLatch(1)
        blockAt = command
    }

    fun awaitBlocked(): Boolean = reached.await(SPLIT_AWAIT_MS, TimeUnit.MILLISECONDS)

    fun release() = gate.countDown()

    /** Drops the link on the next occurrence of [command]; one arming, one failure. */
    fun failOn(command: String) {
        failAt = command
    }

    override fun open(): SplitShellHandle {
        opened.incrementAndGet()
        val session = mutableListOf<String>()
        synchronized(recorded) { recorded += session }
        return object : SplitShellHandle {
            override fun shell(command: String): String {
                if (command == blockAt) {
                    blockAt = null
                    reached.countDown()
                    gate.await(SPLIT_AWAIT_MS, TimeUnit.MILLISECONDS)
                }
                synchronized(recorded) { session += command }
                if (command == failAt) {
                    failAt = null
                    throw IllegalStateException(SPLIT_ADB_DROPPED)
                }
                return synchronized(fake) { fake.shell(command) }
            }

            override fun close() = Unit
        }
    }
}

/** The atomic durable store of contract section 6, counting writes and able to refuse one (K9). */
internal class CountingStore : SplitStateStore {
    @Volatile
    private var current = SplitDurable()

    @Volatile
    var commits: Int = 0
        private set

    @Volatile
    var accept: Boolean = true

    fun seed(snapshot: SplitDurable) {
        current = snapshot
        commits = 0
    }

    override fun load(): SplitDurable = current

    override fun commit(next: SplitDurable): Boolean {
        commits += 1
        if (!accept) return false
        current = next
        return true
    }
}

internal class CountingOverlay : SplitOverlayOwner {
    val begun = AtomicInteger()
    private val released = AtomicInteger()

    fun closed(): Int = released.get()

    override fun begin(): SplitOverlayLease {
        begun.incrementAndGet()
        return object : SplitOverlayLease {
            private val done = AtomicInteger()

            override fun close() {
                if (done.compareAndSet(0, 1)) released.incrementAndGet()
            }

            override fun closeImmediately() = close()
        }
    }
}

/** One firmware-global setting the product borrows and gives back exactly as it found it. */
internal class FakeLease(
    override val kind: String,
    private val key: String,
) : SplitLeaseController {
    private var displaced: String? = null

    override fun ownedValue(): String? = displaced

    override fun enable(shell: (String) -> String) {
        if (displaced != null) return
        displaced = shell("settings get global $key").trim()
        shell("settings put global $key 1")
    }

    override fun restore(shell: (String) -> String) {
        val previous = displaced ?: return
        displaced = null
        shell("settings put global $key $previous")
    }
}

/**
 * The real SmartMulti lease over the fake firmware (contract 1.12).
 *
 * It is the product's own controller rather than a stand-in, because what a scenario has to prove
 * is the end-to-end consequence: what the car is left holding after the session is over.
 */
internal class SmartMultiLease(
    private val store: SplitSmartMultiLeaseStore = FakeSmartMultiLeaseStore(),
) : SplitLeaseController {
    override val kind: String get() = SplitLeaseKind.SMART_MULTI

    override fun ownedValue(): String? = if (store.loadOriginal() != null) "owned" else null

    override fun enable(shell: (String) -> String) =
        SplitSmartMultiController(shell, store).enable()

    override fun restore(shell: (String) -> String) =
        SplitSmartMultiController(shell, store).restore()
}

/**
 * The real resizeability lease over the fake firmware (contract 5, to 1.12).
 *
 * Like [SmartMultiLease] it is the product's own controller rather than a stand-in, so a scenario
 * sees the commands this lease really sends - including the compound statement the open path pays
 * one round trip for - and the diagnostic line the contract owes for each of them.
 */
internal class ResizeabilityLease(
    private val store: FakeResizeabilityLeaseStore = FakeResizeabilityLeaseStore(),
) : SplitLeaseController {
    override val kind: String get() = SplitLeaseKind.RESIZEABILITY

    override fun ownedValue(): String? = store.loadOriginal()?.name

    override fun enable(shell: (String) -> String) =
        SplitResizeabilityController(shell, store).enable()

    override fun restore(shell: (String) -> String) =
        SplitResizeabilityController(shell, store).restore()
}

internal class FakeResizeabilityLeaseStore : SplitResizeabilityLeaseStore {
    private var original: SplitGlobalSettingValue? = null

    override fun loadOriginal(): SplitGlobalSettingValue? = original

    override fun saveOriginal(value: SplitGlobalSettingValue): Boolean {
        original = value
        return true
    }

    override fun clearOriginal(): Boolean {
        original = null
        return true
    }
}

internal class FakeSmartMultiLeaseStore : SplitSmartMultiLeaseStore {
    var original: Map<String, String?>? = null
        private set

    override fun loadOriginal(): Map<String, String?>? = original

    override fun saveOriginal(values: Map<String, String?>): Boolean {
        original = values
        return true
    }

    override fun clearOriginal(): Boolean {
        original = null
        return true
    }
}

/** The picker-access lease store the way wiped preferences hand it over: nothing owned, version 0. */
internal class FakePickerAccessLeaseStore : SplitNativePickerAccessLeaseStore {
    @Volatile
    private var owned = false

    @Volatile
    private var version = 0

    override fun isOwned(): Boolean = owned

    override fun setOwned(owned: Boolean): Boolean {
        this.owned = owned
        return true
    }

    override fun configurationVersion(): Int = version

    override fun setConfigurationVersion(version: Int): Boolean {
        this.version = version
        return true
    }
}

/**
 * The picker-access lease as this car really implements it - and therefore reentrant (live red P1.2).
 *
 * [FakeLease] is one shell write and nothing else, which is precisely why the suite stayed green
 * over a deterministic live failure: the real controller rebinds an accessibility service, and a
 * freshly bound service calls straight back into the coordinator, synchronously, from inside the
 * operation that took the lease. This one runs the production [SplitNativePickerAccessController]
 * against the shared firmware fixture and then makes that call back through [onServiceConnected].
 */
internal class ReentrantPickerAccessLease(
    private val onServiceConnected: () -> Unit,
    private val leaseStore: FakePickerAccessLeaseStore = FakePickerAccessLeaseStore(),
) : SplitLeaseController {
    val enables = AtomicInteger()
    val restores = AtomicInteger()

    @Volatile
    private var connected = false

    override val kind: String get() = SplitLeaseKind.PICKER_ACCESS

    fun isOwned(): Boolean = leaseStore.isOwned()

    override fun ownedValue(): String? = if (leaseStore.isOwned()) OWNED else null

    override fun enable(shell: (String) -> String) {
        enables.incrementAndGet()
        controller(shell).enable()
        connected = true
        onServiceConnected()
    }

    override fun restore(shell: (String) -> String) {
        restores.incrementAndGet()
        controller(shell).restore()
        connected = false
    }

    private fun controller(shell: (String) -> String) = SplitNativePickerAccessController(
        shell = shell,
        leaseStore = leaseStore,
        pauseAfterDisable = {},
        isConnected = { connected },
    )

    private companion object {
        const val OWNED = "owned"
    }
}

/**
 * What a freshly bound picker observer reports to the coordinator.
 *
 * It is `SplitNativePickerAccessibilityService.onServiceConnected` in one line: reconnecting an
 * observer is not an event on the screen (invariant 8), so nothing is reported unless the stock
 * picker really is in a visible window right now. Putting `core.homeVisible()` back in here is the
 * mutation that reproduces the live red chain of P1.2 - and it must fail the reentrant scenario.
 */
internal object ReboundObserver {
    fun report(core: SplitCoordinatorCore, stockPickerVisible: Boolean) {
        if (stockPickerVisible) core.nativePickerVisible()
    }
}

/** Denza Apps is an ordinary row of it, with no exception of any kind (U3, 1.4.3). */
internal object FakeCatalog : SplitLaunchCatalog {
    val installed: Set<String> = setOf(NAVIGATOR, MUSIC, WAZE, RADIO, SPLIT_HOST_PACKAGE)

    override fun resolve(packageName: String): SplitLaunchTarget? =
        if (packageName in installed) {
            SplitLaunchTarget(packageName, "$packageName/$packageName.MainActivity")
        } else {
            null
        }
}

internal object BarrierSpec : SplitOperationSpec {
    override val label = "barrier"
    override val priority = SplitInputPriority.HINT
    override val durationMs = 60_000L
    override val joinKey: Any? = null
    override val coalesceKey: Any? = null

    override fun run(op: SplitOperationContext): SplitOutcome = SplitOutcome.Committed
}

/**
 * Occupies the single worker until [until] holds or [release] is called, so a test can fill the
 * queue and then watch the order the actor actually serves it in. It never preempts: it is queued
 * while the queue is empty and is in flight before anything else arrives.
 */
internal class SplitWorkerHold(private val until: () -> Boolean = { false }) : SplitOperationSpec {
    val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)

    override val label = "hold"
    override val priority = SplitInputPriority.HINT
    override val durationMs = 120_000L
    override val joinKey: Any? = null
    override val coalesceKey: Any? = null

    fun release() = released.countDown()

    override fun run(op: SplitOperationContext): SplitOutcome {
        entered.countDown()
        val deadline = System.currentTimeMillis() + SPLIT_AWAIT_MS
        while (released.count > 0L && !until() && System.currentTimeMillis() < deadline) {
            Thread.sleep(1)
        }
        return SplitOutcome.Committed
    }
}

// endregion
