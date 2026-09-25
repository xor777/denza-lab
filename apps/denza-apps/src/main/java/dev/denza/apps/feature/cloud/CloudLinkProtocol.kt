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
    private val APN_FIELDS = setOf("apn1state", "apn3state")
    private val APN_EXIT = Regex("@@(apn[13]state)Exit:([0-9]+)")

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
        "step" to "getprop sys.tcp_step",
        "regError" to "getprop sys.tcp_reg_errcode",
        "apn1if" to "getprop net.lte.apn1.ifname",
        "apn3if" to "getprop net.lte.apn3.ifname",
    )

    /** APN defaults require a matching completed command, not merely an empty output buffer. */
    fun readCommand(): String =
        FIELDS.joinToString("; ") { (name, command) ->
            "echo $MARKER$name; $command" +
                if (name in APN_FIELDS) "; echo $MARKER${name}Exit:${'$'}?" else ""
        }

    private data class ReadField(
        val lines: MutableList<String> = mutableListOf(),
        var exit: Int? = null,
        var invalid: Boolean = false,
    )

    /**
     * The car's answers, a field left null when its command printed nothing it could mean.
     *
     * A missing `tcp` is not «disconnected»: the adapter must not act on a reading it did not get.
     */
    fun parseRead(output: String): CloudCarState {
        val fields = HashMap<String, ReadField>()
        var field: String? = null
        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            val current = field
            val end = APN_EXIT.matchEntire(line)
            when {
                end != null -> {
                    val name = end.groupValues[1]
                    val read = fields.getOrPut(name) { ReadField(invalid = true) }
                    if (current != name || read.exit != null) read.invalid = true
                    read.exit = end.groupValues[2].toIntOrNull()
                    field = null
                }
                line.startsWith(MARKER) -> {
                    val name = line.removePrefix(MARKER)
                    if (name in fields) fields.getValue(name).invalid = true
                    else fields[name] = ReadField()
                    field = name
                }
                current != null && line.isNotEmpty() -> fields.getValue(current).lines.add(line)
            }
        }
        val values = fields.filterValues { !it.invalid }.mapValues { it.value.lines.firstOrNull() }
        val apn1 = readApnState(fields["apn1state"])
        val apn3 = readApnState(fields["apn3state"])
        return CloudCarState(
            profile = values["profile"]?.takeIf { it in setOf(WIFI_PROFILE, STOCK_PROFILE) },
            buildProfile = values["build"]?.takeIf { it.matches(Regex("[a-z_]{1,32}")) },
            apn1Disabled = when (values["apn1"]) {
                "1" -> true
                "0" -> false
                else -> null
            },
            cellular = apn1.first.isConnected() || apn3.first.isConnected(),
            cloudPid = values["pid"]?.split(' ')?.singleOrNull()?.takeIf { it.all(Char::isDigit) },
            connected = values["tcp"]?.let(::tcpConnected),
            wifiRetained = when (values["wifi"]) {
                "1" -> true
                // Nothing printed is a read that failed. The shell's own word for an absent key is
                // the string `null`, which is the stock default and means «turn Wi-Fi off».
                "0", "null" -> false
                else -> null
            },
            apn1State = apn1.first,
            apn3State = apn3.first,
            tcpStep = values["step"]?.toIntOrNull(),
            registrationError = values["regError"]?.toIntOrNull(),
            apn1Interface = values["apn1if"].interfaceName(),
            apn3Interface = values["apn3if"].interfaceName(),
            apn1ReadSource = apn1.second,
            apn3ReadSource = apn3.second,
        )
    }

    private fun readApnState(read: ReadField?): Pair<String?, CloudApnReadSource> = when {
        read == null || read.invalid || read.exit == null -> null to CloudApnReadSource.INCOMPLETE
        read.exit != 0 -> null to CloudApnReadSource.FAILED
        // The retained stock readers use SystemProperties.get(key, "disconnected") for both APNs.
        read.lines.isEmpty() -> "disconnected" to CloudApnReadSource.DEFAULT_EMPTY
        read.lines.size != 1 -> null to CloudApnReadSource.UNSUPPORTED
        else -> read.lines.single().knownApnState()?.let { it to CloudApnReadSource.VALUE }
            ?: (null to CloudApnReadSource.UNSUPPORTED)
    }

    /** Local read validation; never describe a missing property as an error from Denza's server. */
    fun readFailure(car: CloudCarState): String? {
        val missing = buildList {
            if (car.connected == null) add("TCP")
            if (car.profile !in setOf(WIFI_PROFILE, STOCK_PROFILE)) add("профиль")
            if (car.apn1Disabled == null) add("флаг APN1")
            if (car.apn1State == null) add("APN1 (${car.apn1ReadSource?.problem ?: "нет данных"})")
            if (car.apn3State == null) add("APN3 (${car.apn3ReadSource?.problem ?: "нет данных"})")
        }
        return missing.takeIf { it.isNotEmpty() }?.joinToString(", ", "Не прочитано с машины: ")
    }

    /**
     * `BYDMultiApnConnReceiver.getApn3Status` counts APN3 as up on exactly `connect`; the live run
     * read `disconnected` on both. `connected` is accepted too, as the spelling nobody has seen yet.
     */
    private fun String?.isConnected(): Boolean = this == "connect" || this == "connected"

    private fun String?.knownApnState(): String? = takeIf {
        it in setOf("connect", "connected", "disconnected", "connecting", "disconnecting")
    }

    private fun String?.interfaceName(): String? = this?.takeIf { it.matches(Regex("[a-zA-Z0-9_.-]{1,32}")) }

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

    /** Exact successful reply recorded on the target firmware. Unknown replies fail closed. */
    fun notifyAccepted(output: String): Boolean = output.trim() == "Result: Parcel(NULL)"

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

/** Controlled diagnostic labels; no arbitrary shell output is included in reports. */
enum class CloudApnReadSource(val problem: String? = null) {
    VALUE,
    DEFAULT_EMPTY,
    INCOMPLETE("чтение не завершено"),
    FAILED("ошибка команды"),
    UNSUPPORTED("неизвестное значение"),
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
    val apn1State: String? = null,
    val apn3State: String? = null,
    val tcpStep: Int? = null,
    val registrationError: Int? = null,
    val apn1Interface: String? = null,
    val apn3Interface: String? = null,
    val apn1ReadSource: CloudApnReadSource? = null,
    val apn3ReadSource: CloudApnReadSource? = null,
) {
    /** An APN owned by the stock framework is connected or still transitioning. */
    val stockApnBusy: Boolean
        get() = cellular || listOf(apn1State, apn3State).any { it in setOf("connecting", "disconnecting") }

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
