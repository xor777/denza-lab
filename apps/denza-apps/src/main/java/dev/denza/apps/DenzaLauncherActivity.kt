package dev.denza.apps

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Windowless launcher boundary that opens Denza Apps like any ordinary application. */
class DenzaLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }
}
