package dev.denza.apps.adb

import android.content.Context
import dev.denza.apps.feature.adb.AdbRescueCoordinator
import dev.denza.disharebridge.LocalAdbClient
import java.io.IOException

/**
 * The only default ADB client factory for Denza Apps.
 *
 * Feature code is deliberately passive: it may reuse an already trusted key, but it must never
 * create an authorization prompt. The hidden ADB Rescue flow is the sole owner of explicit
 * public-key submission.
 *
 * What a feature's own connection learns - adbd let it in, refused the key, or does not listen -
 * goes to the access coordinator ([AdbRescueCoordinator.channelReached],
 * [AdbRescueCoordinator.transportLost]): the features' failures are how the app notices access
 * lost while it runs, and their successes how it notices access back. [observer] null is the
 * coordinator's own looks, which must not hear themselves.
 */
object DenzaLocalAdb {
    private const val KEY_COMMENT = "denza-apps@denza"

    private val features = object : LocalAdbClient.ChannelObserver {
        override fun reached() = AdbRescueCoordinator.channelReached()
        override fun lost(definite: IOException) = AdbRescueCoordinator.transportLost()
    }

    fun client(
        context: Context,
        observer: LocalAdbClient.ChannelObserver? = features,
    ): LocalAdbClient = LocalAdbClient(
        context,
        KEY_COMMENT,
        LocalAdbClient.AuthorizationPolicy.PASSIVE,
        observer,
    )
}
