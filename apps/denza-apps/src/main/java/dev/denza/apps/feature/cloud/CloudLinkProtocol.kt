package dev.denza.apps.feature.cloud

/**
 * The shell commands the cloud link sends and how the car's answers read. Pure Kotlin with no
 * Android imports, so the whole protocol is unit tested on the JVM.
 *
 * Everything here goes through the shell, as the live run of 2026-09-23 did
 * (`tools/telematics/stock_wifi_gate_test.py`, docs/telematics-findings.md, "Stock-client Wi-Fi
 * adaptation"): the stock profile broadcast, the native `cloudmanager` Binder and the global setting
 * are all out of the app UID's reach, and shell holds every one of them. Nothing new goes into the
 * product manifest for it.
 */
internal object CloudLinkProtocol {

    /** The stock profile that opens the cloud client's gate on event [READY]. */
    const val WIFI_PROFILE = "double_apn"

    /** This car's build profile, and the answer when the build names neither of the two. */
    const val STOCK_PROFILE = "triple_apn"

    /**
     * `notify_nw(4)`: APN3 is up. Under [WIFI_PROFILE] it opens the native gate and the client
     * resolves, connects and logs in over whatever network the kernel routes it through - Wi-Fi.
     */
    const val READY = 4

    /** `notify_nw(-5)`: APN3 is down. Clears the gate and asks the client to disconnect. */
    const val GONE = -5

    /** `Settings.Global` key the ACC-off radio policy reads; `1` keeps client Wi-Fi on in sleep. */
    const val WIFI_RETENTION_KEY = "byd_off_wifi_switch"

    private const val MARKER = "@@"

    private val FIELDS = listOf(
        "profile" to "getprop persist.sys.byd.apn_type",
        "build" to "getprop ro.build.byd.apn_type",
        "apn1" to "getprop persist.radio.net.lte.apn1.disable",
        // The car's own cellular links, as the stock receiver reads them: `connect` while up.
        "apn1state" to "getprop net.lte.apn1.state",
        "apn3state" to "getprop net.lte.apn3.state",
        "pid" to "pidof cloudmanager",
        // The stock TCP client's own getter: the second word is 1 while it holds a connection.
        "tcp" to "service call cloudmanager 7",
        "wifi" to "settings get global $WIFI_RETENTION_KEY",
    )

    /** One round trip for everything the tile and the adapter need, each answer tagged. */
    fun readCommand(): String =
        FIELDS.joinToString("; ") { (name, command) -> "echo $MARKER$name; $command" }

    /**
     * The car's answers, a field left null when its command printed nothing it could mean.
     *
     * A missing `tcp` is not «disconnected»: the adapter must not act on a reading it did not get.
     */
    fun parseRead(output: String): CloudCarState {
        val values = HashMap<String, String>()
        var field: String? = null
        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            val current = field
            when {
                line.startsWith(MARKER) -> field = line.removePrefix(MARKER)
                current != null && line.isNotEmpty() && current !in values -> values[current] = line
            }
        }
        return CloudCarState(
            profile = values["profile"]?.takeIf { it.isNotBlank() },
            buildProfile = values["build"]?.takeIf { it.isNotBlank() },
            apn1Disabled = when (values["apn1"]) {
                "1" -> true
                "0" -> false
                else -> null
            },
            cellular = values["apn1state"].isConnected() || values["apn3state"].isConnected(),
            cloudPid = values["pid"]?.split(' ')?.singleOrNull()?.takeIf { it.all(Char::isDigit) },
            connected = values["tcp"]?.let(::tcpConnected),
            wifiRetained = when (values["wifi"]) {
                "1" -> true
                // Nothing printed is a read that failed. The shell's own word for an absent key is
                // the string `null`, which is the stock default and means «turn Wi-Fi off».
                null -> null
                else -> false
            },
        )
    }

    /**
     * `BYDMultiApnConnReceiver.getApn3Status` counts APN3 as up on exactly `connect`; the live run
     * read `disconnected` on both. `connected` is accepted too, as the spelling nobody has seen yet.
     */
    private fun String?.isConnected(): Boolean = this == "connect" || this == "connected"

    /** `Result: Parcel(00000000 00000001 ...)`: no exception, and the client holds a connection. */
    fun tcpConnected(line: String): Boolean? {
        val words = PARCEL.find(line)?.groupValues?.get(1)?.let { body ->
            WORD.findAll(body).map { it.value.toLong(16).toInt() }.toList()
        } ?: return null
        if (words.size < 2 || words[0] != 0) return null
        return words[1] == 1
    }

    /**
     * The stock profile switch, exactly as the live run sent it.
     *
     * `com.android.phone` owns the broadcast; it writes `persist.sys.byd.apn_type` and, for
     * [WIFI_PROFILE], disables APN1 - which on this car's unregistered SIM never came up anyway.
     */
    fun profileCommand(profile: String): String =
        "am broadcast --user 0 -a com.byd.action.RADIO_CONFIG -p com.android.phone " +
            "-f 0x01000000 --es opt_name set_default_data --es apn_type $profile"

    fun profileAccepted(output: String): Boolean = output.contains("Broadcast completed: result=0")

    /** One network event to the native client; the Binder answers `Parcel(NULL)`. */
    fun notifyCommand(state: Int): String = "service call cloudmanager 1 i32 $state"

    /** A reply that is a parcel at all; `service not found` and friends are not. */
    fun notifyAccepted(output: String): Boolean = output.contains("Result: Parcel(")

    /**
     * Keep client Wi-Fi through ACC-off, or give the decision back to the car.
     *
     * Off deletes the key rather than writing `0`: the key is absent on a car that was never
     * touched, and absent is the configuration the firmware ships - a stored zero only reads the
     * same (`Utils.java:124-136`, `Settings.Global.getInt(..., 0)`).
     */
    fun wifiRetentionCommand(retain: Boolean): String =
        if (retain) {
            "settings put global $WIFI_RETENTION_KEY 1"
        } else {
            "settings delete global $WIFI_RETENTION_KEY"
        }

    private val PARCEL = Regex("""Parcel\(([^')]*)""")
    private val WORD = Regex("""[0-9a-fA-F]{8}""")
}

/**
 * What the car said about its cloud client, read in one shell call.
 *
 * Null is «did not answer», never a value: a car that could not be read is not a car that is
 * offline, and neither the tile nor the adapter may treat it as one.
 */
data class CloudCarState(
    val profile: String? = null,
    val buildProfile: String? = null,
    val apn1Disabled: Boolean? = null,
    /**
     * A cellular APN of the car's own is up. Then the car has the network the stock client was
     * built for - a car with a working SIM - and the gate is the stock framework's to open and
     * close: the adapter says nothing, above all no «gone» that would close a real APN3.
     */
    val cellular: Boolean = false,
    val cloudPid: String? = null,
    val connected: Boolean? = null,
    val wifiRetained: Boolean? = null,
) {
    /** The profile the adapter needs: the gate opens on [CloudLinkProtocol.READY] only under it. */
    val wifiProfile: Boolean
        get() = profile == CloudLinkProtocol.WIFI_PROFILE && apn1Disabled == true

    /**
     * The profile this car ships with, for switching the link off.
     *
     * The build's own when it names one of the two the broadcast knows, and otherwise the one this
     * car's build names - `triple_apn`, read 2026-09-23.
     */
    val stockProfile: String
        get() = buildProfile.takeIf {
            it == CloudLinkProtocol.WIFI_PROFILE || it == CloudLinkProtocol.STOCK_PROFILE
        } ?: CloudLinkProtocol.STOCK_PROFILE

    /** Whether the car is on the profile it ships with, APN1 as that profile leaves it. */
    val onStockProfile: Boolean
        get() = profile == stockProfile &&
            apn1Disabled == (stockProfile == CloudLinkProtocol.WIFI_PROFILE)
}
