package dev.denza.apps.feature.navigation

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Surface
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.apps.platform.shell.shellQuote
import dev.denza.disharebridge.LocalAdbClient

/**
 * Fixed-operation shell bridge plus an app-owned navigation virtual display.
 *
 * The car strips Binder objects from manifest broadcasts and rejects bound
 * services from a bare app_process caller. Keeping the Surface and
 * VirtualDisplay in the app removes that cross-process Binder handshake; short
 * shell-UID commands perform only the fixed task operations below, on a package
 * [ProjectablePackages] admits.
 */
object NavigationProxyClient {
    private const val MAIN_CLASS = "dev.denza.apps.feature.navigation.ClusterProxyMain"
    private const val RESULT_PREFIX = "DENZA_RESULT:"
    private val DISPLAY_FLAGS =
        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY

    private val lock = Any()
    private val shellLock = Any()
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var adbShell: LocalAdbClient.PersistentShellSession? = null

    fun findTask(context: Context, packageName: String): Int =
        intResult(run(context, findTaskWords(packageName)))

    fun projectTask(
        context: Context,
        packageName: String,
        taskId: Int,
        projectionRootTaskId: Int,
        displayId: Int,
        width: Int,
        height: Int,
    ): Boolean = booleanResult(
        run(
            context,
            projectTaskWords(packageName, taskId, projectionRootTaskId, displayId, width, height),
        ),
    )

    fun returnTask(
        context: Context,
        packageName: String,
        taskId: Int,
        origin: NavigationProjectionOrigin,
        focusNavigation: Boolean,
    ): Boolean = booleanResult(
        run(context, returnTaskWords(packageName, taskId, origin, focusNavigation)),
    )

    fun projectionOrigin(
        context: Context,
        packageName: String,
        taskId: Int,
    ): NavigationProjectionOrigin = projectionOriginValue(
        run(context, projectionOriginWords(packageName, taskId)),
    )

    internal fun projectionOriginValue(output: String): NavigationProjectionOrigin {
        val values = resultValue(output)
            .split(',')
            .map { it.toIntOrNull() ?: error("navigation origin is malformed") }
        check(values.size == 3) { "navigation origin is malformed" }
        return NavigationProjectionOrigin(
            sourceRootTaskId = values[0],
            companionTaskId = values[1],
            companionRootTaskId = values[2],
        )
    }

    fun createVirtualDisplay(
        context: Context,
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Int = synchronized(lock) {
        releaseVirtualDisplay()
        check(surface.isValid) { "navigation surface is invalid" }
        val manager = context.getSystemService(DisplayManager::class.java)
            ?: error("display manager unavailable")
        virtualDisplay = manager.createVirtualDisplay(
            "Denza Navigation",
            width.coerceIn(320, 7_680),
            height.coerceIn(240, 4_320),
            densityDpi.coerceIn(120, 640),
            surface,
            DISPLAY_FLAGS,
        ) ?: error("virtual display creation failed")
        virtualDisplay!!.display.displayId
    }

    fun createProjectionRoot(context: Context, displayId: Int): Int =
        intResult(run(context, createRootWords(displayId)))

    fun taskDisplayId(context: Context, packageName: String, taskId: Int): Int =
        intResult(run(context, taskDisplayWords(packageName, taskId)))

    fun currentVirtualDisplayId(): Int? = synchronized(lock) {
        virtualDisplay?.display?.displayId
    }

    fun isVirtualDisplayAlive(expectedDisplayId: Int): Boolean = synchronized(lock) {
        val display = virtualDisplay?.display ?: return@synchronized false
        display.displayId == expectedDisplayId && display.isValid
    }

    fun releaseVirtualDisplay() = synchronized(lock) {
        virtualDisplay?.release()
        virtualDisplay = null
    }

    fun disconnectShell() {
        synchronized(shellLock) {
            adbShell?.close()
            adbShell = null
        }
    }

    private fun run(context: Context, words: List<String>): String {
        val command = commandLine(context.applicationInfo.sourceDir, words)
        val shell = synchronized(shellLock) {
            adbShell ?: DenzaLocalAdb.client(context)
                .openPersistentShell()
                .also { adbShell = it }
        }
        return shell.shell(command)
    }

    // The words of each fixed operation, in the order and the count ClusterProxyMain.main reads
    // them: an operation, then its arguments, every one of them a separate shell word.

    internal fun findTaskWords(packageName: String): List<String> =
        listOf("find-task", packageName)

    internal fun projectTaskWords(
        packageName: String,
        taskId: Int,
        projectionRootTaskId: Int,
        displayId: Int,
        width: Int,
        height: Int,
    ): List<String> = listOf(
        "project-task",
        packageName,
        taskId.toString(),
        projectionRootTaskId.toString(),
        displayId.toString(),
        width.toString(),
        height.toString(),
    )

    internal fun returnTaskWords(
        packageName: String,
        taskId: Int,
        origin: NavigationProjectionOrigin,
        focusNavigation: Boolean,
    ): List<String> = listOf(
        if (focusNavigation) "return-task" else "restore-task",
        packageName,
        taskId.toString(),
        origin.sourceRootTaskId.toString(),
        origin.companionTaskId.toString(),
        origin.companionRootTaskId.toString(),
    )

    internal fun projectionOriginWords(packageName: String, taskId: Int): List<String> =
        listOf("projection-origin", packageName, taskId.toString())

    internal fun createRootWords(displayId: Int): List<String> =
        listOf("create-root", displayId.toString())

    internal fun taskDisplayWords(packageName: String, taskId: Int): List<String> =
        listOf("task-display", packageName, taskId.toString())

    /** One one-shot start of [ClusterProxyMain] from [classpath], every word quoted on its own. */
    internal fun commandLine(classpath: String, words: List<String>): String =
        "CLASSPATH=${shellQuote(classpath)} app_process /system/bin --nice-name=denza_nav_cmd " +
            "$MAIN_CLASS ${words.joinToString(" ") { shellQuote(it) }}"

    internal fun resultValue(output: String): String = output.lineSequence()
        .map(String::trim)
        .lastOrNull { it.startsWith(RESULT_PREFIX) }
        ?.removePrefix(RESULT_PREFIX)
        ?: throw IllegalStateException("navigation command returned no result")

    private fun intResult(output: String): Int = resultValue(output).toIntOrNull()
        ?: throw IllegalStateException("navigation command returned a non-integer result")

    private fun booleanResult(output: String): Boolean = when (resultValue(output)) {
        "true" -> true
        "false" -> false
        else -> throw IllegalStateException("navigation command returned a non-boolean result")
    }

}

data class NavigationProjectionOrigin(
    val sourceRootTaskId: Int,
    val companionTaskId: Int,
    val companionRootTaskId: Int,
)
