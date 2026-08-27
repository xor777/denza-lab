package dev.denza.apps.feature.split

import android.os.SystemClock
import android.util.Log

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

internal object SplitDiagnostics {
    const val TAG = "DenzaSplitScreen"

    private val ring = SplitDiagnosticRing()

    fun record(message: String, background: Boolean = false) {
        Log.i(TAG, message)
        ring.record(SystemClock.elapsedRealtime(), message, background)
    }

    fun recent(operationLimit: Int, backgroundLimit: Int): List<String> =
        ring.recent(operationLimit, backgroundLimit)
}
