package dev.denza.apps.feature.cloud

import java.security.MessageDigest
import org.json.JSONObject

/** Asset compatibility is checked before staging or touching the car's cloud state. */
internal object CloudRuntimeBundle {
    const val PROFILE = "awake-alpha-v1"
    private val required = setOf("native_registration_codec", "opaque_data_ingest", "control_awake",
        "wake_ack_awake", "timers_awake", "post_login_awake", "heartbeat")

    fun verify(manifest: String, jar: ByteArray, worker: ByteArray): String {
        check(manifest.length <= 65_536 && jar.size in 1..4_194_304 && worker.size in 1..2_097_152) {
            "Проверка файла адаптера не прошла"
        }
        try {
            val value = JSONObject(manifest)
            check(value.get("protocol") == 3 && value.get("native_protocol") == 2)
            check(value.get("profile") == PROFILE && value.get("profile_qualified") == true)
            check(value.get("product_qualified") == false)
            val caps = value.getJSONObject("profile_capabilities")
            check(required.all { caps.get(it) == true })
            val jarHash = hash(jar)
            val workerHash = hash(worker)
            val files = value.getJSONObject("files")
            check(files.get("cloud-native-proxy.jar") == jarHash && files.get("cloud-native-worker") == workerHash)
            val runtime = "${jarHash.take(12)}-${workerHash.take(12)}"
            check(value.get("runtime_id") == runtime)
            return runtime
        } catch (_: Exception) {
            error("Проверка файла адаптера не прошла")
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
