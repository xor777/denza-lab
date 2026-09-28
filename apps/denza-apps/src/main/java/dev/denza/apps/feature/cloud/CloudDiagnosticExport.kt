package dev.denza.apps.feature.cloud

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.AtomicFile
import dev.denza.apps.BuildConfig
import dev.denza.apps.adb.DenzaLocalAdb
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Explicit local export, independent of the cloud controller and its retry policy. */
object CloudDiagnosticExport {
    const val FILE_NAME = "denza-cloud-u9.zip"
    const val REPORT_PATH = "Download/Denza Apps/$FILE_NAME"
    const val IDLE_MESSAGE = "ZIP в Download/Denza Apps/. Повторный сбор заменит файл."
    data class State(val busy: Boolean = false, val message: String = IDLE_MESSAGE)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val collecting = AtomicBoolean()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "cloud-diagnostic-export").apply { isDaemon = true }
    }

    fun collect(context: Context) {
        if (!collecting.compareAndSet(false, true)) return
        val app = context.applicationContext
        mutableState.value = State(true, "Собираем диагностику…")
        executor.execute {
            val temporary = File(app.cacheDir, "cloud-diagnostic.zip.tmp")
            try {
                val client = DenzaLocalAdb.client(app)
                val result = CloudDiagnosticBundle { client.shell(it, 12_000) }.collect(report(app)) {
                    // Fixed names only; no shell output or exception text enters the UI.
                    mutableState.value = State(true, "Собираем диагностику: $it")
                }
                temporary.outputStream().use { CloudDiagnosticBundle.writeZip(result.files, it) }
                save(app, temporary)
                val partial = if (result.unavailable > 0) " Часть данных недоступна — это отмечено в архиве." else ""
                mutableState.value = State(message = "Сохранено: $REPORT_PATH.$partial")
            } catch (error: Exception) {
                mutableState.value = State(message = "Не удалось сохранить архив (${error.javaClass.simpleName}). Повторите сбор.")
            } finally {
                temporary.delete()
                collecting.set(false)
            }
        }
    }

    private fun report(app: Context): String = buildString {
        appendLine("Denza Apps ${BuildConfig.VERSION_NAME} build ${BuildConfig.VERSION_CODE}")
        appendLine("Exported UTC: ${Instant.now()}")
        val digest = MessageDigest.getInstance("SHA-256")
        File(app.applicationInfo.sourceDir).inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        appendLine("APK SHA-256: ${digest.digest().joinToString("") { "%02x".format(it) }}")
        appendLine("Firmware: ${Build.FINGERPRINT}")
        appendLine("Model: ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT}")
        appendLine("Request: ${CloudLinkSettings.request(app)}")
        appendLine("Network: ${runCatching { CloudNetwork.reading(app).toString() }.getOrElse { it.javaClass.simpleName }}")
        for (name in listOf("cloud-link-history.txt", "cloud-native-history.txt")) {
            appendLine("\n$name (retained history; may predate collection):")
            val file = File(app.filesDir, name)
            appendLine(runCatching {
                if (file.length() > 512 * 1024) "TOO_LARGE"
                else String(AtomicFile(file).readFully(), Charsets.UTF_8)
            }.getOrElse { "Unavailable: ${it.javaClass.simpleName}" })
        }
    }

    private fun save(app: Context, source: File) {
        val resolver = app.contentResolver
        val prefs = app.getSharedPreferences("cloud_diagnostic_zip", Context.MODE_PRIVATE)
        val saved = prefs.getString("uri", null)?.let(Uri::parse)
        if (saved != null) {
            try {
                checkNotNull(resolver.openOutputStream(saved, "wt")).use { output ->
                    source.inputStream().use { it.copyTo(output) }
                }
                check(resolver.update(saved, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
                return
            } catch (_: FileNotFoundException) {
                prefs.edit().remove("uri").commit()
            } catch (_: SecurityException) {
                prefs.edit().remove("uri").commit()
            }
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Denza Apps/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            // Remember a pending row too, so an interrupted export is reused on the next press.
            check(prefs.edit().putString("uri", uri.toString()).commit())
            checkNotNull(resolver.openOutputStream(uri, "wt")).use { output ->
                source.inputStream().use { it.copyTo(output) }
            }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            prefs.edit().remove("uri").commit()
            throw error
        }
    }
}
