package dev.denza.apps.feature.defaultapps

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import dev.denza.apps.MainActivity
import dev.denza.apps.feature.split.SplitPickerActivity

/** Windowless cold-start trampoline from AutoVoice to the selected navigation package. */
class DefaultNavigationProxyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (
            DefaultNavigationProxyEntryPolicy.route(
                action = intent?.action,
                categories = intent?.categories,
                fromPackage = intent?.getStringExtra(EXTRA_FROM),
            )
        ) {
            DefaultNavigationProxyEntryRoute.SPLIT_PICKER -> openSplitPicker()
            DefaultNavigationProxyEntryRoute.DENZA_APPS -> openDenzaApps()
            DefaultNavigationProxyEntryRoute.NAVIGATION -> {
                val target = DefaultAppsSettings.navigationProxyTarget(this)
                    ?: DefaultAppsSettings.rememberedPick(this, DefaultAppRole.NAVIGATION)
                if (!launchTarget(target)) openDenzaApps()
            }
        }
        finish()
    }

    /** Preserve a SmartMulti restore when its BYD pane category survives framework routing. */
    private fun openSplitPicker() {
        try {
            val picker = Intent(this, SplitPickerActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
            intent?.categories.orEmpty().forEach { category -> picker.addCategory(category) }
            startActivity(picker)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not forward split restore to the picker", error)
        }
    }

    private fun launchTarget(target: String?): Boolean {
        if (target == null || !DefaultNavigationProxyContract.isValidTarget(target)) {
            Log.w(TAG, "No valid navigation target is configured")
            return false
        }
        return try {
            val launch = if (target == packageName) {
                Intent(this, MainActivity::class.java)
            } else {
                packageManager.getLaunchIntentForPackage(target)
            }
            if (launch == null) {
                Log.w(TAG, "Configured navigation target is not launchable: $target")
                false
            } else {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
                true
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not launch configured navigation target: $target", error)
            false
        }
    }

    private fun openDenzaApps() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not open Denza Apps for proxy repair", error)
        }
    }

    private companion object {
        const val TAG = "DenzaNavProxy"
        const val EXTRA_FROM = "FROM"
    }
}
