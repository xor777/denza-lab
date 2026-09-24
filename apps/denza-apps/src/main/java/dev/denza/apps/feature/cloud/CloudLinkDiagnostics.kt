package dev.denza.apps.feature.cloud

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.AtomicFile
import android.util.Log
import dev.denza.apps.BuildConfig
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import java.net.Inet4Address
import java.net.Inet6Address
import java.security.MessageDigest

/** Small persisted trace of controlled fields, never payloads, VIN, SIM identifiers or tokens. */
internal class CloudLinkTrace(saved: String = "") {
    private val lines = ArrayDeque<String>()
    private var bytes = 0

    init {
        saved.lineSequence().filter(String::isNotBlank).forEach(::append)
    }

    fun add(at: String, message: String) {
        append("$at ${message.replace(Regex("[\\r\\n\\t]"), " ").take(768)}")
    }

    fun text(): String = lines.joinToString("\n")

    private fun append(line: String) {
        lines.addLast(line)
        bytes += storedBytes(line)
        while (lines.size > MAX_EVENTS || bytes > MAX_BYTES) {
            bytes -= storedBytes(lines.removeFirst())
        }
    }

    // Charge a newline for every entry, including the last, so the serialized history fits.
    private fun storedBytes(line: String) = line.toByteArray(Charsets.UTF_8).size + 1

    companion object {
        private const val MAX_EVENTS = 512
        private const val MAX_BYTES = 256 * 1024
    }
}

/** Used only on the cloud controller's worker. Export failure never changes cloud operation. */
internal object CloudLinkDiagnostics {
    const val REPORT_PATH = "Download/Denza Apps/denza-cloud-report.txt"
    private var trace: CloudLinkTrace? = null
    private var lastState: String? = null
    private var lastExport: String? = null
    private var nativeTrace: CloudLinkTrace? = null
    private var nativeCursor: CloudNativeCursor? = null
    private var nativeSummary = "status=NOT_COLLECTED"
    private var nativeCapturedAt: String? = null
    private var apkSha256: String? = null
    @Volatile var exportStatus = "ещё не сохранён"
        private set

    private fun history(context: Context) = AtomicFile(File(context.filesDir, "cloud-link-history.txt"))
    private fun nativeHistory(context: Context) = AtomicFile(File(context.filesDir, "cloud-native-history.txt"))

    /** Native replies may explain the display, but never control retries or an on/off operation. */
    fun captureNative(context: Context, nowMs: Long, shell: (String) -> String) {
        if (!CloudLinkSettings.needsService(context)) return
        try {
            val file = nativeHistory(context)
            if (nativeTrace == null) {
                val saved = runCatching { String(file.readFully(), Charsets.UTF_8) }.getOrDefault("")
                nativeTrace = CloudLinkTrace(saved)
                nativeCursor = CloudNativeCursor(saved)
            }
            if (nativeCursor?.due(nowMs) != true) return
            val snapshot = CloudNativeLog.parse(shell(CloudNativeLog.command()))
            CloudLinkRuntime.registrationFailure = CloudRegistrationFailure.latest(
                snapshot, CloudLinkRuntime.registrationNotBeforeEpochMs,
                System.currentTimeMillis(), SystemClock.elapsedRealtime(),
            )
            nativeCapturedAt = Instant.now().toString()
            nativeSummary = snapshot.summary()
            val fresh = nativeCursor!!.fresh(snapshot.events)
            if (fresh.isNotEmpty()) {
                val log = nativeTrace!!
                fresh.forEach { log.add(it.at, "pid=${it.pid} tid=${it.tid} ${it.message}") }
                val stream = file.startWrite()
                try {
                    stream.write(log.text().toByteArray(Charsets.UTF_8))
                    file.finishWrite(stream)
                } catch (error: Exception) {
                    file.failWrite(stream)
                    throw error
                }
            }
        } catch (_: Exception) {
            CloudLinkRuntime.registrationFailure = null
            nativeCapturedAt = Instant.now().toString()
            nativeSummary = "status=COLLECTION_FAILED"
        }
    }

    fun record(context: Context, message: String) {
        runCatching {
            val file = history(context)
            val log = trace ?: CloudLinkTrace(runCatching { String(file.readFully(), Charsets.UTF_8) }.getOrDefault(""))
                .also { trace = it }
            log.add(Instant.now().toString(), message)
            val stream = file.startWrite()
            try {
                stream.write(log.text().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }.onFailure { Log.w("DenzaCloudLink", "history write failed", it) }
    }

    fun observe(context: Context, car: CloudCarState, network: CloudNetworkReading) {
        if (!CloudLinkSettings.needsService(context) && trace == null && !history(context).baseFile.exists()) return
        val state = "network=${network.kind} validated=${network.validated} operator=${network.simOperator?.takeIf { it.matches(Regex("[0-9]{5,6}")) }} " +
            "profile=${car.profile} build=${car.buildProfile} apn1Disabled=${car.apn1Disabled} " +
            "apn1=${car.apn1State}/${car.apn1Interface} apn3=${car.apn3State}/${car.apn3Interface} " +
            "apn1Read=${car.apn1ReadSource} apn3Read=${car.apn3ReadSource} " +
            "pid=${car.cloudPid} tcp=${car.connected} step=${car.tcpStep} regError=${car.registrationError} wifiRetention=${car.wifiRetained}"
        if (state != lastState) {
            record(context, "read $state")
            lastState = state
        }
    }

    fun export(context: Context) {
        if (!CloudLinkSettings.needsService(context) && trace == null) return
        if (apkSha256 == null) apkSha256 = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            File(context.applicationInfo.sourceDir).inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("UNKNOWN")
        val body = buildString {
            appendLine("Denza Apps ${BuildConfig.VERSION_NAME} build ${BuildConfig.VERSION_CODE}")
            appendLine("Report format: 3; exported UTC: ${Instant.now()}")
            appendLine("APK SHA-256: $apkSha256")
            appendLine("Firmware: ${Build.FINGERPRINT}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("request=${CloudLinkSettings.request(context)}")
            appendLine("failure=${CloudLinkRuntime.failure} readFailure=${CloudLinkRuntime.readFailure}")
            appendLine("readAgeMs=${CloudLinkRuntime.readAtMs?.let { SystemClock.elapsedRealtime() - it }}")
            appendLine("adapter=${CloudLinkRuntime.adapter}")
            appendLine("Latest controlled state: $lastState")
            appendLine("Network path: ${networkPath(context)}")
            appendLine("Native capture UTC: $nativeCapturedAt $nativeSummary")
            appendLine("History (UTC):")
            appendLine(trace?.text().orEmpty())
            appendLine("Native events (UTC, retained log; events may predate the latest attempt):")
            appendLine(nativeTrace?.text().orEmpty())
        }
        if (body == lastExport) return
        val prefs = context.getSharedPreferences("cloud_link_report", Context.MODE_PRIVATE)
        runCatching {
            val resolver = context.contentResolver
            val saved = prefs.getString("uri", null)?.let(Uri::parse)
            val uri = saved ?: resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "denza-cloud-report.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Denza Apps/")
            })?.also { prefs.edit().putString("uri", it.toString()).apply() }
            checkNotNull(uri)
            checkNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(body.toByteArray(Charsets.UTF_8)) }
            lastExport = body
            exportStatus = REPORT_PATH
        }.onFailure {
            // If the owner deleted the file, recreate our report on the next observation.
            if (it is FileNotFoundException || it is SecurityException) prefs.edit().remove("uri").apply()
            exportStatus = "Не сохранён: ${it.javaClass.simpleName}"
            Log.w("DenzaCloudLink", "report export failed", it)
        }
    }

    /** Route shape only; never SSID, IP/DNS addresses, proxy hostname or device identifiers. */
    private fun networkPath(context: Context): String = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork ?: return@runCatching "NONE"
        val properties = manager.getLinkProperties(network) ?: return@runCatching "UNKNOWN"
        val caps = manager.getNetworkCapabilities(network)
        val iface = properties.interfaceName?.takeIf { it.matches(Regex("[a-zA-Z0-9_.-]{1,32}")) }
        "interface=$iface mtu=${properties.mtu} " +
            "ipv4=${properties.linkAddresses.any { it.address is Inet4Address }} " +
            "ipv6=${properties.linkAddresses.any { it.address is Inet6Address }} " +
            "dnsCount=${properties.dnsServers.size} privateDns=${properties.isPrivateDnsActive} " +
            "proxy=${properties.httpProxy != null} vpn=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}"
    }.getOrDefault("UNKNOWN")
}
