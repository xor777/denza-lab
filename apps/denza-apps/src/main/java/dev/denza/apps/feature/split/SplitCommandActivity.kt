package dev.denza.apps.feature.split

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        try {
            when (method) {
                METHOD_OPEN -> SplitScreenCoordinator.openPickerSession(
                    applicationContext,
                    ::showError,
                )
                else -> error("Неизвестная команда разделения экрана")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Split command failed: $method", error)
            showError(USER_ERROR)
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

    private companion object {
        const val TAG = "DenzaSplitCommand"
        const val USER_ERROR = "Не удалось открыть разделение экрана"
        const val METHOD_OPEN = "open"
        const val EXTRA_METHOD = "dev.denza.apps.extra.SPLIT_METHOD"
    }
}
