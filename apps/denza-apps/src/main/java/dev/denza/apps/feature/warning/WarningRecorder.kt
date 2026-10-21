package dev.denza.apps.feature.warning

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import dev.denza.apps.BuildConfig
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * «Предупреждения машины»: the cluster's warning slot, recorded for the life of the process.
 *
 * It asks the car nothing of its own. `VehicleTelemetryHub` reads `TEXT_WARNING` in the sweep it
 * already runs for the energy figures - a tenth of a second while a screen draws them, a second
 * otherwise - and hands each answer, or its absence, to [sample]. So the recording runs exactly as
 * long as the app's link to the car does, with the page closed, and keeps no wake lock and no
 * service of its own: a car that sleeps is a gap in the record, written as one. A change shorter
 * than one sweep can pass unseen; a popup the cluster shows lasts seconds.
 *
 * The journal opens on the first call, on whichever background thread makes it, so the
 * application's start never reads the files.
 */
internal class WarningRecorder private constructor(private val app: Context) {

    private val journal = WarningJournal(File(app.filesDir, DIR), onFailure = { why ->
        Log.w(TAG, "Журнал предупреждений: $why")
    })
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "denza-warnings").apply { isDaemon = true }
    }
    private var opened = false

    private val mutable = MutableStateFlow(WarningLogState())
    val state: StateFlow<WarningLogState> = mutable.asStateFlow()

    private val namesState = MutableStateFlow(WarningNames.EMPTY)
    val names: StateFlow<WarningNames> = namesState.asStateFlow()

    /** What the last export did, said under the buttons until the record changes. */
    private val exportState = MutableStateFlow<String?>(null)
    val exported: StateFlow<String?> = exportState.asStateFlow()

    /** Whether the sweep should ask for the slot at all: off means not one extra call. */
    fun wantsSlot(): Boolean {
        ensureOpen()
        return journal.isEnabled
    }

    /** One sweep's answer for the slot, or null when the car did not give one. The hub's thread. */
    fun sample(code: Int?) {
        ensureOpen()
        if (journal.sample(code, System.currentTimeMillis(), SystemClock.elapsedRealtime())) publish()
    }

    /** The page is open: the record and the car's names for its codes, off the main thread. */
    fun load() = worker.execute {
        ensureOpen()
        publish()
        if (namesState.value.size == 0) namesState.value = readNames()
    }

    fun setEnabled(on: Boolean) = worker.execute {
        ensureOpen()
        journal.setEnabled(on, System.currentTimeMillis())
        publish()
    }

    fun clear() = worker.execute {
        ensureOpen()
        journal.clear(System.currentTimeMillis())
        exportState.value = null
        publish()
    }

    /** The record as a text file in «Загрузки/Denza Apps», a new one each time. */
    fun export() = worker.execute {
        ensureOpen()
        if (namesState.value.size == 0) namesState.value = readNames()
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val name = "denza-warnings-${FILE_STAMP.format(now.atZone(zone))}.txt"
        val body = WarningExport.text(
            header = listOf(
                "Denza Apps ${BuildConfig.VERSION_NAME} · сборка ${BuildConfig.VERSION_CODE} · ${WarningText.TITLE}",
                "Выгружено: ${now.atZone(zone)}",
                "Прошивка: ${Build.FINGERPRINT}",
                "Источник: autoservice getInt 1007 / 0x26100008, в каждом проходе опроса машины",
            ),
            state = journal.state(),
            events = journal.allEvents(),
            names = namesState.value,
            zone = zone,
        )
        exportState.value = runCatching {
            val resolver = app.contentResolver
            val uri = checkNotNull(
                resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Denza Apps/")
                    },
                ),
            )
            checkNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(body.toByteArray(Charsets.UTF_8)) }
            "Сохранено: Загрузки/Denza Apps/$name"
        }.getOrElse { error ->
            Log.w(TAG, "warning export failed", error)
            "Не сохранено: ${error.javaClass.simpleName}"
        }
    }

    @Synchronized
    private fun ensureOpen() {
        if (opened) return
        opened = true
        mutable.value = journal.open(System.currentTimeMillis())
    }

    private fun publish() {
        mutable.value = journal.state()
    }

    private fun readNames(): WarningNames {
        val dictionary = runCatching { File(DICTIONARY).readText() }
            .onFailure { Log.w(TAG, "словарь предупреждений не прочитан: ${it.javaClass.simpleName}") }
            .getOrNull()
        val russian = runCatching { app.assets.open(TRANSLATION).bufferedReader().use { it.readText() } }.getOrNull()
        return WarningNames.of(dictionary, russian)
    }

    companion object {
        private const val TAG = "DenzaWarnings"
        private const val DIR = "warnings"

        /** The cluster's popup dictionary on this firmware; world-readable on the Z9GT. */
        const val DICTIONARY = "/system/bin/config/popup_nads.json"
        const val TRANSLATION = "warnings/popup_ru.tsv"

        private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        @Volatile
        private var instance: WarningRecorder? = null

        fun of(context: Context): WarningRecorder = instance ?: synchronized(this) {
            instance ?: WarningRecorder(context.applicationContext).also { instance = it }
        }
    }
}
