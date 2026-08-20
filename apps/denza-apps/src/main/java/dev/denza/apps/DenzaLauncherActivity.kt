package dev.denza.apps

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.denza.apps.feature.split.SplitScreenSettings

/** Windowless launcher boundary that never becomes a member of the active split pair. */
class DenzaLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SplitScreenSettings.isEnabled(this)) {
            // Keep the chosen pane non-empty while the windowless entry finishes. The service
            // immediately moves this exact singleTask control task to root 4; the picker/app
            // stack underneath therefore never hits SmartMulti's empty-pane collapse path.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_RESTORE_SPLIT_ON_BACK, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            startService(Intent(this, DenzaControlLaunchService::class.java))
        } else {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }
}
