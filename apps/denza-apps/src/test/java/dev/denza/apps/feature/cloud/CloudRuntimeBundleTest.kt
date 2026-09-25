package dev.denza.apps.feature.cloud

import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudRuntimeBundleTest {
    private val jar = "dex candidate".toByteArray()
    private val worker = "arm64 candidate".toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun manifest(): JSONObject {
        val caps = JSONObject()
        listOf("native_registration_codec", "opaque_data_ingest", "control_awake",
            "wake_ack_awake", "timers_awake", "post_login_awake", "heartbeat")
            .forEach { caps.put(it, true) }
        return JSONObject().put("protocol", 3).put("native_protocol", 2).put("product_qualified", false)
            .put("profile", "awake-alpha-v1").put("profile_qualified", true)
            .put("runtime_id", hash(jar).take(12) + "-" + hash(worker).take(12))
            .put("profile_capabilities", caps).put("files", JSONObject()
                .put("cloud-native-proxy.jar", hash(jar)).put("cloud-native-worker", hash(worker)))
    }

    @Test fun compatiblePairIsContentAddressed() {
        assertEquals(hash(jar).take(12) + "-" + hash(worker).take(12),
            CloudRuntimeBundle.verify(manifest().toString(), jar, worker))
    }

    @Test fun candidateMissingClosureOrEitherHashIsRejectedBeforeStaging() {
        val changes: List<(JSONObject) -> Unit> = listOf(
            { it.put("product_qualified", true) },
            { it.put("profile_qualified", false) },
            { it.put("profile", "full-runtime") },
            { it.remove("profile") },
            { it.put("native_protocol", 1) },
            { it.put("protocol", "3") },
            { it.put("protocol", 2) },
            { it.getJSONObject("profile_capabilities").put("wake_ack_awake", false) },
            { it.getJSONObject("profile_capabilities").put("heartbeat", "true") },
            { it.getJSONObject("profile_capabilities").put("post_login_awake", false) },
            { it.getJSONObject("profile_capabilities").remove("timers_awake") },
            { it.getJSONObject("files").put("cloud-native-worker", "0".repeat(64)) },
            { it.getJSONObject("files").put("cloud-native-proxy.jar", "0".repeat(64)) },
            { it.put("runtime_id", "stale") },
        )
        for (change in changes) assertThrows(IllegalStateException::class.java) {
            CloudRuntimeBundle.verify(manifest().also(change).toString(), jar, worker)
        }
    }
}
