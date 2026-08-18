package dev.denza.apps.adb

import android.content.Context
import dev.denza.disharebridge.LocalAdbClient

/**
 * The only default ADB client factory for Denza Apps.
 *
 * Feature code is deliberately passive: it may reuse an already trusted key, but it must never
 * create an authorization prompt. The hidden ADB Rescue flow is the sole owner of explicit
 * public-key submission.
 */
object DenzaLocalAdb {
    private const val KEY_COMMENT = "denza-apps@denza"

    fun client(context: Context): LocalAdbClient = LocalAdbClient(
        context,
        KEY_COMMENT,
        LocalAdbClient.AuthorizationPolicy.PASSIVE,
    )
}
