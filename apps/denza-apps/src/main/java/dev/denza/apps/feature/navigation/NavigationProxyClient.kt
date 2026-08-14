package dev.denza.apps.feature.navigation

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Surface
import dev.denza.disharebridge.LocalAdbClient

/**
 * Fixed-operation shell bridge plus an app-owned navigation virtual display.
 *
 * The car strips Binder objects from manifest broadcasts and rejects bound
 * services from a bare app_process caller. Keeping the Surface and
 * VirtualDisplay in the app removes that cross-process Binder handshake; short
 * shell-UID commands perform only the allowlisted task operations below.
 */
object NavigationProxyClient {
    private const val KEY_COMMENT = "denza-apps@denza"
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

    fun findAllowedTask(context: Context, packageName: String): Int =
        intResult(run(context, "find-task", packageName))

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
            "project-task",
            packageName,
            taskId.toString(),
            projectionRootTaskId.toString(),
            displayId.toString(),
            width.toString(),
            height.toString(),
        ),
    )

    fun returnTask(
        context: Context,
        packageName: String,
        taskId: Int,
        origin: NavigationProjectionOrigin,
        focusNavigation: Boolean,
    ): Boolean = booleanResult(
        run(
            context,
            if (focusNavigation) "return-task" else "restore-task",
            packageName,
            taskId.toString(),
            origin.sourceRootTaskId.toString(),
            origin.companionTaskId.toString(),
            origin.companionRootTaskId.toString(),
        ),
    )

    fun projectionOrigin(
        context: Context,
        packageName: String,
        taskId: Int,
    ): NavigationProjectionOrigin = projectionOriginValue(
        run(context, "projection-origin", packageName, taskId.toString()),
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
        intResult(run(context, "create-root", displayId.toString()))

    fun moveTask(context: Context, packageName: String, taskId: Int, displayId: Int): Boolean =
        booleanResult(
            run(context, "move-task", packageName, taskId.toString(), displayId.toString()),
        )

    fun setTaskBounds(
        context: Context,
        packageName: String,
        taskId: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Boolean = booleanResult(
        run(
            context,
            "set-bounds",
            packageName,
            taskId.toString(),
            left.toString(),
            top.toString(),
            right.toString(),
            bottom.toString(),
        ),
    )

    fun focusTask(context: Context, packageName: String, taskId: Int): Boolean =
        booleanResult(run(context, "focus-task", packageName, taskId.toString()))

    fun backgroundTask(context: Context, packageName: String, taskId: Int): Boolean =
        booleanResult(run(context, "background-task", packageName, taskId.toString()))

    fun taskDisplayId(context: Context, packageName: String, taskId: Int): Int =
        intResult(run(context, "task-display", packageName, taskId.toString()))

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

    fun disconnect() {
        releaseVirtualDisplay()
        disconnectShell()
    }

    fun disconnectShell() {
        synchronized(shellLock) {
            adbShell?.close()
            adbShell = null
        }
    }

    private fun run(context: Context, operation: String, vararg arguments: String): String {
        val apk = shellQuote(context.applicationInfo.sourceDir)
        val args = (listOf(operation) + arguments).joinToString(" ") { shellQuote(it) }
        val command = "CLASSPATH=$apk app_process /system/bin --nice-name=denza_nav_cmd " +
            "$MAIN_CLASS $args"
        val shell = synchronized(shellLock) {
            adbShell ?: LocalAdbClient(context, KEY_COMMENT)
                .openPersistentShell()
                .also { adbShell = it }
        }
        return shell.shell(command)
    }

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

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

}

data class NavigationProjectionOrigin(
    val sourceRootTaskId: Int,
    val companionTaskId: Int,
    val companionRootTaskId: Int,
)
