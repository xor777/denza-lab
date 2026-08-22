package dev.denza.apps.feature.split

import android.content.Context
import android.net.Uri
import android.os.Bundle

internal object SplitCommandContract {
    const val METHOD_PICKER_VISIBLE = "picker_visible"
    const val METHOD_PICKER_HIDDEN = "picker_hidden"
    const val METHOD_SELECT = "select"

    const val EXTRA_PICKER_TASK_ID = "dev.denza.apps.extra.SPLIT_PICKER_TASK_ID"
    const val EXTRA_PACKAGE_NAME = "dev.denza.apps.extra.SPLIT_PACKAGE_NAME"
    const val EXTRA_RESULT_RECEIVER = "dev.denza.apps.extra.SPLIT_RESULT_RECEIVER"
    const val RESULT_ERROR = "dev.denza.apps.result.SPLIT_ERROR"

    private val COMMAND_URI = Uri.parse("content://dev.denza.apps.split")

    fun call(context: Context, method: String, extras: Bundle): String? =
        context.contentResolver
            .call(COMMAND_URI, method, null, extras)
            ?.getString(RESULT_ERROR)
            ?.takeIf(String::isNotBlank)
}
