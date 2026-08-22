package dev.denza.apps.feature.split

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/** Launcher-only boundary that opens the existing, live-verified picker session. */
class SplitLauncherEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchOverlay = SplitLaunchOverlay.begin(applicationContext)
        try {
            SplitScreenCoordinator.openPickerSession(applicationContext) { error ->
                if (error == null) {
                    launchOverlay.close()
                } else {
                    launchOverlay.closeImmediately()
                }
                showError(error)
            }
        } catch (error: Throwable) {
            launchOverlay.closeImmediately()
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
