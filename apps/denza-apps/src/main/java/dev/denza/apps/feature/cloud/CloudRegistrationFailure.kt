package dev.denza.apps.feature.cloud

import java.time.Instant

/** A recent native reply for display only; never a retry, profile or identity control input. */
internal data class CloudRegistrationFailure(val pid: String, val code: Int, val expiresAtMs: Long) {
    fun message(car: CloudCarState?, nowMs: Long): String? =
        if (car?.cloudPid == pid && car.connected == false && nowMs < expiresAtMs) {
            "Облако отклонило регистрацию (код $code)"
        } else null

    companion object {
        private const val FRESH_MS = 90_000L

        fun latest(
            snapshot: CloudNativeLog.Snapshot,
            notBeforeEpochMs: Long,
            nowEpochMs: Long,
            nowMs: Long,
        ): CloudRegistrationFailure? {
            if (snapshot.status != "OK") return null
            val pid = snapshot.pid ?: return null
            // A new attempt or a successful reply supersedes the preceding failure.
            val event = snapshot.events.lastOrNull {
                it.pid == pid && (it.message == "registration_start" || it.message.startsWith("registration_reply code="))
            } ?: return null
            val code = Regex("registration_reply code=([0-9]{1,3})")
                .matchEntire(event.message)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 2..255 } ?: return null
            val at = runCatching { Instant.parse(event.at).toEpochMilli() }.getOrNull() ?: return null
            val age = nowEpochMs - at
            if (at < notBeforeEpochMs || age !in 0 until FRESH_MS) return null
            return CloudRegistrationFailure(pid, code, nowMs + FRESH_MS - age)
        }
    }
}
