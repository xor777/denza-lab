package dev.denza.apps.feature.cloud

import org.json.JSONObject

/** Only controlled, redacted fields cross from the shell worker into UI and diagnostics. */
internal data class CloudCustomStatus(
    val pid: Int,
    val sessionLive: Boolean,
    val stage: String,
    val code: String,
    val updatedElapsedMs: Long,
    val connectedElapsedMs: Long,
    val lastRxElapsedMs: Long,
    val lastTxElapsedMs: Long,
    val lastReportElapsedMs: Long,
    val nextRetryElapsedMs: Long,
    val attempts: Long,
    val reportsSent: Long,
    val statusReplies: Long,
    val commandsForwarded: Long,
    val commandsCompleted: Long,
    val reconnects: Long,
    val callbackAgeMs: Long,
    val events: List<CloudCustomEvent>,
    val ownerId: String = "",
    val runtimeId: String = "",
    val capabilities: Set<String> = emptySet(),
    val retryable: Boolean = false,
    val configGeneration: Long = 0,
    val registrationUncertain: Boolean = false,
    val profile: String = "",
    val leaseUntilUptimeMs: Long = 0,
    val leaseActive: Boolean = false,
    val protocol: Int = 3,
)

internal data class CloudCustomEvent(val seq: Long, val elapsedMs: Long, val event: String)

/** Safe fixed code only: raw native output and exception messages never cross into diagnostics. */
internal class CloudCustomRejected(val code: String, val retryable: Boolean) : Exception("Адаптер отклонил запрос")

internal object CloudCustomOwnershipPolicy {
    fun stopped(status: CloudCustomStatus?): Boolean =
        status != null && !status.sessionLive && status.stage == "stopped" &&
            status.code in setOf("owner_stopped", "stopped", "owner_absent_confirmed") &&
            (status.code != "owner_absent_confirmed" || status.ownerId.isEmpty() && status.configGeneration == 0L)

    fun absent(status: CloudCustomStatus?): Boolean =
        stopped(status) && status?.code == "owner_absent_confirmed" && status.ownerId.isEmpty()
}

/** One request and one answer. Never include the request or raw reply in an exception. */
internal object CloudCustomProtocol {
    private val TOKEN = Regex("[A-Za-z0-9_.:-]{1,80}")
    private val FIXED_CODE = Regex("[a-z][a-z0-9_]{0,79}")
    private val IDENTIFIER_RUN = Regex("[0-9]{4,}")
    private const val MAX_ELAPSED_MS = 1_000_000_000_000L
    private val LEGACY_CAPABILITIES = setOf("reg", "data", "control", "wake", "mcu_state", "timers", "post_login", "heartbeat")
    private val AWAKE_CAPABILITIES = setOf("reg", "data", "control_awake", "wake_ack_awake",
        "timers_awake", "post_login_awake", "heartbeat", "power_guard")
    private val STAGES = setOf("starting", "registering", "registration_wait", "discovering", "connecting", "connected", "waiting_data", "retry_wait", "stopped", "failed")

    const val PROFILE = "awake-alpha-v1"
    private val HEX32 = Regex("[0-9a-f]{32}")

    fun request(id: Long, op: String, identity: CloudIdentity? = null,
                ownerId: String? = null, serviceInstance: String? = null,
                renewSeq: Long? = null, legacyCleanup: Boolean = false): String {
        require(op in setOf("PROBE", "ATTACH", "START", "RENEW", "STATUS", "STOP"))
        require(id > 0)
        require(!legacyCleanup || op in setOf("PROBE", "STATUS", "STOP"))
        val json = JSONObject().put("id", id).put("op", op)
        if (!legacyCleanup) json.put("protocol", 3)
        if (op == "START") {
            requireNotNull(identity).also { check(it.valid()) }
            requireNotNull(serviceInstance).also { require(HEX32.matches(it)) }
            require(renewSeq == 1L)
            json.put("profile", PROFILE).put("iccid", identity.iccid).put("imsi", identity.imsi)
                .put("service_instance", serviceInstance).put("renew_seq", renewSeq)
        } else if (op == "ATTACH" || op == "RENEW") {
            requireNotNull(ownerId).also { require(HEX32.matches(it)) }
            requireNotNull(serviceInstance).also { require(HEX32.matches(it)) }
            json.put("owner_id", ownerId).put("service_instance", serviceInstance)
            if (op == "RENEW") {
                require(renewSeq != null && renewSeq > 0)
                json.put("renew_seq", renewSeq)
            }
        } else if (op == "STOP" && !legacyCleanup) {
            requireNotNull(ownerId).also { require(it.isEmpty() || HEX32.matches(it)) }
            json.put("owner_id", ownerId)
            val token = serviceInstance.orEmpty()
            require(token.isEmpty() || HEX32.matches(token))
            json.put("service_instance", token)
        }
        return json.toString()
    }

    fun answer(raw: String, expectedId: Long, expectedOp: String,
               allowLegacyCleanup: Boolean = false): CloudCustomStatus {
        // A transport error must not echo a malformed reply: native output can be arbitrary.
        check(raw.length <= 32_768) { "Слишком длинный ответ адаптера" }
        val value = try { JSONObject(raw) } catch (_: Exception) { error("Некорректный ответ адаптера") }
        check(value.requiredLong("id") == expectedId && value.get("op") is String &&
            value.getString("op") == expectedOp) { "Ответ не относится к запросу" }
        val protocol = value.requiredInt("protocol")
        check(protocol == 3 || allowLegacyCleanup && protocol == 2 &&
            expectedOp in setOf("PROBE", "STATUS", "STOP")) { "Версия адаптера не совпадает" }
        val profile = if (protocol == 3) value.requiredString("profile") else ""
        check(protocol != 3 || profile == PROFILE) { "Профиль адаптера не совпадает" }
        val leaseUntil = if (protocol == 3) value.requiredLong("lease_until_uptime_ms") else 0L
        val leaseActive = if (protocol == 3) value.requiredBoolean("lease_active") else false
        val accepted = value.requiredBoolean("ok")
        val stage = value.requiredToken("stage")
        check(stage in STAGES) { "Неизвестный этап адаптера" }
        val events = value.optJSONArray("native_events") ?: error("Неполный ответ адаптера")
        check(events.length() <= 128) { "Слишком длинный ответ адаптера" }
        val capabilityArray = value.optJSONArray("capabilities") ?: error("Неполный ответ адаптера")
        val allowedCapabilities = if (protocol == 3) AWAKE_CAPABILITIES else LEGACY_CAPABILITIES
        check(capabilityArray.length() <= allowedCapabilities.size) { "Неполный ответ адаптера" }
        val capabilities = (0 until capabilityArray.length()).map { index ->
            val item = capabilityArray.get(index)
            check(item is String && item in allowedCapabilities) {
                "Неполный ответ адаптера"
            }
            item
        }
        check(capabilities.toSet().size == capabilities.size) { "Неполный ответ адаптера" }
        val ownerId = value.requiredString("owner_id")
        val runtimeId = value.requiredString("runtime_id")
        check(ownerId.isEmpty() || ownerId.matches(Regex("[0-9a-f]{32}"))) { "Неполный ответ адаптера" }
        check(runtimeId.isEmpty() || runtimeId.matches(Regex("[0-9a-f]{12}-[0-9a-f]{12}"))) { "Неполный ответ адаптера" }
        var precedingSeq = -1L
        return CloudCustomStatus(
            pid = value.requiredInt("pid"),
            sessionLive = value.requiredBoolean("session_live"),
            stage = stage,
            code = value.requiredCode("code"),
            updatedElapsedMs = value.requiredLong("updated_elapsed_ms"),
            connectedElapsedMs = value.requiredLong("connected_elapsed_ms"),
            lastRxElapsedMs = value.requiredLong("last_rx_elapsed_ms"),
            lastTxElapsedMs = value.requiredLong("last_tx_elapsed_ms"),
            lastReportElapsedMs = value.requiredLong("last_report_elapsed_ms"),
            nextRetryElapsedMs = value.requiredLong("next_retry_elapsed_ms"),
            attempts = value.requiredLong("attempts"),
            reportsSent = value.requiredLong("reports_sent"),
            statusReplies = value.requiredLong("status_replies"),
            commandsForwarded = value.requiredLong("commands_forwarded"),
            commandsCompleted = value.requiredLong("commands_completed"),
            reconnects = value.requiredLong("reconnects"),
            callbackAgeMs = value.requiredLong("callback_age_ms"),
            events = (0 until events.length()).map { index ->
                val event = events.optJSONObject(index) ?: error("Неполный ответ адаптера")
                val seq = event.requiredLong("seq")
                val at = event.requiredLong("t_ms")
                check(seq > precedingSeq && seq >= 0 && at >= 0) { "Неполный ответ адаптера" }
                precedingSeq = seq
                event.requiredLong("value") // Validate the contract, then discard arbitrary numeric data.
                CloudCustomEvent(seq, at, event.requiredCode("event"))
            },
            ownerId = ownerId,
            runtimeId = runtimeId,
            capabilities = capabilities.toSet(),
            retryable = value.requiredBoolean("retryable"),
            configGeneration = value.requiredLong("config_generation"),
            registrationUncertain = value.requiredBoolean("registration_uncertain"),
            profile = profile,
            leaseUntilUptimeMs = leaseUntil,
            leaseActive = leaseActive,
            protocol = protocol,
        ).also { status ->
            check(status.pid > 0 && status.updatedElapsedMs in 0..MAX_ELAPSED_MS &&
                status.callbackAgeMs in -1..MAX_ELAPSED_MS &&
                listOf(status.connectedElapsedMs, status.lastRxElapsedMs, status.lastTxElapsedMs,
                    status.lastReportElapsedMs, status.nextRetryElapsedMs).all { it in 0..MAX_ELAPSED_MS } &&
                listOf(status.attempts, status.reportsSent, status.statusReplies, status.commandsForwarded,
                    status.commandsCompleted, status.reconnects).all { it >= 0 }) { "Неполный ответ адаптера" }
            check(status.events.all { it.elapsedMs in 0..MAX_ELAPSED_MS &&
                it.elapsedMs <= status.updatedElapsedMs + 1_000L }) {
                "Неполный ответ адаптера"
            }
            check(status.configGeneration >= 0 &&
                (status.ownerId.isEmpty() || status.configGeneration > 0 && status.runtimeId.isNotEmpty())) {
                "Неполный ответ адаптера"
            }
            check(status.leaseUntilUptimeMs in 0..MAX_ELAPSED_MS &&
                (!status.leaseActive || status.ownerId.isNotEmpty() && status.leaseUntilUptimeMs > 0)) {
                "Неполный ответ адаптера"
            }
            check(!status.sessionLive || status.ownerId.isNotEmpty() &&
                (status.stage == "connected" || status.stage == "waiting_data")) {
                "Неподтверждённая сессия адаптера"
            }
            check(protocol != 3 || !status.sessionLive || status.capabilities == AWAKE_CAPABILITIES) {
                "Неполный ответ адаптера"
            }
            if (accepted && expectedOp == "PROBE") check(
                (status.stage == "stopped" && status.code == "owner_absent_confirmed" &&
                    !status.sessionLive && status.ownerId.isEmpty() && status.configGeneration == 0L) ||
                    (status.code == "owner_present" && status.ownerId.isNotEmpty()),
            ) { "Неподтверждённое владение" }
            if (!accepted) throw CloudCustomRejected(status.code, status.retryable)
        }
    }

    private fun JSONObject.requiredString(key: String): String {
        check(has(key) && get(key) is String) { "Неполный ответ адаптера" }
        return getString(key)
    }

    private fun JSONObject.requiredToken(key: String): String = requiredString(key)
        .takeIf { TOKEN.matches(it) } ?: error("Неполный ответ адаптера")

    private fun JSONObject.requiredCode(key: String): String = requiredString(key)
        .takeIf { FIXED_CODE.matches(it) && !IDENTIFIER_RUN.containsMatchIn(it) }
        ?: error("Неполный ответ адаптера")

    private fun JSONObject.requiredLong(key: String): Long {
        check(has(key) && !isNull(key) && get(key) is Number) { "Неполный ответ адаптера" }
        // org.json's getLong truncates 1.5 and can coerce numeric strings. Both are forbidden.
        return get(key).toString().toLongOrNull() ?: error("Неполный ответ адаптера")
    }

    private fun JSONObject.requiredInt(key: String): Int = requiredLong(key).also {
        check(it in Int.MIN_VALUE..Int.MAX_VALUE) { "Неполный ответ адаптера" }
    }.toInt()

    private fun JSONObject.requiredBoolean(key: String): Boolean {
        check(has(key) && get(key) is Boolean) { "Неполный ответ адаптера" }
        return getBoolean(key)
    }
}
