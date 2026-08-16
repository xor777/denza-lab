package dev.denza.apps

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Keeps the control app out of an already-open BYD split layout. */
class DenzaLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addCategory(CATEGORY_FULL_IVI)
                .addFlags(NATIVE_FULL_LAUNCH_FLAGS),
        )
        finish()
    }

    private companion object {
        const val CATEGORY_FULL_IVI = "byd.intent.category.START_IVI_FULL"
        // CLEAR_TASK prevents an existing pane instance from consuming the
        // intent in place; BYD then creates/reparents the control task in FULL.
        const val NATIVE_FULL_LAUNCH_FLAGS =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
}
