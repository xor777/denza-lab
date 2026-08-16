package dev.denza.split

import android.content.Context
import android.content.Intent
import android.os.Bundle

internal object SplitCommandContract {
    const val METHOD_PICKER_VISIBLE = "picker_visible"
    const val METHOD_PICKER_STOPPED = "picker_stopped"
    const val METHOD_SELECT = "select"

    const val EXTRA_PICKER_TASK_ID = "dev.denza.apps.extra.SPLIT_PICKER_TASK_ID"
    const val EXTRA_PACKAGE_NAME = "dev.denza.apps.extra.SPLIT_PACKAGE_NAME"
    const val EXTRA_RESULT_RECEIVER = "dev.denza.apps.extra.SPLIT_RESULT_RECEIVER"
    const val RESULT_ERROR = "dev.denza.apps.result.SPLIT_ERROR"

    fun call(context: Context, method: String, extras: Bundle) {
        context.startActivity(
            Intent()
                .setClassName(DENZA_APPS_PACKAGE, COMMAND_ACTIVITY)
                .putExtra(EXTRA_METHOD, method)
                .putExtras(extras),
        )
    }

    private const val DENZA_APPS_PACKAGE = "dev.denza.apps"
    private const val COMMAND_ACTIVITY =
        "dev.denza.apps.feature.split.SplitCommandActivity"
    private const val EXTRA_METHOD = "dev.denza.apps.extra.SPLIT_METHOD"
}
