package dev.denza.split.launcher

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** Non-split launcher boundary. This package must never be registered with BYD split policy. */
class SplitLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            SplitCommandContract.open(this)
        }.onFailure {
            Toast.makeText(
                applicationContext,
                "Не удалось открыть разделение экрана",
                Toast.LENGTH_LONG,
            ).show()
        }
        finish()
    }
}
