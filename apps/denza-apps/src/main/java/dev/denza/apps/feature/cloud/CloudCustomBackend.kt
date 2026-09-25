package dev.denza.apps.feature.cloud

import android.content.Context
import dev.denza.apps.BuildConfig
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.disharebridge.LocalAdbClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

/** App handle to the independent shell guardian. Closing the handle only detaches. */
internal class CloudCustomBackend private constructor(
    private val resident: LocalAdbClient.ResidentSession,
    val processNonce: String,
    private val serviceInstance: String?,
    private val onStartRequest: () -> Unit,
) : CloudCustomLifecycle.Backend {
    private var nextId = 0L
    private var legacyCleanup = false

    override val nonce: String get() = processNonce

    override fun start(identity: CloudIdentity): CloudCustomStatus {
        // A lost START reply can still have taken effect. Reserve sequence one before IPC;
        // a later ATTACH in the same service must renew with sequence two.
        onStartRequest()
        return request("START", identity, renewSeq = 1)
    }
    override fun probe(): CloudCustomStatus {
        val result = try {
            request("PROBE", allowLegacyReply = true)
        } catch (error: Exception) {
            if (error !is CloudCustomRejected || error.code !in
                setOf("unsupported_protocol", "invalid_protocol", "protocol_mismatch")) throw error
            // A protocol-2 guardian may reject the explicit version field. The retry is
            // strictly read-only and can lead only to local cleanup, never v2 START.
            legacyCleanup = true
            try { request("PROBE") } catch (legacyError: Exception) {
                legacyCleanup = false
                throw legacyError
            }
        }
        legacyCleanup = result.protocol == 2
        return result
    }
    override fun attach(ownerId: String): CloudCustomStatus = request("ATTACH", ownerId = ownerId)
    override fun renew(ownerId: String, renewSeq: Long): CloudCustomStatus =
        request("RENEW", ownerId = ownerId, renewSeq = renewSeq)
    override fun status(): CloudCustomStatus = request("STATUS")
    override fun stop(ownerId: String): CloudCustomStatus =
        request("STOP", ownerId = if (serviceInstance == null) "" else ownerId)
    override fun isRunning(): Boolean = resident.isRunning

    private fun request(op: String, identity: CloudIdentity? = null, ownerId: String? = null,
                        renewSeq: Long? = null, allowLegacyReply: Boolean = false): CloudCustomStatus {
        check(op != "START" || BuildConfig.CLOUD_NATIVE_PILOT) { "Адаптер отсутствует в сборке" }
        check(!legacyCleanup || op in setOf("PROBE", "STATUS", "STOP")) { "Версия адаптера не совпадает" }
        val id = ++nextId
        // Never log this line or the raw reply: START contains the private ICCID/IMSI pair.
        val answer = resident.request(CloudCustomProtocol.request(id, op, identity, ownerId,
            serviceInstance, renewSeq, legacyCleanup), timeoutFor(op))
        if (allowLegacyReply && runCatching { JSONObject(answer).optInt("protocol") }.getOrNull() == 2)
            legacyCleanup = true
        return CloudCustomProtocol.answer(answer, id, op, allowLegacyReply || legacyCleanup)
    }

    override fun close() = resident.close()

    companion object {
        private const val JAR_ASSET = "cloud-native-proxy.jar"
        private const val CONTROL_ASSET = "cloud-control-proxy.jar"
        private const val WORKER_ASSET = "cloud-native-worker"
        private const val ROOT = "/data/local/tmp/denza-cloud-native"
        private const val REQUEST_TIMEOUT_MS = 10_000
        private const val START_TIMEOUT_MS = 40_000
        private const val READY_TIMEOUT_MS = 20_000
        private const val CHUNK_CHARS = 8_192

        internal fun timeoutFor(op: String): Int =
            if (op == "START") START_TIMEOUT_MS else REQUEST_TIMEOUT_MS

        fun open(context: Context, serviceInstance: String? = null,
                 onStartRequest: () -> Unit = {}): CloudCustomBackend {
            val app = context.applicationContext
            val installation = CloudCustomInstallation.publish(app)
            val adb = DenzaLocalAdb.client(app)
            val pilot = BuildConfig.CLOUD_NATIVE_PILOT
            val jarAsset = if (pilot) JAR_ASSET else CONTROL_ASSET
            val jar = app.assets.open(jarAsset).use { it.readBytes() }
            val worker = if (pilot) app.assets.open(WORKER_ASSET).use { it.readBytes() } else null
            check(jar.isNotEmpty()) { "Адаптер отсутствует в сборке" }
            val jarHash = jar.sha256()
            val runtime = if (pilot) {
                val manifest = app.assets.open("cloud-native-manifest.json").bufferedReader().use { it.readText() }
                CloudRuntimeBundle.verify(manifest, jar, checkNotNull(worker))
            } else "control-${jarHash.take(12)}"
            val workdir = "$ROOT-$runtime"
            adb.openPersistentShell().use { shell ->
                shell.shell("umask 077; mkdir -p '$workdir'")
                stage(shell::shell, "$workdir/$jarAsset", jar, jarHash, executable = false)
                if (worker != null) stage(shell::shell, "$workdir/$WORKER_ASSET", worker, worker.sha256(), executable = true)
            }
            val nonce = ByteArray(16).also(SecureRandom()::nextBytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val resident = adb.openResidentSession(nonce)
            try {
                resident.start(
                    "CLASSPATH='$workdir/$jarAsset' exec app_process /system/bin " +
                        "dev.denza.tools.runtime.CloudNativeMain " +
                        (if (pilot) "bridge '$workdir' " else "control ") +
                        "${quote(installation.file.absolutePath)} '$nonce'",
                    READY_TIMEOUT_MS,
                )
                return CloudCustomBackend(resident, nonce, serviceInstance, onStartRequest)
            } catch (error: Exception) {
                resident.close()
                throw error
            }
        }

        /** Chunked content-addressed staging keeps shell commands below the ADB packet limit. */
        private fun stage(shell: (String) -> String, path: String, bytes: ByteArray, sha: String, executable: Boolean) {
            if (remoteSha(shell, path) == sha) return
            // Never overwrite a content-addressed executable: an older owner may still map it.
            check(shell("if [ -e '$path' ] || [ -L '$path' ]; then printf occupied; fi").trim().isEmpty()) {
                "Проверка файла адаптера не прошла"
            }
            val encoded = Base64.getEncoder().encodeToString(bytes)
            val temporary = "$path.${java.util.UUID.randomUUID()}.pending"
            shell("umask 077; : > '$temporary.b64'")
            try {
                encoded.chunked(CHUNK_CHARS).forEach { chunk ->
                    shell("printf '%s' '$chunk' >> '$temporary.b64'")
                }
                shell("base64 -d '$temporary.b64' > '$temporary' && chmod ${if (executable) "700" else "600"} '$temporary'")
                check(remoteSha(shell, temporary) == sha) { "Проверка файла адаптера не прошла" }
                shell("mv -n '$temporary' '$path'")
                check(remoteSha(shell, path) == sha) { "Проверка файла адаптера не прошла" }
            } finally {
                runCatching { shell("rm -f '$temporary' '$temporary.b64'") }
            }
        }

        private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        private fun remoteSha(shell: (String) -> String, path: String): String =
            shell("sha256sum '$path' 2>/dev/null").trim().substringBefore(' ')

        private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
            .digest(this).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
