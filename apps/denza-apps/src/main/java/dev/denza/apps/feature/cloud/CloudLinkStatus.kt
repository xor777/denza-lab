package dev.denza.apps.feature.cloud

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
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

    /** What the adapter believes about the gate and its clocks, for the service report. */
    @Volatile
    var adapter: CloudLinkReport.Adapter? = null

    /** When the car was last read (elapsedRealtime), so a report can say how old [car] is. */
    @Volatile
    var readAtMs: Long? = null
}

/** The kind of internet the car is on, as far as the cloud link is concerned. */
enum class CloudNetworkKind(val label: String) {
    WIFI("Wi-Fi"),
    MOBILE("мобильный"),
    NONE("нет"),
}

/**
 * Whether the car has internet the adapter can translate into «APN3 up» for the stock client.
 *
 * The public profile goes out through the default network like any other client, so it is not
 * Wi-Fi as such that it needs but validated internet: Wi-Fi, or mobile data from the car's own
 * SIM. Proven over Wi-Fi on 2026-09-23; **mobile data is not proven on any car** - it is built so
 * owners with a local SIM can test it.
 *
 * Mobile data counts only from a SIM that is not Chinese (MCC 460). A Chinese SIM with service is
 * a roaming SIM on BYD's private APN - the network the stock client was built for, on the stock
 * profile - and the adapter switching that car to the public profile would disable the private
 * APN under it. Such a car is left to the stock framework unless it is on Wi-Fi.
 */
object CloudNetwork {
    fun usable(context: Context): Boolean = kind(context) != CloudNetworkKind.NONE

    fun kind(context: Context): CloudNetworkKind = reading(context).kind

    /** The default network as the rule sees it, raw, for the rule and for the service report. */
    fun reading(context: Context): CloudNetworkReading {
        // No permission needed: the operator code of the SIM, never its identity.
        val sim = context.getSystemService(TelephonyManager::class.java)?.simOperator
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.activeNetwork?.let(connectivity::getNetworkCapabilities)
            ?: return CloudNetworkReading(validated = false, wifi = false, cellular = false, simOperator = sim)
        return CloudNetworkReading(
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            simOperator = sim,
        )
    }

    /** The rule itself, without Android, so it is tested on the JVM. */
    internal fun kindOf(
        validated: Boolean,
        wifi: Boolean,
        cellular: Boolean,
        simOperator: String?,
    ): CloudNetworkKind = when {
        !validated -> CloudNetworkKind.NONE
        wifi -> CloudNetworkKind.WIFI
        cellular && !chineseSim(simOperator) -> CloudNetworkKind.MOBILE
        else -> CloudNetworkKind.NONE
    }

    /** `46000`-`46099`: a mainland SIM, factory or roaming. An unreadable one is not assumed Chinese. */
    internal fun chineseSim(simOperator: String?): Boolean = simOperator?.startsWith("460") == true
}

/** The default network's facts the rule reads, before the rule reads them. */
data class CloudNetworkReading(
    val validated: Boolean,
    val wifi: Boolean,
    val cellular: Boolean,
    val simOperator: String?,
) {
    val kind: CloudNetworkKind
        get() = CloudNetwork.kindOf(validated, wifi, cellular, simOperator)
}

/**
 * The tile's status, read from the wish and the last reading - never from what the controller is
 * doing this second.
 *
 * | status   | when                                                   | tile            |
 * | -------- | ------------------------------------------------------ | --------------- |
 * | OFF      | switched off                                           | «Выключено»     |
 * | ACTIVE   | the stock client holds its connection                  | «На связи»      |
 * | READY    | switched on, no usable internet to translate           | «Нет интернета» |
 * | STARTING | switched on, on internet, not connected (yet)          | «Подключается»  |
 * | ERROR    | the driver's last press was not taken by the car       | the failure     |
 *
 * READY is on and healthy: the adapter has nothing to do until internet comes back, as the mirrors
 * have nothing to do until a turn signal. STARTING may last - the client retries on its own and the
 * adapter repeats «ready» on a growing backoff - and it is drawn as working for as long as it is
 * true, as weather is before its first forecast.
 */
object CloudLinkStatus {
    /**
     * The tile's words for a snapshot - here rather than on the tile so the service report says the
     * link's state in the same words the tile does, from the one place they are written.
     */
    fun words(snapshot: FeatureSnapshot): String = when (snapshot.status) {
        FeatureStatus.OFF -> "Выключено"
        FeatureStatus.ACTIVE -> "На связи"
        FeatureStatus.READY -> "Нет интернета"
        FeatureStatus.ERROR, FeatureStatus.UNAVAILABLE -> snapshot.message.ifBlank { "Не переключилось" }
        else -> "Подключается"
    }

    fun snapshot(
        enabled: Boolean,
        car: CloudCarState?,
        network: Boolean,
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
            !network -> FeatureReducer.ready(FeatureId.CLOUD_LINK)
            else -> base
        }
    }
}
