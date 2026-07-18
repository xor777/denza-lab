package ru.adbgw.gateway

import android.content.Context
import org.json.JSONObject

class GatewayStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, true)

    fun setEnabled(value: Boolean): Boolean = preferences.edit().putBoolean(KEY_ENABLED, value).commit()

    fun registration(): RelayRegistration? {
        val raw = preferences.getString(KEY_REGISTRATION, null) ?: return null
        return runCatching { registrationFromJson(JSONObject(raw)) }.getOrNull()
    }

    fun saveRegistration(value: RelayRegistration): Boolean = preferences.edit()
        .putString(KEY_REGISTRATION, registrationToJson(value).toString())
        .commit()

    fun trustedClientKey(): String? = preferences.getString(KEY_TRUSTED_CLIENT_KEY, null)

    fun trustedClientLabel(): String? = preferences.getString(KEY_TRUSTED_CLIENT_LABEL, null)

    fun saveTrustedClient(publicKey: String, label: String): Boolean = preferences.edit()
        .putString(KEY_TRUSTED_CLIENT_KEY, publicKey)
        .putString(KEY_TRUSTED_CLIENT_LABEL, label)
        .commit()

    fun pairingRequestId(): String? = preferences.getString(KEY_PAIRING_REQUEST, null)

    fun getOrCreatePairingRequestId(create: () -> String): String {
        pairingRequestId()?.let { return it }
        val requestId = create()
        check(preferences.edit().putString(KEY_PAIRING_REQUEST, requestId).commit()) {
            "Не удалось сохранить запрос подключения"
        }
        return requestId
    }

    fun pendingPairCommit(): PendingPairCommit? {
        val raw = preferences.getString(KEY_PENDING_PAIR_COMMIT, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            PendingPairCommit(
                publicKey = json.getString("public_key"),
                fingerprint = json.getString("fingerprint"),
            )
        }.getOrNull()
    }

    fun savePendingPairCommit(value: PendingPairCommit): Boolean = preferences.edit()
        .putString(
            KEY_PENDING_PAIR_COMMIT,
            JSONObject()
                .put("public_key", value.publicKey)
                .put("fingerprint", value.fingerprint)
                .toString(),
        )
        .commit()

    fun finalizePairCommit(publicKey: String, label: String): Boolean = preferences.edit()
        .putString(KEY_TRUSTED_CLIENT_KEY, publicKey)
        .putString(KEY_TRUSTED_CLIENT_LABEL, label)
        .remove(KEY_PENDING_PAIR_COMMIT)
        .remove(KEY_PAIRING)
        .remove(KEY_PAIRING_REQUEST)
        .commit()

    fun clearPairing(): Boolean = preferences.edit()
        .remove(KEY_PAIRING)
        .remove(KEY_PAIRING_REQUEST)
        .remove(KEY_PENDING_PAIR_COMMIT)
        .commit()

    fun clearForReenrollment(): Boolean = preferences.edit()
        .remove(KEY_REGISTRATION)
        .remove(KEY_TRUSTED_CLIENT_KEY)
        .remove(KEY_TRUSTED_CLIENT_LABEL)
        .remove(KEY_PAIRING)
        .remove(KEY_PAIRING_REQUEST)
        .remove(KEY_PENDING_PAIR_COMMIT)
        .putBoolean(KEY_ENABLED, true)
        .putBoolean(KEY_KEY_ROTATION_REQUIRED, true)
        .commit()

    fun isKeyRotationRequired(): Boolean = preferences.getBoolean(KEY_KEY_ROTATION_REQUIRED, false)

    fun finishKeyRotation(): Boolean = preferences.edit()
        .putBoolean(KEY_KEY_ROTATION_REQUIRED, false)
        .commit()

    fun pairingWindow(): PairingWindow? {
        val raw = preferences.getString(KEY_PAIRING, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            PairingWindow(
                requestId = json.getString("request_id"),
                code = json.getString("code"),
                expiresAtEpochSeconds = json.getLong("expires_at"),
                attemptsRemaining = json.optInt("attempts_remaining", 5),
            )
        }.getOrNull()
    }

    fun savePairingWindow(value: PairingWindow?): Boolean {
        val editor = preferences.edit()
        if (value == null) {
            editor.remove(KEY_PAIRING)
        } else {
            editor.putString(
                KEY_PAIRING,
                JSONObject()
                    .put("request_id", value.requestId)
                    .put("code", value.code)
                    .put("expires_at", value.expiresAtEpochSeconds)
                    .put("attempts_remaining", value.attemptsRemaining)
                    .toString(),
            )
        }
        return editor.commit()
    }

    companion object {
        private const val PREFS = "car_adb_gateway_state"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_REGISTRATION = "registration"
        private const val KEY_TRUSTED_CLIENT_KEY = "trusted_client_key"
        private const val KEY_TRUSTED_CLIENT_LABEL = "trusted_client_label"
        private const val KEY_PAIRING = "pairing"
        private const val KEY_PAIRING_REQUEST = "pairing_request_id"
        private const val KEY_PENDING_PAIR_COMMIT = "pending_pair_commit"
        private const val KEY_KEY_ROTATION_REQUIRED = "key_rotation_required"

        fun registrationToJson(value: RelayRegistration): JSONObject = JSONObject()
            .put("device_id", value.deviceId)
            .put("device_label", value.deviceLabel)
            .put("relay_device_port", value.relayDevicePort)
            .put("inner_host_key", value.innerHostKey)
            .put("endpoint_mode", value.endpointKind?.relayValue ?: "unknown")
            .put("endpoint_host", value.endpointHost ?: JSONObject.NULL)
            .put("enabled", value.enabled)
            .put("lease_expires_at", value.leaseExpiresAtEpochSeconds)

        fun registrationFromJson(json: JSONObject): RelayRegistration = RelayRegistration(
            deviceId = json.getString("device_id"),
            deviceLabel = json.optString("device_label", "Автомобиль"),
            relayDevicePort = json.getInt("relay_device_port"),
            innerHostKey = json.getString("inner_host_key"),
            endpointKind = when (json.optString("endpoint_mode")) {
                "smart" -> AdbEndpointKind.SmartSocket
                "raw" -> AdbEndpointKind.RawAdbd
                else -> null
            },
            endpointHost = json.optString("endpoint_host").takeIf { it.isNotBlank() && it != "null" },
            enabled = json.optBoolean("enabled", true),
            leaseExpiresAtEpochSeconds = json.optLong("lease_expires_at", 0L),
        )
    }
}
