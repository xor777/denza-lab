package dev.denza.apps.feature.split

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * Launcher-only boundary that opens the existing, live-verified picker session.
 *
 * The waiting window belongs to the operation, not to this Activity: a second tap joins the live
 * `OPEN` and must not raise a second shield over the same one (1.3.7, K4).
 */
class SplitLauncherEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            SplitScreenCoordinator.openPickerSession(applicationContext, ::showError)
        } catch (error: Throwable) {
            Log.e(TAG, "Split Screen launch failed", error)
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
        const val TAG = "DenzaSplitLauncher"
        const val USER_ERROR = "Не удалось открыть разделение экрана"
    }
}
