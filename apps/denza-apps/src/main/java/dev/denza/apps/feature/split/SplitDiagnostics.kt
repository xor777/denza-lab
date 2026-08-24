package dev.denza.apps.feature.split

import android.os.SystemClock
import android.util.Log

/**
 * Where a line the split product writes actually ends up.
 *
 * Знание об этой прошивке, ради которого канал устроен так: `Log.i` из приложения - строка в
 * пустоту. Два полных open-прогона не оставили НИ ОДНОЙ записи тега `DenzaSplitScreen`, хотя
 * каждый пишущий путь доказуемо исполнился; тот же тег, записанный из shell (`log -t
 * DenzaSplitScreen`), в буфер попадает - logd этой прошивки фильтрует приложения по UID
 * (доказано da09c6a). `Log.i` остаётся - когда он вдруг работает, он самый дешёвый, - но каналом
 * истины он не является.
 *
 * Правка W6 (диагноз v21 Д4-Ф1): shell-зеркало, носившее эти строки через команду операции,
 * удалено - за весь v21 оно не доставило ни одной строки, и его отказ был невидим сам себе.
 * Канал истины фаз продукта - ограниченный внутрипроцессный ринг ниже, который support-экран
 * читает без logcat вовсе; протокол приёмки §12.1 читает фазы ровно оттуда.
 */
internal object SplitDiagnostics {
    const val TAG = "DenzaSplitScreen"

    /** Enough to hold a whole open, its return and their terminals several times over. */
    private const val CAPACITY = 240

    private val lock = Any()
    private val recorded = ArrayDeque<String>()

    fun record(message: String) {
        Log.i(TAG, message)
        val line = "${SystemClock.elapsedRealtime()} $message"
        synchronized(lock) {
            recorded += line
            while (recorded.size > CAPACITY) recorded.removeFirst()
        }
    }

    /** Newest last, for the support screen. */
    fun recent(limit: Int): List<String> = synchronized(lock) { recorded.toList() }.takeLast(limit)
}
