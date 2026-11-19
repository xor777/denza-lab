package dev.denza.apps.feature.split

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * The boundary that opens the existing, live-verified picker session.
 *
 * Two doors reach it now - the desktop icon and a press on the dashboard's split tile - and both
 * come through here on purpose. A second way of splitting the screen would be a second thing to
 * keep working; this is the same one, entered from somewhere else.
 *
 * The waiting window belongs to the operation, not to this Activity: a second tap joins the live
 * `OPEN` and must not raise a second shield over the same one (1.3.7, K4).
 */
class SplitLauncherEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        try {
            SplitScreenCoordinator.openPickerSession(app) { result ->
                SplitScreenCoordinator.repairOnDeadChannel(app, result)
            }
        } catch (error: Throwable) {
            // U5: the tap produced no scene and there is nothing to say about it here. The one
            // line that explains it is the ring the support screen reads.
            Log.e(TAG, "Split Screen launch failed", error)
            SplitDiagnostics.record("launcher entry failed to submit an open: $error")
        } finally {
            // A `Theme.NoDisplay` Activity has to be gone before it resumes; the outcome above
            // arrives later and is answered from the application context.
            finish()
        }
    }

    private companion object {
        const val TAG = "DenzaSplitLauncher"
    }
}
