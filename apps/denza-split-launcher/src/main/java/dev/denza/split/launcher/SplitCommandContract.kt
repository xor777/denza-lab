package dev.denza.split.launcher

import android.content.Context
import android.content.Intent

internal object SplitCommandContract {
    fun open(context: Context) {
        context.startActivity(
            Intent()
                .setClassName(DENZA_APPS_PACKAGE, COMMAND_ACTIVITY)
                .putExtra(EXTRA_METHOD, METHOD_OPEN),
        )
    }

    private const val DENZA_APPS_PACKAGE = "dev.denza.apps"
    private const val COMMAND_ACTIVITY =
        "dev.denza.apps.feature.split.SplitCommandActivity"
    private const val EXTRA_METHOD = "dev.denza.apps.extra.SPLIT_METHOD"
    private const val METHOD_OPEN = "open"
}
