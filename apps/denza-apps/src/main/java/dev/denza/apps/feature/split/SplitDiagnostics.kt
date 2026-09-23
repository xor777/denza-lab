package dev.denza.apps.feature.split

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.denza.apps.feature.vehicle.JournalFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Where a line the split product writes actually ends up.
 *
 * Правка 2026-08-27 (живьём, сборка `ba9eba82`): `Log.i` из приложения В БУФЕР ПОПАДАЕТ. Очистил
 * буфер, запустил приложение - `adb logcat -s DenzaSplitScreen DenzaSplitPickerA11y` отдал ринг с
 * настоящими таймстампами. Прежняя запись здесь утверждала обратное и объясняла это фильтром logd
 * по UID (`da09c6a`); фильтра нет - у того же процесса в буфере лежат ещё и 265 строк
 * `AudioEffect` из нативного кода внутри него, которые фильтр по UID снёс бы вместе со всем
 * остальным. Отсутствие строк в том прогоне приняли за фильтр, а это был не фильтр; чем оно было
 * на самом деле - неизвестно.
 *
 * Каналом истины ринг остаётся всё равно, и причина не изменилась: logcat требует хоста и провода,
 * а support-экран - нет. У владельца в машине adb не бывает. Изменилось только удобство отладки с
 * этого стола: ринг читается без семи тапов и из-под блокирующей заслонки.
 *
 * Правка W6 (диагноз v21 Д4-Ф1): shell-зеркало, носившее эти строки через команду операции,
 * удалено - за весь v21 оно не доставило ни одной строки, и его отказ был невидим сам себе.
 * Канал истины фаз продукта - ограниченный внутрипроцессный ринг ниже, который support-экран
 * читает без logcat вовсе; протокол приёмки §12.1 читает фазы ровно оттуда.
 *
 * Правка Ф3 волны 16: **два ринга, а не один.** Фоновая сверка ходит к машине каждые несколько
 * секунд и пишет о себе; на приёмке v32 это вытеснило строки самой операции меньше чем за минуту,
 * и приёмщик потерял два круга замеров. Строки операции и фоновый шум больше не соревнуются за
 * одни и те же слоты: у каждого свой ринг и своя доля экрана, и повторяющаяся фоновая строка
 * схлопывается в одну со счётчиком вместо того, чтобы занимать место числом своих повторов.
 */
/**
 * The two rings themselves, with no platform under them so they can be tested for what they are.
 *
 * Правка Ф3 волны 16: операции и фон больше не соревнуются за одни и те же слоты. На приёмке v32
 * фоновая сверка вытеснила строки измеряемой операции меньше чем за минуту, и приёмщик потерял
 * два круга замеров. Повторяющаяся фоновая строка схлопывается в одну со счётчиком.
 */
internal class SplitDiagnosticRing(
    private val operationCapacity: Int = OPERATION_CAPACITY,
    private val backgroundCapacity: Int = BACKGROUND_CAPACITY,
) {
    private val lock = Any()
    private val operations = ArrayDeque<String>()
    private val background = ArrayDeque<Repeated>()

    /** A background line and how many times in a row it has just said the same thing. */
    private class Repeated(val atMs: Long, val message: String) {
        var repeats: Int = 1
    }

    fun record(atMs: Long, message: String, background: Boolean) = synchronized(lock) {
        if (background) recordBackground(atMs, message) else recordOperation(atMs, message)
    }

    /**
     * Newest last: the operations first, then what the background said.
     *
     * The two limits are separate on purpose. A screen holds a fixed number of lines, and the
     * question it is opened to answer is always about an operation; the background lane is there
     * to show that the product is still watching, not to be read line by line.
     */
    fun recent(operationLimit: Int, backgroundLimit: Int): List<String> = synchronized(lock) {
        operations.toList().takeLast(operationLimit) +
            this.background.toList().takeLast(backgroundLimit).map(::rendered)
    }

    private fun rendered(line: Repeated): String =
        if (line.repeats == 1) {
            "${line.atMs} ${line.message}"
        } else {
            "${line.atMs} ${line.message} (x${line.repeats})"
        }

    private fun recordOperation(atMs: Long, message: String) {
        operations += "$atMs $message"
        while (operations.size > operationCapacity) operations.removeFirst()
    }

    private fun recordBackground(atMs: Long, message: String) {
        // The reconcile says the same thing about an unchanged world for as long as it stays
        // unchanged. That is worth one line and a count, never one line each.
        val last = background.lastOrNull()
        if (last != null && last.message == message) {
            last.repeats += 1
            return
        }
        background += Repeated(atMs, message)
        while (background.size > backgroundCapacity) background.removeFirst()
    }

    internal companion object {
        /** Enough to hold several whole opens, their returns and their terminals. */
        const val OPERATION_CAPACITY = 200

        /** The background lane needs only enough to show it is alive and what it last said. */
        const val BACKGROUND_CAPACITY = 40
    }
}

/**
 * The ring on disk, so a split run can still be read after the fact (2026-09-23).
 *
 * The car's main log buffer is 256 KiB and turns over in about twelve seconds - the driver
 * monitor alone writes some 180 lines a second - and the ring lives only as long as its process.
 * This file keeps what the ring is given, with wall-clock times that line up with logcat, and
 * never more than [capBytes] twice over: at the cap the current file becomes the previous one and
 * the previous one is dropped. A background line that only repeats the one before it is not
 * written again. Two processes append to the same pair; a rotation both of them see at once can
 * cost the previous file, never the cap.
 *
 * Read: `adb shell run-as dev.denza.apps cat files/split-journal.1.log files/split-journal.log`.
 */
internal class SplitDiagnosticJournal(
    private val directory: File,
    private val processTag: String,
    private val capBytes: Long = CAP_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var lastBackground: String? = null

    fun append(message: String, background: Boolean) {
        synchronized(lock) {
            if (background) {
                if (message == lastBackground) return
                lastBackground = message
            } else {
                lastBackground = null
            }
            val current = File(directory, CURRENT)
            if (current.length() >= capBytes) current.renameTo(File(directory, PREVIOUS))
            val lane = if (background) " bg" else ""
            JournalFile.append(current, "${time.format(Date(now()))} $processTag$lane $message\n")
        }
    }

    internal companion object {
        /** Each of the two files; a split open writes a few kilobytes, so this is days of use. */
        const val CAP_BYTES = 256L * 1024
        const val CURRENT = "split-journal.log"
        const val PREVIOUS = "split-journal.1.log"
    }
}

internal object SplitDiagnostics {
    const val TAG = "DenzaSplitScreen"

    private val ring = SplitDiagnosticRing()

    @Volatile
    private var journal: SplitDiagnosticJournal? = null
    private val writer: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "split-journal").apply { isDaemon = true }
        }
    }

    /** Every process that records gets the file; before this the lines reach the ring only. */
    fun attach(context: Context, processName: String?) {
        val tag = processName?.substringAfter(':', "main")?.ifEmpty { "main" } ?: "main"
        journal = SplitDiagnosticJournal(context.filesDir, tag)
    }

    fun record(message: String, background: Boolean = false) {
        Log.i(TAG, message)
        ring.record(SystemClock.elapsedRealtime(), message, background)
        journal?.let { file ->
            // Off the caller's thread: an open is timed, and a disk write is not its business.
            writer.execute { runCatching { file.append(message, background) } }
        }
    }

    fun recent(operationLimit: Int, backgroundLimit: Int): List<String> =
        ring.recent(operationLimit, backgroundLimit)
}
