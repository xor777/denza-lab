package dev.denza.apps.feature.split

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import android.widget.Toast

/**
 * Signature-protected, no-display command boundary for the standalone launcher and picker.
 *
 * This DiLink build refuses to start a stopped cross-UID service or content-provider process.
 * An explicit Activity launch is the supported user-initiated boundary. It stays in the caller's
 * task, exposes only fixed commands, never accepts shell text/components, and finishes at once.
 */
class SplitCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val method = intent.getStringExtra(EXTRA_METHOD).orEmpty()
        val receiver = intent.resultReceiver()
        try {
            when (method) {
                METHOD_OPEN -> SplitScreenCoordinator.openPickerSession(
                    applicationContext,
                    ::showError,
                )
                METHOD_PICKER_VISIBLE -> SplitScreenCoordinator.onPickerVisible(
                    applicationContext,
                    intent.requireTaskId(),
                    ::showError,
                )
                METHOD_PICKER_STOPPED -> SplitScreenCoordinator.onPickerStopped(
                    applicationContext,
                    intent.requireTaskId(),
                )
                METHOD_SELECT -> SplitScreenCoordinator.selectApp(
                    context = applicationContext,
                    pickerTaskId = intent.requireTaskId(),
                    packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                        ?.takeIf(String::isNotBlank)
                        ?: error("Не выбрано приложение"),
                ) { error ->
                    receiver?.send(
                        0,
                        Bundle().apply { putString(RESULT_ERROR, error.orEmpty()) },
                    ) ?: showError(error)
                }
                else -> error("Неизвестная команда разделения экрана")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Split command failed: $method", error)
            receiver?.send(
                0,
                Bundle().apply { putString(RESULT_ERROR, USER_ERROR) },
            ) ?: showError(USER_ERROR)
        } finally {
            finish()
        }
    }

    private fun showError(error: String?) {
        if (error == null) return
        Log.w(TAG, error)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, error, Toast.LENGTH_LONG).show()
        }
    }

    private fun android.content.Intent.requireTaskId(): Int =
        getIntExtra(EXTRA_PICKER_TASK_ID, -1).also { require(it > 0) }

    @Suppress("DEPRECATION")
    private fun android.content.Intent.resultReceiver(): ResultReceiver? =
        getParcelableExtra(EXTRA_RESULT_RECEIVER)

    private companion object {
        const val TAG = "DenzaSplitCommand"
        const val USER_ERROR = "Не удалось открыть разделение экрана"
        const val METHOD_OPEN = "open"
        const val METHOD_PICKER_VISIBLE = "picker_visible"
        const val METHOD_PICKER_STOPPED = "picker_stopped"
        const val METHOD_SELECT = "select"
        const val EXTRA_METHOD = "dev.denza.apps.extra.SPLIT_METHOD"
        const val EXTRA_PICKER_TASK_ID = "dev.denza.apps.extra.SPLIT_PICKER_TASK_ID"
        const val EXTRA_PACKAGE_NAME = "dev.denza.apps.extra.SPLIT_PACKAGE_NAME"
        const val EXTRA_RESULT_RECEIVER = "dev.denza.apps.extra.SPLIT_RESULT_RECEIVER"
        const val RESULT_ERROR = "dev.denza.apps.result.SPLIT_ERROR"
    }
}
