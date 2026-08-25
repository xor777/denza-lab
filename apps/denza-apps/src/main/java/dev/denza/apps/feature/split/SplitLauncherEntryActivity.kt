package dev.denza.apps.feature.split

import android.app.Activity
import android.os.Bundle
import android.util.Log

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
            SplitScreenCoordinator.openPickerSession(applicationContext) { Unit }
        } catch (error: Throwable) {
            // U5: the tap produced no scene and there is nothing to say about it here. The one
            // line that explains it is the ring the support screen reads.
            Log.e(TAG, "Split Screen launch failed", error)
            SplitDiagnostics.record("launcher entry failed to submit an open: $error")
        } finally {
            finish()
        }
    }

    private companion object {
        const val TAG = "DenzaSplitLauncher"
    }
}
