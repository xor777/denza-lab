package dev.denza.apps.feature.split

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Inert split-capable base for one selected application task.
 *
 * Third-party launcher aliases are started by this Activity and placed above it in the task.
 * This stable, resizeable base prevents BYD from reclassifying the task from a temporary
 * unresizeable redirect Activity and deliberately never finishes itself, avoiding last-Activity
 * cleanup during cold starts. The host task is separate from the permanent picker task, so
 * native task dismissal still reveals the picker below it.
 */
class SplitAppHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        if (savedInstanceState == null) launchRequestedComponent()
    }

    private fun launchRequestedComponent() {
        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
            ?.takeIf(String::isNotBlank)
            ?: return
        val rawClass = intent.getStringExtra(EXTRA_TARGET_ACTIVITY)
            ?.takeIf(String::isNotBlank)
            ?: return
        val targetClass = if (rawClass.startsWith('.')) targetPackage + rawClass else rawClass
        val component = ComponentName(targetPackage, targetClass)
        val activityInfo = runCatching { packageManager.getActivityInfo(component, 0) }
            .getOrNull()
            ?: return
        if (!activityInfo.exported || activityInfo.packageName != targetPackage) return
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component),
        )
    }

    private companion object {
        const val EXTRA_TARGET_PACKAGE = SPLIT_HOST_TARGET_PACKAGE_EXTRA
        const val EXTRA_TARGET_ACTIVITY = SPLIT_HOST_TARGET_ACTIVITY_EXTRA
    }
}
