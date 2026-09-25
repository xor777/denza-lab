package dev.denza.apps.feature.cloud

import android.content.ContentValues
import android.content.ContentUris
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
import dev.denza.apps.DenzaAppRepository
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import java.net.Inet4Address
import java.net.Inet6Address
import java.security.MessageDigest
import java.util.concurrent.Executors

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
    private const val EXPORT_PREFS = "cloud_link_report_settings"
    private const val EXPORT_ENABLED = "export_enabled"
    private var trace: CloudLinkTrace? = null
    private var lastState: String? = null
    private var lastExport: String? = null
    private var nativeTrace: CloudLinkTrace? = null
    private var nativeCursor: CloudNativeCursor? = null
    private var nativeSummary = "status=NOT_COLLECTED"
    private var nativeCapturedAt: String? = null
    private var customHistoryQueue: CloudCustomHistoryQueue? = null
    private var customProcess: String? = null
    private var customEventSeq = -1L
    private var customSummary = "ещё не было"
    private var apkSha256: String? = null
    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "denza-cloud-report").apply { isDaemon = true }
    }
    private val reportQueue = CloudReportQueue(
        writerExecutor,
        onWriteFailure = { error ->
            exportStatus = "Не сохранён: ${error.javaClass.simpleName}"
            Log.w("DenzaCloudLink", "report worker failed: ${error.javaClass.simpleName}")
        },
        onWriteComplete = { DenzaAppRepository.refresh() },
    )
    private val switchLock = Any()
    @Volatile var exportSwitchPending: Boolean? = null
        private set
    @Volatile var exportSwitchError: String? = null
        private set
    @Volatile var exportStatus = "ещё не сохранён"
        private set

    fun exportEnabled(context: Context): Boolean =
        context.getSharedPreferences(EXPORT_PREFS, Context.MODE_PRIVATE).getBoolean(EXPORT_ENABLED, false)

    fun exportTicket(): Long = reportQueue.ticket()

    fun reportStatus(context: Context): String = when (exportSwitchPending) {
        true -> "включается"
        false -> "выключается"
        null -> if (exportEnabled(context)) exportStatus else "выключено"
    }

    /** Invalidates queued writes immediately; preference I/O waits on the dedicated writer. */
    fun setExportEnabledAsync(context: Context, enabled: Boolean, onSettled: () -> Unit): Boolean {
        synchronized(switchLock) {
            if (exportSwitchPending != null) return false
            exportSwitchPending = enabled
            exportSwitchError = null
        }
        val app = context.applicationContext
        val accepted = reportQueue.change(
            commit = {
                app.getSharedPreferences(EXPORT_PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(EXPORT_ENABLED, enabled).commit()
            },
            settled = { saved ->
                synchronized(switchLock) {
                    if (saved) exportStatus = if (enabled) "ожидает записи" else "выключено"
                    else exportSwitchError = "Не удалось сохранить настройку отчёта"
                    exportSwitchPending = null
                }
                onSettled()
            },
        )
        if (!accepted) synchronized(switchLock) { exportSwitchPending = null }
        return accepted
    }

    private fun history(context: Context) = AtomicFile(File(context.filesDir, "cloud-link-history.txt"))
    private fun nativeHistory(context: Context) = AtomicFile(File(context.filesDir, "cloud-native-history.txt"))
    private fun customHistory(context: Context) = AtomicFile(File(context.filesDir, "cloud-custom-history.txt"))

    /** Constructed only by the controller thread; the queue serializes storage on the report worker. */
    private fun customQueue(context: Context): CloudCustomHistoryQueue =
        customHistoryQueue ?: CloudCustomHistoryQueue(
            executor = writerExecutor,
            load = {
                try { String(customHistory(context).readFully(), Charsets.UTF_8) }
                catch (_: FileNotFoundException) { "" }
            },
            save = { value ->
                val file = customHistory(context)
                val stream = file.startWrite()
                try {
                    stream.write(value.toByteArray(Charsets.UTF_8))
                    file.finishWrite(stream)
                } catch (error: Exception) {
                    file.failWrite(stream)
                    throw error
                }
            },
            onFailure = { Log.w("DenzaCloudLink", "custom history write failed: ${it.javaClass.simpleName}") },
        ).also { customHistoryQueue = it }

    /** Bounded, redacted native events, deduplicated per guardian owner and sequence. */
    fun observeCustom(context: Context, nonce: String, status: CloudCustomStatus) {
        val process = "$nonce:${status.pid}"
        if (process != customProcess) {
            customProcess = process
            customEventSeq = -1L
        }
        customSummary = "pid=${status.pid} live=${status.sessionLive} stage=${status.stage} code=${status.code} " +
            "attempts=${status.attempts} reports=${status.reportsSent} replies=${status.statusReplies} " +
            "commands=${status.commandsForwarded}/${status.commandsCompleted} reconnects=${status.reconnects} " +
            "callbackAgeMs=${status.callbackAgeMs} runtime=${status.runtimeId} generation=${status.configGeneration} " +
            "retryable=${status.retryable} registrationUncertain=${status.registrationUncertain} " +
            "capabilities=${status.capabilities.sorted().joinToString(",")}"
        val fresh = status.events.filter { it.seq > customEventSeq }.sortedBy { it.seq }
        if (fresh.isEmpty()) return
        val queue = customQueue(context.applicationContext)
        queue.addAll(fresh.map { event ->
            Instant.now().toString() to ("pid=${status.pid} seq=${event.seq} t_ms=${event.elapsedMs} " +
                "event=${event.event}")
        })
        customEventSeq = fresh.last().seq
    }

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
        if (!CloudLinkSettings.needsService(context) && !exportEnabled(context) &&
            trace == null && !history(context).baseFile.exists()) return
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

    private data class ReportSnapshot(
        val customMode: Boolean,
        val request: String,
        val failure: String?,
        val readFailure: String?,
        val readAgeMs: Long?,
        val customReadAgeMs: Long?,
        val adapter: CloudLinkReport.Adapter?,
        val lastState: String?,
        val customSummary: String,
        val networkPath: String,
        val nativeCapturedAt: String?,
        val nativeSummary: String,
        val history: String,
        val nativeEvents: String,
        val firmware: String,
        val model: String,
        val capturedAt: String,
    )

    /** Called on the cloud controller: copy its controlled state, then return before file I/O. */
    fun requestExport(context: Context, ticket: Long) {
        val app = context.applicationContext
        reportQueue.capture(ticket, exportEnabled(app), exportSwitchPending != null) {
            val now = SystemClock.elapsedRealtime()
            val custom = CloudLinkSettings.mode(app) == CloudSimMode.CUSTOM
            val snapshot = ReportSnapshot(
                customMode = custom,
                request = CloudLinkSettings.request(app).toString(),
                failure = CloudLinkRuntime.failure,
                readFailure = CloudLinkRuntime.readFailure,
                readAgeMs = CloudLinkRuntime.readAtMs?.let { now - it },
                customReadAgeMs = CloudLinkRuntime.customReadAtMs?.let { now - it },
                adapter = CloudLinkRuntime.adapter,
                lastState = lastState,
                customSummary = customSummary,
                networkPath = networkPath(app),
                nativeCapturedAt = nativeCapturedAt,
                nativeSummary = nativeSummary,
                history = trace?.text().orEmpty(),
                nativeEvents = if (custom) customHistoryQueue?.snapshot().orEmpty() else nativeTrace?.text().orEmpty(),
                firmware = Build.FINGERPRINT,
                model = Build.MODEL,
                capturedAt = Instant.now().toString(),
            )
            val write: () -> Unit = { writeReport(app, snapshot) }
            write
        }
    }

    /** Dedicated report worker only. One MediaStore URI is reused and never deleted by OFF. */
    private fun writeReport(context: Context, snapshot: ReportSnapshot) {
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
            appendLine("Report format: ${if (snapshot.customMode) 4 else 3}; captured UTC: ${snapshot.capturedAt}")
            appendLine("APK SHA-256: $apkSha256")
            appendLine("Firmware: ${snapshot.firmware}")
            appendLine("Model: ${snapshot.model}")
            appendLine("request=${snapshot.request}")
            appendLine(if (snapshot.customMode) "failure=${snapshot.failure}" else
                "failure=${snapshot.failure} readFailure=${snapshot.readFailure}")
            if (snapshot.customMode) {
                appendLine("mode=CUSTOM")
                appendLine("customReadAgeMs=${snapshot.customReadAgeMs}")
                appendLine("custom=${snapshot.customSummary}")
            } else {
                appendLine("readAgeMs=${snapshot.readAgeMs}")
                appendLine("adapter=${snapshot.adapter}")
                appendLine("Latest controlled state: ${snapshot.lastState}")
            }
            appendLine("Network path: ${snapshot.networkPath}")
            if (!snapshot.customMode) appendLine("Native capture UTC: ${snapshot.nativeCapturedAt} ${snapshot.nativeSummary}")
            appendLine("History (UTC):")
            appendLine(snapshot.history)
            if (snapshot.customMode) {
                appendLine("Custom native events (UTC, bounded and redacted):")
                appendLine(snapshot.nativeEvents)
            } else {
                appendLine("Native events (UTC, retained log; events may predate the latest attempt):")
                appendLine(snapshot.nativeEvents)
            }
        }
        if (body == lastExport) return
        val prefs = context.getSharedPreferences("cloud_link_report", Context.MODE_PRIVATE)
        runCatching {
            val resolver = context.contentResolver
            val saved = prefs.getString("uri", null)?.let(Uri::parse)
            val reportName = "denza-cloud-report.txt"
            val reportDirectory = Environment.DIRECTORY_DOWNLOADS + "/Denza Apps/"
            val target = CloudReportTarget(
                find = {
                    val id = MediaStore.MediaColumns._ID
                    val cursor = checkNotNull(resolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(id),
                        "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                        arrayOf(reportName, reportDirectory),
                        null,
                    )) { "Поиск отчёта недоступен" }
                    cursor.use {
                        buildList {
                            while (it.moveToNext() && size < 2) {
                                add(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                    it.getLong(it.getColumnIndexOrThrow(id))))
                            }
                        }
                    }
                },
                insert = {
                    checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, reportName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, reportDirectory)
                    })) { "Не удалось создать отчёт" }
                },
                inspect = { uri ->
                    val cursor = checkNotNull(resolver.query(uri,
                        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
                        null, null, null)) { "Проверка имени отчёта недоступна" }
                    cursor.use {
                        if (!it.moveToFirst()) CloudReportTarget.Match.MISSING
                        else if (it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) == reportName &&
                            it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)) == reportDirectory)
                            CloudReportTarget.Match.EXACT
                        else CloudReportTarget.Match.DIFFERENT
                    }
                },
                discardFresh = { uri -> resolver.delete(uri, null, null) },
                write = { uri ->
                    val stream = resolver.openOutputStream(uri, "wt")
                        ?: throw FileNotFoundException("Отчёт недоступен для записи")
                    stream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                },
                remember = { uri -> prefs.edit().putString("uri", uri.toString()).apply() },
            )
            target.publish(saved)
            lastExport = body
            exportStatus = REPORT_PATH
        }.onFailure {
            // Preserve the saved URI and any old file; an inaccessible report is not deletion.
            exportStatus = "Не сохранён: ${it.javaClass.simpleName}"
            Log.w("DenzaCloudLink", "report export failed: ${it.javaClass.simpleName}")
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
