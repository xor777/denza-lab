package dev.denza.apps.feature.cloud

import kotlin.math.min

/** One thing the adapter says to the car. */
internal sealed interface CloudStep {
    /** Put the car on [CloudLinkProtocol.WIFI_PROFILE], under which the gate answers «ready». */
    data object UseWifiProfile : CloudStep

    /** `notify_nw(4)`: open the native gate. */
    data object AnnounceReady : CloudStep

    /** `notify_nw(-5)`: close it, and let the client disconnect cleanly. */
    data object AnnounceGone : CloudStep

    /** Give the car back the profile it ships with. */
    data class RestoreProfile(val profile: String) : CloudStep
}

/**
 * When the cloud link speaks, and what it says. Pure and single-threaded: the controller owns one
 * of these, feeds it readings and the clock, and carries out the steps it returns.
 *
 * **What the stock client needs from us.** `cloudmanager` owns identity, telemetry, timers and the
 * whole protocol; the only thing it lacks on this car is a network it believes in. Its gate opens on
 * APN3's «ready» (`notify_nw(4)`) and on nothing Wi-Fi sends it, and closes on APN3's «gone»
 * (`-5`). So the adapter translates: validated Wi-Fi is «ready», Wi-Fi that has stayed gone is
 * «gone» - paired, because a gate opened and never closed is a synthetic APN left standing
 * (docs/telematics-findings.md, "Stock-client Wi-Fi adaptation").
 *
 * **Why it may have to say «ready» again.** The stock `BYDMultiApnConnReceiver` sends `-5` itself on
 * any `CONNECTIVITY_CHANGE_FUNCTION` whose APN3 is not connected, with no comparison against the
 * previous state - a cellular event that closes the gate the adapter opened over Wi-Fi. So a car
 * that is on Wi-Fi and off the cloud is announced again. But a TCP of 0 does not say *why* - the
 * client may be in the middle of its own reconnect - so a repeat waits for the disconnection to
 * [settle][SETTLE_MS] and then for a [backoff] that doubles with every «ready» that did not bring
 * the connection back, up to an hour. Seeing the client connected clears it.
 *
 * **What it never does on its own.** It says nothing when switched off: an app that was never
 * asked to hold the link, or was installed with the switch off, leaves the car's connection
 * exactly as it found it. Taking the link over is the driver's explicit «on»; «off» is the
 * driver's explicit off, and is the only path that sends `-5` and restores the stock profile.
 */
internal class CloudLinkCore {

    /** What the adapter knows about the native gate. */
    enum class Gate {
        /** Nobody here has spoken to it since this process started. */
        UNKNOWN,

        /** We said «ready» and have not said «gone» since. */
        OPENED,

        /** We said «gone», or the client restarted - its gate starts closed. */
        CLOSED,
    }

    var gate: Gate = Gate.UNKNOWN
        private set

    /** «Ready»s said since the client was last seen connected; the backoff grows with it. */
    var attempts: Int = 0
        private set

    private var lastReadyAtMs: Long? = null
    private var disconnectedSinceMs: Long? = null
    private var cloudPid: String? = null

    /**
     * The driver switched the link on, or asked for it again.
     *
     * Everything the repeat rules hold back is waived: the press is the reason. What is left is the
     * reading - a client already connected is not told again.
     */
    fun switchedOn(car: CloudCarState, wifi: Boolean, nowMs: Long): List<CloudStep> {
        gate = Gate.CLOSED
        attempts = 0
        lastReadyAtMs = null
        return reconcile(car, wifi, nowMs)
    }

    /**
     * A reading while the link is on: the periodic one, the stock client's status broadcast, a
     * start of the service, validated Wi-Fi coming back.
     */
    fun reconcile(car: CloudCarState, wifi: Boolean, nowMs: Long): List<CloudStep> {
        car.cloudPid?.let { pid ->
            // A restarted client has a fresh gate, and the framework replays only the APN states
            // it recorded - never ours. Nothing is in doubt about what it needs.
            if (cloudPid != null && cloudPid != pid) closed()
            cloudPid = pid
        }
        if (car.connected == true) {
            gate = Gate.OPENED
            attempts = 0
            disconnectedSinceMs = null
            return emptyList()
        }
        if (!wifi) {
            disconnectedSinceMs = null
            return emptyList()
        }
        // A car that did not answer is not a car that is offline.
        if (car.connected == null) return emptyList()

        val since = disconnectedSinceMs ?: nowMs.also { disconnectedSinceMs = it }
        val due = when (gate) {
            // Closed by us or by a restart: nothing to wait for.
            Gate.CLOSED -> true
            Gate.UNKNOWN, Gate.OPENED -> nowMs - since >= SETTLE_MS &&
                lastReadyAtMs.let { it == null || nowMs - it >= backoff(attempts) }
        }
        if (!due) return emptyList()
        return buildList {
            if (!car.wifiProfile) add(CloudStep.UseWifiProfile)
            add(CloudStep.AnnounceReady)
        }
    }

    /**
     * Validated Wi-Fi came back. If we closed the gate when it went, it is closed now and the
     * client is waiting for exactly this; if it was a flicker we never answered, the client's own
     * reconnect is already on it.
     */
    fun wifiReturned(car: CloudCarState, nowMs: Long): List<CloudStep> {
        if (gate == Gate.CLOSED) {
            attempts = 0
            lastReadyAtMs = null
        }
        return reconcile(car, wifi = true, nowMs = nowMs)
    }

    /**
     * Validated Wi-Fi has been gone for [WIFI_LOSS_GRACE_MS]: the other half of the pair.
     *
     * Said while the gate may be ours - including one a previous process opened, which is why
     * [Gate.UNKNOWN] answers too - and only under the profile the adapter put the car on: outside
     * it the gate is not a synthetic APN of ours to close.
     */
    fun wifiGone(car: CloudCarState): List<CloudStep> =
        if (gate == Gate.CLOSED || car.profile != CloudLinkProtocol.WIFI_PROFILE) {
            emptyList()
        } else {
            listOf(CloudStep.AnnounceGone)
        }

    /**
     * The driver switched the link off: close the gate while the profile still honours it, then
     * give the car its own profile back. Each half only when the reading says it is needed.
     */
    fun switchedOff(car: CloudCarState): List<CloudStep> = buildList {
        if (car.profile == CloudLinkProtocol.WIFI_PROFILE) add(CloudStep.AnnounceGone)
        if (!car.onStockProfile) add(CloudStep.RestoreProfile(car.stockProfile))
    }

    /** The car took a «ready». */
    fun readySent(nowMs: Long) {
        gate = Gate.OPENED
        attempts += 1
        lastReadyAtMs = nowMs
        // The settle clock starts again: the next repeat is measured from this one.
        disconnectedSinceMs = nowMs
    }

    /** The car took a «gone». */
    fun goneSent() = closed()

    private fun closed() {
        gate = Gate.CLOSED
        attempts = 0
        lastReadyAtMs = null
        disconnectedSinceMs = null
    }

    companion object {
        /**
         * How long a disconnection must last before «ready» is said again unasked.
         *
         * Long enough for the client's own reconnect: the live run saw one socket drop and come
         * back by itself in the first minute, and the first login took about half a minute.
         */
        const val SETTLE_MS = 90_000L

        /** How long validated Wi-Fi must stay gone before the gate is closed. */
        const val WIFI_LOSS_GRACE_MS = 30_000L

        private const val FIRST_BACKOFF_MS = 5 * 60_000L
        private const val MAX_BACKOFF_MS = 60 * 60_000L

        /** 5, 10, 20, 40 and then 60 minutes after the last «ready» that did not connect. */
        fun backoff(attempts: Int): Long {
            if (attempts <= 0) return 0L
            val doublings = min(attempts - 1, 5)
            return min(FIRST_BACKOFF_MS shl doublings, MAX_BACKOFF_MS)
        }
    }
}
