package dev.denza.apps.feature.cloud

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus

/**
 * The driver's wish, and nothing else.
 *
 * Only the link has one. Keeping Wi-Fi on in sleep is the car's own setting and the panel reads it
 * back from the car, so there is no second copy of it here to disagree with the first.
 *
 * Absent is off, and off on its own does nothing to the car: a new install over a car whose link
 * is already up leaves it up until the driver says «on» and then «off» (see [CloudLinkCore]).
 */
object CloudLinkSettings {
    private const val PREFS = "cloud_link"
    private const val ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    @SuppressLint("UseKtx")
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()
    }
}

/**
 * What the screen may know about the link without asking the car: the last reading, whether a
 * switch is on the wire, and a press that did not take.
 *
 * Written by [CloudLinkController] on its own thread, read by `DenzaAppRepository.refresh`, which
 * must never wait on a shell.
 */
object CloudLinkRuntime {
    @Volatile
    var car: CloudCarState? = null

    /** A switch is being written; both of the panel's switches grey until the car answers. */
    @Volatile
    var busy: Boolean = false

    /** The driver's last press that the car did not take, in the tile's words; null once one does. */
    @Volatile
    var failure: String? = null
}

/** Whether the car is on validated Wi-Fi - the network the adapter translates into «APN3 up». */
object CloudWifi {
    fun validated(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/**
 * The tile's status, read from the wish and the last reading - never from what the controller is
 * doing this second.
 *
 * | status   | when                                              | tile           |
 * | -------- | ------------------------------------------------- | -------------- |
 * | OFF      | switched off                                      | «Выключено»    |
 * | ACTIVE   | the stock client holds its connection             | «На связи»     |
 * | READY    | switched on, no validated Wi-Fi to translate      | «Нет Wi-Fi»    |
 * | STARTING | switched on, on Wi-Fi, not connected (yet)        | «Подключается» |
 * | ERROR    | the driver's last press was not taken by the car  | the failure    |
 *
 * READY is on and healthy: the adapter has nothing to do until Wi-Fi comes back, as the mirrors
 * have nothing to do until a turn signal. STARTING may last - the client retries on its own and the
 * adapter repeats «ready» on a growing backoff - and it is drawn as working for as long as it is
 * true, as weather is before its first forecast.
 */
object CloudLinkStatus {
    fun snapshot(
        enabled: Boolean,
        car: CloudCarState?,
        wifi: Boolean,
        failure: String?,
    ): FeatureSnapshot {
        val base = if (enabled) {
            FeatureReducer.starting(FeatureId.CLOUD_LINK)
        } else {
            FeatureReducer.disabled(FeatureId.CLOUD_LINK)
        }
        return when {
            failure != null -> base.copy(status = FeatureStatus.ERROR, message = failure)
            !enabled -> base
            car?.connected == true -> FeatureReducer.ready(FeatureId.CLOUD_LINK, active = true)
            !wifi -> FeatureReducer.ready(FeatureId.CLOUD_LINK)
            else -> base
        }
    }
}
