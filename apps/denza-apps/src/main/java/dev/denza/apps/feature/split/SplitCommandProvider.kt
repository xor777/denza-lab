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
 * The standalone launcher first starts [SplitCommandActivity], so this provider's owner process
 * is already alive before a picker can call it. Keeping picker events here is essential: an
 * Activity command would join the caller task and make SmartMulti reparent that whole picker.
 */
class SplitCommandProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val app = context?.applicationContext ?: return result(USER_ERROR)
        return try {
            when (method) {
                METHOD_PICKER_VISIBLE -> SplitScreenCoordinator.onPickerVisible(
                    app,
                    extras.requireTaskId(),
                    ::showError,
                )
                METHOD_SELECT -> {
                    val receiver = extras.resultReceiver()
                    SplitScreenCoordinator.selectApp(
                        context = app,
                        pickerTaskId = extras.requireTaskId(),
                        packageName = extras?.getString(EXTRA_PACKAGE_NAME)
                            ?.takeIf(String::isNotBlank)
                            ?: error("Не выбрано приложение"),
                    ) { error ->
                        receiver?.send(
                            0,
                            Bundle().apply { putString(RESULT_ERROR, error.orEmpty()) },
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
        this?.getInt(EXTRA_PICKER_TASK_ID, -1)?.also { require(it > 0) }
            ?: error("Пикер не найден")

    @Suppress("DEPRECATION")
    private fun Bundle?.resultReceiver(): ResultReceiver? =
        this?.getParcelable(EXTRA_RESULT_RECEIVER)

    private fun result(error: String?): Bundle = Bundle().apply {
        putString(RESULT_ERROR, error.orEmpty())
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
        const val METHOD_PICKER_VISIBLE = "picker_visible"
        const val METHOD_SELECT = "select"
        const val EXTRA_PICKER_TASK_ID = "dev.denza.apps.extra.SPLIT_PICKER_TASK_ID"
        const val EXTRA_PACKAGE_NAME = "dev.denza.apps.extra.SPLIT_PACKAGE_NAME"
        const val EXTRA_RESULT_RECEIVER = "dev.denza.apps.extra.SPLIT_RESULT_RECEIVER"
        const val RESULT_ERROR = "dev.denza.apps.result.SPLIT_ERROR"
    }
}
