package dev.denza.apps.feature.split

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import android.widget.Toast

/**
 * In-session command boundary for picker tasks.
 *
 * The provider is private to the one Denza Apps package. Keeping picker events here is essential:
 * an Activity command would join the caller task and make SmartMulti reparent that whole picker.
 */
class SplitCommandProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val app = context?.applicationContext ?: return result(USER_ERROR)
        return try {
            when (method) {
                SplitCommandContract.METHOD_PICKER_VISIBLE -> SplitScreenCoordinator.onPickerVisible(
                    app,
                    extras.requireTaskId(),
                    ::showError,
                )
                SplitCommandContract.METHOD_PICKER_HIDDEN -> SplitScreenCoordinator.onPickerHidden(
                    app,
                    extras.requireTaskId(),
                )
                SplitCommandContract.METHOD_DIVIDER_RESIZED ->
                    SplitScreenCoordinator.onDividerResized(app)
                SplitCommandContract.METHOD_SELECT -> {
                    val receiver = extras.resultReceiver()
                    SplitScreenCoordinator.selectApp(
                        context = app,
                        pickerTaskId = extras.requireTaskId(),
                        packageName = extras?.getString(SplitCommandContract.EXTRA_PACKAGE_NAME)
                            ?.takeIf(String::isNotBlank)
                            ?: error("Не выбрано приложение"),
                    ) { error ->
                        receiver?.send(
                            0,
                            Bundle().apply {
                                putString(SplitCommandContract.RESULT_ERROR, error.orEmpty())
                            },
                        ) ?: showError(error)
                    }
                }
                else -> return result("Неизвестная команда разделения экрана")
            }
            result(null)
        } catch (error: Throwable) {
            Log.e(TAG, "Split command failed: $method", error)
            result(USER_ERROR)
        }
    }

    private fun showError(error: String?) {
        if (error == null) return
        Log.w(TAG, error)
        val app = context?.applicationContext ?: return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(app, error, Toast.LENGTH_LONG).show()
        }
    }

    private fun Bundle?.requireTaskId(): Int =
        this?.getInt(SplitCommandContract.EXTRA_PICKER_TASK_ID, -1)
            ?.also { require(it > 0) }
            ?: error("Окно устарело. Попробуйте ещё раз")

    @Suppress("DEPRECATION")
    private fun Bundle?.resultReceiver(): ResultReceiver? =
        this?.getParcelable(SplitCommandContract.EXTRA_RESULT_RECEIVER)

    private fun result(error: String?): Bundle = Bundle().apply {
        putString(SplitCommandContract.RESULT_ERROR, error.orEmpty())
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
        const val USER_ERROR = "Не удалось открыть разделение экрана"
    }
}
