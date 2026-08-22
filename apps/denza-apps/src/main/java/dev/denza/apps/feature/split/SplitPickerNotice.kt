package dev.denza.apps.feature.split

import android.content.Context
import android.content.Intent

/** Persistent, package-local notice shared by both independent picker tasks. */
internal object SplitPickerNotice {
    const val ACTION_CHANGED = "dev.denza.apps.action.SPLIT_PICKER_NOTICE_CHANGED"
    const val EXTRA_MESSAGE = "dev.denza.apps.extra.SPLIT_PICKER_NOTICE"

    fun current(context: Context): String = SplitScreenSettings.loadPickerNotice(context)

    fun publish(context: Context, message: String) {
        val app = context.applicationContext
        SplitScreenSettings.savePickerNotice(app, message)
        app.sendBroadcast(
            Intent(ACTION_CHANGED)
                .setPackage(app.packageName)
                .putExtra(EXTRA_MESSAGE, message),
        )
    }
}
