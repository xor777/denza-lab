package dev.denza.apps.feature.cloud

import java.time.Instant

/** Read-only, finite log snapshot. No native log text or SIM identity is persisted verbatim. */
internal object CloudNativeLog {
    const val WINDOW = 1200
    const val INTERVAL_MS = 15_000L

    // Capture the PID afresh: cloudmanager can restart between controller observations.
    // No log clearing, verbosity changes, network probes or registration calls.
    fun command(): String = """
        echo @@native:1
        denza_diag_pid=${'$'}(pidof cloudmanager)
        echo @@pid:${'$'}denza_diag_pid
        echo @@registration
        getprop persist.sys.cloud.app_reg_status
        echo @@token
        getprop persist.sys.cloud.token_flag
        denza_diag_shape() {
          denza_diag_value=${'$'}(getprop "${'$'}1" 2>/dev/null)
          if [ ${'$'}? -ne 0 ]; then echo UNKNOWN; return; fi
          denza_diag_length=${'$'}{#denza_diag_value}
          if [ "${'$'}denza_diag_length" -eq 0 ]; then echo MISSING; return; fi
          if [ "${'$'}denza_diag_length" -gt 91 ]; then echo UNKNOWN; return; fi
          case "${'$'}denza_diag_value" in
            *[!0-9A-Fa-f]*) denza_diag_alphabet=OTHER;;
            *[!0-9]*) denza_diag_alphabet=HEX;;
            *) denza_diag_alphabet=DIGITS;;
          esac
          denza_diag_suffix=${'$'}denza_diag_value
          denza_diag_trailing=0
          while [ "${'$'}denza_diag_trailing" -lt "${'$'}denza_diag_length" ]; do
            case "${'$'}denza_diag_suffix" in
              *[Ff]) denza_diag_suffix=${'$'}{denza_diag_suffix%?}
                     denza_diag_trailing=${'$'}((denza_diag_trailing + 1));;
              *) break;;
            esac
          done
          echo "length=${'$'}denza_diag_length alphabet=${'$'}denza_diag_alphabet trailingF=${'$'}denza_diag_trailing"
        }
        echo @@imsi
        denza_diag_shape ril.imsi
        echo @@iccid
        denza_diag_shape ril.csim.iccid
        unset denza_diag_value denza_diag_suffix denza_diag_length denza_diag_trailing denza_diag_alphabet
        echo @@log
        case "${'$'}denza_diag_pid" in
          ''|*[!0-9]*) echo @@logExit:NO_PROCESS;;
          *) timeout 3 logcat -b main -b system -d -t $WINDOW -v epoch --pid=${'$'}denza_diag_pid '[BYDCLOUD]main:V' '[BYDCLOUD]socket:V' '[BYDCLOUD]SSLUtils:V' 'c_ares_dns:V' '*:S'
             echo @@logExit:${'$'}?;;
        esac
        echo @@pidAfter:${'$'}(pidof cloudmanager)
        echo @@done
    """.trimIndent()

    data class Event(val at: String, val pid: String, val tid: String, val message: String) {
        fun line() = "$at pid=$pid tid=$tid $message"
    }

    data class Snapshot(
        val status: String,
        val pid: String?,
        val registration: String,
        val token: String,
        val imsi: String,
        val iccid: String,
        val lines: Int,
        val unclassified: Int,
        val events: List<Event>,
        val windowFull: Boolean,
    ) {
        fun summary() = "status=$status pid=$pid registration=$registration token=$token " +
            "imsiShape=$imsi iccidShape=$iccid lines=$lines unclassified=$unclassified windowFull=$windowFull"
    }

    private val header = Regex("""\s*([0-9]{9,12})\.([0-9]{3,9})\s+([0-9]{1,7})\s+([0-9]{1,7})\s+[VDIWEF]\s+([^:]{1,64}):\s?(.*)""")
    private val pidPattern = Regex("[1-9][0-9]{0,6}")
    private val tags = setOf("[BYDCLOUD]main", "[BYDCLOUD]socket", "[BYDCLOUD]SSLUtils", "c_ares_dns")

    fun parse(output: String): Snapshot {
        var field = ""
        var pid: String? = null
        var pidAfter: String? = null
        var exit: String? = null
        var done = false
        var version = false
        val values = mutableMapOf<String, MutableList<String>>()
        val events = mutableListOf<Event>()
        var lines = 0
        var unknown = 0
        output.lineSequence().take(WINDOW + 100).forEach { raw ->
            val line = raw.trimEnd()
            when {
                line == "@@native:1" -> version = true
                line.startsWith("@@pid:") -> pid = line.substringAfter(':').takeIf(pidPattern::matches)
                line.startsWith("@@pidAfter:") -> { pidAfter = line.substringAfter(':').takeIf(pidPattern::matches); field = "" }
                line.startsWith("@@logExit:") -> { exit = line.substringAfter(':'); field = "" }
                line == "@@done" -> done = true
                line.startsWith("@@") -> field = line.removePrefix("@@")
                field == "log" && !line.startsWith("---------") && line.isNotBlank() -> {
                    lines++
                    val match = header.matchEntire(line)
                    if (match != null && match.groupValues[3] == pid && match.groupValues[5].trim() in tags) {
                        val message = classify(match.groupValues[5].trim(), match.groupValues[6].trim())
                        val at = runCatching {
                            Instant.ofEpochSecond(match.groupValues[1].toLong(), match.groupValues[2].padEnd(9, '0').toLong()).toString()
                        }.getOrNull()
                        if (message != null && at != null) events += Event(at, pid, match.groupValues[4], message)
                        else unknown++
                    } else unknown++
                }
                field in setOf("registration", "token", "imsi", "iccid") && line.isNotBlank() ->
                    values.getOrPut(field) { mutableListOf() }.add(line)
            }
        }
        fun value(name: String, allowed: Set<String>) = values[name]?.singleOrNull()?.takeIf { it in allowed } ?: "UNKNOWN"
        val status = when {
            !version || !done || exit == null -> "INCOMPLETE"
            exit == "NO_PROCESS" -> "NO_PROCESS"
            exit == "124" || exit == "137" -> "TIMEOUT"
            exit != "0" -> "COMMAND_FAILED"
            pid == null || pidAfter != pid -> "PROCESS_CHANGED"
            else -> "OK"
        }
        return Snapshot(status, pid, value("registration", (0..10).map(Int::toString).toSet()),
            value("token", setOf("0", "1")), shape(values["imsi"]?.singleOrNull()),
            shape(values["iccid"]?.singleOrNull()), lines, unknown,
            events, lines >= WINDOW)
    }

    private fun shape(value: String?): String {
        if (value == "MISSING") return value
        val parts = Regex("length=([0-9]{1,2}) alphabet=(DIGITS|HEX|OTHER) trailingF=([0-9]{1,2})")
            .matchEntire(value ?: return "UNKNOWN")?.groupValues ?: return "UNKNOWN"
        val length = parts[1].toInt()
        val trailing = parts[3].toInt()
        return if (length in 1..91 && trailing in 0..length && (parts[2] != "DIGITS" || trailing == 0)) {
            value
        } else "UNKNOWN"
    }

    // Every output is a fixed label with small protocol/status integers. Never forward a message,
    // hostname, address, certificate subject, payload or an arbitrary exception string.
    internal fun classify(tag: String, text: String): String? {
        if (tag !in tags || text.length > 4096) return null
        fun numbers(pattern: String, label: (List<String>) -> String): String? =
            Regex(pattern).matchEntire(text)?.groupValues?.drop(1)?.let(label)
        if (tag == "[BYDCLOUD]main") {
            numbers("send_complete status :([01]), key_id :(200|211|220)") { "send_complete command=${it[1]} success=${it[0]}" }?.let { return it }
            numbers("211 reg_status ([0-9]{1,3})") { "registration_reply code=${it[0]}" }?.let { return it }
            numbers("211 register fail! fail code = ([0-9]{1,3}), mRetryCount = ([0-9]{1,4})") { "registration_failed code=${it[0]} retry=${it[1]}" }?.let { return it }
            numbers("notify_nw\\(\\)\\s+state = (-?[0-9]{1,2})") { "network_notification value=${it[0]}" }?.let { return it }
            numbers("send211 return, mNetworkOk = ([01]), mCloudEnable = ([01]), mTxRegMsg = ([01])") { "registration_gated network=${it[0]} enabled=${it[1]} pending=${it[2]}" }?.let { return it }
            numbers("mApn1Connected IS ([01])") { "native_network_gate value=${it[0]}" }?.let { return it }
            numbers("registeredSuccess is ([0-9]{1,3})") { "registered_state value=${it[0]}" }?.let { return it }
            numbers("tcpReconnect,but can not parse domian ip,mParseCount:([0-9]{1,4});") { "dns_failed reconnect=${it[0]}" }?.let { return it }
            val exact = mapOf(
                "send211" to "registration_start",
                "send211,but no vin return;" to "registration_missing_vehicle_identity",
                "send211,but can not parse domian ip;" to "dns_failed registration",
                "domain prasr faile" to "dns_failed",
                "***mApn1Connected,but can not parse domian ip !!!***" to "dns_failed native_gate_open",
                "*****guid is no exit or null******" to "cloud_identity_missing",
                "*****guid is illegal !!!!******" to "cloud_identity_invalid",
                "recv server data but guid is not correct !" to "reply_identity_invalid",
                "server data format is error!" to "reply_format_invalid",
                "*****connect to tcp server fail !!!*****" to "cloud_login_failed",
                "tcp connect recv 200 err and destory" to "discovery_reply_failed",
                "socket_disconnect_cb() DIV15 is disconnected!" to "cloud_disconnected",
                "socket error, try to connect server again" to "cloud_reconnecting",
                "APN1 connected, but no guid." to "cloud_identity_not_ready",
                "destroyDiv15Connect" to "cloud_connection_closed",
            )
            exact[text]?.let { return it }
            if (text.startsWith("getSSLIPByDomainName is ")) {
                return when (text.removePrefix("getSSLIPByDomainName is ")) {
                    "dilinkreg-cn.denzacloud.com" -> "dns_start public_registration"
                    "dilinkaddr-cn.denzacloud.com" -> "dns_start public_discovery"
                    "dilinkterminalreg-cn.iov.denza.cloud" -> "dns_start private_registration"
                    else -> "dns_start other_endpoint"
                }
            }
        }
        if (tag == "[BYDCLOUD]socket") {
            numbers("connecting status:(-?[0-9]{1,3})!") { "socket_connect status=${it[0]}" }?.let { return it }
            numbers("(?:retry max\\. )?connect error:([0-9]{1,4}):.*") { "socket_connect_failed errno=${it[0]}" }?.let { return it }
            if (text == "connecting socket!") return "socket_connect_start"
            if (text.startsWith("select after connect error ETIMEDOUT,")) return "socket_connect_timeout"
            if (text.startsWith("select after connect error:")) return "socket_select_failed"
            if (text.startsWith("getsockopt after connect error :")) return "socket_option_failed"
        }
        if (tag == "[BYDCLOUD]SSLUtils") {
            if (text == "SSL_connect failed") return "tls_handshake_failed"
            if (text.startsWith("SSL_connect with ")) return "tls_handshake_complete"
            numbers("sslerr is:([0-9]{1,3}):") { "tls_error code=${it[0]}" }?.let { return it }
            numbers("SSL_CTX_check_private_key is ([01])") { "tls_identity_check result=${it[0]}" }?.let { return it }
            if (text.startsWith("SSL Error:")) return when {
                text.contains("certificate verify failed", ignoreCase = true) -> "tls_certificate_verification_failed"
                text.contains("certificate expired", ignoreCase = true) -> "tls_certificate_expired"
                text.contains("handshake failure", ignoreCase = true) -> "tls_peer_handshake_failure"
                else -> "tls_error_other"
            }
        }
        if (tag == "c_ares_dns" && text.startsWith("function_apn address:")) return "dns_address_returned"
        return null
    }
}

/** Overlapping snapshots must not fill the history with the same event on every poll. */
internal class CloudNativeCursor(saved: String = "") {
    private val seen = LinkedHashSet<String>().apply { saved.lineSequence().filter(String::isNotBlank).forEach(::add) }
    private var nextAt = 0L

    fun due(nowMs: Long): Boolean {
        if (nowMs < nextAt) return false
        nextAt = nowMs + CloudNativeLog.INTERVAL_MS
        return true
    }

    fun fresh(events: List<CloudNativeLog.Event>): List<CloudNativeLog.Event> = events.filter {
        seen.add(it.line()).also {
            while (seen.size > 4096) seen.remove(seen.first())
        }
    }
}
