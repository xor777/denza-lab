package dev.denza.apps.feature.hud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * An invisible screen that asks for the map's screen capture when Denza Apps is not on screen.
 *
 * SystemUI names the asker by `getCallingPackage()`, so only an Activity of ours can ask
 * ([HudMapGrant]). The token dies with the process, and the car kills the process every time it
 * sleeps; waiting for the driver to open Denza Apps left the map window empty after every stop.
 * The owner asked on 2026-10-11 for the capture to be taken without that. So when a route runs,
 * Yandex is on the main screen and there is no token, [HudMapGrant] starts this from the
 * background - transparent, no animation, out of the recents, in a task of its own so the app's
 * own task stays where it is - and it closes itself as soon as SystemUI has answered, which with
 * op 46 allowed is at once and without a dialog.
 */
class HudMapCaptureRequestActivity : ComponentActivity() {
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        HudMapGrant.onConsentResult(this, it.resultCode, it.data)
        close()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Re-created with a request under way: its answer comes to [consent] all the same.
        if (savedInstanceState != null) return
        if (!HudMapGrant.onRequestScreen(this) { intent -> consent.launch(intent) }) close()
    }

    private fun close() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
