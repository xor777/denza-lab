package dev.denza.apps.feature.split

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * In-session command boundary for picker tasks.
 *
 * The provider is private to the one Denza Apps package, and it is the *only* way into the
 * coordinator from a picker: the picker runs in its own process, so a direct call there would build
 * a second coordinator with its own actor, its own shell and its own opinion about the durable
 * state (invariant 12). Keeping picker events here also matters for the firmware: an Activity
 * command would join the caller task and make SmartMulti reparent that whole picker.
 */
class SplitCommandProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val app = context?.applicationContext ?: return accepted(false)
        return try {
            when (method) {
                // 1.13.1, U6: a picker reporting a window event must not wait for the coordinator
                // to load its store, queue an operation and answer. Nothing about these three is
                // synchronous to the caller - they are notices, and the picker has a frame to draw.
                SplitCommandContract.METHOD_PICKER_VISIBLE -> {
                    val taskId = extras.requireTaskId()
                    detached { SplitScreenCoordinator.onPickerVisible(app, taskId) }
                }
                SplitCommandContract.METHOD_PICKER_HIDDEN -> {
                    val taskId = extras.requireTaskId()
                    detached { SplitScreenCoordinator.onPickerHidden(app, taskId) }
                }
                SplitCommandContract.METHOD_DIVIDER_RESIZED ->
                    detached { SplitScreenCoordinator.onDividerResized(app) }
                SplitCommandContract.METHOD_PACKAGE_REMOVED -> {
                    val packageName = extras?.getString(SplitCommandContract.EXTRA_PACKAGE_NAME)
                        ?.takeIf(String::isNotBlank)
                        ?: error("Не указан пакет")
                    detached { SplitScreenCoordinator.onPackageRemoved(app, packageName) }
                }
                SplitCommandContract.METHOD_SELECT -> {
                    val receiver = extras.resultReceiver()
                    SplitScreenCoordinator.selectApp(
                        context = app,
                        pickerTaskId = extras.requireTaskId(),
                        packageName = extras?.getString(SplitCommandContract.EXTRA_PACKAGE_NAME)
                            ?.takeIf(String::isNotBlank)
                            ?: error("Не выбрано приложение"),
                    ) {
                        // The picker waits for one thing only: that its selection is over (U5).
                        receiver?.send(0, Bundle.EMPTY)
                    }
                }
                else -> return accepted(false)
            }
            accepted(true)
        } catch (error: Throwable) {
            Log.e(TAG, "Split command failed: $method", error)
            accepted(false)
        }
    }

    /**
     * Runs the notice off the caller's thread, in the order the notices arrived.
     *
     * One thread, so `picker_visible` can never overtake the `picker_hidden` that preceded it, and
     * a thread of its own, because the thread this is protecting is a main thread - the picker's
     * while it is drawing its first frame, and this process's while it does everything else.
     */
    private fun detached(action: () -> Unit) {
        NOTICES.execute {
            runCatching(action).onFailure { error -> Log.e(TAG, "Split notice failed", error) }
        }
    }

    private fun Bundle?.requireTaskId(): Int =
        this?.getInt(SplitCommandContract.EXTRA_PICKER_TASK_ID, -1)
            ?.also { require(it > 0) }
            ?: error("Окно устарело. Попробуйте ещё раз")

    @Suppress("DEPRECATION")
    private fun Bundle?.resultReceiver(): ResultReceiver? =
        this?.getParcelable(SplitCommandContract.EXTRA_RESULT_RECEIVER)

    private fun accepted(taken: Boolean): Bundle = Bundle().apply {
        putBoolean(SplitCommandContract.RESULT_ACCEPTED, taken)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val TAG = "DenzaSplitCommand"

        /** One thread: these notices are ordered, and none of them belongs on a main thread. */
        val NOTICES: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "split-notices").apply { isDaemon = true }
        }
    }
}
