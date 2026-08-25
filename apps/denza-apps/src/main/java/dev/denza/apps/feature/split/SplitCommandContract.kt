package dev.denza.apps.feature.split

import android.content.Context
import android.net.Uri
import android.os.Bundle

internal object SplitCommandContract {
    const val METHOD_PICKER_VISIBLE = "picker_visible"
    const val METHOD_PICKER_HIDDEN = "picker_hidden"
    const val METHOD_DIVIDER_RESIZED = "divider_resized"
    const val METHOD_PACKAGE_REMOVED = "package_removed"
    const val METHOD_SELECT = "select"

    const val EXTRA_PICKER_TASK_ID = "dev.denza.apps.extra.SPLIT_PICKER_TASK_ID"
    const val EXTRA_PACKAGE_NAME = "dev.denza.apps.extra.SPLIT_PACKAGE_NAME"
    const val EXTRA_RESULT_RECEIVER = "dev.denza.apps.extra.SPLIT_RESULT_RECEIVER"

    /**
     * Whether the coordinator took the command, and nothing else (U5).
     *
     * The boundary used to carry a message back for the picker to paint. It carries an
     * acknowledgement now: a command the coordinator refused leaves the pane exactly as it was,
     * which is a usable state, and what went wrong belongs in the ring instead.
     */
    const val RESULT_ACCEPTED = "dev.denza.apps.result.SPLIT_ACCEPTED"

    private val COMMAND_URI = Uri.parse("content://dev.denza.apps.split")

    fun call(context: Context, method: String, extras: Bundle): Boolean =
        context.contentResolver
            .call(COMMAND_URI, method, null, extras)
            ?.getBoolean(RESULT_ACCEPTED, false) == true
}
