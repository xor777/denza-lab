package dev.denza.apps.feature.split

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import dev.denza.apps.MainActivity

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
                if (result == SplitActionResult.CHANNEL_UNAVAILABLE) openRepairScreen(app)
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

    /**
     * Contract 1.11.4: split with a dead control channel is not offered as something that works,
     * and the channel is repaired on the hub's own screen rather than by an error over a picker.
     *
     * The tap still has an outcome, and it is a usable screen: the hub opens on the ADB gate,
     * which is the one surface that can ask for the key and re-check it. What this cannot yet do
     * is stop offering the icon in the first place - the alias is the user's own persisted toggle
     * (1.2), and taking it away for a dead link would be the product forgetting a user decision.
     */
    private fun openRepairScreen(app: Context) {
        SplitDiagnostics.record("open refused by a dead control channel: opening the hub (1.11.4)")
        runCatching {
            app.startActivity(
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { error ->
            SplitDiagnostics.record("the hub could not be opened for the repair: $error")
        }
    }

    private companion object {
        const val TAG = "DenzaSplitLauncher"
    }
}
